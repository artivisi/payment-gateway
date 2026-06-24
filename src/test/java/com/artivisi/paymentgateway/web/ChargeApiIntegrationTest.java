package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.AbstractIntegrationTest;
import com.artivisi.paymentgateway.dto.ConsumerRequest;
import com.artivisi.paymentgateway.dto.EscrowAccountRequest;
import com.artivisi.paymentgateway.entity.AuthScheme;
import com.artivisi.paymentgateway.entity.ConsumerStatus;
import com.artivisi.paymentgateway.entity.EscrowEnvironment;
import com.artivisi.paymentgateway.entity.HostingModel;
import com.artivisi.paymentgateway.entity.TransportProtocol;
import com.artivisi.paymentgateway.service.ConsumerService;
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
import static org.hamcrest.Matchers.notNullValue;

class ChargeApiIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired EscrowAccountService escrowService;
    @Autowired ConsumerService consumerService;

    private String clientId;
    private String clientSecret;
    private String bank_aCode;
    private String bank_bCode;
    private final String bank_aVa = "9000000001";
    private final String bank_bVa = "9000000002";

    private static EscrowAccountRequest escrowRequest(String code) {
        return new EscrowAccountRequest(code, "bank_a", HostingModel.SELF_HOSTED, TransportProtocol.REST_JSON,
                AuthScheme.PROPRIETARY, EscrowEnvironment.SANDBOX, null, null, null, null, null, null, null, null,
                "900900111", "Operator Settlement", "90099", "900", 10, null, null);
    }

    @BeforeEach
    void seed() {
        int n = SEQ.incrementAndGet();
        clientId = "api-client-" + n;
        clientSecret = "secret-" + n;
        bank_aCode = "api-bank_a-" + n;
        bank_bCode = "api-bank_b-" + n;
        consumerService.create(new ConsumerRequest(
                "api-consumer-" + n, "Academic", clientId, clientSecret, "https://hook.example/" + n, ConsumerStatus.ACTIVE));
        escrowService.create(escrowRequest(bank_aCode));
        escrowService.create(escrowRequest(bank_bCode));
    }

    private Map<String, Object> chargeBody(String reference, String bank_aNumber) {
        return Map.of(
                "consumerReference", reference,
                "payerName", "Student",
                "chargeType", "CLOSED",
                "amount", 1000000,
                "accounts", List.of(
                        Map.of("escrowCode", bank_aCode, "vaNumber", bank_aNumber),
                        Map.of("escrowCode", bank_bCode, "vaNumber", bank_bVa)));
    }

    @Test
    void createCharge_fansOutAcrossEscrows() {
        given().header("X-Client-Id", clientId).header("X-Client-Secret", clientSecret)
                .contentType("application/json").body(chargeBody("ref-1", bank_aVa))
                .when().post("/api/charges")
                .then().statusCode(201)
                .body("id", notNullValue())
                .body("status", equalTo("ACTIVE"))
                .body("accounts.size()", equalTo(2))
                .body("accounts.escrowCode", hasItems(bank_aCode, bank_bCode))
                .body("accounts.status", hasItems("ACTIVE"));
    }

    @Test
    void createCharge_isIdempotentOnConsumerReference() {
        String id = given().header("X-Client-Id", clientId).header("X-Client-Secret", clientSecret)
                .contentType("application/json").body(chargeBody("ref-dup", bank_aVa))
                .when().post("/api/charges").then().statusCode(201).extract().path("id");

        given().header("X-Client-Id", clientId).header("X-Client-Secret", clientSecret)
                .contentType("application/json").body(chargeBody("ref-dup", bank_aVa))
                .when().post("/api/charges")
                .then().statusCode(200).body("id", equalTo(id));
    }

    @Test
    void createCharge_withoutCredentials_is401() {
        given().contentType("application/json").body(chargeBody("ref-1", bank_aVa))
                .when().post("/api/charges").then().statusCode(401);
    }

    @Test
    void createCharge_withWrongSecret_is401() {
        given().header("X-Client-Id", clientId).header("X-Client-Secret", "wrong")
                .contentType("application/json").body(chargeBody("ref-1", bank_aVa))
                .when().post("/api/charges").then().statusCode(401);
    }

    @Test
    void createCharge_withVaNumberOutsideSpace_is400() {
        given().header("X-Client-Id", clientId).header("X-Client-Secret", clientSecret)
                .contentType("application/json").body(chargeBody("ref-1", "12345"))
                .when().post("/api/charges").then().statusCode(400);
    }

    @Test
    void getAndCancelCharge() {
        String id = given().header("X-Client-Id", clientId).header("X-Client-Secret", clientSecret)
                .contentType("application/json").body(chargeBody("ref-cancel", bank_aVa))
                .when().post("/api/charges").then().statusCode(201).extract().path("id");

        given().header("X-Client-Id", clientId).header("X-Client-Secret", clientSecret)
                .when().get("/api/charges/{id}", id).then().statusCode(200).body("status", equalTo("ACTIVE"));

        given().header("X-Client-Id", clientId).header("X-Client-Secret", clientSecret)
                .when().post("/api/charges/{id}/cancel", id)
                .then().statusCode(200)
                .body("status", equalTo("CANCELLED"))
                .body("accounts.status", hasItems("CANCELLED"));
    }

    @Test
    void unknownCharge_is404() {
        given().header("X-Client-Id", clientId).header("X-Client-Secret", clientSecret)
                .when().get("/api/charges/{id}", "does-not-exist").then().statusCode(404);
    }
}
