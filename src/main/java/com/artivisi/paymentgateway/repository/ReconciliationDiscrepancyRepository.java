package com.artivisi.paymentgateway.repository;

import com.artivisi.paymentgateway.entity.ReconciliationDiscrepancy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ReconciliationDiscrepancyRepository extends JpaRepository<ReconciliationDiscrepancy, String> {

    List<ReconciliationDiscrepancy> findByReconciliationRunIdOrderByCreatedAtAsc(String reconciliationRunId);

    /** Discrepancies flagged by a run started at or after {@code since} — no resolved/ack state exists yet. */
    long countByReconciliationRunStartedAtGreaterThanEqual(Instant since);
}
