package com.mipay.sme.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Central app configuration. Environment drives the global SIMULATED labelling
 * rule (UAT SB p.16): when environment != production every bank/lender event
 * created by the simulation layer is labelled in UI and audit (BC p.17).
 */
@ConfigurationProperties(prefix = "app")
@Getter
@Setter
public class AppProperties {

    private String environment = "sandbox";  // sandbox | uat | production
    private String devOtp = "123456";        // fixed OTP in sandbox for the UAT demos
    private String rbacUrl = "https://trustplane-rbac:8443";  // trustplane RBAC service

    private Advance advance = new Advance();

    private Recon recon = new Recon();

    /** Simulated bank behaviour knobs (all events labelled SIMULATED, UAT SB p.16). */
    private Sim sim = new Sim();

    @Getter @Setter
    public static class Advance {
        /** Les of rule 1: absolute principal cap (BC p.10). */
        private BigDecimal maxPrincipal = new BigDecimal("25000");
        /** Rule 2: share of verified avg monthly net receipts (BC p.10). */
        private BigDecimal monthlyShare = new BigDecimal("0.20");
        /** Default repayment rate if the lender does not override (BC p.11). */
        private BigDecimal defaultRate = new BigDecimal("0.10");
    }

    @Getter @Setter
    public static class Recon {
        /** Block withdrawals/pay-later releases while a recon run has open breaks (BC p.18). */
        private boolean blockOnOpenBreaks = true;
        /** Holds older than this many minutes are stale (UAT SB p.15). */
        private long staleHoldMinutes = 30;
    }

    @Getter @Setter
    public static class Sim {
        /** Delay before the simulated bank confirms (~1.2 s feels like a real bank). */
        private long confirmDelayMs = 1200;
        /** Delay before the DUPLICATE replay arrives. */
        private long duplicateDelayMs = 800;
        /** First silence window before the TIMEOUT event (TC-ACC-05). */
        private long timeoutWindowMs = 4000;
        /** Delay before the late final answer after a timeout. */
        private long lateConfirmDelayMs = 4000;
    }

    public boolean isSimulatedEnvironment() {
        return !"production".equalsIgnoreCase(environment);
    }
}
