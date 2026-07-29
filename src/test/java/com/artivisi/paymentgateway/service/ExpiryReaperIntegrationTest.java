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
    @Autowired InquiryService inquiryService;
    @Autowired com.artivisi.paymentgateway.repository.EscrowAccountRepository escrowRepository;

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
    void sweep_retiresVasButLeavesTheChargeStatusAlone() {
        String chargeId = createCharge("ref-exp-1", Instant.now().minusSeconds(60), "9009000001");

        reaper.sweep();

        var charge = chargeRepository.findById(chargeId).orElseThrow();
        // Soft expiry: the debt is still owed and still reportable. Flipping the status would make
        // this charge invisible to collection-aging analysis, which is precisely the population such
        // analysis exists to measure.
        assertThat(charge.getStatus()).isEqualTo(ChargeStatus.ACTIVE);

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
                .anyMatch(e -> "CHARGE_VA_RETIRED_ON_EXPIRY".equals(e.getEventType())
                        && "system".equals(e.getActor()));
        assertThat(found).isTrue();
    }

    /**
     * The sweep must be a no-op once it has retired a charge's VAs. Expiry is soft, so the charge
     * keeps its status forever — a selection keyed on status alone re-picks it every minute for the
     * life of the charge, and the audit row it wrote each pass drowned the log (97% of production's
     * audit_event by 2026-07-29).
     */
    @Test
    void sweep_isNoOpOnceTheVasAreRetired() {
        String chargeId = createCharge("ref-exp-twice", Instant.now().minusSeconds(60), "9009000010");

        reaper.sweep();
        long auditsAfterFirst = auditRepository.count();
        assertThat(vaRepository.findByChargeId(chargeId))
                .allMatch(va -> va.getStatus() == VirtualAccountStatus.EXPIRED);

        reaper.sweep();
        reaper.sweep();

        assertThat(auditRepository.count())
                .as("a sweep that retires nothing must not write an audit event")
                .isEqualTo(auditsAfterFirst);
        assertThat(chargeRepository.findExpired(Instant.now(), List.of(ChargeStatus.ACTIVE, ChargeStatus.PARTIALLY_PAID)))
                .as("a charge with no ACTIVE VA left has nothing to sweep")
                .noneMatch(c -> c.getId().equals(chargeId));
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

    /**
     * The point of soft expiry: the charge is refused at the wire, but the row still says a debt is
     * outstanding, so an aging study can see it — and extending the deadline needs no status unwind.
     */
    @Test
    void expiredCharge_isRefusedAtInquiry_yetRemainsVisibleAsOutstanding() {
        String chargeId = createCharge("ref-exp-soft", Instant.now().minusSeconds(60), "9009000009");
        reaper.sweep();

        var charge = chargeRepository.findById(chargeId).orElseThrow();
        assertThat(charge.getStatus()).isEqualTo(ChargeStatus.ACTIVE);
        assertThat(charge.getCumulativePaid()).isEqualByComparingTo(java.math.BigDecimal.ZERO);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> inquiryService.inquire(
                                escrowRepository.findByCode(escrowCode).orElseThrow(), "9009000009"))
                .isInstanceOf(com.artivisi.paymentgateway.exception.NotFoundException.class);
    }
}
