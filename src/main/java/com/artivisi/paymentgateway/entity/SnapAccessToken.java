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

/** A SNAP bearer access token the gateway issued to a bank, scoped to an escrow. */
@Getter
@Setter
@Entity
@Table(name = "snap_access_token")
public class SnapAccessToken {

    @Id
    @UuidGenerator
    private String id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "id_escrow_account")
    private EscrowAccount escrowAccount;

    private String accessToken;

    private Instant expiresAt;

    @CreationTimestamp
    private Instant createdAt;
}
