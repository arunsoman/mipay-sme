package trustplane.rbac.core;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import trustplane.rbac.config.RbacConfig;
import trustplane.shared.ApiException;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes that are not assignments: audit, cache invalidation (Watch), the org-tree sync,
 * the catalogue registry and role grids (idp-rbac-cutover-plan.md P2.1-P2.4, P2.6a).
 */
@Service
@lombok.RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class RbacStore {

    static final DateTimeFormatter RFC3339 = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
    private static final SecureRandom RNG = new SecureRandom();
    static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate db;
    private final WatchHub watch;
    private final Policy policy;
    private final RbacConfig cfg;
    private final Ceilings ceilings;

    public static String newId(String prefix) {
        byte[] r = new byte[9];
        RNG.nextBytes(r);
        return prefix + "-" + Long.toHexString(System.currentTimeMillis()) + "-" + Base64.getUrlEncoder().withoutPadding().encodeToString(r);
    }

    public void audit(String actor, String tenant, String action, Map<String, Object> details) {
        try {
            db.update("INSERT INTO audit_log (actor, tenant_id, action, details) VALUES (?, ?, ?, ?::jsonb)",
                    actor, tenant, action, JSON.writeValueAsString(details));
        } catch (Exception e) {
            log.warn("[rbac] audit write failed for {}: {}", action, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Invalidation: the gateway purges cached decisions on every event
    // ------------------------------------------------------------------

    /** Subject-level change (assignments): every tenant of these subjects. */
    public void touchSubjects(List<String> subjects) {
        if (subjects == null || subjects.isEmpty()) return;
        watch.publish("", 0, subjects);
    }

    /** Policy-wide change (role grid, hierarchy, registry): every cached decision. */
    public void touchAll() {
        watch.publish("", 0, List.of());
    }

    /** A tenant changed (hold, lifecycle): it and every org below it. */
    public void touchTenantTree(String tenant) {
        List<String> ids = new ArrayList<>(db.queryForList(
                "SELECT descendant_id FROM org_closure WHERE ancestor_id=?", String.class, tenant));
        if (!ids.contains(tenant)) ids.add(tenant);
        for (String id : ids) {
            List<Long> v = db.queryForList("UPDATE tenants SET version=version+1 WHERE id=? RETURNING version", Long.class, id);
            watch.publish(id, v.isEmpty() ? 0 : v.get(0), List.of());
        }
    }

    // ------------------------------------------------------------------
    // Org-tree sync (P2.6a): closure + every org's type, parent, lifecycle and holds
    // ------------------------------------------------------------------

    /**
     * One org as the core sends it. {@code ceilingSent}: the payload carried the role ceiling
     * (extended-rbac X6); then {@code roleCeiling} null means "no explicit ceiling" (inherit).
     */
    public record OrgRow(String id, String name, String type, String parent, String lifecycle, boolean held, List<String> holds,
                         boolean ceilingSent, List<String> roleCeiling, List<String> roleDelegable) {
        public OrgRow(String id, String name, String type, String parent, String lifecycle, boolean held, List<String> holds) {
            this(id, name, type, parent, lifecycle, held, holds, false, null, null);
        }
    }

    /**
     * Replaces the core's part of the tree ({@code source='mipay'}); fixtures are untouched.
     * Orgs that disappeared from the core are kept as ARCHIVED (assignments on them stop working).
     */
    @Transactional
    public Map<String, Object> syncOrgs(List<Map<String, Object>> closure, List<OrgRow> orgs) {
        Map<String, Map<String, Object>> before = new HashMap<>();
        for (Map<String, Object> r : db.queryForList("SELECT id, held, lifecycle, role_ceiling, role_delegable FROM tenants WHERE source='mipay'")) {
            before.put((String) r.get("id"), r);
        }
        Set<String> changed = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        for (OrgRow o : orgs) {
            if (o.id() == null || o.id().isBlank()) continue;
            seen.add(o.id());
            String lifecycle = o.lifecycle() == null || o.lifecycle().isBlank() ? "ACTIVE" : o.lifecycle();
            String holds = o.holds() == null ? "" : String.join(",", o.holds());
            db.update("""
                    INSERT INTO tenants (id, name, org_type, parent_id, lifecycle, held, hold_reasons, source, updated_at)
                    VALUES (?,?,?,?,?,?,?,'mipay',now())
                    ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, org_type=COALESCE(EXCLUDED.org_type, tenants.org_type),
                        parent_id=EXCLUDED.parent_id, lifecycle=EXCLUDED.lifecycle, held=EXCLUDED.held,
                        hold_reasons=EXCLUDED.hold_reasons, source='mipay', updated_at=now()""",
                    o.id(), o.name() == null ? o.id() : o.name(), o.type(), o.parent(), lifecycle, o.held(), holds);
            Map<String, Object> b = before.get(o.id());
            if (b == null || !Boolean.valueOf(o.held()).equals(b.get("held")) || !lifecycle.equals(b.get("lifecycle"))) changed.add(o.id());
            if (o.ceilingSent()) {
                List<String> roles = o.roleCeiling() == null ? null : o.roleCeiling().stream().distinct().sorted().toList();
                List<String> deleg = roles == null ? null : (o.roleDelegable() == null ? List.<String>of()
                        : o.roleDelegable().stream().filter(roles::contains).distinct().sorted().toList());
                db.update("UPDATE tenants SET role_ceiling=?::text[], role_delegable=?::text[] WHERE id=?",
                        roles == null ? null : SchemaAndSeed.pgArray(roles), deleg == null ? null : SchemaAndSeed.pgArray(deleg), o.id());
                Set<String> beforeRoles = b == null ? null : Ceilings.set(b.get("role_ceiling"));
                Set<String> beforeDeleg = b == null ? null : Ceilings.set(b.get("role_delegable"));
                if (!java.util.Objects.equals(beforeRoles, roles == null ? null : new LinkedHashSet<>(roles))
                        || !java.util.Objects.equals(beforeDeleg, deleg == null ? null : new LinkedHashSet<>(deleg))) {
                    changed.add(o.id());
                }
            }
        }
        for (String gone : before.keySet()) {
            if (!seen.contains(gone) && !orgs.isEmpty()) {
                db.update("UPDATE tenants SET lifecycle='ARCHIVED', updated_at=now() WHERE id=? AND source='mipay'", gone);
                changed.add(gone);
            }
        }
        int closureRows = 0;
        if (closure != null) {
            db.update("DELETE FROM org_closure WHERE source='mipay'");
            for (Map<String, Object> r : closure) {
                closureRows += db.update("INSERT INTO org_closure (ancestor_id, descendant_id, depth, source) VALUES (?,?,?,'mipay') ON CONFLICT DO NOTHING",
                        String.valueOf(r.get("ancestor")), String.valueOf(r.get("descendant")), ((Number) r.getOrDefault("depth", 0)).intValue());
            }
        }
        changed.addAll(ceilings.clampAll()); // child within parent (extended-rbac X4)
        for (String id : changed) touchTenantTree(id);
        if (closure != null || !changed.isEmpty()) touchAll(); // tree shape or a ceiling changed: subjects anywhere may be affected
        audit("mipay", null, "org_tree_sync", Map.of("orgs", orgs.size(), "closure_rows", closureRows, "changed", changed));
        return Map.of("ok", true, "orgs", orgs.size(), "closureRows", closureRows, "changed", changed.size());
    }

    // ------------------------------------------------------------------
    // Registry (Appendix F/G.3): rows + used cells + the endpoints each cell protects
    // ------------------------------------------------------------------

    public record EndpointReg(String method, String path, String permission, String tenantSource, String handler) {}

    @Transactional
    public Map<String, Object> registerCatalog(String service, List<Map<String, String>> permissions, List<EndpointReg> endpoints) {
        if (service == null || service.isBlank()) throw ApiException.badRequest("service required");
        int newRows = 0;
        Set<String> rows = new LinkedHashSet<>();
        List<String> rejected = new ArrayList<>();
        for (Map<String, String> p : permissions) {
            Verbs.Cell c = Verbs.parse(p.get("id"));
            if (c == null) {
                rejected.add(p.get("id"));
                continue;
            }
            rows.add(c.row());
        }
        for (String row : rows) {
            newRows += db.update("""
                    INSERT INTO rbac_resources (id, service, description) VALUES (?,?,?) ON CONFLICT (id) DO NOTHING""",
                    row, service, row.replace('.', ' '));
        }
        db.update("DELETE FROM rbac_cell_endpoints WHERE service=?", service);
        int eps = 0;
        if (endpoints != null) {
            for (EndpointReg e : endpoints) {
                Verbs.Cell c = Verbs.parse(e.permission());
                if (c == null) continue;
                eps += db.update("""
                        INSERT INTO rbac_cell_endpoints (service, method, path, resource, verb, tenant_source, handler)
                        VALUES (?,?,?,?,?,?,?) ON CONFLICT (service, method, path) DO UPDATE SET resource=EXCLUDED.resource,
                            verb=EXCLUDED.verb, tenant_source=EXCLUDED.tenant_source, handler=EXCLUDED.handler""",
                        service, e.method(), e.path(), c.row(), c.bit(), e.tenantSource(), e.handler());
            }
        }
        // used = the cells that have an endpoint; rows nobody registers keep their declared cells
        db.update("""
                UPDATE rbac_resources r SET used_verbs = COALESCE(
                    (SELECT bit_or(verb) FROM rbac_cell_endpoints ce WHERE ce.resource = r.id), r.declared_verbs), updated_at=now()""");
        policy.reload();
        touchAll();
        List<String> orphans = orphanTicks();
        if (!orphans.isEmpty()) log.warn("[rbac] role ticks on cells no endpoint uses (shown greyed in the role pack): {}", orphans);
        audit(service, null, "permission_register", Map.of("service", service, "cells", permissions.size(),
                "endpoints", eps, "new_rows", newRows, "rejected", rejected));
        return Map.of("service", service, "registered", permissions.size() - rejected.size(), "endpoints", eps,
                "newRows", newRows, "rejected", rejected);
    }

    /** "role row:verb" ticks whose cell no registered endpoint uses. */
    public List<String> orphanTicks() {
        Policy.Snapshot s = policy.get();
        List<String> out = new ArrayList<>();
        for (Policy.Role r : s.roles().values()) {
            for (var g : r.grid().entrySet()) {
                Policy.Resource res = s.resources().get(g.getKey());
                int used = res == null ? 0 : res.used();
                int orphan = g.getValue() & ~used;
                for (String v : Verbs.names(orphan)) out.add(r.name() + " " + g.getKey() + ":" + v);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Roles and grids
    // ------------------------------------------------------------------

    public Map<String, Object> roleView(Policy.Role r) {
        Policy.Snapshot s = policy.get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", r.name());
        m.put("description", r.description());
        m.put("kind", r.kind());
        m.put("approval", r.approval());
        m.put("max_hours", r.maxHours());
        m.put("seeded", r.seeded());
        m.put("inherits", r.juniors());
        m.put("owner_tenant", r.ownerTenant());
        m.put("base_roles", r.baseRoles());
        Map<String, List<String>> grid = new LinkedHashMap<>();
        for (var e : new java.util.TreeMap<>(r.grid()).entrySet()) grid.put(e.getKey(), Verbs.names(e.getValue()));
        m.put("grid", grid);
        m.put("all_rows", Verbs.names(r.allRowsVerbs()));
        // flat effective list (own + inherited + all-rows), the shape the admin portal reads
        List<String> perms = new ArrayList<>();
        for (Policy.Resource res : s.resources().values()) {
            int mask = s.mask(r.name(), res.id()) & res.used();
            for (String v : Verbs.names(mask)) perms.add(res.id() + ":" + v);
        }
        m.put("permissions", perms);
        return m;
    }

    public List<Map<String, Object>> listRoles() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Policy.Role r : policy.get().roles().values()) out.add(roleView(r));
        return out;
    }

    public Map<String, Object> getRole(String name) {
        Policy.Role r = policy.get().roles().get(name);
        if (r == null) throw ApiException.notFound();
        return roleView(r);
    }

    /** Parses either {grid: {row: [verbs]}} or the flat {permissions: ["row:verb"]} form. */
    public Map<String, Integer> parseGrid(Map<String, List<String>> grid, List<String> flat) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (grid != null) {
            for (var e : grid.entrySet()) {
                try {
                    out.merge(e.getKey(), Verbs.mask(e.getValue()), (a, b) -> a | b);
                } catch (IllegalArgumentException ex) {
                    throw ApiException.badRequest(ex.getMessage());
                }
            }
        }
        if (flat != null) {
            for (String p : flat) {
                Verbs.Cell c = Verbs.parse(p);
                if (c == null) throw ApiException.badRequest("not a grid cell: " + p);
                out.merge(c.row(), c.bit(), (a, b) -> a | b);
            }
        }
        return out;
    }

    /** Grid save rules (G.5): cells must exist; no same-row submit+authorize; no administer+authorize in a platform role; no simulate in production. */
    public void validateGrid(String role, String kind, Map<String, Integer> grid) {
        Policy.Snapshot s = policy.get();
        boolean administer = false, authorize = false;
        for (var e : grid.entrySet()) {
            Policy.Resource res = s.resources().get(e.getKey());
            if (res == null) throw new ApiException(400, "unknown_row", e.getKey());
            int bad = e.getValue() & ~res.used();
            if (bad != 0) throw new ApiException(400, "cell_not_used", e.getKey() + ":" + String.join(",", Verbs.names(bad)) + " has no endpoint");
            int v = e.getValue();
            if ((v & Verbs.SUBMIT) != 0 && (v & Verbs.AUTHORIZE) != 0 && !s.exemptions().containsKey(e.getKey())) {
                throw new ApiException(409, "SOD_CONFLICT", role + ": " + e.getKey() + " ticks both submit and authorize (maker = checker)");
            }
            if ((v & Verbs.SIMULATE) != 0 && cfg.isProduction()) {
                throw new ApiException(409, "SOD_CONFLICT", role + ": the simulate column is disabled in production");
            }
            // "admins don't approve customers": approving an access request (rbac.assignment:authorize)
            // is access administration, not a customer decision, so rbac.* rows are not counted
            if (!e.getKey().startsWith("rbac.")) {
                administer |= (v & Verbs.ADMINISTER) != 0;
                authorize |= (v & Verbs.AUTHORIZE) != 0;
            }
        }
        if ("PLATFORM".equals(kind) && administer && authorize) {
            throw new ApiException(409, "SOD_CONFLICT", role + ": a platform role may not both administer access and authorize (admins don't approve customers)");
        }
    }

    @Transactional
    public Map<String, Object> createRole(String actor, String name, String description, String kind,
                                          Map<String, Integer> grid, List<String> inherits) {
        if (name == null || !name.matches("^[a-z][a-z0-9_]{2,63}$")) throw ApiException.badRequest("role name must be lower_snake_case");
        if (policy.get().roles().containsKey(name)) throw ApiException.conflict("role exists: " + name);
        String k = kind == null ? "ORG" : kind;
        if (!Set.of("ORG", "PLATFORM").contains(k)) throw ApiException.badRequest("kind must be ORG or PLATFORM");
        validateGrid(name, k, grid);
        db.update("INSERT INTO roles (name, description, kind, approval, seeded) VALUES (?,?,?,?,FALSE)",
                name, description == null ? "" : description, k, "ORG".equals(k) ? "none" : "platform");
        writeGrid(name, grid);
        setInheritance(name, inherits);
        policy.reload();
        audit(actor, null, "role_create", Map.of("role", name, "kind", k, "grid", grid));
        return getRole(name);
    }

    @Transactional
    public Map<String, Object> updateRole(String actor, String name, String description, Map<String, Integer> grid, List<String> inherits) {
        Policy.Role r = policy.get().roles().get(name);
        if (r == null) throw ApiException.notFound();
        if (description != null && !description.isBlank()) db.update("UPDATE roles SET description=?, updated_at=now() WHERE name=?", description, name);
        Map<String, Object> diff = new LinkedHashMap<>();
        if (grid != null) {
            validateGrid(name, r.kind(), grid);
            List<String> added = new ArrayList<>(), removed = new ArrayList<>();
            Set<String> rows = new HashSet<>(grid.keySet());
            rows.addAll(r.grid().keySet());
            for (String row : rows) {
                int before = r.grid().getOrDefault(row, 0), after = grid.getOrDefault(row, 0);
                Verbs.names(after & ~before).forEach(v -> added.add(row + ":" + v));
                Verbs.names(before & ~after).forEach(v -> removed.add(row + ":" + v));
            }
            diff.put("added", added);
            diff.put("removed", removed);
            writeGrid(name, grid);
        }
        if (inherits != null) setInheritance(name, inherits);
        policy.reload();
        touchAll();
        Map<String, Object> details = new LinkedHashMap<>(diff);
        details.put("role", name);
        audit(actor, null, "role_update", details);
        Map<String, Object> view = getRole(name);
        view.put("diff", diff);
        return view;
    }

    void writeGrid(String role, Map<String, Integer> grid) {
        db.update("DELETE FROM rbac_role_grid WHERE role=?", role);
        for (var e : grid.entrySet()) {
            if (e.getValue() != 0) db.update("INSERT INTO rbac_role_grid (role, resource, verbs) VALUES (?,?,?)", role, e.getKey(), e.getValue());
        }
    }

    void setInheritance(String role, List<String> juniors) {
        if (juniors == null) return;
        Map<String, List<String>> all = new HashMap<>();
        for (Policy.Role r : policy.get().roles().values()) all.put(r.name(), new ArrayList<>(r.juniors()));
        for (String j : juniors) if (!policy.get().roles().containsKey(j)) throw ApiException.badRequest("unknown role " + j);
        all.put(role, juniors);
        try {
            Policy.assertAcyclic(all);
        } catch (IllegalArgumentException e) {
            throw new ApiException(409, "hierarchy_cycle", e.getMessage());
        }
        db.update("DELETE FROM role_inheritance WHERE senior=?", role);
        for (String j : juniors) db.update("INSERT INTO role_inheritance (senior, junior) VALUES (?,?)", role, j);
    }

    @Transactional
    public void deleteRole(String actor, String name) {
        Policy.Role r = policy.get().roles().get(name);
        if (r == null) throw ApiException.notFound();
        if (r.seeded()) throw ApiException.conflict("seeded roles change through the reviewed seed, not the API");
        Integer used = db.queryForObject("SELECT count(*) FROM assignments WHERE role=? AND status IN ('ACTIVE','PENDING_APPROVAL')", Integer.class, name);
        if (used != null && used > 0) throw ApiException.conflict("role has " + used + " active or pending assignments");
        List<String> builtOn = db.queryForList("SELECT name FROM roles WHERE ? = ANY(base_roles)", String.class, name);
        if (!builtOn.isEmpty()) throw ApiException.conflict("org-defined roles are built on " + name + ": " + builtOn);
        db.update("DELETE FROM role_inheritance WHERE junior=?", name);
        db.update("DELETE FROM assignments WHERE role=?", name);
        db.update("DELETE FROM roles WHERE name=?", name);
        policy.reload();
        touchAll();
        audit(actor, null, "role_delete", Map.of("role", name));
    }

    // ------------------------------------------------------------------
    // Org-defined roles (docs/plans/extended-rbac.md X8-X11)
    // ------------------------------------------------------------------

    private static final List<String> APPROVAL_ORDER = List.of("none", "org_admin", "platform", "compliance", "break_glass");

    /** Validates an org-defined role against the owner's ceiling and its base roles; returns its approval rule. */
    String validateOrgRole(String name, String tenant, List<String> bases, Map<String, Integer> grid) {
        Policy.Snapshot s = policy.get();
        List<Map<String, Object>> t = db.queryForList("SELECT lifecycle FROM tenants WHERE id=?", tenant);
        if (t.isEmpty()) throw new ApiException(404, "unknown_tenant", "org not known to RBAC: " + tenant);
        if (Set.of("REJECTED", "BLOCKED", "CLOSED", "ARCHIVED").contains(String.valueOf(t.get(0).get("lifecycle")))) {
            throw ApiException.conflict("org is " + t.get(0).get("lifecycle") + ": no new roles");
        }
        if (bases == null || bases.isEmpty()) throw ApiException.badRequest("base_roles required: the roles this one is carved from");
        Ceilings.Effective c = ceilings.of(tenant);
        for (String b : bases) {
            Policy.Role br = s.roles().get(b);
            if (br == null || !"ORG".equals(br.kind()) || br.orgDefined()) {
                throw new ApiException(400, "bad_base_role", b + " is not a global org role");
            }
            if (!c.allows(b)) throw new ApiException(409, "ROLE_OUTSIDE_CEILING", b + " is outside the role ceiling of " + tenant);
        }
        for (Policy.SodRule r : s.sod()) {
            if (!Set.of("STATIC", "DYNAMIC").contains(r.kind())) continue;
            if (r.a().stream().anyMatch(bases::contains) && r.b().stream().anyMatch(bases::contains)) {
                throw new ApiException(409, "SOD_CONFLICT", "base roles span both sides of " + r.id() + " (" + r.reason() + ")");
            }
        }
        if (grid == null || grid.values().stream().allMatch(v -> v == 0)) throw ApiException.badRequest("the role ticks no cell");
        List<String> beyond = new ArrayList<>();
        for (var e : grid.entrySet()) {
            int bound = 0;
            for (String b : bases) bound |= s.mask(b, e.getKey());
            for (String v : Verbs.names(e.getValue() & ~bound)) beyond.add(e.getKey() + ":" + v);
        }
        if (!beyond.isEmpty()) throw new ApiException(409, "EXCEEDS_BASE_ROLES", "not in any base role: " + beyond);
        validateGrid(name, "ORG", grid);
        String approval = "none";
        for (String b : bases) {
            String a = s.roles().get(b).approval();
            if (APPROVAL_ORDER.indexOf(a) > APPROVAL_ORDER.indexOf(approval)) approval = a;
        }
        return approval;
    }

    public static String orgRoleName(String slug, String tenant) {
        if (slug == null || !slug.matches("^[a-z][a-z0-9_]{2,40}$")) throw ApiException.badRequest("role name must be lower_snake_case, 3-41 characters");
        return slug + "@" + tenant;
    }

    @Transactional
    public Map<String, Object> createOrgRole(String actor, String tenant, String slug, String description,
                                             List<String> bases, Map<String, Integer> grid) {
        String name = orgRoleName(slug, tenant);
        if (policy.get().roles().containsKey(name)) throw ApiException.conflict("role exists: " + name);
        String approval = validateOrgRole(name, tenant, bases, grid);
        db.update("INSERT INTO roles (name, description, kind, approval, seeded, owner_tenant, base_roles) VALUES (?,?,'ORG',?,FALSE,?,?::text[])",
                name, description == null ? "" : description, approval, tenant, SchemaAndSeed.pgArray(List.copyOf(new LinkedHashSet<>(bases))));
        writeGrid(name, grid);
        policy.reload();
        audit(actor, tenant, "org_role_create", Map.of("role", name, "base_roles", bases, "grid", grid));
        return getRole(name);
    }

    @Transactional
    public Map<String, Object> updateOrgRole(String actor, String name, String description, List<String> bases, Map<String, Integer> grid) {
        Policy.Role r = policy.get().roles().get(name);
        if (r == null || !r.orgDefined()) throw ApiException.notFound();
        List<String> newBases = bases == null ? r.baseRoles() : List.copyOf(new LinkedHashSet<>(bases));
        Map<String, Integer> newGrid = grid == null ? r.grid() : grid;
        String approval = validateOrgRole(name, r.ownerTenant(), newBases, newGrid);
        db.update("UPDATE roles SET description=COALESCE(NULLIF(?,''), description), approval=?, base_roles=?::text[], updated_at=now() WHERE name=?",
                description == null ? "" : description, approval, SchemaAndSeed.pgArray(newBases), name);
        if (grid != null) writeGrid(name, grid);
        policy.reload();
        touchAll();
        audit(actor, r.ownerTenant(), "org_role_update", Map.of("role", name, "base_roles", newBases, "grid", newGrid));
        return getRole(name);
    }

    /**
     * The roles an org works with: global ORG roles flagged by its ceiling, and its own
     * org-defined roles (with whether their base roles are still all inside the ceiling).
     */
    public Map<String, Object> rolesFor(String tenant) {
        Policy.Snapshot s = policy.get();
        if (db.queryForList("SELECT 1 FROM tenants WHERE id=?", tenant).isEmpty()) {
            throw new ApiException(404, "unknown_tenant", "org not known to RBAC: " + tenant);
        }
        Ceilings.Effective c = ceilings.of(tenant);
        List<Map<String, Object>> global = new ArrayList<>(), own = new ArrayList<>();
        for (Policy.Role r : s.roles().values()) {
            if (!"ORG".equals(r.kind())) continue;
            if (!r.orgDefined()) {
                Map<String, Object> v = roleView(r);
                v.put("in_ceiling", c.allows(r.name()));
                v.put("delegable", c.mayDelegate(r.name()));
                v.put("baseline", s.baseline().contains(r.name()));
                global.add(v);
            } else if (tenant.equals(r.ownerTenant())) {
                Map<String, Object> v = roleView(r);
                v.put("in_ceiling", r.baseRoles().stream().allMatch(c::allows));
                own.add(v);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ceiling", c.view(s));
        out.put("roles", global);
        out.put("org_roles", own);
        return out;
    }

    // ------------------------------------------------------------------
    // Read models
    // ------------------------------------------------------------------

    public List<Map<String, Object>> resources() {
        Policy.Snapshot s = policy.get();
        Map<String, List<Map<String, Object>>> eps = new HashMap<>();
        db.query("SELECT resource, verb, method, path, service, handler FROM rbac_cell_endpoints ORDER BY path, method", rs -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("verb", Verbs.names(rs.getInt("verb")).get(0));
            e.put("method", rs.getString("method"));
            e.put("path", rs.getString("path"));
            e.put("service", rs.getString("service"));
            e.put("handler", rs.getString("handler"));
            eps.computeIfAbsent(rs.getString("resource"), k -> new ArrayList<>()).add(e);
        });
        List<Map<String, Object>> out = new ArrayList<>();
        for (Policy.Resource r : s.resources().values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("service", r.service());
            m.put("description", r.description());
            m.put("used", Verbs.names(r.used()));
            m.put("declared", Verbs.names(r.declared()));
            m.put("tenant_types", r.tenantTypes());
            m.put("entitlement", Verbs.names(r.grantVerbs()));
            m.put("endpoints", eps.getOrDefault(r.id(), List.of()));
            out.add(m);
        }
        return out;
    }

    public List<Map<String, Object>> audit(String tenant, int limit) {
        int n = (limit <= 0 || limit > 500) ? 100 : limit;
        List<Map<String, Object>> out = new ArrayList<>();
        db.query("SELECT actor, tenant_id, action, details::text AS details, ts FROM audit_log WHERE (?='' OR tenant_id=? OR tenant_id IS NULL) ORDER BY id DESC LIMIT " + n,
                rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("actor", rs.getString("actor"));
                    m.put("tenant", rs.getString("tenant_id"));
                    m.put("action", rs.getString("action"));
                    m.put("details", rs.getString("details"));
                    m.put("ts", rfc3339(rs.getTimestamp("ts")));
                    out.add(m);
                }, tenant, tenant);
        return out;
    }

    public static String rfc3339(Object ts) {
        if (ts == null) return null;
        if (ts instanceof Timestamp t) return RFC3339.format(t.toInstant());
        if (ts instanceof OffsetDateTime o) return RFC3339.format(o.toInstant());
        if (ts instanceof Instant i) return RFC3339.format(i);
        return String.valueOf(ts);
    }
}
