package com.artivisi.paymentgateway.entity;

import jakarta.persistence.Column;
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

import java.math.BigDecimal;
import java.time.Instant;

/** One flagged outcome of a reconciliation run. */
@Getter
@Setter
@Entity
@Table(name = "reconciliation_discrepancy")
public class ReconciliationDiscrepancy {

    @Id
    @UuidGenerator
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id_reconciliation_run")
    private ReconciliationRun reconciliationRun;

    @Enumerated(EnumType.STRING)
    private DiscrepancyType type;

    private String vaNumber;

    private String bankReference;

    private BigDecimal amount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_payment")
    private Payment payment;

    @Column(columnDefinition = "text")
    private String detail;

    @CreationTimestamp
    private Instant createdAt;
}
