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
        e.setSettlementFee(request.settlementFee());
        e.setCompanyId(request.companyId());
        e.setVaPrefix(request.vaPrefix());
        e.setVaDigitLength(request.vaDigitLength());
        e.setAllocationStrategy(request.allocationStrategy());
        e.setSlotDigits(request.slotDigits());
        e.setPayerPadding(request.payerPadding());
        e.setInterbankPrefix(request.interbankPrefix());
        requireUsableLayout(e);
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
        e.setSettlementFee(request.settlementFee());
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

        // The number layout is frozen once numbers have been issued under it, and only then.
        //
        // Freezing exists to stop a segment width or a padding rule changing whose number each
        // future VA belongs to. It must not stop an operator *recording* the layout an escrow has
        // been issuing under all along: escrows that predate gateway-side allocation have VAs and an
        // incomplete layout, and those are precisely the ones that need filling in. Locking them
        // would leave the deployment with escrows it can never enable.
        if (!hasVas || !layoutComplete(e)) {
            e.setAllocationStrategy(request.allocationStrategy());
            e.setSlotDigits(request.slotDigits());
            e.setPayerPadding(request.payerPadding());
            e.setInterbankPrefix(request.interbankPrefix());
            if (layoutComplete(e)) {
                requireUsableLayout(e);
            }
        } else if (layoutChanged(e, request)) {
            throw new IllegalArgumentException(
                    "Number layout is frozen: escrow has VAs allocated under it.");
        }

        EscrowAccount saved = repository.save(e);
        auditService.record("ESCROW_UPDATED", "EscrowAccount", id, "code=" + e.getCode());
        return saved;
    }

    @Transactional
    public void setEnabled(String id, boolean enabled) {
        EscrowAccount e = get(id);
        if (enabled) {
            // An enabled escrow is one the gateway will open VAs through, so this is the last point
            // at which an incomplete layout can be refused before a consumer's charge hits it.
            requireCompleteLayout(e);
        }
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

    private static boolean layoutChanged(EscrowAccount e, EscrowUpdateRequest r) {
        return e.getAllocationStrategy() != r.allocationStrategy()
                || !java.util.Objects.equals(e.getSlotDigits(), r.slotDigits())
                || e.getPayerPadding() != r.payerPadding()
                || !java.util.Objects.equals(e.getInterbankPrefix(), r.interbankPrefix());
    }

    /** Whether every field the allocator needs is set. Incomplete layouts stay editable. */
    public static boolean layoutComplete(EscrowAccount e) {
        return e.getVaPrefix() != null && !e.getVaPrefix().isBlank()
                && e.getVaDigitLength() != null
                && e.getAllocationStrategy() != null
                && e.getSlotDigits() != null
                && e.getPayerPadding() != null
                && e.getInterbankPrefix() != null;
    }

    /**
     * Refuse a number layout that leaves no room for a payer key.
     *
     * <p>Caught here rather than at the first charge because the arithmetic is knowable the moment
     * the escrow is saved, and an escrow that cannot allocate is not a configuration someone meant
     * to save. The allocator checks it again: an escrow may also arrive from a migration or a
     * direct database edit, and it must refuse there too rather than compose a number from a
     * negative segment width.
     */
    /**
     * Every layout field an escrow needs before it can collect, named one at a time.
     *
     * <p>Escrows that predate gateway-side allocation carry only part of the layout: the migration
     * sets the strategy, which is the one value it can state as fact, and leaves the segment widths
     * and the padding rule null because nothing in the database implies them. Enabling such an
     * escrow is refused here, naming the field, rather than at the first payment.
     */
    @Transactional(readOnly = true)
    public boolean hasFrozenLayout(String id) {
        EscrowAccount e = get(id);
        return virtualAccountRepository.countByEscrowAccountId(id) > 0 && layoutComplete(e);
    }

    private static void requireCompleteLayout(EscrowAccount e) {
        requireSet(e, "vaPrefix", e.getVaPrefix() == null || e.getVaPrefix().isBlank() ? null : e.getVaPrefix());
        requireSet(e, "vaDigitLength", e.getVaDigitLength());
        requireSet(e, "allocationStrategy", e.getAllocationStrategy());
        requireSet(e, "slotDigits", e.getSlotDigits());
        requireSet(e, "payerPadding", e.getPayerPadding());
        requireSet(e, "interbankPrefix", e.getInterbankPrefix());
        requireUsableLayout(e);
    }

    private static void requireSet(EscrowAccount e, String field, Object value) {
        if (value == null) {
            throw new IllegalArgumentException("Escrow " + e.getCode() + " cannot be enabled: "
                    + field + " is not configured, and a VA number layout cannot be guessed");
        }
    }

    private static void requireUsableLayout(EscrowAccount e) {
        int taken = e.getVaPrefix().length() + e.getSlotDigits();
        if (taken >= e.getVaDigitLength()) {
            throw new IllegalArgumentException("Number layout leaves no room for a payer key: prefix "
                    + e.getVaPrefix().length() + " + slot " + e.getSlotDigits() + " digits fill all "
                    + e.getVaDigitLength() + " of escrow " + e.getCode());
        }
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
