package com.artivisi.paymentgateway.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;

/** One CIDR entry in the app-layer allowlist for a bank provider's callback endpoints. */
@Getter
@Setter
@Entity
@Table(name = "bank_ip_rule")
public class BankIpRule {

    @Id
    @UuidGenerator
    private String id;

    /** Bank provider key: bsi | cimb | maybank (matches escrow.provider). */
    private String provider;

    private String cidr;

    private String label;

    private boolean enabled;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
