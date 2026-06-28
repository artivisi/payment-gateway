package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.config.AdminSecurityProperties;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.exceptions.QrGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.util.Utils;
import org.springframework.stereotype.Service;

/** RFC 6238 TOTP for admin MFA: secret generation, enrolment QR, and code verification. */
@Service
public class TotpService {

    private final SecretGenerator secretGenerator = new DefaultSecretGenerator();
    private final QrGenerator qrGenerator = new ZxingPngQrGenerator();
    private final CodeVerifier codeVerifier;
    private final String issuer;

    public TotpService(AdminSecurityProperties properties) {
        this.issuer = properties.mfa().issuer();
        DefaultCodeVerifier verifier = new DefaultCodeVerifier(new DefaultCodeGenerator(), new SystemTimeProvider());
        verifier.setAllowedTimePeriodDiscrepancy(1); // tolerate +/- one 30s window for clock skew
        this.codeVerifier = verifier;
    }

    public String generateSecret() {
        return secretGenerator.generate();
    }

    /** A {@code data:image/png;base64,...} URI of the enrolment QR for the authenticator app. */
    public String qrDataUri(String username, String secret) {
        QrData data = new QrData.Builder()
                .label(username)
                .secret(secret)
                .issuer(issuer)
                .algorithm(HashingAlgorithm.SHA1)
                .digits(6)
                .period(30)
                .build();
        try {
            return Utils.getDataUriForImage(qrGenerator.generate(data), qrGenerator.getImageMimeType());
        } catch (QrGenerationException e) {
            throw new IllegalStateException("failed to generate TOTP enrolment QR", e);
        }
    }

    public boolean verify(String secret, String code) {
        return secret != null && code != null && !code.isBlank() && codeVerifier.isValidCode(secret, code.trim());
    }
}
