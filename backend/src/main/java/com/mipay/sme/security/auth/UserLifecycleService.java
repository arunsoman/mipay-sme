package com.mipay.sme.security.auth;

import com.mipay.sme.common.ForbiddenException;
import com.mipay.sme.common.SimException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.IdentityLink;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.domain.org.Organization;
import com.mipay.sme.repository.IdentityLinkRepository;
import com.mipay.sme.repository.OrganizationRepository;
import com.mipay.sme.repository.UserRepository;
import com.mipay.sme.service.OrgAccess;
import com.mipay.sme.service.UsernamePolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.Map;

/**
 * The only path for user creation, status transitions and login/contact changes. Every change
 * persists core state and its provider commands in one transaction: the outbox row commits
 * together with the user row it belongs to. Org moves do not exist in this service — a user's
 * home org is immutable ([U: every user belongs to an org; the org's state governs the user]).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserLifecycleService {
    @PersistenceContext
    private EntityManager em;
    private final UserRepository users;
    private final IdentityLinkRepository links;
    private final OrganizationRepository orgs;
    private final OrgAccess orgAccess;
    private final AuthProviderRegistry registry;
    private final AuthOutboxService outbox;
    private final UsernamePolicy usernamePolicy;
    private final CommsClient comms;

    public record Registration(String orgId, String name, String username, String phone, String email,
                               Enums.UserRole role, Enums.OrgMemberRole orgRole, String createdBy) { }

    /** Creates a user (PENDING, no credentials) in an existing eligible org and enqueues its ENROL command. */
    @Transactional
    public User create(Registration reg) {
        Organization org = orgs.findById(reg.orgId())
                .orElseThrow(() -> new ForbiddenException("NO_ORG", "user home org does not exist"));
        boolean pendingApplicant = org.getLifecycle() == Enums.OrgStatus.PENDING
                && reg.orgRole() == Enums.OrgMemberRole.ADMIN;
        if (!Enums.OrgType.PLATFORM.equals(org.getOrgType())
                && !orgAccess.isOperational(org.getId()) && !pendingApplicant) {
            throw new ForbiddenException("ORG_INELIGIBLE",
                    "users may only be created in operational or pending-applicant orgs");
        }
        if (users.existsByPhone(reg.phone()))
            throw new SimException("DUPLICATE_PHONE", "Phone already registered");

        User user = new User();
        user.setName(reg.name());
        user.setPhone(reg.phone());
        user.setEmail(reg.email());
        user.setUsername(reg.username() == null || reg.username().isBlank()
                ? usernamePolicy.generate(reg.name(), reg.email()) : reg.username());
        user.setRole(reg.role());
        user.setOrgRole(reg.orgRole());
        user.setStatus(Enums.UserStatus.PENDING);
        user.setOrgId(reg.orgId());
        user.setCreatedBy(reg.createdBy() == null ? "lifecycle" : reg.createdBy());
        users.save(user);
        // the provider command lives in the same transaction and its FK points at the user row,
        // so the row must be visible to SQL now: flush (not commit!) before enqueueing
        em.flush();

        if (registry.enabled()) {
            outbox.enqueue(user.getId(), registry.selected().key(), "ENROL",
                    Map.of("user_id", user.getId(), "login_name", user.getUsername(),
                            "phone", user.getPhone() == null ? "" : user.getPhone(),
                            "display_name", user.getName() == null ? "" : user.getName()),
                    "enrol:" + user.getId());
        } else {
            log.debug("auth provider disabled; user {} created without enrolment command", user.getId());
        }
        syncContactsAfterCommit(user.getId(), user.getPhone(), user.getEmail());
        return user;
    }

    /** Applies CREDENTIAL_SET: the matching link becomes ACTIVE and its pending user is activated. */
    @Transactional
    public void activateFromCredentialSet(String subject, String uid, Instant at) {
        IdentityLink link = links.findByUserId(uid).stream()
                .filter(row -> "trustplane".equals(row.getProvider())).findFirst().orElse(null);
        if (link == null || !subject.equals(link.getSubject())) {
            log.error("CREDENTIAL_SET ignored: no matching active link for user {} subject {}", uid, subject);
            return;
        }
        if (link.getStatus() != IdentityLink.Status.PENDING) {
            log.debug("CREDENTIAL_SET replay for already-{} link of user {}", link.getStatus(), uid);
            return;
        }
        link.setStatus(IdentityLink.Status.ACTIVE);
        link.setIdpEnabled(true);
        link.setActivatedAt(at == null ? Instant.now() : at);
        links.save(link);
        users.findById(uid).ifPresent(user -> {
            if (user.getStatus() == Enums.UserStatus.PENDING) {
                user.setStatus(Enums.UserStatus.ACTIVE);
                users.save(user);
            }
        });
    }

    /** Login/contact change on an enrolled user; the IdP account is updated through the outbox. */
    @Transactional
    public void updateLoginContact(String uid, String username, String phone, String email) {
        User user = users.findById(uid)
                .orElseThrow(() -> new SimException("UNKNOWN_USER", "user does not exist"));
        if (phone != null && !phone.isBlank() && !phone.equals(user.getPhone())
                && users.existsByPhone(phone)) {
            throw new SimException("DUPLICATE_PHONE", "Phone already registered");
        }
        if (username != null && !username.isBlank()) user.setUsername(username);
        if (phone != null && !phone.isBlank()) user.setPhone(phone);
        if (email != null && !email.isBlank()) user.setEmail(email);
        users.save(user);
        IdentityLink link = links.findByUserId(uid).stream()
                .filter(row -> "trustplane".equals(row.getProvider())).findFirst().orElse(null);
        if (link != null && link.getSubject() != null && !link.getSubject().isBlank()) {
            outbox.enqueue(uid, "trustplane", "UPDATE_LOGIN",
                    Map.of("user_id", uid, "subject", link.getSubject(),
                            "login_name", user.getUsername(), "phone", user.getPhone() == null ? "" : user.getPhone()),
                    "login:" + uid + ":" + user.getUsername() + ":" + user.getPhone());
        }
        syncContactsAfterCommit(uid, user.getPhone(), user.getEmail());
    }

    /**
     * The core is the comms plane's only contact writer (comms-design D10). Registration
     * happens after commit and is outage-tolerant: core state stays authoritative, the
     * send-time sync in CommsLinkDelivery and the AuthSyncService reconcile close any gap.
     */
    private void syncContactsAfterCommit(String uid, String phone, String email) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            comms.putContacts(uid, phone, email);
                        } catch (Exception e) {
                            log.warn("comms contact sync deferred for user {}: {}", uid, e.getMessage());
                        }
                    }
                });
    }
}
