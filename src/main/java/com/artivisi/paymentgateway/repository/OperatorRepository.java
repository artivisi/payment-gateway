package com.artivisi.paymentgateway.repository;

import com.artivisi.paymentgateway.entity.Operator;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OperatorRepository extends JpaRepository<Operator, String> {

    Optional<Operator> findByUsername(String username);

    boolean existsByUsername(String username);

    long count();

    List<Operator> findAllByOrderByUsernameAsc();
}
