package com.artivisi.paymentgateway.config;

import com.artivisi.paymentgateway.adapter.spi.BankAdapter;
import com.artivisi.paymentgateway.adapter.spi.BankAdapterRegistry;
import com.artivisi.paymentgateway.entity.BankIpRule;
import com.artivisi.paymentgateway.repository.BankIpRuleRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * App-layer IP allowlist for bank-callback endpoints, per provider. Replaces network-layer IP
 * filtering with rules ops manage in the admin UI. A request whose source IP is not in the provider's
 * enabled rules gets 403. No enabled rule for a provider = unrestricted (matches prior behaviour).
 *
 * <p>Source IP is {@code request.getRemoteAddr()} — accurate only if the app is directly exposed or a
 * trusted proxy forwards the client IP (see {@code server.forward-headers-strategy}).
 */
public class BankIpAllowlistFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BankIpAllowlistFilter.class);

    private final BankIpRuleRepository repository;
    private final BankAdapterRegistry adapters;

    public BankIpAllowlistFilter(BankIpRuleRepository repository, BankAdapterRegistry adapters) {
        this.repository = repository;
        this.adapters = adapters;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provider = resolveProvider(request.getRequestURI());
        if (provider != null) {
            List<BankIpRule> rules = repository.findByProviderAndEnabledTrue(provider);
            if (!rules.isEmpty()) {
                String ip = request.getRemoteAddr();
                boolean allowed = rules.stream().anyMatch(rule -> matches(rule.getCidr(), ip));
                if (!allowed) {
                    log.warn("Bank callback rejected: source IP not allowlisted. provider={} ip={} path={}",
                            provider, ip, request.getRequestURI());
                    // setStatus (not sendError): avoid an ERROR re-dispatch through the security chain.
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("text/plain;charset=UTF-8");
                    response.getWriter().write("Forbidden");
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * Which bank's callback this is, asked of the adapters themselves.
     *
     * <p>The paths used to be listed here, which quietly tied the engine to the set of banks one
     * deployment happened to have: an adapter added in a private assembly got no allowlist protection
     * at all, because this method did not know its path and returned null — and null means
     * unrestricted, so the failure was an open endpoint rather than a broken one.
     */
    private String resolveProvider(String uri) {
        return adapters.adapters().values().stream()
                .filter(a -> uri.startsWith(a.inboundPath()))
                .map(BankAdapter::provider)
                .findFirst()
                .orElse(null);
    }

    private static boolean matches(String cidr, String ip) {
        try {
            return new IpAddressMatcher(cidr).matches(ip);
        } catch (IllegalArgumentException e) {
            // A malformed CIDR slipped past validation: never silently allow.
            log.error("Invalid CIDR in bank_ip_rule: {}", cidr);
            return false;
        }
    }
}
