package com.artivisi.paymentgateway.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Required security config. A missing {@code gateway.security.secret-key}
 * fails startup explicitly (fail loud, no default key).
 */
@Validated
@ConfigurationProperties(prefix = "gateway.security")
public record GatewaySecurityProperties(@NotBlank String secretKey) {
}
