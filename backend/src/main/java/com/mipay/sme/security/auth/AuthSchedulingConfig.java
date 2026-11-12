package com.mipay.sme.security.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables the scheduled outbox worker and the AuthSyncService nightly repair (§3.3b). */
@Configuration
@EnableScheduling
@org.springframework.stereotype.Component
public class AuthSchedulingConfig {

    private final org.springframework.context.ApplicationContext context;

    public AuthSchedulingConfig(org.springframework.context.ApplicationContext context) {
        this.context = context;
    }

    /** Nightly repair: reconcile every link against core state and place overdue KYC holds. */
    @org.springframework.scheduling.annotation.Scheduled(cron = "${app.auth-repair-cron:0 0 3 * * *}")
    public void nightlyAuthRepair() {
        context.getBean(AuthSyncService.class).nightlyRepair();   // lazy: avoids construction cycles
    }
}
