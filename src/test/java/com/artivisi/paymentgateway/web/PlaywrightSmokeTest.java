package com.artivisi.paymentgateway.web;

import com.artivisi.paymentgateway.AbstractIntegrationTest;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase-0 browser smoke test: stands up the Playwright harness against the running app
 * and verifies the landing page renders. The admin UI arrives in Phase 4; this only
 * proves the end-to-end browser path works.
 */
class PlaywrightSmokeTest extends AbstractIntegrationTest {

    @Test
    void landingPageRenders() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch();
            Page page = browser.newPage();
            page.navigate("http://localhost:" + port + "/");
            assertThat(page.title()).isEqualTo("payment-gateway");
            assertThat(page.getByTestId("app-title").textContent()).contains("payment-gateway");
            browser.close();
        }
    }
}
