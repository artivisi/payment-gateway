package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.entity.WebhookStatus;
import com.artivisi.paymentgateway.service.WebhookService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Webhook delivery analysis: browse deliveries by status (defaults to FAILED) and replay a single
 * one. Gives operators charge/event/last-error/HTTP detail in the UI instead of grepping logs.
 */
@Controller
@RequestMapping("/admin/webhooks")
public class AdminWebhookController {

    private final WebhookService webhookService;

    public AdminWebhookController(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @GetMapping
    public String list(@RequestParam(required = false) String status,
                       @RequestParam(required = false) String consumer,
                       Model model) {
        WebhookStatus selected = (status == null || status.isBlank())
                ? WebhookStatus.FAILED : WebhookStatus.valueOf(status);
        model.addAttribute("deliveries", webhookService.listByStatus(selected, consumer));
        model.addAttribute("selected", selected);
        model.addAttribute("statuses", WebhookStatus.values());
        model.addAttribute("consumer", consumer);
        return "admin/webhook/list";
    }

    @PostMapping("/{id}/replay")
    public String replay(@PathVariable String id,
                         @RequestParam(required = false) String status,
                         @RequestParam(required = false) String consumer,
                         RedirectAttributes redirectAttributes) {
        boolean requeued = webhookService.replayDelivery(id);
        redirectAttributes.addFlashAttribute("message",
                requeued ? "Delivery requeued." : "Delivery is not in FAILED state; not requeued.");
        redirectAttributes.addAttribute("status", status == null || status.isBlank() ? "FAILED" : status);
        if (consumer != null && !consumer.isBlank()) {
            redirectAttributes.addAttribute("consumer", consumer);
        }
        return "redirect:/admin/webhooks";
    }
}
