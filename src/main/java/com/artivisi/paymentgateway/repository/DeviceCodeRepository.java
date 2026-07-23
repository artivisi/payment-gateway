package com.artivisi.paymentgateway.repository;

import com.artivisi.paymentgateway.entity.DeviceCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DeviceCodeRepository extends JpaRepository<DeviceCode, String> {

    Optional<DeviceCode> findByDeviceCode(String deviceCode);

    Optional<DeviceCode> findByUserCode(String userCode);
}
