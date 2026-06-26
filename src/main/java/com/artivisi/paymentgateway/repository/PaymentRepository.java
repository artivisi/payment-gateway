package com.artivisi.paymentgateway.repository;

import com.artivisi.paymentgateway.entity.Payment;
import com.artivisi.paymentgateway.entity.PaymentStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    @Query("select p from Payment p join fetch p.virtualAccount where p.charge.id = :chargeId order by p.createdAt")
    List<Payment> findByChargeIdWithVa(@Param("chargeId") String chargeId);
}
