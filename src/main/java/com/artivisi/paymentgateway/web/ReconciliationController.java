package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.dto.ReconciliationRequest;
import com.artivisi.paymentgateway.dto.ReconciliationSummary;
import com.artivisi.paymentgateway.entity.EscrowAccount;
import com.artivisi.paymentgateway.entity.ReconciliationRun;
import com.artivisi.paymentgateway.exception.NotFoundException;
import com.artivisi.paymentgateway.repository.EscrowAccountRepository;
import com.artivisi.paymentgateway.repository.ReconciliationDiscrepancyRepository;
import com.artivisi.paymentgateway.service.ReconciliationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin trigger for end-of-day reconciliation via imported settlement credits. */
@RestController
@RequestMapping("/api/escrow-accounts/{code}/reconciliations")
public class ReconciliationController {

    private final EscrowAccountRepository escrowAccountRepository;
    private final ReconciliationService reconciliationService;
    private final ReconciliationDiscrepancyRepository discrepancyRepository;

    public ReconciliationController(EscrowAccountRepository escrowAccountRepository,
                                    ReconciliationService reconciliationService,
                                    ReconciliationDiscrepancyRepository discrepancyRepository) {
        this.escrowAccountRepository = escrowAccountRepository;
        this.reconciliationService = reconciliationService;
        this.discrepancyRepository = discrepancyRepository;
    }

    @PostMapping
    public ResponseEntity<ReconciliationSummary> run(@PathVariable String code,
                                                     @Valid @RequestBody ReconciliationRequest request) {
        EscrowAccount escrow = escrowAccountRepository.findByCode(code)
                .orElseThrow(() -> new NotFoundException("escrow not found: " + code));
        ReconciliationRun run = reconciliationService.reconcile(escrow, request.period(), request.credits());
        ReconciliationSummary summary = ReconciliationSummary.of(run,
                discrepancyRepository.findByReconciliationRunIdOrderByCreatedAtAsc(run.getId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(summary);
    }
}
