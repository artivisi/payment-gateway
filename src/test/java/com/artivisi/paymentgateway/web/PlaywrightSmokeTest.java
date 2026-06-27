package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.AbstractIntegrationTest;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Browser tests for the admin UI: the dashboard renders on the ArtiVisi brand, and a consumer can
 * be created through the form end-to-end.
 */
class PlaywrightSmokeTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private String base() {
        return "http://localhost:" + port;
    }

    @Test
    void dashboardRenders() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch();
            Page page = browser.newPage();
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
                "/admin/audit", "audit-list");
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch();
            Page page = browser.newPage();
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
            page.navigate(base() + "/admin/consumers/new");
            page.fill("#code", code);
            page.fill("#name", "UI Test");
            page.fill("#clientId", "ui-client-" + n);
            page.fill("#clientSecret", "ui-secret");
            page.fill("#webhookUrl", "https://hook.example/ui");
            page.selectOption("#status", "ACTIVE");
            page.click("button[type=submit]");
            assertThat(page.getByTestId("consumer-list").textContent()).contains(code);
            browser.close();
        }
    }
}
