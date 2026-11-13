package com.mipay.sme.service.kyc;

import com.mipay.sme.common.ConflictException;
import com.mipay.sme.common.NotFoundException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.domain.kyc.KycApplication;
import com.mipay.sme.domain.org.OnboardingRequest;
import com.mipay.sme.domain.org.Organization;
import com.mipay.sme.repository.OnboardingRequestRepository;
import com.mipay.sme.repository.OrganizationRepository;
import com.mipay.sme.repository.UserRepository;
import com.mipay.sme.service.ActivationService;
import com.mipay.sme.service.OrgGoLiveBridge;
import com.mipay.sme.service.AuditService;
import com.mipay.sme.service.OrgLifecycleService;
import com.mipay.sme.service.OrgRightsService;
import com.mipay.sme.service.OrgSuspensionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * The single gate (§6.5): the final KycWorkflow approval makes the org ACTIVE,
 * runs provisioning and issues the admin's activation link — all in one core
 * transaction. The onboarding request becomes only a record.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KycGoLiveService {

    private final OrganizationRepository orgRepo;
    private final OrgLifecycleService lifecycle;
    private final OrgSuspensionService suspensions;
    private final ActivationService activation;
    private final OnboardingRequestRepository requestRepo;
    private final UserRepository userRepo;
    private final AuditService auditService;
    private final OrgGoLiveBridge goLiveBridge;
    private final OrgRightsService rights;
    private final com.mipay.sme.security.auth.AuthSyncService authSync;
    private final com.mipay.sme.config.AppProperties properties;
    private final com.mipay.sme.security.rbac.StarterRoleService starterRoles;

    /**
     * Runs when the KYC workflow's last stage APPROVEs (or auto-approval fires).
     *
     * @return "ACTIVATED" for a PENDING org going live, "REKYC" for an org that was already ACTIVE
     */
    @Transactional
    public String goLive(KycApplication app, String actorId) {
        Organization org = orgRepo.findById(app.getOrgId())
                .orElseThrow(() -> new NotFoundException("Organization vanished"));

        // re-check the gate even at go-live: the org must not be suspended
        if (suspensions.isHeld(org.getId())) {
            throw new ConflictException("ORG_SUSPENDED", "The organization is suspended");
        }

        if (org.getLifecycle() == Enums.OrgStatus.PENDING) {
            // [H7] rule 5 at the gate (D8): the onboarder's rights may have been
            // narrowed between submit and approval; a stale rights set refuses
            // go-live and the reviewer's task stays OPEN to be corrected instead.
            for (OnboardingRequest req : requestRepo
                    .findByOrgIdAndStatus(org.getId(), Enums.OnboardingStatus.PENDING_REVIEW)) {
                if (req.getKind() != Enums.OnboardingKind.ORG_ONBOARDING) continue;
                String rightsOrg = req.getRequestedByOrgId() == null
                        ? req.getParentOrgId() : req.getRequestedByOrgId();
                rights.assertNotStale(rightsOrg, ceilingFromMeta(req.getMeta()), directFromMeta(req.getMeta()));
            }

            // RIGHTS_STALE: the onboarder's rights may have been narrowed since submit
            // (rights are validated inside the onboarding request's original flow; at this
            // gate a narrowed or missing ADMIN kills the activation)
            User admin = null;
            if (app.getOrgId() != null) {
                admin = userRepo.findByOrgIdAndOrgRole(org.getId(), Enums.OrgMemberRole.ADMIN).stream()
                        .filter(u -> u.getStatus() == Enums.UserStatus.PENDING)
                        .findFirst().orElse(null);
            }
            if (admin == null) {
                throw new ConflictException("RIGHTS_STALE", "No pending admin to activate for this organization");
            }
            lifecycle.transition(org, Enums.OrgStatus.ACTIVE, actorId);
            goLiveBridge.provisioningApproved(org, actorId, "TPTS_OPS");
            writeOutcome(org, app);
            markRequestApproved(org.getId(), actorId);
            if (properties.getIdentityMode().equals("legacy")) {
                activation.issue(admin, actorId);
            } else {
                // gateway mode: the applicant admin was enrolled at creation — go-live swaps
                // their kyc_applicant assignment for org_admin; the set-up link already went out
                authSync.applicantBecomesAdmin(org.getId(), admin.getId(), actorId);
            }
            // either identity mode: the admin needs an RBAC role or the portal shows them nothing
            starterRoles.grantAtGoLive(org, admin.getId());
            authSync.reconcileOrg(org.getId());   // the ACTIVE org re-enables its users (no-op safe)
            auditService.record(actorId, "TPTS_OPS", "kyc_approved_go_live", "organization",
                    org.getId(), "PENDING", "ACTIVE", "application " + app.getId(), false, null);
            log.info("KYC go-live: org {} activated via application {}", org.getId(), app.getId());
            return "ACTIVATED";
        }

        // already ACTIVE (legacy orgs, later re-KYC): only the outcome is written
        writeOutcome(org, app);
        auditService.record(actorId, "TPTS_OPS", "kyc_approved_rekyc", "organization",
                org.getId(), null, null, "application " + app.getId(), false, null);
        return "REKYC";
    }

    private java.util.Set<Enums.OrgType> ceilingFromMeta(String meta) {
        return fromMeta(meta, "ceiling=");
    }

    private java.util.Set<Enums.OrgType> directFromMeta(String meta) {
        return fromMeta(meta, "direct=");
    }

    private java.util.Set<Enums.OrgType> fromMeta(String meta, String key) {
        if (meta == null) return java.util.Set.of();
        for (String part : meta.split(";")) {
            if (part.startsWith(key)) {
                String names = part.substring(key.length());
                java.util.Set<Enums.OrgType> out = new java.util.HashSet<>();
                for (String name : names.split(",")) {
                    if (!name.isBlank()) out.add(Enums.OrgType.valueOf(name));
                }
                return out;
            }
        }
        return java.util.Set.of();
    }

    /** Writes the KYC outcome onto the org (VERIFIED + risk rating + next review date). */
    private void writeOutcome(Organization org, KycApplication app) {
        org.setKycStatus(Enums.KycStatus.VERIFIED);
        if (app.getRiskRating() != null) {
            try {
                org.setKycRiskRating(Enums.KycRiskRating.valueOf(app.getRiskRating()));
            } catch (IllegalArgumentException ignored) {
                org.setKycRiskRating(Enums.KycRiskRating.MEDIUM);
            }
        }
        org.setKycNextReviewDate(app.getNextReviewDate() != null
                ? app.getNextReviewDate() : Instant.now().plusSeconds(365L * 24 * 3600));
        orgRepo.save(org);
    }

    private void markRequestApproved(String orgId, String actorId) {
        requestRepo.findByOrgIdAndStatus(orgId, Enums.OnboardingStatus.PENDING_REVIEW)
                .forEach(req -> {
                    req.setStatus(Enums.OnboardingStatus.APPROVED);
                    req.setReviewedBy(actorId);
                    req.setReviewedAt(Instant.now());
                    req.setNote("auto-approved by KYC go-live");
                    requestRepo.save(req);
                });
    }

    /** REJECT/BLOCK (§6.4): the org becomes REJECTED or BLOCKED and pending users are archived. */
    @Transactional
    public void terminate(KycApplication app, Enums.OrgStatus target, String reason, String actorId) {
        Organization org = orgRepo.findById(app.getOrgId()).orElse(null);
        if (org == null || org.getLifecycle() != Enums.OrgStatus.PENDING) return;
        lifecycle.transition(org, target, actorId);
        org.setKycStatus(target == Enums.OrgStatus.BLOCKED ? Enums.KycStatus.REJECTED : Enums.KycStatus.REJECTED);
        orgRepo.save(org);
        markRequestForced(org.getId(), target, reason, actorId);
        goLiveBridge.archivePendingUsers(org);
        // §3.3c: termination is terminal — the whole subtree's links are reconciled after
        // the archive (disable + revoke for anything the archive itself did not cover)
        authSync.reconcileOrg(org.getId());
    }

    private void markRequestForced(String orgId, Enums.OrgStatus target, String reason, String actorId) {
        Enums.OnboardingStatus mapped = target == Enums.OrgStatus.BLOCKED
                ? Enums.OnboardingStatus.BLOCKED : Enums.OnboardingStatus.REJECTED;
        requestRepo.findByOrgIdAndStatus(orgId, Enums.OnboardingStatus.PENDING_REVIEW)
                .forEach(req -> {
                    req.setStatus(mapped);
                    req.setReviewedBy(actorId);
                    req.setReviewedAt(Instant.now());
                    req.setNote(reason);
                    requestRepo.save(req);
                });
    }
}
