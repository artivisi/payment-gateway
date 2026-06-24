package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.dto.ConsumerRequest;
import com.artivisi.paymentgateway.entity.Consumer;
import com.artivisi.paymentgateway.exception.DuplicateException;
import com.artivisi.paymentgateway.exception.NotFoundException;
import com.artivisi.paymentgateway.repository.ConsumerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ConsumerService {

    private final ConsumerRepository repository;

    public ConsumerService(ConsumerRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Consumer create(ConsumerRequest request) {
        if (repository.existsByCode(request.code())) {
            throw new DuplicateException("Consumer code already exists: " + request.code());
        }
        if (repository.existsByClientId(request.clientId())) {
            throw new DuplicateException("Consumer client id already exists: " + request.clientId());
        }
        Consumer c = new Consumer();
        c.setCode(request.code());
        c.setName(request.name());
        c.setClientId(request.clientId());
        c.setClientSecret(request.clientSecret());
        c.setWebhookUrl(request.webhookUrl());
        c.setStatus(request.status());
        return repository.save(c);
    }

    @Transactional(readOnly = true)
    public Consumer get(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Consumer not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<Consumer> list() {
        return repository.findAll();
    }
}
