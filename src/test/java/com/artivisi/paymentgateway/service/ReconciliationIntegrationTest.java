package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.AbstractIntegrationTest;
import com.artivisi.paymentgateway.dto.ChargeAccountRequest;
import com.artivisi.paymentgateway.dto.ConsumerRequest;
import com.artivisi.paymentgateway.dto.CreateChargeRequest;
import com.artivisi.paymentgateway.dto.EscrowAccountRequest;
import com.artivisi.paymentgateway.dto.SettlementCredit;
import com.artivisi.paymentgateway.entity.AuthScheme;
import com.artivisi.paymentgateway.entity.Charge;
import com.artivisi.paymentgateway.entity.ChargeStatus;
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
    @Autowired WebhookDeliveryRepository webhookRepository;

    private EscrowAccount escrow;
    private Consumer consumer;

    private static EscrowAccountRequest escrowRequest(String code) {
        return new EscrowAccountRequest(code, "bsi", HostingModel.SELF_HOSTED, TransportProtocol.REST_JSON,
                AuthScheme.PROPRIETARY, EscrowEnvironment.SANDBOX, null, null, null, null, null, null, null, null,
                "930900111", "Operator Settlement", "93099", "930", 10, null, null);
    }

    @BeforeEach
    void seed() {
        int n = SEQ.incrementAndGet();
        escrow = escrowService.create(escrowRequest("recon-bsi-" + n));
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
    void reconcileEndpoint_returnsSummary() {
        createCharge("9300000030", "250000");
        String body = "{\"period\":\"2026-06-25\",\"credits\":[{\"vaNumber\":\"9300000030\","
                + "\"bankReference\":\"R30\",\"amount\":250000,\"transactionTime\":\"2026-06-25T10:00:00Z\"}]}";

        given().contentType("application/json").body(body)
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
