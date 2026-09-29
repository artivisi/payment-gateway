package com.artivisi.paymentgateway.adapter.demo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The invented bank's message signature: SHA-256 over account number, amount and transaction time,
 * keyed by the shared secret on the escrow.
 *
 * <p>Compared with {@link MessageDigest#isEqual} rather than {@link String#equals} — constant time,
 * so a signature cannot be discovered a character at a time by measuring how fast it is rejected.
 * Real schemes differ in every detail except this one.
 */
final class DemoBankSignature {

    private DemoBankSignature() {
    }

    static boolean matches(DemoBankMessages.Request request, String secret) {
        if (request.signature() == null) {
            return false;
        }
        return MessageDigest.isEqual(
                compute(request, secret).getBytes(StandardCharsets.UTF_8),
                request.signature().getBytes(StandardCharsets.UTF_8));
    }

    /** A missing field signs as empty rather than as the text "null", which would be a silent default. */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    static String compute(DemoBankMessages.Request request, String secret) {
        String material = String.join("|",
                nullToEmpty(request.accountNumber()),
                nullToEmpty(request.amount()),
                nullToEmpty(request.transactionTime()),
                secret);
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM this runs on", e);
        }
    }
}
