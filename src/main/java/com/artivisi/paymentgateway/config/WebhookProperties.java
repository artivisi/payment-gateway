package com.artivisi.paymentgateway.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Webhook delivery tuning. All values are required (configured in application.yml; no code defaults). */
@Validated
@ConfigurationProperties(prefix = "gateway.webhook")
public record WebhookProperties(
        @NotNull @Positive Integer maxAttempts,
        @NotNull @Positive Integer backoffBaseSeconds,
        @NotNull @Positive Long pollIntervalMs,
        @NotNull @Positive Integer requestTimeoutSeconds
) {
}
