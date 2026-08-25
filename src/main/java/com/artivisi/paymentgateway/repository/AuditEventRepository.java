package com.artivisi.paymentgateway.repository;

import com.artivisi.paymentgateway.entity.AuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, String> {

    /**
     * Also match the row's subject, not just the event's own columns.
     *
     * <p>The log stores an entity id. Finance arrives holding a bill number off a bank statement, a VA
     * number, or a payer's name, and searching any of those found nothing — the screen would show the
     * bill number in the row it refused to find. These subqueries resolve the charge or payment behind
     * the event so the identifiers a person actually has are the ones that work.
     */
    String SUBJECT_MATCH =
            " or a.entityId in (select c.id from Charge c where lower(c.billNumber) like :pattern"
            + "   or lower(c.payerName) like :pattern or lower(c.consumerReference) like :pattern)"
            + " or a.entityId in (select v.charge.id from VirtualAccount v where lower(v.vaNumber) like :pattern)"
            + " or a.entityId in (select p.id from Payment p where lower(p.bankReference) like :pattern)";

    List<AuditEvent> findByEntityId(String entityId);

    @Query(value = "select a from AuditEvent a "
            + "where :pattern is null or lower(a.actor) like :pattern "
            + "or lower(a.eventType) like :pattern "
            + "or lower(a.entityType) like :pattern "
            + "or lower(a.entityId) like :pattern "
            + SUBJECT_MATCH
            + " order by a.createdAt desc",
            countQuery = "select count(a) from AuditEvent a "
            + "where :pattern is null or lower(a.actor) like :pattern "
            + "or lower(a.eventType) like :pattern "
            + "or lower(a.entityType) like :pattern "
            + "or lower(a.entityId) like :pattern" + SUBJECT_MATCH)
    Page<AuditEvent> searchByPattern(@Param("pattern") String pattern, Pageable pageable);

    /** Search actor / event / entity / id, plus bill number, VA, payer and bank reference; blank q = all. */
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
            + " or lower(a.entityType) like :pattern or lower(a.entityId) like :pattern"
            + SUBJECT_MATCH + ") "
            + "order by a.createdAt desc",
            countQuery = "select count(a) from AuditEvent a where "
            + "(:category is null "
            + " or (:category = 'AUTH' and a.eventType like 'AUTH\\_%' escape '\\') "
            + " or (:category = 'CHARGE' and a.eventType like 'CHARGE\\_%' escape '\\') "
            + " or (:category = 'PAYMENT' and a.eventType like 'PAYMENT\\_%' escape '\\') "
            + " or (:category = 'OTHER' and a.eventType not like 'AUTH\\_%' escape '\\' "
            + "      and a.eventType not like 'CHARGE\\_%' escape '\\' and a.eventType not like 'PAYMENT\\_%' escape '\\')) "
            + "and (:pattern is null or lower(a.actor) like :pattern or lower(a.eventType) like :pattern "
            + " or lower(a.entityType) like :pattern or lower(a.entityId) like :pattern"
            + SUBJECT_MATCH + ")")
    Page<AuditEvent> searchByCategoryAndPattern(@Param("category") String category, @Param("pattern") String pattern,
                                                Pageable pageable);

    /** As {@link #search(String, Pageable)}, narrowed to a derived category; blank q = all. */
    default Page<AuditEvent> search(String category, String q, Pageable pageable) {
        return searchByCategoryAndPattern(category, q == null || q.isBlank() ? null : "%" + q.toLowerCase() + "%", pageable);
    }

    long countByEventTypeStartingWith(String prefix);
}
