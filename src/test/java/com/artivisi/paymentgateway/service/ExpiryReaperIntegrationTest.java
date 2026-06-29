package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.AbstractIntegrationTest;
import com.artivisi.paymentgateway.dto.ChargeAccountRequest;
import com.artivisi.paymentgateway.dto.ConsumerRequest;
import com.artivisi.paymentgateway.dto.CreateChargeRequest;
import com.artivisi.paymentgateway.dto.EscrowAccountRequest;
import com.artivisi.paymentgateway.entity.AuthScheme;
import com.artivisi.paymentgateway.entity.ChargeStatus;
import com.artivisi.paymentgateway.entity.ChargeType;
import com.artivisi.paymentgateway.entity.Consumer;
import com.artivisi.paymentgateway.entity.ConsumerStatus;
import com.artivisi.paymentgateway.entity.EscrowEnvironment;
import com.artivisi.paymentgateway.entity.HostingModel;
import com.artivisi.paymentgateway.entity.TransportProtocol;
import com.artivisi.paymentgateway.entity.VirtualAccountStatus;
import com.artivisi.paymentgateway.repository.AuditEventRepository;
import com.artivisi.paymentgateway.repository.ChargeRepository;
import com.artivisi.paymentgateway.repository.ConsumerRepository;
import com.artivisi.paymentgateway.repository.VirtualAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ExpiryReaperIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired ExpiryReaper reaper;
    @Autowired ChargeService chargeService;
    @Autowired ConsumerService consumerService;
    @Autowired ConsumerRepository consumerRepository;
    @Autowired EscrowAccountService escrowService;
    @Autowired ChargeRepository chargeRepository;
    @Autowired VirtualAccountRepository vaRepository;
    @Autowired AuditEventRepository auditRepository;

    private Consumer consumer;
    private String escrowCode;

    @BeforeEach
    void seed() {
        int n = SEQ.incrementAndGet();
        escrowCode = "reaper-bsi-" + n;
        escrowService.create(new EscrowAccountRequest(
                escrowCode, "bsi", HostingModel.SELF_HOSTED, TransportProtocol.REST_JSON,
                AuthScheme.PROPRIETARY, EscrowEnvironment.SANDBOX,
                null, null, null, null, null, null, null, null,
                "900900111", "Operator", "90099", "900", 10, null, null));
        String clientId = "reaper-client-" + n;
        consumerService.create(new ConsumerRequest(
                "reaper-consumer-" + n, "Test", clientId, "secret-" + n,
                "https://hook.example/" + n, ConsumerStatus.ACTIVE));
        consumer = consumerRepository.findByClientId(clientId).orElseThrow();
    }

    private String createCharge(String ref, Instant expiresAt, String vaNumber) {
        var request = new CreateChargeRequest(
                ref, "Payer", null, null, ChargeType.CLOSED, new BigDecimal("500000"),
                expiresAt,
                List.of(new ChargeAccountRequest(escrowCode, vaNumber)));
        return chargeService.create(consumer, request).response().id();
    }

    @Test
    void sweep_expiresActiveChargeAndCancelsVas() {
        String chargeId = createCharge("ref-exp-1", Instant.now().minusSeconds(60), "9009000001");

        reaper.sweep();

        var charge = chargeRepository.findById(chargeId).orElseThrow();
        assertThat(charge.getStatus()).isEqualTo(ChargeStatus.EXPIRED);

        var vas = vaRepository.findByChargeId(chargeId);
        assertThat(vas).allMatch(va -> va.getStatus() == VirtualAccountStatus.EXPIRED);
    }

    @Test
    void sweep_auditsExpiry() {
        long auditsBefore = auditRepository.count();
        createCharge("ref-exp-aud", Instant.now().minusSeconds(1), "9009000002");

        reaper.sweep();

        long auditsAfter = auditRepository.count();
        assertThat(auditsAfter).isGreaterThan(auditsBefore);
        boolean found = auditRepository.findAll().stream()
                .anyMatch(e -> "CHARGE_EXPIRED".equals(e.getEventType())
                        && "system".equals(e.getActor()));
        assertThat(found).isTrue();
    }

    @Test
    void sweep_ignoresNonExpiredCharges() {
        String chargeId = createCharge("ref-not-exp", Instant.now().plusSeconds(3600), "9009000003");

        reaper.sweep();

        var charge = chargeRepository.findById(chargeId).orElseThrow();
        assertThat(charge.getStatus()).isEqualTo(ChargeStatus.ACTIVE);
    }

    @Test
    void sweep_ignoresAlreadyPaidCharges() {
        String chargeId = createCharge("ref-paid", Instant.now().minusSeconds(60), "9009000004");
        // Manually mark it PAID before the reaper runs
        var charge = chargeRepository.findById(chargeId).orElseThrow();
        charge.setStatus(ChargeStatus.PAID);
        chargeRepository.save(charge);

        reaper.sweep();

        var updated = chargeRepository.findById(chargeId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(ChargeStatus.PAID);
    }
}
