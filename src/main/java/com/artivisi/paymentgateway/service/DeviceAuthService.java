package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.entity.DeviceCode;
import com.artivisi.paymentgateway.entity.DeviceToken;
import com.artivisi.paymentgateway.entity.Operator;
import com.artivisi.paymentgateway.exception.InvalidRequestException;
import com.artivisi.paymentgateway.exception.NotFoundException;
import com.artivisi.paymentgateway.repository.DeviceCodeRepository;
import com.artivisi.paymentgateway.repository.DeviceTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * OAuth 2.0 Device Authorization Grant (RFC 8628).
 *
 * <p>A CLI asks for a code, a human approves it in a browser while logged in, and the CLI exchanges
 * it for a bearer token bound to that operator. Nothing secret is ever typed by the human — the
 * user code authorises, it does not authenticate, so seeing it over someone's shoulder grants
 * nothing without the device code the CLI holds.
 */
@Service
public class DeviceAuthService {

    private static final Logger log = LoggerFactory.getLogger(DeviceAuthService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Excludes I/O/0/1 — these get read aloud and retyped. */
    private static final String USER_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final Duration CODE_TTL = Duration.ofMinutes(15);
    private static final Duration TOKEN_TTL = Duration.ofDays(30);
    public static final int POLL_INTERVAL_SECONDS = 5;

    private final DeviceCodeRepository deviceCodeRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public DeviceAuthService(DeviceCodeRepository deviceCodeRepository,
                             DeviceTokenRepository deviceTokenRepository,
                             PasswordEncoder passwordEncoder,
                             AuditService auditService) {
        this.deviceCodeRepository = deviceCodeRepository;
        this.deviceTokenRepository = deviceTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    @Transactional
    public DeviceCode requestCode(String clientId, String deviceName) {
        if (clientId == null || clientId.isBlank()) {
            throw new InvalidRequestException("clientId is required");
        }
        DeviceCode code = new DeviceCode();
        code.setDeviceCode(randomToken(32));
        code.setUserCode(randomUserCode());
        code.setClientId(clientId);
        code.setDeviceName(deviceName == null || deviceName.isBlank() ? clientId : deviceName);
        code.setStatus(DeviceCode.Status.PENDING);
        code.setExpiresAt(Instant.now().plus(CODE_TTL));
        DeviceCode saved = deviceCodeRepository.save(code);
        log.info("Device authorization requested by client {} (user code {})", clientId, saved.getUserCode());
        return saved;
    }

    @Transactional(readOnly = true)
    public DeviceCode findByUserCode(String userCode) {
        return deviceCodeRepository.findByUserCode(normalise(userCode))
                .orElseThrow(() -> new NotFoundException("No pending device authorization for that code"));
    }

    /** The human approved it. Fails loud on an expired or already-decided code. */
    @Transactional
    public void authorize(String userCode, Operator operator) {
        DeviceCode code = findByUserCode(userCode);
        assertPending(code);
        code.setStatus(DeviceCode.Status.AUTHORIZED);
        code.setOperator(operator);
        code.setAuthorizedAt(Instant.now());
        deviceCodeRepository.save(code);
        auditService.record("DEVICE_AUTHORIZED", "DeviceCode", code.getId(),
                "client=" + code.getClientId() + " device=" + code.getDeviceName());
    }

    @Transactional
    public void deny(String userCode) {
        DeviceCode code = findByUserCode(userCode);
        assertPending(code);
        code.setStatus(DeviceCode.Status.DENIED);
        deviceCodeRepository.save(code);
        auditService.record("DEVICE_DENIED", "DeviceCode", code.getId(), "client=" + code.getClientId());
    }

    /**
     * The CLI polls here. Returns the plaintext token exactly once, on the first poll after approval;
     * the code is consumed so a replay cannot mint a second token.
     */
    @Transactional
    public PollResult poll(String deviceCode) {
        DeviceCode code = deviceCodeRepository.findByDeviceCode(deviceCode)
                .orElseThrow(() -> new NotFoundException("Unknown device code"));
        if (code.isExpired() && code.getStatus() == DeviceCode.Status.PENDING) {
            code.setStatus(DeviceCode.Status.EXPIRED);
            deviceCodeRepository.save(code);
        }
        return switch (code.getStatus()) {
            case PENDING -> new PollResult("authorization_pending", null, null);
            case DENIED -> new PollResult("access_denied", null, null);
            case EXPIRED -> new PollResult("expired_token", null, null);
            case AUTHORIZED -> {
                String plaintext = randomToken(32);
                DeviceToken token = new DeviceToken();
                token.setOperator(code.getOperator());
                token.setTokenHash(passwordEncoder.encode(plaintext));
                token.setDeviceName(code.getDeviceName());
                token.setClientId(code.getClientId());
                token.setExpiresAt(Instant.now().plus(TOKEN_TTL));
                DeviceToken saved = deviceTokenRepository.save(token);
                // Consume the code: it has done its job, and leaving it AUTHORIZED would let a
                // replayed poll mint tokens indefinitely.
                code.setStatus(DeviceCode.Status.EXPIRED);
                deviceCodeRepository.save(code);
                auditService.record("DEVICE_TOKEN_ISSUED", "DeviceToken", saved.getId(),
                        "operator=" + code.getOperator().getUsername() + " device=" + saved.getDeviceName());
                yield new PollResult(null, plaintext, saved);
            }
        };
    }

    /**
     * Resolve a presented bearer token. Hashes are bcrypt, so this compares against active tokens
     * rather than looking one up — the set is small (one row per authorised device).
     */
    @Transactional
    public Optional<DeviceToken> resolve(String plaintext, String callerIp) {
        if (plaintext == null || plaintext.isBlank()) {
            return Optional.empty();
        }
        for (DeviceToken token : deviceTokenRepository.findByRevokedAtIsNull()) {
            if (token.isActive() && passwordEncoder.matches(plaintext, token.getTokenHash())) {
                token.setLastUsedAt(Instant.now());
                token.setLastUsedIp(callerIp);
                deviceTokenRepository.save(token);
                return Optional.of(token);
            }
        }
        return Optional.empty();
    }

    @Transactional(readOnly = true)
    public List<DeviceToken> listActive() {
        return deviceTokenRepository.findByRevokedAtIsNull();
    }

    @Transactional
    public void revoke(String id, String revokedBy) {
        DeviceToken token = deviceTokenRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Device token not found: " + id));
        token.setRevokedAt(Instant.now());
        token.setRevokedBy(revokedBy);
        deviceTokenRepository.save(token);
        auditService.record("DEVICE_TOKEN_REVOKED", "DeviceToken", id, "device=" + token.getDeviceName());
    }

    private void assertPending(DeviceCode code) {
        if (code.isExpired()) {
            code.setStatus(DeviceCode.Status.EXPIRED);
            deviceCodeRepository.save(code);
            throw new InvalidRequestException("That code has expired — ask the device for a new one");
        }
        if (code.getStatus() != DeviceCode.Status.PENDING) {
            throw new InvalidRequestException("That code was already " + code.getStatus().name().toLowerCase());
        }
    }

    private static String normalise(String userCode) {
        return userCode == null ? "" : userCode.trim().toUpperCase().replace(" ", "");
    }

    private static String randomToken(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String randomUserCode() {
        StringBuilder sb = new StringBuilder(9);
        for (int i = 0; i < 8; i++) {
            if (i == 4) {
                sb.append('-');
            }
            sb.append(USER_CODE_ALPHABET.charAt(RANDOM.nextInt(USER_CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    /** Either an RFC 8628 error code, or a freshly minted token whose plaintext is returned once. */
    public record PollResult(String error, String plaintextToken, DeviceToken token) {
        public boolean issued() {
            return plaintextToken != null;
        }
    }
}
