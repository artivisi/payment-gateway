package com.artivisi.paymentgateway.web.pages.admin;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

public class ConsumerListPage {

    private final Page page;
    private final String baseUrl;

    public ConsumerListPage(Page page, String baseUrl) {
        this.page = page;
        this.baseUrl = baseUrl;
    }

    public static ConsumerListPage open(Page page, String baseUrl) {
        page.navigate(baseUrl + "/admin/consumers");
        return new ConsumerListPage(page, baseUrl);
    }

    public Locator root() {
        return page.getByTestId("consumer-list");
    }

    public ConsumerFormPage newConsumer() {
        return ConsumerFormPage.open(page, baseUrl);
    }

    public ConsumerEditPage edit(String id) {
        return ConsumerEditPage.open(page, baseUrl, id);
    }
}
