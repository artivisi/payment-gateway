package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.config.AdminSecurityProperties;
import com.artivisi.paymentgateway.entity.Operator;
import com.artivisi.paymentgateway.repository.OperatorRepository;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Account lockout (PCI Req 8.3.4) + authentication audit (PCI Req 10). Password success resets the
 * failure counter; repeated bad credentials lock the account for a configured window. These fire on
 * the password step; the second factor (TOTP) is verified separately in the MFA flow.
 */
@Component
public class AuthEventListener {

    private final OperatorRepository operatorRepository;
    private final AuditService auditService;
    private final AdminSecurityProperties properties;

    public AuthEventListener(OperatorRepository operatorRepository, AuditService auditService,
                             AdminSecurityProperties properties) {
        this.operatorRepository = operatorRepository;
        this.auditService = auditService;
        this.properties = properties;
    }

    @EventListener
    @Transactional
    public void onSuccess(AuthenticationSuccessEvent event) {
        String username = event.getAuthentication().getName();
        operatorRepository.findByUsername(username).ifPresent(op -> {
            op.setFailedAttempts(0);
            op.setLockedUntil(null);
            operatorRepository.save(op);
        });
        auditService.recordAs(username, "AUTH_LOGIN_SUCCESS", "Operator", null, "password verified");
    }

    @EventListener
    @Transactional
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        String username = event.getAuthentication() == null ? null : String.valueOf(event.getAuthentication().getName());
        if (username != null && event instanceof AuthenticationFailureBadCredentialsEvent) {
            operatorRepository.findByUsername(username).ifPresent(op -> registerBadCredential(op, username));
        }
        auditService.recordAs(username, "AUTH_LOGIN_FAILED", "Operator", null, event.getClass().getSimpleName());
    }

    private void registerBadCredential(Operator op, String username) {
        op.setFailedAttempts(op.getFailedAttempts() + 1);
        if (op.getFailedAttempts() >= properties.maxFailedAttempts()) {
            op.setLockedUntil(Instant.now().plus(properties.lockMinutes(), ChronoUnit.MINUTES));
            auditService.recordAs(username, "AUTH_ACCOUNT_LOCKED", "Operator", op.getId(),
                    "locked after " + op.getFailedAttempts() + " failed attempts");
        }
        operatorRepository.save(op);
    }
}
