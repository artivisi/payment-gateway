package com.artivisi.paymentgateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Admin-UI security configuration. All values are required (no code defaults; fail loud on startup).
 * The bootstrap account seeds the first ADMIN when the operator table is empty.
 */
@Validated
@ConfigurationProperties(prefix = "gateway.admin")
public record AdminSecurityProperties(
        @NotNull @Valid Bootstrap bootstrap,
        /** Lock the account after this many consecutive failed logins (PCI Req 8.3.4: <= 10). */
        @NotNull @Positive Integer maxFailedAttempts,
        /** How long an account stays locked after the threshold is hit. */
        @NotNull @Positive Integer lockMinutes,
        @NotNull @Valid Mfa mfa
) {

    public record Bootstrap(@NotBlank String username, @NotBlank String password) {
    }

    public record Mfa(@NotBlank String issuer) {
    }
}
