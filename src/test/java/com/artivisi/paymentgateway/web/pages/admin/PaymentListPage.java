package com.artivisi.paymentgateway.web.pages.admin;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

public class PaymentListPage {

    private final Page page;
    private final String baseUrl;

    public PaymentListPage(Page page, String baseUrl) {
        this.page = page;
        this.baseUrl = baseUrl;
    }

    public static PaymentListPage open(Page page, String baseUrl) {
        page.navigate(baseUrl + "/admin/payments");
        return new PaymentListPage(page, baseUrl);
    }

    public Locator root() {
        return page.getByTestId("payment-list");
    }

    public PaymentDetailPage detail(String id) {
        return PaymentDetailPage.open(page, baseUrl, id);
    }
}
