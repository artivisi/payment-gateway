package com.artivisi.paymentgateway.config;

import com.artivisi.paymentgateway.entity.DeviceToken;
import com.artivisi.paymentgateway.service.DeviceAuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authenticates {@code Authorization: Bearer <token>} against device tokens (RFC 8628 flow).
 *
 * <p>Authorities come from the owning operator's role **at request time**, not from anything stored
 * on the token — so revoking a permission or disabling the operator takes effect on the next call
 * rather than at the next token issue. A token can therefore never outrank its owner.
 */
@Component
public class DeviceTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final DeviceAuthService deviceAuthService;

    public DeviceTokenAuthenticationFilter(DeviceAuthService deviceAuthService) {
        this.deviceAuthService = deviceAuthService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        // The device-flow endpoints are how a caller GETS a token; requiring one would be circular.
        return !uri.startsWith("/api/") || uri.startsWith("/api/device/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(PREFIX)
                || SecurityContextHolder.getContext().getAuthentication() != null) {
            chain.doFilter(request, response);
            return;
        }
        deviceAuthService.resolve(header.substring(PREFIX.length()).trim(), request.getRemoteAddr())
                .filter(token -> token.getOperator().isEnabled())
                .ifPresent(token -> SecurityContextHolder.getContext().setAuthentication(authenticationFor(token)));
        chain.doFilter(request, response);
    }

    private static UsernamePasswordAuthenticationToken authenticationFor(DeviceToken token) {
        List<SimpleGrantedAuthority> authorities = token.getOperator().getRole().effectivePermissions()
                .stream().map(p -> new SimpleGrantedAuthority(p.name())).toList();
        return new UsernamePasswordAuthenticationToken(
                token.getOperator().getUsername(), null, authorities);
    }
}
