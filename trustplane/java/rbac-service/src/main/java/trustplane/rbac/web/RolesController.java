package trustplane.rbac.web;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;
import trustplane.rbac.core.RbacStore;
import trustplane.shared.ApiException;
import trustplane.shared.CrudResource;
import trustplane.shared.Permission;

import java.util.List;
import java.util.Map;

/** Platform role catalog (root-owned; CRUD). */
@RestController
@lombok.RequiredArgsConstructor
@CrudResource(value = "rbac.role", extra = {})
@RequestMapping("/rbac/admin/roles")
public class RolesController {

    private final RbacStore store;

    public record Body(String name, String description, String[] permissions) {}

    @Permission("rbac.role:read")
    @PreAuthorize("hasAuthority('rbac.role:read')")
    @GetMapping
    public List<Map<String, Object>> list(Authentication auth) {
        WebSupport.recheck(store, auth, "rbac.role:read");
        return store.listRoles();
    }

    @Permission("rbac.role:create")
    @PreAuthorize("hasAuthority('rbac.role:create')")
    @PostMapping
    public Map<String, Object> create(Authentication auth, @RequestBody Body b) {
        WebSupport.recheck(store, auth, "rbac.role:create");
        if (b == null || b.name() == null || b.name().isBlank()) throw ApiException.badRequest("name required");
        return store.createRole(WebSupport.subject(auth), b.name(),
                b.description() == null ? "" : b.description(),
                b.permissions() == null ? List.of() : List.of(b.permissions()));
    }

    @Permission("rbac.role:read")
    @PreAuthorize("hasAuthority('rbac.role:read')")
    @GetMapping("/{name}")
    public Map<String, Object> get(Authentication auth, @PathVariable String name) {
        WebSupport.recheck(store, auth, "rbac.role:read");
        return store.getRole(name);
    }

    @Permission("rbac.role:update")
    @PreAuthorize("hasAuthority('rbac.role:update')")
    @PutMapping("/{name}")
    public Map<String, Object> update(Authentication auth, @PathVariable String name,
                                      @RequestBody(required = false) Body b) {
        WebSupport.recheck(store, auth, "rbac.role:update");
        return store.updateRole(WebSupport.subject(auth), name,
                b == null ? null : b.description(),
                (b == null || b.permissions() == null) ? null : List.of(b.permissions()));
    }

    @Permission("rbac.role:delete")
    @PreAuthorize("hasAuthority('rbac.role:delete')")
    @DeleteMapping("/{name}")
    public Map<String, Object> delete(Authentication auth, @PathVariable String name) {
        WebSupport.recheck(store, auth, "rbac.role:delete");
        store.deleteRole(WebSupport.subject(auth), name);
        return Map.of("ok", true);
    }
}
