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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Root RBAC grants: the delegation boundaries only the platform may create
 * or revoke. Suppliers CANNOT touch this controller (rbac.grant:* never
 * reaches supplier_admin).
 */
@RestController
@lombok.RequiredArgsConstructor
@CrudResource(value = "rbac.grant", extra = {})
@RequestMapping("/rbac/admin/grants")
public class GrantsController {

    private final RbacStore store;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Body(String supplier, String role, String tenant, String scope,
                       Boolean can_delegate, String expires_at) {}

    @Permission("rbac.grant:read")
    @PreAuthorize("hasAuthority('rbac.grant:read')")
    @GetMapping
    public List<Map<String, Object>> list(Authentication auth,
                                          @RequestParam(required = false, defaultValue = "") String supplier) {
        WebSupport.recheck(store, auth, "rbac.grant:read");
        return store.listGrants(supplier);
    }

    @Permission("rbac.grant:create")
    @PreAuthorize("hasAuthority('rbac.grant:create')")
    @PostMapping
    public Map<String, Object> create(Authentication auth, @RequestBody Body b) {
        WebSupport.recheck(store, auth, "rbac.grant:create");
        if (b == null || b.supplier() == null || b.role() == null) throw ApiException.badRequest("supplier+role required");
        OffsetDateTime exp = null;
        if (b.expires_at() != null && !b.expires_at().isBlank()) {
            try {
                exp = OffsetDateTime.parse(b.expires_at());
            } catch (Exception e) {
                throw ApiException.badRequest("expires_at must be RFC3339");
            }
        }
        return store.createGrant(WebSupport.subject(auth), b.supplier(), b.role(),
                b.tenant() == null ? "" : b.tenant(), b.scope() == null ? "" : b.scope(),
                exp, Boolean.TRUE.equals(b.can_delegate()));
    }

    @Permission("rbac.grant:read")
    @PreAuthorize("hasAuthority('rbac.grant:read')")
    @GetMapping("/{id}")
    public Map<String, Object> get(Authentication auth, @PathVariable String id) {
        WebSupport.recheck(store, auth, "rbac.grant:read");
        return store.getGrant(id);
    }

    /** Revocation cascades: every dependent assignment loses authority. */
    @Permission("rbac.grant:delete")
    @PreAuthorize("hasAuthority('rbac.grant:delete')")
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(Authentication auth, @PathVariable String id) {
        WebSupport.recheck(store, auth, "rbac.grant:delete");
        store.revokeGrant(WebSupport.subject(auth), id);
        return Map.of("ok", true);
    }
}
