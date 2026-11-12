package com.mipay.sme.web;

import com.mipay.sme.common.BadRequestException;
import com.mipay.sme.common.NotFoundException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.org.Organization;
import com.mipay.sme.repository.OnboardingRequestRepository;
import com.mipay.sme.repository.OrganizationRepository;
import com.mipay.sme.repository.OrgClosureRepository;
import com.mipay.sme.security.Current;
import com.mipay.sme.security.rbac.Perm;
import com.mipay.sme.security.rbac.TenantSource;
import com.mipay.sme.security.rbac.Verb;
import com.mipay.sme.service.OrgOnboardingService;
import com.mipay.sme.service.OrgTreeService;
import com.mipay.sme.service.OrgUserService;
import com.mipay.sme.web.Dtos;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Org-tree API (org-hierarchy spec): me-scoped views [D4/M10], generic
 * submitOrg, staff, deactivation, admin recovery, decisions and holds.
 */
@RestController
@RequestMapping("/api/orgs")
@RequiredArgsConstructor
public class OrgController {

    private final OrgTreeService tree;
    private final OrgOnboardingService onboarding;
    private final OrgUserService orgUsers;
    private final OrganizationRepository orgRepo;
    private final OnboardingRequestRepository requestRepo;
    private final com.mipay.sme.service.OrgSuspensionService suspension;
    private final com.mipay.sme.service.OrgRightsService orgRights;
    private final com.mipay.sme.service.OrgCallerService callerService;
    private final OrgClosureRepository closureRepo;
    private final com.mipay.sme.service.OrgRoleCeilingService roleCeilings;

    // ---------------- me-scoped views [D4] ----------------

    @Perm(resource = "org.tree", verb = Verb.READ, tenantSource = TenantSource.SELF, tenant = "null",
          description = "View caller's own org")
    @GetMapping("/me")
    public Dtos.OrgView me() {
        return onboarding.toView(tree.ownOrg(Current.get()));
    }

    /** Whole subtree in one closure query, nested in memory [D4]. */
    @Perm(resource = "org.tree", verb = Verb.READ, tenantSource = TenantSource.SELF, tenant = "null",
          description = "View caller's org subtree")
    @GetMapping("/me/tree")
    public Dtos.OrgNodeRow meTree(@RequestParam(required = false) Integer maxDepth,
                                  @RequestParam(required = false) String status) {
        String orgId = tree.ownOrg(Current.get()).getId();
        return tree.tree(Current.get(), orgId, maxDepth, status);
    }

