package com.artivisi.paymentgateway.repository;

import com.artivisi.paymentgateway.entity.AuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, String> {

    List<AuditEvent> findByEntityId(String entityId);

    @Query(value = "select a from AuditEvent a "
            + "where :pattern is null or lower(a.actor) like :pattern "
            + "or lower(a.eventType) like :pattern "
            + "or lower(a.entityType) like :pattern "
            + "or lower(a.entityId) like :pattern "
            + "order by a.createdAt desc",
            countQuery = "select count(a) from AuditEvent a "
            + "where :pattern is null or lower(a.actor) like :pattern "
            + "or lower(a.eventType) like :pattern "
            + "or lower(a.entityType) like :pattern "
            + "or lower(a.entityId) like :pattern")
    Page<AuditEvent> searchByPattern(@Param("pattern") String pattern, Pageable pageable);

    /** Search actor / event / entity / id (case-insensitive substring); blank q = all. */
    default Page<AuditEvent> search(String q, Pageable pageable) {
        return searchByPattern(q == null || q.isBlank() ? null : "%" + q.toLowerCase() + "%", pageable);
    }
}
