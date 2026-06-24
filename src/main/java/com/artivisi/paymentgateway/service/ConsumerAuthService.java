package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.entity.Consumer;
import com.artivisi.paymentgateway.entity.ConsumerStatus;
import com.artivisi.paymentgateway.exception.UnauthorizedException;
import com.artivisi.paymentgateway.repository.ConsumerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
public class ConsumerAuthService {

    private final ConsumerRepository repository;

    public ConsumerAuthService(ConsumerRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Consumer authenticate(String clientId, String clientSecret) {
        if (clientId == null || clientSecret == null) {
            throw new UnauthorizedException("missing client credentials");
        }
        Consumer consumer = repository.findByClientId(clientId)
                .orElseThrow(() -> new UnauthorizedException("invalid client credentials"));
        if (consumer.getStatus() != ConsumerStatus.ACTIVE) {
            throw new UnauthorizedException("consumer is not active");
        }
        String stored = consumer.getClientSecret();
        boolean matches = stored != null && MessageDigest.isEqual(
                stored.getBytes(StandardCharsets.UTF_8), clientSecret.getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            throw new UnauthorizedException("invalid client credentials");
        }
        return consumer;
    }
}