    /** Flat, paged descendants ([D4] fallback for big subtrees). */
    @Perm(resource = "org.tree", verb = Verb.READ, tenantSource = TenantSource.SELF, tenant = "null",
          description = "View caller's org descendants")
    @GetMapping("/me/descendants")
    public Page<Dtos.OrgDescendantRow> meDescendants(@RequestParam(required = false) String type,
                                                     @RequestParam(required = false) String status,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "50") int size) {
        String orgId = tree.ownOrg(Current.get()).getId();
        return tree.descendants(Current.get(), orgId, type, status, page, size);
    }

    /** Ancestor chain, root first; platform staff may query anyone visible. */
    @Perm(resource = "org.tree", verb = Verb.READ, tenantSource = TenantSource.SELF, tenant = "null",
          description = "View caller's org ancestor path")
    @GetMapping("/me/path")
    public List<Dtos.OrgPathRow> mePath() {
        String orgId = tree.ownOrg(Current.get()).getId();
        return tree.path(Current.get(), orgId);
    }

    // ---------------- generic submitOrg [M10] ----------------

    @Perm(resource = "org.onboarding", verb = Verb.CREATE, tenantSource = TenantSource.SELF, tenant = "null",
          description = "Onboard a child organization")
    @PostMapping("/me/children")
    public Dtos.OrgDecisionResult submitChild(@RequestBody Dtos.SubmitOrgRequest req) {
        Enums.OrgType childType;
        try {
            childType = Enums.OrgType.valueOf(req.type().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BadRequestException("ORG_TYPE_REQUIRED",
                    "type must be one of MANUFACTURER, DISTRIBUTOR, SUPPLIER, MERCHANT, LENDER");
        }
        return onboarding.submitOrg(Current.get(), childType,
                new OrgOnboardingService.OrgSpec(req.name(), req.registrationNumber(), req.businessType(),
                        req.ownerName(), req.phone(), req.username(), req.email(),
                        req.bankName(), req.accountNumber(), req.accountHolder(), req.licenceRef(),
                        req.supplierType(), req.ownerIdNumber(),
                        toTypes(req.rightsDirect()), toTypes(req.rightsCeiling()),
                        req.roleCeiling(), req.roleDelegable()));
    }

    /** {type, kyc: the whole KYC document, licenceRef?, rightsDirect?, rightsCeiling?, roleCeiling?, roleDelegable?} */
    public record OnboardWithKycRequest(String type, Map<String, Object> kyc, String licenceRef,
                                        List<String> rightsDirect, List<String> rightsCeiling,
                                        List<String> roleCeiling, List<String> roleDelegable) { }

    /**
     * The onboarding wizard's one call: creates the org with UBO Person 1 as its administrator,
     * saves and submits its KYC, all or nothing.
     */
    @Perm(resource = "org.onboarding", verb = Verb.CREATE, tenantSource = TenantSource.SELF, tenant = "null",
          description = "Onboard a child organization with its complete KYC (all or nothing)")
    @PostMapping("/me/children/with-kyc")
    public Map<String, Object> submitChildWithKyc(@RequestBody OnboardWithKycRequest req) {
        Enums.OrgType childType;
        try {
            childType = Enums.OrgType.valueOf(req.type().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BadRequestException("ORG_TYPE_REQUIRED",
                    "type must be one of MANUFACTURER, DISTRIBUTOR, SUPPLIER, MERCHANT, LENDER");
        }
        return onboarding.onboardWithKyc(Current.get(), childType, req.kyc(), req.licenceRef(),
                toTypes(req.rightsDirect()), toTypes(req.rightsCeiling()), req.roleCeiling(), req.roleDelegable());
    }

    // ---------------- staff [C3/H9] ----------------

    @Perm(resource = "org.user", verb = Verb.READ, tenantSource = TenantSource.SELF, tenant = "null",
          description = "View caller's org users")
    @GetMapping("/me/users")
    public List<Dtos.OrgUserRow> meUsers() {
        String orgId = tree.ownOrg(Current.get()).getId();
        return orgUsers.usersOf(orgId).stream()
                .map(u -> new Dtos.OrgUserRow(u.getId(), u.getName(), u.getUsername(), u.getRole().name(),
                        u.getOrgRole() == null ? null : u.getOrgRole().name(), u.getStatus().name(),
                        u.getPhone(), u.getEmail()))
                .toList();
    }

    @Perm(resource = "org.user", verb = Verb.CREATE, tenantSource = TenantSource.SELF, tenant = "null",
          description = "Add staff to caller's org")
    @PostMapping("/me/users")
    public Dtos.OrgStaffView addStaff(@RequestBody Dtos.StaffRequest req) {
        return onboarding.addStaff(Current.get(), req);
    }

    /** Spec path [H9]: any ancestor admin (or platform) deactivates a user. */
    @Perm(resource = "org.user", verb = Verb.DELETE, tenantSource = TenantSource.PATH, tenant = "#id",
          description = "Deactivate a user (PATH tenant)")
    @PostMapping("/users/{id}/deactivate")
    public Dtos.Message deactivate(@PathVariable String id) {
        return onboarding.deactivateUser(Current.get(), id);
    }

    // ---------------- org-scoped queries ----------------

    @Perm(resource = "org.tree", verb = Verb.READ, tenantSource = TenantSource.PATH, tenant = "#id",
          description = "View an org (PATH tenant)")
    @GetMapping("/{id}")
    public Dtos.OrgView org(@PathVariable String id) {
        Current.Principal caller = Current.get();
        tree.visible(caller, id);
        return onboarding.toView(orgRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("Organization not found")));
    }

    @Perm(resource = "org.tree", verb = Verb.READ, tenantSource = TenantSource.PATH, tenant = "#id",
          description = "View an org's descendants (PATH tenant)")
    @GetMapping("/{id}/descendants")
    public Page<Dtos.OrgDescendantRow> descendants(@PathVariable String id,
                                                   @RequestParam(required = false) String type,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "50") int size) {
        return tree.descendants(Current.get(), id, type, status, page, size);
    }

    @Perm(resource = "org.tree", verb = Verb.READ, tenantSource = TenantSource.PATH, tenant = "#id",
          description = "View an org's ancestor path (PATH tenant)")
    @GetMapping("/{id}/path")
    public List<Dtos.OrgPathRow> path(@PathVariable String id) {
        return tree.path(Current.get(), id);
    }

    // ---------------- admin recovery (ADMIN_RECOVERY request) ----------------

    @Perm(resource = "org.onboarding", verb = Verb.AUTHORIZE, tenantSource = TenantSource.PATH, tenant = "#id",
          description = "Request admin recovery for an org (PATH tenant)")
    @PostMapping("/{id}/admins")
    public Dtos.OrgDecisionResult requestAdminRecovery(@PathVariable String id,
                                                       @RequestBody Dtos.AdminRecoveryRequest req) {
        return onboarding.requestAdminRecovery(Current.get(), id, req);
    }

    // ---------------- rights ([D1/H7]) ----------------

    @Perm(resource = "org.rights", verb = Verb.READ, tenantSource = TenantSource.SELF, tenant = "null",
          description = "View grantable child types for caller's org")
    @GetMapping("/me/grantable")
    public Dtos.GrantableView grantable(@RequestParam(required = false) String childType) {
        String orgId = tree.ownOrg(Current.get()).getId();
        java.util.Map<String, String> desc = new java.util.HashMap<>();
        roleCeilings.catalogue().forEach(r -> desc.put(r.name(), r.description()));
        java.util.Set<String> base = roleCeilings.baseline();
        List<Dtos.GrantableRole> roles = new java.util.TreeSet<>(roleCeilings.handDown(orgId)).stream()
                .map(n -> new Dtos.GrantableRole(n, desc.getOrDefault(n, ""), base.contains(n))).toList();
        return orgRights.grantable(Current.get(), orgId).withRoles(roles, List.copyOf(base));
    }

    @Perm(resource = "org.rights", verb = Verb.READ, tenantSource = TenantSource.PATH, tenant = "#id",
          description = "View an org's rights (PATH tenant)")
    @GetMapping("/{id}/rights")
    public Dtos.OrgRightsView rights(@PathVariable String id) {
        Current.Principal caller = Current.get();
        tree.visible(caller, id);
        return orgRights.view(id);
    }

    /** Narrow only ([D1]); an ancestor's ADMIN or platform staff. */
    @Perm(resource = "org.rights", verb = Verb.ADMINISTER, tenantSource = TenantSource.PATH, tenant = "#id",
          description = "Narrow an org's rights (PATH tenant)")
    @PutMapping("/{id}/rights")
    public Dtos.OrgRightsView narrowRights(@PathVariable String id,
                                           @RequestBody Dtos.RightsPutRequest body) {
        Current.Principal caller = Current.get();
        tree.visible(caller, id);
        assertNarrowAuthority(caller, id);
        return orgRights.narrow(id, toTypes(body.rightsDirect()), toTypes(body.rightsCeiling()), caller);
    }

    private void assertNarrowAuthority(Current.Principal caller, String orgId) {
        if (tree.platformStaff(caller)) return;
        Organization callerOrg = tree.orgOf(caller);
        boolean ancestorAdmin = callerOrg != null && caller.isOrgAdmin()
                && closureRepo != null;
        if (ancestorAdmin) {
            boolean ancestor = callerOrg.getId().equals(orgId)
                    || closureRepo.existsByAncestorIdAndDescendantId(callerOrg.getId(), orgId);
            if (ancestor) return;
        }
        throw new com.mipay.sme.common.ForbiddenException("ANCESTOR_REQUIRED",
                "Only an ancestor ADMIN or platform staff may narrow rights");
    }

    // ---------------- decisions ----------------

    /** Pending requests relevant to the caller (own-org parent queue, platform sees all). */
    @Perm(resource = "org.onboarding", verb = Verb.READ, tenantSource = TenantSource.SELF, tenant = "null",
          description = "View onboarding request queue for caller's org")
    @GetMapping("/onboarding")
    public List<Dtos.OnboardingRequestRow> queue(
            @RequestParam(defaultValue = "PENDING_REVIEW") String status) {
        Current.Principal caller = Current.get();
        Enums.OnboardingStatus st = Enums.OnboardingStatus.valueOf(status.toUpperCase());
        return requestRepo.findByStatusOrderByCreatedAtDesc(st).stream()
                .filter(r -> tree.platformStaff(caller)
                        || (caller.orgId() != null && caller.orgId().equals(r.getParentOrgId())))
                .map(r -> new Dtos.OnboardingRequestRow(r.getId(), r.getKind().name(), r.getStatus().name(),
                        r.getOrgId(), orgName(r.getOrgId()), orgType(r.getOrgId()), r.getParentOrgId(),
                        r.getRequestedBy(), r.getRequestedByRole(), r.getRequestedByOrgId(),
                        r.getReviewedBy(), r.getStatus() == null ? null : r.getStatus().name(),
                        metaPart(r.getMeta(), "direct="), metaPart(r.getMeta(), "ceiling="),
                        r.getNote(), r.getMeta(), r.getCreatedAt(), r.getReviewedAt()))
                .toList();
    }

    @Perm(resource = "org.onboarding", verb = Verb.AUTHORIZE, tenantSource = TenantSource.PATH, tenant = "#id",
          description = "Decide an onboarding request (PATH tenant)")
    @PostMapping("/onboarding/{id}/decide")
    public Dtos.OrgDecisionResult decide(@PathVariable String id, @RequestBody Dtos.OrgDecideRequest req) {
        return onboarding.decide(id, Current.get(), Boolean.TRUE.equals(req.approve()),
                req.note(), req.sanctionsResult());
    }

    private String orgName(String orgId) {
        return orgRepo.findById(orgId).map(o -> o.getName()).orElse(null);
    }

    private String orgType(String orgId) {
        return orgRepo.findById(orgId).map(o -> o.getOrgType().name()).orElse(null);
    }

    private String metaPart(String meta, String key) {
        if (meta == null) return null;
        for (String part : meta.split(";")) {
            if (part.startsWith(key)) return part.substring(key.length());
        }
        return null;
    }

    private java.util.Set<com.mipay.sme.domain.Enums.OrgType> toTypes(List<String> names) {
        if (names == null) return null;
        return names.stream().filter(n -> n != null && !n.isBlank())
                .map(n -> com.mipay.sme.domain.Enums.OrgType.valueOf(n.toUpperCase()))
                .collect(java.util.stream.Collectors.toSet());
    }

    // ---------------- suspension (ancestor ADMIN or platform) ----------------

    @Perm(resource = "org.suspension", verb = Verb.ADMINISTER, tenantSource = TenantSource.PATH, tenant = "#id",
          description = "Suspend an org (PATH tenant)")
    @PostMapping("/{id}/suspend")
    public Dtos.SuspensionRow suspend(@PathVariable String id,
                                      @RequestBody(required = false) Dtos.HoldRequest body) {
        String reason = body == null || body.reason() == null ? "suspended" : body.reason();
        return suspension.suspend(id, reason, Current.get());
    }

    @Perm(resource = "org.suspension", verb = Verb.ADMINISTER, tenantSource = TenantSource.PATH, tenant = "#sid",
          description = "Lift a suspension (PATH tenant)")
    @PostMapping("/suspensions/{sid}/lift")
    public List<Dtos.SuspensionRow> lift(@PathVariable String sid) {
        return suspension.lift(sid, Current.get());
    }
}
