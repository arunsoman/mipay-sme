package com.mipay.sme.service.migration;

import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.credit.Lender;
import com.mipay.sme.domain.core.Merchant;
import com.mipay.sme.domain.core.Supplier;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.domain.org.Organization;
import com.mipay.sme.repository.LenderRepository;
import com.mipay.sme.repository.MerchantRepository;
import com.mipay.sme.repository.OrganizationRepository;
import com.mipay.sme.repository.SupplierRepository;
import com.mipay.sme.repository.UserRepository;
import com.mipay.sme.security.auth.UserLifecycleService;
import com.mipay.sme.service.OrgProvisioningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Org-binding backfill (cut-over P3.10a, decision D4): every user must have a home org
 * before {@code V15b} makes {@code users.org_id NOT NULL} with an FK to organizations.
 *
 * <p>Binding rules mirror {@link OrgProvisioningService#ensureEntityOrg}:</p>
 * <ul>
 *   <li>a user with a platform role (TPTS_OPS / MINDLABS_ADMIN) is bound to the PLATFORM org;</li>
 *   <li>a user owning a merchant / supplier / lender entity is bound to that entity's org
 *       (get-or-create, status ACTIVE, parented at the platform root);</li>
 *   <li>everybody else is <b>listed for a manual decision</b> — never guessed (D4).</li>
 * </ul>
 *
 * <p>Admin-only, safe to re-run: bound users are skipped, the platform and entity-org rules
 * are deterministic. The schema migration itself stays in {@code V15b} ( Flyway owns the
 * schema); this service is the operator/UAT tool that runs before it.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrgBindingBackfill {

    private final UserRepository users;
    private final OrganizationRepository orgs;
    private final MerchantRepository merchants;
    private final SupplierRepository suppliers;
    private final LenderRepository lenders;
    private final OrgProvisioningService provisioning;
    private final UserLifecycleService lifecycle;

    public record Row(String userId, String phone, String role, String status,
                      String resolution, String orgId, String orgName, String note) { }

    public record Report(int unbound, List<Row> rows, List<String> unresolved, boolean clean) { }

    /** Dry-run: what would be bound where; unresolved users are listed, never guessed (D4). */
    @Transactional(readOnly = true)
    public Report report() {
        List<Row> rows = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (User u : users.findByOrgIdIsNull().stream().sorted(Comparator.comparing(User::getPhone)).toList()) {
            Resolution r = resolve(u);
            if (r.org() == null) unresolved.add(u.getId());
            rows.add(row(u, r));
        }
        return new Report(rows.size(), rows, unresolved, unresolved.isEmpty());
    }

    /** Applies the deterministic binds; returns the leftover report (must be empty before V15b). */
    @Transactional
    public Report run() {
        List<Row> rows = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (User u : users.findByOrgIdIsNull().stream().sorted(Comparator.comparing(User::getPhone)).toList()) {
            Resolution r = resolve(u);
            if (r.org() == null) {
                unresolved.add(u.getId());
                rows.add(row(u, r));
                continue;
            }
            Enums.OrgMemberRole orgRole = r.orgRole() != null ? r.orgRole() : u.getOrgRole();
            // bindToOrg only binds unbound users and refuses org moves; the org rule set is the
            // same as ensureEntityOrg's, so this cannot strand a user on an ineligible org
            lifecycle.bindToOrg(u.getId(), r.org().getId(),
                    orgRole == null ? Enums.OrgMemberRole.ADMIN : orgRole, "system:binding");
            log.info("[org-binding-backfill] user {} ({}) bound to org {} as {}", u.getId(),
                    u.getRole(), r.org().getId(), orgRole);
            rows.add(row(users.findById(u.getId()).orElse(u), r));
        }
        Report report = new Report(rows.size(), rows, unresolved, unresolved.isEmpty());
        if (!report.clean()) log.warn("[org-binding-backfill] {} user(s) unresolved for manual decision", unresolved.size());
        return report;
    }

    private record Resolution(Organization org, Enums.OrgMemberRole orgRole, String resolution, String note) { }
    private static final Resolution NONE = new Resolution(null, null, "UNRESOLVED", null);

    private Resolution resolve(User u) {
        // platform staff: members of the PLATFORM org (their authority comes from RBAC platform roles)
        if (u.getRole() == Enums.UserRole.TPTS_OPS || u.getRole() == Enums.UserRole.MINDLABS_ADMIN) {
            Organization platform = provisioning.ensurePlatformRoot();
            return new Resolution(platform, u.getOrgRole(), "PLATFORM", "platform role binds at the root");
        }
        // entity ownership: merchant / supplier / lender (ensureEntityOrg's binding rule)
        Merchant m = merchants.findByUserId(u.getId()).orElse(null);
        if (m != null) return new Resolution(provisioning.ensureEntityOrg(Enums.OrgType.MERCHANT,
                m.getId(), m.getBusinessName()), Enums.OrgMemberRole.ADMIN, "ENTITY_ORG", "merchant " + m.getId());
        Supplier s = suppliers.findByUserId(u.getId()).orElse(null);
        if (s != null) return new Resolution(provisioning.ensureEntityOrg(Enums.OrgType.SUPPLIER,
                s.getId(), s.getBusinessName()), Enums.OrgMemberRole.ADMIN, "ENTITY_ORG", "supplier " + s.getId());
        com.mipay.sme.domain.credit.Lender l = lenders.findByUserId(u.getId()).orElse(null);
        if (l != null) return new Resolution(provisioning.ensureEntityOrg(Enums.OrgType.LENDER,
                l.getId(), l.getName()), Enums.OrgMemberRole.ADMIN, "ENTITY_ORG", "lender " + l.getId());
        // manufacturer / distributor users and orphans cannot be resolved mechanically
        return NONE;
    }

    private static Row row(User u, Resolution r) {
        return new Row(u.getId(), u.getPhone(), String.valueOf(u.getRole()),
                String.valueOf(u.getStatus()), r.resolution(), r.org() == null ? null : r.org().getId(),
                r.org() == null ? null : r.org().getName(), r.note());
    }
}
