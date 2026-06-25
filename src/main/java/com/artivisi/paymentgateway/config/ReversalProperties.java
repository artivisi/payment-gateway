package com.artivisi.paymentgateway.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Payment reversal window. Required (configured in application.yml; no code default). */
@Validated
@ConfigurationProperties(prefix = "gateway.reversal")
public record ReversalProperties(@NotNull @Positive Integer windowMinutes) {
}
