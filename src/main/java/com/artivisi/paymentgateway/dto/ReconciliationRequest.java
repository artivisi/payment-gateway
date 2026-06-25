package com.artivisi.paymentgateway.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/** Import-statement reconciliation request: the settlement credits for an escrow + period. */
public record ReconciliationRequest(
        @NotNull LocalDate period,
        @NotNull List<SettlementCredit> credits
) {
}
