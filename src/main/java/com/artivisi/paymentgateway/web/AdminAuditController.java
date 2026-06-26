package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.repository.AuditEventRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin/audit")
public class AdminAuditController {

    private final AuditEventRepository auditEventRepository;

    public AdminAuditController(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("events", auditEventRepository.findTop200ByOrderByCreatedAtDesc());
        return "admin/audit/list";
    }
}
