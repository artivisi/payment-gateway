package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.repository.AuditEventRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/admin/audit")
public class AdminAuditController {

    private static final int PAGE_SIZE = 50;

    private final AuditEventRepository auditEventRepository;

    public AdminAuditController(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    @GetMapping
    public String list(@RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) String q, Model model) {
        String query = (q == null || q.isBlank()) ? null : q.trim();
        model.addAttribute("events", auditEventRepository.search(query, PageRequest.of(page, PAGE_SIZE)));
        model.addAttribute("q", q);
        return "admin/audit/list";
    }
}
