package trustplane.rbac.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import trustplane.rbac.config.RbacConfig;
import trustplane.rbac.core.AccessService;
import trustplane.rbac.core.Decisions;
import trustplane.rbac.core.RbacStore;
import trustplane.shared.ApiException;
import trustplane.shared.PublicEndpoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mesh endpoints: the caller is its mTLS certificate (CN), never a gateway context.
 * <ul>
 *   <li>any service registers its own catalogue (CN = X-Mesh-Service);</li>
 *   <li>only the core (CN mipay) syncs the org tree and writes system assignments and grants
 *       (P3: RbacBackfill, UserLifecycleService, KYC go-live / terminate).</li>
 * </ul>
 */
@RestController
@lombok.RequiredArgsConstructor
public class MeshController {

    private final RbacStore store;
    private final AccessService access;
    private final Decisions decisions;
    private final RbacConfig cfg;
    private final JdbcTemplate db;

    private Set<String> core() {
        return Set.of(WebSupport.normalize(cfg.coreCN()));
    }

    public record Catalog(String service, List<Map<String, String>> permissions, List<Map<String, Object>> endpoints) {}

    @PublicEndpoint("mesh: a service registers its own grid cells and endpoints (certificate CN = service)")
    @PostMapping("/rbac/internal/permissions/register")
    public Map<String, Object> register(@RequestBody Catalog c, @RequestHeader(value = "X-Mesh-Service", defaultValue = "") String svc,
                                        HttpServletRequest req) {
        String who = WebSupport.meshCaller(req, svc, null);
        if (c == null || c.service() == null || !WebSupport.normalize(c.service()).equals(who)) {
            throw new ApiException(403, "forbidden", "a service registers only its own catalogue");
        }
        List<RbacStore.EndpointReg> eps = new ArrayList<>();
        if (c.endpoints() != null) {
            for (Map<String, Object> e : c.endpoints()) {
                Object perm = e.get("permission");
                if (perm == null) continue; // public endpoints carry no cell
                eps.add(new RbacStore.EndpointReg(String.valueOf(e.get("method")), String.valueOf(e.get("path")), String.valueOf(perm),
                        e.get("tenantSource") == null ? null : String.valueOf(e.get("tenantSource")),
                        e.get("handler") == null ? null : String.valueOf(e.get("handler"))));
            }
        }
        return store.registerCatalog(c.service(), c.permissions() == null ? List.of() : c.permissions(), eps);
    }

    /** {closure:[{ancestor,descendant,depth}], orgs:[{id,name,type,parent,lifecycle,held,holds[]}]} (tenantMapping accepted too). */
    public record SyncBody(List<Map<String, Object>> closure, List<Map<String, Object>> orgs, List<Map<String, Object>> tenantMapping) {}

    @PublicEndpoint("mesh: the core pushes the org tree with each org's type, lifecycle and holds")
    @PostMapping("/rbac/internal/orgs/sync")
    public Map<String, Object> sync(@RequestBody SyncBody b, @RequestHeader(value = "X-Mesh-Service", defaultValue = "") String svc,
                                    HttpServletRequest req) {
        WebSupport.meshCaller(req, svc, core());
        List<RbacStore.OrgRow> orgs = new ArrayList<>();
        if (b.orgs() != null) {
            for (Map<String, Object> o : b.orgs()) {
                List<String> holds = new ArrayList<>();
                if (o.get("holds") instanceof List<?> l) l.forEach(x -> holds.add(String.valueOf(x)));
                boolean ceilingSent = o.containsKey("role_ceiling");
                orgs.add(new RbacStore.OrgRow(str(o.get("id")), str(o.get("name")), str(o.get("type")), str(o.get("parent")),
                        str(o.get("lifecycle")), Boolean.TRUE.equals(o.get("held")), holds,
                        ceilingSent, strings(o.get("role_ceiling")), strings(o.get("role_delegable"))));
            }
        } else if (b.tenantMapping() != null) { // the pre-P2 payload: ids and names only
            for (Map<String, Object> m : b.tenantMapping()) {
                orgs.add(new RbacStore.OrgRow(str(m.get("tenant")), str(m.get("name")), null, null, "ACTIVE", false, List.of()));
            }
        }
        return store.syncOrgs(b.closure(), orgs);
    }

    public record SystemAssign(String subject, String role, String tenant, String scope, String expires_at, String reason, String source) {}

    @PublicEndpoint("mesh: the core writes assignments decided in the core (backfill, KYC go-live, user lifecycle)")
    @PostMapping("/rbac/internal/assignments")
    public Map<String, Object> assign(@RequestBody SystemAssign b, @RequestHeader(value = "X-Mesh-Service", defaultValue = "") String svc,
                                      HttpServletRequest req) {
        WebSupport.meshCaller(req, svc, core());
        String source = b.source() == null || b.source().isBlank() ? "user_lifecycle" : b.source();
        if (Set.of("api", "fixture").contains(source)) throw ApiException.badRequest("source must name the core process");
        return access.request(AccessService.SYSTEM, new AccessService.AssignRequest(b.subject(), b.role(), b.tenant(), b.scope(),
                WebSupport.instant(b.expires_at()), b.reason()), source);
    }

