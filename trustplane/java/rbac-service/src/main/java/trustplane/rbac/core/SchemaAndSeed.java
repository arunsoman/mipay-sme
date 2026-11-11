package trustplane.rbac.core;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import trustplane.rbac.config.RbacConfig;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Applies the idempotent DDL, then the declarative seed (tenants, suppliers,
 * users, permission catalog extras, platform roles, direct platform
 * assignments). Seed assignments carry root authority directly — no grant.
 */
@Component
@Order(1)
@lombok.extern.slf4j.Slf4j
@lombok.RequiredArgsConstructor
public class SchemaAndSeed implements ApplicationRunner {

    public static final List<String> DDL = List.of("""
            CREATE TABLE IF NOT EXISTS tenants(
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                version BIGINT NOT NULL DEFAULT 1)""", """
            CREATE TABLE IF NOT EXISTS suppliers(
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'active',
                created_at TIMESTAMPTZ NOT NULL DEFAULT now())""", """
            CREATE TABLE IF NOT EXISTS users(
                id TEXT PRIMARY KEY,
                supplier_id TEXT REFERENCES suppliers(id),
                display_name TEXT NOT NULL DEFAULT '',
                kind TEXT NOT NULL DEFAULT 'platform',
                status TEXT NOT NULL DEFAULT 'active',
                created_at TIMESTAMPTZ NOT NULL DEFAULT now())""", """
            CREATE TABLE IF NOT EXISTS permissions(
                id TEXT PRIMARY KEY,
                description TEXT NOT NULL DEFAULT '')""", """
            CREATE TABLE IF NOT EXISTS roles(
                name TEXT PRIMARY KEY,
                description TEXT NOT NULL DEFAULT '',
                created_at TIMESTAMPTZ NOT NULL DEFAULT now())""", """
            CREATE TABLE IF NOT EXISTS role_permissions(
                role_name TEXT NOT NULL,
                permission_id TEXT NOT NULL,
                PRIMARY KEY(role_name, permission_id))""", """
            CREATE TABLE IF NOT EXISTS grants(
                id TEXT PRIMARY KEY,
                supplier_id TEXT NOT NULL REFERENCES suppliers(id),
                role_name TEXT NOT NULL,
                tenant_id TEXT,
                scope TEXT NOT NULL CHECK (scope IN ('own','all')),
                can_delegate BOOLEAN NOT NULL DEFAULT FALSE,
                expires_at TIMESTAMPTZ,
                status TEXT NOT NULL DEFAULT 'active',
                granted_by TEXT NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now())""", """
            CREATE TABLE IF NOT EXISTS assignments(
                id TEXT PRIMARY KEY,
                subject TEXT NOT NULL,
                tenant_id TEXT,
                role_name TEXT NOT NULL,
                scope TEXT NOT NULL DEFAULT 'own' CHECK (scope IN ('own','all')),
                grant_id TEXT REFERENCES grants(id),
                supplier_id TEXT,
                group_id TEXT,
                status TEXT NOT NULL DEFAULT 'active',
                created_by TEXT NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now())""", """
            CREATE TABLE IF NOT EXISTS supplier_groups(
                id TEXT PRIMARY KEY,
                supplier_id TEXT NOT NULL REFERENCES suppliers(id),
                name TEXT NOT NULL,
                created_by TEXT NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now())""", """
            CREATE TABLE IF NOT EXISTS group_roles(
                id TEXT PRIMARY KEY,
                group_id TEXT NOT NULL REFERENCES supplier_groups(id),
                role_name TEXT NOT NULL,
                scope TEXT NOT NULL DEFAULT 'own')""", """
            CREATE TABLE IF NOT EXISTS group_members(
                group_id TEXT NOT NULL REFERENCES supplier_groups(id),
                user_id TEXT NOT NULL,
                PRIMARY KEY(group_id, user_id))""", """
            CREATE TABLE IF NOT EXISTS audit_log(
                id BIGSERIAL PRIMARY KEY,
                ts TIMESTAMPTZ NOT NULL DEFAULT now(),
                actor TEXT NOT NULL,
                tenant_id TEXT,
                action TEXT NOT NULL,
                details JSONB NOT NULL DEFAULT '{}'::jsonb)""", """
            CREATE TABLE IF NOT EXISTS org_closure(
                ancestor_id TEXT NOT NULL,
                descendant_id TEXT NOT NULL,
                depth INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(ancestor_id, descendant_id))""", """
            CREATE INDEX IF NOT EXISTS org_closure_descendant_idx ON org_closure(descendant_id)""", """
            CREATE INDEX IF NOT EXISTS org_closure_ancestor_idx ON org_closure(ancestor_id)""", """
            CREATE TABLE IF NOT EXISTS org_tenant_mapping(
                org_id TEXT PRIMARY KEY,
                tenant_id TEXT NOT NULL REFERENCES tenants(id))""");

