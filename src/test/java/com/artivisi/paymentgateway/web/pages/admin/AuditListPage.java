package com.artivisi.paymentgateway.web.pages.admin;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

public class AuditListPage {

    private final Page page;

    public AuditListPage(Page page) {
        this.page = page;
    }

    public static AuditListPage open(Page page, String baseUrl) {
        page.navigate(baseUrl + "/admin/audit");
        return new AuditListPage(page);
    }

    public Locator root() {
        return page.getByTestId("audit-list");
    }
}
