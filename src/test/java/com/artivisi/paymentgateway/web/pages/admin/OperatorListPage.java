package com.artivisi.paymentgateway.web.pages.admin;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

public class OperatorListPage {

    private final Page page;
    private final String baseUrl;

    public OperatorListPage(Page page, String baseUrl) {
        this.page = page;
        this.baseUrl = baseUrl;
    }

    public static OperatorListPage open(Page page, String baseUrl) {
        page.navigate(baseUrl + "/admin/operators");
        return new OperatorListPage(page, baseUrl);
    }

    public Locator root() {
        return page.getByTestId("operator-list");
    }

    public OperatorEditPage edit(String id) {
        return OperatorEditPage.open(page, baseUrl, id);
    }
}
