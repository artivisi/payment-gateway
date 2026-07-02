package com.artivisi.paymentgateway.service;

import com.artivisi.paymentgateway.AbstractIntegrationTest;
import com.artivisi.paymentgateway.dto.ChargeAccountRequest;
import com.artivisi.paymentgateway.dto.ConsumerRequest;
import com.artivisi.paymentgateway.dto.CreateChargeRequest;
import com.artivisi.paymentgateway.dto.EscrowAccountRequest;
import com.artivisi.paymentgateway.entity.AuthScheme;
import com.artivisi.paymentgateway.entity.Charge;
import com.artivisi.paymentgateway.entity.ChargeStatus;
import com.artivisi.paymentgateway.entity.ChargeType;
import com.artivisi.paymentgateway.entity.Consumer;
import com.artivisi.paymentgateway.entity.ConsumerStatus;
import com.artivisi.paymentgateway.entity.EscrowAccount;
import com.artivisi.paymentgateway.entity.EscrowEnvironment;
import com.artivisi.paymentgateway.entity.HostingModel;
import com.artivisi.paymentgateway.entity.Payment;
import com.artivisi.paymentgateway.entity.PaymentStatus;
import com.artivisi.paymentgateway.entity.TransportProtocol;
import com.artivisi.paymentgateway.entity.VirtualAccountStatus;
import com.artivisi.paymentgateway.exception.InvalidPaymentException;
import com.artivisi.paymentgateway.repository.ChargeRepository;
import com.artivisi.paymentgateway.repository.PaymentRepository;
import com.artivisi.paymentgateway.repository.VirtualAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Races concurrent bank notifications against the single-debt invariant. The pessimistic lock on
 * the charge must serialize sibling payments: exactly one full payment settles a CLOSED charge,
 * concurrent partials never lose an update, and a replayed notification never double-counts.
 */
class PaymentConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired EscrowAccountService escrowService;
    @Autowired ConsumerService consumerService;
    @Autowired ChargeService chargeService;
    @Autowired PaymentApplicationService paymentService;
    @Autowired ChargeRepository chargeRepository;
    @Autowired VirtualAccountRepository virtualAccountRepository;
    @Autowired PaymentRepository paymentRepository;

    private Consumer consumer;
    private EscrowAccount bsi;
    private EscrowAccount cimb;
    private String bsiVa;
    private String cimbVa;

    private static EscrowAccountRequest escrowRequest(String code) {
        return new EscrowAccountRequest(code, "bsi", HostingModel.SELF_HOSTED, TransportProtocol.REST_JSON,
                AuthScheme.PROPRIETARY, EscrowEnvironment.SANDBOX, null, null, null, null, null, null, null, null,
                "910900111", "Operator Settlement", "91099", "910", 10, null, null);
    }

    @BeforeEach
    void seed() {
        int n = SEQ.incrementAndGet();
        consumer = consumerService.create(new ConsumerRequest(
                "race-consumer-" + n, "Academic", "race-client-" + n, "secret-" + n,
                "https://hook.example/" + n, ConsumerStatus.ACTIVE));
        bsi = escrowService.create(escrowRequest("race-bsi-" + n));
        cimb = escrowService.create(escrowRequest("race-cimb-" + n));
        bsiVa = "910000000" + (n % 10);
        cimbVa = "910000001" + (n % 10);
    }

    private Charge createCharge(ChargeType type, BigDecimal amount) {
        var outcome = chargeService.create(consumer, new CreateChargeRequest(
                "race-ref-" + SEQ.get(), "Student", null, null, type, amount, null,
                List.of(new ChargeAccountRequest(bsi.getCode(), bsiVa),
                        new ChargeAccountRequest(cimb.getCode(), cimbVa))));
        return chargeRepository.findById(outcome.response().id()).orElseThrow();
    }

    private record Outcome(Payment payment, RuntimeException error) {
    }

    /** Runs the calls on their own threads, released simultaneously, and collects each outcome. */
    private List<Outcome> race(List<Callable<Payment>> calls) throws Exception {
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Callable<Payment> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return new Outcome(call.call(), null);
                    } catch (RuntimeException e) {
                        return new Outcome(null, e);
                    }
                }));
            }
            ready.await();
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get());
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void closed_simultaneousFullPaymentsAtTwoBanks_exactlyOneSettles() throws Exception {
        BigDecimal amount = new BigDecimal("1000000");
        Charge charge = createCharge(ChargeType.CLOSED, amount);

        List<Outcome> outcomes = race(List.of(
                () -> paymentService.apply(bsi, bsiVa, amount, "RACE-BSI-1", Instant.now()),
                () -> paymentService.apply(cimb, cimbVa, amount, "RACE-CIMB-1", Instant.now())));

        assertThat(outcomes).filteredOn(o -> o.payment() != null).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o.error() != null).singleElement()
                .satisfies(o -> assertThat(o.error()).isInstanceOf(InvalidPaymentException.class));

        Charge reloaded = chargeRepository.findById(charge.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ChargeStatus.PAID);
        assertThat(reloaded.getCumulativePaid()).isEqualByComparingTo(amount);
        assertThat(paymentRepository.findByChargeIdWithVa(charge.getId()))
                .hasSize(1)
                .allSatisfy(p -> assertThat(p.getStatus()).isEqualTo(PaymentStatus.ACCEPTED));
    }

    @Test
    void installment_simultaneousPartialsAtTwoBanks_neverLoseAnUpdate() throws Exception {
        Charge charge = createCharge(ChargeType.INSTALLMENT, new BigDecimal("1000000"));

        List<Outcome> outcomes = race(List.of(
                () -> paymentService.apply(bsi, bsiVa, new BigDecimal("300000"), "RACE-BSI-2", Instant.now()),
                () -> paymentService.apply(cimb, cimbVa, new BigDecimal("400000"), "RACE-CIMB-2", Instant.now())));

        assertThat(outcomes).filteredOn(o -> o.payment() != null).hasSize(2);

        Charge reloaded = chargeRepository.findById(charge.getId()).orElseThrow();
        assertThat(reloaded.getCumulativePaid()).isEqualByComparingTo("700000");
        assertThat(reloaded.getStatus()).isEqualTo(ChargeStatus.PARTIALLY_PAID);
        assertThat(virtualAccountRepository.findByChargeId(charge.getId()))
                .allSatisfy(va -> assertThat(va.getStatus()).isEqualTo(VirtualAccountStatus.ACTIVE));
    }

    @Test
    void closed_replayRacingItself_neverDoubleCounts() throws Exception {
        BigDecimal amount = new BigDecimal("1000000");
        Charge charge = createCharge(ChargeType.CLOSED, amount);

        Callable<Payment> replay =
                () -> paymentService.apply(bsi, bsiVa, amount, "RACE-REPLAY-1", Instant.now());
        List<Outcome> outcomes = race(List.of(replay, replay));

        // The replay either returns the recorded payment or is rejected — never applied twice.
        assertThat(outcomes).filteredOn(o -> o.payment() != null).isNotEmpty();

        Charge reloaded = chargeRepository.findById(charge.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ChargeStatus.PAID);
        assertThat(reloaded.getCumulativePaid()).isEqualByComparingTo(amount);
        assertThat(paymentRepository.findByChargeIdWithVa(charge.getId())).hasSize(1);
    }

    @Test
    void open_replayRacingItself_uniqueConstraintPreventsDoubleCount() throws Exception {
        Charge charge = createCharge(ChargeType.OPEN, new BigDecimal("100000"));

        Callable<Payment> replay =
                () -> paymentService.apply(bsi, bsiVa, new BigDecimal("50000"), "RACE-REPLAY-2", Instant.now());
        List<Outcome> outcomes = race(List.of(replay, replay));

        // OPEN accumulates without an already-paid guard, so the (VA, bankReference) unique
        // constraint is the last line of defense against a racing replay.
        assertThat(outcomes).filteredOn(o -> o.payment() != null).isNotEmpty();

        Charge reloaded = chargeRepository.findById(charge.getId()).orElseThrow();
        assertThat(reloaded.getCumulativePaid()).isEqualByComparingTo("50000");
        assertThat(paymentRepository.findByChargeIdWithVa(charge.getId())).hasSize(1);
    }
}
