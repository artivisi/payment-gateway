package com.artivisi.paymentgateway.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Move a charge's deadline.
 *
 * <p>Named "extend" for the usual case, but the new instant may be earlier as well — a bill whose
 * due date was corrected downwards is the same operation.
 */
public record ExtendChargeRequest(@NotNull Instant expiresAt) {
}
