package com.artivisi.paymentgateway.dto;

/** Issued once. The plaintext token is never retrievable again — only its bcrypt hash is stored. */
public record DeviceTokenResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        String operator,
        String deviceName
) {
}
