package com.artivisi.paymentgateway.dto;

import com.artivisi.paymentgateway.entity.Consumer;
import com.artivisi.paymentgateway.entity.ConsumerStatus;

import java.time.Instant;

/** Consumer view for the admin API. Omits {@code clientSecret} — never returned. */
public record ConsumerResponse(
        String id,
        String code,
        String name,
        String clientId,
        String webhookUrl,
        ConsumerStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    public static ConsumerResponse from(Consumer c) {
        return new ConsumerResponse(
                c.getId(), c.getCode(), c.getName(), c.getClientId(),
                c.getWebhookUrl(), c.getStatus(), c.getCreatedAt(), c.getUpdatedAt());
    }
}
