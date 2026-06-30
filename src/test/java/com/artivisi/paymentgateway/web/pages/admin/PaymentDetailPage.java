package com.artivisi.paymentgateway.web.pages.admin;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

public class PaymentDetailPage {

    private final Page page;

    public PaymentDetailPage(Page page) {
        this.page = page;
    }

    public static PaymentDetailPage open(Page page, String baseUrl, String id) {
        page.navigate(baseUrl + "/admin/payments/" + id);
        return new PaymentDetailPage(page);
    }

    public Locator root() {
        return page.getByTestId("payment-detail");
    }
}
