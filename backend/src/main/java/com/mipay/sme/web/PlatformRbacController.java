package com.mipay.sme.web;

import com.mipay.sme.domain.core.User;
import com.mipay.sme.repository.UserRepository;
import com.mipay.sme.security.Current;
import com.mipay.sme.security.rbac.Perm;
import com.mipay.sme.security.rbac.RbacMeshClient;
import com.mipay.sme.security.rbac.RbacReconciliationService;
import com.mipay.sme.security.rbac.TenantSource;
import com.mipay.sme.security.rbac.Verb;
import com.mipay.sme.service.OrgProvisioningService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Platform-wide RBAC administration for the admin portal (cut-over P5.5), backed by RBAC's
 * mesh API: the standard roles and their grids (the permission matrix), the grid rows, the
 * core's users, and assignments across all orgs. Replaces the hard-coded demo data the
 * admin controller used to return. Writes go to RBAC on behalf of the acting user; until
 * cut-over P6 ({@code app.rbac.mode=enforce}) the core vouches for the actor (the
 * /api/admin/** guard), afterwards RBAC checks the actor's own platform cell. Grid save
 * rules, SoD and four-eyes apply either way. Org-defined roles are managed per org.
 */
@RestController
@RequestMapping("/api/admin/rbac")
@RequiredArgsConstructor
public class PlatformRbacController {

    private final RbacMeshClient rbac;
    private final RbacReconciliationService reconciliation;
    private final UserRepository userRepo;

    @Value("${app.rbac.mode:shadow}")
    private String rbacMode;

    private Map<String, Object> onBehalf() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("actor", Current.get().userId());
        m.put("core_authorized", !"enforce".equalsIgnoreCase(rbacMode));
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object o) {
        return o instanceof List<?> l ? (List<String>) l : List.of();
    }

    @SuppressWarnings("unchecked")
    static Dtos.RbacRoleView role(Map<String, Object> m) {
        List<String> own = new ArrayList<>();
        if (m.get("grid") instanceof Map<?, ?> g) {
            for (var e : ((Map<String, Object>) g).entrySet()) for (String v : strings(e.getValue())) own.add(e.getKey() + ":" + v);
        }
        own.sort(null);
        return new Dtos.RbacRoleView(String.valueOf(m.get("name")), String.valueOf(m.getOrDefault("description", "")),
                strings(m.get("permissions")), String.valueOf(m.get("kind")), String.valueOf(m.get("approval")),
                Boolean.TRUE.equals(m.get("seeded")), Boolean.TRUE.equals(m.get("locked"))
                        || "organization_root".equals(m.get("name")), strings(m.get("inherits")), own, strings(m.get("all_rows")));
    }

    static Dtos.RbacAssignmentView assignment(Map<String, Object> m) {
        return new Dtos.RbacAssignmentView(String.valueOf(m.get("id")), String.valueOf(m.get("subject")), String.valueOf(m.get("role")),
                m.get("tenant") == null ? "" : String.valueOf(m.get("tenant")), String.valueOf(m.get("scope")), String.valueOf(m.get("status")));
    }

    // ---------------- roles and the grid ----------------

    @Perm(resource = "rbac.role", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Whether RBAC decides application access (shadow | enforce | off)")
    @GetMapping("/mode")
    public Map<String, Object> mode() {
        return Map.of("mode", rbacMode);
    }

    @Perm(resource = "rbac.role", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "List the standard RBAC roles with their own and effective cells")
    @GetMapping("/roles")
    public List<Dtos.RbacRoleView> roles() {
        return rbac.platformRoles().stream().map(PlatformRbacController::role).toList();
    }

    @Perm(resource = "rbac.role", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Get one standard RBAC role")
    @GetMapping("/roles/{name}")
    public Dtos.RbacRoleView role(@PathVariable String name) {
        return roles().stream().filter(r -> r.name().equals(name)).findFirst()
                .orElseThrow(() -> new com.mipay.sme.common.NotFoundException("Role not found: " + name));
    }

    @Perm(resource = "rbac.role", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "The grid rows and the cells endpoints use")
    @GetMapping("/resources")
    public List<Dtos.RbacResourceView> resources() {
        return rbac.platformResources().stream().map(m -> new Dtos.RbacResourceView(String.valueOf(m.get("id")),
                String.valueOf(m.get("service")), String.valueOf(m.getOrDefault("description", "")),
                strings(m.get("used")), strings(m.get("tenant_types")), endpoints(m.get("endpoints")))).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Dtos.RbacEndpointView> endpoints(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        List<Dtos.RbacEndpointView> out = new ArrayList<>();
        for (Object x : l) {
            if (x instanceof Map<?, ?> e) {
                Map<String, Object> m = (Map<String, Object>) e;
                out.add(new Dtos.RbacEndpointView(String.valueOf(m.get("verb")), String.valueOf(m.get("method")),
                        String.valueOf(m.get("path")), m.get("handler") == null ? "" : String.valueOf(m.get("handler"))));
            }
        }
        return out;
    }

    /** Sets the role's OWN cells; RBAC applies the grid save rules (409 SOD_CONFLICT, 400 cell_not_used). */
    @Perm(resource = "rbac.role", verb = Verb.MODIFY, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Set a standard RBAC role's own cells")
    @PutMapping("/roles/{name}")
    public Dtos.RbacRoleView updateRole(@PathVariable String name, @RequestBody Dtos.RbacRoleUpdate update) {
        Map<String, Object> body = onBehalf();
        body.put("permissions", update.permissions() == null ? List.of() : update.permissions());
        return role(rbac.updatePlatformRole(name, body));
    }

    /** A new standard role: name, what it is for, ORG or PLATFORM, the roles it builds on, and its own cells. */
    public record RbacRoleCreate(String name, String description, String kind, List<String> inherits, List<String> permissions) { }

    /** Creates a non-seeded standard role; RBAC applies the grid save rules and refuses cycles. */
    @Perm(resource = "rbac.role", verb = Verb.CREATE, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Create a standard RBAC role")
    @PostMapping("/roles")
    public Dtos.RbacRoleView createRole(@RequestBody RbacRoleCreate req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw new com.mipay.sme.common.BadRequestException("ROLE_NAME_REQUIRED", "A role name is required");
        }
        Map<String, Object> body = onBehalf();
        body.put("name", req.name().trim());
        body.put("description", req.description() == null ? "" : req.description().trim());
        body.put("kind", req.kind() == null ? "ORG" : req.kind());
        body.put("inherits", req.inherits() == null ? List.of() : req.inherits());
        body.put("permissions", req.permissions() == null ? List.of() : req.permissions());
        return role(rbac.createPlatformRole(body));
    }

    /** Deletes a non-seeded standard role with no active or pending assignments. */
    @Perm(resource = "rbac.role", verb = Verb.DELETE, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Delete a standard RBAC role")
    @DeleteMapping("/roles/{name}")
    public Map<String, Object> deleteRole(@PathVariable String name) {
        rbac.deletePlatformRole(name, onBehalf());
        return Map.of("ok", true);
    }

    // ---------------- users and assignments ----------------

    @Perm(resource = "rbac.user", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "The core's users as RBAC subjects")
    @GetMapping("/users")
    public List<Dtos.RbacUserView> users() {
        List<Dtos.RbacUserView> out = new ArrayList<>();
        for (User u : userRepo.findAll()) {
            boolean platform = OrgProvisioningService.ORG_ROOT.equals(u.getOrgId());
            out.add(new Dtos.RbacUserView(u.getId(), u.getName(), u.getOrgId(), platform ? "platform" : "org",
                    u.getStatus() == null ? null : u.getStatus().name()));
        }
        return out;
    }

    @Perm(resource = "rbac.assignment", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Role assignments across all orgs")
    @GetMapping("/assignments")
    public List<Dtos.RbacAssignmentView> assignments(@RequestParam(required = false, defaultValue = "") String status) {
        return rbac.platformAssignments(status).stream().map(PlatformRbacController::assignment).toList();
    }

    @Perm(resource = "rbac.assignment", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Compare one org's core admin/staff roles with active RBAC assignments")
    @GetMapping("/reconciliation/{orgId}")
    public RbacReconciliationService.Report reconciliation(@PathVariable String orgId) {
        return reconciliation.report(orgId);
    }

    /** Requests a role (four-eyes by the role's risk: platform roles wait for a second platform user admin). */
    @Perm(resource = "rbac.assignment", verb = Verb.CREATE, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Request an RBAC role assignment")
    @PostMapping("/assignments")
    public Dtos.RbacAssignmentView assign(@RequestBody Dtos.RbacAssignRequest req) {
        Map<String, Object> body = onBehalf();
        body.put("subject", req.subject());
        body.put("role", req.role());
        body.put("tenant", req.tenant());
        body.put("scope", req.scope());
        body.put("expires_at", req.expiresAt());
        body.put("reason", req.reason());
        return assignment(rbac.platformAssign(body));
    }

    @Perm(resource = "rbac.assignment", verb = Verb.ADMINISTER, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Revoke an RBAC role assignment")
    @DeleteMapping("/assignments/{id}")
    public Dtos.RbacAssignmentView revoke(@PathVariable String id, @RequestParam(required = false, defaultValue = "") String reason) {
        Map<String, Object> body = onBehalf();
        body.put("reason", reason);
        return assignment(rbac.platformRevoke(id, body));
    }

    @ExceptionHandler(RbacMeshClient.RbacMeshException.class)
    public ResponseEntity<ProblemDetail> rbacError(RbacMeshClient.RbacMeshException e) {
        HttpStatus st = HttpStatus.resolve(e.status);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(st == null ? HttpStatus.BAD_GATEWAY : st, e.getMessage());
        pd.setProperty("code", e.code);
        return ResponseEntity.status(pd.getStatus()).body(pd);
    }
}
