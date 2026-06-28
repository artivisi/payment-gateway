package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.config.AdminSecurityProperties;
import com.artivisi.paymentgateway.entity.Operator;
import com.artivisi.paymentgateway.entity.Role;
import com.artivisi.paymentgateway.exception.DuplicateException;
import com.artivisi.paymentgateway.exception.NotFoundException;
import com.artivisi.paymentgateway.repository.OperatorRepository;
import com.artivisi.paymentgateway.repository.RoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Operator account lifecycle: bootstrap, CRUD, password change, and TOTP MFA enrolment/verification. */
@Service
public class OperatorService {

    private static final Logger log = LoggerFactory.getLogger(OperatorService.class);
    private static final int MIN_PASSWORD_LENGTH = 12; // PCI Req 8.3.6

    private final OperatorRepository repository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final TotpService totpService;
    private final AuditService auditService;
    private final AdminSecurityProperties properties;

    public OperatorService(OperatorRepository repository, RoleRepository roleRepository,
                           PasswordEncoder passwordEncoder, TotpService totpService,
                           AuditService auditService, AdminSecurityProperties properties) {
        this.repository = repository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.totpService = totpService;
        this.auditService = auditService;
        this.properties = properties;
    }

    /** Seed the first ADMIN from required config when the table is empty (fail-loud config, forced change). */
    @Transactional
    public void seedBootstrapAdminIfEmpty() {
        if (repository.count() > 0) {
            return;
        }
        AdminSecurityProperties.Bootstrap bootstrap = properties.bootstrap();
        Operator admin = new Operator();
        admin.setUsername(bootstrap.username());
        admin.setPasswordHash(passwordEncoder.encode(bootstrap.password()));
        admin.setFullName("Bootstrap Administrator");
        admin.setRole(roleRepository.findByName("ADMIN")
                .orElseThrow(() -> new IllegalStateException("ADMIN role not seeded (migration V7)")));
        admin.setEnabled(true);
        admin.setFailedAttempts(0);
        admin.setMfaEnabled(false);
        admin.setMustChangePassword(true);
        repository.save(admin);
        log.warn("Seeded bootstrap ADMIN operator '{}' (must change password + enrol MFA on first login).",
                bootstrap.username());
        auditService.recordAs(bootstrap.username(), "OPERATOR_BOOTSTRAPPED", "Operator", admin.getId(), "initial admin");
    }

    @Transactional(readOnly = true)
    public List<Operator> list() {
        return repository.findAllByOrderByUsernameAsc();
    }

    @Transactional(readOnly = true)
    public Operator get(String id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Operator not found: " + id));
    }

    @Transactional
    public Operator create(String username, String fullName, String roleId, String initialPassword) {
        if (repository.existsByUsername(username)) {
            throw new DuplicateException("Operator username already exists: " + username);
        }
        validatePassword(initialPassword);
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new NotFoundException("Role not found: " + roleId));
        Operator op = new Operator();
        op.setUsername(username);
        op.setFullName(fullName);
        op.setRole(role);
        op.setPasswordHash(passwordEncoder.encode(initialPassword));
        op.setEnabled(true);
        op.setFailedAttempts(0);
        op.setMfaEnabled(false);
        op.setMustChangePassword(true);
        Operator saved = repository.save(op);
        auditService.record("OPERATOR_CREATED", "Operator", saved.getId(),
                "username=" + username + " role=" + role.getName());
        return saved;
    }

    @Transactional
    public void setEnabled(String id, boolean enabled) {
        Operator op = get(id);
        op.setEnabled(enabled);
        repository.save(op);
        auditService.record(enabled ? "OPERATOR_ENABLED" : "OPERATOR_DISABLED", "Operator", id,
                "username=" + op.getUsername());
    }

    /** Admin reset: set a temporary password and force a change on next login. */
    @Transactional
    public void resetPassword(String id, String temporaryPassword) {
        validatePassword(temporaryPassword);
        Operator op = get(id);
        op.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        op.setMustChangePassword(true);
        op.setFailedAttempts(0);
        op.setLockedUntil(null);
        repository.save(op);
        auditService.record("OPERATOR_PASSWORD_RESET", "Operator", id, "username=" + op.getUsername());
    }

    /** Admin reset of a lost authenticator: clears MFA so the operator re-enrols on next login. */
    @Transactional
    public void resetMfa(String id) {
        Operator op = get(id);
        op.setMfaEnabled(false);
        op.setMfaSecret(null);
        repository.save(op);
        auditService.record("OPERATOR_MFA_RESET", "Operator", id, "username=" + op.getUsername());
    }

    @Transactional
    public void changeOwnPassword(String username, String currentPassword, String newPassword) {
        Operator op = require(username);
        if (!passwordEncoder.matches(currentPassword, op.getPasswordHash())) {
            throw new IllegalArgumentException("Current password is incorrect.");
        }
        validatePassword(newPassword);
        if (passwordEncoder.matches(newPassword, op.getPasswordHash())) {
            throw new IllegalArgumentException("New password must differ from the current one.");
        }
        op.setPasswordHash(passwordEncoder.encode(newPassword));
        op.setMustChangePassword(false);
        repository.save(op);
        auditService.recordAs(username, "OPERATOR_PASSWORD_CHANGED", "Operator", op.getId(), null);
    }

    /** Verify the first code against a pending secret and persist enrolment. */
    @Transactional
    public boolean completeMfaEnrolment(String username, String pendingSecret, String code) {
        if (!totpService.verify(pendingSecret, code)) {
            return false;
        }
        Operator op = require(username);
        op.setMfaSecret(pendingSecret);
        op.setMfaEnabled(true);
        op.setLastLoginAt(Instant.now());
        repository.save(op);
        auditService.recordAs(username, "OPERATOR_MFA_ENROLLED", "Operator", op.getId(), null);
        return true;
    }

    @Transactional
    public boolean verifyMfa(String username, String code) {
        Operator op = require(username);
        if (!totpService.verify(op.getMfaSecret(), code)) {
            return false;
        }
        op.setLastLoginAt(Instant.now());
        repository.save(op);
        auditService.recordAs(username, "AUTH_MFA_VERIFIED", "Operator", op.getId(), null);
        return true;
    }

    private Operator require(String username) {
        return repository.findByUsername(username)
                .orElseThrow(() -> new NotFoundException("Operator not found: " + username));
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }
    }
}
