package com.artivisi.paymentgateway.web.pages.admin;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

public class DashboardPage {

    private final Page page;
    final String baseUrl;

    public DashboardPage(Page page, String baseUrl) {
        this.page = page;
        this.baseUrl = baseUrl;
    }

    public Locator root() {
        return page.getByTestId("dashboard");
    }

    public Locator logo() {
        return page.getByTestId("logo");
    }
}
