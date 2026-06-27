package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.entity.AuditEvent;
import com.artivisi.paymentgateway.repository.AuditEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records significant domain actions to the audit log. Joins the caller's transaction, so an event
 * is persisted only if the action it records commits. Detail must never contain secrets or signatures.
 */
@Service
public class AuditService {

    private final AuditEventRepository repository;

    public AuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void record(String eventType, String entityType, String entityId, String detail) {
        AuditEvent event = new AuditEvent();
        event.setEventType(eventType);
        event.setEntityType(entityType);
        event.setEntityId(entityId);
        event.setDetail(detail);
        repository.save(event);
    }
}
