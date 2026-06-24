package com.artivisi.paymentgateway.dto;

import com.artivisi.paymentgateway.entity.AuthScheme;
import com.artivisi.paymentgateway.entity.EscrowEnvironment;
import com.artivisi.paymentgateway.entity.HostingModel;
import com.artivisi.paymentgateway.entity.TransportProtocol;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

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
        @NotBlank String vaPrefix,
        @NotNull Integer vaDigitLength,
        String merchantTag,
        String institutionTag
) {
}
