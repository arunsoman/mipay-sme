package com.mipay.sme.security.auth;

import com.mipay.sme.config.AppProperties;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.IdentityLink;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.domain.ops.Suspension;
import com.mipay.sme.repository.IdentityLinkRepository;
import com.mipay.sme.repository.OrgClosureRepository;
import com.mipay.sme.repository.OrganizationRepository;
import com.mipay.sme.repository.SuspensionRepository;
import com.mipay.sme.repository.UserRepository;
import com.mipay.sme.security.Current;
import com.mipay.sme.security.rbac.RbacMeshClient;
import com.mipay.sme.service.OrgAccess;
import com.mipay.sme.service.OrgSuspensionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The reconcile (cutover plan §3.3b): core state is authoritative, IdP accounts follow. Called
 * on org lifecycle and hold changes, KYC outcomes and a nightly repair, it walks the affected
 * subtree (or repair set) and brings every identity link in step: ineligible links are disabled
 * with their sessions revoked, eligible links are re-enabled, and a lapsed periodic review holds
 * the org exactly like a manual suspension (§3.3d). Idempotent: a command is only queued while
 * no equivalent is still in flight, and both disable and enable are safe to retry.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthSyncService {
    static final String KYC_LAPSED_MARK = "KYC_LAPSED";
    public static final String ACTOR_SYSTEM_KYC = "system:kyc";

    private final UserRepository users;
    private final IdentityLinkRepository links;
    private final OrganizationRepository orgs;
    private final OrgClosureRepository closureRepo;
    private final SuspensionRepository suspensions;
    private final OrgAccess orgAccess;
    private final AuthOutboxService outbox;
    private final AuthProviderRegistry registry;
    private final RbacMeshClient rbac;
    private final AppProperties properties;
    private final JdbcTemplate jdbc;

    // lazy: suspend/lift depend on AuthSyncService for the post-commit reconcile (§3.3b),
    // while lapse placement (rare) reuses the suspension service — the loop would not resolve
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private OrgSuspensionService suspensionService;

    /**
     * Lapse holds and lifts run in their own transaction: an inner failure must not mark the
     * caller's transaction rollback-only, and every hold stands or falls on its own.
     */
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.transaction.PlatformTransactionManager txManager;

    private <T> T inNewTx(java.util.function.Supplier<T> work) {
        var template = new org.springframework.transaction.support.TransactionTemplate(txManager);
        template.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> work.get());
    }

    public record SyncResult(int disabled, int enabled, int unchanged) { }

    // ---------------------------------------------------------------- reconcile

    /** Reconciles the given users' links against core eligibility. Safe after commit. */
    @Transactional
    public SyncResult reconcileUsers(List<String> uids) {
        int disabled = 0;
        int enabled = 0;
        int unchanged = 0;
        for (String uid : uids == null ? List.<String>of() : uids) {
            int[] step = reconcileUser(uid);
            disabled += step[0];
            enabled += step[1];
            unchanged += step[2];
        }
        return new SyncResult(disabled, enabled, unchanged);
    }

    /** Reconciles every user in the org's subtree (org holds, lifecycle and KYC outcomes). */
    @Transactional
    public SyncResult reconcileOrg(String orgId) {
        List<String> subtree = closureRepo.findSubtree(orgId).stream()
                .map(r -> r.getOrg().getId()).toList();
        if (subtree.isEmpty()) subtree = List.of(orgId);
        List<User> members = users.findByOrgIdIn(subtree);
        return reconcileUsers(members.stream().map(User::getId).toList());
    }

    /**
     * One user, one decision (§1.5 U3): a user may hold an enabled credential exactly when
     * they are core-ACTIVE and their org is operational — with the pending-applicant
     * exception (a KYC applicant admin of a PENDING org keeps their credential; the resolver
     * confines them to their own KYC screens regardless).
     */
    private int[] reconcileUser(String uid) {
        if (!registry.enabled()) return new int[]{0, 0, 0};   // legacy mode: no provider, nothing to reconcile
        User user = users.findById(uid).orElse(null);
        if (user == null) return new int[0];
        IdentityLink link = links.findByUserId(uid).stream()
                .filter(l -> l.getSubject() != null && !l.getSubject().isBlank()).findFirst().orElse(null);
        if (link == null) return new int[0];   // never enrolled: nothing at the IdP to reconcile

        boolean enabled = link.getStatus() == IdentityLink.Status.ACTIVE && link.isIdpEnabled();
        boolean eligible = user.getStatus() == Enums.UserStatus.ACTIVE && user.getOrgId() != null
                && (orgAccess.isOperational(user.getOrgId()) || pendingApplicantEligible(user));
        boolean archived = user.getStatus() == Enums.UserStatus.ARCHIVED;

        if (!eligible && enabled) {
            String command = archived ? "UNLINK" : "DISABLE";
            if (commandInFlight(uid, command, link.getSubject())) return new int[]{0, 0, 1};
            String key = "reconcile:" + uid + ":" + System.currentTimeMillis();
            outbox.enqueue(uid, providerKey(), "DISABLE", Map.of("user_id", uid, "subject", link.getSubject(),
                    "reason", archived ? "core archived the user" : "core state no longer permits access"), key);
            if (archived) {
                outbox.enqueue(uid, providerKey(), "UNLINK", Map.of("user_id", uid, "subject", link.getSubject(),
                        "requested_by", "reconcile"), key + ":unlink");
            } else {
                outbox.enqueue(uid, providerKey(), "REVOKE_SESSIONS", Map.of("user_id", uid, "subject", link.getSubject(),
                        "requested_by", "reconcile"), key + ":revoke");
            }
            log.debug("reconcile: disabling user {} (status={}, org={})", uid, user.getStatus(), user.getOrgId());
            return new int[]{1, 0, 0};
        }
        if (eligible && !enabled && user.getStatus() == Enums.UserStatus.ACTIVE
                && (link.getStatus() == IdentityLink.Status.DISABLED || !link.isIdpEnabled())) {
            if (commandInFlight(uid, "ENABLE", link.getSubject())) return new int[]{0, 0, 1};
            outbox.enqueue(uid, providerKey(), "ENABLE", Map.of("user_id", uid, "subject", link.getSubject(),
                    "reason", "core state permits access again"), stateKey("re-enable", uid));
            log.debug("reconcile: re-enabling user {}", uid);
            return new int[]{0, 1, 0};
        }
        if (eligible) reconcileMfa(user, link);   // keep the MFA profile in step with org policy
        return new int[]{0, 0, 1};
    }

    /** The pending-applicant exception, mirrored from the resolver: PENDING org, its ADMIN. */
    private boolean pendingApplicantEligible(User user) {
        return user.getOrgRole() == Enums.OrgMemberRole.ADMIN && orgAccess.isPendingApplicantOrg(user.getOrgId());
    }

    /** Never queue a second identical command: the first one will finish the job. */
    private boolean commandInFlight(String uid, String command, String subject) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM auth_outbox
                WHERE user_id = ? AND command = ? AND state IN ('PENDING','RUNNING')
                  AND payload->>'subject' = ?
                """, Integer.class, uid, command, subject);
        return count != null && count > 0;
    }

    private String stateKey(String kind, String uid) {
        return kind + ":" + uid + ":" + System.currentTimeMillis();
    }

    private String providerKey() {
        return registry.enabled() ? registry.selected().key() : "trustplane";
    }

    /** Reconciles the account's MFA profile from org policy (2fa-design §5.1). */
    private void reconcileMfa(User user, IdentityLink link) {
        if (!registry.enabled()) return;
        String subject = link.getSubject();
        if (subject == null || subject.isBlank()) return;
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM auth_outbox WHERE user_id = ? AND command = 'SET_MFA' AND state IN ('PENDING','RUNNING')",
                Integer.class, user.getId());
        if (count != null && count > 0) return;
        var org = user.getOrgId() == null ? null : orgs.findById(user.getOrgId()).orElse(null);
        if (org == null || !org.isMfaRequired()) return;
        AuthPluginContracts.MfaProfile profile =
                Enums.OrgType.PLATFORM.equals(org.getOrgType()) ? AuthPluginContracts.MfaProfile.PLATFORM_STAFF
                        : user.getOrgRole() == Enums.OrgMemberRole.ADMIN ? AuthPluginContracts.MfaProfile.ORG_ADMIN
                        : AuthPluginContracts.MfaProfile.ORG_MEMBER;
        outbox.enqueue(user.getId(), providerKey(), "SET_MFA", Map.of("user_id", user.getId(), "subject", subject,
                        "mfa_profile", profile.name(), "org_requires_mfa", true),
                stateKey("mfa", user.getId()));
    }

    // ---------------------------------------------------------------- KYC lapse holds (§3.3d)

    /**
     * Places a KYC_LAPSED hold on every operational org whose periodic review is overdue
     * (merchant/supplier next-review dates) and holds none yet. Runs nightly and can be
     * called from review flows. Returns the orgs newly held.
     */
    @Transactional
    public List<String> enforceKycLapses() {
        LocalDate today = LocalDate.now();
        List<String> held = new ArrayList<>();
        holdOverdue(overdue(today), held);
        return held;
    }

    private void holdOverdue(List<Map<String, Object>> rows, List<String> held) {
        for (Map<String, Object> row : rows) {
            String orgId = String.valueOf(row.get("org_id"));
            if (suspensions.findByOrgIdAndLiftedAtIsNull(orgId).stream().anyMatch(Suspension::isActive)) continue;
            try {
                String reason = KYC_LAPSED_MARK + ": periodic review overdue since " + row.get("review_date");
                inNewTx(() -> {
                    // the review clock is a platform system actor; system:kyc is the userid it audits as
                    Current.Principal system = new Current.Principal(ACTOR_SYSTEM_KYC, "TPTS_OPS",
                            "KYC review clock", null, null, null, null, null, null, null);
                    suspensionService.suspend(orgId, reason, system);
                    return null;
                });
                held.add(orgId);
            } catch (Exception e) {
                log.warn("kyc lapse hold failed for org {}: {}", orgId, e.getMessage(), e);
            }
        }
    }

    private List<Map<String, Object>> overdue(LocalDate cutoff) {
        // V3 keeps the review clocks on merchants and suppliers; V6 mirrors it on the org
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.addAll(jdbc.queryForList("""
                SELECT o.id AS org_id, m.kyc_next_review_date AS review_date
                FROM organizations o JOIN merchants m ON m.id = o.entity_id
                WHERE o.org_type = 'MERCHANT' AND o.status = 'ACTIVE'
                  AND m.kyc_next_review_date IS NOT NULL AND m.kyc_next_review_date < ?
                """, cutoff));
        rows.addAll(jdbc.queryForList("""
                SELECT o.id AS org_id, s.kyc_next_review_date AS review_date
                FROM organizations o JOIN suppliers s ON s.id = o.entity_id
                WHERE o.org_type = 'SUPPLIER' AND o.status = 'ACTIVE'
                  AND s.kyc_next_review_date IS NOT NULL AND s.kyc_next_review_date < ?
                """, cutoff));
        return rows;
    }

    /** A completed review lifts the org's KYC_LAPSED holds (manual or other holds persist). */
    @Transactional
    public void liftKycLapses(String orgId, String actorId) {
        suspensions.findByOrgIdAndLiftedAtIsNull(orgId).stream()
                .filter(Suspension::isActive)
                .filter(s -> s.getReason() != null && s.getReason().startsWith(KYC_LAPSED_MARK))
                .forEach(s -> {
                    try {
                        String id = s.getId();
                        // a completed review is a platform system action
                        Current.Principal actor = new Current.Principal(actorId == null ? ACTOR_SYSTEM_KYC : actorId,
                                "TPTS_OPS", "review completed", null, null, null, null, null, null, null);
                        inNewTx(() -> {
                            suspensionService.lift(id, actor);
                            return null;
                        });
                        reconcileOrg(orgId);   // the lifted org is operational again: re-enable
                    } catch (Exception e) {
                        log.warn("kyc lapse lift failed for org {}: {}", orgId, e.getMessage());
                    }
                });
    }

    // ---------------------------------------------------------------- nightly repair

    /** Bounded nightly repair: walk every link and bring it in step with core state. */
    @Transactional
    public SyncResult nightlyRepair() {
        int disabled = 0;
        int enabled = 0;
        int unchanged = 0;
        for (IdentityLink link : links.findAll()) {
            int[] step = reconcileUser(link.getUserId());
            disabled += step[0];
            enabled += step[1];
            unchanged += step[2];
        }
        enforceKycLapses();
        log.info("nightly auth repair: disabled={} enabled={} unchanged={}", disabled, enabled, unchanged);
        return new SyncResult(disabled, enabled, unchanged);
    }

    // ---------------------------------------------------------------- go-live swap (§3.5)

    /**
     * The KYC applicant becomes the org's admin at go-live: the RBAC kyc_applicant assignment
     * is revoked and org_admin granted in the org tenant (no duplicate account — the user row
     * does not change). Both writes use the system source kyc_go_live, so they do not depend on
     * the temporary core_authorized bridge. Failures are logged at error level, never fatal;
     * the per-org RBAC reconciliation report shows the drift. If the grant fails the applicant
     * keeps kyc_applicant rather than being left with nothing.
     */
    @Transactional
    public void applicantBecomesAdmin(String orgId, String adminUid, String actorId) {
        if (!properties.isRbacSync()) {
            log.debug("rbac sync disabled: go-live role swap for {} skipped", adminUid);
            return;
        }
        // Since 2026-11-12 there are no standard org roles (org_admin, kyc_applicant are gone): the
        // new org's people get org-defined roles from its user manager (User management).
        if (true) {
            log.info("[go-live] {} in {}: no standard org role to grant (org-defined roles only)", adminUid, orgId);
            return;
        }
        String actor = actorId == null ? ACTOR_SYSTEM_KYC : actorId;
        try {
            // a system source: the core's own decision, independent of the core_authorized bridge
            // core_authorized only for an older RBAC during a rolling deploy; a current RBAC
            // decides system sources without the bridge, so it keeps working after expiry
            rbac.assign(orgId, Map.of("actor", actor, "core_authorized", true, "subject", adminUid,
                    "role", "org_admin", "scope", "own", "reason", "kyc go-live", "source", "kyc_go_live"));
        } catch (Exception e) {
            log.error("[go-live] org_admin grant FAILED for {} in {} - the org admin has no RBAC admin role until repaired: {}",
                    adminUid, orgId, e.getMessage());
            return; // keep kyc_applicant: never leave the admin with neither role
        }
        // revoke the applicant's kyc_applicant assignment (there should be exactly one ACTIVE)
        try {
            for (Map<String, Object> row : rbac.assignments(orgId)) {
                if (adminUid.equals(String.valueOf(row.get("subject")))
                        && "kyc_applicant".equals(String.valueOf(row.get("role")))
                        && "ACTIVE".equalsIgnoreCase(String.valueOf(row.get("status")))) {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("actor", actor);
                    body.put("source", "kyc_go_live");
                    body.put("core_authorized", true);   // older RBAC only; see the grant above
                    body.put("reason", "kyc go-live: applicant becomes the org admin");
                    rbac.revoke(orgId, String.valueOf(row.get("id")), body);
                }
            }
        } catch (Exception e) {
            log.error("[go-live] kyc_applicant revoke FAILED for {} in {}: {}", adminUid, orgId, e.getMessage());
        }
    }
}
