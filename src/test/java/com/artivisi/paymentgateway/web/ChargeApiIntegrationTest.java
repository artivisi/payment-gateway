package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.AbstractIntegrationTest;
import com.artivisi.paymentgateway.dto.ConsumerRequest;
import com.artivisi.paymentgateway.dto.EscrowAccountRequest;
import com.artivisi.paymentgateway.entity.AuthScheme;
import com.artivisi.paymentgateway.entity.ConsumerStatus;
import com.artivisi.paymentgateway.entity.EscrowEnvironment;
import com.artivisi.paymentgateway.entity.HostingModel;
import com.artivisi.paymentgateway.entity.PayerPadding;
import com.artivisi.paymentgateway.entity.TransportProtocol;
import com.artivisi.paymentgateway.entity.VaAllocationStrategy;
import com.artivisi.paymentgateway.service.ConsumerService;
import com.artivisi.paymentgateway.entity.WebhookEventType;
import com.artivisi.paymentgateway.repository.WebhookDeliveryRepository;
import com.artivisi.paymentgateway.service.EscrowAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;

class ChargeApiIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired EscrowAccountService escrowService;
    @Autowired ConsumerService consumerService;
    @Autowired WebhookDeliveryRepository webhookDeliveryRepository;

    private String clientId;
    private String clientSecret;
    private String primaryCode;
    private String altCode;
    // One payer, one slot, two escrows that happen to share a layout — so both numbers come out
    // the same, which is exactly what a payer wants: the digits they saved work at either bank.
    private final String payerKey = "000001";
    private final String slotKey = "0";
    private final String expectedVa = "9000000001";

    private static EscrowAccountRequest escrowRequest(String code) {
        return new EscrowAccountRequest(code, "demo", HostingModel.SELF_HOSTED, TransportProtocol.REST_JSON,
                AuthScheme.PROPRIETARY, EscrowEnvironment.SANDBOX, null, null, null, null, null, null, null, null,
                "900900111", "Operator Settlement", "90099", "900", 10, null, null, null,
                VaAllocationStrategy.PAYER_ENCODED, 1, PayerPadding.RIGHT, "");
    }

    @BeforeEach
    void seed() {
        int n = SEQ.incrementAndGet();
        clientId = "api-client-" + n;
        clientSecret = "secret-" + n;
        primaryCode = "api-demo-" + n;
        altCode = "api-alt-" + n;
        consumerService.create(new ConsumerRequest(
                "api-consumer-" + n, "Academic", clientId, clientSecret, "https://hook.example/" + n, ConsumerStatus.ACTIVE));
        escrowService.create(escrowRequest(primaryCode));
        escrowService.create(escrowRequest(altCode));
    }

    private Map<String, Object> chargeBody(String reference) {
        return chargeBody(reference, payerKey, slotKey);
    }

    private Map<String, Object> chargeBody(String reference, String payer, String slot) {
        return Map.of(
                "consumerReference", reference,
                "payerName", "Student",
                "chargeType", "CLOSED",
                "amount", 1000000,
                "payerKey", payer,
                "slotKey", slot,
                "accounts", List.of(
                        Map.of("escrowCode", primaryCode),
                        Map.of("escrowCode", altCode)));
    }

    @Test
    void createCharge_fansOutAcrossEscrows() {
        given().header("X-Client-Id", clientId).header("X-Client-Secret", clientSecret)
                .contentType("application/json").body(chargeBody("ref-1"))
                .when().post("/api/charges")
                .then().statusCode(201)
                .body("id", notNullValue())
                .body("status", equalTo("ACTIVE"))
                .body("accounts.size()", equalTo(2))
                .body("accounts.escrowCode", hasItems(primaryCode, altCode))
                .body("accounts.status", hasItems("ACTIVE"))
                // The gateway composed these; the consumer sent no number at all.
                .body("accounts.vaNumber", hasItems(expectedVa))
                .body("accounts.interbankVaNumber", hasItems(expectedVa));
    }

    @Test
    void createCharge_isIdempotentOnConsumerReference() {
        String id = given().header("X-Client-Id", clientId).header("X-Client-Secret", clientSecret)
                .contentType("application/json").body(chargeBody("ref-dup"))
                .when().post("/api/charges").then().statusCode(201).extract().path("id");

        given().header("X-Client-Id", clientId).header("X-Client-Secret", clientSecret)
                .contentType("application/json").body(chargeBody("ref-dup"))
                .when().post("/api/charges")
                .then().statusCode(200).body("id", equalTo(id));
    }

    @Test
    void createCharge_withoutCredentials_is401() {
        given().contentType("application/json").body(chargeBody("ref-1"))
                .when().post("/api/charges").then().statusCode(401);
    }

    @Test
    void createCharge_withWrongSecret_is401() {
        given().header("X-Client-Id", clientId).header("X-Client-Secret", "wrong")
                .contentType("application/json").body(chargeBody("ref-1"))
                .when().post("/api/charges").then().statusCode(401);
    }

    @Test
    void createCharge_withAPayerKeyTooLongForTheSpace_is400() {
        // Seven digits where the layout leaves six. Truncating would compose a well-formed number
        // belonging to a different payer, so it is refused instead.
        given().header("X-Client-Id", clientId).header("X-Client-Secret", clientSecret)
                .contentType("application/json").body(chargeBody("ref-long", "0000012", slotKey))
                .when().post("/api/charges").then().statusCode(400)
                // Coded, because this is the refusal a consumer passes back to its own sender, and it
                // has to tell it apart from an escrow that cannot allocate at all.
                .body("code", equalTo("KEY_UNENCODABLE"))
                .body("field", equalTo("payerKey"))
                .body("escrowCode", notNullValue());
    }

    /**
     * The conflict a consumer reads fields out of, not prose. It cannot compose the number any more,
     * so this response is where it learns the number and which of its own bills is holding it.
     */
    @Test
    void createCharge_onANumberAlreadyActive_is409WithTheNumberAndTheHoldingReference() {
        given().header("X-Client-Id", clientId).header("X-Client-Secret", clientSecret)
                .contentType("application/json").body(chargeBody("ref-busy-first"))
                .when().post("/api/charges").then().statusCode(201);

        given().header("X-Client-Id", clientId).header("X-Client-Secret", clientSecret)
                .contentType("application/json").body(chargeBody("ref-busy-second"))
                .when().post("/api/charges")
                .then().statusCode(409)
                .body("code", equalTo("VA_NUMBER_BUSY"))
                .body("vaNumber", equalTo(expectedVa))
                .body("escrowCode", notNullValue())
                .body("heldByConsumerReference", equalTo("ref-busy-first"));
    }

    @Test
    void createCharge_withANonNumericPayerKey_is400() {
        given().header("X-Client-Id", clientId).header("X-Client-Secret", clientSecret)
                .contentType("application/json").body(chargeBody("ref-alpha", "00A001", slotKey))
                .when().post("/api/charges").then().statusCode(400)
                .body("code", equalTo("KEY_UNENCODABLE"))
                .body("field", equalTo("payerKey"));
    }

    @Test
    void getAndCancelCharge() {
        String id = given().header("X-Client-Id", clientId).header("X-Client-Secret", clientSecret)
                .contentType("application/json").body(chargeBody("ref-cancel"))
                .when().post("/api/charges").then().statusCode(201).extract().path("id");

        given().header("X-Client-Id", clientId).header("X-Client-Secret", clientSecret)
                .when().get("/api/charges/{id}", id).then().statusCode(200).body("status", equalTo("ACTIVE"));

        given().header("X-Client-Id", clientId).header("X-Client-Secret", clientSecret)
                .when().post("/api/charges/{id}/cancel", id)
                .then().statusCode(200)
                .body("status", equalTo("CANCELLED"))
                .body("accounts.status", hasItems("CANCELLED"));
    }

    /**
     * A consumer's charge mirror is derived from this event, so the cancellation has to be announced
     * even when the consumer asked for it — otherwise any other caller (an operator, an ops script)
     * silently desynchronises the consumer's books from the gateway.
     */
    @Test
    void cancellingACharge_notifiesTheConsumerExactlyOnce() {
        String id = given().header("X-Client-Id", clientId).header("X-Client-Secret", clientSecret)
                .contentType("application/json").body(chargeBody("ref-cancel-webhook"))
                .when().post("/api/charges").then().statusCode(201).extract().path("id");

        given().header("X-Client-Id", clientId).header("X-Client-Secret", clientSecret)
                .when().post("/api/charges/{id}/cancel", id).then().statusCode(200);

        assertThat(cancelWebhooksFor(id)).isEqualTo(1);

        // Consumers retry cancellation from a durable outbox; a repeat must not enqueue a second
        // notification, or the retries bury the event that mattered.
        given().header("X-Client-Id", clientId).header("X-Client-Secret", clientSecret)
                .when().post("/api/charges/{id}/cancel", id).then().statusCode(200)
                .body("status", equalTo("CANCELLED"));

        assertThat(cancelWebhooksFor(id)).isEqualTo(1);
    }

    private long cancelWebhooksFor(String chargeId) {
        return webhookDeliveryRepository.findAll().stream()
                .filter(d -> d.getEventType() == WebhookEventType.CHARGE_CANCELLED)
                .filter(d -> chargeId.equals(d.getCharge().getId()))
                .count();
    }

    @Test
    void unknownCharge_is404() {
        given().header("X-Client-Id", clientId).header("X-Client-Secret", clientSecret)
                .when().get("/api/charges/{id}", "does-not-exist").then().statusCode(404);
    }
}
