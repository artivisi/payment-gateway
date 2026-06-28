package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.config.AdminAccessGuardFilter;
import com.artivisi.paymentgateway.service.OperatorService;
import com.artivisi.paymentgateway.service.TotpService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.security.Principal;

/** Login page + post-login step-up flow: forced password change, MFA enrolment, MFA verification. */
@Controller
public class AuthController {

    private static final String ENROLL_SECRET = "MFA_ENROLL_SECRET";

    private final OperatorService operatorService;
    private final TotpService totpService;

    public AuthController(OperatorService operatorService, TotpService totpService) {
        this.operatorService = operatorService;
        this.totpService = totpService;
    }

    @GetMapping("/login")
    public String login() {
        return "auth/login";
    }

    @GetMapping("/change-password")
    public String changePasswordForm() {
        return "auth/change-password";
    }

    @PostMapping("/change-password")
    public String changePassword(Principal principal,
                                 @RequestParam String currentPassword,
                                 @RequestParam String newPassword,
                                 @RequestParam String confirmPassword,
                                 Model model) {
        if (!newPassword.equals(confirmPassword)) {
            model.addAttribute("error", "New password and confirmation do not match.");
            return "auth/change-password";
        }
        try {
            operatorService.changeOwnPassword(principal.getName(), currentPassword, newPassword);
        } catch (RuntimeException e) {
            model.addAttribute("error", e.getMessage());
            return "auth/change-password";
        }
        return "redirect:/admin";
    }

    @GetMapping("/mfa/enroll")
    public String enrollForm(Principal principal, HttpSession session, Model model) {
        String secret = (String) session.getAttribute(ENROLL_SECRET);
        if (secret == null) {
            secret = totpService.generateSecret();
            session.setAttribute(ENROLL_SECRET, secret);
        }
        model.addAttribute("secret", secret);
        model.addAttribute("qrDataUri", totpService.qrDataUri(principal.getName(), secret));
        return "auth/mfa-enroll";
    }

    @PostMapping("/mfa/enroll")
    public String enroll(Principal principal, HttpSession session,
                         @RequestParam String code, Model model) {
        String secret = (String) session.getAttribute(ENROLL_SECRET);
        if (secret == null || !operatorService.completeMfaEnrolment(principal.getName(), secret, code)) {
            model.addAttribute("error", "Invalid code. Scan the QR and enter the current 6-digit code.");
            model.addAttribute("secret", secret);
            model.addAttribute("qrDataUri", secret == null ? null : totpService.qrDataUri(principal.getName(), secret));
            return "auth/mfa-enroll";
        }
        session.removeAttribute(ENROLL_SECRET);
        session.setAttribute(AdminAccessGuardFilter.MFA_VERIFIED, Boolean.TRUE);
        return "redirect:/admin";
    }

    @GetMapping("/mfa")
    public String mfaForm() {
        return "auth/mfa";
    }

    @PostMapping("/mfa")
    public String mfa(Principal principal, HttpSession session, @RequestParam String code, Model model) {
        if (!operatorService.verifyMfa(principal.getName(), code)) {
            model.addAttribute("error", "Invalid code.");
            return "auth/mfa";
        }
        session.setAttribute(AdminAccessGuardFilter.MFA_VERIFIED, Boolean.TRUE);
        return "redirect:/admin";
    }
}
