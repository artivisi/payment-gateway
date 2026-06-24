package com.artivisi.paymentgateway.repository;

import com.artivisi.paymentgateway.entity.ReconciliationRun;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, String> {
}
