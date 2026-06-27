package com.artivisi.paymentgateway.service;

/**
 * Self-contained snapshot of a claimed webhook delivery, carrying everything the send needs
 * (incl. the decrypted signing secret) so the HTTP call runs outside any DB transaction.
 */
public record WebhookSendTask(
        String deliveryId,
        String consumerId,
        String targetUrl,
        String payload,
        String eventType,
        String secret,
        int attempts,
        int maxAttempts
) {
}
