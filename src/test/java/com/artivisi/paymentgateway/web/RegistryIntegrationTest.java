package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

class RegistryIntegrationTest extends AbstractIntegrationTest {

    private static Map<String, Object> escrowRequest(String code) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("provider", "bsi");
        body.put("hostingModel", "SELF_HOSTED");
        body.put("transport", "REST_JSON");
        body.put("authScheme", "PROPRIETARY");
        body.put("activeEnvironment", "SANDBOX");
        body.put("clientId", "client-123");
        body.put("clientSecret", "the-secret");
        body.put("privateKey", "the-private-key");
        body.put("settlementAccountNumber", "900900111");
        body.put("settlementAccountName", "Operator Settlement");
        body.put("companyId", "90099");
        body.put("vaPrefix", "900");
        body.put("vaDigitLength", 16);
        return body;
    }

    private static Map<String, Object> consumerRequest(String code, String clientId) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "Academic System");
        body.put("clientId", clientId);
        body.put("clientSecret", "consumer-secret");
        body.put("webhookUrl", "https://consumer.example/webhook");
        body.put("status", "ACTIVE");
        return body;
    }

    @Test
    void createAndReadEscrowAccount_neverReturnsSecrets() {
        String id = given().contentType("application/json").body(escrowRequest("bsi-launch"))
                .when().post("/api/escrow-accounts")
                .then().statusCode(201)
                .body("id", notNullValue())
                .body("code", equalTo("bsi-launch"))
                .body("clientId", equalTo("client-123"))
                .body("clientSecret", nullValue())
                .body("privateKey", nullValue())
                .extract().path("id");

        given().when().get("/api/escrow-accounts/{id}", id)
                .then().statusCode(200)
                .body("code", equalTo("bsi-launch"))
                .body("vaDigitLength", equalTo(16))
                .body("clientSecret", nullValue());
    }

    @Test
    void duplicateEscrowCode_returns409() {
        given().contentType("application/json").body(escrowRequest("dup-escrow"))
                .when().post("/api/escrow-accounts").then().statusCode(201);

        given().contentType("application/json").body(escrowRequest("dup-escrow"))
                .when().post("/api/escrow-accounts").then().statusCode(409);
    }

    @Test
    void invalidEscrow_returns400() {
        Map<String, Object> body = escrowRequest("bad-escrow");
        body.remove("settlementAccountNumber");
        given().contentType("application/json").body(body)
                .when().post("/api/escrow-accounts").then().statusCode(400);
    }

    @Test
    void unknownEscrow_returns404() {
        given().when().get("/api/escrow-accounts/{id}", "does-not-exist")
                .then().statusCode(404);
    }

    @Test
    void createAndReadConsumer_neverReturnsSecret() {
        String id = given().contentType("application/json").body(consumerRequest("academic", "academic-client"))
                .when().post("/api/consumers")
                .then().statusCode(201)
                .body("id", notNullValue())
                .body("clientId", equalTo("academic-client"))
                .body("clientSecret", nullValue())
                .extract().path("id");

        given().when().get("/api/consumers/{id}", id)
                .then().statusCode(200)
                .body("code", equalTo("academic"))
                .body("clientSecret", nullValue());
    }

    @Test
    void duplicateConsumerClientId_returns409() {
        given().contentType("application/json").body(consumerRequest("c-one", "same-client"))
                .when().post("/api/consumers").then().statusCode(201);

        given().contentType("application/json").body(consumerRequest("c-two", "same-client"))
                .when().post("/api/consumers").then().statusCode(409);
    }
}
