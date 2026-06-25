package com.artivisi.paymentgateway.adapter.snap;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class SnapSignatureHelperTest {

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    @Test
    @SnapSpec("snap.sec.access-token.signature")
    void tokenSignatureRoundTrips() throws Exception {
        KeyPair keyPair = rsaKeyPair();
        String publicKeyPem = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        String clientId = "maybank-client";
        String timestamp = "2026-06-25T10:00:00+07:00";

        String signature = SnapSignatureHelper.signToken(keyPair.getPrivate(), clientId, timestamp);

        assertThat(SnapSignatureHelper.verifyToken(publicKeyPem, clientId, timestamp, signature)).isTrue();
        assertThat(SnapSignatureHelper.verifyToken(publicKeyPem, clientId, "2026-06-25T10:05:00+07:00", signature))
                .as("different timestamp must not verify").isFalse();
    }

    @Test
    @SnapSpec({"snap.sec.transaction.signature.symmetric", "snap.sec.endpoint-url"})
    void transactionSignatureRoundTrips() {
        String secret = "client-secret";
        String body = "{\"virtualAccountNo\":\"  12345 9999999\"}";
        String expected = SnapSignatureHelper.signTransaction(
                secret, "POST", "/v1.0/transfer-va/inquiry", "token-123", body, "2026-06-25T10:00:00+07:00");

        assertThat(SnapSignatureHelper.verifyTransaction(
                secret, "POST", "/v1.0/transfer-va/inquiry", "token-123", body, "2026-06-25T10:00:00+07:00", expected))
                .isTrue();
        assertThat(SnapSignatureHelper.verifyTransaction(
                secret, "POST", "/v1.0/transfer-va/inquiry", "token-123", "{\"x\":1}", "2026-06-25T10:00:00+07:00", expected))
                .as("tampered body must not verify").isFalse();
    }

    @Test
    @SnapSpec("snap.sec.body-minify")
    void emptyBodyHashesEmptyString() {
        assertThat(SnapSignatureHelper.sha256HexLower(""))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }
}
