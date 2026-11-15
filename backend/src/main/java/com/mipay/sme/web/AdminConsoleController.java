package com.mipay.sme.web;

import com.mipay.sme.common.ForbiddenException;
import com.mipay.sme.common.ListQuery;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.domain.org.OrgAccessSetting;
import com.mipay.sme.domain.org.Organization;
import com.mipay.sme.repository.OrgClosureRepository;
import com.mipay.sme.repository.OrganizationRepository;
import com.mipay.sme.repository.UserRepository;
import com.mipay.sme.security.Current;
import com.mipay.sme.security.rbac.Perm;
import com.mipay.sme.security.rbac.RbacMeshClient;
import com.mipay.sme.security.rbac.TenantSource;
import com.mipay.sme.security.rbac.Verb;
import com.mipay.sme.service.OrgFeatureService;
import com.mipay.sme.service.OrgRoleCeilingService;
import com.mipay.sme.service.OrgTreeService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The admin console's lists and the org access tab (docs/plans/admin-console.md):
 * <ul>
 *   <li>the organizations list (KYC status, users, preset, warning badges), searched by the server;</li>
 *   <li>an org's people with the roles they hold, searched by the server;</li>
 *   <li>an org's features ("What it can do"): read, and set by an admin of an org above it or
 *       platform staff (never the org itself, as for its role ceiling).</li>
 * </ul>
 * Every list speaks the {@link ListQuery} contract (q, sort, filters, page, size).
 */
@RestController
@RequiredArgsConstructor
public class AdminConsoleController {
    private final OrganizationRepository orgs;
    private final UserRepository users;
    private final OrgClosureRepository closureRepo;
    private final OrgTreeService tree;
    private final OrgFeatureService features;
    private final RbacMeshClient rbac;

    @Value("${app.rbac.mode:shadow}")
    private String rbacMode;

    // ------------------------------------------------------------------ organizations

    public record OrgRow(String id, String name, String type, String status, String kycStatus, String parentId,
                         String parentName, long users, String preset, int features, List<String> warnings,
                         Instant createdAt) { }

    static final ListQuery.Fields<OrgRow> ORG_FIELDS = ListQuery.Fields.<OrgRow>of()
            .text("name", null, OrgRow::name)
            .field("type", null, OrgRow::type)
            .field("status", null, OrgRow::status)
            .field("kycStatus", null, OrgRow::kycStatus)
            .text("parentName", null, OrgRow::parentName)
            .field("users", null, OrgRow::users)
            .field("preset", null, OrgRow::preset)
            .field("features", null, OrgRow::features)
            .field("warnings", null, OrgRow::warnings)
            .field("createdAt", null, OrgRow::createdAt)
            .searchOnly("id", null, OrgRow::id)
            .defaultSort("name", true);

    /**
     * Every organization with its KYC status, user count, preset and warnings. Filters:
     * type, status, kycStatus, preset, warnings (e.g. {@code warnings=Can't add users}).
     * Enriched rows are assembled by the server, then searched, sorted and paged here.
     */
    @Perm(resource = "admin.org_directory", verb = Verb.READ, tenantSource = TenantSource.NONE,
          description = "Search organizations with KYC status, users and access warnings")
    @GetMapping("/api/admin/organizations/search")
    public ListQuery.Page<OrgRow> organizations(@RequestParam Map<String, String> params) {
        ListQuery query = ListQuery.parse(params, ORG_FIELDS);
        List<Organization> all = orgs.findAll();
        Map<String, String> names = new HashMap<>();
        for (Organization o : all) names.put(o.getId(), o.getName());
        Map<String, Long> counts = new HashMap<>();
        for (Object[] r : users.countByOrg()) counts.put(String.valueOf(r[0]), ((Number) r[1]).longValue());
        Map<String, OrgAccessSetting> settings = features.settingsOf(names.keySet());

        List<OrgRow> rows = new ArrayList<>(all.size());
        for (Organization o : all) {
            OrgAccessSetting s = settings.get(o.getId());
            rows.add(new OrgRow(o.getId(), o.getName(), o.getOrgType().name(),
                    o.getLifecycle() == null ? null : o.getLifecycle().name(),
                    o.getKycStatus() == null ? null : o.getKycStatus().name(),
                    o.getParentId(), o.getParentId() == null ? null : names.get(o.getParentId()),
                    counts.getOrDefault(o.getId(), 0L), s == null ? null : s.getPresetCode(),
                    s == null || s.getFeatures().isBlank() ? 0 : s.getFeatures().split(",").length,
                    features.warnings(o, s), o.getCreatedAt()));
        }
        return query.apply(rows, ORG_FIELDS);
    }

