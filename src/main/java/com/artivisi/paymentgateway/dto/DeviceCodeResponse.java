package com.artivisi.paymentgateway.dto;

/** RFC 8628 device authorization response. Field names follow the spec's intent, camel-cased. */
public record DeviceCodeResponse(
        String deviceCode,
        String userCode,
        String verificationUri,
        String verificationUriComplete,
        long expiresIn,
        int interval
) {
}
