package com.mipay.sme.security.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mipay.sme.config.AppProperties;
import com.mipay.sme.domain.core.AuthOutbox;
import com.mipay.sme.domain.core.IdentityLink;
import com.mipay.sme.repository.IdentityLinkRepository;
import com.mipay.sme.security.auth.AuthPluginContracts.AuthProvider;
import com.mipay.sme.security.auth.AuthPluginContracts.EnrolRequest;
import com.mipay.sme.security.auth.AuthPluginContracts.Enrolment;
import com.mipay.sme.security.auth.AuthPluginContracts.MfaProfile;
import com.mipay.sme.security.auth.AuthPluginContracts.ResetScope;
import com.mipay.sme.security.auth.TrustplaneAuthProvider.AuthProviderException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The single worker that turns durable outbox commands into provider calls. Commands are
 * idempotent; enrol replays through the IdP's Idempotency-Key so a retry never silently
 * invalidates a delivered link. Payloads never carry raw links; delivery goes to the
 * comms plane and is retried until it succeeds.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuthOutboxProcessor {
    static final int BATCH = 20;
    static final Duration LEASE = Duration.ofSeconds(120);

    private final AuthOutboxService outbox;
    private final AuthProviderRegistry registry;
    private final AppProperties properties;
    private final IdentityLinkRepository links;
    private final ObjectMapper mapper;
    private final List<LinkDelivery> sinks;

    @Scheduled(fixedDelayString = "${app.outbox.poll-millis:1000}")
    public void tick() {
        if (!"gateway".equals(properties.getIdentityMode()) || !registry.enabled()) return;
        List<LinkDelivery> deliveries = sinks == null ? List.of() : sinks;
        for (AuthOutbox cmd : outbox.leaseBatch(BATCH, LEASE)) {
            try {
                dispatch(cmd, registry.selected(), deliveries);
                outbox.complete(cmd.getId());
            } catch (AuthProviderException e) {
                if (e.status() >= 500) outbox.fail(cmd.getId(), "provider unavailable (" + e.status() + "): " + tail(e.getMessage()));
                else outbox.failTerminal(cmd.getId(), "provider refused (" + e.status() + "): " + tail(e.getMessage()));
            } catch (EnrolDriftException e) {
                outbox.failTerminal(cmd.getId(), e.getMessage());
            } catch (RuntimeException e) {
                outbox.fail(cmd.getId(), e.getMessage());
            }
        }
    }

    private void dispatch(AuthOutbox cmd, AuthProvider provider, List<LinkDelivery> deliveries) {
        Map<String, Object> p = cmd.getPayload() == null ? Map.of() : cmd.getPayload();
        switch (cmd.getCommand()) {
            case "ENROL" -> enrol(cmd, provider, p, deliveries);
            case "RESET_LINK" -> resetLink(cmd, provider, p, deliveries);
            case "DISABLE" -> provider.disable(str(p, "subject"), str(p, "reason"));
            case "ENABLE" -> provider.enable(str(p, "subject"));
            case "REVOKE_SESSIONS" -> provider.revokeSessions(str(p, "subject"));
            case "UPDATE_LOGIN" -> provider.updateLoginName(str(p, "subject"), str(p, "login_name"), str(p, "phone"));
            case "SET_MFA" -> provider.setMfaProfile(str(p, "subject"),
                    AuthPluginContracts.MfaProfile.valueOf(str(p, "mfa_profile")), Boolean.TRUE.equals(p.get("org_requires_mfa")));
            case "UNLINK" -> provider.unlink(str(p, "subject"));
            default -> outbox.failTerminal(cmd.getId(), "unknown outbox command " + cmd.getCommand());
        }
    }

    private void enrol(AuthOutbox cmd, AuthProvider provider, Map<String, Object> p, List<LinkDelivery> deliveries) {
        String uid = str(p, "user_id");
        Enrolment enrolment = provider.enrol(new EnrolRequest(uid, str(p, "login_name"), str(p, "phone"), str(p, "display_name")));
        attachSubject(cmd, uid, provider, enrolment.subject());
        deliver(cmd, uid, enrolment.subject(), "ENROL", enrolment.oneTimeLink(), enrolment.expiresAt(), deliveries);
    }

    private void resetLink(AuthOutbox cmd, AuthProvider provider, Map<String, Object> p, List<LinkDelivery> deliveries) {
        String uid = str(p, "user_id");
        ResetScope scope = p.containsKey("scope") ? ResetScope.valueOf(str(p, "scope")) : ResetScope.LINK;
        Enrolment enrolment = provider.resetCredential(str(p, "subject"), scope);
        deliver(cmd, uid, enrolment.subject(), "RESET_LINK", enrolment.oneTimeLink(), enrolment.expiresAt(), deliveries);
    }

    /** Persists the provider subject: first enrol creates the link row (subject is NOT NULL by design —
     *  it only exists once the IdP has it); later replays must match. Any drift is terminal because
     *  replaying a differing subject would silently invalidate a delivered link. */
    private void attachSubject(AuthOutbox cmd, String uid, AuthProvider provider, String subject) {
        if (subject == null || subject.isBlank()) throw new IllegalStateException("enrol returned no subject");
        IdentityLink link = links.findByUserId(uid).stream()
                .filter(row -> provider.key().equals(row.getProvider())).findFirst().orElse(null);
        if (link == null) {
            link = new IdentityLink();
            link.setUserId(uid);
            link.setProvider(provider.key());
            link.setIssuer(provider.issuer());
            link.setStatus(IdentityLink.Status.PENDING);
            link.setSubject(subject);
            links.save(link);
            return;
        }
        String existing = link.getSubject();
        if (existing != null && !existing.isBlank() && !existing.equals(subject)) {
            throw new EnrolDriftException("enrol subject drift for user " + uid + ": " + existing + " vs " + subject);
        }
        if (existing == null || existing.isBlank()) {
            link.setSubject(subject);
            links.save(link);
        }
    }

    /** Every configured sink must accept the link; one failure retries the whole command (enrol replays the same link). */
    private void deliver(AuthOutbox cmd, String uid, String subject, String kind, String link, Instant expiresAt,
                         List<LinkDelivery> deliveries) {
        if (link == null || link.isBlank()) throw new IllegalStateException("provider returned no set-up link");
        if (deliveries.isEmpty()) throw new IllegalStateException("no link delivery sink configured");
        LinkDelivery.Request request = new LinkDelivery.Request(uid, subject, kind, link, expiresAt);
        RuntimeException first = null;
        for (LinkDelivery sink : deliveries) {
            try {
                sink.deliver(request);
            } catch (RuntimeException e) {
                if (first == null) first = e;
            }
        }
        if (first != null) throw first;
    }

    private static String str(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) return null;
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static String tail(String message) {
        if (message == null) return "";
        return message.length() <= 200 ? message : message.substring(message.length() - 200);
    }

    static final class EnrolDriftException extends IllegalStateException {
        EnrolDriftException(String message) { super(message); }
    }
}
