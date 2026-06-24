package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.entity.EscrowAccount;
import com.artivisi.paymentgateway.exception.NotFoundException;
import com.artivisi.paymentgateway.repository.EscrowAccountRepository;
import com.artivisi.paymentgateway.repository.VirtualAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Resolves which escrow an inbound bank message targets, from the provider and the VA number.
 * A bank calls one fixed endpoint per provider; the escrow is identified by its number space
 * (prefix + digit length). Disambiguates by a registered VA if multiple escrows share a space.
 */
@Service
public class EscrowResolver {

    private final EscrowAccountRepository escrowAccountRepository;
    private final VirtualAccountRepository virtualAccountRepository;

    public EscrowResolver(EscrowAccountRepository escrowAccountRepository,
                          VirtualAccountRepository virtualAccountRepository) {
        this.escrowAccountRepository = escrowAccountRepository;
        this.virtualAccountRepository = virtualAccountRepository;
    }

    @Transactional(readOnly = true)
    public EscrowAccount resolveForVaNumber(String provider, String vaNumber) {
        if (vaNumber == null || vaNumber.isBlank()) {
            throw new NotFoundException("vaNumber is required to resolve escrow");
        }
        List<EscrowAccount> candidates = escrowAccountRepository.findByProvider(provider).stream()
                .filter(e -> matchesNumberSpace(e, vaNumber))
                .toList();
        if (candidates.isEmpty()) {
            throw new NotFoundException("no " + provider + " escrow matches VA number " + vaNumber);
        }
        if (candidates.size() == 1) {
            return candidates.getFirst();
        }
        return candidates.stream()
                .filter(e -> virtualAccountRepository
                        .findByEscrowAccountIdAndVaNumber(e.getId(), vaNumber).isPresent())
                .findFirst()
                .orElseThrow(() -> new NotFoundException(
                        "ambiguous " + provider + " escrow for VA number " + vaNumber));
    }

    private boolean matchesNumberSpace(EscrowAccount escrow, String vaNumber) {
        Integer length = escrow.getVaDigitLength();
        if (length != null && vaNumber.length() != length) {
            return false;
        }
        String prefix = escrow.getVaPrefix();
        return prefix == null || prefix.isBlank() || vaNumber.startsWith(prefix);
    }
}
