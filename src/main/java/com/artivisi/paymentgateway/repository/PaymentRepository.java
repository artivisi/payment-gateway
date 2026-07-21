package com.artivisi.paymentgateway.repository;

import com.artivisi.paymentgateway.entity.Payment;
import com.artivisi.paymentgateway.entity.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, String> {

    boolean existsByVirtualAccountIdAndBankReference(String virtualAccountId, String bankReference);

    Optional<Payment> findByVirtualAccountIdAndBankReference(String virtualAccountId, String bankReference);

    @Query("""
            select p from Payment p
            where p.virtualAccount.escrowAccount.id = :escrowId
              and p.status = :status
              and p.transactionTime >= :start and p.transactionTime < :end
            """)
    List<Payment> findByEscrowAndStatusInPeriod(@Param("escrowId") String escrowId,
                                                @Param("status") PaymentStatus status,
                                                @Param("start") Instant start,
                                                @Param("end") Instant end);

    @Query("select p from Payment p join fetch p.virtualAccount join fetch p.charge order by p.createdAt desc")
    List<Payment> findRecentWithVaAndCharge(Pageable pageable);

    @Query(value = "select p from Payment p join fetch p.virtualAccount va join fetch p.charge "
            + "where :pattern is null or lower(p.bankReference) like :pattern "
            + "or lower(va.vaNumber) like :pattern "
            + "order by p.createdAt desc",
            countQuery = "select count(p) from Payment p "
            + "where :pattern is null or lower(p.bankReference) like :pattern "
            + "or lower(p.virtualAccount.vaNumber) like :pattern")
    Page<Payment> searchByPattern(@Param("pattern") String pattern, Pageable pageable);

    /** Search bankReference / VA number (case-insensitive substring); blank q = all. */
    default Page<Payment> search(String q, Pageable pageable) {
        return searchByPattern(q == null || q.isBlank() ? null : "%" + q.toLowerCase() + "%", pageable);
    }

    @Query("select p from Payment p join fetch p.virtualAccount where p.charge.id = :chargeId order by p.createdAt")
    List<Payment> findByChargeIdWithVa(@Param("chargeId") String chargeId);

    @Query("select p from Payment p join fetch p.virtualAccount join fetch p.charge where p.id = :id")
    Optional<Payment> findByIdWithVaAndCharge(@Param("id") String id);

    /**
     * {@code start}/{@code end} must both be non-null (Postgres cannot infer a bind parameter's type
     * from a bare {@code ? is null} check with no other typed usage); pass sentinel bounds for "unbounded".
     */
    @Query(value = "select p from Payment p join fetch p.virtualAccount va join fetch va.escrowAccount join fetch p.charge "
            + "where p.transactionTime >= :start and p.transactionTime < :end "
            + "and (:pattern is null or lower(p.bankReference) like :pattern or lower(va.vaNumber) like :pattern) "
            + "order by p.createdAt desc",
            countQuery = "select count(p) from Payment p "
            + "where p.transactionTime >= :start and p.transactionTime < :end "
            + "and (:pattern is null or lower(p.bankReference) like :pattern or lower(p.virtualAccount.vaNumber) like :pattern)")
    Page<Payment> searchByPeriodAndPattern(@Param("start") Instant start, @Param("end") Instant end,
                                           @Param("pattern") String pattern, Pageable pageable);

    /** Search bankReference / VA number within [start, end); blank q = all. */
    default Page<Payment> search(Instant start, Instant end, String q, Pageable pageable) {
        return searchByPeriodAndPattern(start, end, q == null || q.isBlank() ? null : "%" + q.toLowerCase() + "%", pageable);
    }

    long countByTransactionTimeBetween(Instant start, Instant end);

    @Query("select coalesce(sum(p.amount), 0) from Payment p "
            + "where p.status = :status and p.transactionTime >= :start and p.transactionTime < :end")
    BigDecimal sumAmountInPeriod(@Param("status") PaymentStatus status,
                                 @Param("start") Instant start, @Param("end") Instant end);

    long countByStatusAndTransactionTimeBetween(PaymentStatus status, Instant start, Instant end);

    /** Raw rows for the dashboard's day-bucketed collections trend (bucketing done in Java, in the app's zone). */
    List<Payment> findByStatusAndTransactionTimeGreaterThanEqualOrderByTransactionTime(
            PaymentStatus status, Instant start);
}
