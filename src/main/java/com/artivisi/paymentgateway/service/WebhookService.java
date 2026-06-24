package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.config.WebhookProperties;
import com.artivisi.paymentgateway.entity.Charge;
import com.artivisi.paymentgateway.entity.Payment;
import com.artivisi.paymentgateway.entity.WebhookDelivery;
import com.artivisi.paymentgateway.entity.WebhookEventType;
import com.artivisi.paymentgateway.entity.WebhookStatus;
import com.artivisi.paymentgateway.repository.WebhookDeliveryRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Webhook outbox + delivery. {@link #enqueue} persists a delivery row in the caller's
 * transaction (atomic with the payment). {@link #attempt} signs and sends a single delivery,
 * recording success or scheduling a backoff retry. The HTTP call happens inside the attempt
 * transaction — acceptable for the single-threaded dispatcher with a short timeout.
 */
@Service
public class WebhookService {

    private static final String SIGNATURE_HEADER = "X-Signature";
    private static final String EVENT_HEADER = "X-Webhook-Event";
    private static final String ID_HEADER = "X-Webhook-Id";

    private final WebhookDeliveryRepository repository;
    private final WebClient webClient;
    private final WebhookProperties properties;
    private final ObjectMapper objectMapper;

    public WebhookService(WebhookDeliveryRepository repository, WebClient webClient,
                          WebhookProperties properties, ObjectMapper objectMapper) {
        this.repository = repository;
        this.webClient = webClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void enqueue(Charge charge, Payment payment, WebhookEventType type) {
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setConsumer(charge.getConsumer());
        delivery.setCharge(charge);
        delivery.setPayment(payment);
        delivery.setEventType(type);
        delivery.setTargetUrl(charge.getConsumer().getWebhookUrl());
        delivery.setPayload(buildPayload(charge, payment, type));
        delivery.setStatus(WebhookStatus.PENDING);
        delivery.setAttempts(0);
        delivery.setMaxAttempts(properties.maxAttempts());
        delivery.setNextAttemptAt(Instant.now());
        repository.save(delivery);
    }

    @Transactional
    public void attempt(String id) {
        WebhookDelivery delivery = repository.findById(id).orElse(null);
        if (delivery == null || delivery.getStatus() != WebhookStatus.PENDING) {
            return;
        }
        String signature = sign(delivery.getPayload(), delivery.getConsumer().getClientSecret());
        try {
            webClient.post().uri(delivery.getTargetUrl())
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header(EVENT_HEADER, delivery.getEventType().name())
                    .header(ID_HEADER, delivery.getId())
                    .header(SIGNATURE_HEADER, signature)
                    .bodyValue(delivery.getPayload())
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(properties.requestTimeoutSeconds()));
            delivery.setStatus(WebhookStatus.DELIVERED);
            delivery.setLastResponseCode(200);
            delivery.setLastError(null);
        } catch (WebClientResponseException e) {
            recordFailure(delivery, e.getStatusCode().value(), "HTTP " + e.getStatusCode().value());
        } catch (Exception e) {
            recordFailure(delivery, null, truncate(e.getMessage()));
        }
        repository.save(delivery);
    }

    private void recordFailure(WebhookDelivery delivery, Integer responseCode, String error) {
        delivery.setAttempts(delivery.getAttempts() + 1);
        delivery.setLastResponseCode(responseCode);
        delivery.setLastError(error);
        if (delivery.getAttempts() >= delivery.getMaxAttempts()) {
            delivery.setStatus(WebhookStatus.FAILED);
        } else {
            long backoff = (long) properties.backoffBaseSeconds() * (1L << (delivery.getAttempts() - 1));
            delivery.setNextAttemptAt(Instant.now().plusSeconds(backoff));
        }
    }

    private String buildPayload(Charge charge, Payment payment, WebhookEventType type) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventType", type.name());
        body.put("chargeId", charge.getId());
        body.put("consumerReference", charge.getConsumerReference());
        body.put("chargeType", charge.getChargeType().name());
        body.put("chargeStatus", charge.getStatus().name());
        body.put("totalAmount", charge.getAmount());
        body.put("cumulativePaid", charge.getCumulativePaid());
        body.put("remainingAmount", charge.getAmount().subtract(charge.getCumulativePaid()));
        if (payment != null) {
            body.put("escrowCode", payment.getVirtualAccount().getEscrowAccount().getCode());
            body.put("vaNumber", payment.getVirtualAccount().getVaNumber());
            body.put("bankReference", payment.getBankReference());
            body.put("paymentAmount", payment.getAmount());
        }
        // Jackson 3 (tools.jackson) serialization throws unchecked on failure.
        return objectMapper.writeValueAsString(body);
    }

    private String sign(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("failed to sign webhook payload", e);
        }
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
