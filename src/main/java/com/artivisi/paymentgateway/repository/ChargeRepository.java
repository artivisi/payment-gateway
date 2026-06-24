package com.artivisi.paymentgateway.repository;

import com.artivisi.paymentgateway.entity.Charge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChargeRepository extends JpaRepository<Charge, String> {

    Optional<Charge> findByConsumerIdAndConsumerReference(String consumerId, String consumerReference);
}
