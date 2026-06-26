package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.entity.Charge;
import com.artivisi.paymentgateway.exception.NotFoundException;
import com.artivisi.paymentgateway.repository.ChargeRepository;
import com.artivisi.paymentgateway.repository.PaymentRepository;
import com.artivisi.paymentgateway.repository.VirtualAccountRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin/charges")
public class AdminChargeController {

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
    public String list(Model model) {
        model.addAttribute("charges", chargeRepository.findRecentWithConsumer(PageRequest.of(0, 100)));
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
}
