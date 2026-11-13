package trustplane.rbac.core;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.yaml.snakeyaml.Yaml;
import trustplane.rbac.config.RbacConfig;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Schema and bootstrap of the MiPay RBAC grid (idp-rbac-cutover-plan.md P2, §4), retuned 2026-11-13:
 * there is NO seed file. The RBAC boots with an EMPTY grid - no roles, no assignments, no SoD
 * rules - and everything is created through the reviewed admin APIs: on a fresh install the IdP
 * mints the bootstrap installer (installer/installer123), who creates the first org and the first
 * role (every permission) with its assignment, and from then on the live database is the only
 * source of truth. Roles made through the APIs are never overwritten at boot.
 *
 * <ol>
 *   <li>P2.0: if the old supplier-delegation demo schema is present, it is dropped once
 *       (tables {@code suppliers}, {@code supplier_groups}, {@code group_*}, {@code users},
 *       {@code permissions}, {@code role_permissions}, {@code org_tenant_mapping} and the old
 *       {@code roles}/{@code grants}/{@code assignments}/{@code tenants}). Nothing read it.</li>
 *   <li>The grid schema is created (idempotent).</li>
 *   <li>Dev/sandbox only: the e2e fixtures (demo orgs and assignments) may be loaded for tests;
 *       production never sees them.</li>
 * </ol>
 */
@Component
@Order(1)
@lombok.extern.slf4j.Slf4j
@lombok.RequiredArgsConstructor
public class SchemaAndSeed implements ApplicationRunner {

