package com.artivisi.paymentgateway.repository;

import com.artivisi.paymentgateway.entity.Charge;
import com.artivisi.paymentgateway.entity.ChargeStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ChargeRepository extends JpaRepository<Charge, String> {

    Optional<Charge> findByConsumerIdAndConsumerReference(String consumerId, String consumerReference);

    Optional<Charge> findByIdAndConsumerId(String id, String consumerId);

    /** Pessimistic row lock so concurrent payments on the same charge are serialized. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Charge c where c.id = :id")
    Optional<Charge> lockById(@Param("id") String id);

    @Query("select c from Charge c join fetch c.consumer order by c.createdAt desc")
    List<Charge> findRecentWithConsumer(Pageable pageable);

    @Query(value = "select c from Charge c join fetch c.consumer "
            + "where :pattern is null or lower(c.consumerReference) like :pattern "
            + "or lower(c.payerName) like :pattern "
            + "order by c.createdAt desc",
            countQuery = "select count(c) from Charge c "
            + "where :pattern is null or lower(c.consumerReference) like :pattern "
            + "or lower(c.payerName) like :pattern")
    Page<Charge> searchByPattern(@Param("pattern") String pattern, Pageable pageable);

    /** Search consumerReference / payer (case-insensitive substring); blank q = all. */
    default Page<Charge> search(String q, Pageable pageable) {
        return searchByPattern(q == null || q.isBlank() ? null : "%" + q.toLowerCase() + "%", pageable);
    }

    @Query("select c from Charge c join fetch c.consumer where c.id = :id")
    Optional<Charge> findByIdWithConsumer(@Param("id") String id);

    /** Charges past their expiry that are still payable (not yet expired/paid/cancelled). */
    @Query("select c from Charge c where c.expiresAt <= :now and c.status in :statuses")
    List<Charge> findExpired(@Param("now") Instant now,
                             @Param("statuses") List<ChargeStatus> statuses);

    @Query(value = "select c from Charge c join fetch c.consumer "
            + "where (:status is null or c.status = :status) "
            + "and (:pattern is null or lower(c.consumerReference) like :pattern or lower(c.payerName) like :pattern) "
            + "order by c.createdAt desc",
            countQuery = "select count(c) from Charge c "
            + "where (:status is null or c.status = :status) "
            + "and (:pattern is null or lower(c.consumerReference) like :pattern or lower(c.payerName) like :pattern)")
    Page<Charge> searchByStatusAndPattern(@Param("status") ChargeStatus status, @Param("pattern") String pattern,
                                          Pageable pageable);

    /** Search consumerReference / payer, optionally narrowed to one status; blank q = all. */
    default Page<Charge> search(ChargeStatus status, String q, Pageable pageable) {
        return searchByStatusAndPattern(status, q == null || q.isBlank() ? null : "%" + q.toLowerCase() + "%", pageable);
    }

    long countByStatus(ChargeStatus status);

    long countByStatusAndUpdatedAtBetween(ChargeStatus status, Instant from, Instant to);

    long countByCreatedAtBetween(Instant from, Instant to);

    long countByStatusAndCreatedAtBetween(ChargeStatus status, Instant from, Instant to);

    @Query("select count(c) from Charge c where c.status in :statuses and c.expiresAt between :from and :to")
    long countExpiringBetween(@Param("statuses") List<ChargeStatus> statuses,
                              @Param("from") Instant from, @Param("to") Instant to);

    @Query("select coalesce(sum(c.amount - c.cumulativePaid), 0) from Charge c where c.status in :statuses")
    BigDecimal sumOutstanding(@Param("statuses") List<ChargeStatus> statuses);
}
