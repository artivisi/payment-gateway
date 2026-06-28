package com.artivisi.paymentgateway.repository;

import com.artivisi.paymentgateway.entity.BankIpRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BankIpRuleRepository extends JpaRepository<BankIpRule, String> {

    List<BankIpRule> findByProviderAndEnabledTrue(String provider);

    List<BankIpRule> findAllByOrderByProviderAscCreatedAtAsc();
}
