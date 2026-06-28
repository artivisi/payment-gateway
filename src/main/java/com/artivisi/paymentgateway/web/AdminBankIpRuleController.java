package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.service.BankIpRuleService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Per-provider bank IP allowlist management (ADMIN-only; enforced by SecurityConfig). */
@Controller
@RequestMapping("/admin/bank-ip-rules")
public class AdminBankIpRuleController {

    private final BankIpRuleService service;

    public AdminBankIpRuleController(BankIpRuleService service) {
        this.service = service;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("rules", service.list());
        return "admin/bank-ip-rule/list";
    }

    @PostMapping
    public String create(@RequestParam String provider,
                         @RequestParam String cidr,
                         @RequestParam(required = false) String label,
                         RedirectAttributes ra) {
        try {
            service.create(provider, cidr, label);
            ra.addFlashAttribute("message", "Rule added: " + provider + " " + cidr);
        } catch (RuntimeException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/bank-ip-rules";
    }

    @PostMapping("/{id}/enable")
    public String enable(@PathVariable String id, RedirectAttributes ra) {
        service.setEnabled(id, true);
        ra.addFlashAttribute("message", "Rule enabled.");
        return "redirect:/admin/bank-ip-rules";
    }

    @PostMapping("/{id}/disable")
    public String disable(@PathVariable String id, RedirectAttributes ra) {
        service.setEnabled(id, false);
        ra.addFlashAttribute("message", "Rule disabled.");
        return "redirect:/admin/bank-ip-rules";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable String id, RedirectAttributes ra) {
        service.delete(id);
        ra.addFlashAttribute("message", "Rule deleted.");
        return "redirect:/admin/bank-ip-rules";
    }
}
