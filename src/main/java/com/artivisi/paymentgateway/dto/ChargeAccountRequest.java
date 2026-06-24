package com.artivisi.paymentgateway.dto;

import jakarta.validation.constraints.NotBlank;

/** One target bank rail for a charge: the escrow and the consumer-supplied VA number. */
public record ChargeAccountRequest(
        @NotBlank String escrowCode,
        @NotBlank String vaNumber
) {
}
