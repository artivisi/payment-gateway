package com.artivisi.paymentgateway.repository;

import com.artivisi.paymentgateway.entity.ReconciliationDiscrepancy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReconciliationDiscrepancyRepository extends JpaRepository<ReconciliationDiscrepancy, String> {

    List<ReconciliationDiscrepancy> findByReconciliationRunIdOrderByCreatedAtAsc(String reconciliationRunId);
}
