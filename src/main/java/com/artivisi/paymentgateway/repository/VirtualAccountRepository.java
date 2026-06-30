package com.artivisi.paymentgateway.repository;

import com.artivisi.paymentgateway.entity.VirtualAccount;
import com.artivisi.paymentgateway.entity.VirtualAccountStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface VirtualAccountRepository extends JpaRepository<VirtualAccount, String> {

    Optional<VirtualAccount> findByEscrowAccountIdAndVaNumber(String escrowAccountId, String vaNumber);

    Optional<VirtualAccount> findByEscrowAccountIdAndVaNumberAndStatus(
            String escrowAccountId, String vaNumber, VirtualAccountStatus status);

    long countByEscrowAccountId(String escrowAccountId);

    List<VirtualAccount> findByChargeId(String chargeId);

    @Query("select v from VirtualAccount v join fetch v.escrowAccount where v.charge.id = :chargeId")
    List<VirtualAccount> findByChargeIdWithEscrow(@Param("chargeId") String chargeId);
}
