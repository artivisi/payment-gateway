package com.artivisi.paymentgateway.dto;

import com.artivisi.paymentgateway.entity.AuthScheme;
import com.artivisi.paymentgateway.entity.EscrowEnvironment;
import com.artivisi.paymentgateway.entity.HostingModel;
import com.artivisi.paymentgateway.entity.PayerPadding;
import com.artivisi.paymentgateway.entity.TransportProtocol;
import com.artivisi.paymentgateway.entity.VaAllocationStrategy;

/**
 * Escrow edit payload. Code is immutable (omitted). Secrets ({@code clientSecret}, {@code privateKey})
 * rotate only when non-blank. Structural fields are applied only while the escrow has no VAs, and
 * the number layout is structural: changing a segment width or the padding rule after numbers have
 * been issued changes which payer every future number belongs to.
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
        java.math.BigDecimal settlementFee,
        VaAllocationStrategy allocationStrategy,
        Integer slotDigits,
        PayerPadding payerPadding,
        String interbankPrefix
) {
}
