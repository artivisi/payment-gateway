package com.artivisi.paymentgateway.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;

/**
 * A long-lived API credential belonging to one operator, issued through the device flow.
 *
 * <p>Only the bcrypt hash is stored — the plaintext is returned once, at issue, and is
 * unrecoverable afterwards. A token never carries more than its owner's role permissions, and those
 * are resolved at request time, so revoking a permission takes effect immediately rather than at the
 * next token issue.
 */
@Getter
@Setter
@Entity
@Table(name = "device_token")
public class DeviceToken {

    @Id
    @UuidGenerator
    private String id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "id_operator")
    private Operator operator;

    private String tokenHash;

    private String deviceName;

    private String clientId;

    @CreationTimestamp
    private Instant createdAt;

    /** Null means no expiry — deliberate and rare; the default is 30 days. */
    private Instant expiresAt;

    private Instant lastUsedAt;

    private String lastUsedIp;

    private Instant revokedAt;

    private String revokedBy;

    public boolean isActive() {
        return revokedAt == null && (expiresAt == null || Instant.now().isBefore(expiresAt));
    }
}