    public static final List<String> DDL = List.of("""
            CREATE TABLE IF NOT EXISTS tenants(
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL DEFAULT '',
                org_type TEXT,
                parent_id TEXT,
                lifecycle TEXT NOT NULL DEFAULT 'ACTIVE',
                held BOOLEAN NOT NULL DEFAULT FALSE,
                hold_reasons TEXT NOT NULL DEFAULT '',
                source TEXT NOT NULL DEFAULT 'mipay',
                version BIGINT NOT NULL DEFAULT 1,
                updated_at TIMESTAMPTZ NOT NULL DEFAULT now())""", """
            CREATE TABLE IF NOT EXISTS org_closure(
                ancestor_id TEXT NOT NULL,
                descendant_id TEXT NOT NULL,
                depth INTEGER NOT NULL DEFAULT 0,
                source TEXT NOT NULL DEFAULT 'mipay',
                PRIMARY KEY(ancestor_id, descendant_id))""",
            "CREATE INDEX IF NOT EXISTS org_closure_descendant_idx ON org_closure(descendant_id)",
            "CREATE INDEX IF NOT EXISTS org_closure_ancestor_idx ON org_closure(ancestor_id)", """
            CREATE TABLE IF NOT EXISTS rbac_resources(
                id TEXT PRIMARY KEY,
                service TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                declared_verbs INTEGER NOT NULL DEFAULT 0,
                used_verbs INTEGER NOT NULL DEFAULT 0,
                tenant_types TEXT,
                grant_verbs INTEGER NOT NULL DEFAULT 0,
                grant_attribute TEXT,
                retired BOOLEAN NOT NULL DEFAULT FALSE,
                updated_at TIMESTAMPTZ NOT NULL DEFAULT now())""", """
            CREATE TABLE IF NOT EXISTS rbac_cell_endpoints(
                service TEXT NOT NULL,
                method TEXT NOT NULL,
                path TEXT NOT NULL,
                resource TEXT NOT NULL,
                verb INTEGER NOT NULL,
                tenant_source TEXT,
                handler TEXT,
                PRIMARY KEY(service, method, path))""", """
            CREATE TABLE IF NOT EXISTS roles(
                name TEXT PRIMARY KEY,
                description TEXT NOT NULL DEFAULT '',
                kind TEXT NOT NULL CHECK (kind IN ('ORG','PLATFORM','SANDBOX')),
                approval TEXT NOT NULL DEFAULT 'platform',
                max_hours INTEGER,
                all_rows_verbs INTEGER NOT NULL DEFAULT 0,
                seeded BOOLEAN NOT NULL DEFAULT FALSE,
                seed_hash TEXT,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                updated_at TIMESTAMPTZ NOT NULL DEFAULT now())""", """
            CREATE TABLE IF NOT EXISTS rbac_role_grid(
                role TEXT NOT NULL REFERENCES roles(name) ON DELETE CASCADE,
                resource TEXT NOT NULL REFERENCES rbac_resources(id),
                verbs INTEGER NOT NULL CHECK (verbs >= 0 AND verbs < 1024),
                PRIMARY KEY(role, resource))""", """
            CREATE TABLE IF NOT EXISTS role_inheritance(
                senior TEXT NOT NULL REFERENCES roles(name) ON DELETE CASCADE,
                junior TEXT NOT NULL REFERENCES roles(name) ON DELETE CASCADE,
                PRIMARY KEY(senior, junior))""", """
            CREATE TABLE IF NOT EXISTS sod_rules(
                id TEXT PRIMARY KEY,
                kind TEXT NOT NULL CHECK (kind IN ('STATIC','DYNAMIC','OBJECT')),
                roles_a TEXT[] NOT NULL DEFAULT '{}',
                roles_b TEXT[] NOT NULL DEFAULT '{}',
                cell_a TEXT,
                cell_b TEXT,
                reason TEXT NOT NULL)""", """
            CREATE TABLE IF NOT EXISTS same_row_exemptions(
                resource TEXT PRIMARY KEY,
                reason TEXT NOT NULL)""", """
            CREATE TABLE IF NOT EXISTS assignments(
                id TEXT PRIMARY KEY,
                subject TEXT NOT NULL,
                role TEXT NOT NULL REFERENCES roles(name),
                tenant_id TEXT,
                scope TEXT NOT NULL CHECK (scope IN ('own','subtree','all')),
                status TEXT NOT NULL CHECK (status IN ('PENDING_APPROVAL','ACTIVE','REJECTED','EXPIRED','REVOKED')),
                expires_at TIMESTAMPTZ,
                reason TEXT NOT NULL DEFAULT '',
                source TEXT NOT NULL DEFAULT 'api',
                requested_by TEXT NOT NULL,
                requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                request_expires_at TIMESTAMPTZ,
                approved_by TEXT,
                approved_at TIMESTAMPTZ,
                decided_reason TEXT,
                decision_id TEXT,
                revoked_by TEXT,
                revoked_at TIMESTAMPTZ,
                revoke_reason TEXT)""",
            "CREATE INDEX IF NOT EXISTS assignments_subject_idx ON assignments(subject, status)",
            "CREATE INDEX IF NOT EXISTS assignments_role_idx ON assignments(role, status)",
            "CREATE INDEX IF NOT EXISTS assignments_tenant_idx ON assignments(tenant_id, status)", """
            CREATE TABLE IF NOT EXISTS grants(
                id TEXT PRIMARY KEY,
                tenant_id TEXT NOT NULL,
                permission TEXT NOT NULL,
                allowed_values TEXT[] NOT NULL DEFAULT '{}',
                scope TEXT NOT NULL CHECK (scope IN ('own','subtree')),
                can_delegate BOOLEAN NOT NULL DEFAULT FALSE,
                parent_grant_id TEXT REFERENCES grants(id),
                status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','revoked')),
                expires_at TIMESTAMPTZ,
                granted_by TEXT NOT NULL,
                source TEXT NOT NULL DEFAULT 'api',
                created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                revoked_by TEXT,
                revoked_at TIMESTAMPTZ)""",
            "CREATE INDEX IF NOT EXISTS grants_tenant_idx ON grants(tenant_id, permission, status)", """
            CREATE TABLE IF NOT EXISTS reviews(
                id TEXT PRIMARY KEY,
                tenant_id TEXT,
                label TEXT NOT NULL DEFAULT '',
                created_by TEXT NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                due_at TIMESTAMPTZ NOT NULL,
                status TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','CLOSED')))""", """
            CREATE TABLE IF NOT EXISTS review_items(
                review_id TEXT NOT NULL REFERENCES reviews(id),
                assignment_id TEXT NOT NULL REFERENCES assignments(id),
                decision TEXT NOT NULL DEFAULT 'PENDING' CHECK (decision IN ('PENDING','CONFIRMED','REMOVED','EXPIRED')),
                decided_by TEXT,
                decided_at TIMESTAMPTZ,
                PRIMARY KEY(review_id, assignment_id))""", """
            CREATE TABLE IF NOT EXISTS decision_log(
                decision_id TEXT PRIMARY KEY,
                at TIMESTAMPTZ NOT NULL DEFAULT now(),
                subject TEXT NOT NULL,
                tenant TEXT NOT NULL,
                operation TEXT NOT NULL,
                allowed BOOLEAN NOT NULL,
                reason TEXT NOT NULL,
                matched TEXT)""",
            "CREATE INDEX IF NOT EXISTS decision_log_at_idx ON decision_log(at)", """
            CREATE TABLE IF NOT EXISTS audit_log(
                id BIGSERIAL PRIMARY KEY,
                ts TIMESTAMPTZ NOT NULL DEFAULT now(),
                actor TEXT NOT NULL,
                tenant_id TEXT,
                action TEXT NOT NULL,
                details JSONB NOT NULL DEFAULT '{}'::jsonb)""",
            // extended RBAC (docs/plans/extended-rbac.md): role ceilings per org (NULL = no explicit
            // ceiling: inherit), org-defined roles (owner_tenant) bounded by their base roles
            "ALTER TABLE tenants ADD COLUMN IF NOT EXISTS role_ceiling TEXT[]",
            "ALTER TABLE tenants ADD COLUMN IF NOT EXISTS role_delegable TEXT[]",
            "ALTER TABLE roles ADD COLUMN IF NOT EXISTS owner_tenant TEXT",
            "ALTER TABLE roles ADD COLUMN IF NOT EXISTS base_roles TEXT[] NOT NULL DEFAULT '{}'",
            // an org-defined role of an archived / rejected / closed org is retired, never deleted:
            // its assignment history stays for audit, but it can't be assigned or used again
            "ALTER TABLE roles ADD COLUMN IF NOT EXISTS retired_at TIMESTAMPTZ",
            "CREATE INDEX IF NOT EXISTS roles_owner_idx ON roles(owner_tenant)", """
            CREATE TABLE IF NOT EXISTS ceiling_baseline(
                role TEXT PRIMARY KEY REFERENCES roles(name) ON DELETE CASCADE)""");

