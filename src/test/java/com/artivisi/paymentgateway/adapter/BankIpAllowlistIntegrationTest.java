package com.artivisi.paymentgateway.adapter;

import com.artivisi.paymentgateway.AbstractIntegrationTest;
import com.artivisi.paymentgateway.service.BankIpRuleService;
import com.artivisi.paymentgateway.repository.BankIpRuleRepository;
import org.junit.jupiter.api.AfterEach;
import com.artivisi.paymentgateway.adapter.spi.BankAdapter;
import com.artivisi.paymentgateway.entity.HostingModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.not;

/**
 * App-layer per-provider IP allowlist on bank callbacks: no rule = unrestricted; a rule excluding the
 * caller's IP yields 403; a rule for a different provider does not affect this one. Tests call from
 * 127.0.0.1.
 */
@Import(BankIpAllowlistIntegrationTest.SecondBank.class)
class BankIpAllowlistIntegrationTest extends AbstractIntegrationTest {

    /**
     * A second provider, so "a rule for another bank does not affect this one" is testable at all.
     * It needs no endpoint: the rule is keyed by provider, and this test asks what happens to the
     * demo bank's traffic while another bank has a rule. Registered here rather than shipped, because
     * the engine publishes exactly one worked adapter.
     */
    @TestConfiguration
    static class SecondBank {
        @Bean
        BankAdapter secondBankAdapter() {
            return new BankAdapter() {
                @Override public String provider() { return "second-bank"; }
                @Override public HostingModel hostingModel() { return HostingModel.SELF_HOSTED; }
                @Override public String inboundPath() { return "/api/bank/second-bank"; }
            };
        }
    }


    @Autowired BankIpRuleService bankIpRuleService;
    @Autowired BankIpRuleRepository bankIpRuleRepository;

    @AfterEach
    void clearRules() {
        bankIpRuleRepository.deleteAll();
    }

    @Test
    void noRule_isUnrestricted() {
        given().contentType("application/json").body("{}")
                .when().post("/api/bank/demo")
                .then().statusCode(not(403));
    }

    @Test
    void ruleExcludingCaller_yields403() {
        bankIpRuleService.create("demo", "10.0.0.0/8", "not localhost");
        given().contentType("application/json").body("{}")
                .when().post("/api/bank/demo")
                .then().statusCode(403);
    }

    @Test
    void ruleAllowingCaller_passesThrough() {
        bankIpRuleService.create("demo", "127.0.0.1/32", "localhost");
        given().contentType("application/json").body("{}")
                .when().post("/api/bank/demo")
                .then().statusCode(not(403));
    }

    @Test
    void ruleForOtherProvider_doesNotAffectThisOne() {
        bankIpRuleService.create("second-bank", "10.0.0.0/8", "a different provider");
        // demo has no rule of its own -> still unrestricted despite the other bank's rule
        given().contentType("application/json").body("{}")
                .when().post("/api/bank/demo")
                .then().statusCode(not(403));
    }
}
