package com.artivisi.paymentgateway.repository;

import com.artivisi.paymentgateway.entity.AnalysisReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AnalysisReportRepository extends JpaRepository<AnalysisReport, String> {

    Optional<AnalysisReport> findFirstByKindOrderByGeneratedAtDesc(String kind);

    List<AnalysisReport> findByKindOrderByGeneratedAtDesc(String kind);

    List<AnalysisReport> findAllByOrderByGeneratedAtDesc();
}
