package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.entity.DeviceCode;
import com.artivisi.paymentgateway.repository.OperatorRepository;
import com.artivisi.paymentgateway.service.DeviceAuthService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The human half of the device flow: an operator, already logged in, confirms the code their CLI is
 * showing. Requiring a real session here is the whole point — it binds the token to a named person.
 */
@Controller
@RequestMapping("/device")
public class DeviceAuthorizationController {

    private final DeviceAuthService deviceAuthService;
    private final OperatorRepository operatorRepository;

    public DeviceAuthorizationController(DeviceAuthService deviceAuthService,
                                         OperatorRepository operatorRepository) {
        this.deviceAuthService = deviceAuthService;
        this.operatorRepository = operatorRepository;
    }

    @GetMapping
    public String page(@RequestParam(required = false) String code, Model model) {
        model.addAttribute("code", code == null ? "" : code);
        if (code != null && !code.isBlank()) {
            try {
                DeviceCode pending = deviceAuthService.findByUserCode(code);
                model.addAttribute("pending", pending);
            } catch (RuntimeException e) {
                model.addAttribute("error", e.getMessage());
            }
        }
        return "device/authorize";
    }

    @PostMapping("/authorize")
    public String authorize(@RequestParam String code, @AuthenticationPrincipal UserDetails principal,
                            RedirectAttributes ra) {
        try {
            deviceAuthService.authorize(code, operatorRepository.findByUsername(principal.getUsername())
                    .orElseThrow(() -> new IllegalStateException("operator not found")));
            ra.addFlashAttribute("message", "Device authorized. The CLI will pick up its token within a few seconds.");
        } catch (RuntimeException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/device";
    }

    @PostMapping("/deny")
    public String deny(@RequestParam String code, RedirectAttributes ra) {
        try {
            deviceAuthService.deny(code);
            ra.addFlashAttribute("message", "Device denied.");
        } catch (RuntimeException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/device";
    }
}
