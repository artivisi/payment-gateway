package com.artivisi.paymentgateway.adapter.snap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * SNAP signature primitives (BI SNAP 1.0.2). Token requests use asymmetric SHA256withRSA;
 * transactional calls use symmetric HMAC-SHA512. See {@code docs/snap/snap-1.0.2.json}.
 *
 * <p>Inbound verification hashes the raw received body (the caller minified before signing).
 */
public final class SnapSignatureHelper {

    private SnapSignatureHelper() {
    }

    @SnapSpec("snap.sec.access-token.signature")
    public static String tokenStringToSign(String clientId, String timestamp) {
        return clientId + "|" + timestamp;
    }

    @SnapSpec("snap.sec.access-token.signature")
    public static String signToken(PrivateKey privateKey, String clientId, String timestamp) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(tokenStringToSign(clientId, timestamp).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("failed to sign SNAP token", e);
        }
    }

    @SnapSpec("snap.sec.access-token.signature")
    public static boolean verifyToken(String publicKeyPem, String clientId, String timestamp, String signatureB64) {
        if (signatureB64 == null) {
            return false;
        }
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(parsePublicKey(publicKeyPem));
            signature.update(tokenStringToSign(clientId, timestamp).getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(signatureB64));
        } catch (Exception e) {
            return false;
        }
    }

    @SnapSpec({"snap.sec.transaction.signature.symmetric", "snap.sec.body-minify", "snap.sec.endpoint-url"})
    public static String transactionStringToSign(String method, String path, String accessToken,
                                                 String body, String timestamp) {
        return method + ":" + path + ":" + accessToken + ":" + sha256HexLower(body) + ":" + timestamp;
    }

    @SnapSpec("snap.sec.transaction.signature.symmetric")
    public static String signTransaction(String clientSecret, String method, String path,
                                         String accessToken, String body, String timestamp) {
        String stringToSign = transactionStringToSign(method, path, accessToken, body, timestamp);
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(clientSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("failed to sign SNAP transaction", e);
        }
    }

    @SnapSpec("snap.sec.transaction.signature.symmetric")
    public static boolean verifyTransaction(String clientSecret, String method, String path, String accessToken,
                                            String body, String timestamp, String signatureB64) {
        if (signatureB64 == null) {
            return false;
        }
        String expected = signTransaction(clientSecret, method, path, accessToken, body, timestamp);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), signatureB64.getBytes(StandardCharsets.UTF_8));
    }

    @SnapSpec("snap.sec.body-minify")
    public static String sha256HexLower(String body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("failed to hash SNAP body", e);
        }
    }

    private static PublicKey parsePublicKey(String pem) {
        String base64 = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("invalid SNAP public key", e);
        }
    }
}
