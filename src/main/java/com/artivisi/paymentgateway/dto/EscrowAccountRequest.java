package com.artivisi.paymentgateway.dto;

import com.artivisi.paymentgateway.entity.AuthScheme;
import com.artivisi.paymentgateway.entity.EscrowEnvironment;
import com.artivisi.paymentgateway.entity.HostingModel;
import com.artivisi.paymentgateway.entity.PayerPadding;
import com.artivisi.paymentgateway.entity.TransportProtocol;
import com.artivisi.paymentgateway.entity.VaAllocationStrategy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * The four number-layout fields are required, and there is deliberately no overload that leaves
 * them out. An escrow created without them cannot allocate a VA number at all, so an overload
 * filling them with nulls would produce an escrow that looks configured and fails at the first
 * charge. {@code interbankPrefix} may be empty — that states the interbank form is the VA number
 * itself — but it may not be absent.
 */
public record EscrowAccountRequest(
        @NotBlank String code,
        @NotBlank String provider,
        @NotNull HostingModel hostingModel,
        @NotNull TransportProtocol transport,
        @NotNull AuthScheme authScheme,
        @NotNull EscrowEnvironment activeEnvironment,
        String clientId,
        String clientSecret,
        String partnerId,
        String channelId,
        String privateKey,
        String publicKey,
        String sandboxBaseUrl,
        String productionBaseUrl,
        @NotBlank String settlementAccountNumber,
        @NotBlank String settlementAccountName,
        @NotBlank String companyId,
        // Not @NotBlank: a bank whose numbers carry no prefix has an empty one.
        @NotNull String vaPrefix,
        @NotNull Integer vaDigitLength,
        String merchantTag,
        String institutionTag,
        java.math.BigDecimal settlementFee,
        @NotNull VaAllocationStrategy allocationStrategy,
        @NotNull @PositiveOrZero Integer slotDigits,
        @NotNull PayerPadding payerPadding,
        @NotNull String interbankPrefix
) {
}
