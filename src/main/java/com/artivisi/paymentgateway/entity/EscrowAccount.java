package com.artivisi.paymentgateway.entity;

import com.artivisi.paymentgateway.config.SecretConverter;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;

/**
 * The core unit — one bank biller. Carries everything structural: which adapter to use,
 * credentials, hosting model, transport/auth, endpoints, settlement account, number space,
 * and grouping tags. Settlement sits here; per-institution settlement = separate escrows
 * grouped by {@code institutionTag}.
 */
@Getter
@Setter
@Entity
@Table(name = "escrow_account")
public class EscrowAccount {

    @Id
    @UuidGenerator
    private String id;

    private String code;

    /** Selects the adapter: maybank | bsi | cimb. */
    private String provider;

    @Enumerated(EnumType.STRING)
    private HostingModel hostingModel;

    @Enumerated(EnumType.STRING)
    private TransportProtocol transport;

    @Enumerated(EnumType.STRING)
    private AuthScheme authScheme;

    @Enumerated(EnumType.STRING)
    private EscrowEnvironment activeEnvironment;

    private String clientId;

    @Convert(converter = SecretConverter.class)
    private String clientSecret;

    private String partnerId;

    private String channelId;

    @Convert(converter = SecretConverter.class)
    private String privateKey;

    private String publicKey;

    private String sandboxBaseUrl;

    private String productionBaseUrl;

    private String settlementAccountNumber;

    private String settlementAccountName;

    private String companyId;

    private String vaPrefix;

    private Integer vaDigitLength;

    private String merchantTag;

    private String institutionTag;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
