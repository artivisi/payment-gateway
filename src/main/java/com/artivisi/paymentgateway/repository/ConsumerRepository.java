package com.artivisi.paymentgateway.repository;

import com.artivisi.paymentgateway.entity.Consumer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ConsumerRepository extends JpaRepository<Consumer, String> {

    Optional<Consumer> findByCode(String code);

    Optional<Consumer> findByClientId(String clientId);

    boolean existsByCode(String code);

    boolean existsByClientId(String clientId);
}
