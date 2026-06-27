package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.config.WebhookProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Polls the webhook outbox and delivers due rows with a per-consumer bulkhead.
 *
 * <p>Each poll claims a fair batch ({@link WebhookService#claimBatch}, which flips rows to SENDING so
 * the next poll won't re-pick in-flight work) and dispatches each send on its own <b>virtual thread</b>.
 * A {@link Semaphore} per consumer caps concurrent in-flight deliveries, so a slow or unresponsive
 * endpoint can only ever occupy its own lane — its excess deliveries park cheaply on a virtual thread
 * while healthy consumers keep flowing. The HTTP send runs outside any DB transaction, so a slow
 * endpoint never holds a connection from the pool.
 */
@Component
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final WebhookService webhookService;
    private final WebhookSender sender;
    private final WebhookProperties properties;
    private final ExecutorService vexec = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<String, Semaphore> lanes = new ConcurrentHashMap<>();

    public WebhookDispatcher(WebhookService webhookService, WebhookSender sender, WebhookProperties properties) {
        this.webhookService = webhookService;
        this.sender = sender;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${gateway.webhook.poll-interval-ms}")
    public void dispatchDue() {
        submitBatch();
    }

    /** Test hook: claim + dispatch one batch and block until every send in it has finished. */
    public int dispatchDueAndAwait() {
        List<Future<?>> futures = submitBatch();
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted awaiting webhook batch", e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("webhook delivery task failed", e.getCause());
            }
        }
        return futures.size();
    }

    private List<Future<?>> submitBatch() {
        List<WebhookSendTask> tasks = webhookService.claimBatch();
        List<Future<?>> futures = new ArrayList<>(tasks.size());
        for (WebhookSendTask task : tasks) {
            futures.add(vexec.submit(() -> deliver(task)));
        }
        return futures;
    }

    private void deliver(WebhookSendTask task) {
        Semaphore lane = lanes.computeIfAbsent(task.consumerId(),
                k -> new Semaphore(properties.perConsumerConcurrency()));
        try {
            lane.acquire();
            try {
                WebhookSendResult result = sender.send(task);
                webhookService.recordResult(task.deliveryId(), result);
            } finally {
                lane.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            // Bookkeeping failure (not a send failure, which is captured in the result): the row stays
            // SENDING and is reclaimed once stale. Log and move on so one bad row can't wedge the lane.
            log.error("Webhook recordResult failed for deliveryId={}", task.deliveryId(), e);
        }
    }

    @PreDestroy
    void shutdown() {
        vexec.shutdown();
    }
}
