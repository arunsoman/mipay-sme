package com.mipay.sme.service;

import com.mipay.sme.common.ConflictException;
import com.mipay.sme.common.ForbiddenException;
import com.mipay.sme.common.NotFoundException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.credit.Lender;
import com.mipay.sme.domain.core.Merchant;
import com.mipay.sme.domain.core.Supplier;
import com.mipay.sme.domain.ops.Suspension;
import com.mipay.sme.domain.org.Organization;
import com.mipay.sme.repository.DeviceSessionRepository;
import com.mipay.sme.repository.LenderRepository;
import com.mipay.sme.repository.MerchantRepository;
import com.mipay.sme.repository.OrgClosureRepository;
import com.mipay.sme.repository.OrganizationRepository;
import com.mipay.sme.repository.SuspensionRepository;
import com.mipay.sme.repository.SupplierRepository;
import com.mipay.sme.repository.UserRepository;
import com.mipay.sme.security.Current;
import com.mipay.sme.web.Dtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Cascading, reversible suspension ([H1..H5, M1]). Several independent holds
 * per org; an org is suspended when the org or any ancestor has an active row.
 * Session deletion covers every user of the subtree.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrgSuspensionService {

    private final SuspensionRepository suspensionRepo;
    private final OrganizationRepository orgRepo;
    private final OrgClosureRepository closureRepo;
    private final UserRepository userRepo;
    private final DeviceSessionRepository sessionRepo;
    private final MerchantRepository merchantRepo;
    private final SupplierRepository supplierRepo;
    private final LenderRepository lenderRepo;
    private final OrgAccess orgAccess;
    private final AuditService auditService;
    private final OrgCallerService caller;
    private final com.mipay.sme.security.auth.AuthSyncService authSync;
    private final org.springframework.context.ApplicationEventPublisher events;
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private KycApplicationService kycApplications;   // cycle: engine → suspensions → applications

    @Transactional
    public Dtos.SuspensionRow suspend(String orgId, String reason, Current.Principal actor) {
        Organization org = orgRepo.findById(orgId)
                .orElseThrow(() -> new NotFoundException("Organization not found"));
        assertSuspendAuthority(actor, org);

        Suspension s = new Suspension();
        s.setEntityType(Enums.SuspensionEntityType.ORG);
        s.setEntityId(org.getId());
        s.setOrgId(org.getId());
        s.setSuspendedByOrgId(actor.orgId());
        s.setReason(reason);
        s.setSuspendedBy(actor.userId());
        suspensionRepo.save(s);

        // entity mirrors (single writer is OrgLifecycleService for lifecycle; suspensions mirror here)
        mirrorEntity(org, Enums.ApprovalStatus.SUSPENDED);

        // cut the subtree's sessions in bulk
        List<String> userIds = userRepo.findByOrgIdIn(subtreeOrgIds(org.getId())).stream()
                .map(u -> String.valueOf(u.getId())).toList();
        if (!userIds.isEmpty()) {
            sessionRepo.deleteAll(sessionRepo.findByUserIdIn(userIds));
        }

        // subtree impact preview in the audit trail ([D4])
        int subtreeOrgs = closureRepo.findSubtree(org.getId()).size();
        auditService.record(actor.userId(), actor.role(), "org_suspended", "organization", org.getId(),
                "ACTIVE", "SUSPENDED", "reason=" + reason + ";subtreeOrgs=" + subtreeOrgs
                        + ";subtreeUsers=" + userIds.size(), false, null);
        kycApplications.syncSuspension(org.getId());   // isSuspended for the whole subtree
        // RBAC refuses the held subtree before the gateway routes (cut-over P2.6a); runs after commit
        events.publishEvent(new com.mipay.sme.security.rbac.OrgTreeSyncService.OrgTreeChanged(org.getId()));
        reconcileAfterCommit(org.getId());
        return new Dtos.SuspensionRow(s.getId(), s.getEntityType().name(), s.getEntityId(), reason,
                s.getSuspendedAt(), null);
    }

    @Transactional
    public List<Dtos.SuspensionRow> lift(String suspensionId, Current.Principal actor) {
        Suspension s = suspensionRepo.findById(suspensionId)
                .orElseThrow(() -> new NotFoundException("Suspension not found"));
        if (s.isActive()) {
            assertLiftAuthority(actor, s);
            s.setLiftedAt(Instant.now());
            s.setLiftedBy(actor.userId());
            suspensionRepo.save(s);
            Organization org = s.getOrgId() == null ? null : orgRepo.findById(s.getOrgId()).orElse(null);
            if (org != null && orgAccess.isOperational(org.getId())) {
                // other holds on this org or its ancestors may still apply ([H4])
                mirrorEntity(org, Enums.ApprovalStatus.APPROVED);
            }
            auditService.record(actor.userId(), actor.role(), "suspension_lifted", "organization",
                    s.getOrgId() == null ? s.getEntityId() : s.getOrgId(),
                    "SUSPENDED", "ACTIVE", null, false, null);
            kycApplications.syncSuspension(s.getOrgId());   // other holds may keep parts suspended
            events.publishEvent(new com.mipay.sme.security.rbac.OrgTreeSyncService.OrgTreeChanged(s.getOrgId()));
            if (s.getOrgId() != null) reconcileAfterCommit(s.getOrgId());
        }
        return suspensions();
    }

    // ------------------------------------------------------------------

    private void assertSuspendAuthority(Current.Principal actor, Organization target) {
        if (target.getOrgType() == Enums.OrgType.PLATFORM) {
            throw new ForbiddenException("PLATFORM_HOLD_REFUSED", "The platform root cannot be suspended");
        }
        if (platformStaff(actor)) {
            return;   // the platform may suspend any org ([H2]: authority from the role string)
        }
        Organization callerOrg = caller.orgOf(actor);
        if (callerOrg == null) {
            throw new NotFoundException("Organization not found");
        }
        boolean strictDescendant = closureRepo.existsByAncestorIdAndDescendantId(callerOrg.getId(), target.getId())
                && !callerOrg.getId().equals(target.getId());
        boolean admin = actor.orgRole() != null && Enums.OrgMemberRole.ADMIN.name().equals(actor.orgRole());
        if (!strictDescendant || !admin) {
            throw new ForbiddenException("ADMIN_REQUIRED",
                    "Suspension requires a platform authority or an ancestor ADMIN ([M10])");
        }
    }

    private void assertLiftAuthority(Current.Principal actor, Suspension s) {
        if (platformStaff(actor)) return;
        Organization callerOrg = caller.orgOf(actor);
        if (callerOrg == null || s.getSuspendedByOrgId() == null) {
            throw new ForbiddenException("PLATFORM_REQUIRED", "Only the suspending level or above can lift");
        }
        // same level or one of its ancestors; a child can never undo a hold from above
        String suspendingOrg = s.getSuspendedByOrgId();
        boolean same = callerOrg.getId().equals(suspendingOrg);
        boolean ancestor = closureRepo.existsByAncestorIdAndDescendantId(callerOrg.getId(), suspendingOrg);
        if (!same && !ancestor) {
            throw new ForbiddenException("PLATFORM_REQUIRED",
                    "A child cannot lift a hold imposed from above ([H4])");
        }
    }

    private void mirrorEntity(Organization org, Enums.ApprovalStatus to) {
        if (org.getEntityId() == null) return;
        if (org.getOrgType() == Enums.OrgType.MERCHANT) {
            Merchant m = merchantRepo.findById(org.getEntityId()).orElse(null);
            if (m != null && (to != Enums.ApprovalStatus.APPROVED || m.getApprovalStatus() == Enums.ApprovalStatus.SUSPENDED)) {
                m.setApprovalStatus(to);
                merchantRepo.save(m);
            }
        } else if (org.getOrgType() == Enums.OrgType.SUPPLIER) {
            Supplier sp = supplierRepo.findById(org.getEntityId()).orElse(null);
            if (sp != null && (to != Enums.ApprovalStatus.APPROVED || sp.getApprovalStatus() == Enums.ApprovalStatus.SUSPENDED)) {
                sp.setApprovalStatus(to);
                supplierRepo.save(sp);
            }
        } else if (org.getOrgType() == Enums.OrgType.LENDER) {
            Lender l = lenderRepo.findById(org.getEntityId()).orElse(null);
            if (l != null) {
                l.setApprovalStatus(to);
                lenderRepo.save(l);
            }
        }
    }

    public List<String> subtreeOrgIds(String orgId) {
        return closureRepo.findSubtree(orgId).stream().map(r -> r.getOrg().getId()).toList();
    }

    /** True when an active hold exists on the org or any ancestor (also used by the workflow gate). */
    @Transactional(readOnly = true)
    public boolean isHeld(String orgId) {
        if (orgId == null) return false;
        Set<String> ids = new java.util.HashSet<>();
        ids.add(orgId);
        closureRepo.findAncestors(orgId).forEach(r -> ids.add(r.getOrg().getId()));
        return !suspensionRepo.findByOrgIdInAndLiftedAtIsNull(new java.util.ArrayList<>(ids)).isEmpty();
    }

    public List<Dtos.SuspensionRow> suspensions() {
        return suspensionRepo.findAllByOrderBySuspendedAtDesc().stream()
                .map(s -> new Dtos.SuspensionRow(s.getId(), s.getEntityType().name(), s.getEntityId(),
                        s.getReason(), s.getSuspendedAt(), s.getLiftedAt()))
                .toList();
    }

    private boolean platformStaff(Current.Principal actor) {
        return caller.platformStaff(actor);
    }

    /**
     * The IdP follows core state (§3.3b): once the suspension transaction commits, the
     * subtree's links are disabled — on lift they are re-enabled so far as core permits.
     */
    private void reconcileAfterCommit(String orgId) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            authSync.reconcileOrg(orgId);
            return;
        }
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            authSync.reconcileOrg(orgId);
                        } catch (Exception e) {
                            // the nightly repair covers anything this pass could not finish
                        }
                    }
                });
    }
}
