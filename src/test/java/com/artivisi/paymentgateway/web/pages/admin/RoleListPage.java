package com.artivisi.paymentgateway.web.pages.admin;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

public class RoleListPage {

    private final Page page;

    public RoleListPage(Page page) {
        this.page = page;
    }

    public static RoleListPage open(Page page, String baseUrl) {
        page.navigate(baseUrl + "/admin/roles");
        return new RoleListPage(page);
    }

    public Locator root() {
        return page.getByTestId("role-list");
    }
}
