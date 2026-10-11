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
import java.math.BigDecimal;

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

    /** Selects the adapter: matched against {@code BankAdapter.provider()} of the adapters present. */
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

    /**
     * What the bank keeps from each payment before crediting the settlement account (one deployment's
     * bank charges a flat Rp 2.000
     * per VA payment). NULL means not configured, not zero — reconciliation refuses to run rather
     * than guess, because guessing zero silently mismatches every row at a bank that does charge.
     */
    private BigDecimal settlementFee;

    private String companyId;

    private String vaPrefix;

    private Integer vaDigitLength;

    /**
     * The number space this escrow allocates in. Every field is required before the escrow can
     * collect: {@code VaNumberAllocator} refuses an incomplete layout rather than filling a gap,
     * because a guessed segment width mints a number belonging to a different payer.
     */
    @Enumerated(EnumType.STRING)
    private VaAllocationStrategy allocationStrategy;

    /** Width of the slot segment, between the prefix and the payer segment. */
    private Integer slotDigits;

    @Enumerated(EnumType.STRING)
    private PayerPadding payerPadding;

    /**
     * Prefixed to the VA number to form what a payer uses from another bank. An empty string means
     * the interbank form is the number itself, which is a decision rather than an absence — null is
     * the absence, and it is refused.
     */
    private String interbankPrefix;

    /** Digits the payer key gets, after the prefix and the slot segment have taken theirs. */
    public int payerDigits() {
        return vaDigitLength - vaPrefix.length() - slotDigits;
    }

    private String merchantTag;

    private String institutionTag;

    /** Disabled escrows are rejected for new charges; history is preserved. */
    private boolean enabled;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
