package trustplane.rbac.web;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import trustplane.rbac.config.RbacConfig;
import trustplane.rbac.core.Decisions;
import trustplane.rbac.core.RbacStore;
import trustplane.rbac.core.RolePack;
import trustplane.shared.ApiException;
import trustplane.shared.Permission;

import java.util.List;
import java.util.Map;

/** Roles and their grids, the row catalogue, and the compliance role pack (P2.1, P2.5a). */
@RestController
@lombok.RequiredArgsConstructor
@RequestMapping("/rbac/admin")
public class RolesController {

    private final RbacStore store;
    private final Decisions decisions;
    private final RolePack pack;
    private final RbacConfig cfg;

    /**
     * grid: {row: [verbs]} or permissions: ["row:verb"] (the admin portal's flat form).
     * With {@code tenant}: an org-defined role of that org, carved from {@code base_roles}
     * (docs/plans/extended-rbac.md X8); {@code name} is then the slug.
     */
    public record Body(String name, String description, String kind, Map<String, List<String>> grid,
                       List<String> permissions, List<String> inherits, String tenant, List<String> base_roles) {}

    @Permission(value = "rbac.role:read", description = "List roles with their grids (?tenant=: the roles of one org and its ceiling)")
    @GetMapping("/roles")
    public Object list(Authentication auth, @RequestParam(required = false) String tenant) {
        if (tenant != null && !tenant.isBlank()) {
            WebSupport.require(decisions, auth, tenant, "rbac.role:read");
            return store.rolesFor(tenant);
        }
        WebSupport.require(decisions, auth, "", "rbac.role:read");
        boolean platform = decisions.checkPlatform(WebSupport.actor(auth), "rbac.role:read");
        // an org's own roles are visible only to that org (and platform-wide readers)
        return store.listRoles().stream().filter(r -> platform || r.get("owner_tenant") == null).toList();
    }

    @Permission(value = "rbac.role:read", description = "One role with its grid")
    @GetMapping("/roles/{name}")
    public Map<String, Object> get(Authentication auth, @PathVariable String name) {
        Map<String, Object> r = store.getRole(name);
        String owner = (String) r.get("owner_tenant");
        if (owner != null) WebSupport.requireTenantOrPlatform(decisions, auth, owner, "rbac.role:read");
        else WebSupport.require(decisions, auth, "", "rbac.role:read");
        return r;
    }

    @Permission(value = "rbac.role:create", description = "Create a custom role (with tenant: an org-defined role of that org)")
    @PostMapping("/roles")
    public Map<String, Object> create(Authentication auth, @RequestBody Body b) {
        if (b == null) throw ApiException.badRequest("body required");
        if (b.tenant() != null && !b.tenant().isBlank()) {
            WebSupport.require(decisions, auth, b.tenant(), "rbac.role:create");
            return store.createOrgRole(WebSupport.actor(auth), b.tenant(), b.name(), b.description(), b.base_roles(),
                    store.parseGrid(b.grid(), b.permissions()));
        }
        WebSupport.requireTenantOrPlatform(decisions, auth, null, "rbac.role:create");
        return store.createRole(WebSupport.actor(auth), b.name(), b.description(), b.kind(),
                store.parseGrid(b.grid(), b.permissions()), b.inherits());
    }

    @Permission(value = "rbac.role:modify", description = "Change a role's grid, description or hierarchy")
    @PutMapping("/roles/{name}")
    public Map<String, Object> update(Authentication auth, @PathVariable String name, @RequestBody Body b) {
        if (b == null) throw ApiException.badRequest("body required");
        boolean gridGiven = b.grid() != null || b.permissions() != null;
        String owner = (String) store.getRole(name).get("owner_tenant");
        if (owner != null) {
            WebSupport.require(decisions, auth, owner, "rbac.role:modify");
            return store.updateOrgRole(WebSupport.actor(auth), name, b.description(), b.base_roles(),
                    gridGiven ? store.parseGrid(b.grid(), b.permissions()) : null);
        }
        WebSupport.requireTenantOrPlatform(decisions, auth, null, "rbac.role:modify");
        return store.updateRole(WebSupport.actor(auth), name, b.description(),
                gridGiven ? store.parseGrid(b.grid(), b.permissions()) : null, b.inherits());
    }

    @Permission(value = "rbac.role:delete", description = "Delete a custom role with no assignments")
    @DeleteMapping("/roles/{name}")
    public Map<String, Object> delete(Authentication auth, @PathVariable String name) {
        String owner = (String) store.getRole(name).get("owner_tenant");
        if (owner != null) WebSupport.require(decisions, auth, owner, "rbac.role:delete");
        else WebSupport.requireTenantOrPlatform(decisions, auth, null, "rbac.role:delete");
        store.deleteRole(WebSupport.actor(auth), name);
        return Map.of("ok", true);
    }

    @Permission(value = "rbac.role:read", description = "Grid rows with their used cells and endpoints")
    @GetMapping("/resources")
    public List<Map<String, Object>> resources(Authentication auth) {
        WebSupport.require(decisions, auth, "", "rbac.role:read");
        return store.resources();
    }

    @Permission(value = "rbac.role:read", description = "Compliance role pack (markdown)")
    @GetMapping(value = "/export/role-pack", produces = "text/markdown")
    public ResponseEntity<String> rolePack(Authentication auth) {
        WebSupport.require(decisions, auth, "", "rbac.role:read");
        return ResponseEntity.ok().header("Content-Type", "text/markdown; charset=utf-8").body(pack.render(cfg.environment()));
    }
}
