package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.AbstractIntegrationTest;
import com.artivisi.paymentgateway.dto.ChargeAccountRequest;
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
import com.artivisi.paymentgateway.entity.TransportProtocol;
import com.artivisi.paymentgateway.entity.VirtualAccountStatus;
import com.artivisi.paymentgateway.dto.ConsumerRequest;
import com.artivisi.paymentgateway.entity.Charge;
import com.artivisi.paymentgateway.exception.InvalidPaymentException;
import com.artivisi.paymentgateway.exception.NotFoundException;
import com.artivisi.paymentgateway.repository.ChargeRepository;
import com.artivisi.paymentgateway.repository.VirtualAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentLifecycleIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired EscrowAccountService escrowService;
    @Autowired ConsumerService consumerService;
    @Autowired ChargeService chargeService;
    @Autowired PaymentApplicationService paymentService;
    @Autowired InquiryService inquiryService;
    @Autowired ChargeRepository chargeRepository;
    @Autowired VirtualAccountRepository virtualAccountRepository;

    private Consumer consumer;
    private EscrowAccount bank_a;
    private EscrowAccount bank_b;
    private String bank_aVa;
    private String bank_bVa;

    private static EscrowAccountRequest escrowRequest(String code) {
        return new EscrowAccountRequest(code, "bank_a", HostingModel.SELF_HOSTED, TransportProtocol.REST_JSON,
                AuthScheme.PROPRIETARY, EscrowEnvironment.SANDBOX, null, null, null, null, null, null, null, null,
                "900900111", "Operator Settlement", "90099", "900", 10, null, null);
    }

    @BeforeEach
    void seed() {
        int n = SEQ.incrementAndGet();
        consumer = consumerService.create(new ConsumerRequest(
                "pl-consumer-" + n, "Academic", "pl-client-" + n, "secret-" + n,
                "https://hook.example/" + n, ConsumerStatus.ACTIVE));
        bank_a = escrowService.create(escrowRequest("pl-bank_a-" + n));
        bank_b = escrowService.create(escrowRequest("pl-bank_b-" + n));
        bank_aVa = "900000000" + (n % 10);
        bank_bVa = "900000001" + (n % 10);
    }

    private Charge createCharge(ChargeType type, BigDecimal amount) {
        return createCharge(type, amount, null);
    }

    private Charge createCharge(ChargeType type, BigDecimal amount, Instant expiresAt) {
        var outcome = chargeService.create(consumer, new CreateChargeRequest(
                "ref-" + SEQ.get(), "Student", null, null, type, amount, expiresAt,
                List.of(new ChargeAccountRequest(bank_a.getCode(), bank_aVa),
                        new ChargeAccountRequest(bank_b.getCode(), bank_bVa))));
        return chargeRepository.findById(outcome.response().id()).orElseThrow();
    }

    private VirtualAccountStatus vaStatus(String escrowId, String vaNumber) {
        return virtualAccountRepository.findByEscrowAccountIdAndVaNumberOrderByCreatedAtDesc(escrowId, vaNumber)
                .getFirst().getStatus();
    }

    @Test
    void closed_firstPaymentPaysChargeAndCancelsSiblings() {
        Charge charge = createCharge(ChargeType.CLOSED, new BigDecimal("1000000"));

        paymentService.apply(bank_a, bank_aVa, new BigDecimal("1000000"), "BANK_A-REF-1", Instant.now());

        Charge reloaded = chargeRepository.findById(charge.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ChargeStatus.PAID);
        assertThat(reloaded.getCumulativePaid()).isEqualByComparingTo("1000000");
        assertThat(vaStatus(bank_a.getId(), bank_aVa)).isEqualTo(VirtualAccountStatus.PAID);
        assertThat(vaStatus(bank_b.getId(), bank_bVa)).isEqualTo(VirtualAccountStatus.CANCELLED);
    }

    @Test
    void closed_replayedNotificationIsIdempotent() {
        createCharge(ChargeType.CLOSED, new BigDecimal("1000000"));
        var first = paymentService.apply(bank_a, bank_aVa, new BigDecimal("1000000"), "BANK_A-REF-1", Instant.now());
        var second = paymentService.apply(bank_a, bank_aVa, new BigDecimal("1000000"), "BANK_A-REF-1", Instant.now());
        assertThat(second.getId()).isEqualTo(first.getId());
    }

    @Test
    void closed_wrongAmountIsRejected() {
        createCharge(ChargeType.CLOSED, new BigDecimal("1000000"));
        assertThatThrownBy(() -> paymentService.apply(bank_a, bank_aVa, new BigDecimal("999"), "BANK_A-REF-1", Instant.now()))
                .isInstanceOf(InvalidPaymentException.class)
                .hasMessageContaining("exact amount");
    }

    @Test
    void closed_payingCancelledSiblingIsRejected() {
        createCharge(ChargeType.CLOSED, new BigDecimal("1000000"));
        paymentService.apply(bank_a, bank_aVa, new BigDecimal("1000000"), "BANK_A-REF-1", Instant.now());
        assertThatThrownBy(() -> paymentService.apply(bank_b, bank_bVa, new BigDecimal("1000000"), "BANK_B-REF-1", Instant.now()))
                .isInstanceOf(InvalidPaymentException.class)
                .hasMessageContaining("CANCELLED");
    }

    @Test
    void installment_sharesCumulativeAcrossBanksThenCompletes() {
        Charge charge = createCharge(ChargeType.INSTALLMENT, new BigDecimal("1000000"));

        paymentService.apply(bank_a, bank_aVa, new BigDecimal("400000"), "BANK_A-REF-1", Instant.now());
        Charge afterPartial = chargeRepository.findById(charge.getId()).orElseThrow();
        assertThat(afterPartial.getStatus()).isEqualTo(ChargeStatus.PARTIALLY_PAID);
        assertThat(afterPartial.getCumulativePaid()).isEqualByComparingTo("400000");

        // The other bank now sees the reduced remaining (shared cumulative).
        InquiryResult bank_bInquiry = inquiryService.inquire(bank_b, bank_bVa);
        assertThat(bank_bInquiry.remainingAmount()).isEqualByComparingTo("600000");

        paymentService.apply(bank_b, bank_bVa, new BigDecimal("600000"), "BANK_B-REF-1", Instant.now());
        Charge afterFull = chargeRepository.findById(charge.getId()).orElseThrow();
        assertThat(afterFull.getStatus()).isEqualTo(ChargeStatus.PAID);
        assertThat(vaStatus(bank_b.getId(), bank_bVa)).isEqualTo(VirtualAccountStatus.PAID);
        assertThat(vaStatus(bank_a.getId(), bank_aVa)).isEqualTo(VirtualAccountStatus.CANCELLED);
    }

    @Test
    void installment_overpaymentIsRejected() {
        createCharge(ChargeType.INSTALLMENT, new BigDecimal("1000000"));
        assertThatThrownBy(() -> paymentService.apply(bank_a, bank_aVa, new BigDecimal("1200000"), "BANK_A-REF-1", Instant.now()))
                .isInstanceOf(InvalidPaymentException.class)
                .hasMessageContaining("exceeds remaining");
    }

    @Test
    void open_accumulatesAndKeepsSiblingsOpen() {
        Charge charge = createCharge(ChargeType.OPEN, new BigDecimal("100000"));
        paymentService.apply(bank_a, bank_aVa, new BigDecimal("50000"), "BANK_A-REF-1", Instant.now());
        paymentService.apply(bank_a, bank_aVa, new BigDecimal("70000"), "BANK_A-REF-2", Instant.now());

        Charge reloaded = chargeRepository.findById(charge.getId()).orElseThrow();
        assertThat(reloaded.getCumulativePaid()).isEqualByComparingTo("120000");
        assertThat(reloaded.getStatus()).isEqualTo(ChargeStatus.ACTIVE);
        assertThat(vaStatus(bank_b.getId(), bank_bVa)).isEqualTo(VirtualAccountStatus.ACTIVE);
    }

    @Test
    void payment_pastExpiryIsRejectedBeforeReaperRuns() {
        // expiresAt is in the past but the reaper has not swept yet: charge and VA are still ACTIVE.
        createCharge(ChargeType.CLOSED, new BigDecimal("1000000"), Instant.now().minusSeconds(60));
        assertThatThrownBy(() -> paymentService.apply(bank_a, bank_aVa, new BigDecimal("1000000"), "BANK_A-REF-1", Instant.now()))
                .isInstanceOf(InvalidPaymentException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void inquiry_pastExpiryIsNotFound() {
        createCharge(ChargeType.CLOSED, new BigDecimal("1000000"), Instant.now().minusSeconds(60));
        assertThatThrownBy(() -> inquiryService.inquire(bank_a, bank_aVa))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void inquiry_unknownVaIsNotFound() {
        assertThatThrownBy(() -> inquiryService.inquire(bank_a, "9009999999"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void inquiry_cancelledSiblingIsNotFound() {
        createCharge(ChargeType.CLOSED, new BigDecimal("1000000"));
        paymentService.apply(bank_a, bank_aVa, new BigDecimal("1000000"), "BANK_A-REF-1", Instant.now());
        assertThatThrownBy(() -> inquiryService.inquire(bank_b, bank_bVa))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void reversal_reopensChargeAndReactivatesSiblings() {
        Charge charge = createCharge(ChargeType.CLOSED, new BigDecimal("1000000"));
        Instant payTime = Instant.now();
        paymentService.apply(bank_a, bank_aVa, new BigDecimal("1000000"), "BANK_A-REF-1", payTime);
        paymentService.reverse(bank_a, bank_aVa, "BANK_A-REF-1", new BigDecimal("1000000"), payTime.plusSeconds(60));

        Charge reloaded = chargeRepository.findById(charge.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ChargeStatus.ACTIVE);
        assertThat(reloaded.getCumulativePaid()).isEqualByComparingTo("0");
        assertThat(vaStatus(bank_a.getId(), bank_aVa)).isEqualTo(VirtualAccountStatus.ACTIVE);
        assertThat(vaStatus(bank_b.getId(), bank_bVa)).isEqualTo(VirtualAccountStatus.ACTIVE);
        // payable again
        assertThat(inquiryService.inquire(bank_a, bank_aVa).remainingAmount()).isEqualByComparingTo("1000000");
    }

    @Test
    void reversal_isIdempotent() {
        createCharge(ChargeType.CLOSED, new BigDecimal("1000000"));
        Instant payTime = Instant.now();
        paymentService.apply(bank_a, bank_aVa, new BigDecimal("1000000"), "BANK_A-REF-1", payTime);
        var first = paymentService.reverse(bank_a, bank_aVa, "BANK_A-REF-1", new BigDecimal("1000000"), payTime.plusSeconds(10));
        var second = paymentService.reverse(bank_a, bank_aVa, "BANK_A-REF-1", new BigDecimal("1000000"), payTime.plusSeconds(20));
        assertThat(second.getId()).isEqualTo(first.getId());
    }

    @Test
    void reversal_amountMismatchIsRejected() {
        createCharge(ChargeType.CLOSED, new BigDecimal("1000000"));
        Instant payTime = Instant.now();
        paymentService.apply(bank_a, bank_aVa, new BigDecimal("1000000"), "BANK_A-REF-1", payTime);
        assertThatThrownBy(() -> paymentService.reverse(bank_a, bank_aVa, "BANK_A-REF-1", new BigDecimal("999"), payTime.plusSeconds(10)))
                .isInstanceOf(InvalidPaymentException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void reversal_outsideWindowIsRejected() {
        createCharge(ChargeType.CLOSED, new BigDecimal("1000000"));
        Instant payTime = Instant.now();
        paymentService.apply(bank_a, bank_aVa, new BigDecimal("1000000"), "BANK_A-REF-1", payTime);
        assertThatThrownBy(() -> paymentService.reverse(bank_a, bank_aVa, "BANK_A-REF-1", new BigDecimal("1000000"), payTime.plus(Duration.ofMinutes(61))))
                .isInstanceOf(InvalidPaymentException.class)
                .hasMessageContaining("window");
    }
}
