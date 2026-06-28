package com.artivisi.paymentgateway.config;

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

    public BankIpAllowlistFilter(BankIpRuleRepository repository) {
        this.repository = repository;
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

    private static String resolveProvider(String uri) {
        if (uri.startsWith("/api/bank/bsi")) {
            return "bsi";
        }
        if (uri.startsWith("/api/bank/maybank")) {
            return "maybank";
        }
        if (uri.startsWith("/ws/cimb")) {
            return "cimb";
        }
        return null;
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
