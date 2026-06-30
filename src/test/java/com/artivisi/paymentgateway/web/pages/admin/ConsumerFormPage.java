package com.artivisi.paymentgateway.web.pages.admin;

import com.microsoft.playwright.Page;

public class ConsumerFormPage {

    private final Page page;
    private final String baseUrl;

    public ConsumerFormPage(Page page, String baseUrl) {
        this.page = page;
        this.baseUrl = baseUrl;
    }

    public static ConsumerFormPage open(Page page, String baseUrl) {
        page.navigate(baseUrl + "/admin/consumers/new");
        return new ConsumerFormPage(page, baseUrl);
    }

    public ConsumerFormPage fillCode(String code) {
        page.getByTestId("code").fill(code);
        return this;
    }

    public ConsumerFormPage fillName(String name) {
        page.getByTestId("name").fill(name);
        return this;
    }

    public ConsumerFormPage fillClientId(String clientId) {
        page.getByTestId("client-id").fill(clientId);
        return this;
    }

    public ConsumerFormPage fillClientSecret(String secret) {
        page.getByTestId("client-secret").fill(secret);
        return this;
    }

    public ConsumerFormPage fillWebhookUrl(String url) {
        page.getByTestId("webhook-url").fill(url);
        return this;
    }

    public ConsumerFormPage selectStatus(String status) {
        page.getByTestId("status").selectOption(status);
        return this;
    }

    public ConsumerListPage submit() {
        page.getByTestId("form-submit").click();
        page.waitForURL("**/consumers");
        return new ConsumerListPage(page, baseUrl);
    }
}
