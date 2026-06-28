package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.AbstractIntegrationTest;
import com.artivisi.paymentgateway.entity.Operator;
import com.artivisi.paymentgateway.repository.OperatorRepository;
import com.artivisi.paymentgateway.repository.RoleRepository;
import com.artivisi.paymentgateway.service.TotpService;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import dev.samstevens.totp.time.SystemTimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Browser tests for the authenticated admin UI: log in (password + TOTP MFA), then the dashboard
 * renders and a consumer can be created through the form end-to-end.
 */
class PlaywrightSmokeTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String OPERATOR_USER = "pw-admin";
    private static final String OPERATOR_PASS = "playwright-pass-123456";

    @Autowired OperatorRepository operatorRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired TotpService totpService;

    private String operatorSecret;

    @BeforeEach
    void seedOperator() {
        Operator op = operatorRepository.findByUsername(OPERATOR_USER).orElseGet(() -> {
            Operator o = new Operator();
            o.setUsername(OPERATOR_USER);
            o.setPasswordHash(passwordEncoder.encode(OPERATOR_PASS));
            o.setFullName("Playwright Admin");
            o.setRole(roleRepository.findByName("ADMIN").orElseThrow());
            o.setEnabled(true);
            o.setMfaEnabled(true);
            o.setMfaSecret(totpService.generateSecret());
            o.setMustChangePassword(false);
            o.setFailedAttempts(0);
            return operatorRepository.save(o);
        });
        this.operatorSecret = op.getMfaSecret();
    }

    private String base() {
        return "http://localhost:" + port;
    }

    /** Full login: password then the current TOTP. Leaves the page on /admin, session established. */
    private void login(Page page) {
        page.navigate(base() + "/login");
        page.fill("#username", OPERATOR_USER);
        page.fill("#password", OPERATOR_PASS);
        page.click("button[type=submit]");
        page.waitForURL("**/mfa");
        page.fill("#code", currentTotp());
        page.click("button[type=submit]");
        page.waitForURL("**/admin");
    }

    private String currentTotp() {
        try {
            long counter = Math.floorDiv(new SystemTimeProvider().getTime(), 30);
            return new DefaultCodeGenerator().generate(operatorSecret, counter);
        } catch (CodeGenerationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void unauthenticatedAdminRedirectsToLogin() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch();
            Page page = browser.newPage();
            page.navigate(base() + "/admin");
            assertThat(page.url()).contains("/login");
            assertThat(page.getByTestId("login-page").count()).isEqualTo(1);
            browser.close();
        }
    }

    @Test
    void dashboardRenders() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch();
            Page page = browser.newPage();
            login(page);
            page.navigate(base() + "/");   // redirects to /admin
            assertThat(page.url()).endsWith("/admin");
            assertThat(page.title()).isEqualTo("Dashboard · Payment Gateway");
            assertThat(page.getByTestId("dashboard").textContent()).contains("Escrows").contains("Consumers");
            assertThat(page.locator("header img[alt='ArtiVisi']").count()).isEqualTo(1);
            browser.close();
        }
    }

    @Test
    void browseSectionsRender() {
        Map<String, String> sections = Map.of(
                "/admin/escrow-accounts", "escrow-list",
                "/admin/charges", "charge-list",
                "/admin/payments", "payment-list",
                "/admin/reconciliations", "reconciliation-list",
                "/admin/webhooks", "webhook-list",
                "/admin/operators", "operator-list",
                "/admin/roles", "role-list",
                "/admin/bank-ip-rules", "bank-ip-rule-list",
                "/admin/audit", "audit-list");
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch();
            Page page = browser.newPage();
            login(page);
            sections.forEach((path, testId) -> {
                page.navigate(base() + path);
                assertThat(page.getByTestId(testId).count()).as(path).isEqualTo(1);
            });
            browser.close();
        }
    }

    @Test
    void createConsumerThroughUi() {
        int n = SEQ.incrementAndGet();
        String code = "ui-consumer-" + n;
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch();
            Page page = browser.newPage();
            login(page);
            page.navigate(base() + "/admin/consumers/new");
            page.fill("#code", code);
            page.fill("#name", "UI Test");
            page.fill("#clientId", "ui-client-" + n);
            page.fill("#clientSecret", "ui-secret");
            page.fill("#webhookUrl", "https://hook.example/ui");
            page.selectOption("#status", "ACTIVE");
            page.click("main button[type=submit]");   // not the navbar "Sign out" button
            assertThat(page.getByTestId("consumer-list").textContent()).contains(code);
            browser.close();
        }
    }
}
