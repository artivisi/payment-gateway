package com.artivisi.paymentgateway.config;

import com.artivisi.paymentgateway.entity.Operator;
import com.artivisi.paymentgateway.repository.OperatorRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Enforces post-login step-up before any {@code /admin} access: a forced password change, then MFA
 * enrolment, then a per-session MFA verification. An authenticated operator who hasn't completed the
 * required step is redirected to it. The step pages live outside {@code /admin} so they stay reachable.
 */
public class AdminAccessGuardFilter extends OncePerRequestFilter {

    public static final String MFA_VERIFIED = "MFA_VERIFIED";

    private final OperatorRepository repository;

    public AdminAccessGuardFilter(OperatorRepository repository) {
        this.repository = repository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/admin")) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
                Operator operator = repository.findByUsername(auth.getName()).orElse(null);
                if (operator != null) {
                    String redirect = stepFor(operator, request);
                    if (redirect != null) {
                        response.sendRedirect(request.getContextPath() + redirect);
                        return;
                    }
                }
            }
        }
        chain.doFilter(request, response);
    }

    private String stepFor(Operator operator, HttpServletRequest request) {
        if (operator.isMustChangePassword()) {
            return "/change-password";
        }
        if (!operator.isMfaEnabled()) {
            return "/mfa/enroll";
        }
        if (!Boolean.TRUE.equals(request.getSession().getAttribute(MFA_VERIFIED))) {
            return "/mfa";
        }
        return null;
    }
}
