package com.mipay.sme.web;

import com.mipay.sme.common.ForbiddenException;
import com.mipay.sme.common.NotFoundException;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.domain.org.Organization;
import com.mipay.sme.repository.OrgClosureRepository;
import com.mipay.sme.repository.UserRepository;
import com.mipay.sme.security.Current;
import com.mipay.sme.security.rbac.Perm;
import com.mipay.sme.security.rbac.RbacMeshClient;
import com.mipay.sme.security.rbac.TenantSource;
import com.mipay.sme.security.rbac.Verb;
import com.mipay.sme.service.OrgRoleCeilingService;
import com.mipay.sme.service.OrgTreeService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An org's roles and access (docs/plans/extended-rbac.md ER3):
 * <ul>
 *   <li>its role ceiling: read by anyone who sees the org; set by an admin of an org above it
 *       or platform staff, never by the org itself (X5);</li>
 *   <li>its org-defined roles and the role assignments of its users, kept in RBAC and written
 *       through the mesh on behalf of the acting user. RBAC applies the ceiling, SoD and
 *       four-eyes to every write.</li>
 * </ul>
 * Until cut-over P6 ({@code app.rbac.mode=enforce}) the core authorizes the actor itself
 * (org ADMIN of the org or an org above it, or platform staff) and tells RBAC so; once RBAC
 * enforces, RBAC checks the actor's own cells.
 */
@RestController
@RequestMapping("/api/orgs/{id}")
@RequiredArgsConstructor
public class OrgAccessController {

    private final OrgTreeService tree;
    private final OrgClosureRepository closureRepo;
    private final UserRepository userRepo;
    private final OrgRoleCeilingService ceilings;
    private final RbacMeshClient rbac;

    @Value("${app.rbac.mode:shadow}")
    private String rbacMode;

    // ---------------- authority ----------------

    private boolean coreAuthorized() {
        return !"enforce".equalsIgnoreCase(rbacMode);
    }

    /** Platform staff, or an ADMIN of this org or of an org above it. */
    private Current.Principal requireManager(String orgId) {
        Current.Principal caller = Current.get();
        tree.visible(caller, orgId);
        if (tree.platformStaff(caller)) return caller;
        Organization own = tree.orgOf(caller);
        boolean ok = caller.isOrgAdmin() && own != null
                && (own.getId().equals(orgId) || closureRepo.existsByAncestorIdAndDescendantId(own.getId(), orgId));
        if (!ok) throw new ForbiddenException("ORG_ADMIN_REQUIRED", "Only an admin of this org or an org above it manages its roles");
        return caller;
    }

