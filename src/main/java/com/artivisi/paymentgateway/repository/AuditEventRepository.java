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

    @Query(value = "select a from AuditEvent a where "
            + "(:category is null "
            + " or (:category = 'AUTH' and a.eventType like 'AUTH\\_%' escape '\\') "
            + " or (:category = 'CHARGE' and a.eventType like 'CHARGE\\_%' escape '\\') "
            + " or (:category = 'PAYMENT' and a.eventType like 'PAYMENT\\_%' escape '\\') "
            + " or (:category = 'OTHER' and a.eventType not like 'AUTH\\_%' escape '\\' "
            + "      and a.eventType not like 'CHARGE\\_%' escape '\\' and a.eventType not like 'PAYMENT\\_%' escape '\\')) "
            + "and (:pattern is null or lower(a.actor) like :pattern or lower(a.eventType) like :pattern "
            + " or lower(a.entityType) like :pattern or lower(a.entityId) like :pattern) "
            + "order by a.createdAt desc",
            countQuery = "select count(a) from AuditEvent a where "
            + "(:category is null "
            + " or (:category = 'AUTH' and a.eventType like 'AUTH\\_%' escape '\\') "
            + " or (:category = 'CHARGE' and a.eventType like 'CHARGE\\_%' escape '\\') "
            + " or (:category = 'PAYMENT' and a.eventType like 'PAYMENT\\_%' escape '\\') "
            + " or (:category = 'OTHER' and a.eventType not like 'AUTH\\_%' escape '\\' "
            + "      and a.eventType not like 'CHARGE\\_%' escape '\\' and a.eventType not like 'PAYMENT\\_%' escape '\\')) "
            + "and (:pattern is null or lower(a.actor) like :pattern or lower(a.eventType) like :pattern "
            + " or lower(a.entityType) like :pattern or lower(a.entityId) like :pattern)")
    Page<AuditEvent> searchByCategoryAndPattern(@Param("category") String category, @Param("pattern") String pattern,
                                                Pageable pageable);

    /** Search actor / event / entity / id, optionally narrowed to a derived category; blank q = all. */
    default Page<AuditEvent> search(String category, String q, Pageable pageable) {
        return searchByCategoryAndPattern(category, q == null || q.isBlank() ? null : "%" + q.toLowerCase() + "%", pageable);
    }

    long countByEventTypeStartingWith(String prefix);
}
