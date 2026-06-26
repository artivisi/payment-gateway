package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.entity.ReconciliationRun;
import com.artivisi.paymentgateway.exception.NotFoundException;
import com.artivisi.paymentgateway.repository.ReconciliationDiscrepancyRepository;
import com.artivisi.paymentgateway.repository.ReconciliationRunRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin/reconciliations")
public class AdminReconciliationController {

    private final ReconciliationRunRepository runRepository;
    private final ReconciliationDiscrepancyRepository discrepancyRepository;

    public AdminReconciliationController(ReconciliationRunRepository runRepository,
                                         ReconciliationDiscrepancyRepository discrepancyRepository) {
        this.runRepository = runRepository;
        this.discrepancyRepository = discrepancyRepository;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("runs", runRepository.findRecentWithEscrow(PageRequest.of(0, 100)));
        return "admin/reconciliation/list";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable String id, Model model) {
        ReconciliationRun run = runRepository.findByIdWithEscrow(id)
                .orElseThrow(() -> new NotFoundException("reconciliation run not found: " + id));
        model.addAttribute("run", run);
        model.addAttribute("discrepancies",
                discrepancyRepository.findByReconciliationRunIdOrderByCreatedAtAsc(id));
        return "admin/reconciliation/detail";
    }
}
