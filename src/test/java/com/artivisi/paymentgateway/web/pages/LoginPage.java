package com.artivisi.paymentgateway.web.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

public class LoginPage {

    private final Page page;
    private final String baseUrl;

    public LoginPage(Page page, String baseUrl) {
        this.page = page;
        this.baseUrl = baseUrl;
    }

    public static LoginPage open(Page page, String baseUrl) {
        page.navigate(baseUrl + "/login");
        return new LoginPage(page, baseUrl);
    }

    public MfaPage login(String username, String password) {
        page.getByTestId("username").fill(username);
        page.getByTestId("password").fill(password);
        page.getByTestId("login-submit").click();
        page.waitForURL("**/mfa");
        return new MfaPage(page, baseUrl);
    }

    public Locator root() {
        return page.getByTestId("login-page");
    }
}
