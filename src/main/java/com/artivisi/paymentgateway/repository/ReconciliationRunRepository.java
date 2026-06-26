package com.artivisi.paymentgateway.repository;

import com.artivisi.paymentgateway.entity.ReconciliationRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, String> {

    @Query("select r from ReconciliationRun r join fetch r.escrowAccount order by r.createdAt desc")
    List<ReconciliationRun> findRecentWithEscrow(Pageable pageable);

    @Query("select r from ReconciliationRun r join fetch r.escrowAccount where r.id = :id")
    Optional<ReconciliationRun> findByIdWithEscrow(@Param("id") String id);
}
