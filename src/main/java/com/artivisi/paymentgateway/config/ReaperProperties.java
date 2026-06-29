package com.artivisi.paymentgateway.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Expiry reaper tuning. Required; no code defaults. */
@Validated
@ConfigurationProperties(prefix = "gateway.reaper")
public record ReaperProperties(
        /** How often the reaper sweeps for expired charges, in milliseconds. */
        @NotNull @Positive Long intervalMs
) {
}
