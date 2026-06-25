package com.artivisi.paymentgateway.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** One credit line from a bank's settlement (pulled or imported) for reconciliation. */
public record SettlementCredit(
        String vaNumber,
        String bankReference,
        BigDecimal amount,
        Instant transactionTime
) {
}
