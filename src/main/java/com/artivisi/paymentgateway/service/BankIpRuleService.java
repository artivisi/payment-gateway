package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.adapter.spi.BankAdapterRegistry;
import com.artivisi.paymentgateway.entity.BankIpRule;
import com.artivisi.paymentgateway.exception.NotFoundException;
import com.artivisi.paymentgateway.repository.BankIpRuleRepository;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Manage the per-provider bank IP allowlist (admin UI). Validates CIDR and provider; audits changes. */
@Service
public class BankIpRuleService {

    private final BankIpRuleRepository repository;
    private final AuditService auditService;
    private final BankAdapterRegistry adapters;

    public BankIpRuleService(BankIpRuleRepository repository, AuditService auditService,
                             BankAdapterRegistry adapters) {
        this.repository = repository;
        this.auditService = auditService;
        this.adapters = adapters;
    }

    @Transactional(readOnly = true)
    public List<BankIpRule> list() {
        return repository.findAllByOrderByProviderAscCreatedAtAsc();
    }

    @Transactional
    public BankIpRule create(String provider, String cidr, String label) {
        // What this build can actually serve, not a list written down here. The names used to be
        // hardcoded, which meant an operator could allowlist an IP for a bank this deployment has no
        // adapter for and believe the callback was protected, while a real adapter added later was
        // rejected until someone edited this class.
        if (adapters.forProvider(provider).isEmpty()) {
            throw new IllegalArgumentException("Unknown provider: " + provider
                    + ". This build serves " + adapters.adapters().keySet());
        }
        validateCidr(cidr);
        BankIpRule rule = new BankIpRule();
        rule.setProvider(provider);
        rule.setCidr(cidr);
        rule.setLabel(label);
        rule.setEnabled(true);
        BankIpRule saved = repository.save(rule);
        auditService.record("BANK_IP_RULE_ADDED", "BankIpRule", saved.getId(), provider + " " + cidr);
        return saved;
    }

    @Transactional
    public void setEnabled(String id, boolean enabled) {
        BankIpRule rule = get(id);
        rule.setEnabled(enabled);
        repository.save(rule);
        auditService.record(enabled ? "BANK_IP_RULE_ENABLED" : "BANK_IP_RULE_DISABLED", "BankIpRule", id,
                rule.getProvider() + " " + rule.getCidr());
    }

    @Transactional
    public void delete(String id) {
        BankIpRule rule = get(id);
        repository.delete(rule);
        auditService.record("BANK_IP_RULE_DELETED", "BankIpRule", id, rule.getProvider() + " " + rule.getCidr());
    }

    private BankIpRule get(String id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Bank IP rule not found: " + id));
    }

    private void validateCidr(String cidr) {
        try {
            new IpAddressMatcher(cidr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid CIDR/IP: " + cidr);
        }
    }
}