    private Map<String, Object> onBehalf(Current.Principal caller) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("actor", caller.userId());
        b.put("core_authorized", coreAuthorized());
        return b;
    }

    // ---------------- role ceiling ----------------

    @Perm(resource = "org.rights", verb = Verb.READ, tenantSource = TenantSource.PATH, tenant = "#id",
          description = "View an org's role ceiling (PATH tenant)")
    @GetMapping("/role-ceiling")
    public Map<String, Object> ceiling(@PathVariable String id) {
        tree.visible(Current.get(), id);
        return ceilings.view(id);
    }

    @Perm(resource = "org.rights", verb = Verb.ADMINISTER, tenantSource = TenantSource.PATH, tenant = "#id",
          description = "Set the roles an org below may use and hand down (PATH tenant)")
    @PutMapping("/role-ceiling")
    public Map<String, Object> setCeiling(@PathVariable String id, @RequestBody Dtos.RoleCeilingRequest body) {
        Current.Principal caller = Current.get();
        tree.visible(caller, id);
        Organization own = tree.orgOf(caller);
        if (!tree.platformStaff(caller)
                && !OrgRoleCeilingService.isStrictAncestorAdmin(caller, own == null ? null : own.getId(), id, closureRepo)) {
            throw new ForbiddenException("ANCESTOR_REQUIRED", "Only an admin of an org above it, or platform staff, sets an org's role ceiling");
        }
        if (Boolean.TRUE.equals(body.inherit())) return ceilings.clear(id, caller.userId(), caller.role());
        return ceilings.set(id, body.roles(), body.delegableRoles(), caller.userId(), caller.role());
    }

    // ---------------- roles (global within the ceiling + org-defined) ----------------

    @Perm(resource = "rbac.role", verb = Verb.READ, tenantSource = TenantSource.PATH, tenant = "#id",
          description = "The roles an org may use, its ceiling and its own roles (PATH tenant)")
    @GetMapping("/roles")
    public Map<String, Object> roles(@PathVariable String id) {
        tree.visible(Current.get(), id);
        return rbac.rolesFor(id);
    }

    /** {name (slug), description, baseRoles[], permissions["row:verb"]} */
    public record OrgRoleBody(String name, String description, List<String> baseRoles, List<String> permissions) { }

    private Map<String, Object> roleBody(Current.Principal caller, OrgRoleBody b) {
        Map<String, Object> m = onBehalf(caller);
        m.put("name", b.name());
        m.put("description", b.description());
        m.put("base_roles", b.baseRoles());
        m.put("permissions", b.permissions());
        return m;
    }

    @Perm(resource = "rbac.role", verb = Verb.CREATE, tenantSource = TenantSource.PATH, tenant = "#id",
          description = "Create an org-defined role within the org's ceiling (PATH tenant)")
    @PostMapping("/roles")
    public Map<String, Object> createRole(@PathVariable String id, @RequestBody OrgRoleBody body) {
        return rbac.createRole(id, roleBody(requireManager(id), body));
    }

    @Perm(resource = "rbac.role", verb = Verb.MODIFY, tenantSource = TenantSource.PATH, tenant = "#id",
          description = "Change an org-defined role (PATH tenant)")
    @PutMapping("/roles/{name}")
    public Map<String, Object> updateRole(@PathVariable String id, @PathVariable String name, @RequestBody OrgRoleBody body) {
        return rbac.updateRole(id, name, roleBody(requireManager(id), body));
    }

    @Perm(resource = "rbac.role", verb = Verb.DELETE, tenantSource = TenantSource.PATH, tenant = "#id",
          description = "Delete an org-defined role with no assignments (PATH tenant)")
    @DeleteMapping("/roles/{name}")
    public Map<String, Object> deleteRole(@PathVariable String id, @PathVariable String name) {
        return rbac.deleteRole(id, name, onBehalf(requireManager(id)));
    }

    // ---------------- assignments of the org's users ----------------

    @Perm(resource = "org.user", verb = Verb.READ, tenantSource = TenantSource.PATH, tenant = "#id",
          description = "The users of an org, for role assignment (PATH tenant)")
    @GetMapping("/members")
    public List<Map<String, Object>> members(@PathVariable String id) {
        tree.visible(Current.get(), id);
        return userRepo.findByOrgId(id).stream().map(u -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("name", u.getName());
            m.put("username", u.getUsername());
            m.put("status", u.getStatus() == null ? null : u.getStatus().name());
            return m;
        }).toList();
    }

    @Perm(resource = "rbac.assignment", verb = Verb.READ, tenantSource = TenantSource.PATH, tenant = "#id",
          description = "Role assignments in an org, with the users' names (PATH tenant)")
    @GetMapping("/role-assignments")
    public List<Map<String, Object>> assignments(@PathVariable String id) {
        tree.visible(Current.get(), id);
        Map<String, User> users = new HashMap<>();
        for (User u : userRepo.findByOrgId(id)) users.put(u.getId(), u);
        return rbac.assignments(id).stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>(a);
            User u = users.get(String.valueOf(a.get("subject")));
            m.put("userName", u == null ? null : u.getName());
            m.put("username", u == null ? null : u.getUsername());
            return m;
        }).toList();
    }

    /** {userId, role, scope (own|subtree), expiresAt (RFC 3339), reason} */
    public record AssignBody(String userId, String role, String scope, String expiresAt, String reason) { }

    @Perm(resource = "rbac.assignment", verb = Verb.CREATE, tenantSource = TenantSource.PATH, tenant = "#id",
          description = "Give a user of the org a role (four-eyes by the role's risk) (PATH tenant)")
    @PostMapping("/role-assignments")
    public Map<String, Object> assign(@PathVariable String id, @RequestBody AssignBody body) {
        Current.Principal caller = requireManager(id);
        User u = body.userId() == null ? null : userRepo.findById(body.userId()).orElse(null);
        if (u == null || !id.equals(u.getOrgId())) throw new NotFoundException("User not found in this org");
        Map<String, Object> m = onBehalf(caller);
        m.put("subject", u.getId());
        m.put("role", body.role());
        m.put("scope", body.scope());
        m.put("expires_at", body.expiresAt());
        m.put("reason", body.reason());
        return rbac.assign(id, m);
    }

    public record ReasonBody(String reason) { }

    @Perm(resource = "rbac.assignment", verb = Verb.ADMINISTER, tenantSource = TenantSource.PATH, tenant = "#id",
          description = "Revoke a role assignment in the org (PATH tenant)")
    @PostMapping("/role-assignments/{assignmentId}/revoke")
    public Map<String, Object> revoke(@PathVariable String id, @PathVariable String assignmentId,
                                      @RequestBody(required = false) ReasonBody body) {
        Map<String, Object> m = onBehalf(requireManager(id));
        m.put("reason", body == null ? "" : body.reason());
        return rbac.revoke(id, assignmentId, m);
    }

    @Perm(resource = "rbac.assignment", verb = Verb.READ, tenantSource = TenantSource.PATH, tenant = "#id",
          description = "Pending role requests in the org the caller may decide (PATH tenant)")
    @GetMapping("/role-approvals")
    public List<Map<String, Object>> approvals(@PathVariable String id) {
        Current.Principal caller = Current.get();
        tree.visible(caller, id);
        return rbac.approvals(id, caller.userId());
    }

    public record DecideBody(Boolean approve, String reason) { }

    /** Four-eyes: RBAC checks the approver is eligible and is neither the requester nor the grantee. */
    @Perm(resource = "rbac.assignment", verb = Verb.AUTHORIZE, tenantSource = TenantSource.PATH, tenant = "#id",
          description = "Approve or reject a role request in the org (PATH tenant)")
    @PostMapping("/role-approvals/{assignmentId}")
    public Map<String, Object> decide(@PathVariable String id, @PathVariable String assignmentId, @RequestBody DecideBody body) {
        Current.Principal caller = Current.get();
        tree.visible(caller, id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("actor", caller.userId());
        m.put("approve", body.approve());
        m.put("reason", body.reason());
        return rbac.decide(id, assignmentId, m);
    }

    // ---------------- errors from RBAC keep their status and code ----------------

    @ExceptionHandler(RbacMeshClient.RbacMeshException.class)
    public org.springframework.http.ResponseEntity<ProblemDetail> rbacError(RbacMeshClient.RbacMeshException e) {
        HttpStatus st = HttpStatus.resolve(e.status);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(st == null ? HttpStatus.BAD_GATEWAY : st, e.getMessage());
        pd.setProperty("code", e.code);
        return org.springframework.http.ResponseEntity.status(pd.getStatus()).body(pd);
    }
}
