package com.artivisi.paymentgateway.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The unit of money owed, created by a {@link Consumer}. Type and amount live here (not on
 * the VA) because they describe one debt regardless of which bank rail settles it. Payable
 * through 1..N sibling {@link VirtualAccount}s across escrows.
 */
@Getter
@Setter
@Entity
@Table(name = "charge")
public class Charge {

    @Id
    @UuidGenerator
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id_consumer")
    private Consumer consumer;

    /** The consumer's own bill id; unique per consumer (idempotency key). */
    private String consumerReference;

    private String payerName;

    private String payerEmail;

    private String payerPhone;

    @Enumerated(EnumType.STRING)
    private ChargeType chargeType;

    private BigDecimal amount;

    private BigDecimal cumulativePaid;

    @Enumerated(EnumType.STRING)
    private ChargeStatus status;

    private Instant expiresAt;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
