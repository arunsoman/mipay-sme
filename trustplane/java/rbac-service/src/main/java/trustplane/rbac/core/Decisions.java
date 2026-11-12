package trustplane.rbac.core;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * The policy decision point (fapi2-design.md B.2 / G.4):
 *
 * <pre>
 * Check(subject, tenant, "row:verb", active_roles, attributes)
 *  1. the cell must exist (row registered, verb a grid column)
 *  2. tenant chain from the org closure; unknown tenant -> DENY
 *  3. the row must apply to the tenant's org type (merchant rows only in merchant orgs)
 *  4. candidate assignments: ACTIVE, not expired, and own(tenant) | subtree(ancestor) | all;
 *     tenant "" means "in any tenant" (CheckAny, used by the gateway for routes without one)
 *  5. tenant holds (P2.6a): a held or closed org, or one below a held org, refuses every
 *     assignment except platform ones (scope all or a PLATFORM-org assignment; U6)
 *  6. dynamic SoD: a subject holding both sides of a DYNAMIC set uses them only when
 *     activated in the session, and never both at once
 *  7. mask = OR of the grids of the usable roles and their junior roles; allowed = mask & bit
 *  8. entitlement cells (org.onboarding:create) also need an active grant on the tenant
 *     whose allowed values contain the attribute (child_type=MERCHANT)
 *  9. every decision gets an id and is logged (asynchronously, bounded)
 * 10. role ceilings (extended-rbac X7, X10): an org assignment whose role is outside its
 *     org's ceiling is ignored; an org-defined role is bounded by its base roles that are
 *     still inside the owner's ceiling. Platform assignments are exempt (U6)
 * </pre>
 */
