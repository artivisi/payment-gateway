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
    private EscrowAccount bsi;
    private EscrowAccount cimb;
    private String bsiVa;
    private String cimbVa;

    private static EscrowAccountRequest escrowRequest(String code) {
        return new EscrowAccountRequest(code, "bsi", HostingModel.SELF_HOSTED, TransportProtocol.REST_JSON,
                AuthScheme.PROPRIETARY, EscrowEnvironment.SANDBOX, null, null, null, null, null, null, null, null,
                "900900111", "Operator Settlement", "90099", "900", 10, null, null);
    }

    @BeforeEach
    void seed() {
        int n = SEQ.incrementAndGet();
        consumer = consumerService.create(new ConsumerRequest(
                "pl-consumer-" + n, "Academic", "pl-client-" + n, "secret-" + n,
                "https://hook.example/" + n, ConsumerStatus.ACTIVE));
        bsi = escrowService.create(escrowRequest("pl-bsi-" + n));
        cimb = escrowService.create(escrowRequest("pl-cimb-" + n));
        bsiVa = "900000000" + (n % 10);
        cimbVa = "900000001" + (n % 10);
    }

    private Charge createCharge(ChargeType type, BigDecimal amount) {
        var outcome = chargeService.create(consumer, new CreateChargeRequest(
                "ref-" + SEQ.get(), "Student", null, null, type, amount, null,
                List.of(new ChargeAccountRequest(bsi.getCode(), bsiVa),
                        new ChargeAccountRequest(cimb.getCode(), cimbVa))));
        return chargeRepository.findById(outcome.response().id()).orElseThrow();
    }

    private VirtualAccountStatus vaStatus(String escrowId, String vaNumber) {
        return virtualAccountRepository.findByEscrowAccountIdAndVaNumber(escrowId, vaNumber)
                .orElseThrow().getStatus();
    }

    @Test
    void closed_firstPaymentPaysChargeAndCancelsSiblings() {
        Charge charge = createCharge(ChargeType.CLOSED, new BigDecimal("1000000"));

        paymentService.apply(bsi, bsiVa, new BigDecimal("1000000"), "BSI-REF-1", Instant.now());

        Charge reloaded = chargeRepository.findById(charge.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ChargeStatus.PAID);
        assertThat(reloaded.getCumulativePaid()).isEqualByComparingTo("1000000");
        assertThat(vaStatus(bsi.getId(), bsiVa)).isEqualTo(VirtualAccountStatus.PAID);
        assertThat(vaStatus(cimb.getId(), cimbVa)).isEqualTo(VirtualAccountStatus.CANCELLED);
    }

    @Test
    void closed_replayedNotificationIsIdempotent() {
        createCharge(ChargeType.CLOSED, new BigDecimal("1000000"));
        var first = paymentService.apply(bsi, bsiVa, new BigDecimal("1000000"), "BSI-REF-1", Instant.now());
        var second = paymentService.apply(bsi, bsiVa, new BigDecimal("1000000"), "BSI-REF-1", Instant.now());
        assertThat(second.getId()).isEqualTo(first.getId());
    }

    @Test
    void closed_wrongAmountIsRejected() {
        createCharge(ChargeType.CLOSED, new BigDecimal("1000000"));
        assertThatThrownBy(() -> paymentService.apply(bsi, bsiVa, new BigDecimal("999"), "BSI-REF-1", Instant.now()))
                .isInstanceOf(InvalidPaymentException.class)
                .hasMessageContaining("exact amount");
    }

    @Test
    void closed_payingCancelledSiblingIsRejected() {
        createCharge(ChargeType.CLOSED, new BigDecimal("1000000"));
        paymentService.apply(bsi, bsiVa, new BigDecimal("1000000"), "BSI-REF-1", Instant.now());
        assertThatThrownBy(() -> paymentService.apply(cimb, cimbVa, new BigDecimal("1000000"), "CIMB-REF-1", Instant.now()))
                .isInstanceOf(InvalidPaymentException.class)
                .hasMessageContaining("CANCELLED");
    }

    @Test
    void installment_sharesCumulativeAcrossBanksThenCompletes() {
        Charge charge = createCharge(ChargeType.INSTALLMENT, new BigDecimal("1000000"));

        paymentService.apply(bsi, bsiVa, new BigDecimal("400000"), "BSI-REF-1", Instant.now());
        Charge afterPartial = chargeRepository.findById(charge.getId()).orElseThrow();
        assertThat(afterPartial.getStatus()).isEqualTo(ChargeStatus.PARTIALLY_PAID);
        assertThat(afterPartial.getCumulativePaid()).isEqualByComparingTo("400000");

        // The other bank now sees the reduced remaining (shared cumulative).
        InquiryResult cimbInquiry = inquiryService.inquire(cimb, cimbVa);
        assertThat(cimbInquiry.remainingAmount()).isEqualByComparingTo("600000");

        paymentService.apply(cimb, cimbVa, new BigDecimal("600000"), "CIMB-REF-1", Instant.now());
        Charge afterFull = chargeRepository.findById(charge.getId()).orElseThrow();
        assertThat(afterFull.getStatus()).isEqualTo(ChargeStatus.PAID);
        assertThat(vaStatus(cimb.getId(), cimbVa)).isEqualTo(VirtualAccountStatus.PAID);
        assertThat(vaStatus(bsi.getId(), bsiVa)).isEqualTo(VirtualAccountStatus.CANCELLED);
    }

    @Test
    void installment_overpaymentIsRejected() {
        createCharge(ChargeType.INSTALLMENT, new BigDecimal("1000000"));
        assertThatThrownBy(() -> paymentService.apply(bsi, bsiVa, new BigDecimal("1200000"), "BSI-REF-1", Instant.now()))
                .isInstanceOf(InvalidPaymentException.class)
                .hasMessageContaining("exceeds remaining");
    }

    @Test
    void open_accumulatesAndKeepsSiblingsOpen() {
        Charge charge = createCharge(ChargeType.OPEN, new BigDecimal("100000"));
        paymentService.apply(bsi, bsiVa, new BigDecimal("50000"), "BSI-REF-1", Instant.now());
        paymentService.apply(bsi, bsiVa, new BigDecimal("70000"), "BSI-REF-2", Instant.now());

        Charge reloaded = chargeRepository.findById(charge.getId()).orElseThrow();
        assertThat(reloaded.getCumulativePaid()).isEqualByComparingTo("120000");
        assertThat(reloaded.getStatus()).isEqualTo(ChargeStatus.ACTIVE);
        assertThat(vaStatus(cimb.getId(), cimbVa)).isEqualTo(VirtualAccountStatus.ACTIVE);
    }

    @Test
    void inquiry_unknownVaIsNotFound() {
        assertThatThrownBy(() -> inquiryService.inquire(bsi, "9009999999"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void inquiry_cancelledSiblingIsNotFound() {
        createCharge(ChargeType.CLOSED, new BigDecimal("1000000"));
        paymentService.apply(bsi, bsiVa, new BigDecimal("1000000"), "BSI-REF-1", Instant.now());
        assertThatThrownBy(() -> inquiryService.inquire(cimb, cimbVa))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void reversal_reopensChargeAndReactivatesSiblings() {
        Charge charge = createCharge(ChargeType.CLOSED, new BigDecimal("1000000"));
        Instant payTime = Instant.now();
        paymentService.apply(bsi, bsiVa, new BigDecimal("1000000"), "BSI-REF-1", payTime);
        paymentService.reverse(bsi, bsiVa, "BSI-REF-1", new BigDecimal("1000000"), payTime.plusSeconds(60));

        Charge reloaded = chargeRepository.findById(charge.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ChargeStatus.ACTIVE);
        assertThat(reloaded.getCumulativePaid()).isEqualByComparingTo("0");
        assertThat(vaStatus(bsi.getId(), bsiVa)).isEqualTo(VirtualAccountStatus.ACTIVE);
        assertThat(vaStatus(cimb.getId(), cimbVa)).isEqualTo(VirtualAccountStatus.ACTIVE);
        // payable again
        assertThat(inquiryService.inquire(bsi, bsiVa).remainingAmount()).isEqualByComparingTo("1000000");
    }

    @Test
    void reversal_isIdempotent() {
        createCharge(ChargeType.CLOSED, new BigDecimal("1000000"));
        Instant payTime = Instant.now();
        paymentService.apply(bsi, bsiVa, new BigDecimal("1000000"), "BSI-REF-1", payTime);
        var first = paymentService.reverse(bsi, bsiVa, "BSI-REF-1", new BigDecimal("1000000"), payTime.plusSeconds(10));
        var second = paymentService.reverse(bsi, bsiVa, "BSI-REF-1", new BigDecimal("1000000"), payTime.plusSeconds(20));
        assertThat(second.getId()).isEqualTo(first.getId());
    }

    @Test
    void reversal_amountMismatchIsRejected() {
        createCharge(ChargeType.CLOSED, new BigDecimal("1000000"));
        Instant payTime = Instant.now();
        paymentService.apply(bsi, bsiVa, new BigDecimal("1000000"), "BSI-REF-1", payTime);
        assertThatThrownBy(() -> paymentService.reverse(bsi, bsiVa, "BSI-REF-1", new BigDecimal("999"), payTime.plusSeconds(10)))
                .isInstanceOf(InvalidPaymentException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void reversal_outsideWindowIsRejected() {
        createCharge(ChargeType.CLOSED, new BigDecimal("1000000"));
        Instant payTime = Instant.now();
        paymentService.apply(bsi, bsiVa, new BigDecimal("1000000"), "BSI-REF-1", payTime);
        assertThatThrownBy(() -> paymentService.reverse(bsi, bsiVa, "BSI-REF-1", new BigDecimal("1000000"), payTime.plus(Duration.ofMinutes(61))))
                .isInstanceOf(InvalidPaymentException.class)
                .hasMessageContaining("window");
    }
}
