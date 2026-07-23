package com.artivisi.paymentgateway.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;

/** One in-flight device authorization (RFC 8628): issued to a CLI, approved by a human in a browser. */
@Getter
@Setter
@Entity
@Table(name = "device_code")
public class DeviceCode {

    public enum Status { PENDING, AUTHORIZED, DENIED, EXPIRED }

    @Id
    @UuidGenerator
    private String id;

    /** Long random secret the CLI polls with. Never shown to the human. */
    private String deviceCode;

    /** Short code the human types into the browser. Never enough on its own to obtain a token. */
    private String userCode;

    private String clientId;

    private String deviceName;

    @Enumerated(EnumType.STRING)
    private Status status;

    @ManyToOne
    @JoinColumn(name = "id_operator")
    private Operator operator;

    @CreationTimestamp
    private Instant createdAt;

    private Instant expiresAt;

    private Instant authorizedAt;

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
}
