package com.artivisi.paymentgateway.repository;

import com.artivisi.paymentgateway.entity.EscrowAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EscrowAccountRepository extends JpaRepository<EscrowAccount, String> {

    Optional<EscrowAccount> findByCode(String code);

    boolean existsByCode(String code);

    List<EscrowAccount> findByProvider(String provider);
}
