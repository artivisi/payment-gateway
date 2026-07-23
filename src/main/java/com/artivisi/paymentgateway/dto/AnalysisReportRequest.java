package com.artivisi.paymentgateway.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * An analysis run posted in from outside. The gateway stores and renders it; it does not compute it,
 * because the source data may live anywhere (a legacy system, a warehouse, a one-off query).
 *
 * <p>{@code kind} selects the schema below. Today there is one: {@code collection-aging} — does an
 * unpaid bill ever get paid, and how does that differ by bill type? The answer decides expiry policy,
 * dunning effort, and how much of the receivable ledger is real.
 */
public record AnalysisReportRequest(
        @NotBlank String kind,
        @NotBlank String title,
        /** Where the numbers came from, in the author's words — provenance, not a foreign key. */
        @NotBlank String source,
        String periodLabel,
        @NotNull Instant generatedAt,
        @NotEmpty List<@Valid Cohort> cohorts,
        List<@Valid SurvivalSeries> survival,
        List<String> notes
) {

    /** One population of bills — typically a bill type. */
    public record Cohort(
            @NotBlank String key,
            @NotBlank String label,
            int bills,
            int paid,
            Integer medianDaysToPay,
            BigDecimal amountBilled,
            BigDecimal amountPaid
    ) {
        public double paidPercent() {
            return bills == 0 ? 0 : 100.0 * paid / bills;
        }
    }

    /** "Of the bills still unpaid at day N, how many were EVER paid, and for how much?" */
    public record SurvivalSeries(
            @NotBlank String key,
            @NotBlank String label,
            @NotEmpty List<@Valid Point> points
    ) {
        public record Point(int day, int stillUnpaid, int laterPaid, BigDecimal amountLaterPaid) {
            public double laterPaidPercent() {
                return stillUnpaid == 0 ? 0 : 100.0 * laterPaid / stillUnpaid;
            }
        }
    }
}
