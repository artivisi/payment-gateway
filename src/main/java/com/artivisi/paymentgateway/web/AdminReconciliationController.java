package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.entity.EscrowAccount;
import com.artivisi.paymentgateway.entity.ReconciliationRun;
import com.artivisi.paymentgateway.exception.NotFoundException;
import com.artivisi.paymentgateway.repository.EscrowAccountRepository;
import com.artivisi.paymentgateway.repository.ReconciliationDiscrepancyRepository;
import com.artivisi.paymentgateway.repository.ReconciliationRunRepository;
import com.artivisi.paymentgateway.service.ReconciliationService;
import com.artivisi.paymentgateway.service.SettlementCsvParser;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.time.LocalDate;

@Controller
@RequestMapping("/admin/reconciliations")
public class AdminReconciliationController {

    private final ReconciliationRunRepository runRepository;
    private final ReconciliationDiscrepancyRepository discrepancyRepository;
    private final EscrowAccountRepository escrowAccountRepository;
    private final ReconciliationService reconciliationService;
    private final SettlementCsvParser settlementCsvParser;

    public AdminReconciliationController(ReconciliationRunRepository runRepository,
                                         ReconciliationDiscrepancyRepository discrepancyRepository,
                                         EscrowAccountRepository escrowAccountRepository,
                                         ReconciliationService reconciliationService,
                                         SettlementCsvParser settlementCsvParser) {
        this.runRepository = runRepository;
        this.discrepancyRepository = discrepancyRepository;
        this.escrowAccountRepository = escrowAccountRepository;
        this.reconciliationService = reconciliationService;
        this.settlementCsvParser = settlementCsvParser;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("runs", runRepository.findRecentWithEscrow(PageRequest.of(0, 100)));
        model.addAttribute("escrows", escrowAccountRepository.findAll());
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

    @PostMapping("/import")
    public String importStatement(
            @RequestParam String escrowCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period,
            @RequestParam MultipartFile file,
            RedirectAttributes redirectAttributes) {
        try {
            EscrowAccount escrow = escrowAccountRepository.findByCode(escrowCode)
                    .orElseThrow(() -> new NotFoundException("escrow not found: " + escrowCode));
            ReconciliationRun run = reconciliationService.reconcile(escrow, period,
                    settlementCsvParser.parse(file.getInputStream()));
            redirectAttributes.addFlashAttribute("message",
                    "Reconciliation completed: " + run.getMatchedCount() + " matched, "
                            + run.getRecoveredCount() + " recovered, "
                            + run.getDiscrepancyCount() + " discrepancies.");
        } catch (IOException e) {
            redirectAttributes.addFlashAttribute("error", "Failed to read uploaded file: " + e.getMessage());
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/reconciliations";
    }
}
