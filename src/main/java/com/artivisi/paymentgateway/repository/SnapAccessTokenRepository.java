package com.artivisi.paymentgateway.repository;

import com.artivisi.paymentgateway.entity.SnapAccessToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SnapAccessTokenRepository extends JpaRepository<SnapAccessToken, String> {

    Optional<SnapAccessToken> findByAccessToken(String accessToken);
}
