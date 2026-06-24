package com.artivisi.paymentgateway.dto;

import com.artivisi.paymentgateway.entity.ConsumerStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ConsumerRequest(
        @NotBlank String code,
        @NotBlank String name,
        @NotBlank String clientId,
        @NotBlank String clientSecret,
        @NotBlank String webhookUrl,
        @NotNull ConsumerStatus status
) {
}
