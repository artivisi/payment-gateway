package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.exception.NotFoundException;
import com.artivisi.paymentgateway.repository.PaymentRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/admin/payments")
public class AdminPaymentController {

    private static final int PAGE_SIZE = 25;

    private final PaymentRepository paymentRepository;

    public AdminPaymentController(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @GetMapping
    public String list(@RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) String q, Model model) {
        String query = (q == null || q.isBlank()) ? null : q.trim();
        model.addAttribute("payments", paymentRepository.search(query, PageRequest.of(page, PAGE_SIZE)));
        model.addAttribute("q", q);
        return "admin/payment/list";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable String id, Model model) {
        model.addAttribute("payment", paymentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Payment not found: " + id)));
        return "admin/payment/detail";
    }
}
