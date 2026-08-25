package com.artivisi.paymentgateway.repository;

import com.artivisi.paymentgateway.entity.BankCallback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BankCallbackRepository extends JpaRepository<BankCallback, String> {

    /** Callbacks carrying a field we do not model — newest first. */
    List<BankCallback> findByUnknownFieldsIsNotNullOrderByReceivedAtDesc();

    /** Every message received for one bank reference, for tracing a single payment end to end. */
    List<BankCallback> findByBankReferenceOrderByReceivedAtAsc(String bankReference);
}
