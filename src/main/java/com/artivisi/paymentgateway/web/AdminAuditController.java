package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.entity.AuditEvent;
import com.artivisi.paymentgateway.repository.AuditEventRepository;
import com.artivisi.paymentgateway.web.viewmodel.AuditRowView;
import com.artivisi.paymentgateway.web.viewmodel.ChipView;
import com.artivisi.paymentgateway.web.viewmodel.ViewFormats;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

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
                       @RequestParam(required = false) String q,
                       @RequestParam(required = false) String category, Model model) {
        String query = (q == null || q.isBlank()) ? null : q.trim();
        Page<AuditEvent> events = auditEventRepository.search(category, query, PageRequest.of(page, PAGE_SIZE));
        Instant now = Instant.now();
        List<AuditRowView> rows = events.getContent().stream().map(e -> toRow(e, now)).toList();

        model.addAttribute("events", events);
        model.addAttribute("rows", rows);
        model.addAttribute("chips", chips(category, query));
        model.addAttribute("q", q);
        model.addAttribute("category", category);
        return "admin/audit/list";
    }

    private AuditRowView toRow(AuditEvent e, Instant now) {
        return new AuditRowView(
                ViewFormats.relativeDay(e.getCreatedAt(), now), ViewFormats.time(e.getCreatedAt()), e.getCreatedAt().toString(),
                e.getActor() != null ? e.getActor() : "system", e.getActor() != null ? "badge-primary" : "badge-muted",
                e.getEventType().replace('_', ' '), eventClass(e.getEventType()),
                e.getEntityType(), ViewFormats.shortId(e.getEntityId()), e.getEntityId(),
                e.getDetail() != null && !e.getDetail().isBlank() ? e.getDetail() : "—", e.getDetail());
    }

    private static String eventClass(String eventType) {
        if (eventType.startsWith("AUTH_")) {
            return "badge-primary";
        }
        if (eventType.endsWith("_APPLIED") || eventType.endsWith("_SUCCESS") || eventType.endsWith("_VERIFIED")
                || eventType.endsWith("_COMPLETED") || eventType.endsWith("_RECOVERED")) {
            return "badge-ok";
        }
        if (eventType.endsWith("_CANCELLED") || eventType.endsWith("_FAILED") || eventType.endsWith("_REJECTED")
                || eventType.endsWith("_SUSPENDED") || eventType.endsWith("_EXPIRED")) {
            return "badge-warn";
        }
        return "badge-muted";
    }

    private List<ChipView> chips(String activeCategory, String q) {
        Function<String, String> href = c -> UriComponentsBuilder.fromPath("/admin/audit")
                .queryParamIfPresent("category", Optional.ofNullable(c))
                .queryParamIfPresent("q", Optional.ofNullable(q))
                .build().toUriString();
        long authCount = auditEventRepository.countByEventTypeStartingWith("AUTH_");
        long chargeCount = auditEventRepository.countByEventTypeStartingWith("CHARGE_");
        long paymentCount = auditEventRepository.countByEventTypeStartingWith("PAYMENT_");
        long total = auditEventRepository.count();
        long otherCount = total - authCount - chargeCount - paymentCount;
        return List.of(
                new ChipView("All", total, activeCategory == null, href.apply(null)),
                new ChipView("Auth", authCount, "AUTH".equals(activeCategory), href.apply("AUTH")),
                new ChipView("Charges", chargeCount, "CHARGE".equals(activeCategory), href.apply("CHARGE")),
                new ChipView("Payments", paymentCount, "PAYMENT".equals(activeCategory), href.apply("PAYMENT")),
                new ChipView("Other", otherCount, "OTHER".equals(activeCategory), href.apply("OTHER"))
        );
    }
}
