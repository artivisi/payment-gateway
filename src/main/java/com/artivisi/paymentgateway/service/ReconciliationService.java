package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.dto.SettlementCredit;
import com.artivisi.paymentgateway.entity.DiscrepancyType;
import com.artivisi.paymentgateway.entity.EscrowAccount;
import com.artivisi.paymentgateway.entity.Payment;
import com.artivisi.paymentgateway.entity.PaymentStatus;
import com.artivisi.paymentgateway.entity.ReconciliationDiscrepancy;
import com.artivisi.paymentgateway.entity.ReconciliationRun;
import com.artivisi.paymentgateway.entity.ReconciliationStatus;
import com.artivisi.paymentgateway.entity.VirtualAccount;
import com.artivisi.paymentgateway.repository.PaymentRepository;
import com.artivisi.paymentgateway.repository.ReconciliationDiscrepancyRepository;
import com.artivisi.paymentgateway.repository.ReconciliationRunRepository;
import com.artivisi.paymentgateway.repository.VirtualAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * End-of-day reconciliation for one escrow + period. Matches the bank's settlement credits to
 * recorded payments, recovers paid-not-notified credits (creates the payment and forwards the
 * webhook), and flags amount mismatches, duplicates, unmatched credits, and notified-not-settled
 * payments. Settlement credits arrive via pull or imported statement (the source is out of scope here).
 */
@Service
public class ReconciliationService {

    private final ReconciliationRunRepository runRepository;
    private final ReconciliationDiscrepancyRepository discrepancyRepository;
    private final VirtualAccountRepository virtualAccountRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentApplicationService paymentApplicationService;

    public ReconciliationService(ReconciliationRunRepository runRepository,
                                 ReconciliationDiscrepancyRepository discrepancyRepository,
                                 VirtualAccountRepository virtualAccountRepository,
                                 PaymentRepository paymentRepository,
                                 PaymentApplicationService paymentApplicationService) {
        this.runRepository = runRepository;
        this.discrepancyRepository = discrepancyRepository;
        this.virtualAccountRepository = virtualAccountRepository;
        this.paymentRepository = paymentRepository;
        this.paymentApplicationService = paymentApplicationService;
    }

    @Transactional
    public ReconciliationRun reconcile(EscrowAccount escrow, LocalDate period, List<SettlementCredit> credits) {
        ReconciliationRun run = new ReconciliationRun();
        run.setEscrowAccount(escrow);
        run.setPeriod(period);
        run.setStatus(ReconciliationStatus.PENDING);
        run.setStartedAt(Instant.now());
        run = runRepository.save(run);

        Instant start = period.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = period.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<Payment> gatewayPayments = paymentRepository.findByEscrowAndStatusInPeriod(
                escrow.getId(), PaymentStatus.ACCEPTED, start, end);

        Set<String> settledPaymentKeys = new HashSet<>();
        Set<String> seenReferences = new HashSet<>();
        List<ReconciliationDiscrepancy> discrepancies = new ArrayList<>();
        int matched = 0;
        int recovered = 0;

        for (SettlementCredit credit : credits) {
            if (!seenReferences.add(credit.bankReference())) {
                discrepancies.add(fromCredit(run, DiscrepancyType.DUPLICATE, credit, null,
                        "duplicate settlement reference"));
                continue;
            }
            Optional<VirtualAccount> va = virtualAccountRepository
                    .findByEscrowAccountIdAndVaNumber(escrow.getId(), credit.vaNumber());
            if (va.isEmpty()) {
                discrepancies.add(fromCredit(run, DiscrepancyType.UNMATCHED_CREDIT, credit, null,
                        "no virtual account for credit"));
                continue;
            }
            Optional<Payment> payment = paymentRepository
                    .findByVirtualAccountIdAndBankReference(va.get().getId(), credit.bankReference());
            if (payment.isPresent()) {
                Payment existing = payment.get();
                settledPaymentKeys.add(key(existing));
                if (existing.getAmount().compareTo(credit.amount()) == 0) {
                    matched++;
                } else {
                    discrepancies.add(fromCredit(run, DiscrepancyType.AMOUNT_MISMATCH, credit, existing,
                            "gateway " + existing.getAmount() + " vs settled " + credit.amount()));
                }
            } else {
                try {
                    Payment recoveredPayment = paymentApplicationService.apply(escrow, credit.vaNumber(),
                            credit.amount(), credit.bankReference(), credit.transactionTime());
                    settledPaymentKeys.add(key(recoveredPayment));
                    recovered++;
                    discrepancies.add(fromCredit(run, DiscrepancyType.PAID_NOT_NOTIFIED_RECOVERED, credit,
                            recoveredPayment, "recovered; payment created and webhook forwarded"));
                } catch (RuntimeException e) {
                    discrepancies.add(fromCredit(run, DiscrepancyType.RECOVERY_FAILED, credit, null, e.getMessage()));
                }
            }
        }

        for (Payment payment : gatewayPayments) {
            if (!settledPaymentKeys.contains(key(payment))) {
                discrepancies.add(fromPayment(run, DiscrepancyType.NOTIFIED_NOT_SETTLED, payment,
                        "payment not present in settlement"));
            }
        }

        discrepancyRepository.saveAll(discrepancies);
        run.setMatchedCount(matched);
        run.setRecoveredCount(recovered);
        run.setDiscrepancyCount(discrepancies.size());
        run.setStatus(ReconciliationStatus.COMPLETED);
        run.setFinishedAt(Instant.now());
        return runRepository.save(run);
    }

    private static String key(Payment payment) {
        return payment.getVirtualAccount().getId() + "|" + payment.getBankReference();
    }

    private static ReconciliationDiscrepancy fromCredit(ReconciliationRun run, DiscrepancyType type,
                                                        SettlementCredit credit, Payment payment, String detail) {
        ReconciliationDiscrepancy discrepancy = new ReconciliationDiscrepancy();
        discrepancy.setReconciliationRun(run);
        discrepancy.setType(type);
        discrepancy.setVaNumber(credit.vaNumber());
        discrepancy.setBankReference(credit.bankReference());
        discrepancy.setAmount(credit.amount());
        discrepancy.setPayment(payment);
        discrepancy.setDetail(detail);
        return discrepancy;
    }

    private static ReconciliationDiscrepancy fromPayment(ReconciliationRun run, DiscrepancyType type,
                                                         Payment payment, String detail) {
        ReconciliationDiscrepancy discrepancy = new ReconciliationDiscrepancy();
        discrepancy.setReconciliationRun(run);
        discrepancy.setType(type);
        discrepancy.setVaNumber(payment.getVirtualAccount().getVaNumber());
        discrepancy.setBankReference(payment.getBankReference());
        discrepancy.setAmount(payment.getAmount());
        discrepancy.setPayment(payment);
        discrepancy.setDetail(detail);
        return discrepancy;
    }
}
