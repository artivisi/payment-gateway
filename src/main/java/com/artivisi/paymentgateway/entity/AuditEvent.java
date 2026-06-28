package com.artivisi.paymentgateway.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;

/**
 * Append-only record of a significant action. Never stores secrets or signatures.
 */
@Getter
@Setter
@Entity
@Table(name = "audit_event")
public class AuditEvent {

    @Id
    @UuidGenerator
    private String id;

    private String eventType;

    /** Authenticated operator username that performed the action; null for system events. */
    private String actor;

    private String entityType;

    private String entityId;

    @Column(columnDefinition = "text")
    private String detail;

    @CreationTimestamp
    private Instant createdAt;
}
