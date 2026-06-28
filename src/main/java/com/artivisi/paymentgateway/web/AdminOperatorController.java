package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.service.OperatorService;
import com.artivisi.paymentgateway.service.RoleService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Operator account management (ADMIN-only; enforced by SecurityConfig). */
@Controller
@RequestMapping("/admin/operators")
public class AdminOperatorController {

    private final OperatorService operatorService;
    private final RoleService roleService;

    public AdminOperatorController(OperatorService operatorService, RoleService roleService) {
        this.operatorService = operatorService;
        this.roleService = roleService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("operators", operatorService.list());
        return "admin/operator/list";
    }

    @GetMapping("/new")
    public String form(Model model) {
        model.addAttribute("roles", roleService.list());
        return "admin/operator/form";
    }

    @PostMapping
    public String create(@RequestParam String username,
                         @RequestParam String fullName,
                         @RequestParam String roleId,
                         @RequestParam String password,
                         RedirectAttributes redirectAttributes) {
        try {
            operatorService.create(username, fullName, roleId, password);
            redirectAttributes.addFlashAttribute("message", "Operator '" + username + "' created.");
            return "redirect:/admin/operators";
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/operators/new";
        }
    }

    @PostMapping("/{id}/enable")
    public String enable(@PathVariable String id, RedirectAttributes ra) {
        operatorService.setEnabled(id, true);
        ra.addFlashAttribute("message", "Operator enabled.");
        return "redirect:/admin/operators";
    }

    @PostMapping("/{id}/disable")
    public String disable(@PathVariable String id, RedirectAttributes ra) {
        operatorService.setEnabled(id, false);
        ra.addFlashAttribute("message", "Operator disabled.");
        return "redirect:/admin/operators";
    }

    @PostMapping("/{id}/reset-password")
    public String resetPassword(@PathVariable String id, @RequestParam String password, RedirectAttributes ra) {
        try {
            operatorService.resetPassword(id, password);
            ra.addFlashAttribute("message", "Temporary password set; operator must change it on next login.");
        } catch (RuntimeException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/operators";
    }

    @PostMapping("/{id}/reset-mfa")
    public String resetMfa(@PathVariable String id, RedirectAttributes ra) {
        operatorService.resetMfa(id);
        ra.addFlashAttribute("message", "MFA reset; operator must re-enrol on next login.");
        return "redirect:/admin/operators";
    }
}
