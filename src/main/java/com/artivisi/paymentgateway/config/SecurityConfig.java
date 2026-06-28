package com.artivisi.paymentgateway.config;

import com.artivisi.paymentgateway.repository.BankIpRuleRepository;
import com.artivisi.paymentgateway.repository.OperatorRepository;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
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
    public SecurityFilterChain securityFilterChain(HttpSecurity http, OperatorRepository operatorRepository)
            throws Exception {
        AdminAccessGuardFilter guard = new AdminAccessGuardFilter(operatorRepository);
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/css/**", "/js/**", "/img/**", "/favicon.ico").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
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
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**", "/ws/**"))
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
