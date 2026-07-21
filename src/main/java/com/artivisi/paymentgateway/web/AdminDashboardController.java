package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.repository.ConsumerRepository;
import com.artivisi.paymentgateway.repository.EscrowAccountRepository;
import com.artivisi.paymentgateway.service.DashboardMetricsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.Instant;

@Controller
@RequestMapping("/admin")
public class AdminDashboardController {

    private static final int RECENT_PAYMENTS_LIMIT = 5;

    private final EscrowAccountRepository escrowAccountRepository;
    private final ConsumerRepository consumerRepository;
    private final DashboardMetricsService dashboardMetricsService;

    public AdminDashboardController(EscrowAccountRepository escrowAccountRepository,
                                    ConsumerRepository consumerRepository,
                                    DashboardMetricsService dashboardMetricsService) {
        this.escrowAccountRepository = escrowAccountRepository;
        this.consumerRepository = consumerRepository;
        this.dashboardMetricsService = dashboardMetricsService;
    }

    @GetMapping
    public String dashboard(Model model) {
        Instant now = Instant.now();
        model.addAttribute("escrowCount", escrowAccountRepository.count());
        model.addAttribute("consumerCount", consumerRepository.count());
        model.addAttribute("kpis", dashboardMetricsService.kpis(now));
        model.addAttribute("trend", dashboardMetricsService.collectionsTrend(now));
        model.addAttribute("attention", dashboardMetricsService.needsAttention(now));
        model.addAttribute("recentPayments", dashboardMetricsService.recentPayments(RECENT_PAYMENTS_LIMIT, now));
        return "admin/home";
    }
}
