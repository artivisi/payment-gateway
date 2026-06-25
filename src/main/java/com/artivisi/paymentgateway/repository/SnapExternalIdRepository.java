package com.artivisi.paymentgateway.repository;

import com.artivisi.paymentgateway.entity.SnapExternalId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;

public interface SnapExternalIdRepository extends JpaRepository<SnapExternalId, String> {

    boolean existsByEscrowAccountIdAndTransactionDateAndServiceNameAndExternalId(
            String escrowAccountId, LocalDate transactionDate, String serviceName, String externalId);
}
