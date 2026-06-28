package com.artivisi.paymentgateway.config;

import com.artivisi.paymentgateway.service.OperatorService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Seeds the bootstrap ADMIN on startup when no operator exists, so the admin UI is never open. */
@Component
public class OperatorBootstrap implements ApplicationRunner {

    private final OperatorService operatorService;

    public OperatorBootstrap(OperatorService operatorService) {
        this.operatorService = operatorService;
    }

    @Override
    public void run(ApplicationArguments args) {
        operatorService.seedBootstrapAdminIfEmpty();
    }
}
