package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.exception.InvalidRequestException;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final AuditService auditService;
    private final TransactionTemplate recoveryTransaction;

    public ReconciliationService(ReconciliationRunRepository runRepository,
                                 ReconciliationDiscrepancyRepository discrepancyRepository,
                                 VirtualAccountRepository virtualAccountRepository,
                                 PaymentRepository paymentRepository,
                                 PaymentApplicationService paymentApplicationService,
                                 AuditService auditService,
                                 PlatformTransactionManager transactionManager) {
        this.runRepository = runRepository;
        this.discrepancyRepository = discrepancyRepository;
        this.virtualAccountRepository = virtualAccountRepository;
        this.paymentRepository = paymentRepository;
        this.paymentApplicationService = paymentApplicationService;
        this.auditService = auditService;
        // Recovery must run in its own transaction: if apply() joined the run's transaction and
        // threw, the run would be marked rollback-only and one bad credit would abort the whole
        // reconciliation instead of being flagged as RECOVERY_FAILED.
        this.recoveryTransaction = new TransactionTemplate(transactionManager);
        this.recoveryTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Reconcile and recover: the normal end-of-day path. */
    @Transactional
    public ReconciliationRun reconcile(EscrowAccount escrow, LocalDate period, List<SettlementCredit> credits) {
        return reconcile(escrow, period, credits, true);
    }

    /**
     * Reconcile, optionally without recovering.
     *
     * <p>Recovery creates a payment and forwards a webhook for every settled credit the gateway has no
     * record of. That is right when the settlement file is the bank's own and its references are the
     * ones we match on. It is dangerous otherwise: a file whose references do not line up makes every
     * row look unrecorded, and recovery would then manufacture duplicate payments against charges that
     * are already paid. Report-only exists so a statement extract can be examined — which payments the
     * bank never settled, which credits we cannot account for — without writing money into the ledger
     * on the strength of a file nobody has validated yet.
     */
    @Transactional
    public ReconciliationRun reconcile(EscrowAccount escrow, LocalDate period,
                                       List<SettlementCredit> credits, boolean recover) {
        // The bank keeps a fee from each payment, so the settlement line is NET and our payment is
        // GROSS. Refuse rather than guess: assuming zero silently reports every row of a fee-charging
        // bank as an amount mismatch, and a report that is wrong about everything gets discarded.
        // A bank that charges nothing is configured with an explicit 0.
        BigDecimal fee = escrow.getSettlementFee();
        if (fee == null) {
            throw new InvalidRequestException(
                    "settlementFee is not configured for escrow " + escrow.getCode()
                            + " — set it (0 if the bank deducts nothing) before reconciling, or every"
                            + " settled row is compared gross against net");
        }

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
            // Numbers are reusable: the settled payment may sit on any generation of the number.
            List<VirtualAccount> generations = virtualAccountRepository
                    .findByEscrowAccountIdAndVaNumberOrderByCreatedAtDesc(escrow.getId(), credit.vaNumber());
            if (generations.isEmpty()) {
                discrepancies.add(fromCredit(run, DiscrepancyType.UNMATCHED_CREDIT, credit, null,
                        "no virtual account for credit"));
                continue;
            }
            Optional<Payment> payment = generations.stream()
                    .map(g -> paymentRepository
                            .findByVirtualAccountIdAndBankReference(g.getId(), credit.bankReference()))
                    .flatMap(Optional::stream)
                    .findFirst();
            if (payment.isPresent()) {
                Payment existing = payment.get();
                settledPaymentKeys.add(key(existing));
                // What the bank should have credited for this payment, once its fee is taken off.
                BigDecimal expected = existing.getAmount().subtract(fee);
                if (expected.compareTo(credit.amount()) == 0) {
                    matched++;
                } else {
                    discrepancies.add(fromCredit(run, DiscrepancyType.AMOUNT_MISMATCH, credit, existing,
                            "gateway " + existing.getAmount() + " less fee " + fee + " = " + expected
                                    + " vs settled " + credit.amount()));
                }
            } else if (!recover) {
                discrepancies.add(fromCredit(run, DiscrepancyType.PAID_NOT_NOTIFIED_REPORTED, credit, null,
                        "settled but not recorded here; report-only run, no payment created"));
            } else {
                try {
                    Payment recoveredPayment = recoveryTransaction.execute(status ->
                            // The settlement line is net; the payer paid the fee too. Recording the
                            // net here would under-credit the student by the fee on every recovered
                            // payment, leaving charges that can never reach PAID.
                            paymentApplicationService.apply(escrow, credit.vaNumber(),
                                    credit.amount().add(fee), credit.bankReference(),
                                    credit.transactionTime()));
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
        ReconciliationRun completed = runRepository.save(run);
        auditService.record("RECONCILIATION_RUN", "ReconciliationRun", completed.getId(),
                "escrow=" + escrow.getCode() + " period=" + period + " matched=" + matched
                        + " recovered=" + recovered + " discrepancies=" + discrepancies.size());
        return completed;
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