    @SuppressWarnings("unchecked")
    public static class Seed {
        public List<Map<String, Object>> tenants;
        public List<Map<String, Object>> suppliers;
        public List<Map<String, Object>> users;
        public List<Map<String, Object>> permissions;
        public List<Map<String, Object>> roles;
        public List<Map<String, Object>> orgClosure;
        public List<Map<String, Object>> assignments;
    }

    private final JdbcTemplate db;
    private final RbacConfig cfg;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        for (String ddl : DDL) db.execute(ddl);
        int n = seed(cfg.seedFile());
        log.info("[rbac] schema applied, seed rows inserted: {}", n);
    }

    @SuppressWarnings("unchecked")
    int seed(String path) throws Exception {
        if (!Files.exists(Path.of(path))) {
            log.warn("[rbac] no seed file at {}", path);
            return 0;
        }
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            Map<String, Object> raw = new Yaml().load(in);
            int n = 0;
            for (Map<String, Object> t : mapList(raw.get("tenants"))) {
                n += db.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                        t.get("id"), t.get("name"));
            }
            for (Map<String, Object> s : mapList(raw.get("suppliers"))) {
                n += db.update("INSERT INTO suppliers (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                        s.get("id"), s.get("name"));
            }
            for (Map<String, Object> u : mapList(raw.get("users"))) {
                n += db.update("""
                        INSERT INTO users (id, supplier_id, display_name, kind)
                        VALUES (?, COALESCE(?::text, NULL), ?, ?) ON CONFLICT (id) DO NOTHING""",
                        u.get("id"), nul(u.get("supplier")), u.getOrDefault("display_name", ""),
                        u.getOrDefault("kind", "platform"));
            }
            for (Map<String, Object> p : mapList(raw.get("permissions"))) {
                n += db.update("INSERT INTO permissions (id, description) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                        p.get("id"), p.getOrDefault("description", ""));
            }
            for (Map<String, Object> r : mapList(raw.get("roles"))) {
                n += db.update("INSERT INTO roles (name, description) VALUES (?, ?) ON CONFLICT (name) DO NOTHING",
                        r.get("name"), r.getOrDefault("description", ""));
                for (Object perm : (List<Object>) r.getOrDefault("permissions", List.of())) {
                    n += db.update("INSERT INTO role_permissions (role_name, permission_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                            r.get("name"), perm);
                }
            }
            for (Map<String, Object> a : mapList(raw.get("assignments"))) {
                String id = RbacStore.newId("asgmt");
                n += db.update("""
                        INSERT INTO assignments (id, subject, tenant_id, role_name, scope, created_by)
                        VALUES (?, ?, COALESCE(?::text, NULL), ?, ?, 'system') ON CONFLICT DO NOTHING""",
                        id, a.get("subject"), nul(a.get("tenant")), a.get("role"),
                        a.get("scope") == null ? "all" : a.get("scope"));
            }
            for (Map<String, Object> oc : mapList(raw.get("orgClosure"))) {
                n += db.update("INSERT INTO org_closure (ancestor_id, descendant_id, depth) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                        oc.get("ancestor"), oc.get("descendant"), oc.get("depth"));
            }
            for (Map<String, Object> otm : mapList(raw.get("orgTenantMapping"))) {
                n += db.update("INSERT INTO org_tenant_mapping (org_id, tenant_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                        otm.get("org"), otm.get("tenant"));
            }
            return n;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object o) {
        return o == null ? List.of() : (List<Map<String, Object>>) o;
    }

    private static Object nul(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
