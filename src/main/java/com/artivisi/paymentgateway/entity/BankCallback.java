package com.artivisi.paymentgateway.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One inbound bank message, kept as the bank sent it — minus its signature.
 *
 * <p>Append-only by intention: nothing updates a row here. It is the record of what arrived, which is
 * a different thing from what we made of it.
 */
@Getter
@Setter
@Entity
@Table(name = "bank_callback")
public class BankCallback {

    @Id
    @UuidGenerator
    private String id;

    private String provider;

    private Instant receivedAt;

    private String action;

    private String vaNumber;

    private String bankReference;

    @Column(columnDefinition = "text")
    private String payload;

    /**
     * Fields the bank sent that our DTO does not model, comma-separated, or null when there were
     * none. Recorded as data because the alternative — a log line — is what let
     * {@code nomorJurnalPembukuan} go unnoticed from launch until 2026-08-25.
     */
    private String unknownFields;
}
