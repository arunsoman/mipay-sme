package trustplane.rbac;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import trustplane.rbac.config.RbacConfig;
import trustplane.rbac.core.*;
import trustplane.shared.ApiException;
import trustplane.shared.PostgresDsn;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The rebuilt RBAC against a scratch Postgres (TEST_RBAC_DSN): the G.7 worked example,
 * scopes over the org closure, org-type rows, holds (U6), entitlements and delegation,
 * static and dynamic SoD, four-eyes (D10), minimum holders, grid save rules, break-glass,
 * access reviews, the registry and the role pack (idp-rbac-cutover-plan.md P2 "done when").
 */
class RbacGridTest {

    JdbcTemplate db;
    Policy policy;
    RbacStore store;
    Decisions decisions;
    AccessService access;
    SchemaAndSeed seed;
    RbacConfig cfg;
    Ceilings ceilings;
    Map<String, String[]> tree;

    static final String SYS = AccessService.SYSTEM;

    RbacConfig config(String env) {
        return new RbacConfig("", ":0", ":0", "", "", "", "", System.getenv("TEST_RBAC_SEED"), 120, env, "", "sme-mipay-mesh");
    }

    void wire(String env) throws Exception {
        String raw = System.getenv("TEST_RBAC_DSN");
        if ("true".equalsIgnoreCase(System.getenv("REQUIRE_RBAC_DSN"))) {
            assertNotNull(raw, "TEST_RBAC_DSN is required for the RBAC test suite");
            assertFalse(raw.isBlank(), "TEST_RBAC_DSN is required for the RBAC test suite");
        }
        Assumptions.assumeTrue(raw != null && !raw.isBlank(), "TEST_RBAC_DSN not set (needs a scratch Postgres)");
        PostgresDsn dsn = PostgresDsn.parse(raw);
        DriverManagerDataSource ds = new DriverManagerDataSource(dsn.jdbcUrl(), dsn.user(), dsn.password());
        db = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        cfg = config(env);
        policy = new Policy(db);
        WatchHub hub = new WatchHub();
        ceilings = new Ceilings(db, policy);
        store = new RbacStore(db, hub, policy, cfg, ceilings);
        decisions = new Decisions(db, policy, ceilings);
        access = new AccessService(db, policy, decisions, store, cfg, ceilings);
        seed = new SchemaAndSeed(db, cfg, tx, policy);
    }

    @BeforeEach
    void setUp() throws Exception {
        wire("dev");
        db.execute("DROP SCHEMA public CASCADE");
        db.execute("CREATE SCHEMA public");
        // a leftover of the demo model must be removed by P2.0
        db.execute("CREATE TABLE suppliers (id TEXT PRIMARY KEY)");
        db.execute("CREATE TABLE role_permissions (role_name TEXT, permission_id TEXT)");
        seed.apply();
        // the org tree, as the core's sync sends it
        Map<String, String[]> orgs = new LinkedHashMap<>();
        orgs.put("plat", new String[]{"PLATFORM", null});
        orgs.put("namib", new String[]{"DISTRIBUTOR", "plat"});
        orgs.put("oka", new String[]{"MERCHANT", "namib"});
        orgs.put("oka2", new String[]{"MERCHANT", "namib"});
        orgs.put("kav", new String[]{"DISTRIBUTOR", "plat"});
        orgs.put("sib", new String[]{"MERCHANT", "kav"});
        orgs.put("lend", new String[]{"LENDER", "plat"});
        tree = orgs;
        sync(orgs, Map.of());
        assign("selma", "org_admin", "oka", "own");
        assign("tomas", "org_staff", "oka", "own");
        assign("jonas", "org_admin", "namib", "subtree");
        assign("ana", "platform_ops_reviewer", null, "all");
        assign("ua1", "platform_user_admin", null, "all");
        assign("ua2", "platform_user_admin", null, "all");
    }

    void sync(Map<String, String[]> orgs, Map<String, Boolean> held) {
        List<Map<String, Object>> closure = new ArrayList<>();
        List<RbacStore.OrgRow> rows = new ArrayList<>();
        for (var e : orgs.entrySet()) {
            int depth = 0;
            for (String cur = e.getKey(); cur != null; cur = orgs.get(cur)[1], depth++) {
                closure.add(Map.of("ancestor", cur, "descendant", e.getKey(), "depth", depth));
            }
            rows.add(new RbacStore.OrgRow(e.getKey(), e.getKey(), e.getValue()[0], e.getValue()[1], "ACTIVE",
                    held.getOrDefault(e.getKey(), false), held.getOrDefault(e.getKey(), false) ? List.of("MANUAL") : List.of()));
        }
        store.syncOrgs(closure, rows);
    }

