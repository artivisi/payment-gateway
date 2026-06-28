package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.AbstractIntegrationTest;
import com.artivisi.paymentgateway.config.AdminSecurityProperties;
import com.artivisi.paymentgateway.entity.Operator;
import com.artivisi.paymentgateway.repository.OperatorRepository;
import com.artivisi.paymentgateway.repository.RoleRepository;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import dev.samstevens.totp.time.SystemTimeProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Operator lifecycle + auth controls: password policy, change, TOTP MFA, and lockout. */
class OperatorAuthIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired OperatorService operatorService;
    @Autowired TotpService totpService;
    @Autowired OperatorRepository operatorRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired AdminSecurityProperties properties;
    @Autowired ApplicationEventPublisher publisher;

    private String user(String prefix) {
        return prefix + "-" + SEQ.incrementAndGet();
    }

    private String operatorRoleId() {
        return roleRepository.findByName("OPERATOR").orElseThrow().getId();
    }

    @Test
    void bootstrapAdmin_isSeededWithForcedChange() {
        Operator admin = operatorRepository.findByUsername("bootstrap-admin").orElseThrow();
        assertThat(admin.getRole().getName()).isEqualTo("ADMIN");
        assertThat(admin.getRole().isAllPermissions()).isTrue();
        assertThat(admin.isMustChangePassword()).isTrue();
        assertThat(admin.isMfaEnabled()).isFalse();
    }

    @Test
    void create_enforcesMinimumPasswordLength() {
        assertThatThrownBy(() ->
                operatorService.create(user("short"), "S", operatorRoleId(), "tooshort"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void changeOwnPassword_validatesCurrentAndClearsForcedFlag() {
        String username = user("chg");
        operatorService.create(username, "C", operatorRoleId(), "password-123456");

        assertThatThrownBy(() ->
                operatorService.changeOwnPassword(username, "wrong-current", "newpassword-123456"))
                .isInstanceOf(IllegalArgumentException.class);

        operatorService.changeOwnPassword(username, "password-123456", "newpassword-123456");
        assertThat(operatorRepository.findByUsername(username).orElseThrow().isMustChangePassword()).isFalse();
    }

    @Test
    void mfaEnrolmentAndVerification() {
        String username = user("mfa");
        operatorService.create(username, "M", operatorRoleId(), "password-123456");
        String secret = totpService.generateSecret();

        assertThat(operatorService.completeMfaEnrolment(username, secret, code(secret))).isTrue();
        assertThat(operatorRepository.findByUsername(username).orElseThrow().isMfaEnabled()).isTrue();
        assertThat(operatorService.verifyMfa(username, code(secret))).isTrue();
        assertThat(operatorService.verifyMfa(username, "000000")).isFalse();
    }

    @Test
    void repeatedBadCredentials_lockTheAccount() {
        String username = user("lock");
        operatorService.create(username, "L", operatorRoleId(), "password-123456");
        var token = new UsernamePasswordAuthenticationToken(username, "bad");

        for (int i = 0; i < properties.maxFailedAttempts(); i++) {
            publisher.publishEvent(new AuthenticationFailureBadCredentialsEvent(token, new BadCredentialsException("bad")));
        }

        Operator locked = operatorRepository.findByUsername(username).orElseThrow();
        assertThat(locked.getFailedAttempts()).isEqualTo(properties.maxFailedAttempts());
        assertThat(locked.isLocked()).isTrue();
    }

    private String code(String secret) {
        try {
            long counter = Math.floorDiv(new SystemTimeProvider().getTime(), 30);
            return new DefaultCodeGenerator().generate(secret, counter);
        } catch (CodeGenerationException e) {
            throw new IllegalStateException(e);
        }
    }
}
