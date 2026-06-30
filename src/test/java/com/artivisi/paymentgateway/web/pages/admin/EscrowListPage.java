package com.artivisi.paymentgateway.web.pages.admin;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

public class EscrowListPage {

    private final Page page;
    private final String baseUrl;

    public EscrowListPage(Page page, String baseUrl) {
        this.page = page;
        this.baseUrl = baseUrl;
    }

    public static EscrowListPage open(Page page, String baseUrl) {
        page.navigate(baseUrl + "/admin/escrow-accounts");
        return new EscrowListPage(page, baseUrl);
    }

    public Locator root() {
        return page.getByTestId("escrow-list");
    }

    public EscrowEditPage edit(String id) {
        return EscrowEditPage.open(page, baseUrl, id);
    }
}