    @PublicEndpoint("mesh: the core revokes an assignment (user archived or deactivated)")
    @PostMapping("/rbac/internal/assignments/{id}/revoke")
    public Map<String, Object> revoke(@PathVariable String id, @RequestBody(required = false) Map<String, String> b,
                                      @RequestHeader(value = "X-Mesh-Service", defaultValue = "") String svc, HttpServletRequest req) {
        WebSupport.meshCaller(req, svc, core());
        return access.revoke(AccessService.SYSTEM, id, b == null ? "" : b.getOrDefault("reason", ""), true);
    }

    @PublicEndpoint("mesh: the core removes every assignment and grant of an archived / KYC-terminated org and its subtree")
    @PostMapping("/rbac/internal/tenants/{tenant}/revoke-all")
    public Map<String, Object> revokeAll(@PathVariable String tenant, @RequestBody(required = false) Map<String, String> b,
                                         @RequestHeader(value = "X-Mesh-Service", defaultValue = "") String svc, HttpServletRequest req) {
        WebSupport.meshCaller(req, svc, core());
        return Map.of("revoked", access.revokeForTenant(tenant, b == null ? "org archived" : b.getOrDefault("reason", "org archived")));
    }

    @PublicEndpoint("mesh: the core lists a user's assignments (reconcile, 2FA profile)")
    @GetMapping("/rbac/internal/assignments")
    public List<Map<String, Object>> list(@RequestParam String subject, @RequestHeader(value = "X-Mesh-Service", defaultValue = "") String svc,
                                          HttpServletRequest req) {
        WebSupport.meshCaller(req, svc, core());
        return access.list(subject, "", "", "");
    }

    @PublicEndpoint("mesh: the core mirrors DIRECT / CEILING onboarding rights as grants")
    @PostMapping("/rbac/internal/grants")
    public Map<String, Object> grant(@RequestBody GrantsReviewsController.GrantBody b,
                                     @RequestHeader(value = "X-Mesh-Service", defaultValue = "") String svc, HttpServletRequest req) {
        WebSupport.meshCaller(req, svc, core());
        return access.grant(AccessService.SYSTEM, new AccessService.GrantRequest(b.tenant(), b.permission(), b.allowed_values(), b.scope(),
                Boolean.TRUE.equals(b.can_delegate()), b.parent_grant_id(), WebSupport.instant(b.expires_at())), true);
    }

    @PublicEndpoint("mesh: the core revokes a grant")
    @PostMapping("/rbac/internal/grants/{id}/revoke")
    public Map<String, Object> revokeGrant(@PathVariable String id, @RequestHeader(value = "X-Mesh-Service", defaultValue = "") String svc,
                                           HttpServletRequest req) {
        WebSupport.meshCaller(req, svc, core());
        return access.revokeGrant(AccessService.SYSTEM, id, true);
    }

    public record CheckBody(String subject, String tenant, String operation, Map<String, String> attributes) {}

    /**
     * The core's @Perm decision (cut-over P6 / OA8): the same answer as gRPC Check, over the
     * mesh the core already speaks. tenant "" = in any tenant.
     */
    @PublicEndpoint("mesh: the core asks for a decision on one grid cell (@Perm enforcement)")
    @PostMapping("/rbac/internal/check")
    public Map<String, Object> check(@RequestBody CheckBody b, @RequestHeader(value = "X-Mesh-Service", defaultValue = "") String svc,
                                     HttpServletRequest req) {
        WebSupport.meshCaller(req, svc, core());
        Decisions.Decision d = decisions.check(new Decisions.Request(b.subject(), b.tenant() == null ? "" : b.tenant(),
                b.operation(), List.of(), b.attributes() == null ? Map.of() : b.attributes()));
        return Map.of("allowed", d.allowed(), "reason", d.reason(), "decision_id", d.decisionId(),
                "tenant_version", d.tenantVersion(), "matched_roles", d.matchedRoles());
    }

    @PublicEndpoint("liveness probe (mTLS at the listener)")
    @GetMapping("/healthz")
    public ResponseEntity<String> health() {
        try {
            db.queryForObject("SELECT 1", Integer.class);
            return ResponseEntity.ok("ok");
        } catch (Exception e) {
            return ResponseEntity.status(503).body("db down");
        }
    }

    private static List<String> strings(Object o) {
        if (!(o instanceof List<?> l)) return null;
        List<String> out = new ArrayList<>();
        l.forEach(x -> out.add(String.valueOf(x)));
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
