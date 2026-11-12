package com.mipay.sme.service;

import com.mipay.sme.common.SimException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.repository.OrgSubtreeRow;
import com.mipay.sme.domain.org.Organization;
import com.mipay.sme.repository.OrgClosureRepository;
import com.mipay.sme.repository.OrganizationRepository;
import com.mipay.sme.repository.SuspensionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Single operational gate (org-hierarchy spec [M1/D3]): checks the org's
 * lifecycle and the active suspensions of the org or any ancestor. Deliberately
 * NOT cached - nothing can go stale after a test database wipe.
 */
@Service
@RequiredArgsConstructor
public class OrgAccess {

    private final OrganizationRepository orgRepo;
    private final OrgClosureRepository closureRepo;
    private final SuspensionRepository suspensionRepo;

    /**
     * Throws {@link SimException}("ORG_SUSPENDED") for business code ([M1]).
     * A null orgId (legacy fixtures with no org binding) is a no-op here; the
     * entity's own approvalStatus check stays with the business code ([H3]).
     */
    public void requireOperational(String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return;
        }
        if (!isOperational(orgId)) {
            throw new SimException("ORG_SUSPENDED", "Organization suspended");
        }
    }

    /** True when lifecycle=ACTIVE and no active hold exists on the org or an ancestor. */
    public boolean isOperational(String orgId) {
        Organization org = orgRepo.findById(orgId).orElse(null);
        if (org == null) return true;   // legacy rows without an org: no tree gate
        if (org.getLifecycle() != Enums.OrgStatus.ACTIVE) return false;

        return ancestorsActiveAndUnheld(orgId);
    }

    /** An applicant may use a PENDING org only while all ancestors are ACTIVE and none is held. */
    public boolean isPendingApplicantOrg(String orgId) {
        Organization org = orgRepo.findById(orgId).orElse(null);
        return org != null && org.getLifecycle() == Enums.OrgStatus.PENDING && ancestorsActiveAndUnheld(orgId);
    }

    private boolean ancestorsActiveAndUnheld(String orgId) {
        Organization org = orgRepo.findById(orgId).orElse(null);
        if (org == null) return false;

        // ancestor-or-self closure ids (self row included)
        List<String> ids = new ArrayList<>();
        ids.add(org.getId());
        for (OrgSubtreeRow r : closureRepo.findAncestors(org.getId())) {
            Organization ancestor = r.getOrg();
            if (!ancestor.getId().equals(orgId) && ancestor.getLifecycle() != Enums.OrgStatus.ACTIVE) return false;
            if (!ids.contains(ancestor.getId())) ids.add(ancestor.getId());
        }
        return suspensionRepo.findByOrgIdInAndLiftedAtIsNull(ids).isEmpty();
    }

    /**
     * Entity-shaped gate for business code: resolves the org for a merchant/
     * supplier/lender row; a missing org (legacy fixture) is a no-op ([H3]).
     */
    public void requireOperationalForEntity(Enums.OrgType type, String entityId) {
        if (entityId == null) return;
        orgRepo.findByOrgTypeAndEntityId(type, entityId)
                .ifPresent(org -> requireOperational(org.getId()));
    }

    public boolean isOperationalEntity(Enums.OrgType type, String entityId) {
        if (entityId == null) return true;
        return orgRepo.findByOrgTypeAndEntityId(type, entityId)
                .map(org -> isOperational(org.getId()))
                .orElse(true);
    }

    /** Org ids among the given set with at least one active hold of their own. */
    public Set<String> directlySuspended(List<String> orgIds) {
        if (orgIds.isEmpty()) return Set.of();
        return new HashSet<>(suspensionRepo.findByOrgIdInAndLiftedAtIsNull(orgIds).stream()
                .map(com.mipay.sme.domain.ops.Suspension::getOrgId)
                .collect(java.util.stream.Collectors.toSet()));
    }
}
