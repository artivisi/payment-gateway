package com.artivisi.paymentgateway.repository;

import com.artivisi.paymentgateway.entity.VirtualAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VirtualAccountRepository extends JpaRepository<VirtualAccount, String> {

    Optional<VirtualAccount> findByEscrowAccountIdAndVaNumber(String escrowAccountId, String vaNumber);
}