    /** Re-syncs the tree with role ceilings: tenant -> [roles, delegable]; absent = no explicit ceiling. */
    void syncCeilings(Map<String, List<List<String>>> c) {
        List<Map<String, Object>> closure = new ArrayList<>();
        List<RbacStore.OrgRow> rows = new ArrayList<>();
        for (var e : tree.entrySet()) {
            int depth = 0;
            for (String cur = e.getKey(); cur != null; cur = tree.get(cur)[1], depth++) {
                closure.add(Map.of("ancestor", cur, "descendant", e.getKey(), "depth", depth));
            }
            List<List<String>> ce = c.get(e.getKey());
            rows.add(new RbacStore.OrgRow(e.getKey(), e.getKey(), e.getValue()[0], e.getValue()[1], "ACTIVE", false, List.of(),
                    true, ce == null ? null : ce.get(0), ce == null ? null : ce.get(1)));
        }
        store.syncOrgs(closure, rows);
    }

    Map<String, Object> assign(String subject, String role, String tenant, String scope) {
        return access.request(SYS, new AccessService.AssignRequest(subject, role, tenant, scope, null, "test"), "backfill");
    }

    Decisions.Decision check(String subject, String tenant, String op) {
        return decisions.check(new Decisions.Request(subject, tenant, op));
    }

    boolean allowed(String subject, String tenant, String op) {
        return check(subject, tenant, op).allowed();
    }

    static int status(Runnable r) {
        try {
            r.run();
            return 200;
        } catch (ApiException e) {
            return e.status;
        }
    }

    // ------------------------------------------------------------------

