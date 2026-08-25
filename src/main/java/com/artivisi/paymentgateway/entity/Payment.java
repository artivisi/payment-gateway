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

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One received transaction against a {@link VirtualAccount} (and its {@link Charge}).
 * Idempotent on {@code (virtualAccount, bankReference)}.
 */
@Getter
@Setter
@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @UuidGenerator
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id_virtual_account")
    private VirtualAccount virtualAccount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id_charge")
    private Charge charge;

    private BigDecimal amount;

    private String bankReference;

    /**
     * The bank's own bookkeeping reference, when its notification carries one (BSI:
     * {@code nomorJurnalPembukuan}). NULL for payments recorded before this existed, for payments
     * recovered from a settlement file rather than a notification, and for banks that send nothing
     * equivalent. Never derived — it is the bank's number or nothing.
     */
    private String bankJournalNumber;

    private Instant transactionTime;

    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    @CreationTimestamp
    private Instant createdAt;
}
