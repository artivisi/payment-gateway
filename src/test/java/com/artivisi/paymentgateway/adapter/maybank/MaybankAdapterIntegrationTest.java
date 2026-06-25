package com.artivisi.paymentgateway.adapter.maybank;

import com.artivisi.paymentgateway.AbstractIntegrationTest;
import com.artivisi.paymentgateway.adapter.snap.SnapSignatureHelper;
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
import com.artivisi.paymentgateway.service.ChargeService;
import com.artivisi.paymentgateway.service.ConsumerService;
import com.artivisi.paymentgateway.service.EscrowAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

class MaybankAdapterIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String TS = "2026-06-25T10:00:00+07:00";
    private static final String TOKEN_PATH = "/api/bank/maybank/v1.0/access-token/b2b";
    private static final String INQUIRY_PATH = "/api/bank/maybank/v1.0/transfer-va/inquiry";
    private static final String PAYMENT_PATH = "/api/bank/maybank/v1.0/transfer-va/payment";

    @Autowired EscrowAccountService escrowService;
    @Autowired ConsumerService consumerService;
    @Autowired ChargeService chargeService;

    private PrivateKey privateKey;
    private String clientId;
    private String clientSecret;
    private String vaNumber;

    @BeforeEach
    void seed() throws Exception {
        int n = SEQ.incrementAndGet();
        clientId = "maybank-client-" + n;
        clientSecret = "maybank-secret-" + n;
        vaNumber = "8800000" + String.format("%03d", n);

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        privateKey = keyPair.getPrivate();
        String publicKeyPem = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());

        String escrowCode = "maybank-adp-" + n;
        EscrowAccountRequest escrow = new EscrowAccountRequest(escrowCode, "maybank", HostingModel.SELF_HOSTED,
                TransportProtocol.REST_JSON, AuthScheme.SNAP, EscrowEnvironment.SANDBOX,
                clientId, clientSecret, "PARTNER-" + n, "95231", null, publicKeyPem, null, null,
                "880900111", "Operator Settlement", "88099", "880", 10, null, null);
        escrowService.create(escrow);

        Consumer consumer = consumerService.create(new ConsumerRequest(
                "mb-consumer-" + n, "Academic", "mb-client-" + n, "secret-" + n,
                "https://hook.example/" + n, ConsumerStatus.ACTIVE));
        chargeService.create(consumer, new CreateChargeRequest(
                "mb-ref-" + n, "Student", null, null, ChargeType.CLOSED, new BigDecimal("1000000"), null,
                List.of(new ChargeAccountRequest(escrowCode, vaNumber))));
    }

    private String obtainAccessToken() {
        String signature = SnapSignatureHelper.signToken(privateKey, clientId, TS);
        return given().header("X-TIMESTAMP", TS).header("X-CLIENT-KEY", clientId).header("X-SIGNATURE", signature)
                .contentType("application/json").body("{\"grantType\":\"client_credentials\"}")
                .when().post(TOKEN_PATH).then().statusCode(200)
                .body("responseCode", equalTo("2007300")).extract().path("accessToken");
    }

    private String inquiryBody() {
        return "{\"partnerServiceId\":\"88099\",\"customerNo\":\"" + vaNumber
                + "\",\"virtualAccountNo\":\"" + vaNumber + "\",\"inquiryRequestId\":\"INQ1\"}";
    }

    private String paymentBody(String amount, String reference) {
        return "{\"partnerServiceId\":\"88099\",\"customerNo\":\"" + vaNumber
                + "\",\"virtualAccountNo\":\"" + vaNumber + "\",\"paymentRequestId\":\"PAY1\",\"paidAmount\":{\"value\":\""
                + amount + "\",\"currency\":\"IDR\"},\"referenceNo\":\"" + reference + "\"}";
    }

    private io.restassured.response.Response transaction(String path, String token, String body, String externalId) {
        String signature = SnapSignatureHelper.signTransaction(clientSecret, "POST", path, token, body, TS);
        return given()
                .header("Authorization", "Bearer " + token)
                .header("X-TIMESTAMP", TS).header("X-SIGNATURE", signature)
                .header("X-PARTNER-ID", clientId).header("X-EXTERNAL-ID", externalId).header("CHANNEL-ID", "95231")
                .contentType("application/json").body(body)
                .when().post(path);
    }

    @Test
    void fullFlow_tokenInquiryPayment() {
        String token = obtainAccessToken();

        transaction(INQUIRY_PATH, token, inquiryBody(), "EXT-INQ-1").then().statusCode(200)
                .body("responseCode", equalTo("2002400"))
                .body("virtualAccountData.virtualAccountName", equalTo("Student"))
                .body("virtualAccountData.totalAmount.value", equalTo("1000000.00"));

        transaction(PAYMENT_PATH, token, paymentBody("1000000.00", "REF-1"), "EXT-PAY-1").then().statusCode(200)
                .body("responseCode", equalTo("2002500"));

        // Paid VA is no longer payable.
        transaction(INQUIRY_PATH, token, inquiryBody(), "EXT-INQ-2").then().statusCode(404)
                .body("responseCode", equalTo("4042412"));
    }

    @Test
    void token_invalidSignature_isUnauthorized() {
        given().header("X-TIMESTAMP", TS).header("X-CLIENT-KEY", clientId).header("X-SIGNATURE", "bad")
                .contentType("application/json").body("{\"grantType\":\"client_credentials\"}")
                .when().post(TOKEN_PATH).then().statusCode(401).body("responseCode", equalTo("4017300"));
    }

    @Test
    void inquiry_tamperedSignature_isUnauthorized() {
        String token = obtainAccessToken();
        String body = inquiryBody();
        String wrongSignature = SnapSignatureHelper.signTransaction(clientSecret, "POST", INQUIRY_PATH, token,
                "{\"tampered\":true}", TS);
        given().header("Authorization", "Bearer " + token)
                .header("X-TIMESTAMP", TS).header("X-SIGNATURE", wrongSignature).header("X-EXTERNAL-ID", "EXT-1")
                .contentType("application/json").body(body)
                .when().post(INQUIRY_PATH).then().statusCode(401).body("responseCode", equalTo("4012400"));
    }

    @Test
    void inquiry_duplicateExternalId_isConflict() {
        String token = obtainAccessToken();
        transaction(INQUIRY_PATH, token, inquiryBody(), "EXT-DUP").then().statusCode(200);
        transaction(INQUIRY_PATH, token, inquiryBody(), "EXT-DUP").then().statusCode(409)
                .body("responseCode", equalTo("4092400"));
    }

    @Test
    void inquiry_unknownVaNotFound() {
        String token = obtainAccessToken();
        String body = "{\"partnerServiceId\":\"88099\",\"customerNo\":\"8809999999\",\"virtualAccountNo\":\"8809999999\",\"inquiryRequestId\":\"INQ1\"}";
        String signature = SnapSignatureHelper.signTransaction(clientSecret, "POST", INQUIRY_PATH, token, body, TS);
        given().header("Authorization", "Bearer " + token)
                .header("X-TIMESTAMP", TS).header("X-SIGNATURE", signature).header("X-EXTERNAL-ID", "EXT-UNK")
                .contentType("application/json").body(body)
                .when().post(INQUIRY_PATH).then().statusCode(404).body("responseCode", equalTo("4042412"));
    }

    @Test
    void token_success_returnsBearer() {
        String signature = SnapSignatureHelper.signToken(privateKey, clientId, TS);
        requestTokenResponse(signature);
    }

    private void requestTokenResponse(String signature) {
        given().header("X-TIMESTAMP", TS).header("X-CLIENT-KEY", clientId).header("X-SIGNATURE", signature)
                .contentType("application/json").body("{\"grantType\":\"client_credentials\"}")
                .when().post(TOKEN_PATH).then().statusCode(200)
                .body("responseCode", equalTo("2007300"))
                .body("tokenType", equalTo("Bearer"))
                .body("accessToken", notNullValue())
                .body("expiresIn", equalTo("900"));
    }
}
