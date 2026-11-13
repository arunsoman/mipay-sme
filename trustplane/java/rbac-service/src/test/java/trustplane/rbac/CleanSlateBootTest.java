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
import trustplane.shared.PostgresDsn;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The boot contract since 2026-11-13 (the yaml seed is retired): {@code apply()} leaves the grid
 * EMPTY - no roles, no assignments, no SoD rules - and the code's own registration is the only
 * source of permission rows. On a clean slate the bootstrap window allows every check (the
 * installer needs this to create the first org, the first role and its assignment), and the
 * window closes with the first assignment.
 */
class CleanSlateBootTest {

    JdbcTemplate db;
    RbacStore store;
    Decisions decisions;
    AccessService access;
    SchemaAndSeed seed;

    @BeforeEach
    void setUp() throws Exception {
        String raw = System.getenv("TEST_RBAC_DSN");
        Assumptions.assumeTrue(raw != null && !raw.isBlank(), "TEST_RBAC_DSN not set (needs a scratch Postgres)");
        PostgresDsn dsn = PostgresDsn.parse(raw);
        DriverManagerDataSource ds = new DriverManagerDataSource(dsn.jdbcUrl(), dsn.user(), dsn.password());
        db = new JdbcTemplate(ds);
        db.execute("DROP SCHEMA public CASCADE");
        db.execute("CREATE SCHEMA public");
        wire(ds);
    }

    void wire(DriverManagerDataSource ds) {
        RbacConfig cfg = new RbacConfig("", ":0", ":0", "", "", "", "", 120, "dev", "", "sme-mipay-mesh", null);
        Policy policy = new Policy(db);
        Ceilings ceilings = new Ceilings(db, policy);
        store = new RbacStore(db, new WatchHub(), policy, cfg, ceilings);
        decisions = new Decisions(db, policy, ceilings);
        access = new AccessService(db, policy, decisions, store, cfg, ceilings);
        seed = new SchemaAndSeed(db, cfg, new TransactionTemplate(new DataSourceTransactionManager(ds)), policy);
    }

    /** What the backend's EndpointManifestPublisher sends at boot. */
    void registerCode() {
        List<Map<String, String>> perms = List.of(
                Map.of("id", "merchant.withdrawal:execute", "description", "Withdraw to the bank"),
                Map.of("id", "merchant.withdrawal:read", "description", "See withdrawals"),
                Map.of("id", "org.user:administer", "description", "Manage users"),
                Map.of("id", "org.user:read", "description", "See users"),
                Map.of("id", "org.onboarding:create", "description", "Onboard a child org"));
        List<RbacStore.EndpointReg> eps = new ArrayList<>();
        for (var p : perms) eps.add(new RbacStore.EndpointReg("POST", "/x/" + p.get("id"), p.get("id"), "SELF", "h"));
        store.registerCatalog("mipay", perms, eps, List.of(
                Map.of("id", "merchant.withdrawal", "description", "Merchant withdrawal"),
                Map.of("id", "org.user", "description", "Org user"),
                Map.of("id", "org.onboarding", "description", "Org onboarding")));
    }

    @Test
    void bootLeavesTheGridEmpty() throws Exception {
        seed.apply();
        assertEquals(0, db.queryForObject("SELECT count(*) FROM roles", Integer.class), "no roles are seeded");
        assertEquals(0, db.queryForObject("SELECT count(*) FROM assignments", Integer.class), "no assignments are seeded");
        assertEquals(0, db.queryForObject("SELECT count(*) FROM rbac_resources", Integer.class), "no rows are seeded");
        assertEquals(0, db.queryForObject("SELECT count(*) FROM sod_rules", Integer.class), "no SoD rules are seeded");
        assertEquals(0, db.queryForObject("SELECT count(*) FROM ceiling_baseline", Integer.class));
        registerCode(); // the only source of rows: the code's own registration
        assertEquals(List.of("merchant.withdrawal", "org.onboarding", "org.user"),
                db.queryForList("SELECT id FROM rbac_resources ORDER BY id", String.class));
    }

    @Test
    void bootstrapWindowAllowsEverythingThenCloses() throws Exception {
        seed.apply();
        registerCode();
        // the clean-slate window: every check is allowed, whatever the subject, tenant or cell
        assertTrue(decisions.check(new Decisions.Request("whoever", "tpts", "org.user:administer")).allowed(),
                "empty grid + no assignments: the window is open");
        assertEquals("bootstrap_clean_slate",
                decisions.check(new Decisions.Request("whoever", "tpts", "org.user:administer")).reason());
        assertTrue(decisions.checkPlatform("whoever", "rbac.role:read"));
        // ... and it is recorded in the decision log (the flush is a @Scheduled batcher)
        decisions.flush();
        assertEquals(2, db.queryForList("SELECT 1 FROM decision_log WHERE reason='bootstrap_clean_slate'").size());

        // the installer creates the first role and the first assignment through the APIs
        tree(); // syncOrgs so the tenants exist
        store.createRole("inst", "organization_root", "The root role: every permission", "PLATFORM", Map.of(), List.of());
        access.request(AccessService.SYSTEM,
                new AccessService.AssignRequest("root1", "organization_root", "tpts", "all", null, "install"), "backfill");
        assertFalse(decisions.check(new Decisions.Request("other", "tpts", "org.user:administer")).allowed(),
                "the first assignment closes the window");
        assertEquals("no_assignment", decisions.check(new Decisions.Request("other", "tpts", "org.user:administer")).reason());
        assertFalse(db.queryForList("SELECT 1 FROM decision_log WHERE reason='bootstrap_clean_slate'").size() > 2,
                "after closing, every decision is a normal grid decision");
    }

    private void tree() {
        Map<String, String[]> orgs = new LinkedHashMap<>();
        orgs.put("tpts", new String[]{"PLATFORM", null});
        List<Map<String, Object>> closure = new ArrayList<>();
        closure.add(Map.of("ancestor", "tpts", "descendant", "tpts", "depth", 0));
        List<RbacStore.OrgRow> rows = new ArrayList<>();
        rows.add(new RbacStore.OrgRow("tpts", "tpts", "PLATFORM", null, "ACTIVE", false, List.of()));
        store.syncOrgs(closure, rows);
    }
}
