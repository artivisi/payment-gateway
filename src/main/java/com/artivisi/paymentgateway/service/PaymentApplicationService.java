package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.entity.Charge;
import com.artivisi.paymentgateway.entity.ChargeStatus;
import com.artivisi.paymentgateway.entity.EscrowAccount;
import com.artivisi.paymentgateway.entity.Payment;
import com.artivisi.paymentgateway.entity.PaymentStatus;
import com.artivisi.paymentgateway.entity.VirtualAccount;
import com.artivisi.paymentgateway.entity.VirtualAccountStatus;
import com.artivisi.paymentgateway.exception.InvalidPaymentException;
import com.artivisi.paymentgateway.exception.NotFoundException;
import com.artivisi.paymentgateway.repository.ChargeRepository;
import com.artivisi.paymentgateway.repository.PaymentRepository;
import com.artivisi.paymentgateway.repository.VirtualAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
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

    public PaymentApplicationService(VirtualAccountRepository virtualAccountRepository,
                                     ChargeRepository chargeRepository,
                                     PaymentRepository paymentRepository) {
        this.virtualAccountRepository = virtualAccountRepository;
        this.chargeRepository = chargeRepository;
        this.paymentRepository = paymentRepository;
    }

    @Transactional
    public Payment apply(EscrowAccount escrow, String vaNumber, BigDecimal amount,
                         String bankReference, Instant transactionTime) {
        VirtualAccount va = virtualAccountRepository
                .findByEscrowAccountIdAndVaNumber(escrow.getId(), vaNumber)
                .orElseThrow(() -> new NotFoundException(
                        "VA not found in escrow " + escrow.getCode() + ": " + vaNumber));

        // Idempotency: a replayed notification returns the already-recorded payment.
        Optional<Payment> existing =
                paymentRepository.findByVirtualAccountIdAndBankReference(va.getId(), bankReference);
        if (existing.isPresent()) {
            return existing.get();
        }

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
        return paymentRepository.save(payment);
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
