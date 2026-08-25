package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.entity.Charge;
import com.artivisi.paymentgateway.entity.Payment;
import com.artivisi.paymentgateway.entity.VirtualAccount;
import com.artivisi.paymentgateway.repository.ChargeRepository;
import com.artivisi.paymentgateway.repository.PaymentRepository;
import com.artivisi.paymentgateway.repository.VirtualAccountRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
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

    private final ChargeRepository chargeRepository;
    private final VirtualAccountRepository virtualAccountRepository;
    private final PaymentRepository paymentRepository;

    public AdminAuditController(AuditEventRepository auditEventRepository,
                                ChargeRepository chargeRepository,
                                VirtualAccountRepository virtualAccountRepository,
                                PaymentRepository paymentRepository) {
        this.auditEventRepository = auditEventRepository;
        this.chargeRepository = chargeRepository;
        this.virtualAccountRepository = virtualAccountRepository;
        this.paymentRepository = paymentRepository;
    }

    /**
     * Names each row by something a person recognises: the bill number the student was given, the VA
     * they paid into, who they are, or the bank's reference for a payment.
     *
     * <p>The log addresses everything by UUID, which is the right key and the wrong label — finance
     * chasing a payment knows the bill number off the statement, not the charge's primary key.
     * Resolved per page and in bulk, so this costs two queries rather than one per row.
     */
    private Map<String, String> subjectsFor(List<AuditEvent> events) {
        Map<String, String> subjects = new HashMap<>();

        List<String> chargeIds = events.stream()
                .filter(e -> "Charge".equals(e.getEntityType()))
                .map(AuditEvent::getEntityId).filter(Objects::nonNull).distinct().toList();
        if (!chargeIds.isEmpty()) {
            Map<String, List<String>> vasByCharge = virtualAccountRepository.findByChargeIdIn(chargeIds)
                    .stream().collect(Collectors.groupingBy(v -> v.getCharge().getId(),
                            Collectors.mapping(VirtualAccount::getVaNumber, Collectors.toList())));
            for (Charge c : chargeRepository.findAllById(chargeIds)) {
                List<String> parts = new ArrayList<>();
                if (c.getBillNumber() != null && !c.getBillNumber().isBlank()) {
                    parts.add(c.getBillNumber());
                }
                List<String> vas = vasByCharge.getOrDefault(c.getId(), List.of())
                        .stream().distinct().toList();
                if (!vas.isEmpty()) {
                    parts.add("VA " + String.join(", ", vas));
                }
                if (c.getPayerName() != null && !c.getPayerName().isBlank()) {
                    parts.add(c.getPayerName());
                }
                if (!parts.isEmpty()) {
                    subjects.put(c.getId(), String.join(" · ", parts));
                }
            }
        }

        List<String> paymentIds = events.stream()
                .filter(e -> "Payment".equals(e.getEntityType()))
                .map(AuditEvent::getEntityId).filter(Objects::nonNull).distinct().toList();
        if (!paymentIds.isEmpty()) {
            for (Payment p : paymentRepository.findAllById(paymentIds)) {
                if (p.getBankReference() == null || p.getBankReference().isBlank()) {
                    continue;
                }
                // Both references when we have both: the journal number is the one the bank can
                // trace, and someone reading the audit log is usually about to ask them to.
                String label = p.getBankJournalNumber() == null || p.getBankJournalNumber().isBlank()
                        ? p.getBankReference()
                        : p.getBankReference() + " · jurnal " + p.getBankJournalNumber();
                subjects.put(p.getId(), label);
            }
        }
        return subjects;
    }

    @GetMapping
    public String list(@RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) String q,
                       @RequestParam(required = false) String category, Model model) {
        String query = (q == null || q.isBlank()) ? null : q.trim();
        Page<AuditEvent> events = auditEventRepository.search(category, query, PageRequest.of(page, PAGE_SIZE));
        Instant now = Instant.now();
        Map<String, String> subjects = subjectsFor(events.getContent());
        List<AuditRowView> rows = events.getContent().stream()
                .map(e -> toRow(e, now, subjects.get(e.getEntityId()))).toList();

        model.addAttribute("events", events);
        model.addAttribute("rows", rows);
        model.addAttribute("chips", chips(category, query));
        model.addAttribute("q", q);
        model.addAttribute("category", category);
        return "admin/audit/list";
    }

    private AuditRowView toRow(AuditEvent e, Instant now, String subject) {
        return new AuditRowView(
                ViewFormats.relativeDay(e.getCreatedAt(), now), ViewFormats.time(e.getCreatedAt()), e.getCreatedAt().toString(),
                e.getActor() != null ? e.getActor() : "system", e.getActor() != null ? "badge-primary" : "badge-muted",
                e.getEventType().replace('_', ' '), eventClass(e.getEventType()),
                e.getEntityType(), ViewFormats.shortId(e.getEntityId()), e.getEntityId(),
                e.getDetail() != null && !e.getDetail().isBlank() ? e.getDetail() : "—", e.getDetail(),
                subject);
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
