package com.artivisi.paymentgateway.entity;

import com.artivisi.paymentgateway.config.SecretConverter;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;

/** An admin-UI operator account (PCI Req 8: unique identity, hashed credential, lockout, MFA). */
@Getter
@Setter
@Entity
@Table(name = "operator")
public class Operator {

    @Id
    @UuidGenerator
    private String id;

    private String username;

    /** BCrypt hash — never the plaintext. */
    private String passwordHash;

    private String fullName;

    @Enumerated(EnumType.STRING)
    private OperatorRole role;

    private boolean enabled;

    private int failedAttempts;

    private Instant lockedUntil;

    /** Base32 TOTP shared secret, encrypted at rest. Null until MFA enrolment. */
    @Convert(converter = SecretConverter.class)
    private String mfaSecret;

    private boolean mfaEnabled;

    private boolean mustChangePassword;

    private Instant lastLoginAt;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    public boolean isLocked() {
        return lockedUntil != null && lockedUntil.isAfter(Instant.now());
    }
}
