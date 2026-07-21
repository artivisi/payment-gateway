package com.artivisi.paymentgateway.dto;

import com.artivisi.paymentgateway.entity.ChargeType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record CreateChargeRequest(
        @NotBlank String consumerReference,
        @NotBlank String payerName,
        String payerEmail,
        String payerPhone,
        @NotNull ChargeType chargeType,
        @NotNull @Positive BigDecimal amount,
        Instant expiresAt,
        @NotEmpty List<@Valid ChargeAccountRequest> accounts,
        String description
) {
    /** Source-compatibility overload for callers predating the {@code description} field (defaults null). */
    public CreateChargeRequest(String consumerReference, String payerName, String payerEmail, String payerPhone,
                               ChargeType chargeType, BigDecimal amount, Instant expiresAt,
                               List<ChargeAccountRequest> accounts) {
        this(consumerReference, payerName, payerEmail, payerPhone, chargeType, amount, expiresAt, accounts, null);
    }
}
