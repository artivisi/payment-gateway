package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * The management REST API must not be open to the internet.
 *
 * <p>A blanket {@code /api/**} permitAll — written for bank callbacks, which authenticate themselves
 * — also covered {@code /api/escrow-accounts} and {@code /api/consumers}. Verified against production
 * on 2026-08-25: a GET returned the escrow configuration, including the settlement account number and
 * the bank client id, and the consumer registry with its client ids and webhook URLs; a POST reached
 * the escrow-create handler and was refused only by bean validation.
 *
 * <p>Secrets were never exposed — both response records deliberately omit them — so this is bank
 * integration configuration rather than a credential leak, which is why it is a matter of closing the
 * door rather than rotating keys.
 */
class ManagementApiAccessIntegrationTest extends AbstractIntegrationTest {

    @Test
    void escrowConfigurationIsNotReadableWithoutCredentials() {
        given().when().get("/api/escrow-accounts").then().statusCode(401);
    }

    @Test
    void anEscrowCannotBeCreatedWithoutCredentials() {
        // 401 before validation: the handler must not be reached at all. It answered 400 in
        // production, which is how we knew it was reachable.
        given().contentType("application/json").body(Map.of())
                .when().post("/api/escrow-accounts").then().statusCode(401);
    }

    @Test
    void theConsumerRegistryIsNotReadableWithoutCredentials() {
        given().when().get("/api/consumers").then().statusCode(401);
    }

    @Test
    void reconciliationCannotBeTriggeredWithoutCredentials() {
        // This one creates payments and forwards webhooks when it recovers, so an open door here is
        // not merely disclosure.
        given().contentType("application/json").body(Map.of("period", "2026-06-25", "credits", java.util.List.of()))
                .when().post("/api/escrow-accounts/any/reconciliations").then().statusCode(401);
    }

    @Test
    void bankCallbacksStayOpen() {
        // The whole point of the original rule. BSI has no session and cannot get one; it
        // authenticates by checksum and source IP. A 401 here would stop payment collection.
        given().contentType("application/json").body(Map.of("action", "inquiry", "nomorPembayaran", "000000000000"))
                .when().post("/api/bank/bsi").then().statusCode(200);
    }

    @Test
    void theConsumerApiStaysOpenToItsOwnAuth() {
        // /api/charges authenticates by client id and secret in the request, not by session, so the
        // security layer must let it reach the application. 400 is the proof: an empty body got as
        // far as bean validation. A 401 or a 302 here would mean the door had been shut on the
        // Consumer API — which is how account-receivable opens every charge.
        given().contentType("application/json")
                .header("X-Client-Id", "nobody").header("X-Client-Secret", "wrong")
                .body(Map.of()).when().post("/api/charges").then().statusCode(400);
    }
}
