package com.artivisi.paymentgateway.repository;

import com.artivisi.paymentgateway.entity.WebhookDelivery;
import com.artivisi.paymentgateway.entity.WebhookStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, String> {

    /**
     * Claimable rows for a non-suspended consumer: PENDING and due, or SENDING gone stale
     * (reclaimed after a crash mid-send). Consumer is fetched so the send can read its signing
     * secret outside the claim transaction. Ordered oldest-due first; fairness is applied in code.
     */
    @Query("select d from WebhookDelivery d join fetch d.consumer c "
            + "where c.webhookSuspended = false and ("
            + "  (d.status = :pending and d.nextAttemptAt <= :now) or "
            + "  (d.status = :sending and d.updatedAt < :staleBefore)) "
            + "order by d.nextAttemptAt asc")
    List<WebhookDelivery> findClaimable(@Param("pending") WebhookStatus pending,
                                        @Param("sending") WebhookStatus sending,
                                        @Param("now") Instant now,
                                        @Param("staleBefore") Instant staleBefore,
                                        Pageable pageable);

    @Query("select d from WebhookDelivery d join fetch d.consumer where d.id = :id")
    Optional<WebhookDelivery> findByIdWithConsumer(@Param("id") String id);

    List<WebhookDelivery> findByConsumerIdAndStatus(String consumerId, WebhookStatus status);

    long countByConsumerIdAndStatus(String consumerId, WebhookStatus status);

    /** Deliveries in a status, newest attempt first, with consumer + charge fetched for the admin view. */
    @Query("select d from WebhookDelivery d join fetch d.consumer c join fetch d.charge "
            + "where d.status = :status order by d.updatedAt desc")
    List<WebhookDelivery> findByStatusWithDetail(@Param("status") WebhookStatus status, Pageable pageable);

    @Query("select d from WebhookDelivery d join fetch d.consumer c join fetch d.charge "
            + "where d.status = :status and c.code = :consumerCode order by d.updatedAt desc")
    List<WebhookDelivery> findByStatusAndConsumerCodeWithDetail(@Param("status") WebhookStatus status,
                                                                @Param("consumerCode") String consumerCode,
                                                                Pageable pageable);

    List<WebhookDelivery> findByChargeIdOrderByCreatedAtAsc(String chargeId);
}
