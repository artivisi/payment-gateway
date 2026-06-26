package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.AbstractIntegrationTest;
import com.artivisi.paymentgateway.dto.ChargeAccountRequest;
import com.artivisi.paymentgateway.dto.ConsumerRequest;
import com.artivisi.paymentgateway.dto.CreateChargeRequest;
import com.artivisi.paymentgateway.dto.EscrowAccountRequest;
import com.artivisi.paymentgateway.entity.AuthScheme;
import com.artivisi.paymentgateway.entity.ChargeType;
import com.artivisi.paymentgateway.entity.Consumer;
import com.artivisi.paymentgateway.entity.ConsumerStatus;
import com.artivisi.paymentgateway.entity.EscrowAccount;
import com.artivisi.paymentgateway.entity.EscrowEnvironment;
import com.artivisi.paymentgateway.entity.HostingModel;
import com.artivisi.paymentgateway.entity.TransportProtocol;
import com.artivisi.paymentgateway.entity.WebhookDelivery;
import com.artivisi.paymentgateway.entity.WebhookStatus;
import com.artivisi.paymentgateway.repository.WebhookDeliveryRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookDeliveryIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired EscrowAccountService escrowService;
    @Autowired ConsumerService consumerService;
    @Autowired ChargeService chargeService;
    @Autowired PaymentApplicationService paymentService;
    @Autowired WebhookService webhookService;
    @Autowired WebhookDeliveryRepository webhookRepository;

    private HttpServer server;
    private final List<Captured> received = Collections.synchronizedList(new ArrayList<>());

    private record Captured(String body, String signature, String event) {
    }

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
            byte[] body = exchange.getRequestBody().readAllBytes();
            received.add(new Captured(
                    new String(body, StandardCharsets.UTF_8),
                    exchange.getRequestHeaders().getFirst("X-Signature"),
                    exchange.getRequestHeaders().getFirst("X-Webhook-Event")));
            exchange.sendResponseHeaders(responseCode, -1);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    private static EscrowAccountRequest escrowRequest(String code) {
        return new EscrowAccountRequest(code, "bsi", HostingModel.SELF_HOSTED, TransportProtocol.REST_JSON,
                AuthScheme.PROPRIETARY, EscrowEnvironment.SANDBOX, null, null, null, null, null, null, null, null,
                "900900111", "Operator Settlement", "90099", "900", 10, null, null);
    }

    private record Fixture(Consumer consumer, EscrowAccount escrow, String secret, String vaNumber) {
    }

    private Fixture seed(String webhookUrl) {
        int n = SEQ.incrementAndGet();
        String secret = "wh-secret-" + n;
        Consumer consumer = consumerService.create(new ConsumerRequest(
                "wh-consumer-" + n, "Academic", "wh-client-" + n, secret, webhookUrl, ConsumerStatus.ACTIVE));
        EscrowAccount escrow = escrowService.create(escrowRequest("wh-bsi-" + n));
        return new Fixture(consumer, escrow, secret, "900000000" + (n % 10));
    }

    private String createAndPay(Fixture f, ChargeType type, String amount, String bankRef) {
        var outcome = chargeService.create(f.consumer(), new CreateChargeRequest(
                "wh-ref-" + SEQ.get(), "Student", null, null, type, new java.math.BigDecimal(amount), null,
                List.of(new ChargeAccountRequest(f.escrow().getCode(), f.vaNumber()))));
        paymentService.apply(f.escrow(), f.vaNumber(), new java.math.BigDecimal(amount), bankRef, Instant.now());
        return outcome.response().id();
    }

    private static String hmac(String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    /** Dispatch only this charge's deliveries (isolated; the global outbox holds other tests' rows). */
    private void dispatchOwn(String chargeId) {
        webhookRepository.findByChargeIdOrderByCreatedAtAsc(chargeId)
                .forEach(d -> webhookService.attempt(d.getId()));
    }

    @Test
    void closedPayment_deliversSignedPaymentAndPaidEvents() throws Exception {
        Fixture f = seed(startReceiver(200));
        String chargeId = createAndPay(f, ChargeType.CLOSED, "1000000", "BSI-REF-1");

        // Outbox has both events pending before dispatch.
        List<WebhookDelivery> before = webhookRepository.findByChargeIdOrderByCreatedAtAsc(chargeId);
        assertThat(before).hasSize(2);
        assertThat(before).allMatch(d -> d.getStatus() == WebhookStatus.PENDING);

        dispatchOwn(chargeId);

        assertThat(received).hasSize(2);
        assertThat(received).extracting(Captured::event)
                .containsExactlyInAnyOrder("PAYMENT_RECEIVED", "CHARGE_PAID");
        for (Captured c : received) {
            assertThat(c.signature()).isEqualTo(hmac(c.body(), f.secret()));
        }
        assertThat(webhookRepository.findByChargeIdOrderByCreatedAtAsc(chargeId))
                .allMatch(d -> d.getStatus() == WebhookStatus.DELIVERED);
    }

    @Test
    void failedDelivery_incrementsAttemptsAndBacksOff() throws Exception {
        Fixture f = seed(startReceiver(500));
        String chargeId = createAndPay(f, ChargeType.OPEN, "50000", "BSI-REF-1");

        dispatchOwn(chargeId);

        WebhookDelivery delivery = webhookRepository.findByChargeIdOrderByCreatedAtAsc(chargeId).getFirst();
        assertThat(delivery.getStatus()).isEqualTo(WebhookStatus.PENDING);
        assertThat(delivery.getAttempts()).isEqualTo(1);
        assertThat(delivery.getLastResponseCode()).isEqualTo(500);
        assertThat(delivery.getNextAttemptAt()).isAfter(Instant.now());
    }

    @Test
    void delivery_failsTerminallyAfterMaxAttempts() throws Exception {
        Fixture f = seed(startReceiver(500));
        String chargeId = createAndPay(f, ChargeType.OPEN, "50000", "BSI-REF-1");

        WebhookDelivery delivery = webhookRepository.findByChargeIdOrderByCreatedAtAsc(chargeId).getFirst();
        delivery.setAttempts(delivery.getMaxAttempts() - 1);
        delivery.setNextAttemptAt(Instant.now());
        webhookRepository.save(delivery);

        dispatchOwn(chargeId);

        WebhookDelivery reloaded = webhookRepository.findById(delivery.getId()).orElseThrow();
        assertThat(reloaded.getAttempts()).isEqualTo(reloaded.getMaxAttempts());
        assertThat(reloaded.getStatus()).isEqualTo(WebhookStatus.FAILED);
    }
}
