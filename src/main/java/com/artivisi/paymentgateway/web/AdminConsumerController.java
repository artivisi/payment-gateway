package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.dto.ConsumerRequest;
import com.artivisi.paymentgateway.entity.ConsumerStatus;
import com.artivisi.paymentgateway.service.ConsumerService;
import com.artivisi.paymentgateway.service.WebhookService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/consumers")
public class AdminConsumerController {

    private final ConsumerService consumerService;
    private final WebhookService webhookService;

    public AdminConsumerController(ConsumerService consumerService, WebhookService webhookService) {
        this.consumerService = consumerService;
        this.webhookService = webhookService;
    }

    @GetMapping
    public String list(Model model) {
        var consumers = consumerService.list();
        java.util.Map<String, Long> failedCounts = new java.util.HashMap<>();
        for (var c : consumers) {
            failedCounts.put(c.getId(), webhookService.failedCount(c.getId()));
        }
        model.addAttribute("consumers", consumers);
        model.addAttribute("failedCounts", failedCounts);
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

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable String id, Model model) {
        model.addAttribute("consumer", consumerService.get(id));
        return "admin/consumer/edit";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable String id,
                         @RequestParam String name,
                         @RequestParam String webhookUrl,
                         @RequestParam com.artivisi.paymentgateway.entity.ConsumerStatus status,
                         RedirectAttributes redirectAttributes) {
        try {
            consumerService.update(id, name, webhookUrl, status);
            redirectAttributes.addFlashAttribute("message", "Consumer updated.");
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/consumers/" + id + "/edit";
        }
        return "redirect:/admin/consumers";
    }

    @PostMapping("/{id}/webhook/suspend")
    public String suspend(@PathVariable String id, RedirectAttributes redirectAttributes) {
        var c = consumerService.setWebhookSuspended(id, true);
        redirectAttributes.addFlashAttribute("message", "Webhook delivery suspended for '" + c.getCode() + "'.");
        return "redirect:/admin/consumers";
    }

    @PostMapping("/{id}/webhook/resume")
    public String resume(@PathVariable String id, RedirectAttributes redirectAttributes) {
        var c = consumerService.setWebhookSuspended(id, false);
        redirectAttributes.addFlashAttribute("message", "Webhook delivery resumed for '" + c.getCode() + "'.");
        return "redirect:/admin/consumers";
    }

    @PostMapping("/{id}/webhook/replay")
    public String replay(@PathVariable String id, RedirectAttributes redirectAttributes) {
        int requeued = webhookService.replayFailed(id);
        redirectAttributes.addFlashAttribute("message", requeued + " failed delivery(ies) requeued.");
        return "redirect:/admin/consumers";
    }
}
