package com.artivisi.paymentgateway.dto;

import com.artivisi.paymentgateway.entity.AuthScheme;
import com.artivisi.paymentgateway.entity.EscrowAccount;
import com.artivisi.paymentgateway.entity.EscrowEnvironment;
import com.artivisi.paymentgateway.entity.HostingModel;
import com.artivisi.paymentgateway.entity.TransportProtocol;

import java.time.Instant;

/**
 * Escrow view for the admin API. Deliberately omits {@code clientSecret} and {@code privateKey} —
 * secrets are never returned.
 */
public record EscrowAccountResponse(
        String id,
        String code,
        String provider,
        HostingModel hostingModel,
        TransportProtocol transport,
        AuthScheme authScheme,
        EscrowEnvironment activeEnvironment,
        String clientId,
        String partnerId,
        String channelId,
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
        Instant createdAt,
        Instant updatedAt
) {
    public static EscrowAccountResponse from(EscrowAccount e) {
        return new EscrowAccountResponse(
                e.getId(), e.getCode(), e.getProvider(), e.getHostingModel(), e.getTransport(),
                e.getAuthScheme(), e.getActiveEnvironment(), e.getClientId(), e.getPartnerId(),
                e.getChannelId(), e.getPublicKey(), e.getSandboxBaseUrl(), e.getProductionBaseUrl(),
                e.getSettlementAccountNumber(), e.getSettlementAccountName(), e.getCompanyId(),
                e.getVaPrefix(), e.getVaDigitLength(), e.getMerchantTag(), e.getInstitutionTag(),
                e.getCreatedAt(), e.getUpdatedAt());
    }
}
