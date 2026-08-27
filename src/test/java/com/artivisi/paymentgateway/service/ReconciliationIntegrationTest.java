package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.AbstractIntegrationTest;
import com.artivisi.paymentgateway.dto.ChargeAccountRequest;
import com.artivisi.paymentgateway.dto.ConsumerRequest;
import com.artivisi.paymentgateway.dto.CreateChargeRequest;
import com.artivisi.paymentgateway.dto.EscrowAccountRequest;
import com.artivisi.paymentgateway.dto.SettlementAmountBasis;
import com.artivisi.paymentgateway.dto.SettlementCredit;
import com.artivisi.paymentgateway.entity.AuthScheme;
import com.artivisi.paymentgateway.entity.Charge;
import com.artivisi.paymentgateway.entity.ChargeStatus;
import com.artivisi.paymentgateway.entity.Payment;
import com.artivisi.paymentgateway.repository.PaymentRepository;
import com.artivisi.paymentgateway.entity.ChargeType;
import com.artivisi.paymentgateway.entity.Consumer;
import com.artivisi.paymentgateway.entity.ConsumerStatus;
import com.artivisi.paymentgateway.entity.DiscrepancyType;
import com.artivisi.paymentgateway.entity.EscrowAccount;
import com.artivisi.paymentgateway.entity.EscrowEnvironment;
import com.artivisi.paymentgateway.entity.HostingModel;
import com.artivisi.paymentgateway.entity.ReconciliationRun;
import com.artivisi.paymentgateway.entity.TransportProtocol;
import com.artivisi.paymentgateway.repository.ChargeRepository;
import com.artivisi.paymentgateway.repository.ReconciliationDiscrepancyRepository;
import com.artivisi.paymentgateway.repository.WebhookDeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.equalTo;

class ReconciliationIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final LocalDate PERIOD = LocalDate.of(2026, 6, 25);
    private static final Instant TX_TIME = PERIOD.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(36000);

    @Autowired EscrowAccountService escrowService;
    @Autowired ConsumerService consumerService;
    @Autowired ChargeService chargeService;
    @Autowired PaymentApplicationService paymentService;
    @Autowired ReconciliationService reconciliationService;
    @Autowired ReconciliationDiscrepancyRepository discrepancyRepository;
    @Autowired ChargeRepository chargeRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired WebhookDeliveryRepository webhookRepository;

    private EscrowAccount escrow;
    private Consumer consumer;

    /** {@code fee} is what the bank keeps per payment; ZERO says "this one keeps nothing". */
    private static EscrowAccountRequest escrowRequest(String code, BigDecimal fee) {
        return new EscrowAccountRequest(code, "bsi", HostingModel.SELF_HOSTED, TransportProtocol.REST_JSON,
                AuthScheme.PROPRIETARY, EscrowEnvironment.SANDBOX, null, null, null, null, null, null, null, null,
                "930900111", "Operator Settlement", "93099", "930", 10, null, null, fee);
    }

    @BeforeEach
    void seed() {
        int n = SEQ.incrementAndGet();
        escrow = escrowService.create(escrowRequest("recon-bsi-" + n, BigDecimal.ZERO));
        consumer = consumerService.create(new ConsumerRequest(
                "recon-consumer-" + n, "Academic", "recon-client-" + n, "secret-" + n,
                "https://hook.example/" + n, ConsumerStatus.ACTIVE));
    }

    private void createCharge(String vaNumber, String amount) {
        chargeService.create(consumer, new CreateChargeRequest(
                "recon-ref-" + vaNumber, "Student", null, null, ChargeType.CLOSED, new BigDecimal(amount), null,
                List.of(new ChargeAccountRequest(escrow.getCode(), vaNumber))));
    }

    private static SettlementCredit credit(String va, String ref, String amount) {
        return new SettlementCredit(va, ref, new BigDecimal(amount), TX_TIME);
    }

    @Test
    void reconcile_refusesUntilTheSettlementFeeIsKnown() {
        EscrowAccount unset = escrowService.create(
                escrowRequest("recon-nofee-" + SEQ.incrementAndGet(), null));
        createCharge("9300000009", "100000");

        // Guessing zero would report every row of a fee-charging bank as an amount mismatch, and a
        // report that is wrong about everything gets read as a broken feature.
        assertThatThrownBy(() -> reconciliationService.reconcile(unset, PERIOD, List.of()))
                .hasMessageContaining("settlementFee is not configured");
    }

    @Test
    void aBankFeeIsExpected_notAMismatch() {
        EscrowAccount fee = escrowService.create(
                escrowRequest("recon-fee-" + SEQ.incrementAndGet(), new BigDecimal("2000")));
        escrow = fee;                       // charges and payments must live on this escrow
        createCharge("9300000011", "100000");
        createCharge("9300000012", "300000");
        paymentService.apply(fee, "9300000011", new BigDecimal("100000"), "F1", TX_TIME);
        paymentService.apply(fee, "9300000012", new BigDecimal("300000"), "F2", TX_TIME);

        ReconciliationRun run = reconciliationService.reconcile(fee, PERIOD, List.of(
                credit("9300000011", "F1", "98000"),     // payer paid 100.000, bank kept 2.000
                credit("9300000012", "F2", "300000")));  // full amount arrived: that IS wrong

        assertThat(run.getMatchedCount()).isEqualTo(1);
        assertThat(discrepancyRepository.findByReconciliationRunIdOrderByCreatedAtAsc(run.getId()))
                .extracting("type").containsExactly(DiscrepancyType.AMOUNT_MISMATCH);
    }

    @Test
    void aRecoveredPaymentRecordsWhatThePayerPaid_notWhatTheBankKept() {
        EscrowAccount fee = escrowService.create(
                escrowRequest("recon-rec-" + SEQ.incrementAndGet(), new BigDecimal("2000")));
        escrow = fee;
        createCharge("9300000013", "100000");

        // Bank settled it, no callback ever arrived. The settlement line carries the net.
        ReconciliationRun run = reconciliationService.reconcile(fee, PERIOD, List.of(
                credit("9300000013", "F3", "98000")));

        assertThat(run.getRecoveredCount()).isEqualTo(1);
        Payment recovered = paymentRepository.findAll().stream()
                .filter(p -> "F3".equals(p.getBankReference())).findFirst().orElseThrow();
        // Recording 98.000 would leave the student 2.000 short of a charge they settled in full,
        // and the charge could never reach PAID.
        assertThat(recovered.getAmount()).isEqualByComparingTo("100000");
        assertThat(chargeRepository.findAll().stream()
                .filter(c -> ("recon-ref-9300000013").equals(c.getConsumerReference()))
                .findFirst().orElseThrow().getStatus()).isEqualTo(ChargeStatus.PAID);
    }

    @Test
    void matchesOnTheBankJournalNumberWhenTheFileCarriesThatInstead() {
        EscrowAccount fee = escrowService.create(
                escrowRequest("recon-jrn-" + SEQ.incrementAndGet(), new BigDecimal("2000")));
        escrow = fee;
        createCharge("9300000031", "100000");
        // The callback's own reference, and beside it the bank's core-banking journal number.
        paymentService.apply(fee, "9300000031", new BigDecimal("100000"),
                "93000000312026062517000", TX_TIME, "5310230625100000000451");

        // A file exported from the bank's transaction portal identifies the same payment by the
        // journal number instead. Matching on the callback reference alone finds nothing in such a
        // file, and every row of it then reads as money the bank settled and we never recorded —
        // which, in a recovering run, would manufacture a duplicate payment for each one.
        ReconciliationRun run = reconciliationService.reconcile(fee, PERIOD,
                List.of(credit("9300000031", "5310230625100000000451", "98000")),
                true, SettlementAmountBasis.NET_OF_FEE);

        assertThat(run.getMatchedCount()).isEqualTo(1);
        assertThat(run.getRecoveredCount()).isZero();
        assertThat(run.getDiscrepancyCount()).isZero();
    }

    @Test
    void aGrossTransactionListIsComparedGross_notNetOfTheFee() {
        EscrowAccount fee = escrowService.create(
                escrowRequest("recon-gross-" + SEQ.incrementAndGet(), new BigDecimal("2000")));
        escrow = fee;
        createCharge("9300000032", "100000");
        paymentService.apply(fee, "9300000032", new BigDecimal("100000"), "G1", TX_TIME);

        // A transaction list reports what the payer sent, not what the account received. Taking the
        // fee off here would make every row of it an amount mismatch.
        ReconciliationRun run = reconciliationService.reconcile(fee, PERIOD,
                List.of(credit("9300000032", "G1", "100000")), true, SettlementAmountBasis.GROSS);

        assertThat(run.getMatchedCount()).isEqualTo(1);
        assertThat(run.getDiscrepancyCount()).isZero();
    }

    @Test
    void aGrossFileDoesNotNeedTheFeeConfigured() {
        // The fee only exists to bridge gross to net. A file that is already gross does not need it,
        // and refusing to run would be refusing for no reason.
        EscrowAccount unset = escrowService.create(
                escrowRequest("recon-gross-nofee-" + SEQ.incrementAndGet(), null));
        escrow = unset;
        createCharge("9300000033", "100000");
        paymentService.apply(unset, "9300000033", new BigDecimal("100000"), "G2", TX_TIME);

        ReconciliationRun run = reconciliationService.reconcile(unset, PERIOD,
                List.of(credit("9300000033", "G2", "100000")), true, SettlementAmountBasis.GROSS);

        assertThat(run.getMatchedCount()).isEqualTo(1);
        assertThat(run.getDiscrepancyCount()).isZero();
    }

    @Test
    void reportOnly_flagsUnrecordedCreditsWithoutManufacturingPayments() {
        createCharge("9300000021", "100000");
        long paymentsBefore = paymentRepository.count();

        // A credit for a VA we have, with no payment recorded against it. The recovering path would
        // create the payment; report-only must not, because the file may not be one whose references
        // we can trust — that is the whole reason the mode exists.
        ReconciliationRun run = reconciliationService.reconcile(escrow, PERIOD,
                List.of(credit("9300000021", "RO-1", "100000")), false, SettlementAmountBasis.NET_OF_FEE);

        assertThat(run.getRecoveredCount()).isZero();
        assertThat(paymentRepository.count())
                .as("report-only must not write payments into the ledger")
                .isEqualTo(paymentsBefore);
        assertThat(discrepancyRepository.findByReconciliationRunIdOrderByCreatedAtAsc(run.getId()))
                .extracting("type")
                .containsExactly(DiscrepancyType.PAID_NOT_NOTIFIED_REPORTED);
    }

    @Test
    void theSettlementDayIsTheBanksDayNotUtc() {
        createCharge("9300000031", "400000");
        // 04:38 WIB on the 26th — before 07:00, so UTC still calls it the 25th. The bank's statement
        // for the 26th contains it, and a run for the 26th has to agree, or the same payment is
        // reported as never settled on one day and never recorded on the next.
        paymentService.apply(escrow, "9300000031", new BigDecimal("400000"), "TZ-1",
                Instant.parse("2026-06-25T21:38:00Z"));

        // Reconciled against a statement that does NOT contain it. Matching finds a payment by VA and
        // reference whatever the period, so a run with the credit present looks identical either way;
        // only the unsettled sweep reads the period, and that is where a UTC window loses the payment
        // silently — the seven-hour shift drops it out of the day entirely and nothing is reported.
        ReconciliationRun run = reconciliationService.reconcile(
                escrow, LocalDate.parse("2026-06-26"), List.of());

        assertThat(discrepancyRepository.findByReconciliationRunIdOrderByCreatedAtAsc(run.getId()))
                .as("a payment the bank did not settle must be reported on the day the bank booked it")
                .extracting("type")
                .containsExactly(DiscrepancyType.NOTIFIED_NOT_SETTLED);
    }

    @Test
    void reconcile_classifiesEveryOutcome() {
        // VA1 matched, VA2 paid-not-notified, VA3 amount mismatch, VA4 notified-not-settled.
        createCharge("9300000001", "100000");
        createCharge("9300000002", "200000");
        createCharge("9300000003", "300000");
        createCharge("9300000004", "400000");

        paymentService.apply(escrow, "9300000001", new BigDecimal("100000"), "R1", TX_TIME);
        paymentService.apply(escrow, "9300000003", new BigDecimal("300000"), "R3", TX_TIME);
        paymentService.apply(escrow, "9300000004", new BigDecimal("400000"), "R4", TX_TIME);

        List<SettlementCredit> credits = List.of(
                credit("9300000001", "R1", "100000"),   // matched
                credit("9300000001", "R1", "100000"),   // duplicate reference
                credit("9300000002", "R2", "200000"),   // recovered (paid-not-notified)
                credit("9300000003", "R3", "250000"),   // amount mismatch
                credit("9309999999", "R9", "50000"));    // unmatched (unknown VA)

        ReconciliationRun run = reconciliationService.reconcile(escrow, PERIOD, credits);

        assertThat(run.getMatchedCount()).isEqualTo(1);
        assertThat(run.getRecoveredCount()).isEqualTo(1);
        assertThat(run.getDiscrepancyCount()).isEqualTo(5);

        assertThat(discrepancyRepository.findByReconciliationRunIdOrderByCreatedAtAsc(run.getId()))
                .extracting("type")
                .containsExactlyInAnyOrder(
                        DiscrepancyType.DUPLICATE,
                        DiscrepancyType.PAID_NOT_NOTIFIED_RECOVERED,
                        DiscrepancyType.AMOUNT_MISMATCH,
                        DiscrepancyType.UNMATCHED_CREDIT,
                        DiscrepancyType.NOTIFIED_NOT_SETTLED);
    }

    @Test
    void recovery_marksChargePaidAndForwardsWebhook() {
        createCharge("9300000010", "500000");
        // No prior payment: the bank settled but the gateway never recorded it.

        ReconciliationRun run = reconciliationService.reconcile(escrow, PERIOD,
                List.of(credit("9300000010", "R10", "500000")));

        assertThat(run.getRecoveredCount()).isEqualTo(1);
        Charge charge = chargeRepository
                .findByConsumerIdAndConsumerReference(consumer.getId(), "recon-ref-9300000010").orElseThrow();
        assertThat(charge.getStatus()).isEqualTo(ChargeStatus.PAID);
        assertThat(charge.getCumulativePaid()).isEqualByComparingTo("500000");
        // Recovery forwarded a webhook (PAYMENT_RECEIVED + CHARGE_PAID).
        assertThat(webhookRepository.findByChargeIdOrderByCreatedAtAsc(charge.getId())).isNotEmpty();
    }

    @Test
    void reconcileEndpoint_refusesAFileWhoseAmountBasisIsNotStated() {
        createCharge("9300000034", "250000");
        // Omitting it used to be harmless because net was assumed. It is not harmless: the same file
        // read on the wrong basis reports every row as an amount mismatch, so the caller has to say.
        String body = "{\"period\":\"2026-06-25\",\"credits\":[{\"vaNumber\":\"9300000034\","
                + "\"bankReference\":\"R34\",\"amount\":250000,\"transactionTime\":\"2026-06-25T10:00:00Z\"}]}";

        given().header("Authorization", "Bearer " + managementToken()).contentType("application/json").body(body)
                .when().post("/api/escrow-accounts/{code}/reconciliations", escrow.getCode())
                .then().statusCode(400);
    }

    @Test
    void reconcileEndpoint_returnsSummary() {
        createCharge("9300000030", "250000");
        String body = "{\"period\":\"2026-06-25\",\"amountBasis\":\"NET_OF_FEE\","
                + "\"credits\":[{\"vaNumber\":\"9300000030\","
                + "\"bankReference\":\"R30\",\"amount\":250000,\"transactionTime\":\"2026-06-25T10:00:00Z\"}]}";

        given().header("Authorization", "Bearer " + managementToken()).contentType("application/json").body(body)
                .when().post("/api/escrow-accounts/{code}/reconciliations", escrow.getCode())
                .then().statusCode(201)
                .body("recoveredCount", equalTo(1))
                .body("discrepancyCount", equalTo(1))
                .body("discrepancies[0].type", equalTo("PAID_NOT_NOTIFIED_RECOVERED"));
    }

    @Test
    void recoveryFailure_isFlaggedLoudNotSilentlyAccepted() {
        // The consumer cancelled the charge, but the bank still settled a credit for its VA.
        createCharge("9300000040", "600000");
        Charge charge = chargeRepository
                .findByConsumerIdAndConsumerReference(consumer.getId(), "recon-ref-9300000040").orElseThrow();
        chargeService.cancel(consumer, charge.getId());

        ReconciliationRun run = reconciliationService.reconcile(escrow, PERIOD,
                List.of(credit("9300000040", "R40", "600000")));

        assertThat(run.getMatchedCount()).isZero();
        assertThat(run.getRecoveredCount()).isZero();
        assertThat(run.getDiscrepancyCount()).isEqualTo(1);
        var discrepancies = discrepancyRepository.findByReconciliationRunIdOrderByCreatedAtAsc(run.getId());
        assertThat(discrepancies).hasSize(1);
        assertThat(discrepancies.getFirst().getType()).isEqualTo(DiscrepancyType.RECOVERY_FAILED);
        assertThat(discrepancies.getFirst().getDetail()).contains("cancelled");

        // The unrecoverable credit was never applied to the charge.
        Charge reloaded = chargeRepository.findById(charge.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ChargeStatus.CANCELLED);
        assertThat(reloaded.getCumulativePaid()).isEqualByComparingTo("0");
    }

    @Test
    void cleanReconciliation_hasNoDiscrepancies() {
        createCharge("9300000020", "150000");
        paymentService.apply(escrow, "9300000020", new BigDecimal("150000"), "R20", TX_TIME);

        ReconciliationRun run = reconciliationService.reconcile(escrow, PERIOD,
                List.of(credit("9300000020", "R20", "150000")));

        assertThat(run.getMatchedCount()).isEqualTo(1);
        assertThat(run.getRecoveredCount()).isEqualTo(0);
        assertThat(run.getDiscrepancyCount()).isEqualTo(0);
    }
}
