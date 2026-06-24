package com.artivisi.paymentgateway.config;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCipherTest {

    private static String key(int bytes) {
        return Base64.getEncoder().encodeToString(new byte[bytes]);
    }

    @Test
    void encryptDecryptRoundTrips() {
        SecretCipher cipher = new SecretCipher(new GatewaySecurityProperties(key(32)));
        String plaintext = "super-secret-client-secret";
        String encrypted = cipher.encrypt(plaintext);
        assertThat(encrypted).isNotEqualTo(plaintext);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(plaintext);
    }

    @Test
    void distinctCiphertextPerEncryption() {
        SecretCipher cipher = new SecretCipher(new GatewaySecurityProperties(key(32)));
        assertThat(cipher.encrypt("x")).isNotEqualTo(cipher.encrypt("x"));
    }

    @Test
    void rejectsWrongLengthKey() {
        assertThatThrownBy(() -> new SecretCipher(new GatewaySecurityProperties(key(16))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void rejectsNonBase64Key() {
        assertThatThrownBy(() -> new SecretCipher(new GatewaySecurityProperties("not valid base64 !!!")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Base64");
    }
}
