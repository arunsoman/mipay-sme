package trustplane.rbac.web;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import trustplane.rbac.core.AccessService;
import trustplane.rbac.core.Decisions;
import trustplane.shared.ApiException;
import trustplane.shared.Permission;

import java.util.List;
import java.util.Map;

/**
 * Role assignments and their four-eyes approvals (P2.2, P2.7, P2.7a). A request becomes ACTIVE
 * at once for low-risk org roles, otherwise PENDING_APPROVAL until an eligible second person
 * approves it (D10). Revoking the last org_admin of an org is refused.
 */
@RestController
@lombok.RequiredArgsConstructor
@RequestMapping("/rbac/admin")
public class AssignmentsController {

    private final AccessService access;
    private final Decisions decisions;

    public record Body(String subject, String role, String tenant, String scope, String expires_at, String reason) {}

    public record Decision(String reason) {}

    @Permission(value = "rbac.assignment:read", tenantSource = "QUERY", description = "List assignments (of one org, or platform-wide)")
    @GetMapping("/assignments")
    public List<Map<String, Object>> list(Authentication auth,
                                          @RequestParam(required = false, defaultValue = "") String subject,
                                          @RequestParam(required = false, defaultValue = "") String tenant,
                                          @RequestParam(required = false, defaultValue = "") String status,
                                          @RequestParam(required = false, defaultValue = "") String role) {
        if (tenant.isBlank() && !subject.isBlank() && subject.equals(WebSupport.actor(auth))) {
            return access.list(subject, "", status, role); // everyone may see their own assignments
        }
        WebSupport.requireTenantOrPlatform(decisions, auth, tenant, "rbac.assignment:read");
        return access.list(subject, tenant, status, role);
    }

    @Permission(value = "rbac.assignment:read", description = "One assignment")
    @GetMapping("/assignments/{id}")
    public Map<String, Object> get(Authentication auth, @PathVariable String id) {
        Map<String, Object> a = access.view(id);
        if (!WebSupport.actor(auth).equals(a.get("subject"))) {
            WebSupport.requireTenantOrPlatform(decisions, auth, (String) a.get("tenant"), "rbac.assignment:read");
        }
        return a;
    }

    @Permission(value = "rbac.assignment:create", tenantSource = "BODY", description = "Request (or make) a role assignment")
    @PostMapping("/assignments")
    public Map<String, Object> create(Authentication auth, @RequestBody Body b) {
        if (b == null || b.role() == null || b.subject() == null) throw ApiException.badRequest("subject and role required");
        // the cell check here is "in some org"; AccessService checks the actor against the real org and role
        boolean breakGlass = "platform_break_glass".equals(b.role());
        if (!breakGlass) WebSupport.require(decisions, auth, "", "rbac.assignment:create");
        return access.request(WebSupport.actor(auth), new AccessService.AssignRequest(b.subject(), b.role(), b.tenant(), b.scope(),
                WebSupport.instant(b.expires_at()), b.reason()), "api");
    }

    @Permission(value = "rbac.assignment:administer", description = "Revoke an assignment (or withdraw one's own request)")
    @DeleteMapping("/assignments/{id}")
    public Map<String, Object> revoke(Authentication auth, @PathVariable String id,
                                      @RequestParam(required = false, defaultValue = "") String reason) {
        return access.revoke(WebSupport.actor(auth), id, reason, false);
    }

    @Permission(value = "rbac.assignment:authorize", description = "Requests the caller may approve")
    @GetMapping("/approvals")
    public List<Map<String, Object>> approvals(Authentication auth) {
        WebSupport.require(decisions, auth, "", "rbac.assignment:authorize");
        return access.approvalsFor(WebSupport.actor(auth));
    }

    @Permission(value = "rbac.assignment:authorize", description = "Approve an assignment request (four-eyes)")
    @PostMapping("/approvals/{id}/approve")
    public Map<String, Object> approve(Authentication auth, @PathVariable String id, @RequestBody(required = false) Decision d) {
        WebSupport.require(decisions, auth, "", "rbac.assignment:authorize");
        return access.decide(WebSupport.actor(auth), id, true, d == null ? null : d.reason());
    }

    @Permission(value = "rbac.assignment:authorize", description = "Reject an assignment request")
    @PostMapping("/approvals/{id}/reject")
    public Map<String, Object> reject(Authentication auth, @PathVariable String id, @RequestBody(required = false) Decision d) {
        WebSupport.require(decisions, auth, "", "rbac.assignment:authorize");
        return access.decide(WebSupport.actor(auth), id, false, d == null ? null : d.reason());
    }

    @Permission(value = "rbac.assignment:read", tenantSource = "QUERY", description = "Who holds a role in an org (ListHolders)")
    @GetMapping("/holders")
    public List<Decisions.Holder> holders(Authentication auth, @RequestParam String role,
                                          @RequestParam(required = false, defaultValue = "") String tenant) {
        WebSupport.requireTenantOrPlatform(decisions, auth, tenant, "rbac.assignment:read");
        return decisions.holders(role, tenant);
    }
}
