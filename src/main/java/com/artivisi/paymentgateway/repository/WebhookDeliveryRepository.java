package com.artivisi.paymentgateway.repository;

import com.artivisi.paymentgateway.entity.WebhookDelivery;
import com.artivisi.paymentgateway.entity.WebhookStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, String> {

    List<WebhookDelivery> findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            WebhookStatus status, Instant cutoff, Pageable pageable);

    List<WebhookDelivery> findByChargeIdOrderByCreatedAtAsc(String chargeId);
}
