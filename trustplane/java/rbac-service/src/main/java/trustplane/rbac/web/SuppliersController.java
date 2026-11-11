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

/** Supplier registry lifecycle (platform staff only). */
@RestController
@lombok.RequiredArgsConstructor
@CrudResource(value = "rbac.supplier", extra = {})
@RequestMapping("/rbac/admin/suppliers")
public class SuppliersController {

    private final RbacStore store;

    public record Body(String id, String name, String status) {}

    @Permission("rbac.supplier:read")
    @PreAuthorize("hasAuthority('rbac.supplier:read')")
    @GetMapping
    public List<Map<String, Object>> list(Authentication auth) {
        WebSupport.recheck(store, auth, "rbac.supplier:read");
        return store.listSuppliers();
    }

    @Permission("rbac.supplier:create")
    @PreAuthorize("hasAuthority('rbac.supplier:create')")
    @PostMapping
    public Map<String, Object> create(Authentication auth, @RequestBody Body b) {
        WebSupport.recheck(store, auth, "rbac.supplier:create");
        if (b == null || b.name() == null || b.name().isBlank()) throw ApiException.badRequest("name required");
        return store.createSupplier(WebSupport.subject(auth), b.id(), b.name());
    }

    @Permission("rbac.supplier:read")
    @PreAuthorize("hasAuthority('rbac.supplier:read')")
    @GetMapping("/{id}")
    public Map<String, Object> get(Authentication auth, @PathVariable String id) {
        WebSupport.recheck(store, auth, "rbac.supplier:read");
        return store.getSupplier(id);
    }

    @Permission("rbac.supplier:update")
    @PreAuthorize("hasAuthority('rbac.supplier:update')")
    @PutMapping("/{id}")
    public Map<String, Object> update(Authentication auth, @PathVariable String id,
                                      @RequestBody(required = false) Body b) {
        WebSupport.recheck(store, auth, "rbac.supplier:update");
        return store.updateSupplier(WebSupport.subject(auth), id,
                b == null ? null : b.name(), b == null ? null : b.status());
    }

    @Permission("rbac.supplier:delete")
    @PreAuthorize("hasAuthority('rbac.supplier:delete')")
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(Authentication auth, @PathVariable String id) {
        WebSupport.recheck(store, auth, "rbac.supplier:delete");
        return store.updateSupplier(WebSupport.subject(auth), id, null, "suspended");
    }
}
