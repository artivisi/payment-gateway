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

/**
 * {@code payerKey} and {@code slotKey} are what the gateway allocates every target escrow's VA
 * number from, and they are deliberately the only number-space inputs a consumer provides.
 *
 * <p>{@code payerKey} is the consumer's own stable identifier for the payer — a student id, a member
 * id. It must stay the same across that payer's charges, because the number is derived from it and
 * payers keep the number in their banking app. {@code slotKey} is a short numeric key separating one
 * payer's concurrent VAs; a consumer maps its own vocabulary (bill category, invoice type) onto slot
 * values, and the gateway never learns what a slot means.
 *
 * <p>Neither is a bank fact, which is the point: the same pair allocates a number at every escrow
 * named in {@code accounts}, each in that escrow's own layout.
 */
public record CreateChargeRequest(
        @NotBlank String consumerReference,
        @NotBlank String payerName,
        String payerEmail,
        String payerPhone,
        @NotNull ChargeType chargeType,
        @NotNull @Positive BigDecimal amount,
        Instant expiresAt,
        @NotEmpty List<@Valid ChargeAccountRequest> accounts,
        String description,
        String billNumber,
        @NotBlank String payerKey,
        @NotBlank String slotKey
) {
}
