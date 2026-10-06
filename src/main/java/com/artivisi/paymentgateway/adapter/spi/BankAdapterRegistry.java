package com.artivisi.paymentgateway.adapter.spi;

import com.artivisi.paymentgateway.repository.EscrowAccountRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Every {@link BankAdapter} on the classpath, and the startup check that the deployment has one for
 * each escrow it is configured to collect through.
 *
 * <p><b>This one refuses to start.</b> An escrow whose adapter is missing does not fail visibly: the
 * bank posts its payment notification to a path nothing serves, gets a 404, and the payer's money
 * sits settled at the bank with no charge marked paid. That is money lost quietly, and quietly is the
 * only way this product loses money. Refusing to boot turns it into a failed deploy instead.
 *
 * <p>Deliberately different from {@code ContractListenerStarter} in the receivables application,
 * which boots without its broker on purpose. The distinction is whether the previous build was any
 * better: a broker outage breaks the old jar and the new one alike, so failing there leaves a
 * deployment with nowhere to roll back to, while a missing adapter is a property of the build being
 * deployed — the previous one had it, so the automatic rollback is a real fix.
 */
@Component
public class BankAdapterRegistry {

    private static final Logger log = LoggerFactory.getLogger(BankAdapterRegistry.class);

    private final Map<String, BankAdapter> byProvider;
    private final EscrowAccountRepository escrowAccounts;

    public BankAdapterRegistry(List<BankAdapter> adapters, EscrowAccountRepository escrowAccounts) {
        this.escrowAccounts = escrowAccounts;
        this.byProvider = adapters.stream().collect(Collectors.toMap(
                BankAdapter::provider, Function.identity(),
                (first, second) -> {
                    throw new IllegalStateException("Two adapters claim provider '" + first.provider()
                            + "': " + first.getClass().getName() + " and " + second.getClass().getName()
                            + ". One escrow's traffic cannot be served by both.");
                },
                TreeMap::new));
    }

    public Optional<BankAdapter> forProvider(String provider) {
        return Optional.ofNullable(byProvider.get(provider));
    }

    /** What this build can serve — for the admin UI and for reading a deployment's capability off a log. */
    public Map<String, BankAdapter> adapters() {
        return Map.copyOf(byProvider);
    }

    @PostConstruct
    void everyConfiguredEscrowHasAnAdapter() {
        // Enabled escrows only. A disabled one collects nothing, so a missing adapter costs nothing
        // either, and refusing to start over a bank the operator has already retired would make
        // decommissioning a deployment harder than commissioning it.
        List<String> orphans = escrowAccounts.findEnabled().stream()
                .filter(e -> !byProvider.containsKey(e.getProvider()))
                .map(e -> e.getCode() + " (provider '" + e.getProvider() + "')")
                .sorted()
                .toList();
        if (!orphans.isEmpty()) {
            throw new IllegalStateException("No bank adapter is present for " + orphans
                    + ". This build cannot collect for those escrow accounts: the bank's callbacks"
                    + " would be answered with 404 and its payments would never be recorded."
                    + " Adapters present: " + byProvider.keySet());
        }
        log.info("Bank adapters present: {}", byProvider.values().stream()
                .map(a -> a.provider() + " (" + a.hostingModel() + " at " + a.inboundPath() + ")")
                .toList());
    }
}
