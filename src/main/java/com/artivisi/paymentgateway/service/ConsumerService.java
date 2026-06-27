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
    private final AuditService auditService;

    public ConsumerService(ConsumerRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
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
        c.setWebhookSuspended(false);
        Consumer saved = repository.save(c);
        auditService.record("CONSUMER_CREATED", "Consumer", saved.getId(), "code=" + saved.getCode());
        return saved;
    }

    /** Ops kill-switch: pause/resume webhook delivery to a consumer without touching its auth status. */
    @Transactional
    public Consumer setWebhookSuspended(String id, boolean suspended) {
        Consumer c = get(id);
        c.setWebhookSuspended(suspended);
        Consumer saved = repository.save(c);
        auditService.record(suspended ? "CONSUMER_WEBHOOK_SUSPENDED" : "CONSUMER_WEBHOOK_RESUMED",
                "Consumer", saved.getId(), "code=" + saved.getCode());
        return saved;
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
