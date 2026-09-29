package com.artivisi.paymentgateway.adapter.demo;

import com.artivisi.paymentgateway.AbstractIntegrationTest;
import com.artivisi.paymentgateway.dto.ChargeAccountRequest;
import com.artivisi.paymentgateway.dto.ConsumerRequest;
import com.artivisi.paymentgateway.dto.CreateChargeRequest;
import com.artivisi.paymentgateway.dto.EscrowAccountRequest;
import com.artivisi.paymentgateway.entity.AuthScheme;
import com.artivisi.paymentgateway.entity.ChargeType;
import com.artivisi.paymentgateway.entity.Consumer;
import com.artivisi.paymentgateway.entity.ConsumerStatus;
import com.artivisi.paymentgateway.entity.EscrowEnvironment;
import com.artivisi.paymentgateway.entity.HostingModel;
import com.artivisi.paymentgateway.entity.TransportProtocol;
import com.artivisi.paymentgateway.repository.PaymentRepository;
import com.artivisi.paymentgateway.service.ChargeService;
import com.artivisi.paymentgateway.service.ConsumerService;
import com.artivisi.paymentgateway.service.EscrowAccountService;
import io.restassured.config.JsonConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.path.json.config.JsonPathConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * The whole bank-facing path, end to end, against the invented bank this repository ships.
 *
 * <p>Every real adapter's tests live with that adapter in the private assembly, so these are the
 * public repository's proof that the engine behind them works: inquiry answers what is owed, a
 * payment settles the charge, the amount rule is enforced, a bad signature is refused, and the raw
 * message is kept whatever happens.
 */
class DemoBankAdapterIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String SECRET = "demo-shared-secret";
    private static final String WHEN = "20260624100000";
    private static final BigDecimal AMOUNT = new BigDecimal("1000000");

    @Autowired PaymentRepository paymentRepository;
    @Autowired com.artivisi.paymentgateway.repository.BankCallbackRepository callbackRepository;
    @Autowired EscrowAccountService escrowService;
    @Autowired ConsumerService consumerService;
    @Autowired ChargeService chargeService;

    private String vaNumber;
    private String billNumber;

    @BeforeEach
    void seed() {
        int n = SEQ.incrementAndGet();
        String escrowCode = "demo-adp-" + n;
        vaNumber = "9200000" + String.format("%03d", n);
        billNumber = "2026070900" + String.format("%06d", n);
        Consumer consumer = consumerService.create(new ConsumerRequest(
                "demoadp-consumer-" + n, "Academic", "demoadp-client-" + n, "secret-" + n,
                "https://hook.example/" + n, ConsumerStatus.ACTIVE));
        escrowService.create(new EscrowAccountRequest(escrowCode, "demo", HostingModel.SELF_HOSTED,
                TransportProtocol.REST_JSON, AuthScheme.PROPRIETARY, EscrowEnvironment.SANDBOX,
                "demo-client", SECRET, null, null, null, null, null, null,
                "920900111", "Operator Settlement", "92099", "920", 10, null, null));
        chargeService.create(consumer, new CreateChargeRequest(
                "demoadp-ref-" + n, "Student", null, null, ChargeType.CLOSED, AMOUNT, null,
                List.of(new ChargeAccountRequest(escrowCode, vaNumber)), "SPP Semester Ganjil", billNumber));
    }

    private Map<String, Object> body(String action, String va, BigDecimal amount, String signature) {
        Map<String, Object> m = new HashMap<>();
        m.put("action", action);
        m.put("accountNumber", va);
        m.put("transactionId", "TRX-" + va);
        m.put("transactionTime", WHEN);
        if (amount != null) {
            m.put("amount", amount.toPlainString());
        }
        m.put("signature", signature);
        return m;
    }

    private String sign(String va, BigDecimal amount) {
        return DemoBankSignature.compute(new DemoBankMessages.Request(
                null, va, amount == null ? null : amount.toPlainString(), null, WHEN, null), SECRET);
    }

    /**
     * Payments on this test's VA, read through a query with its account fetched. Walking
     * {@code payment.getVirtualAccount()} from a findAll() would fail: open-in-view is off, so the
     * association is unreadable once the request that loaded it is over.
     */
    private List<com.artivisi.paymentgateway.entity.Payment> paymentsOnTheVa() {
        return paymentRepository.findAllWithVa().stream()
                .filter(p -> p.getVirtualAccount().getVaNumber().equals(vaNumber))
                .toList();
    }

    private io.restassured.response.ValidatableResponse post(Map<String, Object> body) {
        return given()
                .config(RestAssuredConfig.config().jsonConfig(
                        JsonConfig.jsonConfig().numberReturnType(JsonPathConfig.NumberReturnType.BIG_DECIMAL)))
                .contentType("application/json").body(body)
                .when().post("/api/bank/demo").then();
    }

    @Test
    void inquiryAnswersWhatIsStillOwed() {
        post(body("inquiry", vaNumber, null, sign(vaNumber, null)))
                .statusCode(200)
                .body("responseCode", equalTo("00"))
                .body("billNumber", equalTo(billNumber))
                .body("totalAmount", comparesEqualTo(AMOUNT))
                .body("remainingAmount", comparesEqualTo(AMOUNT));
    }

    @Test
    void paymentSettlesTheChargeAndIsRecordedOnce() {
        post(body("payment", vaNumber, AMOUNT, sign(vaNumber, AMOUNT)))
                .statusCode(200)
                .body("responseCode", equalTo("00"))
                .body("paymentReference", notNullValue())
                .body("remainingAmount", comparesEqualTo(BigDecimal.ZERO));

        assertThat(paymentsOnTheVa()).as("one payment, recorded exactly once").hasSize(1);

        // The bill is settled, so the number stops answering: the next inquiry is not payable.
        post(body("inquiry", vaNumber, null, sign(vaNumber, null)))
                .statusCode(200)
                .body("responseCode", equalTo("14"));
    }

    @Test
    void aClosedBillRefusesAnyAmountButTheExactOne() {
        BigDecimal short_ = new BigDecimal("999999");
        post(body("payment", vaNumber, short_, sign(vaNumber, short_)))
                .statusCode(200)
                .body("responseCode", equalTo("13"));

        assertThat(paymentsOnTheVa())
                .as("a part payment on a closed bill must not be recorded at all").isEmpty();
    }

    @Test
    void aWrongSignatureIsRefusedBeforeAnythingHappens() {
        post(body("payment", vaNumber, AMOUNT, "0".repeat(64)))
                .statusCode(200)
                .body("responseCode", equalTo("63"));

        assertThat(paymentsOnTheVa()).isEmpty();
    }

    @Test
    void anUnknownAccountIsTheBanksAnswerNotAStackTrace() {
        post(body("inquiry", "9299999999", null, sign("9299999999", null)))
                .statusCode(200)
                .body("responseCode", equalTo("14"));
    }

    @Test
    void theRawMessageIsKeptEvenWhenTheAnswerIsARefusal() {
        long before = callbackRepository.count();
        post(body("payment", vaNumber, AMOUNT, "0".repeat(64))).statusCode(200);
        assertThat(callbackRepository.count())
                .as("what the bank sent is a fact worth keeping whatever we made of it")
                .isEqualTo(before + 1);
    }
}
