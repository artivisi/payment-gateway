package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.entity.WebhookDelivery;
import com.artivisi.paymentgateway.entity.WebhookStatus;
import com.artivisi.paymentgateway.repository.WebhookDeliveryRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Polls the webhook outbox and delivers due rows. Each delivery is attempted in its own
 * transaction via {@link WebhookService#attempt} (cross-bean call so {@code @Transactional} applies).
 */
@Component
public class WebhookDispatcher {

    private static final int BATCH_SIZE = 50;

    private final WebhookDeliveryRepository repository;
    private final WebhookService webhookService;

    public WebhookDispatcher(WebhookDeliveryRepository repository, WebhookService webhookService) {
        this.repository = repository;
        this.webhookService = webhookService;
    }

    @Scheduled(fixedDelayString = "${gateway.webhook.poll-interval-ms}")
    public int dispatchDue() {
        List<WebhookDelivery> due = repository
                .findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                        WebhookStatus.PENDING, Instant.now(), PageRequest.of(0, BATCH_SIZE));
        for (WebhookDelivery delivery : due) {
            webhookService.attempt(delivery.getId());
        }
        return due.size();
    }
}
