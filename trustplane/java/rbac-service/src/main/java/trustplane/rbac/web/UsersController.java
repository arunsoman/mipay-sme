package trustplane.rbac.web;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;
import trustplane.rbac.core.RbacStore;
import trustplane.shared.ApiException;
import trustplane.shared.CrudResource;
import trustplane.shared.GatewayContext;
import trustplane.shared.Permission;

import java.util.List;
import java.util.Map;

/**
 * User management for both planes: platform staff manage anyone; supplier
 * admins are confined to their own organization (enforced in the store).
 */
@RestController
@lombok.RequiredArgsConstructor
@CrudResource(value = "rbac.user", extra = {})
@RequestMapping("/rbac/admin/users")
public class UsersController {

    private final RbacStore store;

    public record Body(String id, String display_name, String supplier, String status) {}

    @Permission("rbac.user:read")
    @PreAuthorize("hasAuthority('rbac.user:read')")
    @GetMapping
    public List<Map<String, Object>> list(Authentication auth, @RequestParam(required = false, defaultValue = "") String supplier) {
        WebSupport.recheck(store, auth, "rbac.user:read");
        String actorSup = actorSupplier(store, auth);
        if (!actorSup.isBlank()) supplier = actorSup; // supplier admins only see their own users
        return store.listUsers(supplier);
    }

    @Permission("rbac.user:create")
    @PreAuthorize("hasAuthority('rbac.user:create')")
    @PostMapping
    public Map<String, Object> create(Authentication auth, @RequestBody Body b) {
        WebSupport.recheck(store, auth, "rbac.user:create");
        if (b == null || b.id() == null || b.id().isBlank()) throw ApiException.badRequest("id required");
        String actorSup = actorSupplier(store, auth);
        String supplier = b.supplier();
        // Delegation boundaries are explicit: a supplier admin may create users
        // only inside his own supplier — a request targeting another supplier
        // (or an empty target resolved to a different org) must be REJECTED,
        // not silently coerced.
        if (!actorSup.isBlank() && supplier != null && !supplier.isBlank() && !supplier.equals(actorSup)) {
            throw trustplane.shared.Failure.toException(
                    trustplane.shared.Failure.membership("supplier admins may only create users in their own supplier"));
        }
        if (!actorSup.isBlank() && (supplier == null || supplier.isBlank())) supplier = actorSup;
        return store.createUser(WebSupport.subject(auth), actorSup, b.id(), b.display_name(), supplier);
    }

    @Permission("rbac.user:read")
    @PreAuthorize("hasAuthority('rbac.user:read')")
    @GetMapping("/{id}")
    public Map<String, Object> get(Authentication auth, @PathVariable String id) {
        WebSupport.recheck(store, auth, "rbac.user:read");
        return store.getUser(id);
    }

    @Permission("rbac.user:update")
    @PreAuthorize("hasAuthority('rbac.user:update')")
    @PutMapping("/{id}")
    public Map<String, Object> update(Authentication auth, @PathVariable String id,
                                      @RequestBody(required = false) Body b) {
        WebSupport.recheck(store, auth, "rbac.user:update");
        String actorSup = actorSupplier(store, auth);
        return store.updateUser(WebSupport.subject(auth), actorSup, id,
                b == null ? null : b.display_name(), b == null ? null : b.status());
    }

    /** Offboarding: suspend the identity; authority dies at request time. */
    @Permission("rbac.user:delete")
    @PreAuthorize("hasAuthority('rbac.user:delete')")
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(Authentication auth, @PathVariable String id) {
        WebSupport.recheck(store, auth, "rbac.user:delete");
        store.deleteUser(WebSupport.subject(auth), actorSupplier(store, auth), id);
        return Map.of("ok", true);
    }

    static String actorSupplier(RbacStore store, Authentication auth) {
        String sub = WebSupport.subject(auth);
        try {
            return store.actorSupplier(sub);
        } catch (Exception e) {
            return "";
        }
    }
}
