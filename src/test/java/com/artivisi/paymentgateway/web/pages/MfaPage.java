package com.artivisi.paymentgateway.web.pages;

import com.artivisi.paymentgateway.web.pages.admin.DashboardPage;
import com.microsoft.playwright.Page;

public class MfaPage {

    private final Page page;
    private final String baseUrl;

    public MfaPage(Page page, String baseUrl) {
        this.page = page;
        this.baseUrl = baseUrl;
    }

    public DashboardPage verify(String code) {
        page.getByTestId("mfa-code").fill(code);
        page.getByTestId("mfa-submit").click();
        page.waitForURL("**/admin");
        return new DashboardPage(page, baseUrl);
    }
}
