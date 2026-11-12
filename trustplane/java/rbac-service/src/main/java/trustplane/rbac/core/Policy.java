package trustplane.rbac.core;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory snapshot of the slowly changing policy: rows, roles, grids, the role hierarchy,
 * SoD rules and exemptions. Assignments, tenants and grants are always read live. The
 * snapshot is replaced atomically on every change (seed, grid edit, registration).
 */
@Component
@lombok.RequiredArgsConstructor
public class Policy {

    public record Resource(String id, String service, String description, int declared, int used,
                           Set<String> tenantTypes, int grantVerbs, String grantAttribute) {}

    /**
     * A role. {@code ownerTenant} is set for an org-defined role (extended-rbac X8): it is
     * assignable only in that org and its mask is bounded by {@code baseRoles} at decision time.
     */
    public record Role(String name, String description, String kind, String approval, Integer maxHours,
                       int allRowsVerbs, boolean seeded, Map<String, Integer> grid, List<String> juniors,
                       String ownerTenant, List<String> baseRoles) {
        public int ownMask(String row) {
            return grid.getOrDefault(row, 0) | allRowsVerbs;
        }

        public boolean orgDefined() {
            return ownerTenant != null;
        }
    }

    public record SodRule(String id, String kind, List<String> a, List<String> b, String cellA, String cellB, String reason) {}

    public record Snapshot(Map<String, Resource> resources, Map<String, Role> roles,
                           Map<String, Set<String>> expanded, List<SodRule> sod, Map<String, String> exemptions,
                           Set<String> baseline) {

        /** Global ORG roles (the catalogue a ceiling chooses from), not org-defined ones. */
        public Set<String> globalOrgRoles() {
            Set<String> out = new LinkedHashSet<>();
            for (Role r : roles.values()) if ("ORG".equals(r.kind()) && !r.orgDefined()) out.add(r.name());
            return out;
        }

        /** For static SoD an org-defined role counts as its base roles (X9). */
        public Set<String> sodIdentity(String role) {
            Role r = roles.get(role);
            if (r == null || !r.orgDefined()) return Set.of(role);
            Set<String> out = new LinkedHashSet<>(r.baseRoles());
            out.add(role);
            return out;
        }

        /** Effective mask of a role on a row, including every inherited (junior) role. */
        public int mask(String role, String row) {
            int m = 0;
            for (String r : expanded.getOrDefault(role, Set.of())) {
                Role rr = roles.get(r);
                if (rr != null) m |= rr.ownMask(row);
            }
            return m;
        }
    }

    private final JdbcTemplate db;
    private volatile Snapshot snap = new Snapshot(Map.of(), Map.of(), Map.of(), List.of(), Map.of(), Set.of());

    public Snapshot get() {
        return snap;
    }

    public synchronized void reload() {
        Map<String, Resource> res = new LinkedHashMap<>();
        db.query("SELECT id, service, description, declared_verbs, used_verbs, tenant_types, grant_verbs, grant_attribute FROM rbac_resources WHERE NOT retired ORDER BY id",
                rs -> {
                    String types = rs.getString("tenant_types");
                    res.put(rs.getString("id"), new Resource(rs.getString("id"), rs.getString("service"), rs.getString("description"),
                            rs.getInt("declared_verbs"), rs.getInt("used_verbs"),
                            types == null || types.isBlank() ? Set.of() : new LinkedHashSet<>(Arrays.asList(types.split(","))),
                            rs.getInt("grant_verbs"), rs.getString("grant_attribute")));
                });
        Map<String, Map<String, Integer>> grids = new HashMap<>();
        db.query("SELECT role, resource, verbs FROM rbac_role_grid", rs -> {
            grids.computeIfAbsent(rs.getString("role"), k -> new LinkedHashMap<>()).put(rs.getString("resource"), rs.getInt("verbs"));
        });
        Map<String, List<String>> juniors = new HashMap<>();
        db.query("SELECT senior, junior FROM role_inheritance ORDER BY senior, junior", rs -> {
            juniors.computeIfAbsent(rs.getString("senior"), k -> new ArrayList<>()).add(rs.getString("junior"));
        });
        Map<String, Role> roles = new LinkedHashMap<>();
        db.query("SELECT name, description, kind, approval, max_hours, all_rows_verbs, seeded, owner_tenant, base_roles FROM roles ORDER BY name", rs -> {
            String n = rs.getString("name");
            Integer mh = (Integer) rs.getObject("max_hours");
            roles.put(n, new Role(n, rs.getString("description"), rs.getString("kind"), rs.getString("approval"), mh,
                    rs.getInt("all_rows_verbs"), rs.getBoolean("seeded"),
                    grids.getOrDefault(n, Map.of()), juniors.getOrDefault(n, List.of()),
                    rs.getString("owner_tenant"), arr(rs.getArray("base_roles"))));
        });
        Map<String, Set<String>> expanded = new HashMap<>();
        for (String r : roles.keySet()) expanded.put(r, expand(r, juniors));
        List<SodRule> sod = new ArrayList<>();
        db.query("SELECT id, kind, roles_a, roles_b, cell_a, cell_b, reason FROM sod_rules ORDER BY id", rs -> {
            sod.add(new SodRule(rs.getString("id"), rs.getString("kind"), arr(rs.getArray("roles_a")), arr(rs.getArray("roles_b")),
                    rs.getString("cell_a"), rs.getString("cell_b"), rs.getString("reason")));
        });
        Map<String, String> ex = new HashMap<>();
        db.query("SELECT resource, reason FROM same_row_exemptions", rs -> {
            ex.put(rs.getString("resource"), rs.getString("reason"));
        });
        Set<String> baseline = new LinkedHashSet<>(db.queryForList("SELECT role FROM ceiling_baseline ORDER BY role", String.class));
        snap = new Snapshot(res, roles, expanded, sod, ex, baseline);
    }

    private static List<String> arr(Array a) throws java.sql.SQLException {
        if (a == null) return List.of();
        return Arrays.asList((String[]) a.getArray());
    }

    static Set<String> expand(String role, Map<String, List<String>> juniors) {
        Set<String> out = new LinkedHashSet<>();
        List<String> stack = new ArrayList<>(List.of(role));
        while (!stack.isEmpty()) {
            String r = stack.remove(stack.size() - 1);
            if (out.add(r)) stack.addAll(juniors.getOrDefault(r, List.of()));
        }
        return out;
    }

    /** Refuses a cyclic hierarchy (fapi2-design §9.3). */
    public static void assertAcyclic(Map<String, List<String>> juniors) {
        Map<String, Integer> state = new HashMap<>();
        for (String r : juniors.keySet()) visit(r, juniors, state, new ArrayList<>());
    }

    private static void visit(String r, Map<String, List<String>> juniors, Map<String, Integer> state, List<String> path) {
        Integer s = state.get(r);
        if (s != null && s == 2) return;
        if (s != null && s == 1) {
            path.add(r);
            throw new IllegalArgumentException("role hierarchy has a cycle: " + String.join(" -> ", path));
        }
        state.put(r, 1);
        path.add(r);
        for (String j : juniors.getOrDefault(r, List.of())) visit(j, juniors, state, path);
        path.remove(path.size() - 1);
        state.put(r, 2);
    }

    /** All roles that a role set expands to. */
    public Set<String> expandAll(Snapshot s, Set<String> roles) {
        Set<String> out = new HashSet<>();
        for (String r : roles) out.addAll(s.expanded().getOrDefault(r, Set.of(r)));
        return out;
    }
}
