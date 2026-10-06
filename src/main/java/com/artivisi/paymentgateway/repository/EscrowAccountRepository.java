package com.artivisi.paymentgateway.repository;

import com.artivisi.paymentgateway.entity.EscrowAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface EscrowAccountRepository extends JpaRepository<EscrowAccount, String> {

    Optional<EscrowAccount> findByCode(String code);

    Optional<EscrowAccount> findByClientId(String clientId);

    boolean existsByCode(String code);

    List<EscrowAccount> findByProvider(String provider);

    /**
     * Code and provider of every enabled escrow, read without materialising the entity. Loading it
     * decrypts the credentials, which needs {@code SecretCipher} registered first — a startup check
     * that runs in another bean's {@code @PostConstruct} has no such guarantee, and needs no secret.
     */
    @Query("select e.code as code, e.provider as provider from EscrowAccount e where e.enabled = true")
    List<EnabledEscrow> findEnabled();

    interface EnabledEscrow {
        String getCode();

        String getProvider();
    }
}
