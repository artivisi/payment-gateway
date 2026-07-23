package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.AbstractIntegrationTest;
import com.artivisi.paymentgateway.repository.OperatorRepository;
import com.artivisi.paymentgateway.service.DeviceAuthService;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * The device flow end to end, and the property that matters most: a token is worthless until a human
 * with a session approves it, and it never carries more than that operator's permissions.
 */
class DeviceAuthIntegrationTest extends AbstractIntegrationTest {

    @Autowired DeviceAuthService deviceAuthService;
    @Autowired OperatorRepository operatorRepository;

    private static final String REPORT = """
            {"kind":"collection-aging","title":"Bill aging","source":"test",
             "generatedAt":"2026-07-23T10:00:00Z",
             "cohorts":[{"key":"01","label":"Registration","bills":100,"paid":14,"medianDaysToPay":1}],
             "survival":[{"key":"registration","label":"Registration",
                          "points":[{"day":30,"stillUnpaid":86,"laterPaid":1,"amountLaterPaid":250000}]}]}
            """;

    @Test
    void unapprovedCode_yieldsNoToken() {
        String deviceCode = given().contentType(ContentType.JSON)
                .body(Map.of("clientId", "claude-code"))
                .when().post("/api/device/code")
                .then().statusCode(200)
                .body("userCode", notNullValue())
                .extract().path("deviceCode");

        given().contentType(ContentType.JSON).body(Map.of("deviceCode", deviceCode))
                .when().post("/api/device/token")
                .then().statusCode(400).body("error", equalTo("authorization_pending"));
    }

    @Test
    void approvedCode_yieldsTokenThatCanPublish_andIsSingleUse() {
        var start = given().contentType(ContentType.JSON)
                .body(Map.of("clientId", "claude-code", "deviceName", "test device"))
                .when().post("/api/device/code").then().statusCode(200).extract().jsonPath();
        String deviceCode = start.getString("deviceCode");

        // The human half: an operator with a session approves the code they can see on the CLI.
        deviceAuthService.authorize(start.getString("userCode"),
                operatorRepository.findAll().getFirst());

        String token = given().contentType(ContentType.JSON).body(Map.of("deviceCode", deviceCode))
                .when().post("/api/device/token")
                .then().statusCode(200).body("tokenType", equalTo("Bearer"))
                .extract().path("accessToken");
        assertThat(token).isNotBlank();

        given().contentType(ContentType.JSON).header("Authorization", "Bearer " + token).body(REPORT)
                .when().post("/api/analysis-reports")
                .then().statusCode(201).body("kind", equalTo("collection-aging"));

        // The code is consumed at issue: replaying the poll must not mint a second token.
        given().contentType(ContentType.JSON).body(Map.of("deviceCode", deviceCode))
                .when().post("/api/device/token")
                .then().statusCode(400).body("error", equalTo("expired_token"));
    }

    @Test
    void publishingWithoutAToken_isRejected() {
        given().contentType(ContentType.JSON).body(REPORT)
                .when().post("/api/analysis-reports")
                .then().statusCode(401);
    }

    @Test
    void publishingWithAGarbageToken_isRejected() {
        given().contentType(ContentType.JSON).header("Authorization", "Bearer not-a-real-token")
                .body(REPORT)
                .when().post("/api/analysis-reports")
                .then().statusCode(401);
    }
}