    // ------------------------------------------------------------------ people

    public record PersonRow(String id, String name, String username, String email, String phone, String orgRole,
                            String status, List<String> roles, List<String> pendingRoles) { }

    static final ListQuery.Fields<PersonRow> PEOPLE_FIELDS = ListQuery.Fields.<PersonRow>of()
            .text("name", null, PersonRow::name)
            .text("username", null, PersonRow::username)
            .searchOnly("email", null, PersonRow::email)
            .searchOnly("phone", null, PersonRow::phone)
            .field("orgRole", null, PersonRow::orgRole)
            .field("status", null, PersonRow::status)
            .text("roles", null, PersonRow::roles)
            .field("pendingRoles", null, PersonRow::pendingRoles)
            .defaultSort("name", true);

    /**
     * An org's people with the roles they hold (ACTIVE) and have requested (PENDING), from RBAC.
     * Filters: orgRole, status, roles (e.g. {@code roles=organization_access}).
     */
    @Perm(resource = "org.user", verb = Verb.READ, tenantSource = TenantSource.PATH, tenant = "#id",
          description = "Search an org's people with their roles (PATH tenant)")
    @GetMapping("/api/orgs/{id}/people")
    public ListQuery.Page<PersonRow> people(@PathVariable String id, @RequestParam Map<String, String> params) {
        tree.visible(Current.get(), id);
        ListQuery query = ListQuery.parse(params, PEOPLE_FIELDS);
        Map<String, List<String>> active = new HashMap<>(), pending = new HashMap<>();
        if (rbac.configured()) {
            for (Map<String, Object> a : rbac.assignments(id)) {
                String st = String.valueOf(a.get("status"));
                Map<String, List<String>> into = "ACTIVE".equalsIgnoreCase(st) ? active
                        : "PENDING".equalsIgnoreCase(st) ? pending : null;
                if (into != null) into.computeIfAbsent(String.valueOf(a.get("subject")), k -> new ArrayList<>())
                        .add(slug(String.valueOf(a.get("role"))));
            }
        }
        List<PersonRow> rows = new ArrayList<>();
        for (User u : users.findByOrgId(id)) {
            rows.add(new PersonRow(u.getId(), u.getName(), u.getUsername(), u.getEmail(), u.getPhone(),
                    u.getOrgRole() == null ? null : u.getOrgRole().name(),
                    u.getStatus() == null ? null : u.getStatus().name(),
                    active.getOrDefault(u.getId(), List.of()), pending.getOrDefault(u.getId(), List.of())));
        }
        return query.apply(rows, PEOPLE_FIELDS);
    }

    /** "organization_access@org-1" → "organization_access" (an org's own roles carry its id). */
    static String slug(String role) {
        int at = role.indexOf('@');
        return at < 0 ? role : role.substring(0, at);
    }

    // ------------------------------------------------------------------ features

    @Perm(resource = "org.rights", verb = Verb.READ, tenantSource = TenantSource.PATH, tenant = "#id",
          description = "What an org can do: its features (org-portal menus) and presets (PATH tenant)")
    @GetMapping("/api/orgs/{id}/features")
    public OrgFeatureService.AccessView featuresOf(@PathVariable String id) {
        tree.visible(Current.get(), id);
        return features.view(id);
    }

    /** {preset?: code, features?: [menu ids]}: features win; a preset alone means its features. */
    public record FeaturesBody(String preset, List<String> features) { }

    @Perm(resource = "org.rights", verb = Verb.ADMINISTER, tenantSource = TenantSource.PATH, tenant = "#id",
          description = "Switch an org's features on or off (PATH tenant)")
    @PutMapping("/api/orgs/{id}/features")
    public OrgFeatureService.AccessView setFeatures(@PathVariable String id, @RequestBody FeaturesBody body) {
        Current.Principal caller = Current.get();
        tree.visible(caller, id);
        Organization own = tree.orgOf(caller);
        if (!tree.platformStaff(caller)
                && !OrgRoleCeilingService.isStrictAncestorAdmin(caller, own == null ? null : own.getId(), id, closureRepo)) {
            throw new ForbiddenException("ANCESTOR_REQUIRED", "Only an admin of an org above it, or platform staff, sets what an org can do");
        }
        return features.set(id, body == null ? null : body.preset(), body == null ? null : body.features(),
                caller.userId(), !"enforce".equalsIgnoreCase(rbacMode));
    }
}
