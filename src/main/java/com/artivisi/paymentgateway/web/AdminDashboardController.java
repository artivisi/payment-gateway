package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.repository.ChargeRepository;
import com.artivisi.paymentgateway.repository.ConsumerRepository;
import com.artivisi.paymentgateway.repository.EscrowAccountRepository;
import com.artivisi.paymentgateway.repository.PaymentRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin")
public class AdminDashboardController {

    private final EscrowAccountRepository escrowAccountRepository;
    private final ConsumerRepository consumerRepository;
    private final ChargeRepository chargeRepository;
    private final PaymentRepository paymentRepository;

    public AdminDashboardController(EscrowAccountRepository escrowAccountRepository,
                                    ConsumerRepository consumerRepository, ChargeRepository chargeRepository,
                                    PaymentRepository paymentRepository) {
        this.escrowAccountRepository = escrowAccountRepository;
        this.consumerRepository = consumerRepository;
        this.chargeRepository = chargeRepository;
        this.paymentRepository = paymentRepository;
    }

    @GetMapping
    public String dashboard(Model model) {
        model.addAttribute("escrowCount", escrowAccountRepository.count());
        model.addAttribute("consumerCount", consumerRepository.count());
        model.addAttribute("chargeCount", chargeRepository.count());
        model.addAttribute("paymentCount", paymentRepository.count());
        return "admin/home";
    }
}
