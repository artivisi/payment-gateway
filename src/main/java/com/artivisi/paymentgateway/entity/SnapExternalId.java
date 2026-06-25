package com.artivisi.paymentgateway.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.time.LocalDate;

/** Records a consumed SNAP X-EXTERNAL-ID for daily idempotency, per escrow + service. */
@Getter
@Setter
@Entity
@Table(name = "snap_external_id")
public class SnapExternalId {

    @Id
    @UuidGenerator
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id_escrow_account")
    private EscrowAccount escrowAccount;

    private String externalId;

    private String serviceName;

    private LocalDate transactionDate;

    @CreationTimestamp
    private Instant createdAt;
}
