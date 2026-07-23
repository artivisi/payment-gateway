package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.dto.AnalysisReportRequest;
import com.artivisi.paymentgateway.entity.AnalysisReport;
import com.artivisi.paymentgateway.service.AnalysisReportService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Ingest for externally computed analysis. Authenticated by a device token (RFC 8628) carrying
 * {@code ANALYSIS_VIEW}, so every upload is attributable to a named operator.
 */
@RestController
@RequestMapping("/api/analysis-reports")
public class AnalysisReportApiController {

    private final AnalysisReportService service;

    public AnalysisReportApiController(AnalysisReportService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> store(@Valid @RequestBody AnalysisReportRequest request) {
        AnalysisReport saved = service.store(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", saved.getId(),
                "kind", saved.getKind(),
                "view", "/admin/analysis/" + saved.getId()));
    }
}
