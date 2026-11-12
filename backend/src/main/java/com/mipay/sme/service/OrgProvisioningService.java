package com.mipay.sme.service;

import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.Enums.OrgRightKind;
import com.mipay.sme.domain.org.OrgClosure;
import com.mipay.sme.domain.org.OrgRight;
import com.mipay.sme.domain.org.Organization;
import com.mipay.sme.repository.OrgClosureRepository;
import com.mipay.sme.repository.OrgRightRepository;
import com.mipay.sme.repository.OrganizationRepository;
import com.mipay.sme.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Get-or-create org provisioning ([M2/M3]): the platform root carries the
 * fixed constant id ORG_ROOT and explicit DIRECT+CEILING rows for the whole
 * chain ([H7]). Legacy/backfilled and self-registered orgs get NO rights.
 */
@Service
@RequiredArgsConstructor
public class OrgProvisioningService {

    public static final String ORG_ROOT = "00000000-0000-0000-0000-000000000000";

    private final OrganizationRepository orgRepo;
    private final OrgClosureRepository closureRepo;
    private final OrgRightRepository rightRepo;
    private final UserRepository ownerRepo;
    private final com.mipay.sme.security.auth.UserLifecycleService userLifecycle;

    @Transactional(propagation = Propagation.REQUIRED)
    public Organization ensureEntityOrg(Enums.OrgType type, String entityId, String name) {
        return ensureEntityOrg(type, entityId, name, null);
    }

    /** As above, additionally binding the entity's owner user to the org as ADMIN. */
    @Transactional(propagation = Propagation.REQUIRED)
    public Organization ensureEntityOrg(Enums.OrgType type, String entityId, String name, String ownerUserId) {
        Organization root = ensurePlatformRoot();
        Organization org = orgRepo.findByOrgTypeAndEntityId(type, entityId)
                .orElseGet(() -> create(type, entityId, name, root));
        if (ownerUserId != null) {
            // the only org-binding writer (gateway mode additionally enrols the now-eligible user)
            userLifecycle.bindToOrg(ownerUserId, org.getId(), Enums.OrgMemberRole.ADMIN, "system:binding");
        }
        return org;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public Organization ensurePlatformRoot() {
        return orgRepo.findById(ORG_ROOT)
                .orElseGet(() -> {
                    Organization root = new Organization();
                    root.setId(ORG_ROOT);
                    root.setOrgType(Enums.OrgType.PLATFORM);
                    root.setName("Platform Root");
                    root.setLifecycle(Enums.OrgStatus.ACTIVE);
                    root.setLicenceType("PSP");
                    root.setCreatedBy("system:seed");
                    orgRepo.save(root);
                    closureRepo.save(OrgClosure.of(root.getId(), root.getId(), 0));
                    // explicit DIRECT and CEILING rows for every type below the platform ([H7])
                    for (Enums.OrgType t : List.of(Enums.OrgType.MANUFACTURER, Enums.OrgType.DISTRIBUTOR,
                            Enums.OrgType.SUPPLIER, Enums.OrgType.MERCHANT, Enums.OrgType.LENDER)) {
                        saveRight(root.getId(), t, OrgRightKind.DIRECT, "system:seed");
                        saveRight(root.getId(), t, OrgRightKind.CEILING, "system:seed");
                    }
                    return root;
                });
    }

    /**
     * OA1: a PLATFORM-typed sub-root with a fixed entity marker ('mindlabs' = operator org,
     * 'tpts' = operating root) — the split of yesterday's shared root. Fixed ids keep the
     * migration (V18), the seeder and the one-time bootstrap idempotent against each other.
     * These orgs carry no org_rights rows (they onboard nothing via the delegated flow).
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public Organization ensurePlatformSubOrg(String entityId, String name, String fixedId) {
        Organization root = ensurePlatformRoot();
        return orgRepo.findByOrgTypeAndEntityId(Enums.OrgType.PLATFORM, entityId)
                .orElseGet(() -> {
                    Organization org = new Organization();
                    if (fixedId != null && orgRepo.findById(fixedId).isEmpty()) org.setId(fixedId);
                    org.setOrgType(Enums.OrgType.PLATFORM);
                    org.setName(name);
                    org.setEntityId(entityId);
                    org.setParentId(root.getId());
                    org.setLifecycle(Enums.OrgStatus.ACTIVE);
                    org.setLicenceType("PSP");
                    org.setCreatedBy("system:bootstrap");
                    orgRepo.save(org);
                    closureRepo.save(OrgClosure.of(org.getId(), org.getId(), 0));
                    closureRepo.save(OrgClosure.of(root.getId(), org.getId(), 1));
                    // D9: a platform sub-org keeps the root's onboarding abilities so
                    // today's access is unchanged while the old root becomes a shell
                    for (Enums.OrgType t : List.of(Enums.OrgType.MANUFACTURER, Enums.OrgType.DISTRIBUTOR,
                            Enums.OrgType.SUPPLIER, Enums.OrgType.MERCHANT, Enums.OrgType.LENDER)) {
                        saveRight(org.getId(), t, OrgRightKind.DIRECT, "system:bootstrap");
                        saveRight(org.getId(), t, OrgRightKind.CEILING, "system:bootstrap");
                    }
                    return org;
                });
    }

    /** An org without rights rows may onboard nothing — rights are explicit. */
    private Organization create(Enums.OrgType type, String entityId, String name, Organization root) {
        Organization org = new Organization();
        org.setOrgType(type);
        org.setName(name == null || name.isBlank() ? type.name() : name);
        org.setEntityId(entityId);
        org.setParentId(root.getId());
        org.setLifecycle(Enums.OrgStatus.ACTIVE);   // pre-existing entity; binding is bookkeeping
        org.setCreatedBy("system:binding");
        orgRepo.save(org);

        closureRepo.save(OrgClosure.of(org.getId(), org.getId(), 0));
        closureRepo.save(OrgClosure.of(root.getId(), org.getId(), 1));
        return org;
    }

    /** Tree onboarding writes DIRECT+CEILING rows at submission time ([M5]). */
    @Transactional(propagation = Propagation.REQUIRED)
    public void writeRights(String orgId, java.util.Set<Enums.OrgType> direct,
                            java.util.Set<Enums.OrgType> ceiling, String grantedBy) {
        for (Enums.OrgType t : direct) saveRight(orgId, t, OrgRightKind.DIRECT, grantedBy);
        for (Enums.OrgType t : ceiling) saveRight(orgId, t, OrgRightKind.CEILING, grantedBy);
    }

    private void saveRight(String orgId, Enums.OrgType t, OrgRightKind kind, String grantedBy) {
        OrgRight r = new OrgRight();
        r.setOrgId(orgId);
        r.setTargetType(t);
        r.setKind(kind);
        r.setGrantedBy(grantedBy);
        r.setGrantedAt(java.time.Instant.now());
        rightRepo.save(r);
    }
}
