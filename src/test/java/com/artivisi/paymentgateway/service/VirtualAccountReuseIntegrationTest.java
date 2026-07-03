package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.AbstractIntegrationTest;
import com.artivisi.paymentgateway.dto.ChargeAccountRequest;
import com.artivisi.paymentgateway.dto.ConsumerRequest;
import com.artivisi.paymentgateway.dto.CreateChargeRequest;
import com.artivisi.paymentgateway.dto.EscrowAccountRequest;
import com.artivisi.paymentgateway.dto.InquiryResult;
import com.artivisi.paymentgateway.entity.AuthScheme;
import com.artivisi.paymentgateway.entity.ChargeStatus;
import com.artivisi.paymentgateway.entity.ChargeType;
import com.artivisi.paymentgateway.entity.Consumer;
import com.artivisi.paymentgateway.entity.ConsumerStatus;
import com.artivisi.paymentgateway.entity.EscrowAccount;
import com.artivisi.paymentgateway.entity.EscrowEnvironment;
import com.artivisi.paymentgateway.entity.HostingModel;
import com.artivisi.paymentgateway.entity.Payment;
import com.artivisi.paymentgateway.entity.TransportProtocol;
import com.artivisi.paymentgateway.repository.ChargeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VA numbers are reusable once a generation is no longer ACTIVE (the billing system reissues the
 * same number = invoice-type code + debtor code for the next bill). Inquiry, payment, and replay
 * idempotency must resolve the right generation.
 */
class VirtualAccountReuseIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired EscrowAccountService escrowService;
    @Autowired ConsumerService consumerService;
    @Autowired ChargeService chargeService;
    @Autowired PaymentApplicationService paymentService;
    @Autowired InquiryService inquiryService;
    @Autowired ChargeRepository chargeRepository;

    private Consumer consumer;
    private EscrowAccount escrow;
    private String vaNumber;

    @BeforeEach
    void seed() {
        int n = SEQ.incrementAndGet();
        consumer = consumerService.create(new ConsumerRequest(
                "reuse-consumer-" + n, "Academic", "reuse-client-" + n, "secret-" + n,
                "https://hook.example/" + n, ConsumerStatus.ACTIVE));
        escrow = escrowService.create(new EscrowAccountRequest(
                "reuse-bsi-" + n, "bsi", HostingModel.SELF_HOSTED, TransportProtocol.REST_JSON,
                AuthScheme.PROPRIETARY, EscrowEnvironment.SANDBOX, null, null, null, null, null, null, null, null,
                "920900111", "Operator Settlement", "92099", "920", 10, null, null));
        vaNumber = "920000000" + (n % 10);
    }

    private String createCharge(BigDecimal amount) {
        var outcome = chargeService.create(consumer, new CreateChargeRequest(
                "reuse-ref-" + SEQ.get() + "-" + amount, "Student", null, null,
                ChargeType.CLOSED, amount, null,
                List.of(new ChargeAccountRequest(escrow.getCode(), vaNumber))));
        return outcome.response().id();
    }

    @Test
    void paidGeneration_reissuedNumber_paysNewChargeAndKeepsReplayIdempotent() {
        String first = createCharge(new BigDecimal("100000"));
        Payment original = paymentService.apply(escrow, vaNumber,
                new BigDecimal("100000"), "REF-A", Instant.now());
        assertThat(chargeRepository.findById(first).orElseThrow().getStatus())
                .isEqualTo(ChargeStatus.PAID);

        String second = createCharge(new BigDecimal("250000"));

        InquiryResult inquiry = inquiryService.inquire(escrow, vaNumber);
        assertThat(inquiry.remainingAmount()).isEqualByComparingTo("250000");

        // Replaying the settled generation's reference returns the original payment untouched.
        Payment replayed = paymentService.apply(escrow, vaNumber,
                new BigDecimal("100000"), "REF-A", Instant.now());
        assertThat(replayed.getId()).isEqualTo(original.getId());
        assertThat(chargeRepository.findById(second).orElseThrow().getStatus())
                .isEqualTo(ChargeStatus.ACTIVE);

        Payment paid = paymentService.apply(escrow, vaNumber,
                new BigDecimal("250000"), "REF-B", Instant.now());
        assertThat(paid.getCharge().getId()).isEqualTo(second);
        assertThat(chargeRepository.findById(second).orElseThrow().getStatus())
                .isEqualTo(ChargeStatus.PAID);
    }

    @Test
    void cancelledGeneration_reissuedNumber_isPayable() {
        String first = createCharge(new BigDecimal("500000"));
        chargeService.cancel(consumer, first);

        String second = createCharge(new BigDecimal("750000"));

        assertThat(inquiryService.inquire(escrow, vaNumber).remainingAmount())
                .isEqualByComparingTo("750000");
        Payment payment = paymentService.apply(escrow, vaNumber,
                new BigDecimal("750000"), "REF-C", Instant.now());
        assertThat(payment.getCharge().getId()).isEqualTo(second);
    }
}
