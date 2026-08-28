package com.artivisi.paymentgateway.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Change what an open charge is worth — the amount an inquiry answers from now on.
 *
 * <p>The consumer owns the debt and decides the figure; the gateway only has to make the bank see
 * it. For a CLOSED charge this is the exact amount the next payment must carry. A receivables
 * application uses it to walk one VA through an instalment plan: the number never changes, the
 * amount due does.
 */
public record RepriceChargeRequest(@NotNull @Positive BigDecimal amount) {
}
