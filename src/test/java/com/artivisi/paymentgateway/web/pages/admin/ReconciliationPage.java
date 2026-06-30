package com.artivisi.paymentgateway.web.pages.admin;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.FilePayload;

public class ReconciliationPage {

    private final Page page;
    private final String baseUrl;

    public ReconciliationPage(Page page, String baseUrl) {
        this.page = page;
        this.baseUrl = baseUrl;
    }

    public static ReconciliationPage open(Page page, String baseUrl) {
        page.navigate(baseUrl + "/admin/reconciliations");
        return new ReconciliationPage(page, baseUrl);
    }

    public Locator root() {
        return page.getByTestId("reconciliation-list");
    }

    public ReconciliationPage selectEscrow(String code) {
        page.getByTestId("escrow-code").selectOption(code);
        return this;
    }

    public ReconciliationPage fillPeriod(String date) {
        page.getByTestId("period").fill(date);
        return this;
    }

    public ReconciliationPage uploadSettlement(FilePayload file) {
        page.getByTestId("settlement-file").setInputFiles(file);
        return this;
    }

    public ReconciliationPage submit() {
        page.getByTestId("form-submit").click();
        page.waitForURL("**/reconciliations");
        return this;
    }
}
