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
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One end-of-day reconciliation pass for an escrow account over a settlement period.
 */
@Getter
@Setter
@Entity
@Table(name = "reconciliation_run")
public class ReconciliationRun {

    @Id
    @UuidGenerator
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id_escrow_account")
    private EscrowAccount escrowAccount;

    private LocalDate period;

    @Enumerated(EnumType.STRING)
    private ReconciliationStatus status;

    private Instant startedAt;

    private Instant finishedAt;

    @CreationTimestamp
    private Instant createdAt;
}
