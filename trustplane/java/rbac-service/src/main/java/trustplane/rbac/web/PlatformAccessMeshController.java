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
 * Mesh endpoints behind the admin portal's platform-wide access screens (cut-over P5.5):
 * the standard roles and their grids, the grid rows, and platform-wide assignments. Writes
 * are made on behalf of a named actor (the core user id), exactly like
 * {@link OrgAccessMeshController}: RBAC checks the actor's platform-wide cell unless the core
 * says it authorized the actor itself ({@code core_authorized}, until cut-over P6); grid
 * save rules, SoD and four-eyes apply either way, and audit names the actor. Org-defined
 * roles are managed per org, not here. Only the core's certificate may call these.
 */
@RestController
@lombok.RequiredArgsConstructor
@RequestMapping("/rbac/internal/platform")
public class PlatformAccessMeshController {

    private final RbacStore store;
    private final AccessService access;
    private final Decisions decisions;
    private final RbacConfig cfg;

    public record RoleBody(String actor, Boolean core_authorized, String description,
                           Map<String, List<String>> grid, List<String> permissions) {}

    public record CreateBody(String actor, Boolean core_authorized, String name, String description, String kind,
                             List<String> inherits, Map<String, List<String>> grid, List<String> permissions) {}

    public record AssignBody(String actor, Boolean core_authorized, String subject, String role, String tenant,
                             String scope, String expires_at, String reason) {}

    public record ReasonBody(String actor, Boolean core_authorized, String reason) {}

    private void core(HttpServletRequest req, String svc) {
        WebSupport.meshCaller(req, svc, Set.of(WebSupport.normalize(cfg.coreCN())));
    }

    private String authorize(String actor, Boolean coreAuthorized, String cell) {
        if (actor == null || actor.isBlank()) throw ApiException.badRequest("actor (the core user id) required");
        if (!WebSupport.coreAuthorized(coreAuthorized, cfg) && !decisions.checkPlatform(actor, cell)) {
            throw new ApiException(403, "insufficient_permission", "actor lacks " + cell + " platform-wide");
        }
        return actor;
    }

    @PublicEndpoint("mesh: the core lists the standard roles with their own and effective grids")
    @GetMapping("/roles")
    public List<Map<String, Object>> roles(@RequestHeader(value = "X-Mesh-Service", defaultValue = "") String svc, HttpServletRequest req) {
        core(req, svc);
        return store.listRoles().stream().filter(r -> r.get("owner_tenant") == null).toList();
    }

    @PublicEndpoint("mesh: the core lists the grid rows with the cells endpoints use")
    @GetMapping("/resources")
    public List<Map<String, Object>> resources(@RequestHeader(value = "X-Mesh-Service", defaultValue = "") String svc, HttpServletRequest req) {
        core(req, svc);
        return store.resources();
    }

    @PublicEndpoint("mesh: the core changes a standard role's own grid on behalf of a platform user admin")
    @PutMapping("/roles/{name}")
    public Map<String, Object> updateRole(@PathVariable String name, @RequestBody RoleBody b,
                                          @RequestHeader(value = "X-Mesh-Service", defaultValue = "") String svc, HttpServletRequest req) {
        core(req, svc);
        if (b == null) throw ApiException.badRequest("body required");
        if (store.getRole(name).get("owner_tenant") != null) throw ApiException.notFound(); // org-defined: per org
        String actor = authorize(b.actor(), b.core_authorized(), "rbac.role:modify");
        boolean gridGiven = b.grid() != null || b.permissions() != null;
        return store.updateRole(actor, name, b.description(), gridGiven ? store.parseGrid(b.grid(), b.permissions()) : null, null);
    }

    @PublicEndpoint("mesh: the core creates a standard (non-seeded) role on behalf of a platform user admin")
    @PostMapping("/roles")
    public Map<String, Object> createRole(@RequestBody CreateBody b,
                                          @RequestHeader(value = "X-Mesh-Service", defaultValue = "") String svc, HttpServletRequest req) {
        core(req, svc);
        if (b == null) throw ApiException.badRequest("body required");
        String actor = authorize(b.actor(), b.core_authorized(), "rbac.role:create");
        String kind = b.kind() == null || b.kind().isBlank() ? "ORG" : b.kind();
        for (String j : b.inherits() == null ? List.<String>of() : b.inherits()) {
            Map<String, Object> jr = store.getRole(j);
            if (jr.get("owner_tenant") != null) throw ApiException.badRequest("a standard role can't inherit the org-defined role " + j);
            if (!kind.equals(jr.get("kind"))) throw ApiException.badRequest("a " + kind + " role can't inherit the " + jr.get("kind") + " role " + j);
        }
        return store.createRole(actor, b.name(), b.description(), kind,
                store.parseGrid(b.grid(), b.permissions() == null ? List.of() : b.permissions()), b.inherits() == null ? List.of() : b.inherits());
    }

    @PublicEndpoint("mesh: the core deletes a non-seeded standard role with no assignments on behalf of a platform user admin")
    @PostMapping("/roles/{name}/delete")
    public Map<String, Object> deleteRole(@PathVariable String name, @RequestBody ReasonBody b,
                                          @RequestHeader(value = "X-Mesh-Service", defaultValue = "") String svc, HttpServletRequest req) {
        core(req, svc);
        if (store.getRole(name).get("owner_tenant") != null) throw ApiException.notFound(); // org-defined: per org
        String actor = authorize(b == null ? null : b.actor(), b == null ? null : b.core_authorized(), "rbac.role:delete");
        store.deleteRole(actor, name);
        return Map.of("ok", true);
    }

    @PublicEndpoint("mesh: the core lists assignments across all orgs (platform admin screens)")
    @GetMapping("/assignments")
    public List<Map<String, Object>> assignments(@RequestParam(required = false, defaultValue = "") String status,
                                                 @RequestParam(required = false, defaultValue = "") String role,
                                                 @RequestParam(required = false, defaultValue = "") String subject,
                                                 @RequestHeader(value = "X-Mesh-Service", defaultValue = "") String svc, HttpServletRequest req) {
        core(req, svc);
        return access.list(subject, "", status, role);
    }

    @PublicEndpoint("mesh: the core requests a role on behalf of a platform user admin (four-eyes applies)")
    @PostMapping("/assignments")
    public Map<String, Object> assign(@RequestBody AssignBody b, @RequestHeader(value = "X-Mesh-Service", defaultValue = "") String svc,
                                      HttpServletRequest req) {
        core(req, svc);
        if (b == null || b.actor() == null || b.actor().isBlank()) throw ApiException.badRequest("actor (the core user id) required");
        return access.request(b.actor(), new AccessService.AssignRequest(b.subject(), b.role(), b.tenant(), b.scope(),
                WebSupport.instant(b.expires_at()), b.reason()), OrgAccessMeshController.SOURCE, WebSupport.coreAuthorized(b.core_authorized(), cfg));
    }

    @PublicEndpoint("mesh: the core revokes an assignment on behalf of a platform user admin")
    @PostMapping("/assignments/{id}/revoke")
    public Map<String, Object> revoke(@PathVariable String id, @RequestBody ReasonBody b,
                                      @RequestHeader(value = "X-Mesh-Service", defaultValue = "") String svc, HttpServletRequest req) {
        core(req, svc);
        if (b == null || b.actor() == null || b.actor().isBlank()) throw ApiException.badRequest("actor (the core user id) required");
        return access.revoke(b.actor(), id, b.reason(), false, WebSupport.coreAuthorized(b.core_authorized(), cfg));
    }
}
