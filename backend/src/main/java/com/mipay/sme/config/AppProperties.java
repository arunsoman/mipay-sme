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
    /** Demo accounts and fixtures; disable on public sandbox hosts with real user data. */
    private boolean seedDemoData = true;
    private String devOtp = "123456";        // fixed OTP in sandbox for the UAT demos
    private String rbacUrl = "https://rbac:8443";  // trustplane RBAC mesh service

    /**
     * Identity mode (idp-rbac-cutover-plan.md §3): legacy → gateway.
     * The gateway filter is part of P3; legacy remains the default until it is installed.
     * The provider registry validates the setting at startup.
     */
    private String identityMode = "legacy";

    /**
     * Credential provider for the AuthProvider contract: none | trustplane.
     * When set, user lifecycle changes queue provider commands and enrolment replaces activation (P3).
     */
    private String authProvider = "none";

    /** Core-to-IdP mesh endpoint; the provider uses the core's mTLS certificate. */
    private String authUrl = "https://idp:8443";
    private String authIssuer = "https://idp.fapi.local:8443";

    /** Private, one-time portal installer credential. Empty disables creation. */
    private String installerPassword = "";

    /** Core-to-comms mesh endpoint (blank = comms plane not wired; setup links stay undeliverable). */
    private String commsUrl = "";

    /** Same raw HMAC key file the gateway reads when signing X-SME-Context. */
    private String gatewayContextSecretFile = "/pki/ctx-secret";

    /** When true, role and org changes are also written to RBAC assignments and grants (P3.11). */
    private boolean rbacSync = false;

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
