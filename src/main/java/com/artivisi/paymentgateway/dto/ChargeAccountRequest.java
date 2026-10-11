package com.artivisi.paymentgateway.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * One target bank rail for a charge: the escrow, and nothing else.
 *
 * <p>The VA number used to be here, supplied by the consumer. The gateway allocates it now, in that
 * escrow's own layout, from the charge's {@code payerKey} and {@code slotKey} — so naming the escrow
 * is the whole of what a consumer has to know about a bank.
 */
public record ChargeAccountRequest(
        @NotBlank String escrowCode
) {
}
