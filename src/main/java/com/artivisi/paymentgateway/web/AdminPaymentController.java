package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.repository.PaymentRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin/payments")
public class AdminPaymentController {

    private final PaymentRepository paymentRepository;

    public AdminPaymentController(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("payments", paymentRepository.findRecentWithVaAndCharge(PageRequest.of(0, 100)));
        return "admin/payment/list";
    }
}
