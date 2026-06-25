package com.artivisi.paymentgateway.dto;

import com.artivisi.paymentgateway.entity.DiscrepancyType;
import com.artivisi.paymentgateway.entity.ReconciliationDiscrepancy;
import com.artivisi.paymentgateway.entity.ReconciliationRun;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ReconciliationSummary(
        String runId,
        LocalDate period,
        Integer matchedCount,
        Integer recoveredCount,
        Integer discrepancyCount,
        List<Discrepancy> discrepancies
) {
    public record Discrepancy(DiscrepancyType type, String vaNumber, String bankReference,
                              BigDecimal amount, String detail) {
        static Discrepancy from(ReconciliationDiscrepancy d) {
            return new Discrepancy(d.getType(), d.getVaNumber(), d.getBankReference(), d.getAmount(), d.getDetail());
        }
    }

    public static ReconciliationSummary of(ReconciliationRun run, List<ReconciliationDiscrepancy> discrepancies) {
        return new ReconciliationSummary(run.getId(), run.getPeriod(), run.getMatchedCount(),
                run.getRecoveredCount(), run.getDiscrepancyCount(),
                discrepancies.stream().map(Discrepancy::from).toList());
    }
}
