package com.artivisi.paymentgateway.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * Import-statement reconciliation request: the settlement credits for an escrow + period.
 *
 * <p>{@code amountBasis} is required and deliberately has no default. The caller knows which
 * document they are holding; the importer cannot tell a statement's net figures from a transaction
 * list's gross ones, and guessing wrong makes every row an amount mismatch.
 */
public record ReconciliationRequest(
        @NotNull LocalDate period,
        @NotNull List<SettlementCredit> credits,
        @NotNull SettlementAmountBasis amountBasis
) {
}
