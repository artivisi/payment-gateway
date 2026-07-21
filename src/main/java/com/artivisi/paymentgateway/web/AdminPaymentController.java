package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.entity.Payment;
import com.artivisi.paymentgateway.entity.PaymentStatus;
import com.artivisi.paymentgateway.exception.NotFoundException;
import com.artivisi.paymentgateway.repository.PaymentRepository;
import com.artivisi.paymentgateway.web.viewmodel.ChipView;
import com.artivisi.paymentgateway.web.viewmodel.PaymentRowView;
import com.artivisi.paymentgateway.web.viewmodel.ViewFormats;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static com.artivisi.paymentgateway.web.viewmodel.ViewFormats.DISPLAY_ZONE;

@Controller
@RequestMapping("/admin/payments")
public class AdminPaymentController {

    private static final int PAGE_SIZE = 25;
    private static final String DEFAULT_RANGE = "7D";

    /** Postgres timestamptz-safe stand-ins for "unbounded"; avoids nullable bind parameters (see repository). */
    private static final Instant EPOCH = Instant.EPOCH;
    private static final Instant FAR_FUTURE = Instant.parse("9999-12-31T23:59:59Z");

    private final PaymentRepository paymentRepository;

    public AdminPaymentController(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @GetMapping
    public String list(@RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) String q,
                       @RequestParam(required = false) String range, Model model) {
        String query = (q == null || q.isBlank()) ? null : q.trim();
        String effectiveRange = (range == null || range.isBlank()) ? DEFAULT_RANGE : range;
        Instant now = Instant.now();
        Instant[] bounds = rangeBounds(effectiveRange, now);

        Page<Payment> payments = paymentRepository.search(bounds[0], bounds[1], query, PageRequest.of(page, PAGE_SIZE));
        List<PaymentRowView> rows = payments.getContent().stream().map(p -> toRow(p, now)).toList();

        model.addAttribute("payments", payments);
        model.addAttribute("rows", rows);
        model.addAttribute("chips", chips(effectiveRange, query, now));
        model.addAttribute("q", q);
        model.addAttribute("range", effectiveRange);
        return "admin/payment/list";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable String id, Model model) {
        model.addAttribute("payment", paymentRepository.findByIdWithVaAndCharge(id)
                .orElseThrow(() -> new NotFoundException("Payment not found: " + id)));
        return "admin/payment/detail";
    }

    private PaymentRowView toRow(Payment p, Instant now) {
        return new PaymentRowView(
                p.getId(), ViewFormats.relativeDay(p.getTransactionTime(), now), ViewFormats.time(p.getTransactionTime()),
                p.getTransactionTime().toString(), p.getCharge().getPayerName(),
                p.getVirtualAccount().getEscrowAccount().getProvider().toUpperCase(), p.getVirtualAccount().getVaNumber(),
                p.getBankReference(), ViewFormats.rupiah(p.getAmount()),
                p.getStatus().name(), p.getStatus() == PaymentStatus.ACCEPTED ? "badge-ok" : "badge-danger",
                ViewFormats.shortId(p.getCharge().getId()), "/admin/charges/" + p.getCharge().getId());
    }

    /** [start, end) for a named range, on the Asia/Jakarta calendar day. Never null — see repository note. */
    private static Instant[] rangeBounds(String range, Instant now) {
        ZonedDateTime today = now.atZone(DISPLAY_ZONE).toLocalDate().atStartOfDay(DISPLAY_ZONE);
        return switch (range) {
            case "TODAY" -> new Instant[]{today.toInstant(), today.plusDays(1).toInstant()};
            case "YESTERDAY" -> new Instant[]{today.minusDays(1).toInstant(), today.toInstant()};
            case "ALL" -> new Instant[]{EPOCH, FAR_FUTURE};
            default -> new Instant[]{today.minusDays(6).toInstant(), now}; // "7D"
        };
    }

    private List<ChipView> chips(String activeRange, String q, Instant now) {
        Function<String, String> href = r -> UriComponentsBuilder.fromPath("/admin/payments")
                .queryParam("range", r)
                .queryParamIfPresent("q", Optional.ofNullable(q))
                .build().toUriString();
        Instant[] today = rangeBounds("TODAY", now);
        Instant[] yesterday = rangeBounds("YESTERDAY", now);
        Instant[] last7d = rangeBounds("7D", now);
        return List.of(
                new ChipView("Today", paymentRepository.countByTransactionTimeBetween(today[0], today[1]),
                        "TODAY".equals(activeRange), href.apply("TODAY")),
                new ChipView("Yesterday", paymentRepository.countByTransactionTimeBetween(yesterday[0], yesterday[1]),
                        "YESTERDAY".equals(activeRange), href.apply("YESTERDAY")),
                new ChipView("Last 7 days", paymentRepository.countByTransactionTimeBetween(last7d[0], now),
                        "7D".equals(activeRange), href.apply("7D")),
                new ChipView("All", paymentRepository.count(), "ALL".equals(activeRange), href.apply("ALL"))
        );
    }
}
