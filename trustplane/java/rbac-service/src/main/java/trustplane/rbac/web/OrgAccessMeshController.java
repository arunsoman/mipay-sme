package trustplane.rbac.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import trustplane.rbac.config.RbacConfig;
import trustplane.rbac.core.AccessService;
import trustplane.rbac.core.Decisions;
import trustplane.rbac.core.RbacStore;
import trustplane.shared.ApiException;
import trustplane.shared.PublicEndpoint;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mesh endpoints the core uses for an org's own access screens (docs/plans/extended-rbac.md
 * ER3): its roles and ceiling, its org-defined roles, and role assignments of its users.
 *
 * <p>Writes are made <b>on behalf of</b> a named actor (the core user id). RBAC checks the
 * actor's own cell unless the core says it has authorized the actor itself
 * ({@code core_authorized}, used until cut-over P6 makes RBAC decide for the core). Either
 * way the write is the actor's: ceilings, SoD and four-eyes apply, and audit names the actor.
 * Only the core's certificate may call these.
 */
@RestController
@lombok.RequiredArgsConstructor
@RequestMapping("/rbac/internal/tenants/{tenant}")
public class OrgAccessMeshController {

    static final String SOURCE = "core_portal";
    private static final java.util.Set<String> SYSTEM_SOURCES =
            java.util.Set.copyOf(trustplane.rbac.core.AccessService.SYSTEM_SOURCES);

    private final RbacStore store;
    private final AccessService access;
    private final Decisions decisions;
    private final RbacConfig cfg;

    public record OnBehalf(String actor, Boolean core_authorized) {}

    public record RoleBody(String actor, Boolean core_authorized, String name, String description,
                           List<String> base_roles, Map<String, List<String>> grid, List<String> permissions) {}

    public record AssignBody(String actor, Boolean core_authorized, String subject, String role, String scope,
                             String expires_at, String reason, String source) {}

    public record DecideBody(String actor, Boolean core_authorized, String reason, String source) {}

    public record ApproveBody(String actor, Boolean approve, String reason) {}

    private void core(HttpServletRequest req, String svc) {
        WebSupport.meshCaller(req, svc, Set.of(WebSupport.normalize(cfg.coreCN())));
    }

    private static String actorOf(String actor) {
        if (actor == null || actor.isBlank()) throw ApiException.badRequest("actor (the core user id) required");
        return actor;
    }

    /** The actor holds the cell in the tenant, unless the core authorized the actor itself. */
    private String authorize(String actor, Boolean coreAuthorized, String tenant, String cell) {
        String a = actorOf(actor);
        if (WebSupport.coreAuthorized(coreAuthorized, cfg)) return a;
        var d = decisions.check(new Decisions.Request(a, tenant, cell));
        if (!d.allowed()) {
            throw new ApiException(403, "insufficient_permission", "actor lacks " + cell + " in " + tenant + " (" + d.reason() + ", " + d.decisionId() + ")");
        }
        return a;
    }

    private static void ownedBy(Map<String, Object> role, String tenant) {
        if (!tenant.equals(role.get("owner_tenant"))) throw ApiException.notFound();
    }

    // ---- roles and ceiling -------------------------------------------------

    @PublicEndpoint("mesh: the core reads an org's ceiling, the roles it may use and its own roles")
    @GetMapping("/roles")
    public Map<String, Object> roles(@PathVariable String tenant, @RequestHeader(value = "X-Mesh-Service", defaultValue = "") String svc,
                                     HttpServletRequest req) {
        core(req, svc);
        return store.rolesFor(tenant);
    }

    @PublicEndpoint("mesh: the core creates an org-defined role on behalf of an org admin")
    @PostMapping("/roles")
    public Map<String, Object> createRole(@PathVariable String tenant, @RequestBody RoleBody b,
                                          @RequestHeader(value = "X-Mesh-Service", defaultValue = "") String svc, HttpServletRequest req) {
        core(req, svc);
        if (b == null) throw ApiException.badRequest("body required");
        String actor = authorize(b.actor(), b.core_authorized(), tenant, "rbac.role:create");
        return store.createOrgRole(actor, tenant, b.name(), b.description(), b.base_roles(), store.parseGrid(b.grid(), b.permissions()));
    }

    @PublicEndpoint("mesh: the core changes an org-defined role on behalf of an org admin")
    @PutMapping("/roles/{name}")
    public Map<String, Object> updateRole(@PathVariable String tenant, @PathVariable String name, @RequestBody RoleBody b,
                                          @RequestHeader(value = "X-Mesh-Service", defaultValue = "") String svc, HttpServletRequest req) {
        core(req, svc);
        if (b == null) throw ApiException.badRequest("body required");
        ownedBy(store.getRole(name), tenant);
        String actor = authorize(b.actor(), b.core_authorized(), tenant, "rbac.role:modify");
        boolean gridGiven = b.grid() != null || b.permissions() != null;
        return store.updateOrgRole(actor, name, b.description(), b.base_roles(), gridGiven ? store.parseGrid(b.grid(), b.permissions()) : null);
    }

