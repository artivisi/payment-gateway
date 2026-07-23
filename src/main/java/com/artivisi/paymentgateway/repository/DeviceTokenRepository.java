package com.artivisi.paymentgateway.repository;

import com.artivisi.paymentgateway.entity.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, String> {

    List<DeviceToken> findByRevokedAtIsNull();

    List<DeviceToken> findByOperatorIdOrderByCreatedAtDesc(String operatorId);
}
