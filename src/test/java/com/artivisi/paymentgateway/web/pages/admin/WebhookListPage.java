package com.artivisi.paymentgateway.web.pages.admin;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

public class WebhookListPage {

    private final Page page;

    public WebhookListPage(Page page) {
        this.page = page;
    }

    public static WebhookListPage open(Page page, String baseUrl) {
        page.navigate(baseUrl + "/admin/webhooks");
        return new WebhookListPage(page);
    }

    public Locator root() {
        return page.getByTestId("webhook-list");
    }
}