    @PublicEndpoint("mesh: the core deletes an org-defined role with no assignments on behalf of an org admin")
    @PostMapping("/roles/{name}/delete")
    public Map<String, Object> deleteRole(@PathVariable String tenant, @PathVariable String name, @RequestBody OnBehalf b,
                                          @RequestHeader(value = "X-Mesh-Service", defaultValue = "") String svc, HttpServletRequest req) {
        core(req, svc);
        ownedBy(store.getRole(name), tenant);
        String actor = authorize(b == null ? null : b.actor(), b == null ? null : b.core_authorized(), tenant, "rbac.role:delete");
        store.deleteRole(actor, name);
        return Map.of("ok", true);
    }

    // ---- assignments ---------------------------------------------------------

    @PublicEndpoint("mesh: the core lists the role assignments in an org")
    @GetMapping("/assignments")
    public List<Map<String, Object>> assignments(@PathVariable String tenant, @RequestParam(required = false, defaultValue = "") String subject,
                                                 @RequestHeader(value = "X-Mesh-Service", defaultValue = "") String svc, HttpServletRequest req) {
        core(req, svc);
        return access.list(subject, tenant, "", "");
    }

    @PublicEndpoint("mesh: the core requests a role for a user of the org on behalf of an org admin (four-eyes applies)")
    @PostMapping("/assignments")
    public Map<String, Object> assign(@PathVariable String tenant, @RequestBody AssignBody b,
                                      @RequestHeader(value = "X-Mesh-Service", defaultValue = "") String svc, HttpServletRequest req) {
        core(req, svc);
        if (b == null) throw ApiException.badRequest("body required");
        String actor = actorOf(b.actor());
        // the core decides its own writes (backfill, KYC go-live, user lifecycle): when the core
        // names a system source the decision flows as the core made it, otherwise core_portal
        // A system source is the core's own decision, not a portal user's: it never rides on the
        // temporary core_authorized bridge, so go-live keeps working after the bridge closes.
        boolean system = isSystem(b.source());
        String source = system ? b.source() : SOURCE;
        return access.request(actor, new AccessService.AssignRequest(b.subject(), b.role(), tenant,
                        b.scope() == null || b.scope().isBlank() ? "own" : b.scope(), WebSupport.instant(b.expires_at()), b.reason()),
                source, system || WebSupport.coreAuthorized(b.core_authorized(), cfg));
    }

    @PublicEndpoint("mesh: the core revokes a role assignment in the org on behalf of an org admin")
    @PostMapping("/assignments/{id}/revoke")
    public Map<String, Object> revoke(@PathVariable String tenant, @PathVariable String id, @RequestBody DecideBody b,
                                      @RequestHeader(value = "X-Mesh-Service", defaultValue = "") String svc, HttpServletRequest req) {
        core(req, svc);
        if (b == null) throw ApiException.badRequest("body required");
        if (!tenant.equals(access.view(id).get("tenant"))) throw ApiException.notFound();
        boolean system = isSystem(b.source());
        return access.revoke(actorOf(b.actor()), id, b.reason(), system, !system && WebSupport.coreAuthorized(b.core_authorized(), cfg));
    }

    private static boolean isSystem(String source) {
        return source != null && SYSTEM_SOURCES.contains(source);
    }

    @PublicEndpoint("mesh: the core lists pending role requests in the org that the actor may decide")
    @GetMapping("/approvals")
    public List<Map<String, Object>> approvals(@PathVariable String tenant, @RequestParam String actor,
                                               @RequestHeader(value = "X-Mesh-Service", defaultValue = "") String svc, HttpServletRequest req) {
        core(req, svc);
        return access.approvalsFor(actorOf(actor)).stream().filter(a -> tenant.equals(a.get("tenant"))).toList();
    }

    @PublicEndpoint("mesh: the core approves or rejects a role request on behalf of an approver (never the requester or grantee)")
    @PostMapping("/approvals/{id}")
    public Map<String, Object> decide(@PathVariable String tenant, @PathVariable String id, @RequestBody ApproveBody b,
                                      @RequestHeader(value = "X-Mesh-Service", defaultValue = "") String svc, HttpServletRequest req) {
        core(req, svc);
        if (b == null || b.approve() == null) throw ApiException.badRequest("approve (true|false) required");
        if (!tenant.equals(access.view(id).get("tenant"))) throw ApiException.notFound();
        return access.decide(actorOf(b.actor()), id, b.approve(), b.reason());
    }
}
