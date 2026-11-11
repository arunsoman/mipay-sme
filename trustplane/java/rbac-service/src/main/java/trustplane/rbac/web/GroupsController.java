package trustplane.rbac.web;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;
import trustplane.rbac.core.RbacStore;
import trustplane.shared.ApiException;
import trustplane.shared.CrudResource;
import trustplane.shared.Permission;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Supplier-local groups mapped onto delegated roles. Mapping (or adding a
 * member) is validated against the root grant boundary; assignments are
 * materialized through the covering grant so every membership remains
 * traceable to Root RBAC.
 */
@RestController
@lombok.RequiredArgsConstructor
@CrudResource(value = "rbac.group", extra = {})
@RequestMapping("/rbac/admin/groups")
public class GroupsController {

    private final RbacStore store;

    public record Body(String name, String supplier, String role, String scope, String user) {}

    @Permission("rbac.group:read")
    @PreAuthorize("hasAuthority('rbac.group:read')")
    @GetMapping
    public Object list(Authentication auth, @RequestParam(required = false, defaultValue = "") String supplier) {
        WebSupport.recheck(store, auth, "rbac.group:read");
        String actorSup = store.actorSupplier(WebSupport.subject(auth));
        if (actorSup != null && !actorSup.isBlank()) supplier = actorSup;
        return store.listGroups(supplier);
    }

    @Permission("rbac.group:create")
    @PreAuthorize("hasAuthority('rbac.group:create')")
    @PostMapping
    public Map<String, Object> create(Authentication auth, @RequestBody Body b) {
        WebSupport.recheck(store, auth, "rbac.group:create");
        if (b == null || b.name() == null || b.name().isBlank()) throw ApiException.badRequest("name required");
        String actorSup = store.actorSupplier(WebSupport.subject(auth));
        return store.createGroup(WebSupport.subject(auth), actorSup, b.name(),
                b.supplier() == null ? "" : b.supplier());
    }

    @Permission("rbac.group:read")
    @PreAuthorize("hasAuthority('rbac.group:read')")
    @GetMapping("/{id}")
    public Map<String, Object> get(Authentication auth, @PathVariable String id) {
        WebSupport.recheck(store, auth, "rbac.group:read");
        return store.getGroup(id);
    }

    @Permission("rbac.group:delete")
    @PreAuthorize("hasAuthority('rbac.group:delete')")
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(Authentication auth, @PathVariable String id) {
        WebSupport.recheck(store, auth, "rbac.group:delete");
        store.deleteGroup(WebSupport.subject(auth), id);
        return Map.of("ok", true);
    }

    @Permission("rbac.group:update")
    @PreAuthorize("hasAuthority('rbac.group:update')")
    @PostMapping("/{id}/roles")
    public Map<String, Object> mapRole(Authentication auth, @PathVariable String id, @RequestBody Body b) {
        WebSupport.recheck(store, auth, "rbac.group:update");
        if (b == null || b.role() == null || b.role().isBlank()) throw ApiException.badRequest("role required");
        return store.mapGroupRole(WebSupport.subject(auth), id, b.role(), b.scope() == null ? "" : b.scope());
    }

    @Permission("rbac.group:update")
    @PreAuthorize("hasAuthority('rbac.group:update')")
    @PostMapping("/{id}/members")
    public Map<String, Object> addMember(Authentication auth, @PathVariable String id, @RequestBody Body b) {
        WebSupport.recheck(store, auth, "rbac.group:update");
        if (b == null || b.user() == null || b.user().isBlank()) throw ApiException.badRequest("user required");
        return store.groupAddMember(WebSupport.subject(auth), id, b.user());
    }

    @Permission("rbac.group:update")
    @PreAuthorize("hasAuthority('rbac.group:update')")
    @DeleteMapping("/{id}/members")
    public Map<String, Object> removeMember(Authentication auth, @PathVariable String id,
                                            @RequestParam(required = false, defaultValue = "") String user) {
        WebSupport.recheck(store, auth, "rbac.group:update");
        if (user.isBlank()) throw ApiException.badRequest("user required");
        store.groupRemoveMember(WebSupport.subject(auth), id, user);
        return Map.of("ok", true);
    }
}
