package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.dto.AnalysisReportRequest;
import com.artivisi.paymentgateway.entity.AnalysisReport;
import com.artivisi.paymentgateway.exception.NotFoundException;
import com.artivisi.paymentgateway.repository.AnalysisReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** Stores analysis runs as a series and hands them back for rendering. */
@Service
public class AnalysisReportService {

    private final AnalysisReportRepository repository;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    public AnalysisReportService(AnalysisReportRepository repository, ObjectMapper objectMapper,
                                 AuditService auditService) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    @Transactional
    public AnalysisReport store(AnalysisReportRequest request) {
        AnalysisReport report = new AnalysisReport();
        report.setKind(request.kind());
        report.setTitle(request.title());
        report.setSource(request.source());
        report.setPeriodLabel(request.periodLabel());
        report.setGeneratedAt(request.generatedAt());
        report.setPayload(objectMapper.writeValueAsString(request));
        AnalysisReport saved = repository.save(report);
        auditService.record("ANALYSIS_REPORT_STORED", "AnalysisReport", saved.getId(),
                "kind=" + saved.getKind() + " source=" + saved.getSource());
        return saved;
    }

    @Transactional(readOnly = true)
    public AnalysisReportRequest latest(String kind) {
        return parse(repository.findFirstByKindOrderByGeneratedAtDesc(kind)
                .orElseThrow(() -> new NotFoundException("No analysis report of kind " + kind)));
    }

    @Transactional(readOnly = true)
    public List<AnalysisReport> history() {
        return repository.findAllByOrderByGeneratedAtDesc();
    }

    @Transactional(readOnly = true)
    public AnalysisReportRequest parse(String id) {
        return parse(repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Analysis report not found: " + id)));
    }

    private AnalysisReportRequest parse(AnalysisReport report) {
        return objectMapper.readValue(report.getPayload(), AnalysisReportRequest.class);
    }
}
