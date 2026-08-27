package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.exception.InvalidRequestException;
import com.artivisi.paymentgateway.dto.SettlementAmountBasis;
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
import java.time.ZoneId;
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

    /**
     * The calendar the settlement day is reckoned on. Indonesian banks close their books locally and
     * a statement headed "8 July" means the Jakarta day, so a run for that date has to cover the same
     * hours the bank did.
     */
    private static final ZoneId SETTLEMENT_ZONE = ZoneId.of("Asia/Jakarta");

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

    /** Reconcile and recover from a bank statement: the normal end-of-day path. */
    @Transactional
    public ReconciliationRun reconcile(EscrowAccount escrow, LocalDate period, List<SettlementCredit> credits) {
        return reconcile(escrow, period, credits, true, SettlementAmountBasis.NET_OF_FEE);
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
                                       List<SettlementCredit> credits, boolean recover,
                                       SettlementAmountBasis amountBasis) {
        // The bank keeps a fee from each payment, so the settlement line is NET and our payment is
        // GROSS. Refuse rather than guess: assuming zero silently reports every row of a fee-charging
        // bank as an amount mismatch, and a report that is wrong about everything gets discarded.
        // A bank that charges nothing is configured with an explicit 0.
        BigDecimal fee = escrow.getSettlementFee();
        if (amountBasis == SettlementAmountBasis.NET_OF_FEE && fee == null) {
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

        // The settlement period is a bank calendar day, and the bank keeps its books in Jakarta.
        // Bounding it in UTC instead shifted the window seven hours: a payment at 04:38 WIB fell into
        // the previous UTC day, so reconciling a statement reported it as a credit we never recorded
        // while the day before reported the same payment as never settled. Two false discrepancies
        // per real payment, at both ends of every run. The rest of the app already reckons calendar
        // days this way (ViewFormats.DISPLAY_ZONE, the payment list's date ranges).
        Instant start = period.atStartOfDay(SETTLEMENT_ZONE).toInstant();
        Instant end = period.plusDays(1).atStartOfDay(SETTLEMENT_ZONE).toInstant();
        List<Payment> gatewayPayments = paymentRepository.findByEscrowAndStatusInPeriod(
                escrow.getId(), PaymentStatus.ACCEPTED, start, end);

        Set<String> settledPaymentKeys = new HashSet<>();
        Set<String> seenReferences = new HashSet<>();
        List<ReconciliationDiscrepancy> discrepancies = new ArrayList<>();
        int matched = 0;
        int recovered = 0;
        int matchedByJournal = 0;

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
            Optional<Payment> payment = resolveByReference(generations, credit.bankReference());
            boolean byJournal = false;
            if (payment.isEmpty()) {
                payment = resolveByJournalNumber(generations, credit.bankReference());
                byJournal = payment.isPresent();
            }
            if (payment.isPresent()) {
                Payment existing = payment.get();
                if (byJournal) {
                    matchedByJournal++;
                }
                settledPaymentKeys.add(key(existing));
                BigDecimal expected = expected(existing, fee, amountBasis);
                if (expected.compareTo(credit.amount()) == 0) {
                    matched++;
                } else {
                    discrepancies.add(fromCredit(run, DiscrepancyType.AMOUNT_MISMATCH, credit, existing,
                            describeExpectation(existing, fee, amountBasis, expected, credit)
                                    + (byJournal ? " (matched on journal number)" : "")));
                }
            } else if (!recover) {
                discrepancies.add(fromCredit(run, DiscrepancyType.PAID_NOT_NOTIFIED_REPORTED, credit, null,
                        "settled but not recorded here; report-only run, no payment created"));
            } else {
                try {
                    // A NET line is what the account received; the payer sent the fee as well.
                    // Recording the net would under-credit the payer on every recovered payment and
                    // leave a charge that can never reach PAID. A GROSS line is already what the
                    // payer sent, so adding the fee there would over-credit by the same amount.
                    BigDecimal payerAmount = amountBasis == SettlementAmountBasis.NET_OF_FEE
                            ? credit.amount().add(fee)
                            : credit.amount();
                    Payment recoveredPayment = recoveryTransaction.execute(status ->
                            paymentApplicationService.apply(escrow, credit.vaNumber(),
                                    payerAmount, credit.bankReference(),
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
                "escrow=" + escrow.getCode() + " period=" + period + " basis=" + amountBasis
                        + " matched=" + matched + " matchedByJournal=" + matchedByJournal
                        + " recovered=" + recovered + " discrepancies=" + discrepancies.size());
        return completed;
    }

    private Optional<Payment> resolveByReference(List<VirtualAccount> generations, String reference) {
        return generations.stream()
                .map(g -> paymentRepository.findByVirtualAccountIdAndBankReference(g.getId(), reference))
                .flatMap(Optional::stream)
                .findFirst();
    }

    /**
     * Second key for the same payment. Two references identify a BSI payment and which one you hold
     * depends on which document you were given: the payment callback carries {@code idTransaksi},
     * stored as {@code bankReference}, while the bank's transaction portal exports
     * {@code nomorJurnalPembukuan}. The two numbering spaces have no overlap, so a portal export
     * matches nothing on the reference alone even though every row is a payment already recorded
     * here — which reads as the whole file being unsettled money.
     *
     * <p>Tried only after the reference finds nothing. The primary path is unchanged, and the
     * shapes differ (26 digits versus 22), so one cannot be mistaken for the other.
     */
    private Optional<Payment> resolveByJournalNumber(List<VirtualAccount> generations, String reference) {
        return generations.stream()
                .map(g -> paymentRepository.findByVirtualAccountIdAndBankJournalNumber(g.getId(), reference))
                .flatMap(Optional::stream)
                .findFirst();
    }

    /** What this file should show for a payment we hold, on its own amount basis. */
    private static BigDecimal expected(Payment payment, BigDecimal fee, SettlementAmountBasis basis) {
        return basis == SettlementAmountBasis.NET_OF_FEE ? payment.getAmount().subtract(fee) : payment.getAmount();
    }

    private static String describeExpectation(Payment payment, BigDecimal fee, SettlementAmountBasis basis,
                                              BigDecimal expected, SettlementCredit credit) {
        return basis == SettlementAmountBasis.NET_OF_FEE
                ? "gateway " + payment.getAmount() + " less fee " + fee + " = " + expected
                        + " vs settled " + credit.amount()
                : "gateway " + payment.getAmount() + " vs bank-reported " + credit.amount() + " (gross)";
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
