package com.artivisi.paymentgateway.web.pages.admin;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

public class BankIpListPage {

    private final Page page;

    public BankIpListPage(Page page) {
        this.page = page;
    }

    public static BankIpListPage open(Page page, String baseUrl) {
        page.navigate(baseUrl + "/admin/bank-ip-rules");
        return new BankIpListPage(page);
    }

    public Locator root() {
        return page.getByTestId("bank-ip-rule-list");
    }
}
