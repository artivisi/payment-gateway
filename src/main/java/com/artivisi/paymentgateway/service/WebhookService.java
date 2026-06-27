package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.config.WebhookProperties;
import com.artivisi.paymentgateway.entity.Charge;
import com.artivisi.paymentgateway.entity.Payment;
import com.artivisi.paymentgateway.entity.WebhookDelivery;
import com.artivisi.paymentgateway.entity.WebhookEventType;
import com.artivisi.paymentgateway.entity.WebhookStatus;
import com.artivisi.paymentgateway.repository.WebhookDeliveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Webhook outbox + delivery bookkeeping. The send itself lives in {@link WebhookSender} and runs
 * outside any transaction; this service only touches the DB, in three short steps:
 * <ul>
 *   <li>{@link #enqueue} — persist a delivery row in the caller's transaction (atomic with the payment).</li>
 *   <li>{@link #claimBatch} — mark a fair batch of due rows SENDING and snapshot them for sending.</li>
 *   <li>{@link #recordResult} — apply a send outcome (delivered / backoff retry / terminal FAILED).</li>
 * </ul>
 * No HTTP call ever holds a DB connection, so a slow consumer cannot exhaust the pool. Fairness and
 * per-consumer concurrency are enforced by the claim cap here plus the dispatcher's per-consumer lanes.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private final WebhookDeliveryRepository repository;
    private final WebhookSender sender;
    private final WebhookProperties properties;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    public WebhookService(WebhookDeliveryRepository repository, WebhookSender sender,
                          WebhookProperties properties, ObjectMapper objectMapper, AuditService auditService) {
        this.repository = repository;
        this.sender = sender;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
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

    /**
     * Claim a fair batch of due deliveries: flip each to SENDING and return a self-contained send
     * task. At most {@code perConsumerConcurrency} rows per consumer are taken per round so one
     * consumer's backlog cannot monopolize the batch. Suspended consumers are excluded by the query.
     */
    @Transactional
    public List<WebhookSendTask> claimBatch() {
        Instant now = Instant.now();
        Instant staleBefore = now.minusSeconds(properties.staleSendingSeconds());
        int cap = properties.perConsumerConcurrency();
        int batch = properties.batchSize();
        // Over-fetch candidates so the fair cap has rows from multiple consumers to choose among.
        List<WebhookDelivery> candidates = repository.findClaimable(
                WebhookStatus.PENDING, WebhookStatus.SENDING, now, staleBefore,
                PageRequest.of(0, batch * 5));

        Map<String, Integer> perConsumer = new HashMap<>();
        List<WebhookSendTask> tasks = new ArrayList<>();
        for (WebhookDelivery d : candidates) {
            if (tasks.size() >= batch) {
                break;
            }
            String consumerId = d.getConsumer().getId();
            if (perConsumer.merge(consumerId, 1, Integer::sum) > cap) {
                continue;
            }
            d.setStatus(WebhookStatus.SENDING);
            repository.save(d);
            tasks.add(buildTask(d));
        }
        return tasks;
    }

    /** Apply a send outcome to one delivery. On terminal failure, logs an alert + records an audit event. */
    @Transactional
    public void recordResult(String deliveryId, WebhookSendResult result) {
        WebhookDelivery delivery = repository.findByIdWithConsumer(deliveryId).orElse(null);
        if (delivery == null) {
            return;
        }
        if (result.delivered()) {
            delivery.setStatus(WebhookStatus.DELIVERED);
            delivery.setLastResponseCode(result.responseCode());
            delivery.setLastError(null);
        } else {
            delivery.setAttempts(delivery.getAttempts() + 1);
            delivery.setLastResponseCode(result.responseCode());
            delivery.setLastError(result.error());
            if (delivery.getAttempts() >= delivery.getMaxAttempts()) {
                delivery.setStatus(WebhookStatus.FAILED);
                alertFailed(delivery);
            } else {
                long backoff = (long) properties.backoffBaseSeconds() * (1L << (delivery.getAttempts() - 1));
                delivery.setStatus(WebhookStatus.PENDING);
                delivery.setNextAttemptAt(Instant.now().plusSeconds(backoff));
            }
        }
        repository.save(delivery);
    }

    /**
     * Synchronous single-delivery send (load → send → record). Used for isolated dispatch in tests;
     * the dispatcher uses {@link #claimBatch} + {@link #recordResult} with per-consumer lanes.
     */
    public void attempt(String deliveryId) {
        WebhookDelivery delivery = repository.findByIdWithConsumer(deliveryId).orElse(null);
        if (delivery == null
                || (delivery.getStatus() != WebhookStatus.PENDING && delivery.getStatus() != WebhookStatus.SENDING)) {
            return;
        }
        WebhookSendResult result = sender.send(buildTask(delivery));
        recordResult(deliveryId, result);
    }

    /** Ops recovery: requeue every FAILED delivery for a consumer (attempts reset, due now). */
    @Transactional
    public int replayFailed(String consumerId) {
        List<WebhookDelivery> failed = repository.findByConsumerIdAndStatus(consumerId, WebhookStatus.FAILED);
        Instant now = Instant.now();
        for (WebhookDelivery d : failed) {
            d.setStatus(WebhookStatus.PENDING);
            d.setAttempts(0);
            d.setNextAttemptAt(now);
            d.setLastError(null);
            repository.save(d);
        }
        if (!failed.isEmpty()) {
            auditService.record("WEBHOOK_REPLAY", "Consumer", consumerId, "requeued=" + failed.size());
        }
        return failed.size();
    }

    private void alertFailed(WebhookDelivery delivery) {
        log.error("Webhook delivery FAILED permanently: deliveryId={} consumer={} charge={} event={} "
                        + "attempts={} lastResponseCode={} lastError={}",
                delivery.getId(), delivery.getConsumer().getCode(), delivery.getCharge().getId(),
                delivery.getEventType(), delivery.getAttempts(), delivery.getLastResponseCode(),
                delivery.getLastError());
        auditService.record("WEBHOOK_DELIVERY_FAILED", "WebhookDelivery", delivery.getId(),
                "consumer=" + delivery.getConsumer().getCode()
                        + " charge=" + delivery.getCharge().getId()
                        + " event=" + delivery.getEventType()
                        + " attempts=" + delivery.getAttempts()
                        + " lastResponseCode=" + delivery.getLastResponseCode());
    }

    private WebhookSendTask buildTask(WebhookDelivery d) {
        return new WebhookSendTask(d.getId(), d.getConsumer().getId(), d.getTargetUrl(), d.getPayload(),
                d.getEventType().name(), d.getConsumer().getClientSecret(), d.getAttempts(), d.getMaxAttempts());
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
}
