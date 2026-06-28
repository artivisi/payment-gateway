package com.artivisi.paymentgateway.adapter;

import com.artivisi.paymentgateway.AbstractIntegrationTest;
import com.artivisi.paymentgateway.service.BankIpRuleService;
import com.artivisi.paymentgateway.repository.BankIpRuleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.not;

/**
 * App-layer per-provider IP allowlist on bank callbacks: no rule = unrestricted; a rule excluding the
 * caller's IP yields 403; a rule for a different provider does not affect this one. Tests call from
 * 127.0.0.1.
 */
class BankIpAllowlistIntegrationTest extends AbstractIntegrationTest {

    @Autowired BankIpRuleService bankIpRuleService;
    @Autowired BankIpRuleRepository bankIpRuleRepository;

    @AfterEach
    void clearRules() {
        bankIpRuleRepository.deleteAll();
    }

    @Test
    void noRule_isUnrestricted() {
        given().contentType("application/json").body("{}")
                .when().post("/api/bank/bsi")
                .then().statusCode(not(403));
    }

    @Test
    void ruleExcludingCaller_yields403() {
        bankIpRuleService.create("bsi", "10.0.0.0/8", "not localhost");
        given().contentType("application/json").body("{}")
                .when().post("/api/bank/bsi")
                .then().statusCode(403);
    }

    @Test
    void ruleAllowingCaller_passesThrough() {
        bankIpRuleService.create("bsi", "127.0.0.1/32", "localhost");
        given().contentType("application/json").body("{}")
                .when().post("/api/bank/bsi")
                .then().statusCode(not(403));
    }

    @Test
    void ruleForOtherProvider_doesNotAffectThisOne() {
        bankIpRuleService.create("cimb", "10.0.0.0/8", "cimb only");
        // bsi has no rule -> still unrestricted despite the cimb rule
        given().contentType("application/json").body("{}")
                .when().post("/api/bank/bsi")
                .then().statusCode(not(403));
    }
}
