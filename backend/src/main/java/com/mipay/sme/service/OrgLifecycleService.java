package com.mipay.sme.service;

import com.mipay.sme.domain.Enums;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.mipay.sme.domain.credit.Lender;
import com.mipay.sme.domain.core.Merchant;
import com.mipay.sme.domain.core.Supplier;
import com.mipay.sme.domain.org.Organization;
import com.mipay.sme.repository.LenderRepository;
import com.mipay.sme.repository.MerchantRepository;
import com.mipay.sme.repository.SupplierRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only code that changes organizations.lifecycle ([C1/D2]); mirrors the
 * entity's approvalStatus (and the merchant's kycStatus) in the same
 * transaction so existing views keep working.
 */
@Service
@RequiredArgsConstructor
public class OrgLifecycleService {

    private final MerchantRepository merchantRepo;
    private final SupplierRepository supplierRepo;
    private final LenderRepository lenderRepo;
    private final AuditService auditService;
    private final com.mipay.sme.security.auth.AuthSyncService authSync;

    @Transactional
    public Organization transition(Organization org, Enums.OrgStatus to, String actorId) {
        Enums.OrgStatus from = org.getLifecycle();
        org.setLifecycle(to);
        orgRepoMirror(org, to, actorId);
        auditService.record(actorId, "SYSTEM", "org_lifecycle", "organization", org.getId(),
                from == null ? null : from.name(), to.name(), null, false, null);
        reconcileAfterCommit(org, to);
        return org;
    }

    /**
     * The IdP follows core state (§3.3b): once a lifecycle transition commits, the subtree's
     * links are brought in step (ACTIVE orgs re-enable, others disable). Idempotent.
     */
    private void reconcileAfterCommit(Organization target, Enums.OrgStatus to) {
        final String orgId = target.getId();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            authSync.reconcileOrg(orgId);   // no surrounding tx (tests, seeds): reconcile directly
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
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

    private void orgRepoMirror(Organization org, Enums.OrgStatus to, String actorId) {
        if (org.getEntityId() == null) return;
        switch (org.getOrgType()) {
            case MERCHANT -> merchantRepo.findById(org.getEntityId()).ifPresent(m -> {
                m.setApprovalStatus(mirrorApproval(to));
                m.setKycStatus(mirrorKyc(to));
                if (to == Enums.OrgStatus.ACTIVE) {
                    m.setApprovedBy(actorId);
                    m.setApprovedAt(java.time.Instant.now());
                }
                merchantRepo.save(m);
            });
            case SUPPLIER -> supplierRepo.findById(org.getEntityId()).ifPresent(s -> {
                s.setApprovalStatus(mirrorApproval(to));
                if (to == Enums.OrgStatus.ACTIVE) {
                    s.setApprovedBy(actorId);
                    s.setApprovedAt(java.time.Instant.now());
                }
                supplierRepo.save(s);
            });
            case LENDER -> lenderRepo.findById(org.getEntityId()).ifPresent(l -> {
                // null is treated as APPROVED ([H3]); APPROVED is only re-written when leaving SUSPENDED
                l.setApprovalStatus(mirrorApproval(to));
                lenderRepo.save(l);
            });
            case MANUFACTURER, DISTRIBUTOR, PLATFORM -> { /* org IS the entity */ }
        }
    }

    private Enums.ApprovalStatus mirrorApproval(Enums.OrgStatus to) {
        return switch (to) {
            case PENDING -> Enums.ApprovalStatus.PENDING;
            case ACTIVE -> Enums.ApprovalStatus.APPROVED;
            case REJECTED, BLOCKED -> Enums.ApprovalStatus.REJECTED;
        };
    }

    private Enums.KycStatus mirrorKyc(Enums.OrgStatus to) {
        return switch (to) {
            case PENDING -> Enums.KycStatus.PENDING;
            case ACTIVE -> Enums.KycStatus.VERIFIED;
            case REJECTED, BLOCKED -> Enums.KycStatus.REJECTED;
        };
    }
}
