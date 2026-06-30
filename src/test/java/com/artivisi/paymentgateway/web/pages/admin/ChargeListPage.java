package com.artivisi.paymentgateway.web.pages.admin;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

public class ChargeListPage {

    private final Page page;
    private final String baseUrl;

    public ChargeListPage(Page page, String baseUrl) {
        this.page = page;
        this.baseUrl = baseUrl;
    }

    public static ChargeListPage open(Page page, String baseUrl) {
        page.navigate(baseUrl + "/admin/charges");
        return new ChargeListPage(page, baseUrl);
    }

    public Locator root() {
        return page.getByTestId("charge-list");
    }

    public ChargeDetailPage detail(String id) {
        return ChargeDetailPage.open(page, baseUrl, id);
    }
}
