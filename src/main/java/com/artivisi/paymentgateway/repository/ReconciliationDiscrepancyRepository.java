package com.artivisi.paymentgateway.repository;

import com.artivisi.paymentgateway.entity.DiscrepancyType;
import com.artivisi.paymentgateway.entity.ReconciliationDiscrepancy;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ReconciliationDiscrepancyRepository extends JpaRepository<ReconciliationDiscrepancy, String> {

    List<ReconciliationDiscrepancy> findByReconciliationRunIdOrderByCreatedAtAsc(String reconciliationRunId);

    /** Discrepancies flagged by a run started at or after {@code since} — no resolved/ack state exists yet. */
    long countByReconciliationRunStartedAtGreaterThanEqual(Instant since);

    /**
     * Every discrepancy across every run, newest first, optionally of one type.
     *
     * <p>Finance works a class of problem — "what did we record that the bank never settled" — not a
     * particular reconciliation run, and would otherwise have to open each run in turn to find them.
     * The run and its escrow are fetched with it because the list is meaningless without the account
     * and period each row belongs to.
     */
    @Query("""
            select d from ReconciliationDiscrepancy d
              join fetch d.reconciliationRun r
              join fetch r.escrowAccount
             where (:type is null or d.type = :type)
             order by r.period desc, d.createdAt desc
            """)
    List<ReconciliationDiscrepancy> findForReview(@Param("type") DiscrepancyType type, Pageable pageable);
}
