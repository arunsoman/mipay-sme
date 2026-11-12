package com.mipay.sme.service;

import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.Merchant;
import com.mipay.sme.domain.core.Supplier;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.domain.org.OnboardingRequest;
import com.mipay.sme.domain.org.Organization;
import com.mipay.sme.repository.MerchantRepository;
import com.mipay.sme.repository.OnboardingRequestRepository;
import com.mipay.sme.repository.SupplierRepository;
import com.mipay.sme.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Go-live plumbing shared by the KYC gate and (legacy) onboarding decisions:
 * entity provisioning and admin archiving. Kept separate so the workflow gate
 * never depends on the application service (no cycles), §6.5/§16.8.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrgGoLiveBridge {

    private final MerchantRepository merchantRepo;
    private final SupplierRepository supplierRepo;
    private final UserRepository userRepo;
    private final OnboardingRequestRepository onboardingRepo;
    private final ComplianceService complianceService;
    private final ProvisioningService provisioningService;
    private final com.mipay.sme.security.auth.UserLifecycleService userLifecycle;
    private final com.mipay.sme.repository.OrgClosureRepository closureRepo;
    private final com.mipay.sme.config.AppProperties properties;
    private final com.mipay.sme.security.rbac.RbacMeshClient rbac;

    /** Provisioning for an org that just passed the KYC gate. */
    public void provisioningApproved(Organization org, String approverId, String approverRole) {
        closePendingOnboardingRequests(org, approverId);
        provisioningApprovedPrivate(org, approverId, approverRole);
    }

    /** Provisioning for the legacy onboarding-decision path (request already closed by the caller). */
    public void provisioningApprovedPublic(Organization org, String approverId, String approverRole) {
        provisioningApprovedPrivate(org, approverId, approverRole);
    }

    // archivePendingUsers lives below (§3.3c full-subtree semantics in gateway mode)

    private void provisioningApprovedPrivate(Organization org, String reviewerId, String reviewerRole) {
        if (org.getEntityId() == null) return;
        switch (org.getOrgType()) {
            case MERCHANT -> merchantRepo.findById(org.getEntityId()).ifPresent(m -> {
                complianceService.applyRiskRating(m, Enums.KycResult.PASS, reviewerId, reviewerRole);
                merchantRepo.save(m);
                provisioningService.provision(m);
            });
            case SUPPLIER -> supplierRepo.findById(org.getEntityId()).ifPresent(s -> {
                if (s.getKycRiskRating() == null) {   // suppliers stay MEDIUM by default (bank risk)
                    s.setKycRiskRating(Enums.KycRiskRating.MEDIUM);
                    s.setKycNextReviewDate(java.time.LocalDate.now().plusMonths(24));
                }
                supplierRepo.save(s);
                provisioningService.provisionSupplier(s);
            });
            case LENDER, MANUFACTURER, DISTRIBUTOR, PLATFORM -> { /* nothing extra */ }
        }
    }

    /**
     * [D8]: once the workflow activates the org, its PENDING_REVIEW
     * ORG_ONBOARDING requests are closed as APPROVED so the queue stays
     * truthful (the legacy decide endpoint can no longer act on them).
     */
    private void closePendingOnboardingRequests(Organization org, String reviewerId) {
        if (org == null || org.getId() == null) return;
        onboardingRepo.findByOrgIdAndStatus(org.getId(), Enums.OnboardingStatus.PENDING_REVIEW).stream()
                .filter(r -> r.getKind() == Enums.OnboardingKind.ORG_ONBOARDING)
                .forEach(r -> {
                    r.setStatus(Enums.OnboardingStatus.APPROVED);
                    r.setReviewedBy(reviewerId);
                    r.setReviewedAt(java.time.Instant.now());
                    r.setNote("approved via KYC workflow (go-live gate)");
                    onboardingRepo.save(r);
                });
    }

    /**
     * §3.3c: termination is terminal — gateway mode archives ALL users of the org's subtree
     * (not only the pending ones), queues DISABLE+UNLINK for their credentials and revokes the
     * org tenant's RBAC assignments and grants. Legacy mode keeps historic behaviour.
     */
    public void archivePendingUsers(Organization org) {
        if (!properties.getIdentityMode().equals("legacy")) {
            List<String> subtree = closureRepo.findSubtree(org.getId()).stream()
                    .map(r -> r.getOrg().getId()).toList();
            List<String> orgIds = subtree.isEmpty() ? List.of(org.getId()) : subtree;
            for (User u : userRepo.findByOrgIdIn(orgIds)) archiveUser(u);
            try {
                if (rbac.configured()) rbac.revokeAll(org.getId(), "org rejected/closed: subtree archived");
            } catch (Exception e) {
                // assignments are reconcilable: the log records the drift, the next run repairs
                log.warn("rbac revoke-all failed for {}: {}", org.getId(), e.getMessage());
            }
            return;
        }
        archivePendingOnly(org);
    }

    /** Legacy behaviour kept verbatim: only the org's own PENDING members are archived. */
    private void archivePendingOnly(Organization org) {
        userRepo.findByOrgIdAndStatus(org.getId(), Enums.UserStatus.PENDING).forEach(this::archiveUser);
    }

    /** [M7]: pending members are archived with an audit-tagged username. */
    private void archiveUser(User u) {
        String tag = "arch-" + u.getId().substring(0, Math.min(5, u.getId().length()))
                + "-" + Long.toString(System.currentTimeMillis(), 36);
        if (u.getUsername() == null) u.setUsername(tag);
        else u.setUsername("arch-" + u.getUsername());
        // status + session kill + provider commands happen in the lifecycle service
        userLifecycle.archive(u, "kyc-golive-bridge");
    }
}
