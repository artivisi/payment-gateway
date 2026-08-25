package com.artivisi.paymentgateway.dto;

import com.artivisi.paymentgateway.entity.AuthScheme;
import com.artivisi.paymentgateway.entity.EscrowEnvironment;
import com.artivisi.paymentgateway.entity.HostingModel;
import com.artivisi.paymentgateway.entity.TransportProtocol;

/**
 * Escrow edit payload. Code is immutable (omitted). Secrets ({@code clientSecret}, {@code privateKey})
 * rotate only when non-blank. Structural fields are applied only while the escrow has no VAs.
 */
public record EscrowUpdateRequest(
        String provider,
        HostingModel hostingModel,
        TransportProtocol transport,
        AuthScheme authScheme,
        EscrowEnvironment activeEnvironment,
        String clientId,
        String clientSecret,
        String partnerId,
        String channelId,
        String privateKey,
        String publicKey,
        String sandboxBaseUrl,
        String productionBaseUrl,
        String settlementAccountNumber,
        String settlementAccountName,
        String companyId,
        String vaPrefix,
        Integer vaDigitLength,
        String merchantTag,
        String institutionTag,
        java.math.BigDecimal settlementFee
) {
    /** Source-compatibility overload for callers predating the settlement fee (null = not configured). */
    public EscrowUpdateRequest(String provider, HostingModel hostingModel, TransportProtocol transport, AuthScheme authScheme, EscrowEnvironment activeEnvironment, String clientId, String clientSecret, String partnerId, String channelId, String privateKey, String publicKey, String sandboxBaseUrl, String productionBaseUrl, String settlementAccountNumber, String settlementAccountName, String companyId, String vaPrefix, Integer vaDigitLength, String merchantTag, String institutionTag) {
        this(provider, hostingModel, transport, authScheme, activeEnvironment, clientId, clientSecret, partnerId, channelId, privateKey, publicKey, sandboxBaseUrl, productionBaseUrl, settlementAccountNumber, settlementAccountName, companyId, vaPrefix, vaDigitLength, merchantTag, institutionTag, null);
    }
}
