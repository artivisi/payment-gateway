package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.dto.SettlementClaimLine;
import com.artivisi.paymentgateway.entity.DiscrepancyType;
import com.artivisi.paymentgateway.entity.EscrowAccount;
import com.artivisi.paymentgateway.entity.ReconciliationDiscrepancy;
import com.artivisi.paymentgateway.entity.ReconciliationRun;
import com.artivisi.paymentgateway.exception.NotFoundException;
import com.artivisi.paymentgateway.repository.EscrowAccountRepository;
import com.artivisi.paymentgateway.repository.ReconciliationDiscrepancyRepository;
import com.artivisi.paymentgateway.repository.ReconciliationRunRepository;
import com.artivisi.paymentgateway.service.ReconciliationService;
import com.artivisi.paymentgateway.dto.SettlementAmountBasis;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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

    /**
     * A settlement claim the operator can print and send to the bank.
     *
     * <p>Separate from the detail screen because the audiences differ: the detail screen is for
     * whoever is working the run, and shows every row as data. This is addressed to the bank, so it
     * leads with the account and period being claimed against, groups the rows by what each class is
     * asking the bank to do, and says so in words. A claim that arrives as a table of enum names gets
     * bounced for clarification before anyone looks at the money.
     */
    @GetMapping("/{id}/claim")
    public String claim(@PathVariable String id, Model model) {
        ReconciliationRun run = runRepository.findByIdWithEscrow(id)
                .orElseThrow(() -> new NotFoundException("reconciliation run not found: " + id));
        List<ReconciliationDiscrepancy> discrepancies =
                discrepancyRepository.findByReconciliationRunIdOrderByCreatedAtAsc(id);

        // Grouped in a fixed order rather than by whatever the run happened to produce, so two claims
        // for different periods read the same way.
        List<SettlementClaimLine> summary = new ArrayList<>();
        for (DiscrepancyType type : DiscrepancyType.values()) {
            List<ReconciliationDiscrepancy> of = discrepancies.stream()
                    .filter(d -> d.getType() == type).toList();
            if (of.isEmpty()) {
                continue;
            }
            BigDecimal total = of.stream()
                    .map(d -> d.getAmount() == null ? BigDecimal.ZERO : d.getAmount())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            summary.add(new SettlementClaimLine(type, SettlementClaimLine.meaningOf(type), of.size(), total));
        }

        model.addAttribute("run", run);
        model.addAttribute("summary", summary);
        model.addAttribute("discrepancies", discrepancies);
        model.addAttribute("claimTotal", summary.stream()
                .map(SettlementClaimLine::total).reduce(BigDecimal.ZERO, BigDecimal::add));
        model.addAttribute("generatedAt", Instant.now());
        return "admin/reconciliation/claim";
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

    /**
     * What the bank has not settled, across every run — the question finance actually asks.
     *
     * <p>Discrepancies otherwise live one run at a time, so chasing "which payments has BSI not
     * settled" means opening each period in turn and remembering what was in the last one.
     */
    @GetMapping("/discrepancies")
    public String discrepancies(@RequestParam(required = false) DiscrepancyType type, Model model) {
        model.addAttribute("selectedType", type);
        model.addAttribute("types", DiscrepancyType.values());
        model.addAttribute("discrepancies",
                discrepancyRepository.findForReview(type, PageRequest.of(0, 500)));
        return "admin/reconciliation/discrepancies";
    }

    @PostMapping("/import")
    public String importStatement(
            @RequestParam String escrowCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period,
            @RequestParam MultipartFile file,
            // An unchecked checkbox is simply not submitted, so absent means "recover", which is the
            // established end-of-day behaviour. Report-only is the deliberate, opt-in choice.
            @RequestParam(defaultValue = "false") boolean reportOnly,
            // No default: an account statement's amounts are net of the bank's fee and a transaction
            // list's are gross, and reading one as the other makes every row a mismatch. The form
            // makes it a required choice rather than letting the common case ride on a default.
            @RequestParam SettlementAmountBasis amountBasis,
            RedirectAttributes redirectAttributes) {
        try {
            EscrowAccount escrow = escrowAccountRepository.findByCode(escrowCode)
                    .orElseThrow(() -> new NotFoundException("escrow not found: " + escrowCode));
            ReconciliationRun run = reconciliationService.reconcile(escrow, period,
                    settlementCsvParser.parse(file.getInputStream()), !reportOnly, amountBasis);
            redirectAttributes.addFlashAttribute("message",
                    "Reconciliation completed: " + run.getMatchedCount() + " matched, "
                            + run.getRecoveredCount() + " recovered, "
                            + run.getDiscrepancyCount() + " discrepancies."
                            + (reportOnly ? " Report only — nothing was recovered." : ""));
        } catch (IOException e) {
            redirectAttributes.addFlashAttribute("error", "Failed to read uploaded file: " + e.getMessage());
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/reconciliations";
    }
}
