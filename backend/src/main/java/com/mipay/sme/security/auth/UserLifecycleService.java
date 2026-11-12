package com.mipay.sme.security.auth;

import com.mipay.sme.common.ConflictException;
import com.mipay.sme.common.ForbiddenException;
import com.mipay.sme.common.SimException;
import com.mipay.sme.config.AppProperties;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.IdentityLink;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.domain.org.Organization;
import com.mipay.sme.repository.DeviceSessionRepository;
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
import java.util.concurrent.atomic.AtomicLong;

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
    private final DeviceSessionRepository sessions;
    private final AppProperties properties;

    public record Registration(String orgId, String name, String username, String phone, String email,
                               Enums.UserRole role, Enums.OrgMemberRole orgRole, String createdBy) { }

    /** Monotonic discriminator for state-change dedup keys (a key must never repeat across calls). */
    private static final AtomicLong STATE_SEQ = new AtomicLong();

    private String stateKey(String kind, String uid) {
        return kind + ":" + uid + ":" + System.currentTimeMillis() + "-" + STATE_SEQ.incrementAndGet();
    }

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
            enqueueEnrol(user);
        } else {
            log.debug("auth provider disabled; user {} created without enrolment command", user.getId());
        }
        syncContactsAfterCommit(user.getId(), user.getPhone(), user.getEmail());
        return user;
    }

    /**
     * Pre-binding creation for self registration and seeding: the user row exists before its
     * home org does (the entity row that owns the org references the user id). The user is
     * PENDING without credentials; binding to the org (bindToOrg) is what triggers enrolment.
     */
    @Transactional
    public User createPreBinding(Enums.UserRole role, String name, String phone, String email, String createdBy) {
        if (users.existsByPhone(phone)) throw new SimException("DUPLICATE_PHONE", "Phone already registered");
        User user = new User();
        user.setName(name);
        user.setPhone(phone);
        user.setEmail(email);
        user.setRole(role);
        user.setStatus(Enums.UserStatus.PENDING);
        user.setCreatedBy(createdBy == null ? "lifecycle" : createdBy);
        users.save(user);
        em.flush();
        return user;
    }

    /**
     * Binds a pre-binding user to its home org — only from unbound, since a home org is
     * immutable once set — and enrols the now-eligible user. Idempotent for the same org.
     */
    @Transactional
    public User bindToOrg(String uid, String orgId, Enums.OrgMemberRole orgRole, String createdBy) {
        User user = users.findById(uid)
                .orElseThrow(() -> new ForbiddenException("NO_USER", "user to bind does not exist"));
        if (user.getOrgId() != null) {
            if (!user.getOrgId().equals(orgId))
                throw new ConflictException("ORG_MOVE_REFUSED", "a user's home org is immutable");
            return user;   // ensureEntityOrg is called on every access path: re-binding is a no-op
        }
        Organization org = orgs.findById(orgId)
                .orElseThrow(() -> new ForbiddenException("NO_ORG", "home org does not exist"));
        boolean pendingApplicant = org.getLifecycle() == Enums.OrgStatus.PENDING && orgRole == Enums.OrgMemberRole.ADMIN;
        if (!Enums.OrgType.PLATFORM.equals(org.getOrgType())
                && !orgAccess.isOperational(org.getId()) && !pendingApplicant) {
            throw new ConflictException("ORG_INELIGIBLE", "users may only bind to operational or pending-applicant orgs");
        }
        user.setOrgId(org.getId());
        if (orgRole != null) user.setOrgRole(orgRole);
        if (createdBy != null) user.setCreatedBy(createdBy);
        if (user.getUsername() == null || user.getUsername().isBlank())
            user.setUsername(usernamePolicy.generate(user.getName(), user.getEmail()));
        users.save(user);
        em.flush();
        if (registry.enabled()) enqueueEnrol(user);
        syncContactsAfterCommit(user.getId(), user.getPhone(), user.getEmail());
        return user;
    }

    /**
     * Core-first suspension: the user is refused on the next request immediately; the IdP
     * account is disabled and its sessions revoked through the outbox. Idempotent.
     */
    @Transactional
    public User suspend(String uid, String actor) {
        User user = users.findById(uid)
                .orElseThrow(() -> new ForbiddenException("NO_USER", "user does not exist"));
        if (user.getStatus() == Enums.UserStatus.ARCHIVED || user.getStatus() == Enums.UserStatus.SUSPENDED) {
            return user;
        }
        user.setStatus(Enums.UserStatus.SUSPENDED);
        users.save(user);
        em.flush();
        sessions.deleteAll(sessions.findByUserId(uid));
        String key = stateKey("suspend", uid);
        String subject = subjectOf(uid);
        if (subject != null) {
            outbox.enqueue(uid, key(), "DISABLE", Map.of("user_id", uid, "subject", subject,
                    "reason", "suspended by " + actor), key);
            outbox.enqueue(uid, key(), "REVOKE_SESSIONS", Map.of("user_id", uid, "subject", subject,
                    "requested_by", actor == null ? "lifecycle" : actor), key + ":revoke");
        }
        return user;
    }

    /**
     * Reinstates a suspended user and re-enables the IdP account. Refuses for archived users
     * (permanent) and no-ops for ACTIVE/PENDING users whose org state governs eligibility.
     */
    @Transactional
    public User reinstate(String uid, String actor) {
        User user = users.findById(uid)
                .orElseThrow(() -> new ForbiddenException("NO_USER", "user does not exist"));
        if (user.getStatus() == Enums.UserStatus.ARCHIVED)
            throw new ConflictException("ARCHIVED", "archived users cannot be reinstated");
        if (user.getStatus() != Enums.UserStatus.SUSPENDED) return user;
        user.setStatus(Enums.UserStatus.ACTIVE);
        users.save(user);
        em.flush();
        String subject = subjectOf(uid);
        if (subject != null) {
            outbox.enqueue(uid, key(), "ENABLE", Map.of("user_id", uid, "subject", subject,
                    "reason", "reinstated by " + actor), stateKey("reinstate", uid));
        }
        return user;
    }

    /**
     * Terminal archive (rejection, blocking, closure): kills sessions and queues DISABLE +
     * UNLINK; the processor marks the link REVOKED when the IdP confirms. Legacy mode keeps
     * the historical SUSPENDED status; gateway mode writes ARCHIVED.
     */
    @Transactional
    public User archive(User user, String actor) {
        boolean gateway = !properties.getIdentityMode().equals("legacy");
        user.setStatus(gateway ? Enums.UserStatus.ARCHIVED : Enums.UserStatus.SUSPENDED);
        users.save(user);
        em.flush();
        sessions.deleteAll(sessions.findByUserId(user.getId()));
        String subject = subjectOf(user.getId());
        if (gateway && subject != null) {
            String key = stateKey("archive", user.getId());
            outbox.enqueue(user.getId(), key(), "DISABLE", Map.of("user_id", user.getId(), "subject", subject,
                    "reason", "archived by " + actor), key);
            outbox.enqueue(user.getId(), key(), "UNLINK", Map.of("user_id", user.getId(), "subject", subject,
                    "requested_by", actor == null ? "lifecycle" : actor), key + ":unlink");
        }
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
            outbox.enqueue(uid, key(), "UPDATE_LOGIN",
                    Map.of("user_id", uid, "subject", link.getSubject(),
                            "login_name", user.getUsername(), "phone", user.getPhone() == null ? "" : user.getPhone()),
                    // the value can legitimately flip back to an earlier state: the key must never
                    // collide with an older DONE row, or the update would be silently dropped
                    stateKey("login", uid));
        }
        syncContactsAfterCommit(uid, user.getPhone(), user.getEmail());
    }

    /**
     * The comms plane's only contact writer (comms-design D10). Registration
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

    private void enqueueEnrol(User user) {
        outbox.enqueue(user.getId(), key(), "ENROL",
                Map.of("user_id", user.getId(), "login_name", user.getUsername(),
                        "phone", user.getPhone() == null ? "" : user.getPhone(),
                        "display_name", user.getName() == null ? "" : user.getName()),
                "enrol:" + user.getId());
    }

    private String key() {
        return registry.enabled() ? registry.selected().key() : "trustplane";
    }

    /** The provider subject for a user, or null while the user has no delivered credential. */
    private String subjectOf(String uid) {
        return links.findByUserId(uid).stream()
                .filter(l -> l.getSubject() != null && !l.getSubject().isBlank())
                .map(IdentityLink::getSubject).findFirst().orElse(null);
    }
}
