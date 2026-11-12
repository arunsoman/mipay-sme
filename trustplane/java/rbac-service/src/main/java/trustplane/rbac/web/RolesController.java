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

    /** grid: {row: [verbs]} or permissions: ["row:verb"] (the admin portal's flat form). */
    public record Body(String name, String description, String kind, Map<String, List<String>> grid,
                       List<String> permissions, List<String> inherits) {}

    @Permission(value = "rbac.role:read", description = "List roles with their grids")
    @GetMapping("/roles")
    public List<Map<String, Object>> list(Authentication auth) {
        WebSupport.require(decisions, auth, "", "rbac.role:read");
        return store.listRoles();
    }

    @Permission(value = "rbac.role:read", description = "One role with its grid")
    @GetMapping("/roles/{name}")
    public Map<String, Object> get(Authentication auth, @PathVariable String name) {
        WebSupport.require(decisions, auth, "", "rbac.role:read");
        return store.getRole(name);
    }

    @Permission(value = "rbac.role:create", description = "Create a custom role")
    @PostMapping("/roles")
    public Map<String, Object> create(Authentication auth, @RequestBody Body b) {
        WebSupport.requireTenantOrPlatform(decisions, auth, null, "rbac.role:create");
        if (b == null) throw ApiException.badRequest("body required");
        return store.createRole(WebSupport.actor(auth), b.name(), b.description(), b.kind(),
                store.parseGrid(b.grid(), b.permissions()), b.inherits());
    }

    @Permission(value = "rbac.role:modify", description = "Change a role's grid, description or hierarchy")
    @PutMapping("/roles/{name}")
    public Map<String, Object> update(Authentication auth, @PathVariable String name, @RequestBody Body b) {
        WebSupport.requireTenantOrPlatform(decisions, auth, null, "rbac.role:modify");
        if (b == null) throw ApiException.badRequest("body required");
        boolean gridGiven = b.grid() != null || b.permissions() != null;
        return store.updateRole(WebSupport.actor(auth), name, b.description(),
                gridGiven ? store.parseGrid(b.grid(), b.permissions()) : null, b.inherits());
    }

    @Permission(value = "rbac.role:delete", description = "Delete a custom role with no assignments")
    @DeleteMapping("/roles/{name}")
    public Map<String, Object> delete(Authentication auth, @PathVariable String name) {
        WebSupport.requireTenantOrPlatform(decisions, auth, null, "rbac.role:delete");
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
