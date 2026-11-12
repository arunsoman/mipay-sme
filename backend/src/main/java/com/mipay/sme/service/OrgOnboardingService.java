package com.mipay.sme.service;

import com.mipay.sme.common.BadRequestException;
import com.mipay.sme.common.ConflictException;
import com.mipay.sme.common.ForbiddenException;
import com.mipay.sme.common.NotFoundException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.Enums.OrgType;
import com.mipay.sme.domain.credit.Lender;
import com.mipay.sme.domain.core.Merchant;
import com.mipay.sme.domain.core.Supplier;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.domain.org.OnboardingRequest;
import com.mipay.sme.domain.org.OrgBankAccount;
import com.mipay.sme.domain.org.OrgClosure;
import com.mipay.sme.domain.org.Organization;
import com.mipay.sme.repository.LenderRepository;
import com.mipay.sme.repository.MerchantRepository;
import com.mipay.sme.repository.OnboardingRequestRepository;
import com.mipay.sme.repository.OrgBankAccountRepository;
import com.mipay.sme.repository.OrgClosureRepository;
import com.mipay.sme.repository.OrgSubtreeRow;
import com.mipay.sme.repository.OrganizationRepository;
import com.mipay.sme.repository.SupplierRepository;
import com.mipay.sme.repository.UserRepository;
import com.mipay.sme.security.Current;
import com.mipay.sme.web.Dtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Delegated org onboarding with flexible rights ([D1/H7/M5]), second-person
 * review with KYC ([L2/L3]), staff ([C3/H9]) and admin recovery.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrgOnboardingService {

    private final OrganizationRepository orgRepo;
    private final OrgClosureRepository closureRepo;
    private final OnboardingRequestRepository requestRepo;
    private final OrgBankAccountRepository bankAccountRepo;
    private final UserRepository userRepo;
    private final MerchantRepository merchantRepo;
    private final SupplierRepository supplierRepo;
    private final LenderRepository lenderRepo;
    private final OrgTreeService tree;
    private final OrgRightsService rights;
    private final OrgAccess orgAccess;
    private final OrgLifecycleService lifecycle;
    private final OrgUserService orgUsers;
    private final ActivationService activation;
    private final com.mipay.sme.security.auth.UserLifecycleService userLifecycle;
    private final com.mipay.sme.config.AppProperties properties;
    private final UsernamePolicy usernamePolicy;
    private final ComplianceService complianceService;
    private final ProvisioningService provisioningService;
    private final AuditService auditService;
    private final OnboardingService onboardingService;   // legacy guard coexistence (USE_ONBOARDING_REQUEST)
    private final OrgProvisioningService orgProvisioning;
    private final OrgRoleCeilingService roleCeilings;
    private final KycApplicationService kycApplications;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    /** Internal normalized profile shared by the controllers. */
    /** §12: the KYC document no longer passes through onboarding; two calls instead. */
    public record OrgSpec(String name, String registrationNumber, String businessType,
                          String ownerName, String phone, String username, String email,
                          String bankName, String accountNumber, String accountHolder,
                          String licenceRef, String supplierType, String ownerIdNumber,
                          Set<OrgType> rightsDirect, Set<OrgType> rightsCeiling,
                          List<String> roleCeiling, List<String> roleDelegable) {
        /** Without the role ceiling (the child inherits its parent's hand-down set, extended-rbac X2). */
        public OrgSpec(String name, String registrationNumber, String businessType, String ownerName, String phone,
                       String username, String email, String bankName, String accountNumber, String accountHolder,
                       String licenceRef, String supplierType, String ownerIdNumber,
                       Set<OrgType> rightsDirect, Set<OrgType> rightsCeiling) {
            this(name, registrationNumber, businessType, ownerName, phone, username, email, bankName, accountNumber,
                    accountHolder, licenceRef, supplierType, ownerIdNumber, rightsDirect, rightsCeiling, null, null);
        }
    }

    // ------------------------------------------------------------------
    // submitOrg (POST /api/orgs/me/children — the path only accepts "me" [M10])
    // ------------------------------------------------------------------

    @Transactional
    public Dtos.OrgDecisionResult submitOrg(Current.Principal caller, OrgType childType, OrgSpec spec) {
        if (childType == OrgType.PLATFORM) {
            throw new ForbiddenException("NO_RIGHT", "The PLATFORM org cannot be onboarded through the tree");
        }
        Organization parent = tree.ownOrg(caller);
        if (parent == null) throw new NotFoundException("Caller has no organization");

        // parent must be operational: a suspended onboarder cannot submit ([H4])
        if (!orgAccess.isOperational(parent.getId())) {
            throw new ConflictException("PARENT_NOT_OPERATIONAL",
                    "Organization suspended; onboarding refused until lifted");
        }

        // 1) rank rule + rights validation (proposal normalized; 422 on violation) [D1/H7]
        if (!OrgTypeRules.parentAllows(parent.getOrgType(), childType)) {
            throw new ForbiddenException("RANK_RULE",
                    childType + " may only hang off " + OrgTypeRules.ALLOWED_PARENTS.get(childType));
        }
        OrgRightsService.Proposal proposal =
                rights.validateProposal(parent.getId(), childType, spec.rightsDirect(), spec.rightsCeiling());
        // role ceiling (extended-rbac X4): within the onboarder's own hand-down set; absent = inherit
        if (spec.roleCeiling() != null) roleCeilings.validate(parent.getId(), spec.roleCeiling(), spec.roleDelegable());

        // 2) profile validation + uniqueness
        String name = spec.name();
        if (name == null || name.isBlank()) {
            throw new BadRequestException("PROFILE_INVALID", "name required");
        }
        if (spec.phone() == null || spec.phone().isBlank()) {
            throw new BadRequestException("PROFILE_INVALID", "admin phone required");
        }
        if (userRepo.existsByPhone(spec.phone())) {
            throw new ConflictException("DUPLICATE_PHONE", "Phone already registered");
        }
        usernamePolicy.validate(spec.username());   // [H6]
        validateTypeProfile(childType, spec, name);

        // 3) the org row FIRST (a user is never created outside an existing org), then rights and
        //    ceilings, then the admin through the UserLifecycleService — whose enrolment command
        //    commits atomically with the user row it belongs to
        Organization org = new Organization();
        org.setOrgType(childType);
        org.setName(name);
        org.setRegistrationNumber(spec.registrationNumber());
        org.setParentId(parent.getId());
        org.setLicenceType(parent.getLicenceType() == null ? "PSP" : parent.getLicenceType());
        org.setLifecycle(Enums.OrgStatus.PENDING);
        org.setOnboardedBy(caller.userId());
        org.setCreatedBy(caller.userId());
        orgRepo.save(org);

        for (OrgSubtreeRow anc : closureRepo.findAncestors(parent.getId())) {
            closureRepo.save(OrgClosure.of(anc.getOrg().getId(), org.getId(), anc.getDepth() + 1));
        }
        closureRepo.save(OrgClosure.of(org.getId(), org.getId(), 0));

        // rights rows are written NOW, while the org is PENDING ([M5]); they bind when ACTIVE
        orgProvisioning.writeRights(org.getId(), proposal.direct(), proposal.ceiling(), caller.userId());
        if (spec.roleCeiling() != null) {
            roleCeilings.set(org.getId(), spec.roleCeiling(), spec.roleDelegable(), caller.userId(), caller.role());
        }

        User admin = userLifecycle.create(new com.mipay.sme.security.auth.UserLifecycleService.Registration(
                org.getId(), spec.ownerName() == null ? name : spec.ownerName(), spec.username(),
                spec.phone(), spec.email(), roleFor(childType), Enums.OrgMemberRole.ADMIN, caller.userId()));

        String entityId = null;
        switch (childType) {
            case MERCHANT -> {
                Merchant merchant = new Merchant();
                merchant.setUserId(admin.getId());
                merchant.setBusinessName(name);
                merchant.setBusinessType(spec.businessType());
                merchant.setRegistrationNumber(spec.registrationNumber());
                merchant.setOwnerName(spec.ownerName());
                merchant.setOwnerPhone(spec.phone());
                merchant.setKycStatus(Enums.KycStatus.PENDING);
                merchant.setApprovalStatus(Enums.ApprovalStatus.PENDING);
                merchantRepo.save(merchant);
                entityId = merchant.getId();
            }
            case SUPPLIER -> {
                Supplier supplier = new Supplier();
                supplier.setUserId(admin.getId());
                supplier.setBusinessName(name);
                supplier.setRegistrationNumber(spec.registrationNumber());
                supplier.setOwnerName(spec.ownerName());
                supplier.setOwnerPhone(spec.phone());
                supplier.setSupplierType(spec.supplierType());
                supplier.setOwnerIdNumber(spec.ownerIdNumber());
                supplier.setApprovalStatus(Enums.ApprovalStatus.PENDING);
                supplierRepo.save(supplier);
                entityId = supplier.getId();
            }
            case LENDER -> {
                Lender lender = new Lender();
                lender.setName(name);
                lender.setLicenceRef(spec.licenceRef());
                lender.setUserId(admin.getId());
                lender.setStatus(Enums.LenderStatus.ACTIVE);
                lenderRepo.save(lender);
                entityId = lender.getId();
            }
            case MANUFACTURER, DISTRIBUTOR, PLATFORM -> { /* the org row IS the entity */ }
            default -> { }
        }

        // the entity rows reference the admin; the org regains its entityId after they exist
        org.setEntityId(entityId);
        orgRepo.save(org);


        // 5) supplier bank account (independent approval still applies downstream)
        if (childType == OrgType.SUPPLIER) {
            OrgBankAccount account = new OrgBankAccount();
            account.setOrg(org);
            account.setBankName(required(spec.bankName(), "bankName"));
            account.setAccountNumber(required(spec.accountNumber(), "accountNumber"));
            account.setAccountHolder(required(spec.accountHolder(), "accountHolder"));
            account.setStatus(Enums.BankAccountStatus.PENDING_APPROVAL);
            account.setRequestedBy(admin.getId());
            account.setRequestNote("tree onboarding");
            bankAccountRepo.save(account);
        }

        // 6) the review request ([M4] reviewed_by <> requested_by enforced at the DB too)
        OnboardingRequest req = new OnboardingRequest();
        req.setKind(Enums.OnboardingKind.ORG_ONBOARDING);
        req.setStatus(Enums.OnboardingStatus.PENDING_REVIEW);
        req.setOrgId(org.getId());
        req.setParentOrgId(parent.getId());
        req.setAdminUserId(admin.getId());
        req.setRequestedBy(caller.userId());
        req.setRequestedByRole(caller.role());
        req.setRequestedByOrgId(caller.orgId());
        req.setNote(name + " (" + childType + ")");
        req.setMeta(metaOf(childType, proposal));
        requestRepo.save(req);

        auditService.record(caller.userId(), caller.role(), "org_onboarded_request", "organization",
                org.getId(), null, "PENDING",
                "childType=" + childType + ";parentOrg=" + parent.getId()
                        + ";direct=" + names(proposal.direct()) + ";ceiling=" + names(proposal.ceiling()),
                false, null);

        // two-call onboarding (§12): the child starts an empty DRAFT; the parent
        // completes and submits the KYC document separately
        kycApplications.createDraftAtOnboarding(org, caller.userId());

        return new Dtos.OrgDecisionResult(req.getId(), org.getId(), "PENDING",
                "Onboarding request created; the KYC workflow gates this org's go-live");
    }

    // -------------------------------------------------------------------
    // go-live helpers, exposed so the single gate can reuse the same
    // implementation (org lifecycle remains in core in the interim, §16.8)
    // -------------------------------------------------------------------

    /** Provisioning for entities when the KYC gate activates the org. */
    public void provisioningApprovedPublic(Organization org, String approverId, String approverRole) {
        provisioningApproved(org, approverId, approverRole);
    }

    /** Archived pending users on REJECT/BLOCK (§6.4; mirrors [M7]). */
    public void archivePendingUsersPublic(Organization org) {
        archivePendingUsers(org);
    }

    private String metaOf(OrgType t, OrgRightsService.Proposal p) {
        return "childType=" + t + ";direct=" + names(p.direct()) + ";ceiling=" + names(p.ceiling());
    }


    private String names(Set<OrgType> set) {
        return set.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    private void validateTypeProfile(OrgType t, OrgSpec spec, String name) {
        switch (t) {
            case MANUFACTURER, DISTRIBUTOR -> required(spec.registrationNumber(), "registrationNumber");
            case SUPPLIER -> {
                required(spec.ownerName(), "ownerName");
                required(spec.bankName(), "bankName");
                required(spec.accountNumber(), "accountNumber");
                required(spec.accountHolder(), "accountHolder");
            }
            case MERCHANT -> required(spec.ownerName(), "ownerName");
            case LENDER -> required(spec.licenceRef(), "licenceRef");
            default -> { }
        }
    }

    private String required(String v, String field) {
        if (v == null || v.isBlank()) {
            throw new BadRequestException("PROFILE_INVALID", field + " required");
        }
        return v;
    }

    // ------------------------------------------------------------------
    // decide (review queue): lock, independence, [L3], RIGHTS_STALE, KYC, transition
    // ------------------------------------------------------------------

    @Transactional
    public Dtos.OrgDecisionResult decide(String requestId, Current.Principal reviewer,
                                         boolean approve, String note, String sanctionsResult) {
        OnboardingRequest req = requestRepo.findByIdForUpdate(requestId)
                .orElseThrow(() -> new NotFoundException("Onboarding request not found"));
        if (req.getStatus() != Enums.OnboardingStatus.PENDING_REVIEW) {
            throw new ConflictException("ALREADY_DECIDED", "Request was already decided");
        }
        // independence rule (reviewer never the requester) [review invariant 2]
        if (reviewer.userId().equals(req.getRequestedBy())) {
            throw new ConflictException("INDEPENDENCE_RULE",
                    "Same user cannot submit and review (second-person rule)");
        }
        // authority: platform staff review, or an ancestor ADMIN of the parent org
        if (!tree.platformStaff(reviewer)) {
            Organization callerOrg = tree.orgOf(reviewer);
            boolean parentAdmin = callerOrg != null
                    && callerOrg.getId().equals(req.getParentOrgId())
                    && reviewer.orgRole() != null
                    && Enums.OrgMemberRole.ADMIN.name().equals(reviewer.orgRole());
            if (!parentAdmin) {
                throw new ForbiddenException("DECIDE_AUTHORITY",
                        "Only platform review staff or the parent org's ADMIN decide requests");
            }
        }
        // [L3] root-sourced request + a decision from the same platform role is audit-flagged
        boolean fromRoot = req.getParentOrgId() != null && req.getParentOrgId().equals(OrgProvisioningService.ORG_ROOT);
        if (fromRoot && tree.platformStaff(reviewer)) {
            auditService.record(reviewer.userId(), reviewer.role(), "root_self_decision_flag", "onboarding_request",
                    req.getId(), null, null, "request originated at the platform root (L3)", false, null);
        }

        Set<OrgType> ceiling = ceilingFromMeta(req.getMeta());
        Set<OrgType> direct = directFromMeta(req.getMeta());

        // re-check the onboarder's CURRENT effective rights ([H7] rule 5) + parent operational
        if (approve) {
            if (req.getParentOrgId() != null) {
                Organization parent = orgRepo.findById(req.getParentOrgId()).orElse(null);
                if (parent == null || parent.getLifecycle() != Enums.OrgStatus.ACTIVE
                        || !orgAccess.isOperational(parent.getId())) {
                    throw new ConflictException("RIGHTS_STALE", "Onboarding parent is no longer operational");
                }
            }
            rights.assertNotStale(req.getRequestedByOrgId() == null
                            ? req.getParentOrgId() : req.getRequestedByOrgId(),
                    ceiling, direct);
        }

        // D8/§6.5: ORG_ONBOARDING requests no longer carry their own decision —
        // the KYC workflow's final approval is the single gate. ADMIN_RECOVERY stays.
        if (req.getKind() == Enums.OnboardingKind.ORG_ONBOARDING) {
            throw new ConflictException("USE_KYC_WORKFLOW",
                    "Onboarding decisions are made through the KYC workflow now; this request stays PENDING_REVIEW until go-live");
        }

        return req.getKind() == Enums.OnboardingKind.ADMIN_RECOVERY
                ? decideAdminRecovery(req, reviewer, approve, note)
                : decideOrgOnboarding(req, reviewer, approve, note, sanctionsResult);
    }

    private Set<OrgType> ceilingFromMeta(String meta) {
        return fromMeta(meta, "ceiling=");
    }

    private Set<OrgType> directFromMeta(String meta) {
        return fromMeta(meta, "direct=");
    }

    private Set<OrgType> fromMeta(String meta, String key) {
        if (meta == null) return Set.of();
        for (String part : meta.split(";")) {
            if (part.startsWith(key)) {
                String v = part.substring(key.length());
                if (v.isBlank()) return Set.of();
                return java.util.Arrays.stream(v.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty())
                        .map(OrgType::valueOf).collect(Collectors.toSet());
            }
        }
        return Set.of();
    }

    // ------------------------------------------------------------------

    private Dtos.OrgDecisionResult decideOrgOnboarding(OnboardingRequest req, Current.Principal reviewer,
                                                       boolean approve, String note, String sanctionsResult) {
        Organization org = orgRepo.findById(req.getOrgId())
                .orElseThrow(() -> new NotFoundException("Organization vanished"));
        String entityType = org.getOrgType().name().toLowerCase();
        String entityId = org.getEntityId() != null ? org.getEntityId() : org.getId();

        Enums.KycResult sanctions = "fail".equalsIgnoreCase(sanctionsResult)
                ? Enums.KycResult.FAIL : Enums.KycResult.PASS;

        req.setReviewedBy(reviewer.userId());
        req.setReviewedAt(Instant.now());
        req.setNote(note);

        if (approve) {
            // KYC at the point of decision: IDENTITY + SANCTIONS + risk rating
            complianceService.recordCheck(entityType, entityId, Enums.KycCheckType.IDENTITY,
                    Enums.KycResult.PASS, "simulated KYC provider");
            complianceService.recordCheck(entityType, entityId, Enums.KycCheckType.SANCTIONS,
                    sanctions, "simulated sanctions screening");

            // Persist the KYC outcome onto the org so it is visible (org-typed entities have no
            // Merchant/Supplier row to carry kycStatus).
            if (sanctions == Enums.KycResult.FAIL) {
                org.setKycStatus(Enums.KycStatus.REJECTED);
            } else {
                // Org-typed onboarding (MANUFACTURER/DISTRIBUTOR/LENDER) carries no business-type
                // input, so default to MEDIUM risk with a 24-month review cycle.
                org.setKycStatus(Enums.KycStatus.VERIFIED);
                org.setKycRiskRating(Enums.KycRiskRating.MEDIUM);
                org.setKycNextReviewDate(Instant.now().plusSeconds(24L * 30 * 24 * 60 * 60));
            }
            orgRepo.save(org);

            if (sanctions == Enums.KycResult.FAIL) {
                lifecycle.transition(org, Enums.OrgStatus.BLOCKED, reviewer.userId());
                req.setStatus(Enums.OnboardingStatus.BLOCKED);
                requestRepo.save(req);
                archivePendingUsers(org);
                auditService.record(reviewer.userId(), reviewer.role(), "org_onboarding_blocked", "organization",
                        org.getId(), "PENDING", "BLOCKED", "sanctions fail", false, null);
                kycApplications.onOnboardingDecision(org.getId(), false, reviewer, "sanctions fail");
                return new Dtos.OrgDecisionResult(req.getId(), org.getId(), "BLOCKED",
                        "Sanctions check failed; onboarding blocked");
            }
            lifecycle.transition(org, Enums.OrgStatus.ACTIVE, reviewer.userId());
            provisioningApproved(org, reviewer.userId(), reviewer.role());
            req.setStatus(Enums.OnboardingStatus.APPROVED);
            requestRepo.save(req);
            User admin = userRepo.findById(req.getAdminUserId())
                    .orElseThrow(() -> new NotFoundException("Admin user for org not found"));
            if (properties.getIdentityMode().equals("legacy")) {
                activation.issue(admin, reviewer.userId());
            }  // gateway mode: the enrolment issued at creation already queued the set-up link
            auditService.record(reviewer.userId(), reviewer.role(), "org_approved", "organization",
                    org.getId(), "PENDING", "ACTIVE", null, false, null);
            kycApplications.onOnboardingDecision(org.getId(), true, reviewer, note);
            return new Dtos.OrgDecisionResult(req.getId(), org.getId(), "ACTIVE",
                    "Organization approved and provisioned; activation link queued");
        }

        lifecycle.transition(org, Enums.OrgStatus.REJECTED, reviewer.userId());
        req.setStatus(Enums.OnboardingStatus.REJECTED);
        requestRepo.save(req);
        archivePendingUsers(org);
        auditService.record(reviewer.userId(), reviewer.role(), "org_rejected", "organization",
                org.getId(), "PENDING", "REJECTED", note, false, null);
        kycApplications.onOnboardingDecision(org.getId(), false, reviewer, note);
        return new Dtos.OrgDecisionResult(req.getId(), org.getId(), "REJECTED",
                "Onboarding rejected; pending admin archived ([M7])");
    }

    private void provisioningApproved(Organization org, String reviewerId, String reviewerRole) {
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

    private Dtos.OrgDecisionResult decideAdminRecovery(OnboardingRequest req, Current.Principal reviewer,
                                                       boolean approve, String note) {
        Organization org = orgRepo.findById(req.getOrgId())
                .orElseThrow(() -> new NotFoundException("Organization not found"));
        User admin = req.getAdminUserId() == null ? null : userRepo.findById(req.getAdminUserId()).orElse(null);
        req.setReviewedBy(reviewer.userId());
        req.setReviewedAt(Instant.now());
        req.setNote(note);
        if (approve) {
            if (admin == null) {
                throw new ConflictException("CANDIDATE_MISSING", "Recovery candidate user missing");
            }
            req.setStatus(Enums.OnboardingStatus.APPROVED);
            requestRepo.save(req);
            if (properties.getIdentityMode().equals("legacy")) {
                activation.issue(admin, reviewer.userId());
            }  // gateway mode: the replacement candidate was enrolled when the request was filed
            auditService.record(reviewer.userId(), reviewer.role(), "org_admin_recovery_approved", "organization",
                    org.getId(), null, null, "replacement admin activation issued", false, null);
            return new Dtos.OrgDecisionResult(req.getId(), org.getId(), "PENDING",
                    "Replacement admin issued; activation link pending");
        }
        req.setStatus(Enums.OnboardingStatus.REJECTED);
        requestRepo.save(req);
        if (admin != null && admin.getStatus() == Enums.UserStatus.PENDING) {
            archiveUser(admin);
        }
        auditService.record(reviewer.userId(), reviewer.role(), "org_admin_recovery_rejected", "organization",
                org.getId(), null, null, note, false, null);
        return new Dtos.OrgDecisionResult(req.getId(), org.getId(), "REJECTED",
                "Admin recovery rejected; candidate archived ([M7])");
    }

    /** [M7]: reject/block archives the org's PENDING users; the business can be onboarded again. */
    private void archivePendingUsers(Organization org) {
        userRepo.findByOrgIdAndStatus(org.getId(), Enums.UserStatus.PENDING).forEach(this::archiveUser);
    }

    private void archiveUser(User u) {
        String tag = "arch-" + u.getId().substring(0, Math.min(5, u.getId().length()))
                + "-" + Long.toString(System.currentTimeMillis(), 36);
        u.setPhone(tag);
        if (u.getUsername() != null) {
            u.setUsername(tag);
        }
        u.setStatus(Enums.UserStatus.SUSPENDED);
        orgUsers.deleteSessions(u.getId());
        userRepo.save(u);
    }

    // ------------------------------------------------------------------
    // staff [C3/H9]
    // ------------------------------------------------------------------

    @Transactional
    public Dtos.OrgStaffView addStaff(Current.Principal caller, Dtos.StaffRequest req) {
        Organization org = tree.ownOrg(caller);
        if (org == null || org.getOrgType() == Enums.OrgType.PLATFORM) {
            throw new ForbiddenException("PLATFORM_STAFF_REFUSED",
                    "Platform users are seeded or provisioned outside this feature ([C3/H9])");
        }
        if (caller.orgRole() == null || !Enums.OrgMemberRole.ADMIN.name().equals(caller.orgRole())) {
            throw new ForbiddenException("ADMIN_REQUIRED", "Only an org ADMIN may add staff");
        }
        orgAccess.requireOperational(org.getId());
        if (userRepo.existsByPhone(req.phone())) {
            throw new ConflictException("DUPLICATE_PHONE", "Phone already registered");
        }
        usernamePolicy.validate(req.username());   // [H6]
        if (req.username() != null && !req.username().isBlank()
                && userRepo.findByUsername(req.username()).isPresent()) {
            throw new ConflictException("DUPLICATE_USERNAME", "Username already taken");
        }

        User staff;
        if (properties.getIdentityMode().equals("legacy")) {
            staff = new User();
        staff.setName(req.name());
        staff.setPhone(req.phone());
        staff.setEmail(req.email());
        staff.setUsername(req.username() == null || req.username().isBlank()
                ? usernamePolicy.generate(req.name(), req.email()) : req.username());
        staff.setRole(roleFor(org.getOrgType()));    // always the org's own role [C3/H9]
        staff.setOrgId(org.getId());
        staff.setOrgRole(req.orgRole() == null ? Enums.OrgMemberRole.STAFF : req.orgRole());
        staff.setCreatedBy(caller.userId());
        if (req.password() != null && !req.password().isBlank()) {
            staff.setPasswordHash(passwordEncoder.encode(req.password()));
            staff.setStatus(Enums.UserStatus.ACTIVE);
            staff.setActivatedAt(Instant.now());
        } else {
            staff.setStatus(Enums.UserStatus.PENDING);   // activation round-trip
        }
            userRepo.save(staff);
            if (staff.getStatus() == Enums.UserStatus.PENDING) activation.issue(staff, caller.userId());
        } else {
            // gateway mode: no API-submitted passwords — staff is created PENDING and enrolled;
            // the setup link goes out through the comms plane (resend via the auth access endpoints)
            staff = userLifecycle.create(new com.mipay.sme.security.auth.UserLifecycleService.Registration(
                    org.getId(), req.name(), req.username(), req.phone(), req.email(),
                    roleFor(org.getOrgType()),
                    req.orgRole() == null ? Enums.OrgMemberRole.STAFF : req.orgRole(),
                    caller.userId()));
        }
        auditService.record(caller.userId(), caller.role(), "org_staff_added", "user", staff.getId(),
                null, "ACTIVE", "orgRole=" + staff.getOrgRole(), false, null);
        return new Dtos.OrgStaffView(staff.getId(), staff.getName(), staff.getUsername(), staff.getPhone(),
                staff.getEmail(), staff.getRole().name(), staff.getOrgRole().name(), staff.getStatus().name(),
                staff.getActivatedAt());
    }

    @Transactional
    public Dtos.Message deactivateUser(Current.Principal caller, String userId) {
        User target = userRepo.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        if (target.getOrgId() == null) {
            throw new ForbiddenException("NO_ORG", "User has no org binding");
        }
        assertUserAdminAuthority(caller, target.getOrgId());
        if (target.getStatus() == Enums.UserStatus.SUSPENDED) {
            throw new ConflictException("ALREADY_SUSPENDED", "User already suspended");
        }
        // refuse for the last active ADMIN of the org
        if (target.getOrgRole() == Enums.OrgMemberRole.ADMIN
                && target.getStatus() == Enums.UserStatus.ACTIVE
                && orgUsers.countActiveAdmins(target.getOrgId()) <= 1) {
            throw new ConflictException("LAST_ADMIN",
                    "Refusing to deactivate the last active ADMIN of the org");
        }
        target.setStatus(Enums.UserStatus.SUSPENDED);
        userRepo.save(target);
        orgUsers.deleteSessions(target.getId());   // session kill on deactivation
        auditService.record(caller.userId(), caller.role(), "user_deactivated", "user", target.getId(),
                "ACTIVE", "SUSPENDED", "org=" + target.getOrgId(), false, null);
        return new Dtos.Message("User deactivated; sessions deleted", target.getId());
    }

    /** Org ADMIN for their own org; ancestor ADMIN or platform for descendants. */
    private void assertUserAdminAuthority(Current.Principal caller, String targetOrgId) {
        if (tree.platformStaff(caller)) return;
        Organization callerOrg = tree.orgOf(caller);
        if (callerOrg == null || callerOrg.getOrgType() == Enums.OrgType.PLATFORM) {
            throw new ForbiddenException("PLATFORM_STAFF_REFUSED", "Platform org handles its own access");
        }
        boolean admin = caller.orgRole() != null && Enums.OrgMemberRole.ADMIN.name().equals(caller.orgRole());
        boolean selfOrDescendant = callerOrg.getId().equals(targetOrgId)
                || closureRepo.existsByAncestorIdAndDescendantId(callerOrg.getId(), targetOrgId);
        if (!admin || !selfOrDescendant) {
            throw new ForbiddenException("ADMIN_REQUIRED",
                    "Requires an ADMIN of the org or an ancestor");
        }
    }

    // ------------------------------------------------------------------
    // admin recovery ([C3/H9] review-gated replacement admin)
    // ------------------------------------------------------------------

    @Transactional
    public Dtos.OrgDecisionResult requestAdminRecovery(Current.Principal caller, String orgId,
                                                       Dtos.AdminRecoveryRequest body) {
        Organization org = orgRepo.findById(orgId)
                .orElseThrow(() -> new NotFoundException("Organization not found"));
        if (org.getOrgType() == Enums.OrgType.PLATFORM) {
            throw new ForbiddenException("PLATFORM_STAFF_REFUSED",
                    "Platform admins are provisioned outside this feature");
        }
        assertUserAdminAuthority(caller, orgId);
        if (orgUsers.countActiveAdmins(orgId) > 0) {
            throw new ConflictException("ADMIN_EXISTS", "Org still has an active admin");
        }
        if (userRepo.existsByPhone(body.phone())) {
            throw new ConflictException("DUPLICATE_PHONE", "Phone already registered");
        }

        // replacement admin through the lifecycle service: existing eligible org, PENDING row,
        // ENROL committed atomically — the reviewer's approval then only enables delivery
        User candidate = userLifecycle.create(new com.mipay.sme.security.auth.UserLifecycleService.Registration(
                org.getId(), body.adminName(), body.username(), body.phone(), body.email(),
                roleFor(org.getOrgType()), Enums.OrgMemberRole.ADMIN, caller.userId()));

        OnboardingRequest req = new OnboardingRequest();
        req.setKind(Enums.OnboardingKind.ADMIN_RECOVERY);
        req.setStatus(Enums.OnboardingStatus.PENDING_REVIEW);
        req.setOrgId(org.getId());
        req.setParentOrgId(org.getParentId());
        req.setAdminUserId(candidate.getId());
        req.setRequestedBy(caller.userId());
        req.setRequestedByRole(caller.role());
        req.setRequestedByOrgId(caller.orgId());
        req.setNote("replacement admin for " + org.getName());
        req.setMeta("reason=admin-recovery");
        requestRepo.save(req);

        auditService.record(caller.userId(), caller.role(), "org_admin_recovery_requested", "organization",
                org.getId(), null, "PENDING", "candidate=" + candidate.getId(), false, null);
        return new Dtos.OrgDecisionResult(req.getId(), org.getId(), "PENDING",
                "Admin recovery request awaits an independent review");
    }

    // ------------------------------------------------------------------

    public Dtos.OrgView toView(Organization o) {
        Set<String> held = orgAccess.directlySuspended(List.of(o.getId()));
        Set<String> inherited = new HashSet<>();
        String parentId = o.getParentId();
        Set<String> seen = new HashSet<>();
        while (parentId != null && seen.add(parentId)) {
            inherited.add(parentId);
            parentId = orgRepo.findById(parentId).map(Organization::getParentId).orElse(null);
        }
        held.addAll(orgAccess.directlySuspended(new ArrayList<>(inherited)));
        String eff = tree.effectiveStatus(o, held);
        return new Dtos.OrgView(o.getId(), o.getOrgType().name(), o.getName(), o.getLifecycle().name(),
                eff, o.getParentId(), o.getEntityId(), o.getRegistrationNumber(), o.getCreatedAt(),
                o.getKycStatus() == null ? null : o.getKycStatus().name(),
                o.getKycRiskRating() == null ? null : o.getKycRiskRating().name(),
                o.getKycNextReviewDate());
    }

    private Enums.UserRole roleFor(OrgType t) {
        return switch (t) {
            case PLATFORM -> Enums.UserRole.TPTS_OPS;   // never used for creation
            case MANUFACTURER -> Enums.UserRole.MANUFACTURER;
            case DISTRIBUTOR -> Enums.UserRole.DISTRIBUTOR;
            case SUPPLIER -> Enums.UserRole.SUPPLIER;
            case MERCHANT -> Enums.UserRole.MERCHANT;
            case LENDER -> Enums.UserRole.LENDER;
        };
    }
}
