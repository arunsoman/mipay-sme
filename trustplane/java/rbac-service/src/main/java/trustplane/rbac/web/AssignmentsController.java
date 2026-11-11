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

import java.util.List;
import java.util.Map;

/**
 * Role assignment controller. THE core of the delegated model: platform
 * staff assign directly; supplier admins are validated against the covering
 * root grant (membership, scope, tenant) at assignment time — and every
 * assignment is revalidated at request time.
 */
@RestController
@lombok.RequiredArgsConstructor
@CrudResource(value = "rbac.assignment", extra = {})
@RequestMapping("/rbac/admin/assignments")
public class AssignmentsController {

    private final RbacStore store;

    public record Body(String subject, String role, String tenant, String scope) {}

    @Permission("rbac.assignment:read")
    @PreAuthorize("hasAuthority('rbac.assignment:read')")
    @GetMapping
    public List<Map<String, Object>> list(Authentication auth,
                                          @RequestParam(required = false, defaultValue = "") String subject,
                                          @RequestParam(required = false, defaultValue = "") String tenant,
                                          @RequestParam(required = false, defaultValue = "") String supplier) {
        WebSupport.recheck(store, auth, "rbac.assignment:read");
        return store.listAssignments(subject, tenant, supplier);
    }

    @Permission("rbac.assignment:create")
    @PreAuthorize("hasAuthority('rbac.assignment:create')")
    @PostMapping
    public Map<String, Object> create(Authentication auth, @RequestBody Body b) {
        WebSupport.recheck(store, auth, "rbac.assignment:create");
        if (b == null || b.subject() == null || b.subject().isBlank()
                || b.role() == null || b.role().isBlank()) {
            throw ApiException.badRequest("subject+role required");
        }
        return store.createAssignment(WebSupport.subject(auth), b.subject(), b.role(),
                b.tenant() == null ? "" : b.tenant(), b.scope() == null ? "" : b.scope());
    }

    @Permission("rbac.assignment:delete")
    @PreAuthorize("hasAuthority('rbac.assignment:delete')")
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(Authentication auth, @PathVariable String id) {
        WebSupport.recheck(store, auth, "rbac.assignment:delete");
        store.revokeAssignment(WebSupport.subject(auth), id);
        return Map.of("ok", true);
    }
}
