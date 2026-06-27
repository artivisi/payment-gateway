package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.AbstractIntegrationTest;
import com.artivisi.paymentgateway.dto.ChargeAccountRequest;
import com.artivisi.paymentgateway.dto.ConsumerRequest;
import com.artivisi.paymentgateway.dto.CreateChargeRequest;
import com.artivisi.paymentgateway.dto.EscrowAccountRequest;
import com.artivisi.paymentgateway.entity.AuditEvent;
import com.artivisi.paymentgateway.entity.AuthScheme;
import com.artivisi.paymentgateway.entity.Charge;
import com.artivisi.paymentgateway.entity.ChargeType;
import com.artivisi.paymentgateway.entity.Consumer;
import com.artivisi.paymentgateway.entity.ConsumerStatus;
import com.artivisi.paymentgateway.entity.EscrowAccount;
import com.artivisi.paymentgateway.entity.EscrowEnvironment;
import com.artivisi.paymentgateway.entity.HostingModel;
import com.artivisi.paymentgateway.entity.TransportProtocol;
import com.artivisi.paymentgateway.entity.WebhookDelivery;
import com.artivisi.paymentgateway.entity.WebhookEventType;
import com.artivisi.paymentgateway.entity.WebhookStatus;
import com.artivisi.paymentgateway.repository.AuditEventRepository;
import com.artivisi.paymentgateway.repository.ChargeRepository;
import com.artivisi.paymentgateway.repository.WebhookDeliveryRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Per-consumer delivery isolation: fair claim cap, suspend kill-switch, terminal-failure alert, replay. */
class WebhookBulkheadTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired EscrowAccountService escrowService;
    @Autowired ConsumerService consumerService;
    @Autowired ChargeService chargeService;
    @Autowired PaymentApplicationService paymentService;
    @Autowired WebhookService webhookService;
    @Autowired WebhookDispatcher webhookDispatcher;
    @Autowired WebhookDeliveryRepository webhookRepository;
    @Autowired ChargeRepository chargeRepository;
    @Autowired AuditEventRepository auditRepository;
    @Autowired com.artivisi.paymentgateway.config.WebhookProperties webhookProperties;

    private HttpServer server;
    private final List<String> received = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        received.clear();
    }

    private String startReceiver(int responseCode) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", (HttpExchange exchange) -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(responseCode, -1);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    private record Fixture(Consumer consumer, EscrowAccount escrow, String vaNumber) {
    }

    private Fixture seed(String webhookUrl) {
        int n = SEQ.incrementAndGet();
        Consumer consumer = consumerService.create(new ConsumerRequest(
                "bh-consumer-" + n, "Academic", "bh-client-" + n, "bh-secret-" + n, webhookUrl, ConsumerStatus.ACTIVE));
        EscrowAccount escrow = escrowService.create(new EscrowAccountRequest(
                "bh-bsi-" + n, "bsi", HostingModel.SELF_HOSTED, TransportProtocol.REST_JSON, AuthScheme.PROPRIETARY,
                EscrowEnvironment.SANDBOX, null, null, null, null, null, null, null, null,
                "900900111", "Operator Settlement", "90099", "900", 10, null, null));
        return new Fixture(consumer, escrow, "900000000" + (n % 10));
    }

    private String createAndPay(Fixture f, ChargeType type, String amount, String bankRef) {
        var outcome = chargeService.create(f.consumer(), new CreateChargeRequest(
                "bh-ref-" + SEQ.incrementAndGet(), "Student", null, null, type, new BigDecimal(amount), null,
                List.of(new ChargeAccountRequest(f.escrow().getCode(), f.vaNumber()))));
        paymentService.apply(f.escrow(), f.vaNumber(), new BigDecimal(amount), bankRef, Instant.now());
        return outcome.response().id();
    }

    private List<WebhookDelivery> deliveriesFor(String consumerId, List<WebhookSendTask> claimed) {
        return claimed.stream().filter(t -> t.consumerId().equals(consumerId))
                .map(t -> webhookRepository.findById(t.deliveryId()).orElseThrow()).toList();
    }

    private void addDueDeliveries(Consumer consumer, Charge charge, int count) {
        for (int i = 0; i < count; i++) {
            WebhookDelivery d = new WebhookDelivery();
            d.setConsumer(consumer);
            d.setCharge(charge);
            d.setEventType(WebhookEventType.PAYMENT_RECEIVED);
            d.setTargetUrl(consumer.getWebhookUrl());
            d.setPayload("{}");
            d.setStatus(WebhookStatus.PENDING);
            d.setAttempts(0);
            d.setMaxAttempts(6);
            d.setNextAttemptAt(Instant.now());
            webhookRepository.save(d);
        }
    }

    @Test
    void claimBatch_capsPerConsumerSoOneBacklogCannotMonopolize() throws Exception {
        Fixture heavy = seed(startReceiver(200));
        String heavyCharge = createAndPay(heavy, ChargeType.CLOSED, "1000000", "BSI-H");
        // Push the heavy consumer well past the per-consumer cap (4) with extra due rows.
        addDueDeliveries(heavy.consumer(), chargeRepository.findById(heavyCharge).orElseThrow(), 8);

        Fixture light = seed(startReceiver(200));
        createAndPay(light, ChargeType.OPEN, "50000", "BSI-L");

        List<WebhookSendTask> claimed = webhookService.claimBatch();

        long heavyClaimed = claimed.stream().filter(t -> t.consumerId().equals(heavy.consumer().getId())).count();
        long lightClaimed = claimed.stream().filter(t -> t.consumerId().equals(light.consumer().getId())).count();
        // Heavy is capped; light is not starved despite the heavy backlog.
        assertThat(heavyClaimed).isEqualTo(4);
        assertThat(lightClaimed).isEqualTo(1);
        // Claimed rows are marked SENDING (won't be re-picked next poll while in flight).
        assertThat(deliveriesFor(heavy.consumer().getId(), claimed))
                .allMatch(d -> d.getStatus() == WebhookStatus.SENDING);
    }

    @Test
    void suspendedConsumer_isExcludedFromClaim_thenResumed() throws Exception {
        Fixture f = seed(startReceiver(200));
        createAndPay(f, ChargeType.CLOSED, "1000000", "BSI-S");

        consumerService.setWebhookSuspended(f.consumer().getId(), true);
        assertThat(webhookService.claimBatch())
                .noneMatch(t -> t.consumerId().equals(f.consumer().getId()));

        consumerService.setWebhookSuspended(f.consumer().getId(), false);
        assertThat(webhookService.claimBatch())
                .anyMatch(t -> t.consumerId().equals(f.consumer().getId()));
    }

    @Test
    void terminalFailure_recordsAuditAlert() throws Exception {
        Fixture f = seed(startReceiver(500));
        String chargeId = createAndPay(f, ChargeType.OPEN, "50000", "BSI-F");
        WebhookDelivery d = webhookRepository.findByChargeIdOrderByCreatedAtAsc(chargeId).getFirst();
        d.setAttempts(d.getMaxAttempts() - 1);
        webhookRepository.save(d);

        webhookService.recordResult(d.getId(), new WebhookSendResult(false, 500, "HTTP 500"));

        WebhookDelivery reloaded = webhookRepository.findById(d.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(WebhookStatus.FAILED);
        assertThat(auditRepository.findByEntityId(d.getId()))
                .extracting(AuditEvent::getEventType).contains("WEBHOOK_DELIVERY_FAILED");
    }

    @Test
    void replayFailed_requeuesAndAudits() throws Exception {
        Fixture f = seed(startReceiver(500));
        String chargeId = createAndPay(f, ChargeType.OPEN, "50000", "BSI-R");
        WebhookDelivery d = webhookRepository.findByChargeIdOrderByCreatedAtAsc(chargeId).getFirst();
        d.setAttempts(d.getMaxAttempts() - 1);
        webhookRepository.save(d);
        webhookService.recordResult(d.getId(), new WebhookSendResult(false, 500, "HTTP 500"));
        assertThat(webhookRepository.findById(d.getId()).orElseThrow().getStatus()).isEqualTo(WebhookStatus.FAILED);

        int requeued = webhookService.replayFailed(f.consumer().getId());

        assertThat(requeued).isEqualTo(1);
        WebhookDelivery reloaded = webhookRepository.findById(d.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(WebhookStatus.PENDING);
        assertThat(reloaded.getAttempts()).isZero();
        assertThat(auditRepository.findByEntityId(f.consumer().getId()))
                .extracting(AuditEvent::getEventType).contains("WEBHOOK_REPLAY");
    }

    @Test
    void failedCount_andAnalysisListing_surfaceFailedDeliveries() throws Exception {
        Fixture f = seed(startReceiver(500));
        String chargeId = createAndPay(f, ChargeType.OPEN, "50000", "BSI-FC");
        WebhookDelivery d = webhookRepository.findByChargeIdOrderByCreatedAtAsc(chargeId).getFirst();
        d.setAttempts(d.getMaxAttempts() - 1);
        webhookRepository.save(d);
        webhookService.recordResult(d.getId(), new WebhookSendResult(false, 500, "HTTP 500"));

        assertThat(webhookService.failedCount(f.consumer().getId())).isEqualTo(1);
        assertThat(webhookService.listByStatus(WebhookStatus.FAILED, f.consumer().getCode()))
                .extracting(WebhookDelivery::getId).contains(d.getId());
    }

    @Test
    void replayDelivery_requeuesSingleFailed() throws Exception {
        Fixture f = seed(startReceiver(500));
        String chargeId = createAndPay(f, ChargeType.OPEN, "50000", "BSI-RD");
        WebhookDelivery d = webhookRepository.findByChargeIdOrderByCreatedAtAsc(chargeId).getFirst();
        d.setAttempts(d.getMaxAttempts() - 1);
        webhookRepository.save(d);
        webhookService.recordResult(d.getId(), new WebhookSendResult(false, 500, "HTTP 500"));

        boolean requeued = webhookService.replayDelivery(d.getId());

        assertThat(requeued).isTrue();
        WebhookDelivery reloaded = webhookRepository.findById(d.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(WebhookStatus.PENDING);
        assertThat(reloaded.getAttempts()).isZero();
    }

    @Test
    void httpConnectionLimits_areExplicitlyConfigured() {
        var http = webhookProperties.http();
        // Fail-loud config: @Validated @NotNull would block startup if these were missing.
        assertThat(http).isNotNull();
        assertThat(http.maxConnectionsPerHost()).isPositive();
        assertThat(http.connectTimeoutMs()).isPositive();
        assertThat(http.pendingAcquireTimeoutMs()).isPositive();
    }

    @Test
    void dispatcher_deliversClaimedBatchThroughBulkhead() throws Exception {
        Fixture f = seed(startReceiver(200));
        String chargeId = createAndPay(f, ChargeType.CLOSED, "1000000", "BSI-D");

        webhookDispatcher.dispatchDueAndAwait();

        // Both events for this charge are delivered (claim -> send on a virtual thread -> record).
        assertThat(webhookRepository.findByChargeIdOrderByCreatedAtAsc(chargeId))
                .allMatch(d -> d.getStatus() == WebhookStatus.DELIVERED);
        assertThat(received).hasSizeGreaterThanOrEqualTo(2);
    }
}
