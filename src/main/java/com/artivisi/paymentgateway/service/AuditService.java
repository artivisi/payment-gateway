package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.entity.AuditEvent;
import com.artivisi.paymentgateway.repository.AuditEventRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records significant domain actions to the audit log (PCI Req 10). Joins the caller's transaction,
 * so an event is persisted only if the action it records commits. The acting operator is captured
 * from the security context. Detail must never contain secrets or signatures.
 */
@Service
public class AuditService {

    private final AuditEventRepository repository;

    public AuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void record(String eventType, String entityType, String entityId, String detail) {
        recordAs(currentActor(), eventType, entityType, entityId, detail);
    }

    /** Record with an explicit actor — for auth events where there is no established security context yet. */
    @Transactional
    public void recordAs(String actor, String eventType, String entityType, String entityId, String detail) {
        AuditEvent event = new AuditEvent();
        event.setActor(actor);
        event.setEventType(eventType);
        event.setEntityType(entityType);
        event.setEntityId(entityId);
        event.setDetail(detail);
        repository.save(event);
    }

    private static String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return null;
        }
        return auth.getName();
    }
}
