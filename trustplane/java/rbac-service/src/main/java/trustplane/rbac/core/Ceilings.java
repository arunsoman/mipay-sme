package trustplane.rbac.core;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Role ceilings per org (docs/plans/extended-rbac.md X1-X4, X6):
 * <ul>
 *   <li>an explicit ceiling on an org: {@code roles} it may use, {@code delegable} it may hand down;</li>
 *   <li>no explicit ceiling: the nearest ancestor's {@code delegable} set, as both sets;</li>
 *   <li>none anywhere above: unrestricted (every ORG role);</li>
 *   <li>the baseline roles ({@code ceiling_baseline} in the seed) are always inside.</li>
 * </ul>
 * The core is the authority; {@link #clampAll()} re-applies "child within parent" after every sync.
 */
@Service
@lombok.RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class Ceilings {

    /**
     * {@code roles} / {@code delegable} null = unrestricted. {@code from} = the org whose explicit
     * ceiling applies (the org itself or an ancestor), null when unrestricted.
     */
    public record Effective(String tenant, boolean explicit, String from, Set<String> roles, Set<String> delegable) {
        public boolean allows(String role) {
            return roles == null || roles.contains(role);
        }

        public boolean mayDelegate(String role) {
            return delegable == null || delegable.contains(role);
        }

        public Map<String, Object> view(Policy.Snapshot s) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tenant", tenant);
            m.put("explicit", explicit);
            m.put("inherited_from", explicit ? null : from);
            m.put("unrestricted", roles == null);
            m.put("roles", new ArrayList<>(roles == null ? s.globalOrgRoles() : new TreeSet<>(roles)));
            m.put("delegable", new ArrayList<>(delegable == null ? s.globalOrgRoles() : new TreeSet<>(delegable)));
            m.put("baseline", new ArrayList<>(s.baseline()));
            return m;
        }
    }

    private final JdbcTemplate db;
    private final Policy policy;

    /** The effective ceiling of a tenant (an unknown tenant is unrestricted: Check refuses it anyway). */
    public Effective of(String tenant) {
        Set<String> baseline = policy.get().baseline();
        List<Map<String, Object>> chain = new ArrayList<>(db.queryForList("""
                SELECT t.id, t.role_ceiling, t.role_delegable, c.depth FROM org_closure c JOIN tenants t ON t.id=c.ancestor_id
                WHERE c.descendant_id=? ORDER BY c.depth""", tenant));
        if (chain.stream().noneMatch(r -> tenant.equals(r.get("id")))) {
            List<Map<String, Object>> self = db.queryForList("SELECT id, role_ceiling, role_delegable, 0 AS depth FROM tenants WHERE id=?", tenant);
            chain.addAll(0, self);
        }
        for (Map<String, Object> r : chain) {
            Set<String> roles = set(r.get("role_ceiling"));
            if (roles == null) continue;
            Set<String> delegable = set(r.get("role_delegable"));
            if (delegable == null) delegable = new LinkedHashSet<>();
            boolean self = tenant.equals(r.get("id"));
            Set<String> effRoles = new LinkedHashSet<>(self ? roles : delegable);
            Set<String> effDelegable = new LinkedHashSet<>(delegable);
            effDelegable.retainAll(self ? roles : delegable);
            effRoles.addAll(baseline);
            return new Effective(tenant, self, (String) r.get("id"), effRoles, effDelegable);
        }
        return new Effective(tenant, false, null, null, null);
    }

    /** Effective ceilings for several tenants (one decision touches a handful). */
    public Map<String, Effective> of(Iterable<String> tenants) {
        Map<String, Effective> out = new HashMap<>();
        for (String t : tenants) if (t != null) out.computeIfAbsent(t, this::of);
        return out;
    }

    /**
     * Child within parent (X4), applied top-down after every sync: an explicit ceiling is
     * clamped to the parent's effective delegable set (plus the baseline), and delegable to
     * roles. Returns the tenants that changed.
     */
    public List<String> clampAll() {
        Set<String> baseline = policy.get().baseline();
        List<String> changed = new ArrayList<>();
        List<Map<String, Object>> rows = db.queryForList("""
                SELECT t.id, t.parent_id, t.role_ceiling, t.role_delegable,
                       COALESCE((SELECT max(depth) FROM org_closure c WHERE c.descendant_id=t.id), 0) AS d
                FROM tenants t WHERE t.role_ceiling IS NOT NULL ORDER BY d""");
        for (Map<String, Object> r : rows) {
            String id = (String) r.get("id");
            Set<String> roles = set(r.get("role_ceiling"));
            Set<String> delegable = set(r.get("role_delegable"));
            if (delegable == null) delegable = new LinkedHashSet<>();
            Set<String> newRoles = new LinkedHashSet<>(roles);
            String parent = parentOf(id, (String) r.get("parent_id"));
            if (parent != null) {
                Effective p = of(parent);
                if (p.delegable() != null) newRoles.removeIf(x -> !p.delegable().contains(x) && !baseline.contains(x));
            }
            Set<String> newDelegable = new LinkedHashSet<>(delegable);
            newDelegable.retainAll(newRoles);
            if (!newRoles.equals(roles) || !newDelegable.equals(delegable)) {
                db.update("UPDATE tenants SET role_ceiling=?::text[], role_delegable=?::text[] WHERE id=?",
                        SchemaAndSeed.pgArray(new ArrayList<>(newRoles)), SchemaAndSeed.pgArray(new ArrayList<>(newDelegable)), id);
                log.warn("[rbac] role ceiling of {} clamped to its parent: roles {} -> {}, delegable {} -> {}", id, roles, newRoles, delegable, newDelegable);
                changed.add(id);
            }
        }
        return changed;
    }

    private String parentOf(String id, String parentId) {
        if (parentId != null && !parentId.isBlank()) return parentId;
        List<String> p = db.queryForList("SELECT ancestor_id FROM org_closure WHERE descendant_id=? AND depth=1", String.class, id);
        return p.isEmpty() ? null : p.get(0);
    }

    static Set<String> set(Object o) {
        if (o == null) return null;
        try {
            if (o instanceof Array a) return new LinkedHashSet<>(Arrays.asList((String[]) a.getArray()));
            if (o instanceof String[] a) return new LinkedHashSet<>(Arrays.asList(a));
        } catch (Exception ignore) { }
        return null;
    }
}
