package trustplane.rbac.core;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import trustplane.rbac.config.RbacConfig;
import trustplane.shared.ApiException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Assignments, four-eyes approvals, delegated grants and access reviews
 * (idp-rbac-cutover-plan.md P2.2, P2.3, P2.6, P2.7, P2.7a; D10).
 *
 * <p>Who may <b>request</b> (D10):
 * <ul>
 *   <li>ORG roles: anyone holding {@code rbac.assignment:create} on that org (its org_admin,
 *       an org_admin of an org above through a subtree assignment, or platform_user_admin);</li>
 *   <li>PLATFORM and SANDBOX roles: a platform_user_admin;</li>
 *   <li>platform_break_glass: only the person themselves.</li>
 * </ul>
 * Who must <b>approve</b> (never the requester, never the grantee): see {@link #eligibleApprovers}.
 * The core's mesh calls ({@code source} = backfill, kyc_go_live, org_onboarding ...) are system
 * writes on decisions already reviewed in the core: they become ACTIVE directly, SoD still applies.
 */
@Service
@lombok.RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class AccessService {

    public static final String SYSTEM = "system:mipay";
    private static final Set<String> SYSTEM_SOURCES = Set.of("backfill", "kyc_go_live", "org_onboarding", "user_lifecycle", "fixture");

    private final JdbcTemplate db;
    private final Policy policy;
    private final Decisions decisions;
    private final RbacStore store;
    private final RbacConfig cfg;

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    record AsgRow(String id, String subject, String role, String tenant, String scope, String status,
                  String requestedBy, Timestamp requestExpires, Timestamp expiresAt) {}

    AsgRow load(String id, boolean forUpdate) {
        List<AsgRow> rows = db.query("SELECT id, subject, role, tenant_id, scope, status, requested_by, request_expires_at, expires_at FROM assignments WHERE id=?"
                        + (forUpdate ? " FOR UPDATE" : ""),
                (rs, i) -> new AsgRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getTimestamp(8), rs.getTimestamp(9)), id);
        if (rows.isEmpty()) throw ApiException.notFound();
        return rows.get(0);
    }

    /** Active roles a subject holds (optionally including pending requests), any tenant. */
    Set<String> rolesOf(String subject, boolean withPending) {
        return new LinkedHashSet<>(db.queryForList("SELECT DISTINCT role FROM assignments WHERE subject=? AND status IN "
                        + (withPending ? "('ACTIVE','PENDING_APPROVAL')" : "('ACTIVE')") + " AND (expires_at IS NULL OR expires_at > now())",
                String.class, subject));
    }

    boolean holdsPlatformRole(String subject, String role) {
        Integer n = db.queryForObject("""
                SELECT count(*) FROM assignments WHERE subject=? AND role=? AND status='ACTIVE' AND scope='all'
                  AND (expires_at IS NULL OR expires_at > now())""", Integer.class, subject, role);
        return n != null && n > 0;
    }

    boolean can(String actor, String tenant, String cell) {
        return decisions.check(new Decisions.Request(actor, tenant == null ? "" : tenant, cell)).allowed();
    }

    Policy.Role role(String name) {
        Policy.Role r = policy.get().roles().get(name);
        if (r == null) throw new ApiException(400, "unknown_role", "role is not defined: " + name);
        return r;
    }

    Map<String, Object> tenantRow(String id) {
        List<Map<String, Object>> rows = db.queryForList("SELECT id, org_type, parent_id, lifecycle, held FROM tenants WHERE id=?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ------------------------------------------------------------------
    // Static separation of duty (fapi2-design §9.4, G.5)
    // ------------------------------------------------------------------

    /** Throws 409 SOD_CONFLICT if adding {@code role} to what the subject holds breaks a STATIC rule. */
    void checkStaticSod(String subject, String role, String tenant, String scope) {
        Policy.Snapshot s = policy.get();
        Set<String> held = rolesOf(subject, true);
        held.remove(role);
        for (Policy.SodRule r : s.sod()) {
            if (!"STATIC".equals(r.kind())) continue;
            boolean conflict = (r.a().contains(role) && r.b().stream().anyMatch(held::contains))
                    || (r.b().contains(role) && r.a().stream().anyMatch(held::contains));
            if (conflict) {
                String other = r.a().contains(role) ? first(r.b(), held) : first(r.a(), held);
                throw new ApiException(409, "SOD_CONFLICT", role + " conflicts with " + other + " (" + r.id() + ": " + r.reason() + ")");
            }
        }
        // same-row maker/checker across the roles a subject holds on the same org (G.5 shorthand)
        Set<String> sameTenant = new HashSet<>(db.queryForList("""
                SELECT DISTINCT role FROM assignments WHERE subject=? AND status IN ('ACTIVE','PENDING_APPROVAL')
                  AND (scope='all' OR tenant_id IS NOT DISTINCT FROM ?)""", String.class, subject, "all".equals(scope) ? null : tenant));
        sameTenant.add(role);
        for (Policy.Resource res : s.resources().values()) {
            if (s.exemptions().containsKey(res.id())) continue;
            int m = 0;
            for (String rn : sameTenant) m |= s.mask(rn, res.id());
            if ((m & Verbs.SUBMIT) != 0 && (m & Verbs.AUTHORIZE) != 0) {
                throw new ApiException(409, "SOD_CONFLICT", "with " + role + " the subject could both submit and authorize " + res.id() + " on the same org");
            }
        }
    }

    private static String first(List<String> candidates, Set<String> held) {
        for (String c : candidates) if (held.contains(c)) return c;
        return candidates.isEmpty() ? "?" : candidates.get(0);
    }

    // ------------------------------------------------------------------
    // Request an assignment
    // ------------------------------------------------------------------

    public record AssignRequest(String subject, String role, String tenant, String scope, Instant expiresAt, String reason) {}

    @Transactional
    public Map<String, Object> request(String actor, AssignRequest req, String source) {
        boolean system = source != null && SYSTEM_SOURCES.contains(source);
        if (req.subject() == null || req.subject().isBlank()) throw ApiException.badRequest("subject required (the core user id)");
        Policy.Role r = role(req.role());
        String tenant = req.tenant() == null || req.tenant().isBlank() ? null : req.tenant();
        String scope = req.scope() == null || req.scope().isBlank() ? ("PLATFORM".equals(r.kind()) ? "all" : "own") : req.scope();
        if (!Set.of("own", "subtree", "all").contains(scope)) throw ApiException.badRequest("scope must be own, subtree or all");
        switch (r.kind()) {
            case "ORG" -> {
                if (tenant == null) throw ApiException.badRequest(r.name() + " is an org role: tenant (org id) required");
                if ("all".equals(scope)) throw ApiException.badRequest("org roles are own or subtree");
            }
            case "PLATFORM" -> {
                if (!"all".equals(scope)) throw ApiException.badRequest(r.name() + " is a platform role: scope all");
            }
            case "SANDBOX" -> {
                if (cfg.isProduction()) throw new ApiException(409, "SOD_CONFLICT", "sandbox roles do not exist in production");
            }
            default -> throw ApiException.badRequest("unknown role kind");
        }
        if (tenant != null) {
            Map<String, Object> t = tenantRow(tenant);
            if (t == null) throw new ApiException(404, "unknown_tenant", "org not known to RBAC: " + tenant);
            if ("PLATFORM".equals(r.kind()) && !"PLATFORM".equals(t.get("org_type"))) {
                throw ApiException.badRequest("platform roles are assigned on the PLATFORM org (or without a tenant)");
            }
            String lc = String.valueOf(t.get("lifecycle"));
            if (Set.of("REJECTED", "BLOCKED", "CLOSED", "ARCHIVED").contains(lc)) {
                throw ApiException.conflict("org is " + lc + ": no new assignments");
            }
        }
        Instant expires = req.expiresAt();
        if (r.maxHours() != null) {
            Instant max = Instant.now().plus(r.maxHours(), ChronoUnit.HOURS);
            if (expires == null) expires = max;
            if (expires.isAfter(max.plusSeconds(60))) throw ApiException.badRequest(r.name() + " lasts at most " + r.maxHours() + " h");
        }
        if (expires != null && !expires.isAfter(Instant.now())) throw ApiException.badRequest("expires_at is in the past");

        // who may request
        if (!system) {
            switch (r.approval()) {
                case "break_glass" -> {
                    if (!actor.equals(req.subject())) throw new ApiException(403, "forbidden", "break-glass is requested by the person themselves");
                }
                case "none", "org_admin" -> {
                    if ("ORG".equals(r.kind()) ? !can(actor, tenant, "rbac.assignment:create")
                            : !holdsPlatformRole(actor, "platform_user_admin")) {
                        throw new ApiException(403, "insufficient_permission", "actor may not assign " + r.name() + " in " + tenant);
                    }
                }
                default -> {
                    if (!holdsPlatformRole(actor, "platform_user_admin")) {
                        throw new ApiException(403, "insufficient_permission", "only a platform_user_admin requests platform roles");
                    }
                }
            }
            if ("subtree".equals(scope) && "ORG".equals(r.kind()) && !can(actor, tenant, "rbac.assignment:create")) {
                throw new ApiException(403, "insufficient_permission", "subtree assignment beyond the actor's reach");
            }
        }
        Integer dup = db.queryForObject("""
                SELECT count(*) FROM assignments WHERE subject=? AND role=? AND tenant_id IS NOT DISTINCT FROM ? AND scope=?
                  AND status IN ('ACTIVE','PENDING_APPROVAL') AND (expires_at IS NULL OR expires_at > now())""",
                Integer.class, req.subject(), r.name(), tenant, scope);
        if (dup != null && dup > 0) throw ApiException.conflict("already assigned or pending");
        checkStaticSod(req.subject(), r.name(), tenant, scope);

        boolean needsApproval = !system && !"none".equals(r.approval());
        String id = RbacStore.newId("asg");
        db.update("""
                INSERT INTO assignments (id, subject, role, tenant_id, scope, status, expires_at, reason, source, requested_by,
                    request_expires_at, approved_by, approved_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                id, req.subject(), r.name(), tenant, scope, needsApproval ? "PENDING_APPROVAL" : "ACTIVE",
                expires == null ? null : Timestamp.from(expires), req.reason() == null ? "" : req.reason(),
                source == null ? "api" : source, actor,
                needsApproval ? Timestamp.from(Instant.now().plus(72, ChronoUnit.HOURS)) : null,
                needsApproval ? null : (system ? SYSTEM : "no_approval_required"), needsApproval ? null : Timestamp.from(Instant.now()));
        if (!needsApproval) store.touchSubjects(List.of(req.subject()));
        store.audit(actor, tenant, needsApproval ? "assignment_requested" : "assignment_create", Map.of(
                "assignment", id, "subject", req.subject(), "role", r.name(), "scope", scope, "source", source == null ? "api" : source));
        return view(id);
    }

    // ------------------------------------------------------------------
    // Approvals (D10)
    // ------------------------------------------------------------------

    /** Approvers for a pending request, by the risk of the role (the D10 table). */
    public Set<String> eligibleApprovers(AsgRow a) {
        Policy.Role r = role(a.role());
        Set<String> exclude = new HashSet<>(List.of(a.requestedBy(), a.subject()));
        Set<String> out = new LinkedHashSet<>();
        switch (r.approval()) {
            case "org_admin" -> {
                // a second org_admin of the same org; if none, of the parent org; if none, platform_user_admin
                for (Decisions.Holder h : decisions.holders("org_admin", a.tenant())) {
                    if ("own".equals(h.scope()) && a.tenant().equals(h.tenant()) && !exclude.contains(h.subject())) out.add(h.subject());
                }
                if (out.isEmpty()) {
                    Map<String, Object> t = tenantRow(a.tenant());
                    String parent = t == null ? null : (String) t.get("parent_id");
                    if (parent != null) {
                        for (Decisions.Holder h : decisions.holders("org_admin", parent)) {
                            if (!"all".equals(h.scope()) && !exclude.contains(h.subject())) out.add(h.subject());
                        }
                    }
                }
                if (out.isEmpty()) out.addAll(platformHolders("platform_user_admin", exclude));
            }
            case "platform", "break_glass" -> out.addAll(platformHolders("platform_user_admin", exclude));
            case "compliance" -> out.addAll(platformHolders("platform_mlro".equals(r.name()) ? "platform_senior_approver" : "platform_mlro", exclude));
            default -> { }
        }
        return out;
    }

    private List<String> platformHolders(String role, Set<String> exclude) {
        List<String> out = new ArrayList<>();
        for (Decisions.Holder h : decisions.holders(role, null)) {
            if ("all".equals(h.scope()) && !exclude.contains(h.subject()) && !out.contains(h.subject())) out.add(h.subject());
        }
        return out;
    }

    @Transactional
    public Map<String, Object> decide(String actor, String id, boolean approve, String reason) {
        AsgRow a = load(id, true);
        if (!"PENDING_APPROVAL".equals(a.status())) throw ApiException.conflict("request is " + a.status());
        if (a.requestExpires() != null && a.requestExpires().toInstant().isBefore(Instant.now())) {
            db.update("UPDATE assignments SET status='EXPIRED', decided_reason='request expired after 72 h' WHERE id=?", id);
            throw ApiException.conflict("request expired after 72 h");
        }
        if (actor.equals(a.requestedBy()) || actor.equals(a.subject())) {
            throw new ApiException(403, "four_eyes", "the requester and the grantee never approve their own request");
        }
        if (!eligibleApprovers(a).contains(actor)) {
            throw new ApiException(403, "four_eyes", "actor is not an eligible approver for " + a.role());
        }
        String decisionId = Decisions.newDecisionId();
        if (!approve) {
            db.update("UPDATE assignments SET status='REJECTED', approved_by=?, approved_at=now(), decided_reason=?, decision_id=? WHERE id=?",
                    actor, reason == null ? "" : reason, decisionId, id);
            store.audit(actor, a.tenant(), "assignment_rejected", Map.of("assignment", id, "decision_id", decisionId));
            return view(id);
        }
        // state may have changed since the request
        Set<String> held = rolesOf(a.subject(), false);
        held.remove(a.role());
        for (Policy.SodRule r : policy.get().sod()) {
            if (!"STATIC".equals(r.kind())) continue;
            if ((r.a().contains(a.role()) && r.b().stream().anyMatch(held::contains)) || (r.b().contains(a.role()) && r.a().stream().anyMatch(held::contains))) {
                throw new ApiException(409, "SOD_CONFLICT", a.role() + " now conflicts (" + r.id() + ": " + r.reason() + ")");
            }
        }
        db.update("UPDATE assignments SET status='ACTIVE', approved_by=?, approved_at=now(), decided_reason=?, decision_id=? WHERE id=?",
                actor, reason == null ? "" : reason, decisionId, id);
        store.touchSubjects(List.of(a.subject()));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("assignment", id);
        details.put("subject", a.subject());
        details.put("role", a.role());
        details.put("decision_id", decisionId);
        store.audit(actor, a.tenant(), "assignment_approved", details);
        if ("platform_break_glass".equals(a.role())) {
            store.audit(actor, null, "break_glass_activated", details);
            log.warn("[rbac] BREAK-GLASS activated for {} (approved by {}, expires {}) - security contact alert", a.subject(), actor, a.expiresAt());
        }
        return view(id);
    }

    /** Pending requests this actor may decide. */
    public List<Map<String, Object>> approvalsFor(String actor) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : db.queryForList("SELECT id FROM assignments WHERE status='PENDING_APPROVAL' AND request_expires_at > now() ORDER BY requested_at", String.class)) {
            AsgRow a = load(id, false);
            if (eligibleApprovers(a).contains(actor)) out.add(view(id));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Revoke (minimum holders, P2.7)
    // ------------------------------------------------------------------

    @Transactional
    public Map<String, Object> revoke(String actor, String id, String reason, boolean system) {
        AsgRow a = load(id, true);
        if (!Set.of("ACTIVE", "PENDING_APPROVAL").contains(a.status())) throw ApiException.conflict("assignment is " + a.status());
        Policy.Role r = role(a.role());
        if (!system) {
            boolean ownRequest = "PENDING_APPROVAL".equals(a.status()) && actor.equals(a.requestedBy());
            boolean allowed = ownRequest || ("ORG".equals(r.kind()) ? can(actor, a.tenant(), "rbac.assignment:administer")
                    : holdsPlatformRole(actor, "platform_user_admin") || holdsPlatformRole(actor, "platform_break_glass"));
            if (!allowed) throw new ApiException(403, "insufficient_permission", "actor may not revoke this assignment");
        }
        if (!system && "ACTIVE".equals(a.status()) && "org_admin".equals(a.role()) && "own".equals(a.scope())) {
            Integer others = db.queryForObject("""
                    SELECT count(*) FROM assignments WHERE role='org_admin' AND scope='own' AND tenant_id=? AND status='ACTIVE'
                      AND (expires_at IS NULL OR expires_at > now()) AND id<>?""", Integer.class, a.tenant(), id);
            if (others == null || others == 0) {
                throw new ApiException(409, "LAST_ADMIN", "an org keeps at least one active org_admin; assign another first");
            }
        }
        db.update("UPDATE assignments SET status='REVOKED', revoked_by=?, revoked_at=now(), revoke_reason=? WHERE id=?",
                system ? SYSTEM : actor, reason == null ? "" : reason, id);
        store.touchSubjects(List.of(a.subject()));
        store.audit(system ? SYSTEM : actor, a.tenant(), "assignment_revoke", Map.of("assignment", id, "subject", a.subject(),
                "role", a.role(), "reason", reason == null ? "" : reason));
        return view(id);
    }

    /** Core: an org was archived / KYC terminated (3.3c): every assignment on it and below goes. */
    @Transactional
    public int revokeForTenant(String tenant, String reason) {
        List<String> subjects = db.queryForList("""
                UPDATE assignments SET status='REVOKED', revoked_by=?, revoked_at=now(), revoke_reason=?
                WHERE status IN ('ACTIVE','PENDING_APPROVAL') AND tenant_id IN (
                    SELECT descendant_id FROM org_closure WHERE ancestor_id=? UNION SELECT ?)
                RETURNING subject""", String.class, SYSTEM, reason, tenant, tenant);
        db.update("""
                UPDATE grants SET status='revoked', revoked_by=?, revoked_at=now() WHERE status='active' AND tenant_id IN (
                    SELECT descendant_id FROM org_closure WHERE ancestor_id=? UNION SELECT ?)""", SYSTEM, tenant, tenant);
        store.touchSubjects(new ArrayList<>(new LinkedHashSet<>(subjects)));
        store.audit(SYSTEM, tenant, "tenant_assignments_revoked", Map.of("count", subjects.size(), "reason", reason));
        return subjects.size();
    }

    // ------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------

    public Map<String, Object> view(String id) {
        List<Map<String, Object>> rows = db.queryForList("""
                SELECT id, subject, role, tenant_id, scope, status, expires_at, reason, source, requested_by, requested_at,
                       request_expires_at, approved_by, approved_at, decided_reason, decision_id, revoked_by, revoked_at, revoke_reason
                FROM assignments WHERE id=?""", id);
        if (rows.isEmpty()) throw ApiException.notFound();
        return shape(rows.get(0));
    }

    static Map<String, Object> shape(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.get("id"));
        m.put("subject", r.get("subject"));
        m.put("role", r.get("role"));
        m.put("tenant", r.get("tenant_id"));
        m.put("scope", r.get("scope"));
        m.put("status", r.get("status"));
        m.put("expires_at", RbacStore.rfc3339(r.get("expires_at")));
        m.put("reason", r.get("reason"));
        m.put("source", r.get("source"));
        m.put("requested_by", r.get("requested_by"));
        m.put("requested_at", RbacStore.rfc3339(r.get("requested_at")));
        m.put("request_expires_at", RbacStore.rfc3339(r.get("request_expires_at")));
        m.put("approved_by", r.get("approved_by"));
        m.put("approved_at", RbacStore.rfc3339(r.get("approved_at")));
        m.put("decision_id", r.get("decision_id"));
        m.put("revoked_by", r.get("revoked_by"));
        m.put("revoked_at", RbacStore.rfc3339(r.get("revoked_at")));
        return m;
    }

    public List<Map<String, Object>> list(String subject, String tenant, String status, String role) {
        StringBuilder q = new StringBuilder("""
                SELECT id, subject, role, tenant_id, scope, status, expires_at, reason, source, requested_by, requested_at,
                       request_expires_at, approved_by, approved_at, decided_reason, decision_id, revoked_by, revoked_at, revoke_reason
                FROM assignments WHERE true""");
        List<Object> args = new ArrayList<>();
        if (subject != null && !subject.isBlank()) { q.append(" AND subject=?"); args.add(subject); }
        if (tenant != null && !tenant.isBlank()) { q.append(" AND tenant_id=?"); args.add(tenant); }
        if (status != null && !status.isBlank()) { q.append(" AND status=?"); args.add(status); }
        if (role != null && !role.isBlank()) { q.append(" AND role=?"); args.add(role); }
        q.append(" ORDER BY requested_at DESC LIMIT 500");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : db.queryForList(q.toString(), args.toArray())) out.add(shape(r));
        return out;
    }

    // ------------------------------------------------------------------
    // Delegated grants (P2.6, fapi2-design §9.6): narrow only, revocation cascades
    // ------------------------------------------------------------------

    public record GrantRequest(String tenant, String permission, List<String> allowedValues, String scope,
                               boolean canDelegate, String parentGrantId, Instant expiresAt) {}

    @Transactional
    public Map<String, Object> grant(String actor, GrantRequest g, boolean system) {
        Verbs.Cell cell = Verbs.parse(g.permission());
        Policy.Resource res = cell == null ? null : policy.get().resources().get(cell.row());
        if (res == null || (res.grantVerbs() & cell.bit()) == 0) throw ApiException.badRequest(g.permission() + " is not an entitlement cell");
        if (g.tenant() == null || tenantRow(g.tenant()) == null) throw new ApiException(404, "unknown_tenant", String.valueOf(g.tenant()));
        String scope = g.scope() == null ? "own" : g.scope();
        if (!Set.of("own", "subtree").contains(scope)) throw ApiException.badRequest("grant scope is own or subtree");
        List<String> values = g.allowedValues() == null ? List.of() : g.allowedValues();
        Instant expires = g.expiresAt();
        if (g.parentGrantId() != null && !g.parentGrantId().isBlank()) {
            List<Map<String, Object>> ps = db.queryForList("SELECT tenant_id, permission, allowed_values, scope, can_delegate, status, expires_at FROM grants WHERE id=?", g.parentGrantId());
            if (ps.isEmpty()) throw new ApiException(404, "unknown_grant", g.parentGrantId());
            Map<String, Object> p = ps.get(0);
            if (!"active".equals(p.get("status")) || !Boolean.TRUE.equals(p.get("can_delegate")) || !"subtree".equals(p.get("scope"))) {
                throw new ApiException(403, "no_matching_grant", "parent grant is not an active delegable subtree grant");
            }
            if (!g.permission().equals(p.get("permission"))) throw ApiException.badRequest("a delegated grant keeps the parent's permission");
            String parentTenant = (String) p.get("tenant_id");
            if (parentTenant.equals(g.tenant()) || db.queryForList("SELECT 1 FROM org_closure WHERE ancestor_id=? AND descendant_id=?",
                    parentTenant, g.tenant()).isEmpty()) {
                throw new ApiException(403, "scope_exceeds_grant", "the grantee must be below the parent grant's org");
            }
            List<String> parentValues = arr(p.get("allowed_values"));
            if (!parentValues.isEmpty() && (values.isEmpty() || !parentValues.containsAll(values))) {
                throw new ApiException(403, "scope_exceeds_grant", "narrow only: " + values + " is not within " + parentValues);
            }
            Timestamp pe = (Timestamp) p.get("expires_at");
            if (pe != null && (expires == null || expires.isAfter(pe.toInstant()))) expires = pe.toInstant();
            if (!system && !can(actor, parentTenant, "org.rights:administer")) {
                throw new ApiException(403, "insufficient_permission", "actor may not delegate the rights of " + parentTenant);
            }
        } else if (!system && !holdsPlatformRole(actor, "platform_user_admin")) {
            throw new ApiException(403, "insufficient_permission", "root grants are made by a platform_user_admin or the core");
        }
        String id = RbacStore.newId("grant");
        db.update("""
                INSERT INTO grants (id, tenant_id, permission, allowed_values, scope, can_delegate, parent_grant_id, expires_at, granted_by, source)
                VALUES (?,?,?,?::text[],?,?,?,?,?,?)""",
                id, g.tenant(), cell.id(), SchemaAndSeed.pgArray(values), scope, g.canDelegate(),
                g.parentGrantId() == null || g.parentGrantId().isBlank() ? null : g.parentGrantId(),
                expires == null ? null : Timestamp.from(expires), system ? SYSTEM : actor, system ? "core" : "api");
        store.touchTenantTree(g.tenant());
        store.audit(system ? SYSTEM : actor, g.tenant(), "grant_create", Map.of("grant", id, "permission", cell.id(), "values", values, "scope", scope));
        return grantView(id);
    }

    @Transactional
    public Map<String, Object> revokeGrant(String actor, String id, boolean system) {
        List<Map<String, Object>> rows = db.queryForList("SELECT tenant_id, parent_grant_id, status FROM grants WHERE id=?", id);
        if (rows.isEmpty()) throw ApiException.notFound();
        Map<String, Object> g = rows.get(0);
        if (!system) {
            String parent = (String) g.get("parent_grant_id");
            boolean ok = holdsPlatformRole(actor, "platform_user_admin");
            if (!ok && parent != null) {
                String parentTenant = db.queryForObject("SELECT tenant_id FROM grants WHERE id=?", String.class, parent);
                ok = can(actor, parentTenant, "org.rights:administer");
            }
            if (!ok) throw new ApiException(403, "insufficient_permission", "actor may not revoke this grant");
        }
        List<String> revoked = db.queryForList("""
                WITH RECURSIVE tree AS (SELECT id FROM grants WHERE id=? UNION ALL SELECT g.id FROM grants g JOIN tree t ON g.parent_grant_id=t.id)
                UPDATE grants SET status='revoked', revoked_by=?, revoked_at=now() WHERE id IN (SELECT id FROM tree) AND status='active'
                RETURNING tenant_id""", String.class, id, system ? SYSTEM : actor);
        for (String t : new LinkedHashSet<>(revoked)) store.touchTenantTree(t);
        store.audit(system ? SYSTEM : actor, (String) g.get("tenant_id"), "grant_revoke", Map.of("grant", id, "cascaded", revoked.size()));
        return Map.of("ok", true, "revoked", revoked.size());
    }

    public Map<String, Object> grantView(String id) {
        List<Map<String, Object>> rows = db.queryForList("""
                SELECT id, tenant_id, permission, allowed_values, scope, can_delegate, parent_grant_id, status, expires_at, granted_by, created_at
                FROM grants WHERE id=?""", id);
        if (rows.isEmpty()) throw ApiException.notFound();
        return grantShape(rows.get(0));
    }

    Map<String, Object> grantShape(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.get("id"));
        m.put("tenant", r.get("tenant_id"));
        m.put("permission", r.get("permission"));
        m.put("allowed_values", arr(r.get("allowed_values")));
        m.put("scope", r.get("scope"));
        m.put("can_delegate", r.get("can_delegate"));
        m.put("parent_grant_id", r.get("parent_grant_id"));
        m.put("status", r.get("status"));
        m.put("expires_at", RbacStore.rfc3339(r.get("expires_at")));
        m.put("granted_by", r.get("granted_by"));
        return m;
    }

    public List<Map<String, Object>> grants(String tenant) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : db.queryForList("""
                SELECT id, tenant_id, permission, allowed_values, scope, can_delegate, parent_grant_id, status, expires_at, granted_by, created_at
                FROM grants WHERE (?='' OR tenant_id=?) ORDER BY created_at DESC LIMIT 500""", tenant, tenant)) out.add(grantShape(r));
        return out;
    }

    static List<String> arr(Object o) {
        try {
            if (o instanceof java.sql.Array a) return List.of((String[]) a.getArray());
        } catch (Exception ignore) { }
        return List.of();
    }

    // ------------------------------------------------------------------
    // Access reviews (fapi2-design §9.7): confirm or remove; unconfirmed expire after 14 days
    // ------------------------------------------------------------------

    @Transactional
    public Map<String, Object> createReview(String actor, String tenant, String label) {
        if (tenant != null && !tenant.isBlank()) {
            if (tenantRow(tenant) == null) throw new ApiException(404, "unknown_tenant", tenant);
            if (!can(actor, tenant, "rbac.review:create")) throw new ApiException(403, "insufficient_permission", "actor may not open a review here");
        } else if (!can(actor, "", "rbac.review:create") || !holdsPlatformRole(actor, "platform_user_admin")) {
            throw new ApiException(403, "insufficient_permission", "platform reviews are opened by a platform_user_admin");
        }
        String id = RbacStore.newId("review");
        db.update("INSERT INTO reviews (id, tenant_id, label, created_by, due_at) VALUES (?,?,?,?,now()+interval '14 days')",
                id, tenant == null || tenant.isBlank() ? null : tenant, label == null ? "" : label, actor);
        int n = tenant == null || tenant.isBlank()
                ? db.update("INSERT INTO review_items (review_id, assignment_id) SELECT ?, id FROM assignments WHERE status='ACTIVE' AND scope='all'", id)
                : db.update("INSERT INTO review_items (review_id, assignment_id) SELECT ?, id FROM assignments WHERE status='ACTIVE' AND tenant_id=?", id, tenant);
        store.audit(actor, tenant, "review_open", Map.of("review", id, "items", n));
        return review(id);
    }

    public Map<String, Object> review(String id) {
        List<Map<String, Object>> rows = db.queryForList("SELECT id, tenant_id, label, created_by, created_at, due_at, status FROM reviews WHERE id=?", id);
        if (rows.isEmpty()) throw ApiException.notFound();
        Map<String, Object> r = rows.get(0);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.get("id"));
        m.put("tenant", r.get("tenant_id"));
        m.put("label", r.get("label"));
        m.put("status", r.get("status"));
        m.put("due_at", RbacStore.rfc3339(r.get("due_at")));
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> i : db.queryForList("""
                SELECT ri.assignment_id, ri.decision, ri.decided_by, a.subject, a.role, a.tenant_id, a.scope FROM review_items ri
                JOIN assignments a ON a.id=ri.assignment_id WHERE ri.review_id=? ORDER BY a.subject, a.role""", id)) {
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("assignment", i.get("assignment_id"));
            it.put("subject", i.get("subject"));
            it.put("role", i.get("role"));
            it.put("tenant", i.get("tenant_id"));
            it.put("scope", i.get("scope"));
            it.put("decision", i.get("decision"));
            it.put("decided_by", i.get("decided_by"));
            items.add(it);
        }
        m.put("items", items);
        return m;
    }

    @Transactional
    public Map<String, Object> decideReviewItem(String actor, String reviewId, String assignmentId, String decision) {
        Map<String, Object> rv = review(reviewId);
        if (!"OPEN".equals(rv.get("status"))) throw ApiException.conflict("review is closed");
        if (!Set.of("CONFIRMED", "REMOVED").contains(decision)) throw ApiException.badRequest("decision is CONFIRMED or REMOVED");
        AsgRow a = load(assignmentId, false);
        if (actor.equals(a.subject())) throw new ApiException(403, "four_eyes", "nobody reviews their own access");
        String tenant = (String) rv.get("tenant");
        boolean ok = tenant == null ? holdsPlatformRole(actor, "platform_user_admin") || holdsPlatformRole(actor, "platform_compliance_officer")
                : can(actor, tenant, "rbac.review:administer");
        if (!ok) throw new ApiException(403, "insufficient_permission", "actor may not decide this review");
        int n = db.update("UPDATE review_items SET decision=?, decided_by=?, decided_at=now() WHERE review_id=? AND assignment_id=? AND decision='PENDING'",
                decision, actor, reviewId, assignmentId);
        if (n == 0) throw ApiException.conflict("item already decided or not in this review");
        if ("REMOVED".equals(decision) && "ACTIVE".equals(a.status())) revoke(actor, assignmentId, "access review " + reviewId, false);
        store.audit(actor, tenant, "review_decision", Map.of("review", reviewId, "assignment", assignmentId, "decision", decision));
        return review(reviewId);
    }

    // ------------------------------------------------------------------
    // Sweeps: expired requests, expired assignments, overdue reviews, old decision log
    // ------------------------------------------------------------------

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    @Transactional
    public void sweep() {
        db.update("UPDATE assignments SET status='EXPIRED', decided_reason='request expired after 72 h' WHERE status='PENDING_APPROVAL' AND request_expires_at < now()");
        List<String> expired = db.queryForList("UPDATE assignments SET status='EXPIRED' WHERE status='ACTIVE' AND expires_at < now() RETURNING subject", String.class);
        if (!expired.isEmpty()) store.touchSubjects(new ArrayList<>(new LinkedHashSet<>(expired)));
        for (String rv : db.queryForList("SELECT id FROM reviews WHERE status='OPEN' AND due_at < now()", String.class)) {
            List<String> ids = db.queryForList("UPDATE review_items SET decision='EXPIRED' WHERE review_id=? AND decision='PENDING' RETURNING assignment_id", String.class, rv);
            List<String> subjects = new ArrayList<>();
            for (String aid : ids) {
                AsgRow a = load(aid, true);
                if ("org_admin".equals(a.role()) && "own".equals(a.scope())) {
                    Integer others = db.queryForObject("""
                            SELECT count(*) FROM assignments WHERE role='org_admin' AND scope='own' AND tenant_id=? AND status='ACTIVE' AND id<>?""",
                            Integer.class, a.tenant(), aid);
                    if (others == null || others == 0) { // minimum holders beats expiry: never lock an org out
                        store.audit("system", a.tenant(), "review_unconfirmed_last_admin_kept", Map.of("review", rv, "assignment", aid));
                        continue;
                    }
                }
                subjects.addAll(db.queryForList("UPDATE assignments SET status='EXPIRED', revoke_reason=?, revoked_at=now() WHERE id=? AND status='ACTIVE' RETURNING subject",
                        String.class, "unconfirmed in access review " + rv, aid));
            }
            db.update("UPDATE reviews SET status='CLOSED' WHERE id=?", rv);
            store.touchSubjects(subjects);
            store.audit("system", null, "review_closed", Map.of("review", rv, "expired", ids.size()));
        }
        db.update("DELETE FROM decision_log WHERE at < now() - interval '90 days'");
    }
}
