package trustplane.rbac.core;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import trustplane.shared.ApiException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Port of the Go RBAC store: the delegated administration model.
 *
 *   - Root RBAC owns role definitions + delegation Grants.
 *   - Supplier admins may only ASSIGN roles covered by an active grant, to
 *     their own members, within the grant's scope (own|all). Assignments
 *     reference the covering grant => full traceability.
 *   - Check-time enforcement joins assignment->user->supplier->grant so a
 *     revoked grant (or suspended user/supplier) ends authority immediately,
 *     even while OAuth tokens are still valid.
 */
@Service
@lombok.RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class RbacStore {

    private static final DateTimeFormatter RFC3339 = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final SecureRandom RNG = new SecureRandom();

    private static final ObjectMapper AUDIT_MAPPER = new ObjectMapper();

    private final JdbcTemplate db;
    private final WatchHub watch;


    // ------------------------------------------------------------------
    // IDs / audit / touch
    // ------------------------------------------------------------------

    public static String newId(String prefix) {
        byte[] r = new byte[9];
        RNG.nextBytes(r);
        return prefix + "-" + Long.toHexString(System.currentTimeMillis()) + "-"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(r);
    }

    /** Bumps the tenant version and fans an invalidation event to watchers. */
    public void touch(String tenant, List<String> subjects) {
        long version = 0;
        if (tenant != null && !tenant.isBlank()) {
            try {
                Map<String, Object> r = db.queryForMap(
                        "UPDATE tenants SET version = version + 1 WHERE id = ? RETURNING version", tenant);
                version = ((Number) r.get("version")).longValue();
            } catch (Exception ignore) { /* unknown tenant: version 0 */ }
        }
        if (subjects == null) subjects = List.of();
        watch.publish(tenant == null ? "" : tenant, version, subjects);
    }

    public void audit(String actor, String tenant, String action, Map<String, Object> details) {
        try {
            String json = AUDIT_MAPPER.writeValueAsString(details);
            db.update("INSERT INTO audit_log (actor, tenant_id, action, details) VALUES (?, ?, ?, ?::jsonb)",
                    actor, tenant, action, json);
        } catch (Exception ignore) { /* audit best-effort, as in Go */ }
    }

    // ------------------------------------------------------------------
    // Decision API (gateway PDP)
    // ------------------------------------------------------------------

    public record Decision(boolean allowed, long tenantVersion, String supplierId, String reason) {}

    public Decision checkDecision(String subject, String tenant, String op) {
        final String q = """
                SELECT COALESCE((
                    SELECT TRUE FROM assignments a
                    JOIN role_permissions rp ON rp.role_name=a.role_name AND rp.permission_id=?
                    JOIN users u ON u.id=a.subject
                    LEFT JOIN suppliers sp ON sp.id=u.supplier_id
                    LEFT JOIN grants g ON g.id=a.grant_id
                    WHERE a.subject=? AND a.status='active'
                      AND (?='' OR a.tenant_id IS NULL OR a.tenant_id=?)
                      AND u.status='active'
                      AND (u.supplier_id IS NULL OR sp.status='active')
                      AND (a.grant_id IS NULL OR (g.status='active' AND (g.expires_at IS NULL OR g.expires_at > now())))
                    LIMIT 1), FALSE) AS allowed,
                COALESCE((SELECT version FROM tenants WHERE id=? AND ?<>''), 0) AS version,
                COALESCE((SELECT supplier_id FROM users WHERE id=?), '') AS supplier""";
        List<Map<String, Object>> rows = db.queryForList(q, op, subject, tenant, tenant, tenant, tenant, subject);
        Map<String, Object> r = rows.get(0);
        boolean allowed = Boolean.TRUE.equals(r.get("allowed"));
        long version = ((Number) r.getOrDefault("version", 0)).longValue();
        String supplier = String.valueOf(r.getOrDefault("supplier", ""));
        return new Decision(allowed, version, supplier == null ? "" : supplier,
                allowed ? "assignment_active" : "no_active_assignment_or_grant");
    }

    public record PermissionSet(List<String> operations, long tenantVersion, String supplierId) {}

    public PermissionSet listPermissions(String subject, String tenant) {
        final String q = """
                SELECT DISTINCT rp.permission_id
                FROM assignments a
                JOIN role_permissions rp ON rp.role_name=a.role_name
                JOIN users u ON u.id=a.subject
                LEFT JOIN suppliers sp ON sp.id=u.supplier_id
                LEFT JOIN grants g ON g.id=a.grant_id
                WHERE a.subject=? AND a.status='active'
                  AND (?='' OR a.tenant_id IS NULL OR a.tenant_id=?)
                  AND u.status='active'
                  AND (u.supplier_id IS NULL OR sp.status='active')
                  AND (a.grant_id IS NULL OR (g.status='active' AND (g.expires_at IS NULL OR g.expires_at > now())))""";
        List<String> ops = new ArrayList<>();
        for (Map<String, Object> r : db.queryForList(q, subject, tenant, tenant)) {
            ops.add(String.valueOf(r.get("permission_id")));
        }
        final String vq = """
                SELECT COALESCE((SELECT version FROM tenants WHERE id=? AND ?<>''), 0) AS version,
                       COALESCE((SELECT supplier_id FROM users WHERE id=?), '') AS supplier""";
        Map<String, Object> meta = db.queryForMap(vq, tenant, tenant, subject);
        long version = ((Number) meta.getOrDefault("version", 0)).longValue();
        String supplier = String.valueOf(meta.getOrDefault("supplier", ""));
        return new PermissionSet(ops, version, supplier == null ? "" : supplier);
    }

    // ------------------------------------------------------------------
    // Grant boundary helpers
    // ------------------------------------------------------------------

    /** Active root grant covering (supplier, role, scope, tenant); "no grant, no assignment". */
    public String coveringGrant(String supplier, String role, String scope, String tenant) {
        final String q = """
                SELECT id FROM grants
                WHERE supplier_id=? AND role_name=? AND status='active'
                  AND (expires_at IS NULL OR expires_at > now())
                  AND (scope='all' OR ?='own')
                  AND (?='' OR tenant_id IS NULL OR tenant_id=?)
                ORDER BY created_at DESC LIMIT 1""";
        List<String> ids = db.queryForList(q, String.class, supplier, role, scope, tenant, tenant);
        if (ids.isEmpty()) {
            throw ApiExceptionCodes.noMatchingGrant();
        }
        return ids.get(0);
    }

    public record DelegatedAssignment(String grantId, String scope, String tenant, String supplierId) {}

    /**
     * Resolves + validates the covering root grant for a supplier-side
     * assignment. Distinguishes "not delegated" (no_matching_grant) from
     * "delegated but out of scope" (scope_exceeds_grant).
     */
    public DelegatedAssignment delegatedAssignment(String actorSup, String subject, String role,
                                                   String tenant, String scope) {
        List<String> sups = db.queryForList(
                "SELECT supplier_id FROM users WHERE id=?", String.class, subject);
        if (sups.isEmpty()) throw ApiExceptionCodes.notFound("target user is not registered");
        String userSup = sups.get(0);
        if (userSup == null || !userSup.equals(actorSup)) {
            throw ApiExceptionCodes.membership("target user is not a member of the actor's supplier");
        }
        if (role.equals("platform_admin") || role.equals("supplier_admin")) {
            throw ApiExceptionCodes.noMatchingGrant();
        }
        // scope boundary: an 'all' request must be backed by an all-scope grant;
        // if only an own-scope grant exists, the request exceeds the grant.
        if (scope.equals("all")) {
            try {
                coveringGrant(actorSup, role, "all", tenant);
            } catch (ApiException e) {
                if (!"no_matching_grant".equals(e.error)) throw e;
                try {
                    coveringGrant(actorSup, role, "own", tenant);
                } catch (ApiException e2) {
                    if (!"no_matching_grant".equals(e2.error)) throw e2;
                    throw ApiExceptionCodes.noMatchingGrant();
                }
                throw ApiExceptionCodes.scopeExceeded();
            }
        }
        String grantId = coveringGrant(actorSup, role, scope, tenant);
        Map<String, Object> g = db.queryForMap(
                "SELECT tenant_id, scope FROM grants WHERE id=?", grantId);
        String gScope = String.valueOf(g.get("scope"));
        String gTenant = g.get("tenant_id") == null ? "" : String.valueOf(g.get("tenant_id"));
        return new DelegatedAssignment(grantId, gScope, gTenant, actorSup);
    }

    /** Supplier org of the acting user ("" = platform staff). */
    public String actorSupplier(String actor) {
        List<String> sup = db.queryForList(
                "SELECT supplier_id FROM users WHERE id=? AND status='active'", String.class, actor);
        if (sup.isEmpty()) throw ApiExceptionCodes.forbidden();
        return sup.get(0) == null ? "" : sup.get(0);
    }

    // ------------------------------------------------------------------
    // Suppliers (platform user management)
    // ------------------------------------------------------------------

    public Map<String, Object> supplierRow(String id, String name, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("status", status);
        return m;
    }

    public Map<String, Object> createSupplier(String actor, String id, String name) {
        String finalId = (id == null || id.isBlank()) ? "sup-" + UUID.randomUUID().toString().substring(0, 8) : id;
        db.update("INSERT INTO suppliers (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING", finalId, name);
        audit(actor, null, "supplier_create", Map.of("supplier", finalId, "name", name));
        return getSupplier(finalId);
    }

    public Map<String, Object> updateSupplier(String actor, String id, String name, String status) {
        if (name != null && !name.isBlank()) {
            db.update("UPDATE suppliers SET name=? WHERE id=?", name, id);
        }
        if (status != null && !status.isBlank()) {
            db.update("UPDATE suppliers SET status=? WHERE id=?", status, id);
            List<String> subjects = db.queryForList(
                    "SELECT id FROM users WHERE supplier_id=?", String.class, id);
            touch("", subjects);
            audit(actor, null, "supplier_update", Map.of("supplier", id, "status", status));
        }
        return getSupplier(id);
    }

    public Map<String, Object> getSupplier(String id) {
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT id, name, status FROM suppliers WHERE id=?", id);
        if (rows.isEmpty()) throw ApiExceptionCodes.notFound(null);
        Map<String, Object> r = rows.get(0);
        return supplierRow((String) r.get("id"), (String) r.get("name"), (String) r.get("status"));
    }

    public List<Map<String, Object>> listSuppliers() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : db.queryForList("SELECT id, name, status FROM suppliers ORDER BY id")) {
            out.add(supplierRow((String) r.get("id"), (String) r.get("name"), (String) r.get("status")));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Users (CRUD + supplier-admin confinement)
    // ------------------------------------------------------------------

    public Map<String, Object> userRow(String id, Object supplierId, String displayName, String kind, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("supplier_id", supplierId);
        m.put("display_name", displayName);
        m.put("kind", kind);
        m.put("status", status);
        return m;
    }

    public Map<String, Object> createUser(String actor, String actorSup, String id, String displayName, String supplier) {
        if (actorSup != null && !actorSup.isBlank() && !actorSup.equals(supplier)) {
            throw ApiExceptionCodes.membership("supplier admins may only create users in their own supplier");
        }
        String finalId = (id == null || id.isBlank()) ? "user-" + UUID.randomUUID().toString().substring(0, 8) : id;
        String kind = (supplier == null || supplier.isBlank()) ? "platform" : "supplier";
        db.update("INSERT INTO users (id, supplier_id, display_name, kind) VALUES (?, ?, ?, ?) ON CONFLICT (id) DO NOTHING",
                finalId, (supplier == null || supplier.isBlank()) ? null : supplier, displayName, kind);
        audit(actor, null, "user_create", Map.of("user", finalId, "supplier", supplier));
        return getUser(finalId);
    }

    public Map<String, Object> updateUser(String actor, String actorSup, String id, String displayName, String status) {
        requireOwnMembership(actorSup, id);
        if (displayName != null && !displayName.isBlank()) {
            db.update("UPDATE users SET display_name=? WHERE id=?", displayName, id);
        }
        if (status != null && !status.isBlank()) {
            db.update("UPDATE users SET status=? WHERE id=?", status, id);
            touch("", List.of(id));
            audit(actor, null, "user_update", Map.of("user", id, "status", status));
        }
        return getUser(id);
    }

    public Map<String, Object> getUser(String id) {
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT id, supplier_id, display_name, kind, status FROM users WHERE id=?", id);
        if (rows.isEmpty()) throw ApiExceptionCodes.notFound(null);
        Map<String, Object> r = rows.get(0);
        return userRow((String) r.get("id"), r.get("supplier_id"),
                (String) r.get("display_name"), (String) r.get("kind"), (String) r.get("status"));
    }

    public List<Map<String, Object>> listUsers(String supplier) {
        String q = "SELECT id, supplier_id, display_name, kind, status FROM users";
        List<Map<String, Object>> rows = (supplier == null || supplier.isBlank())
                ? db.queryForList(q + " ORDER BY id")
                : db.queryForList(q + " WHERE supplier_id=? ORDER BY id", supplier);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            out.add(userRow((String) r.get("id"), r.get("supplier_id"),
                    (String) r.get("display_name"), (String) r.get("kind"), (String) r.get("status")));
        }
        return out;
    }

    /** Offboarding: suspend identity — request-time checks drop authority at once. */
    public void deleteUser(String actor, String actorSup, String id) {
        requireOwnMembership(actorSup, id);
        db.update("UPDATE users SET status='suspended' WHERE id=?", id);
        touch("", List.of(id));
        audit(actor, null, "user_delete", Map.of("user", id));
    }

    private void requireOwnMembership(String actorSup, String userId) {
        if (actorSup == null || actorSup.isBlank()) return; // platform staff
        List<String> sups = db.queryForList("SELECT supplier_id FROM users WHERE id=?", String.class, userId);
        if (sups.isEmpty()) throw ApiExceptionCodes.notFound(null);
        String sup = sups.get(0);
        if (sup == null || !sup.equals(actorSup)) {
            throw ApiExceptionCodes.membership("target user is not part of the actor's supplier");
        }
    }

    // ------------------------------------------------------------------
    // Grants (Root RBAC authority)
    // ------------------------------------------------------------------

    public Map<String, Object> grantRow(String id, String supplier, String role, Object tenant,
                                        String scope, boolean canDelegate, Object expiresAt, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("supplier_id", supplier);
        m.put("role", role);
        m.put("tenant", tenant);
        m.put("scope", scope);
        m.put("can_delegate", canDelegate);
        m.put("expires_at", expiresAt);
        m.put("status", status);
        return m;
    }

    public Map<String, Object> createGrant(String actor, String supplier, String role, String tenant,
                                           String scope, OffsetDateTime expiresAt, boolean canDelegate) {
        if (scope == null || !(scope.equals("own") || scope.equals("all"))) {
            throw new ApiException(400, "bad_scope", "scope must be own|all");
        }
        if (db.queryForList("SELECT 1 FROM suppliers WHERE id=?", String.class, supplier).isEmpty()) {
            throw new ApiException(403, "unknown_supplier", "supplier not registered");
        }
        if (db.queryForList("SELECT 1 FROM roles WHERE name=?", String.class, role).isEmpty()) {
            throw new ApiException(403, "unknown_role", "role is not defined by root RBAC");
        }
        String id = newId("grant");
        db.update("INSERT INTO grants (id, supplier_id, role_name, tenant_id, scope, can_delegate, expires_at, granted_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, supplier, role, (tenant == null || tenant.isBlank()) ? null : tenant,
                scope, canDelegate, expiresAt, actor);
        audit(actor, tenant, "grant_create", Map.of("grant", id, "supplier", supplier,
                "role", role, "tenant", tenant, "scope", scope));
        return getGrant(id);
    }

    public void revokeGrant(String actor, String id) {
        int n = db.update("UPDATE grants SET status='revoked' WHERE id=? AND status='active'", id);
        if (n == 0) throw ApiExceptionCodes.notFound(null);
        List<String> subjects = db.queryForList(
                "SELECT DISTINCT subject FROM assignments WHERE grant_id=?", String.class, id);
        touch("", subjects);
        List<String> supRows = db.queryForList("SELECT supplier_id FROM grants WHERE id=?", String.class, id);
        String supplier = (supRows == null || supRows.isEmpty() || supRows.get(0) == null)
                ? null : supRows.get(0);
        audit(actor, null, "grant_revoke", supplier == null
                ? Map.of("grant", id, "subjects", subjects)
                : Map.of("grant", id, "supplier", supplier, "subjects", subjects));
    }

    public Map<String, Object> getGrant(String id) {
        List<Map<String, Object>> rows = db.queryForList("""
                SELECT id, supplier_id, role_name, tenant_id, scope, can_delegate, expires_at, status
                FROM grants WHERE id=?""", id);
        if (rows.isEmpty()) throw ApiExceptionCodes.notFound(null);
        return grantMap(rows.get(0));
    }

    private Map<String, Object> grantMap(Map<String, Object> r) {
        return grantRow((String) r.get("id"), (String) r.get("supplier_id"), (String) r.get("role_name"),
                r.get("tenant_id"), (String) r.get("scope"),
                Boolean.TRUE.equals(r.get("can_delegate")), rfc3339(r.get("expires_at")), (String) r.get("status"));
    }

    public List<Map<String, Object>> listGrants(String supplier) {
        String q = "SELECT id, supplier_id, role_name, tenant_id, scope, can_delegate, expires_at, status FROM grants";
        List<Map<String, Object>> rows = (supplier == null || supplier.isBlank())
                ? db.queryForList(q + " ORDER BY created_at DESC")
                : db.queryForList(q + " WHERE supplier_id=? ORDER BY created_at DESC", supplier);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) out.add(grantMap(r));
        return out;
    }

    // ------------------------------------------------------------------
    // Assignments
    // ------------------------------------------------------------------

    public Map<String, Object> assignmentRow(String id, String subject, Object tenant, String role, String scope,
                                             Object grantId, Object supplierId, Object groupId,
                                             String status, String createdBy) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("subject", subject);
        m.put("tenant", tenant);
        m.put("role", role);
        m.put("scope", scope);
        m.put("grant_id", grantId);
        m.put("supplier_id", supplierId);
        m.put("group_id", groupId);
        m.put("status", status);
        m.put("created_by", createdBy);
        return m;
    }

    /**
     * Assignment-time validation:
     *   platform staff  : direct authority (root power, no grant needed)
     *   supplier admin  : membership + covering root grant + scope boundary
     */
    public Map<String, Object> createAssignment(String actor, String subject, String role,
                                                String tenant, String scope) {
        String effScope = (scope == null || scope.isBlank()) ? "own" : scope;
        if (!effScope.equals("own") && !effScope.equals("all")) {
            throw new ApiException(400, "bad_scope", "scope must be own|all");
        }
        if (db.queryForList("SELECT 1 FROM roles WHERE name=?", String.class, role).isEmpty()) {
            throw new ApiException(400, "unknown_role", "role is not defined");
        }
        List<Map<String, Object>> subRows = db.queryForList(
                "SELECT status, supplier_id FROM users WHERE id=?", subject);
        if (subRows.isEmpty()) throw new ApiException(403, "unknown_subject", "target user is not registered");
        Object userSupObj = subRows.get(0).get("supplier_id");
        String userSup = userSupObj == null ? "" : String.valueOf(userSupObj);
        String actorSup = actorSupplier(actor);

        String grantId = null, supplierRef = null, asgScope = effScope, asgTenant = tenant;
        if (actorSup.isBlank()) {
            // platform staff: direct authority; supplier user assignments are
            // inherently own-scoped to their own supplier's records
            if (!userSup.isBlank()) {
                asgScope = "own";
                supplierRef = userSup;
            }
        } else {
            if (userSup.isBlank() || !userSup.equals(actorSup)) {
                throw ApiExceptionCodes.membership("target user is not a member of the actor's supplier");
            }
            var d = delegatedAssignment(actorSup, subject, role, tenant, effScope);
            grantId = d.grantId();
            asgScope = d.scope();
            asgTenant = d.tenant();
            supplierRef = d.supplierId();
        }
        String id = newId("asgmt");
        db.update("""
                INSERT INTO assignments (id, subject, tenant_id, role_name, scope, grant_id, supplier_id, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                id, subject, (asgTenant == null || asgTenant.isBlank()) ? null : asgTenant,
                role, asgScope, grantId, supplierRef, actor);
        touch("", List.of(subject));
        audit(actor, asgTenant, "assignment_create", Map.of(
                "assignment", id, "subject", subject, "role", role, "scope", asgScope,
                "grant", grantId == null ? "" : grantId, "supplier", supplierRef == null ? "" : supplierRef));
        return getAssignment(id);
    }

    public void revokeAssignment(String actor, String id) {
        String actorSup = actorSupplier(actor);
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT subject, supplier_id FROM assignments WHERE id=?", id);
        if (rows.isEmpty()) throw ApiExceptionCodes.notFound(null);
        Object sup = rows.get(0).get("supplier_id");
        String subject = String.valueOf(rows.get(0).get("subject"));
        if (!actorSup.isBlank() && (sup == null || !actorSup.equals(String.valueOf(sup)))) {
            throw ApiExceptionCodes.membership("assignment does not belong to the actor's supplier");
        }
        db.update("UPDATE assignments SET status='revoked' WHERE id=?", id);
        touch("", List.of(subject));
        audit(actor, null, "assignment_revoke", Map.of("assignment", id, "subject", subject));
    }

    public Map<String, Object> getAssignment(String id) {
        List<Map<String, Object>> rows = db.queryForList("""
                SELECT id, subject, tenant_id, role_name, scope, grant_id, supplier_id, group_id, status, created_by
                FROM assignments WHERE id=?""", id);
        if (rows.isEmpty()) throw ApiExceptionCodes.notFound(null);
        return assignmentMap(rows.get(0));
    }

    private Map<String, Object> assignmentMap(Map<String, Object> r) {
        return assignmentRow((String) r.get("id"), (String) r.get("subject"), r.get("tenant_id"),
                (String) r.get("role_name"), (String) r.get("scope"), r.get("grant_id"),
                r.get("supplier_id"), r.get("group_id"), (String) r.get("status"), (String) r.get("created_by"));
    }

    public List<Map<String, Object>> listAssignments(String subject, String tenant, String supplier) {
        StringBuilder q = new StringBuilder("""
                SELECT id, subject, tenant_id, role_name, scope, grant_id, supplier_id, group_id, status, created_by
                FROM assignments WHERE true""");
        List<Object> args = new ArrayList<>();
        if (subject != null && !subject.isBlank()) {
            args.add(subject); q.append(" AND subject=?");
        }
        if (tenant != null && !tenant.isBlank()) {
            args.add(tenant); q.append(" AND (tenant_id=? OR tenant_id IS NULL)");
        }
        if (supplier != null && !supplier.isBlank()) {
            args.add(supplier); q.append(" AND supplier_id=?");
        }
        q.append(" ORDER BY created_at DESC LIMIT 200");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : db.queryForList(q.toString(), args.toArray())) {
            out.add(assignmentMap(r));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Roles (root-owned catalog; CRUD)
    // ------------------------------------------------------------------

    public Map<String, Object> createRole(String actor, String name, String description, List<String> perms) {
        if (name == null || name.isBlank()) throw new ApiException(400, "bad_request", "role name required");
        db.update("INSERT INTO roles (name, description) VALUES (?, ?) ON CONFLICT (name) DO NOTHING", name, description);
        if (perms != null) for (String p : perms) {
            db.update("INSERT INTO role_permissions (role_name, permission_id) VALUES (?, ?) ON CONFLICT DO NOTHING", name, p);
        }
        audit(actor, null, "role_create", Map.of("role", name, "permissions", perms == null ? List.of() : perms));
        return getRole(name);
    }

    public Map<String, Object> updateRole(String actor, String name, String description, List<String> perms) {
        if (description != null && !description.isBlank()) {
            db.update("UPDATE roles SET description=? WHERE name=?", description, name);
        }
        if (perms != null && !perms.isEmpty()) {
            db.update("DELETE FROM role_permissions WHERE role_name=?", name);
            for (String p : perms) {
                db.update("INSERT INTO role_permissions (role_name, permission_id) VALUES (?, ?) ON CONFLICT DO NOTHING", name, p);
            }
            List<String> subjects = db.queryForList(
                    "SELECT DISTINCT subject FROM assignments WHERE role_name=?", String.class, name);
            touch("", subjects);
        }
        audit(actor, null, "role_update", Map.of("role", name));
        return getRole(name);
    }

    public void deleteRole(String actor, String name) {
        List<String> subjects = db.queryForList(
                "SELECT DISTINCT subject FROM assignments WHERE role_name=?", String.class, name);
        db.update("DELETE FROM assignments WHERE role_name=?", name);
        db.update("DELETE FROM role_permissions WHERE role_name=?", name);
        db.update("DELETE FROM roles WHERE name=?", name);
        touch("", subjects);
        audit(actor, null, "role_delete", Map.of("role", name));
    }

    public Map<String, Object> getRole(String name) {
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT name, description FROM roles WHERE name=?", name);
        if (rows.isEmpty()) throw ApiExceptionCodes.notFound(null);
        Map<String, Object> r = rows.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", r.get("name"));
        out.put("description", r.get("description"));
        out.put("permissions", rolePermissions(name));
        return out;
    }

    public List<Map<String, Object>> listRoles() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : db.queryForList("SELECT name, description FROM roles ORDER BY name")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", r.get("name"));
            m.put("description", r.get("description"));
            m.put("permissions", rolePermissions((String) r.get("name")));
            out.add(m);
        }
        return out;
    }

    private List<String> rolePermissions(String name) {
        return db.queryForList(
                "SELECT permission_id FROM role_permissions WHERE role_name=? ORDER BY permission_id", String.class, name);
    }

    // ------------------------------------------------------------------
    // Local supplier groups mapped onto delegated roles
    // ------------------------------------------------------------------

    public Map<String, Object> groupRow(String id, String supplier, String name, String createdBy,
                                        List<Map<String, Object>> roles, List<Map<String, Object>> members) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("supplier_id", supplier);
        m.put("name", name);
        m.put("created_by", createdBy);
        m.put("roles", roles == null ? List.of() : roles);
        m.put("members", members == null ? List.of() : members);
        return m;
    }

    public Map<String, Object> createGroup(String actor, String actorSup, String name, String supplier) {
        String sup = actorSup;
        if (sup == null || sup.isBlank()) sup = supplier;
        if (sup == null || sup.isBlank()) throw new ApiException(400, "bad_request", "supplier required");
        String id = newId("grp");
        db.update("INSERT INTO supplier_groups (id, supplier_id, name, created_by) VALUES (?, ?, ?, ?)", id, sup, name, actor);
        audit(actor, null, "group_create", Map.of("group", id, "supplier", sup, "name", name));
        return getGroup(id);
    }

    public Map<String, Object> getGroup(String id) {
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT id, supplier_id, name, created_by FROM supplier_groups WHERE id=?", id);
        if (rows.isEmpty()) throw ApiExceptionCodes.notFound(null);
        Map<String, Object> r = rows.get(0);
        List<Map<String, Object>> roles = new ArrayList<>();
        for (Map<String, Object> gr : db.queryForList(
                "SELECT role_name, scope FROM group_roles WHERE group_id=?", id)) {
            roles.add(Map.of("role", gr.get("role_name"), "scope", String.valueOf(gr.get("scope"))));
        }
        List<Map<String, Object>> members = new ArrayList<>();
        for (String u : db.queryForList("SELECT user_id FROM group_members WHERE group_id=?", String.class, id)) {
            members.add(Map.of("user_id", u));
        }
        return groupRow((String) r.get("id"), (String) r.get("supplier_id"), (String) r.get("name"),
                (String) r.get("created_by"), roles, members);
    }

    public List<String> listGroups(String supplier) {
        return db.queryForList("SELECT id FROM supplier_groups WHERE (?='' OR supplier_id=?) ORDER BY created_at DESC",
                String.class, supplier, supplier);
    }

    /** Binds a delegated role onto a local group (root grant boundary enforced). */
    public Map<String, Object> mapGroupRole(String actor, String groupId, String role, String scope) {
        String effScope = (scope == null || scope.isBlank()) ? "own" : scope;
        Map<String, Object> group = getGroup(groupId);
        String actorSup = actorSupplier(actor);
        String groupSup = String.valueOf(group.get("supplier_id"));
        if (!actorSup.isBlank() && !groupSup.equals(actorSup)) {
            throw ApiExceptionCodes.membership("group does not belong to the actor's supplier");
        }
        if (db.queryForList("SELECT 1 FROM roles WHERE name=?", String.class, role).isEmpty()) {
            throw new ApiException(400, "unknown_role", "role is not defined");
        }
        String grantId = coveringGrant(groupSup, role, effScope, "");
        db.update("INSERT INTO group_roles (id, group_id, role_name, scope) VALUES (?, ?, ?, ?)",
                newId("gr"), groupId, role, effScope);
        // materialize assignments for existing members through this grant
        for (String uid : db.queryForList("SELECT user_id FROM group_members WHERE group_id=?", String.class, groupId)) {
            materializeGroupAssignment(actor, groupId, uid, role, effScope, grantId, groupSup);
        }
        audit(actor, null, "group_role_map", Map.of("group", groupId, "role", role, "scope", effScope));
        return getGroup(groupId);
    }

    public Map<String, Object> groupAddMember(String actor, String groupId, String userId) {
        Map<String, Object> group = getGroup(groupId);
        String actorSup = actorSupplier(actor);
        String groupSup = String.valueOf(group.get("supplier_id"));
        if (!actorSup.isBlank() && !groupSup.equals(actorSup)) {
            throw ApiExceptionCodes.membership("group does not belong to the actor's supplier");
        }
        List<String> sups = db.queryForList(
                "SELECT supplier_id FROM users WHERE id=? AND status='active'", String.class, userId);
        if (sups.isEmpty() || sups.get(0) == null || !sups.get(0).equals(groupSup)) {
            throw ApiExceptionCodes.membership("target user is not a member of the group's supplier");
        }
        db.update("INSERT INTO group_members (group_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING", groupId, userId);
        for (Map<String, Object> gr : db.queryForList(
                "SELECT role_name, scope FROM group_roles WHERE group_id=?", groupId)) {
            String role = String.valueOf(gr.get("role_name"));
            String scope = String.valueOf(gr.get("scope"));
            try {
                String grantId = coveringGrant(groupSup, role, scope, "");
                materializeGroupAssignment(actor, groupId, userId, role, scope, grantId, groupSup);
            } catch (ApiException e) {
                if (!"no_matching_grant".equals(e.error)) throw e;
                // grant gone: membership without active grant = powerless
            }
        }
        audit(actor, null, "group_member_add", Map.of("group", groupId, "user", userId));
        return getGroup(groupId);
    }

    private void materializeGroupAssignment(String actor, String groupId, String userId,
                                            String role, String scope, String grantId, String supplier) {
        int exists = 0;
        try {
            Integer e = db.queryForObject(
                    "SELECT COUNT(*) FROM assignments WHERE subject=? AND role_name=? AND grant_id=? AND status='active'",
                    Integer.class, userId, role, grantId);
            exists = e == null ? 0 : e;
        } catch (Exception ignore) {}
        if (exists == 0) {
            Object tenantId = db.queryForList("SELECT tenant_id FROM grants WHERE id=?", grantId)
                    .stream().findFirst().map(m -> m.get("tenant_id")).orElse(null);
            String id = newId("asgmt");
            db.update("""
                    INSERT INTO assignments (id, subject, tenant_id, role_name, scope, grant_id, supplier_id, group_id, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    id, userId, tenantId, role, scope, grantId, supplier, groupId, actor);
            touch("", List.of(userId));
        }
    }

    public void groupRemoveMember(String actor, String groupId, String userId) {
        Map<String, Object> group = getGroup(groupId);
        String actorSup = actorSupplier(actor);
        String groupSup = String.valueOf(group.get("supplier_id"));
        if (!actorSup.isBlank() && !groupSup.equals(actorSup)) {
            throw ApiExceptionCodes.membership("group does not belong to the actor's supplier");
        }
        db.update("DELETE FROM group_members WHERE group_id=? AND user_id=?", groupId, userId);
        db.update("DELETE FROM assignments WHERE group_id=? AND subject=?", groupId, userId);
        touch("", List.of(userId));
        audit(actor, null, "group_member_remove", Map.of("group", groupId, "user", userId));
    }

    public void deleteGroup(String actor, String groupId) {
        Map<String, Object> group = getGroup(groupId);
        String actorSup = actorSupplier(actor);
        String groupSup = String.valueOf(group.get("supplier_id"));
        if (!actorSup.isBlank() && !groupSup.equals(actorSup)) {
            throw ApiExceptionCodes.membership("group does not belong to the actor's supplier");
        }
        List<String> subjects = db.queryForList(
                "SELECT subject FROM assignments WHERE group_id=?", String.class, groupId);
        db.update("DELETE FROM assignments WHERE group_id=?", groupId);
        db.update("DELETE FROM group_members WHERE group_id=?", groupId);
        db.update("DELETE FROM group_roles WHERE group_id=?", groupId);
        db.update("DELETE FROM supplier_groups WHERE id=?", groupId);
        touch("", subjects);
        audit(actor, null, "group_delete", Map.of("group", groupId));
    }

    // ------------------------------------------------------------------
    // Audit + permission registry (auto pickup)
    // ------------------------------------------------------------------

    public List<Map<String, Object>> audit(String tenant, String supplier, int limit) {
        int n = (limit <= 0 || limit > 500) ? 100 : limit;
        StringBuilder q = new StringBuilder("""
                SELECT actor, tenant_id, action, ts FROM audit_log
                WHERE (?='' OR tenant_id=? OR tenant_id IS NULL)""");
        List<Object> args = new ArrayList<>();
        args.add(tenant); args.add(tenant);
        if (supplier != null && !supplier.isBlank()) {
            q.append(" AND details->>'supplier'=?");
            args.add(supplier);
        }
        q.append(" ORDER BY id DESC LIMIT ").append(n);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : db.queryForList(q.toString(), args.toArray())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("actor", r.get("actor"));
            m.put("tenant", r.get("tenant_id"));
            m.put("action", r.get("action"));
            m.put("ts", rfc3339(r.get("ts")));
            out.add(m);
        }
        return out;
    }

    /** Upserts a service's controller-derived permissions (auto pickup). */
    public int registerCatalog(String service, List<Map<String, String>> perms) {
        int n = 0;
        for (Map<String, String> p : perms) {
            n += db.update("INSERT INTO permissions (id, description) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                    p.get("id"), p.getOrDefault("description", ""));
        }
        audit(service, null, "permission_register", Map.of("service", service,
                "count", perms.size(), "permissions", perms.stream().map(p -> p.get("id")).toList()));
        return n;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static String rfc3339(Object ts) {
        if (ts == null) return null;
        if (ts instanceof Timestamp t) return RFC3339.format(t.toInstant().atOffset(ZoneOffset.UTC));
        if (ts instanceof OffsetDateTime o) return RFC3339.format(o.toInstant().atOffset(ZoneOffset.UTC));
        if (ts instanceof Instant i) return RFC3339.format(i.atOffset(ZoneOffset.UTC));
        if (ts instanceof BigDecimal b) return RFC3339.format(Instant.ofEpochMilli(b.longValue()).atOffset(ZoneOffset.UTC));
        return String.valueOf(ts);
    }
}
