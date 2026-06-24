package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.dto.EscrowAccountRequest;
import com.artivisi.paymentgateway.entity.EscrowAccount;
import com.artivisi.paymentgateway.exception.DuplicateException;
import com.artivisi.paymentgateway.exception.NotFoundException;
import com.artivisi.paymentgateway.repository.EscrowAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class EscrowAccountService {

    private final EscrowAccountRepository repository;

    public EscrowAccountService(EscrowAccountRepository repository) {
        this.repository = repository;
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
        return repository.save(e);
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
}