    @Test
    void demoModelIsGoneAndSeedIsLoaded() {
        assertEquals(0, db.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name IN ('suppliers','role_permissions','users')", Integer.class));
        Set<String> roles = policy.get().roles().keySet();
        for (String r : List.of("kyc_applicant", "org_viewer", "org_staff", "org_admin", "platform_ops_reviewer", "platform_operations",
                "platform_user_admin", "platform_engineer", "platform_compliance_officer", "platform_mlro", "platform_senior_approver",
                "platform_auditor", "platform_break_glass", "lender_officer", "sandbox_tester")) {
            assertTrue(roles.contains(r), "missing role " + r);
        }
        assertFalse(roles.contains("platform_admin"), "no superuser (D9)");
        // the G.6 example mask: org_admin on kyc.application = read+modify+delete+submit = 29
        assertEquals(29, policy.get().mask("org_admin", "kyc.application") & 31);
    }

    @Test
    void workedExampleG7() {
        assertTrue(allowed("selma", "oka", "kyc.application:modify"), "1. Selma saves the KYC draft");
        assertTrue(allowed("selma", "oka", "kyc.application.modify"), "design spelling row.verb accepted");
        assertFalse(allowed("tomas", "oka", "merchant.withdrawal:execute"), "2. Tomas cannot withdraw (org_staff: read only)");
        assertTrue(allowed("tomas", "oka", "merchant.withdrawal:read"));
        assertTrue(allowed("tomas", "oka", "merchant.inventory:create"));
        assertTrue(allowed("jonas", "oka", "kyc.application:modify"), "3. Jonas edits his child org's draft (subtree)");
        assertFalse(allowed("jonas", "sib", "kyc.application:read"), "3. ...but not a sibling under another distributor");
        assertFalse(allowed("selma", "oka2", "kyc.application:read"), "own scope does not reach a sibling");
        assertTrue(allowed("ana", "", "kyc.task:authorize"), "4. Ops-Ana decides (platform, any tenant)");
        assertTrue(allowed("ana", "oka", "kyc.task:authorize"));
        assertFalse(allowed("selma", "oka", "kyc.task:authorize"), "5. Selma never decides tasks");
        assertEquals(409, status(() -> assign("ana", "platform_user_admin", null, "all")), "6. SoD: admins don't approve customers");
        Decisions.Decision d = check("selma", "oka", "kyc.application:submit");
        assertTrue(d.decisionId().startsWith("d-") && d.reason().contains("org_admin@own(oka)"), d.reason());
    }

    @Test
    void rowsApplyOnlyToTheirOrgType() {
        assertFalse(allowed("jonas", "namib", "merchant.inventory:create"), "merchant rows never apply in a distributor org");
        assertTrue(check("jonas", "namib", "merchant.inventory:create").reason().startsWith("row_not_applicable"));
        assertTrue(allowed("jonas", "oka", "merchant.inventory:create"), "...but do in a merchant below it");
        assign("lena", "org_admin", "lend", "own");
        assertTrue(allowed("lena", "lend", "lender.advance:authorize"));
        assertFalse(allowed("lena", "lend", "merchant.withdrawal:execute"));
        assertFalse(allowed("selma", "", "lender.advance:authorize"), "own merchant assignment never acts as a lender through 'any tenant'");
        assertFalse(allowed("selma", "nowhere", "kyc.application:read"));
        assertEquals("unknown_tenant:nowhere", check("selma", "nowhere", "kyc.application:read").reason());
        assertEquals("bad_operation", check("selma", "oka", "kyc.application:approve").reason(), "4 verbs are not the grid");
    }

    @Test
    void holdsBlockTheSubtreeButNotPlatformStaff() {
        Map<String, String[]> orgs = new LinkedHashMap<>();
        orgs.put("plat", new String[]{"PLATFORM", null});
        orgs.put("namib", new String[]{"DISTRIBUTOR", "plat"});
        orgs.put("oka", new String[]{"MERCHANT", "namib"});
        orgs.put("oka2", new String[]{"MERCHANT", "namib"});
        orgs.put("kav", new String[]{"DISTRIBUTOR", "plat"});
        orgs.put("sib", new String[]{"MERCHANT", "kav"});
        orgs.put("lend", new String[]{"LENDER", "plat"});
        sync(orgs, Map.of("namib", true));
        Decisions.Decision d = check("selma", "oka", "kyc.application:read");
        assertFalse(d.allowed(), "a hold on the parent blocks the child (U3)");
        assertTrue(d.reason().startsWith("tenant_held:namib"), d.reason());
        assertFalse(allowed("jonas", "oka", "kyc.application:read"));
        assertFalse(allowed("selma", "", "kyc.application:read"), "'any tenant' skips held orgs too");
        assertTrue(allowed("ana", "oka", "kyc.task:authorize"), "platform staff are not affected (U6)");
        sync(orgs, Map.of());
        assertTrue(allowed("selma", "oka", "kyc.application:read"), "lifting the hold restores access by itself");
    }

    @Test
    void entitlementsAndNarrowOnlyDelegation() {
        Map<String, String> merchant = Map.of("child_type", "MERCHANT");
        Decisions.Decision d = decisions.check(new Decisions.Request("jonas", "namib", "org.onboarding:create", List.of(), merchant));
        assertFalse(d.allowed());
        assertTrue(d.reason().startsWith("no_grant"), d.reason());
        assertEquals("attribute_required:child_type", check("jonas", "namib", "org.onboarding:create").reason());
        Map<String, Object> root = access.grant(SYS, new AccessService.GrantRequest("namib", "org.onboarding:create",
                List.of("MERCHANT", "SUPPLIER"), "subtree", true, null, null), true);
        assertTrue(decisions.check(new Decisions.Request("jonas", "namib", "org.onboarding:create", List.of(), merchant)).allowed());
        assertFalse(decisions.check(new Decisions.Request("jonas", "namib", "org.onboarding:create", List.of(), Map.of("child_type", "LENDER"))).allowed());
        // jonas (org_admin@subtree namib) delegates MERCHANT only to oka; wider is refused
        assertEquals(403, status(() -> access.grant("jonas", new AccessService.GrantRequest("oka", "org.onboarding:create",
                List.of("LENDER"), "subtree", false, (String) root.get("id"), null), false)));
        Map<String, Object> child = access.grant("jonas", new AccessService.GrantRequest("oka", "org.onboarding:create",
                List.of("MERCHANT"), "own", false, (String) root.get("id"), null), false);
        assertEquals("active", child.get("status"));
        assertEquals(403, status(() -> access.grant("selma", new AccessService.GrantRequest("namib", "org.onboarding:create",
                List.of(), "own", false, null, null), false)), "root grants are platform-only");
        access.revokeGrant(SYS, (String) root.get("id"), true);
        assertEquals("revoked", access.grantView((String) child.get("id")).get("status"), "revocation cascades down");
    }

    @Test
    void staticAndDynamicSeparationOfDuty() {
        assertEquals(409, status(() -> assign("ana", "platform_senior_approver", null, "all")), "two review stages, two people");
        assertEquals(409, status(() -> assign("ua1", "platform_mlro", null, "all")), "MLRO independent of access admin");
        assertEquals(409, status(() -> assign("ana", "platform_break_glass", null, "all")), "break-glass is a separate identity");
        // DSD: holding both review stages is allowed, using both in one session is not
        assign("ana", "platform_compliance_officer", null, "all");
        Decisions.Decision none = check("ana", "oka", "kyc.task:authorize");
        assertFalse(none.allowed());
        assertEquals("activation_required", none.reason());
        assertTrue(decisions.check(new Decisions.Request("ana", "oka", "kyc.task:authorize", List.of("platform_ops_reviewer"), Map.of())).allowed());
        assertEquals("dsd_conflict:dsd-one-review-stage-per-session", decisions.check(new Decisions.Request("ana", "oka", "kyc.task:authorize",
                List.of("platform_ops_reviewer", "platform_compliance_officer"), Map.of())).reason());
        assertNotNull(decisions.validateActivation("ana", List.of("platform_ops_reviewer", "platform_compliance_officer")));
        assertNull(decisions.validateActivation("ana", List.of("platform_compliance_officer")));
        assertTrue(allowed("ana", "oka", "kyc.application:read"), "auditor reads stay usable while stages wait for activation");
    }

    @Test
    void fourEyesByTheRiskOfTheRole() {
        // platform role: requested by a user admin, approved by a SECOND user admin
        Map<String, Object> req = access.request("ua1", new AccessService.AssignRequest("bob", "platform_operations", null, "all", null, "ops desk"), "api");
        assertEquals("PENDING_APPROVAL", req.get("status"));
        assertFalse(allowed("bob", "", "ops.incident:create"), "pending is powerless");
        String id = (String) req.get("id");
        assertEquals(403, status(() -> access.decide("ua1", id, true, null)), "never the requester");
        assertEquals(403, status(() -> access.decide("bob", id, true, null)), "never the grantee");
        assertEquals(403, status(() -> access.decide("ana", id, true, null)), "only an eligible approver");
        assertEquals("ACTIVE", access.decide("ua2", id, true, "ok").get("status"));
        assertTrue(allowed("bob", "", "ops.incident:create"));
        assertEquals(403, status(() -> access.request("selma", new AccessService.AssignRequest("x", "platform_auditor", null, "all", null, ""), "api")),
                "only a platform_user_admin requests platform roles");

        // org_admin: no second admin in oka -> an org_admin of the parent (jonas) approves
        Map<String, Object> r2 = access.request("selma", new AccessService.AssignRequest("tomas", "org_admin", "oka", "own", null, ""), "api");
        assertEquals("PENDING_APPROVAL", r2.get("status"));
        assertEquals(403, status(() -> access.decide("ua1", (String) r2.get("id"), true, null)), "the fallback stops at the parent admin");
        assertEquals("ACTIVE", access.decide("jonas", (String) r2.get("id"), true, null).get("status"));
        // org_staff / org_viewer need no second approval
        assertEquals("ACTIVE", access.request("selma", new AccessService.AssignRequest("vera", "org_viewer", "oka", "own", null, ""), "api").get("status"));
        assertEquals(403, status(() -> access.request("selma", new AccessService.AssignRequest("vera", "org_viewer", "oka2", "own", null, ""), "api")),
                "an org admin assigns only in their own org");

        // compliance roles: approved by the MLRO; granting the MLRO: by a senior approver
        assign("mo", "platform_mlro", null, "all");
        Map<String, Object> r3 = access.request("ua1", new AccessService.AssignRequest("cleo", "platform_compliance_officer", null, "all", null, ""), "api");
        assertEquals(403, status(() -> access.decide("ua2", (String) r3.get("id"), true, null)));
        assertEquals("ACTIVE", access.decide("mo", (String) r3.get("id"), true, null).get("status"));
        Map<String, Object> r4 = access.request("ua1", new AccessService.AssignRequest("max", "platform_mlro", null, "all", null, ""), "api");
        assertEquals(403, status(() -> access.decide("mo", (String) r4.get("id"), true, null)));
        assign("sam", "platform_senior_approver", null, "all");
        assertEquals("ACTIVE", access.decide("sam", (String) r4.get("id"), true, null).get("status"));
        // requests expire after 72 h
        Map<String, Object> r5 = access.request("ua1", new AccessService.AssignRequest("old", "platform_auditor", null, "all", null, ""), "api");
        db.update("UPDATE assignments SET request_expires_at = now() - interval '1 minute' WHERE id=?", r5.get("id"));
        assertEquals(409, status(() -> access.decide("ua2", (String) r5.get("id"), true, null)));
    }

    @Test
    void anOrgKeepsAnAdmin() {
        String selmaAdmin = (String) access.list("selma", "oka", "ACTIVE", "org_admin").get(0).get("id");
        assertEquals(409, status(() -> access.revoke("jonas", selmaAdmin, "", false)), "minimum holders");
        access.decide("jonas", (String) access.request("selma", new AccessService.AssignRequest("tomas", "org_admin", "oka", "own", null, ""), "api").get("id"), true, null);
        assertEquals("REVOKED", access.revoke("jonas", selmaAdmin, "left the company", false).get("status"));
        assertFalse(allowed("selma", "oka", "kyc.application:modify"));
        assertEquals(403, status(() -> access.revoke("tomas", (String) access.list("ana", "", "ACTIVE", "").get(0).get("id"), "", false)),
                "an org admin cannot revoke platform access");
        assertTrue(access.revokeForTenant("namib", "KYC terminated") >= 2, "the core removes a terminated subtree's assignments");
        assertFalse(allowed("jonas", "oka", "kyc.application:read"));
    }

    @Test
    void gridSaveRules() {
        // a row whose code uses both submit and authorize: one role may not tick both (maker = checker)
        store.registerCatalog("testsvc", List.of(Map.of("id", "test.flow:submit", "description", ""), Map.of("id", "test.flow:authorize", "description", "")),
                List.of(new RbacStore.EndpointReg("POST", "/t/submit", "test.flow:submit", "NONE", "T#s"),
                        new RbacStore.EndpointReg("POST", "/t/approve", "test.flow:authorize", "NONE", "T#a")));
        assertEquals(409, status(() -> store.createRole("ua1", "maker_checker", "", "ORG",
                store.parseGrid(Map.of("test.flow", List.of("submit", "authorize")), null), List.of())));
        assertEquals(200, status(() -> store.createRole("ua1", "maker_only", "", "ORG",
                store.parseGrid(Map.of("test.flow", List.of("submit")), null), List.of())));
        // across a user's roles on one org, too
        assign("mia", "maker_only", "oka", "own");
        store.createRole("ua1", "checker_only", "", "ORG", store.parseGrid(Map.of("test.flow", List.of("authorize")), null), List.of());
        assertEquals(409, status(() -> assign("mia", "checker_only", "oka", "own")));
        assertEquals(200, status(() -> assign("mia", "checker_only", "oka2", "own")), "different orgs are fine");
        // the exempt row (merchant.advance) may carry both, with the reason in the pack
        assertTrue((policy.get().mask("org_admin", "merchant.advance") & (16 | 32)) == (16 | 32));
        assertEquals(409, status(() -> store.createRole("ua1", "bad_platform", "", "PLATFORM",
                store.parseGrid(Map.of("ops.suspension", List.of("administer"), "ops.merchant_approval", List.of("authorize")), null), List.of())));
        assertEquals(400, status(() -> store.createRole("ua1", "phantom", "", "ORG",
                store.parseGrid(Map.of("merchant.account", List.of("delete")), null), List.of())), "a cell no endpoint uses can't be ticked");
        assertEquals(409, status(() -> store.updateRole("ua1", "org_viewer", null, null, List.of("org_admin"))), "hierarchy cycles refused");
        // a valid edit is applied and reaches decisions at once
        store.updateRole("ua1", "org_viewer", null, store.parseGrid(Map.of("merchant.account", List.of("read")), null), null);
        assertFalse(allowed("vera2", "oka", "merchant.account:read"));
        assign("vera2", "org_viewer", "oka", "own");
        assertTrue(allowed("vera2", "oka", "merchant.account:read"));
        assertFalse(allowed("vera2", "oka", "kyc.application:read"), "removed by the edit");
        assertEquals(409, status(() -> store.deleteRole("ua1", "org_viewer")), "seeded roles change through the seed");
    }

    @Test
    void productionRefusesSimulation() throws Exception {
        RbacConfig prod = config("production");
        RbacStore prodStore = new RbacStore(db, new WatchHub(), policy, prod, ceilings);
        AccessService prodAccess = new AccessService(db, policy, decisions, prodStore, prod, ceilings);
        assertEquals(409, status(() -> prodStore.createRole("ua1", "sim_role", "", "ORG",
                prodStore.parseGrid(Map.of("merchant.payment", List.of("simulate")), null), List.of())));
        assertEquals(409, status(() -> prodAccess.request(SYS, new AccessService.AssignRequest("t", "sandbox_tester", "oka", "own", null, ""), "backfill")));
    }

    @Test
    void breakGlassIsSelfRequestedApprovedAndTimeBoxed() {
        assertEquals(403, status(() -> access.request("ua1", new AccessService.AssignRequest("bg", "platform_break_glass", null, "all", null, ""), "api")));
        assertEquals(400, status(() -> access.request("bg", new AccessService.AssignRequest("bg", "platform_break_glass", null, "all",
                Instant.now().plus(9, ChronoUnit.HOURS), "outage"), "api")), "at most 8 h");
        Map<String, Object> r = access.request("bg", new AccessService.AssignRequest("bg", "platform_break_glass", null, "all", null, "outage"), "api");
        assertEquals("PENDING_APPROVAL", r.get("status"));
        assertNotNull(r.get("expires_at"), "always time-boxed");
        assertEquals("ACTIVE", access.decide("ua1", (String) r.get("id"), true, "incident 42").get("status"));
        assertTrue(allowed("bg", "oka", "org.user:administer"));
        assertFalse(allowed("bg", "oka", "merchant.withdrawal:execute"), "break-glass restores access, it does not move money");
        assertEquals(1, db.queryForObject("SELECT count(*) FROM audit_log WHERE action='break_glass_activated'", Integer.class));
    }

    @Test
    void accessReviews() {
        assertEquals(403, status(() -> access.createReview("selma", "oka", "Q4")), "org admins decide reviews, platform opens them");
        Map<String, Object> rv = access.createReview("ua1", "oka", "Q4");
        String tomasStaff = (String) access.list("tomas", "oka", "ACTIVE", "org_staff").get(0).get("id");
        String selmaAdmin = (String) access.list("selma", "oka", "ACTIVE", "org_admin").get(0).get("id");
        assertEquals(403, status(() -> access.decideReviewItem("selma", (String) rv.get("id"), selmaAdmin, "CONFIRMED")), "nobody reviews their own access");
        access.decideReviewItem("selma", (String) rv.get("id"), tomasStaff, "REMOVED");
        assertFalse(allowed("tomas", "oka", "merchant.inventory:create"));
        // overdue: unconfirmed items expire, but the last org_admin is kept
        db.update("UPDATE reviews SET due_at = now() - interval '1 minute' WHERE id=?", rv.get("id"));
        access.sweep();
        assertTrue(allowed("selma", "oka", "kyc.application:modify"), "minimum holders beats review expiry");
        assertEquals("CLOSED", access.review((String) rv.get("id")).get("status"));
    }

    @Test
    void registryAndRolePack() {
        Map<String, Object> r = store.registerCatalog("mipay", List.of(Map.of("id", "kyc.application:modify", "description", "save")),
                List.of(new RbacStore.EndpointReg("PUT", "/api/kyc/orgs/{id}", "kyc.application:modify", "PATH", "KycController#save")));
        assertEquals(1, r.get("endpoints"));
        List<String> orphans = store.orphanTicks();
        assertTrue(orphans.stream().anyMatch(o -> o.contains("kyc.application:submit")), "ticks without endpoints are reported");
        String md = new RolePack(db, policy).render("dev");
        assertTrue(md.contains("## `org_admin`") && md.contains("PUT /api/kyc/orgs/{id}") && md.contains("Approved for use"));
        assertTrue(md.contains("ssd-access-admin-not-reviewer"));
        // listPermissions gives the UI masks and the gateway's flat list
        Decisions.Permissions p = decisions.listPermissions("tomas", "oka", List.of());
        assertTrue(p.operations().contains("merchant.inventory:create"));
        assertFalse(p.operations().contains("merchant.withdrawal:execute"));
        assertTrue(decisions.holders("org_admin", "oka").stream().anyMatch(h -> h.subject().equals("jonas")), "subtree admins hold above");
    }

    @Test
    void seedIsIdempotentAndKeepsApiEdits() throws Exception {
        store.updateRole("ua1", "org_viewer", null, store.parseGrid(Map.of("org.tree", List.of("read")), null), null);
        seed.apply();
        assertEquals(1, policy.get().roles().get("org_viewer").grid().size(), "an unchanged seed keeps the edit");
        db.update("UPDATE roles SET seed_hash='changed' WHERE name='org_viewer'");
        seed.apply();
        assertTrue(policy.get().roles().get("org_viewer").grid().size() > 10, "a changed seed is authoritative again");
    }

    // ------------------------------------------------------------------
    // Extended RBAC (docs/plans/extended-rbac.md): role ceilings and org-defined roles
    // ------------------------------------------------------------------

    @Test
    void roleCeilingsInheritClampAndBindDecisions() throws Exception {
        assertTrue(allowed("tomas", "oka", "merchant.inventory:create"), "no ceiling anywhere: unrestricted, as today");
        assertNull(ceilings.of("oka").roles());

        // Namib may use staff + viewer and hand down only viewer; Oka has no explicit ceiling: it inherits
        syncCeilings(Map.of("namib", List.of(List.of("org_staff", "org_viewer"), List.of("org_viewer"))));
        Ceilings.Effective namib = ceilings.of("namib");
        assertTrue(namib.explicit());
        assertEquals(Set.of("org_staff", "org_viewer", "kyc_applicant", "org_admin"), namib.roles(), "baseline always inside");
        Ceilings.Effective oka = ceilings.of("oka");
        assertFalse(oka.explicit());
        assertEquals("namib", oka.from());
        assertEquals(Set.of("org_viewer", "kyc_applicant", "org_admin"), oka.roles());

        // decision time: Tomas's org_staff on Oka stops working on the next request (X7)
        Decisions.Decision d = check("tomas", "oka", "merchant.inventory:create");
        assertFalse(d.allowed());
        assertEquals("role_outside_ceiling:org_staff@oka", d.reason());
        assertFalse(decisions.listPermissions("tomas", "oka", List.of()).operations().contains("merchant.inventory:create"));
        // assignment time: refused, system writes too
        assertEquals(409, status(() -> assign("nina", "org_staff", "oka", "own")));
        assertEquals("ACTIVE", assign("nina", "org_viewer", "oka", "own").get("status"));
        assertTrue(allowed("nina", "oka", "merchant.inventory:read"));
        // the baseline is never cut: Oka's admin keeps everything org_admin inherits
        assertTrue(allowed("selma", "oka", "merchant.inventory:create"));
        // platform assignments are exempt (U6)
        assertTrue(allowed("ana", "oka", "kyc.task:read"));

        // a child wider than its parent is clamped on arrival (X4)
        syncCeilings(Map.of("namib", List.of(List.of("org_staff", "org_viewer"), List.of("org_viewer")),
                "oka2", List.of(List.of("org_staff", "org_viewer"), List.of("org_staff", "org_viewer"))));
        assertEquals(Set.of("org_viewer"), Set.copyOf(Arrays.asList((String[]) db.queryForObject(
                "SELECT role_ceiling FROM tenants WHERE id='oka2'", java.sql.Array.class).getArray())));
        assertEquals(Set.of("org_viewer", "kyc_applicant", "org_admin"), ceilings.of("oka2").roles());

        // dropping the ceiling restores today's access
        syncCeilings(Map.of());
        assertTrue(allowed("tomas", "oka", "merchant.inventory:create"));
        // a sync without ceilings (an older core) leaves them alone
        syncCeilings(Map.of("namib", List.of(List.of("org_viewer"), List.of())));
        sync(tree, Map.of());
        assertTrue(ceilings.of("namib").explicit());
    }

    @Test
    void orgDefinedRolesStayInsideBaseRolesAndCeiling() {
        syncCeilings(Map.of("oka", List.of(List.of("org_staff", "org_viewer"), List.of())));
        Map<String, Integer> clerk = store.parseGrid(Map.of("merchant.inventory", List.of("read", "create")), null);
        Map<String, Object> r = store.createOrgRole("selma", "oka", "stock_clerk", "Stock clerk", List.of("org_staff"), clerk);
        assertEquals("stock_clerk@oka", r.get("name"));
        assertEquals("oka", r.get("owner_tenant"));
        assertEquals("none", policy.get().roles().get("stock_clerk@oka").approval());

        // carved from its base roles only
        assertEquals(409, status(() -> store.createOrgRole("selma", "oka", "payer", "", List.of("org_staff"),
                store.parseGrid(Map.of("merchant.withdrawal", List.of("execute")), null))), "org_admin's cell is not in org_staff");
        assertEquals(400, status(() -> store.createOrgRole("selma", "oka", "bad", "", List.of("platform_auditor"), clerk)));
        assertEquals(400, status(() -> store.createOrgRole("selma", "oka", "Bad Name", "", List.of("org_staff"), clerk)));

        // assignable only in its own org; works there, narrower than its base
        assertEquals(409, status(() -> assign("pia", "stock_clerk@oka", "oka2", "own")));
        assertEquals("ACTIVE", assign("pia", "stock_clerk@oka", "oka", "own").get("status"));
        assertTrue(allowed("pia", "oka", "merchant.inventory:create"));
        assertFalse(allowed("pia", "oka", "merchant.tab:create"), "org_staff has it, the org role does not");
        assertFalse(allowed("pia", "oka2", "merchant.inventory:read"));

        // bounded at decision time (X10): a grid edited behind the API still cannot exceed the base
        db.update("INSERT INTO rbac_role_grid (role, resource, verbs) VALUES ('stock_clerk@oka','merchant.withdrawal',?)", Verbs.bit("execute"));
        policy.reload();
        assertFalse(allowed("pia", "oka", "merchant.withdrawal:execute"));

        // narrowing the ceiling switches it off on the next request
        syncCeilings(Map.of("oka", List.of(List.of("org_viewer"), List.of())));
        assertEquals("role_outside_ceiling:stock_clerk@oka", check("pia", "oka", "merchant.inventory:read").reason());
        assertEquals(409, status(() -> store.createOrgRole("selma", "oka", "clerk2", "", List.of("org_staff"), clerk)));
        Map<String, Object> view = store.rolesFor("oka");
        assertEquals(List.of("stock_clerk@oka"), ((List<Map<String, Object>>) view.get("org_roles")).stream().map(m -> m.get("name")).toList());
        assertEquals(false, ((List<Map<String, Object>>) view.get("org_roles")).get(0).get("in_ceiling"));
        syncCeilings(Map.of());
        assertTrue(allowed("pia", "oka", "merchant.inventory:create"));

        // SoD: base roles never span both sides of a rule; an org role counts as its bases (X9)
        db.update("INSERT INTO sod_rules (id, kind, roles_a, roles_b, reason) VALUES ('ssd-test','STATIC','{org_staff}','{kyc_applicant}','test')");
        policy.reload();
        assertEquals(409, status(() -> store.createOrgRole("selma", "oka", "mixed", "", List.of("org_staff", "kyc_applicant"), clerk)));
        assertEquals(409, status(() -> assign("pia", "kyc_applicant", "oka", "own")), "pia holds org_staff through stock_clerk@oka");

        // in use: not deletable; listed in the role pack for the access review
        assertEquals(409, status(() -> store.deleteRole("selma", "stock_clerk@oka")));
        String md = new RolePack(db, policy).render("dev");
        assertTrue(md.contains("`stock_clerk@oka` | oka | `org_staff`"));
        assertFalse(md.contains("## `stock_clerk@oka`"), "not a role for separate sign-off");
    }

    @Test
    void onBehalfRequestsKeepFourEyesAndCeilings() {
        // the core authorized the actor itself: no RBAC cell needed, but four-eyes still applies to org_admin
        Map<String, Object> r = access.request("core-user-7", new AccessService.AssignRequest("tomas", "org_admin", "oka", "own", null, ""),
                "core_portal", true);
        assertEquals("PENDING_APPROVAL", r.get("status"));
        assertEquals(403, status(() -> access.request("core-user-7", new AccessService.AssignRequest("vera", "org_viewer", "oka", "own", null, ""),
                "core_portal", false)), "without core authorization the actor needs the cell");
        syncCeilings(Map.of("oka", List.of(List.of("org_viewer"), List.of())));
        assertEquals(409, status(() -> access.request("core-user-7", new AccessService.AssignRequest("vera", "org_staff", "oka", "own", null, ""),
                "core_portal", true)));
    }

    @Test
    void orgDefinedRolesAreRetiredWithTheirOrgButKeepTheirHistory() {
        Map<String, Integer> clerk = store.parseGrid(Map.of("merchant.inventory", List.of("read", "create")), null);
        store.createOrgRole("selma", "oka", "stock_clerk", "", List.of("org_staff"), clerk);
        store.createOrgRole("jonas", "oka2", "counter", "", List.of("org_viewer"),
                store.parseGrid(Map.of("merchant.inventory", List.of("read")), null));
        String asg = (String) assign("pia", "stock_clerk@oka", "oka", "own").get("id");
        assertTrue(allowed("pia", "oka", "merchant.inventory:create"));

        // the core's revoke-all (KYC terminated / org archived) retires the org's own roles too
        access.revokeForTenant("oka", "kyc rejected");
        Policy.Role r = policy.get().roles().get("stock_clerk@oka");
        assertTrue(r.retired());
        assertEquals("REVOKED", access.view(asg).get("status"), "history kept, not deleted");
        assertFalse(allowed("pia", "oka", "merchant.inventory:read"));
        assertEquals(409, status(() -> assign("pia", "stock_clerk@oka", "oka", "own")));
        assertEquals(409, status(() -> store.updateOrgRole("selma", "stock_clerk@oka", "x", null, null)));
        assertTrue(((List<?>) store.rolesFor("oka").get("org_roles")).isEmpty());
        // even an assignment written behind the API stays unusable
        db.update("UPDATE assignments SET status='ACTIVE' WHERE id=?", asg);
        assertEquals("role_retired:stock_clerk@oka", check("pia", "oka", "merchant.inventory:read").reason());

        // an org that reaches a terminal state through the org-tree sync loses its roles as well
        assertFalse(policy.get().roles().get("counter@oka2").retired());
        List<Map<String, Object>> closure = new ArrayList<>();
        List<RbacStore.OrgRow> rows = new ArrayList<>();
        for (var e : tree.entrySet()) {
            int depth = 0;
            for (String cur = e.getKey(); cur != null; cur = tree.get(cur)[1], depth++) {
                closure.add(Map.of("ancestor", cur, "descendant", e.getKey(), "depth", depth));
            }
            rows.add(new RbacStore.OrgRow(e.getKey(), e.getKey(), e.getValue()[0], e.getValue()[1],
                    "oka2".equals(e.getKey()) ? "CLOSED" : "ACTIVE", false, List.of()));
        }
        store.syncOrgs(closure, rows);
        assertTrue(policy.get().roles().get("counter@oka2").retired());
        String md = new RolePack(db, policy).render("dev");
        assertFalse(md.contains("stock_clerk@oka") || md.contains("counter@oka2"), "retired roles leave the role pack");
    }
}
