package com.artivisi.paymentgateway.config;

import com.artivisi.paymentgateway.repository.BankIpRuleRepository;
import com.artivisi.paymentgateway.repository.OperatorRepository;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Admin-UI authentication + RBAC (PCI Req 7/8). {@code /admin} requires an authenticated operator and
 * the appropriate role; bank-callback ({@code /api/**}, {@code /ws/**}), Consumer API, and health stay
 * open here (they carry their own signature/key auth, and bank IP allowlisting is a separate filter).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, OperatorRepository operatorRepository,
                                                   DeviceTokenAuthenticationFilter deviceTokenFilter)
            throws Exception {
        AdminAccessGuardFilter guard = new AdminAccessGuardFilter(operatorRepository);
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/css/**", "/js/**", "/img/**", "/favicon.ico").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // Device flow: how a CLI OBTAINS a token, so it cannot itself require one.
                        .requestMatchers("/api/device/**").permitAll()
                        // Operator-facing API, authenticated by a device token (RFC 8628) carrying the
                        // owning operator's permissions.
                        .requestMatchers("/api/analysis-reports/**").hasAuthority("ANALYSIS_VIEW")
                        // Bank callbacks + Consumer API authenticate themselves (signature / client key / IP).
                        .requestMatchers("/api/**", "/ws/**").permitAll()
                        // Post-login step-up pages: any authenticated operator (incl. pre-MFA).
                        .requestMatchers("/change-password", "/mfa", "/mfa/enroll").authenticated()
                        // Permission-based RBAC (feature -> permission). View vs manage split where it matters.
                        .requestMatchers("/admin/operators/**").hasAuthority("OPERATOR_MANAGE")
                        .requestMatchers("/admin/roles/**").hasAuthority("ROLE_MANAGE")
                        .requestMatchers("/admin/bank-ip-rules/**").hasAuthority("BANK_IP_MANAGE")
                        .requestMatchers(HttpMethod.POST, "/admin/escrow-accounts/**").hasAuthority("ESCROW_MANAGE")
                        .requestMatchers("/admin/escrow-accounts/**").hasAuthority("ESCROW_VIEW")
                        .requestMatchers(HttpMethod.POST, "/admin/consumers/*/webhook/**").hasAuthority("WEBHOOK_MANAGE")
                        .requestMatchers(HttpMethod.POST, "/admin/consumers/**").hasAuthority("CONSUMER_MANAGE")
                        .requestMatchers("/admin/consumers/**").hasAuthority("CONSUMER_VIEW")
                        .requestMatchers(HttpMethod.POST, "/admin/webhooks/**").hasAuthority("WEBHOOK_MANAGE")
                        .requestMatchers("/admin/webhooks/**").hasAuthority("WEBHOOK_VIEW")
                        .requestMatchers("/admin/charges/**").hasAuthority("CHARGE_VIEW")
                        .requestMatchers("/admin/payments/**").hasAuthority("PAYMENT_VIEW")
                        .requestMatchers("/admin/reconciliations/**").hasAuthority("RECONCILIATION_VIEW")
                        .requestMatchers("/admin/audit/**").hasAuthority("AUDIT_VIEW")
                        .requestMatchers("/admin/analysis/**").hasAuthority("ANALYSIS_VIEW")
                        // Approving a device is a normal operator action; the token it mints inherits
                        // that operator's permissions and no more.
                        .requestMatchers("/device", "/device/**").authenticated()
                        // Dashboard + any other admin path: just an authenticated operator.
                        .requestMatchers("/admin", "/admin/").authenticated()
                        .requestMatchers("/admin/**").authenticated()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .defaultSuccessUrl("/admin", true)
                        .failureUrl("/login?error")
                        .permitAll())
                .logout(logout -> logout
                        .logoutSuccessUrl("/login?logout")
                        .permitAll())
                // Bank callbacks / Consumer API are not browser forms and carry no CSRF token.
                // An API caller with no/blank credentials must get 401, not a 302 to the HTML login
                // page — a CLI cannot follow that, and a redirect reads as "endpoint moved".
                // The second mapping is NOT redundant: with only the /api/ mapping registered,
                // DelegatingAuthenticationEntryPoint falls back to the FIRST registered entry point
                // for everything else, which sent browsers a bare 401 instead of the login page.
                .exceptionHandling(ex -> ex
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                request -> request.getRequestURI().startsWith("/api/"))
                        .defaultAuthenticationEntryPointFor(
                                new LoginUrlAuthenticationEntryPoint("/login"),
                                AnyRequestMatcher.INSTANCE))
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**", "/ws/**"))
                // Bearer tokens are stateless: resolve one before the session filter so an API call
                // never depends on a session, and a browser session never grants API access.
                .addFilterBefore(deviceTokenFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(guard, AuthorizationFilter.class);
        return http.build();
    }

    /** Registers the per-provider bank IP allowlist on the bank-callback paths only. */
    @Bean
    public FilterRegistrationBean<BankIpAllowlistFilter> bankIpAllowlistFilter(BankIpRuleRepository repository) {
        FilterRegistrationBean<BankIpAllowlistFilter> registration =
                new FilterRegistrationBean<>(new BankIpAllowlistFilter(repository));
        registration.addUrlPatterns("/api/bank/*", "/ws/cimb/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
