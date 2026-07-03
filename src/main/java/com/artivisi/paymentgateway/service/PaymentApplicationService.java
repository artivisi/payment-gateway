package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.entity.Charge;
import com.artivisi.paymentgateway.entity.ChargeStatus;
import com.artivisi.paymentgateway.entity.EscrowAccount;
import com.artivisi.paymentgateway.entity.Payment;
import com.artivisi.paymentgateway.entity.PaymentStatus;
import com.artivisi.paymentgateway.entity.VirtualAccount;
import com.artivisi.paymentgateway.entity.VirtualAccountStatus;
import com.artivisi.paymentgateway.entity.WebhookEventType;
import com.artivisi.paymentgateway.exception.InvalidPaymentException;
import com.artivisi.paymentgateway.exception.NotFoundException;
import com.artivisi.paymentgateway.repository.ChargeRepository;
import com.artivisi.paymentgateway.repository.PaymentRepository;
import com.artivisi.paymentgateway.config.ReversalProperties;
import com.artivisi.paymentgateway.repository.VirtualAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Applies a received payment to a charge. The charge row is pessimistically locked so
 * concurrent payments across sibling banks are serialized. Owns the single-debt invariant:
 * shared cumulative, first-paid-cancels-siblings, fail-loud on overpayment/mismatch.
 *
 * <p>Adapters call {@link #apply} on a bank payment notification. Phase 1 drives it directly.
 */
@Service
public class PaymentApplicationService {

    private final VirtualAccountRepository virtualAccountRepository;
    private final ChargeRepository chargeRepository;
    private final PaymentRepository paymentRepository;
    private final WebhookService webhookService;
    private final ReversalProperties reversalProperties;
    private final AuditService auditService;

    public PaymentApplicationService(VirtualAccountRepository virtualAccountRepository,
                                     ChargeRepository chargeRepository,
                                     PaymentRepository paymentRepository,
                                     WebhookService webhookService,
                                     ReversalProperties reversalProperties,
                                     AuditService auditService) {
        this.virtualAccountRepository = virtualAccountRepository;
        this.chargeRepository = chargeRepository;
        this.paymentRepository = paymentRepository;
        this.webhookService = webhookService;
        this.reversalProperties = reversalProperties;
        this.auditService = auditService;
    }

    @Transactional
    public Payment apply(EscrowAccount escrow, String vaNumber, BigDecimal amount,
                         String bankReference, Instant transactionTime) {
        // Numbers are reusable: several generations may exist, at most one ACTIVE.
        List<VirtualAccount> generations = virtualAccountRepository
                .findByEscrowAccountIdAndVaNumberOrderByCreatedAtDesc(escrow.getId(), vaNumber);
        if (generations.isEmpty()) {
            throw new NotFoundException("VA not found in escrow " + escrow.getCode() + ": " + vaNumber);
        }

        // Idempotency: a replayed notification (against any generation) returns the recorded payment.
        for (VirtualAccount generation : generations) {
            Optional<Payment> existing = paymentRepository
                    .findByVirtualAccountIdAndBankReference(generation.getId(), bankReference);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        // Prefer the ACTIVE generation; otherwise the newest one carries the failure semantics
        // (paid/cancelled/expired) reported back to the bank.
        VirtualAccount va = generations.stream()
                .filter(g -> g.getStatus() == VirtualAccountStatus.ACTIVE)
                .findFirst()
                .orElse(generations.getFirst());

        // Pessimistic lock on the charge serializes sibling payments.
        Charge charge = chargeRepository.lockById(va.getCharge().getId())
                .orElseThrow(() -> new NotFoundException("charge not found for VA " + vaNumber));

        if (amount == null || amount.signum() <= 0) {
            throw new InvalidPaymentException("payment amount must be positive");
        }
        if (charge.getStatus() == ChargeStatus.CANCELLED) {
            throw new InvalidPaymentException("charge is cancelled");
        }
        if (charge.getStatus() == ChargeStatus.EXPIRED
                || (charge.getExpiresAt() != null && Instant.now().isAfter(charge.getExpiresAt()))) {
            throw new InvalidPaymentException("charge has expired");
        }
        if (va.getStatus() == VirtualAccountStatus.CANCELLED
                || va.getStatus() == VirtualAccountStatus.EXPIRED) {
            throw new InvalidPaymentException("virtual account is " + va.getStatus());
        }

        switch (charge.getChargeType()) {
            case CLOSED -> applyClosed(charge, va, amount);
            case INSTALLMENT -> applyInstallment(charge, va, amount);
            case OPEN -> applyOpen(charge, amount);
        }

        Payment payment = new Payment();
        payment.setVirtualAccount(va);
        payment.setCharge(charge);
        payment.setAmount(amount);
        payment.setBankReference(bankReference);
        payment.setTransactionTime(transactionTime);
        payment.setStatus(PaymentStatus.ACCEPTED);
        Payment saved = paymentRepository.save(payment);

        // Transactional outbox: enqueue notifications atomically with the payment.
        webhookService.enqueue(charge, saved, WebhookEventType.PAYMENT_RECEIVED);
        if (charge.getStatus() == ChargeStatus.PAID) {
            webhookService.enqueue(charge, saved, WebhookEventType.CHARGE_PAID);
        }
        auditService.record("PAYMENT_APPLIED", "Payment", saved.getId(),
                "va=" + vaNumber + " amount=" + amount + " ref=" + bankReference);
        return saved;
    }

    /**
     * Reverses a previously accepted payment within the configured window. Subtracts from the
     * shared cumulative and re-opens the charge; if the payment had settled the charge (siblings
     * cancelled), reactivates the siblings. Idempotent if the payment is already reversed.
     */
    @Transactional
    public Payment reverse(EscrowAccount escrow, String vaNumber, String bankReference,
                           BigDecimal amount, Instant reversalTime) {
        // The referenced payment may sit on any generation of a reused number.
        List<VirtualAccount> generations = virtualAccountRepository
                .findByEscrowAccountIdAndVaNumberOrderByCreatedAtDesc(escrow.getId(), vaNumber);
        if (generations.isEmpty()) {
            throw new NotFoundException("VA not found in escrow " + escrow.getCode() + ": " + vaNumber);
        }
        Payment payment = generations.stream()
                .map(g -> paymentRepository.findByVirtualAccountIdAndBankReference(g.getId(), bankReference))
                .flatMap(Optional::stream)
                .findFirst()
                .orElseThrow(() -> new NotFoundException(
                        "no payment to reverse for reference " + bankReference));
        VirtualAccount va = payment.getVirtualAccount();

        if (payment.getStatus() == PaymentStatus.REVERSED) {
            return payment;
        }
        if (amount != null && payment.getAmount().compareTo(amount) != 0) {
            throw new InvalidPaymentException(
                    "reversal amount " + amount + " does not match payment " + payment.getAmount());
        }
        long minutesElapsed = Duration.between(payment.getTransactionTime(), reversalTime).toMinutes();
        if (minutesElapsed > reversalProperties.windowMinutes()) {
            throw new InvalidPaymentException("reversal window of "
                    + reversalProperties.windowMinutes() + " minutes exceeded");
        }

        Charge charge = chargeRepository.lockById(payment.getCharge().getId())
                .orElseThrow(() -> new NotFoundException("charge not found for reversal"));
        if (charge.getStatus() == ChargeStatus.CANCELLED) {
            throw new InvalidPaymentException("charge is cancelled");
        }

        boolean settledByThisPayment = charge.getStatus() == ChargeStatus.PAID;
        payment.setStatus(PaymentStatus.REVERSED);
        charge.setCumulativePaid(charge.getCumulativePaid().subtract(payment.getAmount()));
        charge.setStatus(charge.getCumulativePaid().signum() == 0
                ? ChargeStatus.ACTIVE : ChargeStatus.PARTIALLY_PAID);
        if (settledByThisPayment) {
            for (VirtualAccount sibling : virtualAccountRepository.findByChargeId(charge.getId())) {
                if (sibling.getStatus() == VirtualAccountStatus.PAID
                        || sibling.getStatus() == VirtualAccountStatus.CANCELLED) {
                    sibling.setStatus(VirtualAccountStatus.ACTIVE);
                    virtualAccountRepository.save(sibling);
                }
            }
        }

        Payment reversed = paymentRepository.save(payment);
        webhookService.enqueue(charge, reversed, WebhookEventType.PAYMENT_REVERSED);
        auditService.record("PAYMENT_REVERSED", "Payment", reversed.getId(),
                "va=" + vaNumber + " amount=" + reversed.getAmount() + " ref=" + bankReference);
        return reversed;
    }

    private void applyClosed(Charge charge, VirtualAccount paidVa, BigDecimal amount) {
        if (charge.getStatus() == ChargeStatus.PAID) {
            throw new InvalidPaymentException("charge already paid (duplicate/overpayment)");
        }
        if (amount.compareTo(charge.getAmount()) != 0) {
            throw new InvalidPaymentException(
                    "CLOSED charge requires exact amount " + charge.getAmount() + "; got " + amount);
        }
        charge.setCumulativePaid(amount);
        charge.setStatus(ChargeStatus.PAID);
        settleAndCancelSiblings(charge, paidVa);
    }

    private void applyInstallment(Charge charge, VirtualAccount paidVa, BigDecimal amount) {
        if (charge.getStatus() == ChargeStatus.PAID) {
            throw new InvalidPaymentException("charge already paid (overpayment)");
        }
        BigDecimal newCumulative = charge.getCumulativePaid().add(amount);
        if (newCumulative.compareTo(charge.getAmount()) > 0) {
            BigDecimal remaining = charge.getAmount().subtract(charge.getCumulativePaid());
            throw new InvalidPaymentException("payment exceeds remaining " + remaining);
        }
        charge.setCumulativePaid(newCumulative);
        if (newCumulative.compareTo(charge.getAmount()) == 0) {
            charge.setStatus(ChargeStatus.PAID);
            settleAndCancelSiblings(charge, paidVa);
        } else {
            charge.setStatus(ChargeStatus.PARTIALLY_PAID);
        }
    }

    private void applyOpen(Charge charge, BigDecimal amount) {
        // Persistent, free amount, repeated payments: accumulate, never auto-complete, keep siblings open.
        charge.setCumulativePaid(charge.getCumulativePaid().add(amount));
    }

    private void settleAndCancelSiblings(Charge charge, VirtualAccount paidVa) {
        for (VirtualAccount sibling : virtualAccountRepository.findByChargeId(charge.getId())) {
            if (sibling.getId().equals(paidVa.getId())) {
                sibling.setStatus(VirtualAccountStatus.PAID);
            } else if (sibling.getStatus() == VirtualAccountStatus.ACTIVE) {
                sibling.setStatus(VirtualAccountStatus.CANCELLED);
            }
            virtualAccountRepository.save(sibling);
        }
    }
}
