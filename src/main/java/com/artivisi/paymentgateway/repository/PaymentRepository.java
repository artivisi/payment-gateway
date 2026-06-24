package com.artivisi.paymentgateway.repository;

import com.artivisi.paymentgateway.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, String> {

    boolean existsByVirtualAccountIdAndBankReference(String virtualAccountId, String bankReference);
}
