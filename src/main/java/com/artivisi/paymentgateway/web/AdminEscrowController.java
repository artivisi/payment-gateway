package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.dto.EscrowAccountRequest;
import com.artivisi.paymentgateway.entity.AuthScheme;
import com.artivisi.paymentgateway.entity.EscrowEnvironment;
import com.artivisi.paymentgateway.entity.HostingModel;
import com.artivisi.paymentgateway.entity.TransportProtocol;
import com.artivisi.paymentgateway.service.EscrowAccountService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/escrow-accounts")
public class AdminEscrowController {

    private final EscrowAccountService escrowAccountService;

    public AdminEscrowController(EscrowAccountService escrowAccountService) {
        this.escrowAccountService = escrowAccountService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("escrows", escrowAccountService.list());
        return "admin/escrow/list";
    }

    @GetMapping("/new")
    public String form() {
        return "admin/escrow/form";
    }

    @PostMapping
    public String create(
            @RequestParam String code,
            @RequestParam String provider,
            @RequestParam HostingModel hostingModel,
            @RequestParam TransportProtocol transport,
            @RequestParam AuthScheme authScheme,
            @RequestParam EscrowEnvironment activeEnvironment,
            @RequestParam(required = false) String clientId,
            @RequestParam(required = false) String clientSecret,
            @RequestParam(required = false) String partnerId,
            @RequestParam(required = false) String channelId,
            @RequestParam(required = false) String publicKey,
            @RequestParam String settlementAccountNumber,
            @RequestParam String settlementAccountName,
            @RequestParam String companyId,
            @RequestParam String vaPrefix,
            @RequestParam Integer vaDigitLength,
            @RequestParam(required = false) String merchantTag,
            @RequestParam(required = false) String institutionTag,
            RedirectAttributes redirectAttributes) {
        try {
            escrowAccountService.create(new EscrowAccountRequest(code, provider, hostingModel, transport, authScheme,
                    activeEnvironment, blankToNull(clientId), blankToNull(clientSecret), blankToNull(partnerId),
                    blankToNull(channelId), null, blankToNull(publicKey), null, null,
                    settlementAccountNumber, settlementAccountName, companyId, vaPrefix, vaDigitLength,
                    blankToNull(merchantTag), blankToNull(institutionTag)));
            redirectAttributes.addFlashAttribute("message", "Escrow account '" + code + "' created.");
            return "redirect:/admin/escrow-accounts";
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/escrow-accounts/new";
        }
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
