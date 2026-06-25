package com.artivisi.paymentgateway.adapter.maybank;

import com.artivisi.paymentgateway.adapter.snap.SnapSignatureHelper;
import com.artivisi.paymentgateway.entity.EscrowAccount;
import com.artivisi.paymentgateway.entity.SnapExternalId;
import com.artivisi.paymentgateway.exception.ConflictException;
import com.artivisi.paymentgateway.exception.UnauthorizedException;
import com.artivisi.paymentgateway.repository.SnapExternalIdRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Validates an inbound SNAP transaction: resolves the escrow from the bearer token, verifies the
 * HMAC-SHA512 signature over the raw body, and enforces daily X-EXTERNAL-ID idempotency.
 */
@Service
public class SnapRequestValidator {

    private final SnapTokenService tokenService;
    private final SnapExternalIdRepository externalIdRepository;

    public SnapRequestValidator(SnapTokenService tokenService, SnapExternalIdRepository externalIdRepository) {
        this.tokenService = tokenService;
        this.externalIdRepository = externalIdRepository;
    }

    @Transactional
    public EscrowAccount authorize(String authorizationHeader, String timestamp, String signature,
                                   String externalId, String method, String path, String body, String serviceName) {
        String accessToken = stripBearer(authorizationHeader);
        EscrowAccount escrow = tokenService.resolveEscrow(accessToken)
                .orElseThrow(() -> new UnauthorizedException("invalid or expired access token"));

        boolean valid = SnapSignatureHelper.verifyTransaction(
                escrow.getClientSecret(), method, path, accessToken, body, timestamp, signature);
        if (!valid) {
            throw new UnauthorizedException("invalid signature");
        }

        LocalDate today = LocalDate.now();
        if (externalIdRepository.existsByEscrowAccountIdAndTransactionDateAndServiceNameAndExternalId(
                escrow.getId(), today, serviceName, externalId)) {
            throw new ConflictException("duplicate X-EXTERNAL-ID");
        }
        SnapExternalId record = new SnapExternalId();
        record.setEscrowAccount(escrow);
        record.setExternalId(externalId);
        record.setServiceName(serviceName);
        record.setTransactionDate(today);
        externalIdRepository.save(record);

        return escrow;
    }

    private static String stripBearer(String authorizationHeader) {
        if (authorizationHeader == null) {
            return null;
        }
        String prefix = "Bearer ";
        return authorizationHeader.startsWith(prefix)
                ? authorizationHeader.substring(prefix.length()) : authorizationHeader;
    }
}
