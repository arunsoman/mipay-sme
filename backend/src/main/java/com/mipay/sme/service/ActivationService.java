package com.mipay.sme.service;

import com.mipay.sme.common.BadRequestException;
import com.mipay.sme.common.ConflictException;
import com.mipay.sme.common.ForbiddenException;
import com.mipay.sme.common.HashUtil;
import com.mipay.sme.common.NotFoundException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.domain.org.ActivationToken;
import com.mipay.sme.domain.org.NotificationMessage;
import com.mipay.sme.domain.org.SimOutbox;
import com.mipay.sme.repository.ActivationTokenRepository;
import com.mipay.sme.repository.NotificationMessageRepository;
import com.mipay.sme.repository.SimOutboxRepository;
import com.mipay.sme.repository.UserRepository;
import com.mipay.sme.security.Current;
import com.mipay.sme.web.Dtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Activation mechanics ([C4/H8/L2/M8]): 32 random bytes, SHA-256 stored only,
 * 72h expiry, single-use atomic consumption, PENDING → ACTIVE only. The link
 * is never stored or returned: notification_messages carries only the token
 * id; the raw token exists only in the sandbox sim_outbox for the reader.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivationService {

    private static final Duration TTL = Duration.ofHours(72);

    private final ActivationTokenRepository tokenRepo;
    private final NotificationMessageRepository notificationRepo;
    private final SimOutboxRepository simOutboxRepo;
    private final UserRepository userRepo;
    private final OrgAccess orgAccess;
    private final UsernamePolicy usernamePolicy;
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;
    private final com.mipay.sme.config.AppProperties properties;
    /** Comms-plane delivery of the set-up link (email, then SMS), when the comms plane is configured. */
    private final org.springframework.beans.factory.ObjectProvider<com.mipay.sme.security.auth.LinkDelivery> linkDelivery;
    private final org.springframework.beans.factory.ObjectProvider<com.mipay.sme.service.OrgTreeService> orgTree;

    @Value("${app.environment:sandbox}")
    private String environment;

    @Value("${app.public-base-url:http://localhost:8080}")
    private String publicBaseUrl;

    private final SecureRandom random = new SecureRandom();

    /** Issue (or re-issue) an activation token for a PENDING user. Returns the token id. */
    @Transactional
    public String issue(User user, String issuedBy) {
        if (user == null) {
            throw new IllegalArgumentException("activation target user is null");
        }
        // PENDING only ([C4]); ACTIVE users get resend-refused upstream
        if (user.getStatus() == Enums.UserStatus.ACTIVE) {
            throw new ConflictException("USER_ACTIVE", "User is already activated");
        }
        tokenRepo.invalidateAllForUser(user.getId(), Instant.now());

        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String rawToken = HexFormat.of().formatHex(raw);
        ActivationToken token = new ActivationToken();
        token.setTokenHash(HashUtil.sha256(rawToken));
        token.setUserId(user.getId());
        token.setExpiresAt(Instant.now().plus(TTL));
        token.setIssuedBy(issuedBy);
        tokenRepo.save(token);

        // real outbox: no link, only the token id ([H8])
        NotificationMessage msg = new NotificationMessage();
        msg.setChannel(user.getEmail() != null ? "EMAIL" : "SMS");
        msg.setRecipient(masked(user.getEmail() != null ? user.getEmail() : user.getPhone()));
        msg.setEventCode("ACTIVATION");
        msg.setTemplateVars("name=" + (user.getName() == null ? "" : user.getName())
                + ";username=" + (user.getUsername() == null ? "" : user.getUsername())
                + ";expiresInHours=72");
        msg.setActivationTokenId(token.getId());
        msg.setStatus("QUEUED");
        notificationRepo.save(msg);

        // raw-token mirror, kept in every environment (2026-11-12): a user manager can show and
        // copy a pending person's set-up link from User management
        if (true) {
            SimOutbox outbox = new SimOutbox();
            outbox.setPurpose("ACTIVATION");
            outbox.setUserId(user.getId());
            outbox.setActivationTokenId(token.getId());
            outbox.setRawToken(rawToken);
            outbox.setMeta("destination=" + msg.getRecipient());
            simOutboxRepo.save(outbox);
        }

        // the set-up link goes out through the comms plane (email first, then SMS); a comms outage
        // never blocks the issue — the user manager can still copy the link
        String link = linkFor(rawToken);
        com.mipay.sme.security.auth.LinkDelivery delivery = linkDelivery.getIfAvailable();
        if (delivery != null) {
            try {
                delivery.deliver(new com.mipay.sme.security.auth.LinkDelivery.Request(user.getId(), token.getId(), "ENROL",
                        link, token.getExpiresAt()));
                msg.setStatus("SENT");
                notificationRepo.save(msg);
            } catch (RuntimeException e) {
                log.error("[activation] set-up link for {} not delivered through comms: {}", user.getId(), e.getMessage());
            }
        }

        // audit carries the hash only — never the raw token ([C4/H8])
        auditService.record(issuedBy, "SYSTEM", "activation_issued", "user", user.getId(),
                null, null, "tokenHash=" + HashUtil.sha256(rawToken).substring(0, 16)
                        + "…;expires in 72h; link assembled at dispatch", false, null);
        return token.getId();
    }

    /** The page a person opens to set their password. */
    public String linkFor(String rawToken) {
        return publicBaseUrl.replaceAll("/+$", "") + "/activate?token=" + rawToken;
    }

    /** The set-up link of an issued token (from the token mirror), or null. */
    @Transactional(readOnly = true)
    public String linkOfToken(String tokenId) {
        return simOutboxRepo.findAll().stream().filter(o -> tokenId.equals(o.getActivationTokenId()))
                .findFirst().map(o -> linkFor(o.getRawToken())).orElse(null);
    }

    /** Resend: only while PENDING, and only by the user's org admin or an ancestor admin. */
    @Transactional
    public String resend(String userId, Current.Principal caller) {
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        if (user.getStatus() != Enums.UserStatus.PENDING) {
            throw new ConflictException("USER_ACTIVE", "Resend is only possible while the user is PENDING");
        }
        if (user.getOrgId() == null) {
            throw new ForbiddenException("NO_ORG", "User has no org binding");
        }
        // who may resend is RBAC's decision (org.user:administer); the person must be in the
        // caller's org or an org below it
        var tree = orgTree.getIfAvailable();
        if (tree != null) tree.visible(caller, user.getOrgId());
        String outboxId = issue(user, caller.userId());
        return outboxId;
    }

    /**
     * Set the password and consume the token in one transaction ([C4/H8]).
     * 1) password policy first → 2) atomic token use → 3) PENDING + org operational
     * → 4) store bcrypt hash, set ACTIVE → 5) audit. Failures: 400.
     */
    @Transactional
    public User activate(String rawToken, String password) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new BadRequestException("ACTIVATION_INVALID", "Activation token missing");
        }
        String hash = HashUtil.sha256(rawToken);

        // the token identifies the user before we can validate their username/phone policy
        ActivationToken token = tokenRepo.findByTokenHash(hash)
                .orElseThrow(() -> new BadRequestException("ACTIVATION_INVALID",
                        "Activation link is invalid, expired or already used"));
        User user = userRepo.findById(token.getUserId())
                .orElseThrow(() -> new BadRequestException("ACTIVATION_INVALID", "Activation user missing"));

        if (!properties.getIdentityMode().equals("legacy")) {
            // gateway mode: activation links are retired — users set up credentials through
            // the IdP via one-time enrolment links; core status only changes on CREDENTIAL_SET
            throw new BadRequestException("ACTIVATION_RETIRED",
                    "Activation links are retired in gateway mode; use the credential setup link");
        }

        // 1) password policy first (at least 12 chars, not the username, not the phone) [H8]
        validatePassword(user, password);

        // 2) single atomic consumption: reuse/expired/unknown → the same 400 (UAT TC-HON)
        Instant now = Instant.now();
        int consumed = tokenRepo.consume(hash, now);
        if (consumed == 0) {
            throw new BadRequestException("ACTIVATION_INVALID",
                    "Activation link is invalid, expired or already used");
        }

        // 3) PENDING only + operational org
        if (user.getStatus() != Enums.UserStatus.PENDING) {
            throw new BadRequestException("ACTIVATION_INVALID", "User is not awaiting activation");
        }
        orgAccess.requireOperational(user.getOrgId());

        // 4) commit
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setStatus(Enums.UserStatus.ACTIVE);
        user.setActivatedAt(now);
        userRepo.save(user);
        notificationRepo.findByActivationTokenId(token.getId()).forEach(m -> {
            m.setStatus("SENT");
            notificationRepo.save(m);
        });

        // 5) audit
        auditService.record(user.getId(), user.getRole().name(), "activation_completed", "user",
                user.getId(), "PENDING", "ACTIVE", null, false, null);
        return user;
    }

    /** Password policy [H8]. */
    private void validatePassword(User user, String password) {
        if (password == null || password.length() < 12) {
            throw new BadRequestException("ACTIVATION_INVALID", "Password must be at least 12 characters");
        }
        if (user.getUsername() != null && password.equalsIgnoreCase(user.getUsername())) {
            throw new BadRequestException("ACTIVATION_INVALID", "Password must differ from the username");
        }
        String digits = user.getPhone() == null ? "" : user.getPhone().replaceAll("\\D", "");
        if (!digits.isEmpty() && password.replaceAll("\\D", "").contains(digits)) {
            throw new BadRequestException("ACTIVATION_INVALID", "Password must not contain the phone number");
        }
    }

    /**
     * Sandbox outbox reading ([M8]): the message row PLUS an assembled link.
     * MINDLABS_ADMIN only, sandbox environment only (not merely !production).
     */
    @Transactional(readOnly = true)
    public List<Dtos.OutboxRow> outbox(Current.Principal caller) {
        if (!"sandbox".equalsIgnoreCase(environment)) {
            throw new ForbiddenException("SANDBOX_ONLY", "The simulated outbox exists only in sandbox");
        }
        if (!Enums.UserRole.MINDLABS_ADMIN.name().equals(caller.role())) {
            throw new ForbiddenException("ADMIN_REQUIRED", "MINDLABS_ADMIN only");
        }
        return notificationRepo.findTop100ByOrderByCreatedAtDesc().stream()
                .map(m -> {
                    String link = null;
                    if ("ACTIVATION".equals(m.getEventCode()) && m.getActivationTokenId() != null) {
                        link = simOutboxRepo.findAll().stream()
                                .filter(o -> m.getActivationTokenId().equals(o.getActivationTokenId()))
                                .findFirst()
                                .map(o -> publicBaseUrl + "/api/auth/activate?token=" + o.getRawToken())
                                .orElse(null);
                    }
                    return new Dtos.OutboxRow(m.getId(), m.getEventCode(), m.getChannel(), m.getRecipient(),
                            m.getStatus(), m.getCreatedAt(), link);
                })
                .toList();
    }

    /** Raw token for the link, when present. Used by the sim reader path only. */
    private String masked(String contact) {
        if (contact == null || contact.length() <= 4) return "****";
        return contact.substring(0, 2) + "***" + contact.substring(contact.length() - 2);
    }
}
