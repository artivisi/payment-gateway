package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.AbstractIntegrationTest;
import com.artivisi.paymentgateway.dto.ChargeAccountRequest;
import com.artivisi.paymentgateway.dto.ConsumerRequest;
import com.artivisi.paymentgateway.dto.CreateChargeRequest;
import com.artivisi.paymentgateway.dto.EscrowAccountRequest;
import com.artivisi.paymentgateway.entity.AuditEvent;
import com.artivisi.paymentgateway.entity.AuthScheme;
import com.artivisi.paymentgateway.entity.ChargeType;
import com.artivisi.paymentgateway.entity.Consumer;
import com.artivisi.paymentgateway.entity.ConsumerStatus;
import com.artivisi.paymentgateway.entity.EscrowAccount;
import com.artivisi.paymentgateway.entity.EscrowEnvironment;
import com.artivisi.paymentgateway.entity.HostingModel;
import com.artivisi.paymentgateway.entity.Payment;
import com.artivisi.paymentgateway.entity.TransportProtocol;
import com.artivisi.paymentgateway.repository.AuditEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AuditLoggingIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired EscrowAccountService escrowService;
    @Autowired ConsumerService consumerService;
    @Autowired ChargeService chargeService;
    @Autowired PaymentApplicationService paymentService;
    @Autowired AuditEventRepository auditEventRepository;

    @Test
    void keyActionsAreAuditedWithoutSecrets() {
        int n = SEQ.incrementAndGet();
        String va = "9500000" + String.format("%03d", n);

        EscrowAccount escrow = escrowService.create(new EscrowAccountRequest(
                "audit-bsi-" + n, "bsi", HostingModel.SELF_HOSTED, TransportProtocol.REST_JSON,
                AuthScheme.PROPRIETARY, EscrowEnvironment.SANDBOX, "audit-client", "audit-secret", null, null,
                null, null, null, null, "950900111", "Operator Settlement", "95099", "950", 10, null, null));
        Consumer consumer = consumerService.create(new ConsumerRequest(
                "audit-consumer-" + n, "Academic", "audit-consumer-client-" + n, "audit-consumer-secret",
                "https://hook.example/" + n, ConsumerStatus.ACTIVE));
        String chargeId = chargeService.create(consumer, new CreateChargeRequest(
                        "audit-ref-" + n, "Student", null, null, ChargeType.CLOSED, new BigDecimal("100000"), null,
                        List.of(new ChargeAccountRequest("audit-bsi-" + n, va)))).response().id();
        Payment payment = paymentService.apply(escrow, va, new BigDecimal("100000"), "AUD-R1", Instant.now());

        assertThat(eventTypes(escrow.getId())).contains("ESCROW_CREATED");
        assertThat(eventTypes(consumer.getId())).contains("CONSUMER_CREATED");
        assertThat(eventTypes(chargeId)).contains("CHARGE_CREATED");
        assertThat(eventTypes(payment.getId())).contains("PAYMENT_APPLIED");

        // Secrets must never appear in audit detail.
        for (AuditEvent event : auditEventRepository.findByEntityId(escrow.getId())) {
            assertThat(event.getDetail()).doesNotContain("audit-secret");
        }
    }

    private List<String> eventTypes(String entityId) {
        return auditEventRepository.findByEntityId(entityId).stream().map(AuditEvent::getEventType).toList();
    }
}