@Service
@lombok.RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class Decisions {

    public record Request(String subject, String tenant, String operation, Collection<String> activeRoles,
                          Map<String, String> attributes) {
        public Request(String subject, String tenant, String operation) {
            this(subject, tenant, operation, List.of(), Map.of());
        }
    }

    public record Decision(boolean allowed, String reason, long tenantVersion, String decisionId, List<String> matchedRoles) {}

    public record Holder(String subject, String assignmentId, String tenant, String scope, String role) {}

    /**
     * {@code gatedTo}: while awaiting DSD activation, only these (junior) roles count.
     * {@code bounds}: for an org-defined role, the base roles inside the ceiling; its mask
     * never exceeds theirs (X10). null = not bounded.
     */
    record Asg(String id, String role, String tenant, String scope, String tenantType, Set<String> gatedTo, Set<String> bounds) {
        Asg(String id, String role, String tenant, String scope, String tenantType) {
            this(id, role, tenant, scope, tenantType, null, null);
        }

        Asg gated(Set<String> to) {
            return new Asg(id, role, tenant, scope, tenantType, to, bounds);
        }

        Asg bounded(Set<String> b) {
            return new Asg(id, role, tenant, scope, tenantType, gatedTo, b);
        }

        boolean platform() {
            return "all".equals(scope) || "PLATFORM".equals(tenantType);
        }

        /** Mask on a row: the role (and its juniors), or only the juniors while it awaits activation. */
        int mask(Policy.Snapshot s, String row) {
            int m = 0;
            if (gatedTo == null) {
                m = s.mask(role, row);
            } else {
                for (String r : gatedTo) {
                    Policy.Role rr = s.roles().get(r);
                    if (rr != null) m |= rr.ownMask(row);
                }
            }
            if (bounds != null) {
                int b = 0;
                for (String x : bounds) b |= s.mask(x, row);
                m &= b;
            }
            return m;
        }
    }

    record TenantInfo(String id, String orgType, long version, List<String> chain, boolean blocked, String blockReason) {}

    private static final SecureRandom RNG = new SecureRandom();
    private static final String TERMINAL = "('REJECTED','BLOCKED','CLOSED','ARCHIVED')";

    private final JdbcTemplate db;
    private final Policy policy;
    private final Ceilings ceilings;
    private final BlockingQueue<Object[]> logQueue = new ArrayBlockingQueue<>(20_000);

    public static String newDecisionId() {
        byte[] b = new byte[10];
        RNG.nextBytes(b);
        return "d-" + java.util.HexFormat.of().formatHex(b);
    }

    // ------------------------------------------------------------------

    TenantInfo tenant(String id) {
        List<Map<String, Object>> self = db.queryForList(
                "SELECT id, org_type, version, held, lifecycle, hold_reasons FROM tenants WHERE id=?", id);
        if (self.isEmpty()) return null;
        Map<String, Object> t = self.get(0);
        List<String> chain = new ArrayList<>();
        boolean blocked = false;
        String why = "";
        for (Map<String, Object> a : db.queryForList("""
                SELECT t.id, t.held, t.lifecycle, t.hold_reasons FROM org_closure c JOIN tenants t ON t.id=c.ancestor_id
                WHERE c.descendant_id=? ORDER BY c.depth""", id)) {
            chain.add((String) a.get("id"));
            if (!blocked && isBlocked(a)) {
                blocked = true;
                why = blockReason(a);
            }
        }
        if (!chain.contains(id)) {
            chain.add(0, id);
            if (!blocked && isBlocked(t)) {
                blocked = true;
                why = blockReason(t);
            }
        }
        return new TenantInfo(id, (String) t.get("org_type"), ((Number) t.get("version")).longValue(), chain, blocked, why);
    }

    private static boolean isBlocked(Map<String, Object> t) {
        String lc = String.valueOf(t.get("lifecycle"));
        return Boolean.TRUE.equals(t.get("held")) || TERMINAL.contains("'" + lc + "'");
    }

    private static String blockReason(Map<String, Object> t) {
        String reasons = String.valueOf(t.getOrDefault("hold_reasons", ""));
        return "tenant_held:" + t.get("id") + (reasons.isBlank() ? ":" + t.get("lifecycle") : ":" + reasons);
    }

    List<Asg> assignmentsOf(String subject) {
        List<Asg> out = new ArrayList<>();
        db.query("""
                SELECT a.id, a.role, a.tenant_id, a.scope, t.org_type FROM assignments a LEFT JOIN tenants t ON t.id=a.tenant_id
                WHERE a.subject=? AND a.status='ACTIVE' AND (a.expires_at IS NULL OR a.expires_at > now())
                ORDER BY a.requested_at""", rs -> {
            out.add(new Asg(rs.getString("id"), rs.getString("role"), rs.getString("tenant_id"), rs.getString("scope"), rs.getString("org_type")));
        }, subject);
        return out;
    }

    /** Tenants (among ids) that are held/closed themselves or below a held/closed org. */
    Set<String> blockedAmong(Collection<String> ids) {
        Set<String> out = new HashSet<>();
        if (ids.isEmpty()) return out;
        String arr = SchemaAndSeed.pgArray(new ArrayList<>(ids));
        out.addAll(db.queryForList("""
                SELECT DISTINCT c.descendant_id FROM org_closure c JOIN tenants t ON t.id=c.ancestor_id
                WHERE c.descendant_id = ANY(?::text[]) AND (t.held OR t.lifecycle IN """ + TERMINAL + ")", String.class, arr));
        out.addAll(db.queryForList("SELECT id FROM tenants WHERE id = ANY(?::text[]) AND (held OR lifecycle IN " + TERMINAL + ")",
                String.class, arr));
        return out;
    }

    /** Assignments covering the tenant ("" = any tenant), after holds. Each entry: assignment. */
    List<Asg> covering(String subject, TenantInfo t, List<String> skipped) {
        List<Asg> all = assignmentsOf(subject);
        List<Asg> out = new ArrayList<>();
        if (t == null) {
            Set<String> tenants = new HashSet<>();
            for (Asg a : all) if (a.tenant() != null) tenants.add(a.tenant());
            Set<String> blocked = blockedAmong(tenants);
            for (Asg a : all) {
                if (!a.platform() && a.tenant() != null && blocked.contains(a.tenant())) {
                    skipped.add("tenant_held:" + a.tenant());
                    continue;
                }
                out.add(a);
            }
            return withinCeilings(out, skipped);
        }
        for (Asg a : all) {
            boolean covers = switch (a.scope()) {
                case "all" -> true;
                case "own" -> t.id().equals(a.tenant());
                case "subtree" -> a.tenant() != null && t.chain().contains(a.tenant());
                default -> false;
            };
            if (!covers) continue;
            if (t.blocked() && !a.platform()) {
                skipped.add(t.blockReason());
                continue;
            }
            out.add(a);
        }
        return withinCeilings(out, skipped);
    }

    /**
     * Role ceilings (X7, X10): drops org assignments whose role is outside the ceiling of the
     * org it is assigned on, and bounds org-defined roles by their base roles inside it.
     */
    List<Asg> withinCeilings(List<Asg> cands, List<String> skipped) {
        Policy.Snapshot s = policy.get();
        Set<String> tenants = new HashSet<>();
        for (Asg a : cands) if (!a.platform() && a.tenant() != null) tenants.add(a.tenant());
        if (tenants.isEmpty()) return cands;
        Map<String, Ceilings.Effective> eff = ceilings.of(tenants);
        List<Asg> out = new ArrayList<>();
        for (Asg a : cands) {
            Policy.Role r = s.roles().get(a.role());
            if (a.platform() || a.tenant() == null || r == null || !"ORG".equals(r.kind())) {
                out.add(a);
                continue;
            }
            Ceilings.Effective c = eff.get(a.tenant());
            if (r.orgDefined()) {
                if (!r.ownerTenant().equals(a.tenant())) {
                    skipped.add("role_not_owned:" + r.name() + "@" + a.tenant());
                    continue;
                }
                Set<String> bases = new LinkedHashSet<>();
                for (String b : r.baseRoles()) if (c.allows(b)) bases.add(b);
                if (bases.isEmpty()) {
                    skipped.add("role_outside_ceiling:" + r.name()); // the name already carries "@<org>"
                    continue;
                }
                out.add(a.bounded(bases));
            } else if (c.allows(r.name())) {
                out.add(a);
            } else {
                skipped.add("role_outside_ceiling:" + r.name() + "@" + a.tenant());
            }
        }
        return out;
    }

    /** Applies dynamic SoD. Returns usable assignments or null with the conflict in reason[0]. */
    List<Asg> activate(Policy.Snapshot s, List<Asg> cands, Collection<String> activeRoles, String[] reason) {
        Set<String> held = new HashSet<>();
        for (Asg a : cands) held.addAll(s.sodIdentity(a.role())); // an org-defined role counts as its base roles (X9)
        Set<String> active = activeRoles == null ? Set.of() : new HashSet<>(activeRoles);
        Set<String> gated = new HashSet<>();
        for (Policy.SodRule r : s.sod()) {
            if (!"DYNAMIC".equals(r.kind())) continue;
            boolean holdsA = r.a().stream().anyMatch(held::contains), holdsB = r.b().stream().anyMatch(held::contains);
            if (!(holdsA && holdsB)) continue; // only one side held: no activation needed
            boolean actA = r.a().stream().anyMatch(active::contains), actB = r.b().stream().anyMatch(active::contains);
            if (actA && actB) {
                reason[0] = "dsd_conflict:" + r.id();
                return null;
            }
            for (String x : r.a()) if (!actA) gated.add(x);
            for (String x : r.b()) if (!actB) gated.add(x);
        }
        // a gated role is not active, but what it inherits from roles outside the set stays usable
        // (a reviewer holding both stages keeps the auditor's reads while choosing a stage)
        List<Asg> out = new ArrayList<>();
        for (Asg a : cands) {
            if (s.sodIdentity(a.role()).stream().noneMatch(gated::contains)) {
                out.add(a);
                continue;
            }
            Set<String> juniors = new LinkedHashSet<>(s.expanded().getOrDefault(a.role(), Set.of()));
            juniors.remove(a.role());
            juniors.removeAll(gated);
            out.add(a.gated(juniors));
            if (reason[0] == null) reason[0] = "activation_required";
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Check
    // ------------------------------------------------------------------

    public Decision check(Request q) {
        Decision d = decide(q);
        record(d, q);
        return d;
    }

    Decision deny(String reason) {
        return new Decision(false, reason, 0, newDecisionId(), List.of());
    }

    Decision decide(Request q) {
        Policy.Snapshot s = policy.get();
        Verbs.Cell cell = Verbs.parse(q.operation());
        if (cell == null) return deny("bad_operation");
        Policy.Resource res = s.resources().get(cell.row());
        if (res == null) return deny("unknown_row:" + cell.row());
        if (q.subject() == null || q.subject().isBlank()) return deny("no_subject");
        String tenantId = q.tenant() == null ? "" : q.tenant();
        TenantInfo t = null;
        if (!tenantId.isEmpty()) {
            t = tenant(tenantId);
            if (t == null) return deny("unknown_tenant:" + tenantId);
            if (!res.tenantTypes().isEmpty() && (t.orgType() == null || !res.tenantTypes().contains(t.orgType()))) {
                return new Decision(false, "row_not_applicable:" + cell.row() + " in " + t.orgType(), t.version(), newDecisionId(), List.of());
            }
        }
        long version = t == null ? 0 : t.version();
        List<String> skipped = new ArrayList<>();
        List<Asg> cands = covering(q.subject(), t, skipped);
        String[] why = new String[1];
        List<Asg> usable = activate(s, cands, q.activeRoles(), why);
        if (usable == null) return new Decision(false, why[0], version, newDecisionId(), List.of());
        for (Asg a : usable) {
            if ((a.mask(s, cell.row()) & cell.bit()) == 0) continue;
            // a role assigned on a merchant org never acts in another org type through "any tenant"
            if (t == null && !res.tenantTypes().isEmpty() && !"all".equals(a.scope())
                    && "own".equals(a.scope()) && a.tenantType() != null && !res.tenantTypes().contains(a.tenantType())) continue;
            if ((res.grantVerbs() & cell.bit()) != 0) {
                String ent = entitlement(t, cell, res, q.attributes());
                if (ent != null) return new Decision(false, ent, version, newDecisionId(), List.of(a.role()));
            }
            return new Decision(true, "granted via " + a.role() + (a.gatedTo() == null ? "" : " (inherited part; not activated)")
                    + "@" + a.scope() + (a.tenant() == null ? "" : "(" + a.tenant() + ")"),
                    version, newDecisionId(), List.of(a.role()));
        }
        String reason = !skipped.isEmpty() && (cands.isEmpty() || usable.isEmpty()) ? skipped.get(0)
                : why[0] != null ? why[0]
                : cands.isEmpty() ? "no_assignment" : "no_permission";
        return new Decision(false, reason, version, newDecisionId(), List.of());
    }

    /** null when an active grant covers the entitlement, else the deny reason. */
    String entitlement(TenantInfo t, Verbs.Cell cell, Policy.Resource res, Map<String, String> attrs) {
        if (t == null) return "entitlement_needs_tenant";
        String attr = res.grantAttribute();
        String value = attr == null || attrs == null ? null : attrs.get(attr);
        if (attr != null && (value == null || value.isBlank())) return "attribute_required:" + attr;
        List<String> ids = db.queryForList("""
                SELECT id FROM grants WHERE status='active' AND (expires_at IS NULL OR expires_at > now()) AND permission=?
                  AND ((scope='own' AND tenant_id=?) OR (scope='subtree' AND tenant_id = ANY(?::text[])))
                  AND (cardinality(allowed_values)=0 OR ? = ANY(allowed_values))""",
                String.class, cell.id(), t.id(), SchemaAndSeed.pgArray(t.chain()), value == null ? "" : value);
        return ids.isEmpty() ? "no_grant:" + cell.id() + (value == null ? "" : "[" + attr + "=" + value + "]") : null;
    }

    /** Holds the cell through a platform-wide (scope all) assignment: cross-org admin reads. */
    public boolean checkPlatform(String subject, String operation) {
        Policy.Snapshot s = policy.get();
        Verbs.Cell cell = Verbs.parse(operation);
        if (cell == null || subject == null) return false;
        for (Asg a : assignmentsOf(subject)) {
            if ("all".equals(a.scope()) && (s.mask(a.role(), cell.row()) & cell.bit()) != 0) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Other questions
    // ------------------------------------------------------------------

    public record Permissions(long tenantVersion, List<String> operations, Map<String, Integer> masks) {}

    /** Every cell the subject may use in the tenant ("" = anywhere): UI hints and the gateway context. */
    public Permissions listPermissions(String subject, String tenantId, Collection<String> activeRoles) {
        Policy.Snapshot s = policy.get();
        TenantInfo t = tenantId == null || tenantId.isBlank() ? null : tenant(tenantId);
        if (tenantId != null && !tenantId.isBlank() && t == null) return new Permissions(0, List.of(), Map.of());
        List<Asg> usable = activate(s, covering(subject, t, new ArrayList<>()), activeRoles, new String[1]);
        Map<String, Integer> masks = new TreeMap<>();
        if (usable != null) {
            for (Policy.Resource r : s.resources().values()) {
                if (t != null && !r.tenantTypes().isEmpty() && (t.orgType() == null || !r.tenantTypes().contains(t.orgType()))) continue;
                int m = 0;
                for (Asg a : usable) m |= a.mask(s, r.id());
                m &= r.used();
                if (m != 0) masks.put(r.id(), m);
            }
        }
        List<String> ops = new ArrayList<>();
        masks.forEach((row, m) -> Verbs.names(m).forEach(v -> ops.add(row + ":" + v)));
        return new Permissions(t == null ? 0 : t.version(), ops, masks);
    }

    /** Who holds a role in a tenant (own there, subtree above it, or platform-wide). */
    public List<Holder> holders(String role, String tenantId) {
        List<Holder> out = new ArrayList<>();
        TenantInfo t = tenantId == null || tenantId.isBlank() ? null : tenant(tenantId);
        db.query("""
                SELECT id, subject, tenant_id, scope FROM assignments
                WHERE role=? AND status='ACTIVE' AND (expires_at IS NULL OR expires_at > now()) ORDER BY requested_at""", rs -> {
            String scope = rs.getString("scope"), at = rs.getString("tenant_id");
            boolean covers = t == null || "all".equals(scope) || ("own".equals(scope) && t.id().equals(at))
                    || ("subtree".equals(scope) && at != null && t.chain().contains(at));
            if (covers) out.add(new Holder(rs.getString("subject"), rs.getString("id"), at, scope, role));
        }, role);
        return out;
    }

    /** Session activation (DSD): the roles must be held and must not form both sides of a DYNAMIC set. */
    public String validateActivation(String subject, Collection<String> roles) {
        Set<String> held = new HashSet<>();
        for (Asg a : assignmentsOf(subject)) held.addAll(policy.get().sodIdentity(a.role()));
        for (String r : roles) if (!held.contains(r)) return "not_held:" + r;
        for (Policy.SodRule r : policy.get().sod()) {
            if (!"DYNAMIC".equals(r.kind())) continue;
            if (r.a().stream().anyMatch(roles::contains) && r.b().stream().anyMatch(roles::contains)) return "dsd_conflict:" + r.id();
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Decision log: bounded queue, flushed in batches (a full queue drops the oldest log line,
    // never the decision)
    // ------------------------------------------------------------------

    void record(Decision d, Request q) {
        Object[] row = {d.decisionId(), q.subject() == null ? "" : q.subject(), q.tenant() == null ? "" : q.tenant(),
                q.operation() == null ? "" : q.operation(), d.allowed(), d.reason(), String.join(",", d.matchedRoles())};
        if (!logQueue.offer(row)) {
            logQueue.poll();
            logQueue.offer(row);
        }
    }

    @Scheduled(fixedDelay = 500)
    public void flush() {
        List<Object[]> batch = new ArrayList<>();
        logQueue.drainTo(batch, 2000);
        if (batch.isEmpty()) return;
        try {
            db.batchUpdate("INSERT INTO decision_log (decision_id, subject, tenant, operation, allowed, reason, matched) VALUES (?,?,?,?,?,?,?) ON CONFLICT DO NOTHING", batch);
        } catch (Exception e) {
            log.warn("[rbac] decision log flush failed ({} rows): {}", batch.size(), e.getMessage());
        }
    }

    public Map<String, Object> explain(String decisionId) {
        flush();
        List<Map<String, Object>> rows = db.queryForList("SELECT decision_id, at, subject, tenant, operation, allowed, reason, matched FROM decision_log WHERE decision_id=?", decisionId);
        return rows.isEmpty() ? null : new LinkedHashMap<>(rows.get(0));
    }

    static <T> Set<T> setOf(Collection<T> c) {
        return new LinkedHashSet<>(c);
    }

    static Map<String, Integer> copy(Map<String, Integer> m) {
        return new HashMap<>(m);
    }
}
