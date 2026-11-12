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
 * Schema and seed of the MiPay RBAC grid (idp-rbac-cutover-plan.md P2, §4).
 *
 * <ol>
 *   <li>P2.0: if the old supplier-delegation demo schema is present, it is dropped once
 *       (tables {@code suppliers}, {@code supplier_groups}, {@code group_*}, {@code users},
 *       {@code permissions}, {@code role_permissions}, {@code org_tenant_mapping} and the old
 *       {@code roles}/{@code grants}/{@code assignments}/{@code tenants}). Nothing read it.</li>
 *   <li>The grid schema is created (idempotent).</li>
 *   <li>The seed (rows, roles, grids, hierarchy, SoD) is applied. A seeded role's grid is
 *       rewritten only when the seed for that role changed (content hash), so grid edits made
 *       through the API survive restarts until the next reviewed seed change.</li>
 *   <li>In dev/sandbox only, the e2e fixtures (demo orgs and assignments) are loaded.</li>
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
        tx.executeWithoutResult(s -> {
            try {
                seed(cfg.seedFile());
            } catch (Exception e) {
                throw new IllegalStateException("rbac seed: " + e.getMessage(), e);
            }
        });
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
    // Seed
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    void seed(String path) throws Exception {
        if (!Files.exists(Path.of(path))) {
            throw new IllegalStateException("seed file not found: " + path);
        }
        String text = Files.readString(Path.of(path), StandardCharsets.UTF_8);
        Map<String, Object> raw = new Yaml().load(text);
        int rows = 0;
        Map<String, Object> resources = map(raw.get("resources"));
        for (var e : resources.entrySet()) {
            Map<String, Object> r = map(e.getValue());
            String id = e.getKey();
            if (Verbs.parse(id + ":read") == null) throw new IllegalArgumentException("bad row id " + id);
            int declared = Verbs.mask(strings(r.get("used")));
            List<String> types = strings(r.get("tenant_types"));
            db.update("""
                    INSERT INTO rbac_resources (id, service, description, declared_verbs, used_verbs, tenant_types)
                    VALUES (?,?,?,?,?,?)
                    ON CONFLICT (id) DO UPDATE SET service=EXCLUDED.service, description=EXCLUDED.description,
                        declared_verbs=EXCLUDED.declared_verbs, tenant_types=EXCLUDED.tenant_types, updated_at=now(),
                        used_verbs = CASE WHEN EXISTS (SELECT 1 FROM rbac_cell_endpoints ce WHERE ce.resource=EXCLUDED.id)
                                          THEN rbac_resources.used_verbs ELSE EXCLUDED.declared_verbs END""",
                    id, str(r.get("service"), "mipay"), str(r.get("description"), ""), declared, declared,
                    types.isEmpty() ? null : String.join(",", types));
            rows++;
        }
        db.update("UPDATE rbac_resources SET grant_verbs=0, grant_attribute=NULL");
        for (Object o : list(raw.get("entitlements"))) {
            Map<String, Object> en = map(o);
            Verbs.Cell c = Verbs.parse(str(en.get("cell"), ""));
            if (c == null || !resources.containsKey(c.row())) throw new IllegalArgumentException("bad entitlement cell " + en.get("cell"));
            db.update("UPDATE rbac_resources SET grant_verbs = grant_verbs | ?, grant_attribute=? WHERE id=?",
                    c.bit(), str(en.get("attribute"), null), c.row());
        }

        Map<String, Object> roles = map(raw.get("roles"));
        int changed = 0;
        for (var e : roles.entrySet()) {
            String name = e.getKey();
            Map<String, Object> r = map(e.getValue());
            String hash = sha256(new Yaml().dump(r));
            List<String> prev = db.queryForList("SELECT seed_hash FROM roles WHERE name=?", String.class, name);
            String kind = str(r.get("kind"), "ORG");
            int allRows = Verbs.mask(strings(r.get("grid_all_rows")));
            Integer maxHours = r.get("max_hours") == null ? null : ((Number) r.get("max_hours")).intValue();
            db.update("""
                    INSERT INTO roles (name, description, kind, approval, max_hours, all_rows_verbs, seeded, seed_hash)
                    VALUES (?,?,?,?,?,?,TRUE,?)
                    ON CONFLICT (name) DO UPDATE SET description=EXCLUDED.description, kind=EXCLUDED.kind,
                        approval=EXCLUDED.approval, max_hours=EXCLUDED.max_hours, seeded=TRUE, updated_at=now()""",
                    name, str(r.get("description"), ""), kind, str(r.get("approval"), "platform"), maxHours, allRows, hash);
            if (!prev.isEmpty() && hash.equals(prev.get(0))) continue; // unchanged seed: keep API edits
            db.update("UPDATE roles SET all_rows_verbs=?, seed_hash=? WHERE name=?", allRows, hash, name);
            db.update("DELETE FROM rbac_role_grid WHERE role=?", name);
            for (var g : map(r.get("grid")).entrySet()) {
                if (!resources.containsKey(g.getKey())) throw new IllegalArgumentException(name + ": unknown row " + g.getKey());
                db.update("INSERT INTO rbac_role_grid (role, resource, verbs) VALUES (?,?,?)",
                        name, g.getKey(), Verbs.mask(strings(g.getValue())));
            }
            changed++;
        }
        // hierarchy (rewritten for seeded roles; cycles refused)
        Map<String, List<String>> inherits = new LinkedHashMap<>();
        for (var e : roles.entrySet()) inherits.put(e.getKey(), strings(map(e.getValue()).get("inherits")));
        Policy.assertAcyclic(inherits);
        for (var e : inherits.entrySet()) {
            db.update("DELETE FROM role_inheritance WHERE senior=?", e.getKey());
            for (String j : e.getValue()) {
                if (!roles.containsKey(j)) throw new IllegalArgumentException(e.getKey() + " inherits unknown role " + j);
                db.update("INSERT INTO role_inheritance (senior, junior) VALUES (?,?)", e.getKey(), j);
            }
        }
        // separation of duty and exemptions: the seed is authoritative
        db.update("DELETE FROM sod_rules");
        for (Object o : list(raw.get("sod"))) {
            Map<String, Object> s = map(o);
            String kind = str(s.get("kind"), "");
            List<String> a = strings(s.get("a")), b = strings(s.get("b"));
            for (String rn : concat(a, b)) {
                if (!roles.containsKey(rn)) throw new IllegalArgumentException("sod " + s.get("id") + ": unknown role " + rn);
            }
            db.update("INSERT INTO sod_rules (id, kind, roles_a, roles_b, cell_a, cell_b, reason) VALUES (?,?,?::text[],?::text[],?,?,?)",
                    s.get("id"), kind, pgArray(a), pgArray(b), s.get("cell_a"), s.get("cell_b"), str(s.get("reason"), ""));
        }
        db.update("DELETE FROM same_row_exemptions");
        for (var e : map(raw.get("same_row_exemptions")).entrySet()) {
            db.update("INSERT INTO same_row_exemptions (resource, reason) VALUES (?,?)", e.getKey(), String.valueOf(e.getValue()));
        }
        // roles inside every org's ceiling (extended-rbac X3): the seed is authoritative
        db.update("DELETE FROM ceiling_baseline");
        for (String rn : strings(raw.get("ceiling_baseline"))) {
            Map<String, Object> r = map(roles.get(rn));
            if (!roles.containsKey(rn) || !"ORG".equals(str(r.get("kind"), "ORG"))) {
                throw new IllegalArgumentException("ceiling_baseline: " + rn + " is not a seeded ORG role");
            }
            db.update("INSERT INTO ceiling_baseline (role) VALUES (?)", rn);
        }
        log.info("[rbac] seed applied: {} rows, {} roles ({} rewritten from a changed seed)", rows, roles.size(), changed);
        db.update("INSERT INTO audit_log (actor, action, details) VALUES ('system','seed_applied',?::jsonb)",
                "{\"rows\":" + rows + ",\"roles\":" + roles.size() + ",\"rewritten\":" + changed + "}");
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
