package com.artivisi.paymentgateway.web.pages.admin;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

public class ConsumerEditPage {

    private final Page page;
    private final String baseUrl;

    public ConsumerEditPage(Page page, String baseUrl) {
        this.page = page;
        this.baseUrl = baseUrl;
    }

    public static ConsumerEditPage open(Page page, String baseUrl, String id) {
        page.navigate(baseUrl + "/admin/consumers/" + id + "/edit");
        return new ConsumerEditPage(page, baseUrl);
    }

    public Locator root() {
        return page.getByTestId("consumer-edit");
    }

    public ConsumerEditPage fillName(String name) {
        page.getByTestId("name").fill(name);
        return this;
    }

    public ConsumerListPage submit() {
        page.getByTestId("form-submit").click();
        page.waitForURL("**/consumers");
        return new ConsumerListPage(page, baseUrl);
    }
}
