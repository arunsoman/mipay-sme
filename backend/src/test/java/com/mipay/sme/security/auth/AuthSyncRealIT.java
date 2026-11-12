package com.mipay.sme.security.auth;

import com.mipay.sme.domain.core.AuthOutbox;
import com.mipay.sme.domain.core.IdentityLink;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.repository.AuthOutboxRepository;
import com.mipay.sme.repository.IdentityLinkRepository;
import com.mipay.sme.repository.UserRepository;
import com.mipay.sme.security.Current;
import com.mipay.sme.security.rbac.RbacMeshClient;
import com.mipay.sme.service.ComplianceService;
import com.mipay.sme.service.OrgProvisioningService;
import com.mipay.sme.service.OrgSuspensionService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The reconcile against the real mesh, no mocks (§3.3b/§3.3d/§3.5): an org hold disables its
 * subtree's credentials at the real IdP and a lift re-enables them; a lapsed periodic review
 * holds the org exactly like a manual suspension and a completed review lifts it; the KYC
 * go-live swap grants org_admin in the real RBAC service and revokes kyc_applicant. Gateway
 * route end-to-end: a minted signed context resolves; a missing or forged context is refused.
 * Infra: auth-it Postgres (25433), the sme-mesh IdP (18443), comms (18444) and RBAC (18445).
 */
@ExtendWith(AuthSyncRealIT.RealInfra.class)
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.profiles.active=postgres",
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:25433/auth_it_sync",
        "spring.datasource.username=t",
        "spring.datasource.password=t",
        "app.identity-mode=gateway",
        "app.auth-provider=trustplane",
        "app.auth-url=https://127.0.0.1:18443",
        "app.comms-url=https://127.0.0.1:18444/comms/v1",
        "app.rbac-url=https://127.0.0.1:18445",
        "app.rbac-sync=true",
        "app.outbox.poll-millis=3600000",
        "app.gateway-context-secret-file=build/test-pki/_vol/ctx-secret"
})
class AuthSyncRealIT {

