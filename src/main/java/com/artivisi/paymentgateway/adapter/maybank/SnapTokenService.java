package com.artivisi.paymentgateway.adapter.maybank;

import com.artivisi.paymentgateway.entity.EscrowAccount;
import com.artivisi.paymentgateway.entity.SnapAccessToken;
import com.artivisi.paymentgateway.repository.SnapAccessTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Issues and resolves SNAP bearer access tokens (900s TTL per the standard). */
@Service
public class SnapTokenService {

    private static final long TTL_SECONDS = 900;

    private final SnapAccessTokenRepository repository;

    public SnapTokenService(SnapAccessTokenRepository repository) {
        this.repository = repository;
    }

    public long ttlSeconds() {
        return TTL_SECONDS;
    }

    @Transactional
    public SnapAccessToken issue(EscrowAccount escrow) {
        SnapAccessToken token = new SnapAccessToken();
        token.setEscrowAccount(escrow);
        token.setAccessToken(UUID.randomUUID().toString());
        token.setExpiresAt(Instant.now().plusSeconds(TTL_SECONDS));
        return repository.save(token);
    }

    @Transactional(readOnly = true)
    public Optional<EscrowAccount> resolveEscrow(String accessToken) {
        if (accessToken == null) {
            return Optional.empty();
        }
        return repository.findByAccessToken(accessToken)
                .filter(token -> token.getExpiresAt().isAfter(Instant.now()))
                .map(SnapAccessToken::getEscrowAccount);
    }
}
