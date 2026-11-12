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
 * The product seed since 2026-11-12: one standard role (organization_root, every permission),
 * permission rows only from the code's @Perm registration, and org-defined roles built from
 * picked permissions with no base roles.
 */
class RootRoleSeedTest {

    JdbcTemplate db;
    RbacStore store;
    Decisions decisions;
    AccessService access;
    SchemaAndSeed seed;
    String engineSeed;

    @BeforeEach
    void setUp() throws Exception {
        String raw = System.getenv("TEST_RBAC_DSN");
        Assumptions.assumeTrue(raw != null && !raw.isBlank(), "TEST_RBAC_DSN not set (needs a scratch Postgres)");
        PostgresDsn dsn = PostgresDsn.parse(raw);
        DriverManagerDataSource ds = new DriverManagerDataSource(dsn.jdbcUrl(), dsn.user(), dsn.password());
        db = new JdbcTemplate(ds);
        db.execute("DROP SCHEMA public CASCADE");
        db.execute("CREATE SCHEMA public");
        engineSeed = System.getenv("TEST_RBAC_SEED");
        wire(System.getenv("TEST_RBAC_PRODUCT_SEED"), ds);
    }

    void wire(String seedFile, DriverManagerDataSource ds) {
        RbacConfig cfg = new RbacConfig("", ":0", ":0", "", "", "", "", seedFile, 120, "dev", "", "sme-mipay-mesh", null);
        Policy policy = new Policy(db);
        Ceilings ceilings = new Ceilings(db, policy);
        store = new RbacStore(db, new WatchHub(), policy, cfg, ceilings);
        decisions = new Decisions(db, policy, ceilings);
        access = new AccessService(db, policy, decisions, store, cfg, ceilings);
        seed = new SchemaAndSeed(db, cfg, new TransactionTemplate(new DataSourceTransactionManager(ds)), policy);
    }

    void tree() {
        Map<String, String[]> orgs = new LinkedHashMap<>();
        orgs.put("tpts", new String[]{"PLATFORM", null});
        orgs.put("namib", new String[]{"DISTRIBUTOR", "tpts"});
        orgs.put("oka", new String[]{"MERCHANT", "namib"});
        List<Map<String, Object>> closure = new ArrayList<>();
        List<RbacStore.OrgRow> rows = new ArrayList<>();
        for (var e : orgs.entrySet()) {
            int depth = 0;
            for (String cur = e.getKey(); cur != null; cur = orgs.get(cur)[1], depth++) {
                closure.add(Map.of("ancestor", cur, "descendant", e.getKey(), "depth", depth));
            }
            rows.add(new RbacStore.OrgRow(e.getKey(), e.getKey(), e.getValue()[0], e.getValue()[1], "ACTIVE", false, List.of()));
        }
        store.syncOrgs(closure, rows);
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

    boolean allowed(String subject, String tenant, String cell) {
        return decisions.check(new Decisions.Request(subject, tenant, cell)).allowed();
    }

    @Test
    void oneStandardRoleAndRowsFromTheCode() throws Exception {
        seed.apply();
        assertEquals(List.of("organization_root"), db.queryForList("SELECT name FROM roles WHERE seeded ORDER BY name", String.class));
        // the yml declares no rows: only the entitlement's placeholder exists before the code registers
        assertEquals(List.of("org.onboarding"), db.queryForList("SELECT id FROM rbac_resources ORDER BY id", String.class));
        registerCode();
        assertEquals("MERCHANT", db.queryForObject("SELECT tenant_types FROM rbac_resources WHERE id='merchant.withdrawal'", String.class));
        assertNull(db.queryForObject("SELECT tenant_types FROM rbac_resources WHERE id='org.user'", String.class));
        assertEquals("Merchant withdrawal", db.queryForObject("SELECT description FROM rbac_resources WHERE id='merchant.withdrawal'", String.class));

        // a row that leaves the code leaves the grid
        store.registerCatalog("mipay", List.of(Map.of("id", "org.user:read", "description", "See users")),
                List.of(new RbacStore.EndpointReg("GET", "/x/u", "org.user:read", "SELF", "h")), List.of());
        assertTrue(db.queryForList("SELECT id FROM rbac_resources WHERE id='merchant.withdrawal'").isEmpty());
    }

    @Test
    void rootAdminsHoldEverythingAndBuildRolesFromPickedPermissions() throws Exception {
        seed.apply();
        registerCode();
        tree();
        access.request(AccessService.SYSTEM, new AccessService.AssignRequest("root1", "organization_root", "tpts", "all", null, "t"), "backfill");
        assertTrue(allowed("root1", "oka", "merchant.withdrawal:execute"), "every permission, in every org");
        assertTrue(allowed("root1", "tpts", "org.user:administer"));
        assertFalse(allowed("selma", "oka", "merchant.withdrawal:read"), "everyone else holds nothing until given a role");

        // extended RBAC: a role from picked permissions, no base roles, then assigned
        store.createOrgRole("root1", "oka", "cashier", "Takes payments", List.of(),
                Map.of("merchant.withdrawal", 1 | 64));
        access.request(AccessService.SYSTEM, new AccessService.AssignRequest("selma", "cashier@oka", "oka", "own", null, "t"), "backfill");
        assertTrue(allowed("selma", "oka", "merchant.withdrawal:execute"));
        assertFalse(allowed("selma", "oka", "org.user:administer"), "only what was picked");
        assertTrue(decisions.listPermissions("selma", "oka", List.of()).operations().contains("merchant.withdrawal:read"));
    }

    @Test
    void reseedingRemovesTheOldRolesAndTheirAssignments() throws Exception {
        Assumptions.assumeTrue(engineSeed != null && !engineSeed.isBlank());
        DriverManagerDataSource ds = new DriverManagerDataSource(PostgresDsn.parse(System.getenv("TEST_RBAC_DSN")).jdbcUrl(),
                PostgresDsn.parse(System.getenv("TEST_RBAC_DSN")).user(), PostgresDsn.parse(System.getenv("TEST_RBAC_DSN")).password());
        wire(engineSeed, ds);
        seed.apply();
        tree();
        access.request(AccessService.SYSTEM, new AccessService.AssignRequest("ua1", "platform_user_admin", "tpts", "all", null, "t"), "backfill");
        assertTrue(db.queryForObject("SELECT count(*) FROM roles WHERE seeded", Integer.class) > 10);

        wire(System.getenv("TEST_RBAC_PRODUCT_SEED"), ds);
        seed.apply();
        assertEquals(List.of("organization_root"), db.queryForList("SELECT name FROM roles WHERE seeded", String.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM assignments WHERE role='platform_user_admin'", Integer.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM ceiling_baseline", Integer.class));
    }
}