    static class RealInfra implements ExecutionCondition {
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext ctx) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", 25433), 500);
                return ConditionEvaluationResult.enabled("test Postgres reachable");
            } catch (Exception e) {
                return ConditionEvaluationResult.disabled("no Postgres at 127.0.0.1:25433 (auth-it infra)");
            }
        }
    }

    @Autowired AuthSyncService authSync;
    @Autowired UserLifecycleService lifecycle;
    @Autowired AuthOutboxProcessor processor;
    @Autowired OrgProvisioningService orgProvisioning;
    @Autowired OrgSuspensionService suspensions;
    @Autowired ComplianceService compliance;
    @Autowired RbacMeshClient rbac;
    @Autowired UserRepository users;
    @Autowired IdentityLinkRepository links;
    @Autowired AuthOutboxRepository outboxRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mockMvc;
    @Autowired com.mipay.sme.security.rbac.OrgTreeSyncService orgTreeSync;

    @BeforeAll
    static void provisionDb() throws Exception {
        try (var c = java.sql.DriverManager.getConnection("jdbc:postgresql://127.0.0.1:25433/postgres", "t", "t");
             var s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS auth_it_sync WITH (FORCE)");
            s.execute("CREATE DATABASE auth_it_sync");
        }
    }

    private Current.Principal system() {
        return new Current.Principal("sync-it-system", "TPTS_OPS", "Sync IT", null,
                null, null, null, null, null, null);
    }

    /**
     * The self-registration shape: a PENDING pre-binding user, then the entity row, then the
     * entity org — whose binding enqueues the real enrolment (exactly what the writer sweep
     * routed OnboardingService through).
     */
    private record Seeded(String orgId, String entityId, String adminId) { }

    private Seeded seedMerchant(String tag, boolean pending) {
        // the IdP keeps its accounts across IT runs: the phone must be fresh every run
        User admin = lifecycle.createPreBinding(Enums.UserRole.MERCHANT, "Merchant " + tag,
                "+264836" + String.format("%08d", new java.util.SplittableRandom().nextInt(100_000_000)),
                tag + "@sync.test", "sync-it");
        String id = admin.getId();
        jdbc.update("""
                INSERT INTO merchants (id, user_id, business_name, business_type, approval_status, kyc_status)
                VALUES (?, ?, ?, 'RETAIL', 'PENDING', 'PENDING')
                """, "mer-" + tag, id, "Sync IT " + tag);
        var org = orgProvisioning.ensureEntityOrg(Enums.OrgType.MERCHANT, "mer-" + tag, "Sync IT " + tag, id);
        tick();   // the binding's enrol completes against the real IdP
        AuthOutbox row = outboxRepo.findAll().stream()
                .filter(r -> id.equals(r.getUserId()) && "ENROL".equals(r.getCommand())).findFirst().orElseThrow();
        assertEquals(AuthOutbox.State.DONE, row.getState(), "bound user enrolled by the org binding: " + row.getLastError());
        if (!pending) {
            jdbc.update("UPDATE organizations SET status='ACTIVE' WHERE id=?", org.getId());
            jdbc.update("UPDATE users SET status='ACTIVE' WHERE id=?", id);
            jdbc.update("UPDATE identity_links SET status='ACTIVE', idp_enabled=true WHERE user_id=?", id);
            jdbc.update("UPDATE merchants SET approval_status='APPROVED' WHERE id=?", "mer-" + tag);
        }
        return new Seeded(org.getId(), "mer-" + tag, id);
    }

    private void tick() {
        processor.tick();
    }

    private IdentityLink linkOf(String uid) {
        return links.findByUserId(uid).stream().findFirst().orElseThrow();
    }

    @Test
    void orgSuspensionDisablesTheSubtreeAndLiftReEnablesIt() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 6);
        Seeded seeded = seedMerchant(tag, false);
        assertEquals(IdentityLink.Status.ACTIVE, linkOf(seeded.adminId()).getStatus());

        var row = suspensions.suspend(seeded.orgId(), "sync-it hold", system());
        tick();   // the afterCommit reconcile queued; the real IdP confirms
        assertTrue(outboxRepo.findAll().stream().anyMatch(r -> seeded.adminId().equals(r.getUserId())
                        && "DISABLE".equals(r.getCommand()) && r.getState() == AuthOutbox.State.DONE),
                "the hold disabled the IdP account");
        assertEquals(IdentityLink.Status.DISABLED, linkOf(seeded.adminId()).getStatus());

        suspensions.lift(row.id(), system());
        tick();
        assertTrue(outboxRepo.findAll().stream().anyMatch(r -> seeded.adminId().equals(r.getUserId())
                        && "ENABLE".equals(r.getCommand()) && r.getState() == AuthOutbox.State.DONE),
                "the lift re-enabled the account");
        assertEquals(IdentityLink.Status.ACTIVE, linkOf(seeded.adminId()).getStatus());
        assertEquals(Enums.UserStatus.ACTIVE, users.findById(seeded.adminId()).orElseThrow().getStatus());
    }

    @Test
    void lapsedReviewHoldsTheOrgAndCompletedReviewLiftsIt() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 6);
        Seeded seeded = seedMerchant(tag, false);
        jdbc.update("UPDATE merchants SET kyc_next_review_date = CURRENT_DATE - 30 WHERE id=?", seeded.entityId());

        assertTrue(authSync.enforceKycLapses().contains(seeded.orgId()),
                "the overdue merchant org was placed on a KYC_LAPSED hold");
        assertTrue(jdbc.queryForObject(
                "SELECT count(*) FROM suspensions WHERE org_id=? AND lifted_at IS NULL AND reason LIKE 'KYC_LAPSED%'",
                Integer.class, seeded.orgId()) >= 1);

        // a completed review lifts the lapse hold (manual holds would persist)
        compliance.applyRiskRating(merchantOf(seeded.entityId()), Enums.KycResult.PASS, "sync-it", "TPTS_OPS");
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM suspensions WHERE org_id=? AND lifted_at IS NULL",
                Integer.class, seeded.orgId()), "the lapse hold was lifted by the completed review");
    }

    private com.mipay.sme.domain.core.Merchant merchantOf(String entityId) {
        var m = new com.mipay.sme.domain.core.Merchant();
        m.setId(entityId);
        return m;   // applyRiskRating writes risk fields; the entity is keyed by id
    }

    @Test
    void goLiveSwapsKycApplicantForOrgAdminInTheRealRbac() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 6);
        Seeded seeded = seedMerchant(tag, true);
        orgTreeSync.sync();   // the org tenant must be known to RBAC before its first assignment
        var granted = rbac.assign(seeded.orgId(), Map.of("actor", "sync-it", "core_authorized", true,
                "subject", seeded.adminId(), "role", "kyc_applicant", "scope", "own",
                "reason", "applicant setup completed"));
        assertFalse(granted.isEmpty());

        authSync.applicantBecomesAdmin(seeded.orgId(), seeded.adminId(), "sync-it");

        var rows = rbac.assignments(seeded.orgId()).stream()
                .filter(r -> seeded.adminId().equals(String.valueOf(r.get("subject")))).toList();
        assertTrue(rows.stream().anyMatch(r -> "org_admin".equals(String.valueOf(r.get("role")))
                        && "ACTIVE".equalsIgnoreCase(String.valueOf(r.get("status")))),
                "org_admin granted at go-live: " + rows);
        assertFalse(rows.stream().anyMatch(r -> "kyc_applicant".equals(String.valueOf(r.get("role")))
                        && "ACTIVE".equalsIgnoreCase(String.valueOf(r.get("status")))),
                "kyc_applicant revoked at go-live: " + rows);
    }

    @Test
    void gatewayRouteResolvesWithAMintedContextAndIsRefusedWithoutOne() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 6);
        Seeded seeded = seedMerchant(tag, false);
        String subject = linkOf(seeded.adminId()).getSubject();

        byte[] key = java.nio.file.Files.readAllBytes(java.nio.file.Path.of("build/test-pki/_vol/ctx-secret"));
        var call = new GatewayTestContext.Call("https://idp.fapi.local:8443", subject, seeded.adminId(),
                "mfa", List.of(), System.currentTimeMillis() / 1000, "sync-it-sid", null);
        String header = GatewayTestContext.header(key, call);

        // end-to-end: filter verifies the HMAC, the resolver re-checks core state, the route answers
        mockMvc.perform(get("/api/orgs/{id}/auth/users", seeded.orgId()).header("X-SME-Context", header))
                .andExpect(status().isOk());
        // fail-closed: no header and a forged signature are refused
        mockMvc.perform(get("/api/orgs/{id}/auth/users", seeded.orgId()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/orgs/{id}/auth/users", seeded.orgId()).header("X-SME-Context", header + "xx"))
                .andExpect(status().isUnauthorized());
    }

    @AfterAll
    static void dropDb() throws Exception {
        try (var c = java.sql.DriverManager.getConnection("jdbc:postgresql://127.0.0.1:25433/postgres", "t", "t");
             var s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS auth_it_sync WITH (FORCE)");
        } catch (Exception ignored) { }
    }
}
