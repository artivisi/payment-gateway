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
import com.artivisi.paymentgateway.entity.PayerPadding;
import com.artivisi.paymentgateway.entity.TransportProtocol;
import com.artivisi.paymentgateway.entity.VaAllocationStrategy;
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
    private EscrowAccount primary;
    private EscrowAccount altEscrow;
    private String primaryVa;
    private String altVa;
    private String payerKey;
    private String slotKey;

    private static EscrowAccountRequest escrowRequest(String code, String vaPrefix) {
        return new EscrowAccountRequest(code, "demo", HostingModel.SELF_HOSTED, TransportProtocol.REST_JSON,
                AuthScheme.PROPRIETARY, EscrowEnvironment.SANDBOX, null, null, null, null, null, null, null, null,
                "900900111", "Operator Settlement", "90099", vaPrefix, 10, null, null, null,
                VaAllocationStrategy.PAYER_ENCODED, 1, PayerPadding.RIGHT, "");
    }

    @BeforeEach
    void seed() {
        int n = SEQ.incrementAndGet();
        consumer = consumerService.create(new ConsumerRequest(
                "pl-consumer-" + n, "Academic", "pl-client-" + n, "secret-" + n,
                "https://hook.example/" + n, ConsumerStatus.ACTIVE));
        primary = escrowService.create(escrowRequest("pl-demo-" + n, "900"));
        altEscrow = escrowService.create(escrowRequest("pl-alt-" + n, "905"));
        // One payer, one slot, two banks: the gateway composes a number per escrow, each in
        // that escrow's own layout. The consumer never sees either layout.
        payerKey = String.format("%06d", n);
        slotKey = "0";
        primaryVa = "900" + slotKey + payerKey;
        altVa = "905" + slotKey + payerKey;
    }

    private Charge createCharge(ChargeType type, BigDecimal amount) {
        return createCharge(type, amount, null);
    }

    private Charge createCharge(ChargeType type, BigDecimal amount, Instant expiresAt) {
        var outcome = chargeService.create(consumer, new CreateChargeRequest(
                "ref-" + SEQ.get(), "Student", null, null, type, amount, expiresAt,
                List.of(new ChargeAccountRequest(primary.getCode()),
                        new ChargeAccountRequest(altEscrow.getCode())), null, null,
                payerKey, slotKey));
        return chargeRepository.findById(outcome.response().id()).orElseThrow();
    }

    private VirtualAccountStatus vaStatus(String escrowId, String vaNumber) {
        return virtualAccountRepository.findByEscrowAccountIdAndVaNumberOrderByCreatedAtDesc(escrowId, vaNumber)
                .getFirst().getStatus();
    }

    @Test
    void closed_firstPaymentPaysChargeAndCancelsSiblings() {
        Charge charge = createCharge(ChargeType.CLOSED, new BigDecimal("1000000"));

        paymentService.apply(primary, primaryVa, new BigDecimal("1000000"), "the primary escrow-REF-1", Instant.now());

        Charge reloaded = chargeRepository.findById(charge.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ChargeStatus.PAID);
        assertThat(reloaded.getCumulativePaid()).isEqualByComparingTo("1000000");
        assertThat(vaStatus(primary.getId(), primaryVa)).isEqualTo(VirtualAccountStatus.PAID);
        assertThat(vaStatus(altEscrow.getId(), altVa)).isEqualTo(VirtualAccountStatus.CANCELLED);
    }

    @Test
    void closed_replayedNotificationIsIdempotent() {
        createCharge(ChargeType.CLOSED, new BigDecimal("1000000"));
        var first = paymentService.apply(primary, primaryVa, new BigDecimal("1000000"), "the primary escrow-REF-1", Instant.now());
        var second = paymentService.apply(primary, primaryVa, new BigDecimal("1000000"), "the primary escrow-REF-1", Instant.now());
        assertThat(second.getId()).isEqualTo(first.getId());
    }

    @Test
    void closed_wrongAmountIsRejected() {
        createCharge(ChargeType.CLOSED, new BigDecimal("1000000"));
        assertThatThrownBy(() -> paymentService.apply(primary, primaryVa, new BigDecimal("999"), "the primary escrow-REF-1", Instant.now()))
                .isInstanceOf(InvalidPaymentException.class)
                .hasMessageContaining("exact amount");
    }

    @Test
    void closed_payingCancelledSiblingIsRejected() {
        createCharge(ChargeType.CLOSED, new BigDecimal("1000000"));
        paymentService.apply(primary, primaryVa, new BigDecimal("1000000"), "the primary escrow-REF-1", Instant.now());
        assertThatThrownBy(() -> paymentService.apply(altEscrow, altVa, new BigDecimal("1000000"), "the sibling escrow-REF-1", Instant.now()))
                .isInstanceOf(InvalidPaymentException.class)
                .hasMessageContaining("CANCELLED");
    }

    /**
     * A receivables application walks one VA through an instalment plan by repricing the same
     * CLOSED charge: the number the student holds never changes, the amount the bank answers does.
     */
    @Test
    void closed_repriceChangesWhatInquiryAnswers_andPaymentMustMatchTheNewAmount() {
        Charge charge = createCharge(ChargeType.CLOSED, new BigDecimal("2166667"));
        assertThat(inquiryService.inquire(primary, primaryVa).remainingAmount()).isEqualByComparingTo("2166667");

        chargeService.reprice(charge.getConsumer(), charge.getId(), new BigDecimal("4333334"));

        assertThat(inquiryService.inquire(primary, primaryVa).remainingAmount()).isEqualByComparingTo("4333334");
        assertThatThrownBy(() -> paymentService.apply(primary, primaryVa, new BigDecimal("2166667"), "the primary escrow-OLD", Instant.now()))
                .isInstanceOf(InvalidPaymentException.class)
                .hasMessageContaining("exact amount");
        paymentService.apply(primary, primaryVa, new BigDecimal("4333334"), "the primary escrow-NEW", Instant.now());
        assertThat(chargeRepository.findById(charge.getId()).orElseThrow().getStatus()).isEqualTo(ChargeStatus.PAID);
    }

    @Test
    void reprice_isRefusedOncePaidOrCancelled() {
        Charge charge = createCharge(ChargeType.CLOSED, new BigDecimal("1000000"));
        paymentService.apply(primary, primaryVa, new BigDecimal("1000000"), "the primary escrow-REF-1", Instant.now());
        assertThatThrownBy(() -> chargeService.reprice(charge.getConsumer(), charge.getId(), new BigDecimal("5")))
                .isInstanceOf(com.artivisi.paymentgateway.exception.InvalidRequestException.class)
                .hasMessageContaining("PAID");
    }

    @Test
    void open_accumulatesAndKeepsSiblingsOpen() {
        Charge charge = createCharge(ChargeType.OPEN, new BigDecimal("100000"));
        paymentService.apply(primary, primaryVa, new BigDecimal("50000"), "the primary escrow-REF-1", Instant.now());
        paymentService.apply(primary, primaryVa, new BigDecimal("70000"), "the primary escrow-REF-2", Instant.now());

        Charge reloaded = chargeRepository.findById(charge.getId()).orElseThrow();
        assertThat(reloaded.getCumulativePaid()).isEqualByComparingTo("120000");
        assertThat(reloaded.getStatus()).isEqualTo(ChargeStatus.ACTIVE);
        assertThat(vaStatus(altEscrow.getId(), altVa)).isEqualTo(VirtualAccountStatus.ACTIVE);
    }

    @Test
    void payment_pastExpiryIsRejectedBeforeReaperRuns() {
        // expiresAt is in the past but the reaper has not swept yet: charge and VA are still ACTIVE.
        createCharge(ChargeType.CLOSED, new BigDecimal("1000000"), Instant.now().minusSeconds(60));
        assertThatThrownBy(() -> paymentService.apply(primary, primaryVa, new BigDecimal("1000000"), "the primary escrow-REF-1", Instant.now()))
                .isInstanceOf(InvalidPaymentException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void inquiry_pastExpiryIsNotFound() {
        createCharge(ChargeType.CLOSED, new BigDecimal("1000000"), Instant.now().minusSeconds(60));
        assertThatThrownBy(() -> inquiryService.inquire(primary, primaryVa))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void inquiry_unknownVaIsNotFound() {
        assertThatThrownBy(() -> inquiryService.inquire(primary, "9009999999"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void inquiry_cancelledSiblingIsNotFound() {
        createCharge(ChargeType.CLOSED, new BigDecimal("1000000"));
        paymentService.apply(primary, primaryVa, new BigDecimal("1000000"), "the primary escrow-REF-1", Instant.now());
        assertThatThrownBy(() -> inquiryService.inquire(altEscrow, altVa))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void reversal_reopensChargeAndReactivatesSiblings() {
        Charge charge = createCharge(ChargeType.CLOSED, new BigDecimal("1000000"));
        Instant payTime = Instant.now();
        paymentService.apply(primary, primaryVa, new BigDecimal("1000000"), "the primary escrow-REF-1", payTime);
        paymentService.reverse(primary, primaryVa, "the primary escrow-REF-1", new BigDecimal("1000000"), payTime.plusSeconds(60));

        Charge reloaded = chargeRepository.findById(charge.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ChargeStatus.ACTIVE);
        assertThat(reloaded.getCumulativePaid()).isEqualByComparingTo("0");
        assertThat(vaStatus(primary.getId(), primaryVa)).isEqualTo(VirtualAccountStatus.ACTIVE);
        assertThat(vaStatus(altEscrow.getId(), altVa)).isEqualTo(VirtualAccountStatus.ACTIVE);
        // payable again
        assertThat(inquiryService.inquire(primary, primaryVa).remainingAmount()).isEqualByComparingTo("1000000");
    }

    @Test
    void reversal_isIdempotent() {
        createCharge(ChargeType.CLOSED, new BigDecimal("1000000"));
        Instant payTime = Instant.now();
        paymentService.apply(primary, primaryVa, new BigDecimal("1000000"), "the primary escrow-REF-1", payTime);
        var first = paymentService.reverse(primary, primaryVa, "the primary escrow-REF-1", new BigDecimal("1000000"), payTime.plusSeconds(10));
        var second = paymentService.reverse(primary, primaryVa, "the primary escrow-REF-1", new BigDecimal("1000000"), payTime.plusSeconds(20));
        assertThat(second.getId()).isEqualTo(first.getId());
    }

    @Test
    void reversal_amountMismatchIsRejected() {
        createCharge(ChargeType.CLOSED, new BigDecimal("1000000"));
        Instant payTime = Instant.now();
        paymentService.apply(primary, primaryVa, new BigDecimal("1000000"), "the primary escrow-REF-1", payTime);
        assertThatThrownBy(() -> paymentService.reverse(primary, primaryVa, "the primary escrow-REF-1", new BigDecimal("999"), payTime.plusSeconds(10)))
                .isInstanceOf(InvalidPaymentException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void reversal_outsideWindowIsRejected() {
        createCharge(ChargeType.CLOSED, new BigDecimal("1000000"));
        Instant payTime = Instant.now();
        paymentService.apply(primary, primaryVa, new BigDecimal("1000000"), "the primary escrow-REF-1", payTime);
        assertThatThrownBy(() -> paymentService.reverse(primary, primaryVa, "the primary escrow-REF-1", new BigDecimal("1000000"), payTime.plus(Duration.ofMinutes(61))))
                .isInstanceOf(InvalidPaymentException.class)
                .hasMessageContaining("window");
    }
}
