package com.artivisi.paymentgateway.web.pages.admin;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

public class OperatorEditPage {

    private final Page page;
    private final String baseUrl;

    public OperatorEditPage(Page page, String baseUrl) {
        this.page = page;
        this.baseUrl = baseUrl;
    }

    public static OperatorEditPage open(Page page, String baseUrl, String id) {
        page.navigate(baseUrl + "/admin/operators/" + id + "/edit");
        return new OperatorEditPage(page, baseUrl);
    }

    public Locator root() {
        return page.getByTestId("operator-edit");
    }

    public OperatorEditPage fillFullName(String name) {
        page.getByTestId("full-name").fill(name);
        return this;
    }

    public OperatorListPage submit() {
        page.getByTestId("form-submit").click();
        page.waitForURL("**/operators");
        return new OperatorListPage(page, baseUrl);
    }
}
