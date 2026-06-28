package com.artivisi.paymentgateway.config;

import com.artivisi.paymentgateway.entity.Operator;
import com.artivisi.paymentgateway.repository.OperatorRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Loads {@link Operator}s for Spring Security: role authority, enabled and lock state. */
@Service
public class OperatorDetailsService implements UserDetailsService {

    private final OperatorRepository repository;

    public OperatorDetailsService(OperatorRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        Operator operator = repository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown operator"));
        return User.withUsername(operator.getUsername())
                .password(operator.getPasswordHash())
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_" + operator.getRole().name())))
                .disabled(!operator.isEnabled())
                .accountLocked(operator.isLocked())
                .build();
    }
}
