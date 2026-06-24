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

import java.time.Instant;

/**
 * One bank payment instrument for a {@link Charge}. The consumer-supplied {@code vaNumber}
 * is validated within the escrow's number space. Effective amount on inquiry is
 * {@code charge.amount - charge.cumulativePaid}.
 */
@Getter
@Setter
@Entity
@Table(name = "virtual_account")
public class VirtualAccount {

    @Id
    @UuidGenerator
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id_charge")
    private Charge charge;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id_escrow_account")
    private EscrowAccount escrowAccount;

    private String vaNumber;

    @Enumerated(EnumType.STRING)
    private VirtualAccountStatus status;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
