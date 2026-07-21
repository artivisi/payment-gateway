package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.entity.Charge;
import com.artivisi.paymentgateway.entity.ChargeStatus;
import com.artivisi.paymentgateway.entity.VirtualAccount;
import com.artivisi.paymentgateway.entity.VirtualAccountStatus;
import com.artivisi.paymentgateway.exception.NotFoundException;
import com.artivisi.paymentgateway.repository.ChargeRepository;
import com.artivisi.paymentgateway.repository.PaymentRepository;
import com.artivisi.paymentgateway.repository.VirtualAccountRepository;
import com.artivisi.paymentgateway.web.viewmodel.ChargeRowView;
import com.artivisi.paymentgateway.web.viewmodel.ChipView;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin/charges")
public class AdminChargeController {

    private static final int PAGE_SIZE = 25;

    private final ChargeRepository chargeRepository;
    private final VirtualAccountRepository virtualAccountRepository;
    private final PaymentRepository paymentRepository;

    public AdminChargeController(ChargeRepository chargeRepository,
                                 VirtualAccountRepository virtualAccountRepository,
                                 PaymentRepository paymentRepository) {
        this.chargeRepository = chargeRepository;
        this.virtualAccountRepository = virtualAccountRepository;
        this.paymentRepository = paymentRepository;
    }

    @GetMapping
    public String list(@RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) String q,
                       @RequestParam(required = false) ChargeStatus status, Model model) {
        String query = (q == null || q.isBlank()) ? null : q.trim();
        Page<Charge> charges = chargeRepository.search(status, query, PageRequest.of(page, PAGE_SIZE));

        Map<String, List<VirtualAccount>> activeVasByCharge = activeVasByChargeId(charges.getContent());
        Instant now = Instant.now();
        List<ChargeRowView> rows = charges.getContent().stream()
                .map(c -> toRow(c, activeVasByCharge.getOrDefault(c.getId(), List.of()), now))
                .toList();

        model.addAttribute("charges", charges);
        model.addAttribute("rows", rows);
        model.addAttribute("chips", chips(status, query));
        model.addAttribute("q", q);
        model.addAttribute("status", status);
        return "admin/charge/list";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable String id, Model model) {
        Charge charge = chargeRepository.findByIdWithConsumer(id)
                .orElseThrow(() -> new NotFoundException("charge not found: " + id));
        model.addAttribute("charge", charge);
        model.addAttribute("accounts", virtualAccountRepository.findByChargeIdWithEscrow(id));
        model.addAttribute("payments", paymentRepository.findByChargeIdWithVa(id));
        return "admin/charge/detail";
    }

    private Map<String, List<VirtualAccount>> activeVasByChargeId(List<Charge> charges) {
        List<String> ids = charges.stream().map(Charge::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return virtualAccountRepository.findByChargeIdInAndStatusWithEscrow(ids, VirtualAccountStatus.ACTIVE).stream()
                .collect(Collectors.groupingBy(va -> va.getCharge().getId()));
    }

    private ChargeRowView toRow(Charge c, List<VirtualAccount> activeVas, Instant now) {
        String bankVa = activeVas.isEmpty() ? "—" : activeVas.stream()
                .map(va -> va.getEscrowAccount().getProvider().toUpperCase() + " " + va.getVaNumber())
                .collect(Collectors.joining(", "));
        boolean hasPaid = c.getCumulativePaid() != null && c.getCumulativePaid().signum() > 0;
        return new ChargeRowView(
                ViewFormats.shortId(c.getId()), c.getId(), c.getConsumer().getCode(), c.getPayerName(),
                c.getChargeType().name(), bankVa,
                ViewFormats.rupiah(c.getAmount()),
                hasPaid ? ViewFormats.rupiah(c.getCumulativePaid()) : "—",
                hasPaid ? "text-accent-700" : "text-slate-300",
                c.getStatus().name(), statusClass(c.getStatus()),
                ViewFormats.relativeDay(c.getCreatedAt(), now) + ", " + ViewFormats.time(c.getCreatedAt()),
                c.getCreatedAt().toString());
    }

    private static String statusClass(ChargeStatus status) {
        return switch (status) {
            case ACTIVE -> "badge-primary";
            case PARTIALLY_PAID -> "badge-warn";
            case PAID -> "badge-ok";
            case EXPIRED -> "badge-danger";
            case CANCELLED -> "badge-muted";
        };
    }

    private List<ChipView> chips(ChargeStatus activeStatus, String q) {
        Function<ChargeStatus, String> href = s -> UriComponentsBuilder.fromPath("/admin/charges")
                .queryParamIfPresent("status", Optional.ofNullable(s))
                .queryParamIfPresent("q", Optional.ofNullable(q))
                .build().toUriString();
        return List.of(
                new ChipView("All", chargeRepository.count(), activeStatus == null, href.apply(null)),
                new ChipView("Active", chargeRepository.countByStatus(ChargeStatus.ACTIVE),
                        activeStatus == ChargeStatus.ACTIVE, href.apply(ChargeStatus.ACTIVE)),
                new ChipView("Partially paid", chargeRepository.countByStatus(ChargeStatus.PARTIALLY_PAID),
                        activeStatus == ChargeStatus.PARTIALLY_PAID, href.apply(ChargeStatus.PARTIALLY_PAID)),
                new ChipView("Paid", chargeRepository.countByStatus(ChargeStatus.PAID),
                        activeStatus == ChargeStatus.PAID, href.apply(ChargeStatus.PAID)),
                new ChipView("Expired", chargeRepository.countByStatus(ChargeStatus.EXPIRED),
                        activeStatus == ChargeStatus.EXPIRED, href.apply(ChargeStatus.EXPIRED)),
                new ChipView("Cancelled", chargeRepository.countByStatus(ChargeStatus.CANCELLED),
                        activeStatus == ChargeStatus.CANCELLED, href.apply(ChargeStatus.CANCELLED))
        );
    }
}
