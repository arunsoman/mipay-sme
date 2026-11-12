package trustplane.rbac.web;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import trustplane.rbac.core.AccessService;
import trustplane.rbac.core.Decisions;
import trustplane.rbac.core.RbacStore;
import trustplane.shared.ApiException;
import trustplane.shared.Permission;

import java.util.List;
import java.util.Map;

/** Delegated grants (onboarding rights, P2.6), access reviews (P2.7), audit and decisions. */
@RestController
@lombok.RequiredArgsConstructor
@RequestMapping("/rbac/admin")
public class GrantsReviewsController {

    private final AccessService access;
    private final Decisions decisions;
    private final RbacStore store;

    public record GrantBody(String tenant, String permission, List<String> allowed_values, String scope,
                            Boolean can_delegate, String parent_grant_id, String expires_at) {}

    public record ReviewBody(String tenant, String label) {}

    public record ItemBody(String decision) {}

    @Permission(value = "rbac.grant:read", tenantSource = "QUERY", description = "List grants")
    @GetMapping("/grants")
    public List<Map<String, Object>> grants(Authentication auth, @RequestParam(required = false, defaultValue = "") String tenant) {
        WebSupport.requireTenantOrPlatform(decisions, auth, tenant, "rbac.grant:read");
        return access.grants(tenant);
    }

    @Permission(value = "rbac.grant:create", tenantSource = "BODY", description = "Grant (or delegate, narrowing) an entitlement")
    @PostMapping("/grants")
    public Map<String, Object> grant(Authentication auth, @RequestBody GrantBody b) {
        if (b == null) throw ApiException.badRequest("body required");
        WebSupport.require(decisions, auth, "", "rbac.grant:create");
        return access.grant(WebSupport.actor(auth), new AccessService.GrantRequest(b.tenant(), b.permission(), b.allowed_values(),
                b.scope(), Boolean.TRUE.equals(b.can_delegate()), b.parent_grant_id(), WebSupport.instant(b.expires_at())), false);
    }

    @Permission(value = "rbac.grant:administer", description = "Revoke a grant (cascades to delegated grants)")
    @DeleteMapping("/grants/{id}")
    public Map<String, Object> revokeGrant(Authentication auth, @PathVariable String id) {
        WebSupport.require(decisions, auth, "", "rbac.grant:administer");
        return access.revokeGrant(WebSupport.actor(auth), id, false);
    }

    @Permission(value = "rbac.review:create", tenantSource = "BODY", description = "Open an access review")
    @PostMapping("/reviews")
    public Map<String, Object> openReview(Authentication auth, @RequestBody(required = false) ReviewBody b) {
        WebSupport.require(decisions, auth, "", "rbac.review:create");
        return access.createReview(WebSupport.actor(auth), b == null ? null : b.tenant(), b == null ? null : b.label());
    }

    @Permission(value = "rbac.review:read", description = "One access review with its items")
    @GetMapping("/reviews/{id}")
    public Map<String, Object> review(Authentication auth, @PathVariable String id) {
        Map<String, Object> r = access.review(id);
        WebSupport.requireTenantOrPlatform(decisions, auth, (String) r.get("tenant"), "rbac.review:read");
        return r;
    }

    @Permission(value = "rbac.review:administer", description = "Confirm or remove one assignment in a review")
    @PostMapping("/reviews/{id}/items/{assignmentId}")
    public Map<String, Object> decide(Authentication auth, @PathVariable String id, @PathVariable String assignmentId, @RequestBody ItemBody b) {
        WebSupport.require(decisions, auth, "", "rbac.review:administer");
        return access.decideReviewItem(WebSupport.actor(auth), id, assignmentId, b == null ? null : b.decision());
    }

    @Permission(value = "rbac.audit:read", description = "RBAC audit trail")
    @GetMapping("/audit")
    public List<Map<String, Object>> audit(Authentication auth,
                                           @RequestParam(required = false, defaultValue = "") String tenant,
                                           @RequestParam(required = false, defaultValue = "100") int limit) {
        WebSupport.requireTenantOrPlatform(decisions, auth, tenant, "rbac.audit:read");
        return store.audit(tenant, limit);
    }

    @Permission(value = "rbac.audit:read", description = "Explain a decision by its id")
    @GetMapping("/decisions/{id}")
    public Map<String, Object> decision(Authentication auth, @PathVariable String id) {
        WebSupport.requireTenantOrPlatform(decisions, auth, null, "rbac.audit:read");
        Map<String, Object> d = decisions.explain(id);
        if (d == null) throw ApiException.notFound();
        return d;
    }
}