    /** Tables of the supplier-delegation demo (cut-over plan §4.1). */
    static final List<String> LEGACY_TABLES = List.of("group_members", "group_roles", "supplier_groups",
            "role_permissions", "permissions", "org_tenant_mapping", "assignments", "grants", "users", "suppliers",
            "roles", "tenants", "org_closure");

    private final JdbcTemplate db;
    private final RbacConfig cfg;
    private final TransactionTemplate tx;
    private final Policy policy;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        apply();
    }

    public void apply() throws Exception {
        dropLegacyDemo();
        for (String ddl : DDL) db.execute(ddl);
        log.warn("[rbac] the grid is NOT seeded: no roles, no assignments, no SoD rules. The installer "
                + "creates the first org and the first role through the APIs while the clean-slate "
                + "bootstrap window is open (see Decisions.bootstrapWindow).");
        if (cfg.isDev() && !cfg.fixturesFile().isBlank()) {
            tx.executeWithoutResult(s -> {
                try {
                    fixtures(cfg.fixturesFile());
                } catch (Exception e) {
                    throw new IllegalStateException("rbac fixtures: " + e.getMessage(), e);
                }
            });
        } else if (!cfg.fixturesFile().isBlank()) {
            log.warn("[rbac] RBAC_FIXTURES_FILE ignored: fixtures load only in dev/sandbox (environment={})", cfg.environment());
        }
        policy.reload();
    }

    /** P2.0: the demo model is removed in one step, never half. */
    void dropLegacyDemo() {
        Boolean legacy = db.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name='suppliers')
                    OR EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='assignments' AND column_name='supplier_id')
                    OR EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name='role_permissions')""", Boolean.class);
        if (!Boolean.TRUE.equals(legacy)) return;
        log.warn("[rbac] P2.0: dropping the supplier-delegation demo schema ({})", LEGACY_TABLES);
        for (String t : LEGACY_TABLES) db.execute("DROP TABLE IF EXISTS " + t + " CASCADE");
        db.execute("DROP TABLE IF EXISTS audit_log CASCADE");
    }

    // ------------------------------------------------------------------
    // E2E fixtures (dev/sandbox only): demo orgs + assignments, never touched by the org sync
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    void fixtures(String path) throws Exception {
        if (!Files.exists(Path.of(path))) {
            log.warn("[rbac] fixtures file {} not found", path);
            return;
        }
        Map<String, Object> raw;
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            raw = new Yaml().load(in);
        }
        Map<String, String> parent = new LinkedHashMap<>();
        for (Object o : list(raw.get("orgs"))) {
            Map<String, Object> t = map(o);
            String id = str(t.get("id"), "");
            parent.put(id, str(t.get("parent"), null));
            db.update("""
                    INSERT INTO tenants (id, name, org_type, parent_id, lifecycle, held, source) VALUES (?,?,?,?,'ACTIVE',FALSE,'fixture')
                    ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, org_type=EXCLUDED.org_type, parent_id=EXCLUDED.parent_id,
                        lifecycle='ACTIVE', held=FALSE, hold_reasons='', source='fixture'""",
                    id, str(t.get("name"), id), str(t.get("type"), null), parent.get(id));
        }
        db.update("DELETE FROM org_closure WHERE source='fixture'");
        for (String id : parent.keySet()) {
            int depth = 0;
            for (String cur = id; cur != null && depth < 32; cur = parent.get(cur), depth++) {
                db.update("INSERT INTO org_closure (ancestor_id, descendant_id, depth, source) VALUES (?,?,?,'fixture') ON CONFLICT DO NOTHING",
                        cur, id, depth);
            }
        }
        int n = 0;
        for (Object o : list(raw.get("assignments"))) {
            Map<String, Object> a = map(o);
            String subject = str(a.get("subject"), ""), role = str(a.get("role"), "");
            String tenant = str(a.get("tenant"), null), scope = str(a.get("scope"), "own");
            String id = "fx-" + sha256(subject + "|" + role + "|" + tenant + "|" + scope).substring(0, 16);
            n += db.update("""
                    INSERT INTO assignments (id, subject, role, tenant_id, scope, status, source, requested_by, approved_by, approved_at, reason)
                    VALUES (?,?,?,?,?,'ACTIVE','fixture','fixture','fixture',now(),'e2e fixture')
                    ON CONFLICT (id) DO UPDATE SET status='ACTIVE', revoked_at=NULL, revoked_by=NULL, expires_at=NULL""",
                    id, subject, role, tenant, scope);
        }
        log.info("[rbac] e2e fixtures loaded: {} orgs, {} assignments", parent.size(), n);
    }

    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return o == null ? new LinkedHashMap<>() : (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) {
        return o == null ? List.of() : (List<Object>) o;
    }

    static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        for (Object x : list(o)) out.add(String.valueOf(x));
        return out;
    }

    static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    public static String pgArray(List<String> xs) {
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) b.append(',');
            b.append('"').append(xs.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return b.append('}').toString();
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
