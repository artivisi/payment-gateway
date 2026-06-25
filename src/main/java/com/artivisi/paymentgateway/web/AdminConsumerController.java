package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.dto.ConsumerRequest;
import com.artivisi.paymentgateway.entity.ConsumerStatus;
import com.artivisi.paymentgateway.service.ConsumerService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/consumers")
public class AdminConsumerController {

    private final ConsumerService consumerService;

    public AdminConsumerController(ConsumerService consumerService) {
        this.consumerService = consumerService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("consumers", consumerService.list());
        return "admin/consumer/list";
    }

    @GetMapping("/new")
    public String form() {
        return "admin/consumer/form";
    }

    @PostMapping
    public String create(
            @RequestParam String code,
            @RequestParam String name,
            @RequestParam String clientId,
            @RequestParam String clientSecret,
            @RequestParam String webhookUrl,
            @RequestParam ConsumerStatus status,
            RedirectAttributes redirectAttributes) {
        try {
            consumerService.create(new ConsumerRequest(code, name, clientId, clientSecret, webhookUrl, status));
            redirectAttributes.addFlashAttribute("message", "Consumer '" + code + "' created.");
            return "redirect:/admin/consumers";
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/consumers/new";
        }
    }
}
