package com.artivisi.paymentgateway.web.pages.admin;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

public class ChargeDetailPage {

    private final Page page;

    public ChargeDetailPage(Page page) {
        this.page = page;
    }

    public static ChargeDetailPage open(Page page, String baseUrl, String id) {
        page.navigate(baseUrl + "/admin/charges/" + id);
        return new ChargeDetailPage(page);
    }

    public Locator root() {
        return page.getByTestId("charge-detail");
    }
}
