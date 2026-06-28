package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.dto.EscrowAccountRequest;
import com.artivisi.paymentgateway.dto.EscrowUpdateRequest;
import com.artivisi.paymentgateway.entity.EscrowAccount;
import com.artivisi.paymentgateway.exception.DuplicateException;
import com.artivisi.paymentgateway.exception.NotFoundException;
import com.artivisi.paymentgateway.repository.EscrowAccountRepository;
import com.artivisi.paymentgateway.repository.VirtualAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class EscrowAccountService {

    private final EscrowAccountRepository repository;
    private final VirtualAccountRepository virtualAccountRepository;
    private final AuditService auditService;

    public EscrowAccountService(EscrowAccountRepository repository,
                                VirtualAccountRepository virtualAccountRepository, AuditService auditService) {
        this.repository = repository;
        this.virtualAccountRepository = virtualAccountRepository;
        this.auditService = auditService;
    }

    @Transactional
    public EscrowAccount create(EscrowAccountRequest request) {
        if (repository.existsByCode(request.code())) {
            throw new DuplicateException("Escrow account code already exists: " + request.code());
        }
        EscrowAccount e = new EscrowAccount();
        e.setCode(request.code());
        e.setProvider(request.provider());
        e.setHostingModel(request.hostingModel());
        e.setTransport(request.transport());
        e.setAuthScheme(request.authScheme());
        e.setActiveEnvironment(request.activeEnvironment());
        e.setClientId(request.clientId());
        e.setClientSecret(request.clientSecret());
        e.setPartnerId(request.partnerId());
        e.setChannelId(request.channelId());
        e.setPrivateKey(request.privateKey());
        e.setPublicKey(request.publicKey());
        e.setSandboxBaseUrl(request.sandboxBaseUrl());
        e.setProductionBaseUrl(request.productionBaseUrl());
        e.setSettlementAccountNumber(request.settlementAccountNumber());
        e.setSettlementAccountName(request.settlementAccountName());
        e.setCompanyId(request.companyId());
        e.setVaPrefix(request.vaPrefix());
        e.setVaDigitLength(request.vaDigitLength());
        e.setMerchantTag(request.merchantTag());
        e.setInstitutionTag(request.institutionTag());
        e.setEnabled(true);
        EscrowAccount saved = repository.save(e);
        auditService.record("ESCROW_CREATED", "EscrowAccount", saved.getId(), "code=" + saved.getCode());
        return saved;
    }

    /**
     * Update operational fields + optionally rotate secrets. Structural fields (provider, transport,
     * auth scheme, hosting model, number space) are frozen once the escrow has VAs (fail loud).
     */
    @Transactional
    public EscrowAccount update(String id, EscrowUpdateRequest request) {
        EscrowAccount e = get(id);
        boolean hasVas = virtualAccountRepository.countByEscrowAccountId(id) > 0;

        e.setActiveEnvironment(request.activeEnvironment());
        e.setClientId(request.clientId());
        e.setPartnerId(request.partnerId());
        e.setChannelId(request.channelId());
        e.setPublicKey(request.publicKey());
        e.setSandboxBaseUrl(request.sandboxBaseUrl());
        e.setProductionBaseUrl(request.productionBaseUrl());
        e.setSettlementAccountNumber(request.settlementAccountNumber());
        e.setSettlementAccountName(request.settlementAccountName());
        e.setMerchantTag(request.merchantTag());
        e.setInstitutionTag(request.institutionTag());

        // Secrets rotate only when a new value is supplied; blank keeps the existing one.
        if (request.clientSecret() != null && !request.clientSecret().isBlank()) {
            e.setClientSecret(request.clientSecret());
        }
        if (request.privateKey() != null && !request.privateKey().isBlank()) {
            e.setPrivateKey(request.privateKey());
        }

        if (hasVas) {
            if (structuralChanged(e, request)) {
                throw new IllegalArgumentException(
                        "Structural fields (provider, transport, auth, hosting, number space) are frozen: escrow has VAs.");
            }
        } else {
            e.setProvider(request.provider());
            e.setHostingModel(request.hostingModel());
            e.setTransport(request.transport());
            e.setAuthScheme(request.authScheme());
            e.setCompanyId(request.companyId());
            e.setVaPrefix(request.vaPrefix());
            e.setVaDigitLength(request.vaDigitLength());
        }

        EscrowAccount saved = repository.save(e);
        auditService.record("ESCROW_UPDATED", "EscrowAccount", id, "code=" + e.getCode());
        return saved;
    }

    @Transactional
    public void setEnabled(String id, boolean enabled) {
        EscrowAccount e = get(id);
        e.setEnabled(enabled);
        repository.save(e);
        auditService.record(enabled ? "ESCROW_ENABLED" : "ESCROW_DISABLED", "EscrowAccount", id, "code=" + e.getCode());
    }

    private static boolean structuralChanged(EscrowAccount e, EscrowUpdateRequest r) {
        return !e.getProvider().equals(r.provider())
                || e.getHostingModel() != r.hostingModel()
                || e.getTransport() != r.transport()
                || e.getAuthScheme() != r.authScheme()
                || !e.getCompanyId().equals(r.companyId())
                || !e.getVaPrefix().equals(r.vaPrefix())
                || !e.getVaDigitLength().equals(r.vaDigitLength());
    }

    @Transactional(readOnly = true)
    public EscrowAccount get(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Escrow account not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<EscrowAccount> list() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public boolean hasVirtualAccounts(String id) {
        return virtualAccountRepository.countByEscrowAccountId(id) > 0;
    }
}
