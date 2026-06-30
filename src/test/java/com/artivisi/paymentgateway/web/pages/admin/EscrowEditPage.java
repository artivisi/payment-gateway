package com.artivisi.paymentgateway.web.pages.admin;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

public class EscrowEditPage {

    private final Page page;

    public EscrowEditPage(Page page) {
        this.page = page;
    }

    public static EscrowEditPage open(Page page, String baseUrl, String id) {
        page.navigate(baseUrl + "/admin/escrow-accounts/" + id + "/edit");
        return new EscrowEditPage(page);
    }

    public Locator root() {
        return page.getByTestId("escrow-edit");
    }

    public Locator providerSelect() {
        return page.getByTestId("provider");
    }
}
