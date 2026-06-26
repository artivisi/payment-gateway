package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.dto.ReconciliationRequest;
import com.artivisi.paymentgateway.dto.ReconciliationSummary;
import com.artivisi.paymentgateway.entity.EscrowAccount;
import com.artivisi.paymentgateway.entity.ReconciliationRun;
import com.artivisi.paymentgateway.exception.NotFoundException;
import com.artivisi.paymentgateway.dto.SettlementCredit;
import com.artivisi.paymentgateway.repository.EscrowAccountRepository;
import com.artivisi.paymentgateway.repository.ReconciliationDiscrepancyRepository;
import com.artivisi.paymentgateway.service.ReconciliationService;
import com.artivisi.paymentgateway.service.SettlementCsvParser;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/** Admin trigger for end-of-day reconciliation via imported settlement credits. */
@RestController
@RequestMapping("/api/escrow-accounts/{code}/reconciliations")
public class ReconciliationController {

    private final EscrowAccountRepository escrowAccountRepository;
    private final ReconciliationService reconciliationService;
    private final ReconciliationDiscrepancyRepository discrepancyRepository;
    private final SettlementCsvParser settlementCsvParser;

    public ReconciliationController(EscrowAccountRepository escrowAccountRepository,
                                    ReconciliationService reconciliationService,
                                    ReconciliationDiscrepancyRepository discrepancyRepository,
                                    SettlementCsvParser settlementCsvParser) {
        this.escrowAccountRepository = escrowAccountRepository;
        this.reconciliationService = reconciliationService;
        this.discrepancyRepository = discrepancyRepository;
        this.settlementCsvParser = settlementCsvParser;
    }

    @PostMapping
    public ResponseEntity<ReconciliationSummary> run(@PathVariable String code,
                                                     @Valid @RequestBody ReconciliationRequest request) {
        return reconcile(code, request.period(), request.credits());
    }

    /** Import-statement path: upload a settlement CSV ({@code vaNumber,bankReference,amount,transactionTime}). */
    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ReconciliationSummary> importStatement(
            @PathVariable String code,
            @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period,
            @RequestParam("file") MultipartFile file) throws IOException {
        return reconcile(code, period, settlementCsvParser.parse(file.getInputStream()));
    }

    private ResponseEntity<ReconciliationSummary> reconcile(String code, LocalDate period,
                                                            List<SettlementCredit> credits) {
        EscrowAccount escrow = escrowAccountRepository.findByCode(code)
                .orElseThrow(() -> new NotFoundException("escrow not found: " + code));
        ReconciliationRun run = reconciliationService.reconcile(escrow, period, credits);
        ReconciliationSummary summary = ReconciliationSummary.of(run,
                discrepancyRepository.findByReconciliationRunIdOrderByCreatedAtAsc(run.getId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(summary);
    }
}
