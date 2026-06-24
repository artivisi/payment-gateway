package com.artivisi.paymentgateway.repository;

import com.artivisi.paymentgateway.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, String> {

    boolean existsByVirtualAccountIdAndBankReference(String virtualAccountId, String bankReference);

    Optional<Payment> findByVirtualAccountIdAndBankReference(String virtualAccountId, String bankReference);
}
