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
 * One bank payment instrument for a {@link Charge}. The {@code vaNumber} is allocated by the
 * gateway inside this escrow's number space, from the charge's payer and slot keys. Effective amount
 * on inquiry is {@code charge.amount - charge.cumulativePaid}.
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

    /**
     * The form a payer uses from a different bank, as composed when this VA was allocated.
     *
     * <p>Stored rather than derived: the payer was told this number once and keeps it, so a later
     * change to the escrow's interbank prefix must not rewrite what past charges reported. Null on
     * VAs created before the gateway allocated numbers, when the consumer composed both forms.
     */
    private String interbankVaNumber;

    @Enumerated(EnumType.STRING)
    private VirtualAccountStatus status;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
