package com.mipay.sme.service.migration;

import com.mipay.sme.domain.core.IdentityLink;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.repository.IdentityLinkRepository;
import com.mipay.sme.repository.UserRepository;
import com.mipay.sme.security.auth.AuthPluginContracts.EnrolRequest;
import com.mipay.sme.security.auth.AuthPluginContracts.Enrolment;
import com.mipay.sme.security.auth.AuthPluginContracts.ResetScope;
import com.mipay.sme.security.auth.AuthPluginContracts.AuthProvider;
import com.mipay.sme.security.auth.AuthProviderRegistry;
import com.mipay.sme.security.auth.LinkDelivery;
import com.mipay.sme.security.auth.TrustplaneAuthProvider.AuthProviderException;
import com.mipay.sme.security.auth.UserLifecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Identity backfill (cut-over P3.10): enrols every existing core user at the credential
 * provider and writes the {@code identity_links} row, so the gateway identity mode has a
 * link to resolve. Admin-only and safe to re-run — users with an ACTIVE link are skipped,
 * the deterministic rules produce the same result every time.
 *
 * <p>Per user:</p>
 * <ul>
 *   <li>a user with a real bcrypt hash ({@code {bcrypt}$2a/$2b…}) is enrolled with that hash
 *       copied to the provider — the IdP account is ACTIVE straight away, the user and its
 *       link are activated through the normal CREDENTIAL_SET event (nothing special-cased);</li>
 *   <li>users with a {@code {noop}} delegated hash (seeded demo users) get a set-up link
 *       instead — the tool issues it through the provider and pushes it through the comms
 *       sinks when they are configured; undelivered links are reported, never emailed here;</li>
 *   <li>a PENDING link from an earlier partial run is re-issued (the IdP link expires);</li>
 *   <li>an ACTIVE link short-circuits to SKIPPED; DISABLED/REVOKED links are left to the admin.</li>
 * </ul>
 *
 * <p>Raw links are never written to durable core records: the report carries outcomes only.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdentityBackfill {

    private final UserRepository users;
    private final IdentityLinkRepository links;
    private final AuthProviderRegistry registry;
    private final UserLifecycleService lifecycle;
    private final List<LinkDelivery> sinks;

    public record Row(String userId, String phone, String status, String resolution, String linkStatus, String note) { }

    public record Report(int scanned, List<Row> rows, boolean errors) { }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Report preview() {
        requireProvider();
        return scan(true);
    }

    /** Runs the backfill; per-user failures are isolated and reported, the run continues. */
    public Report run() {
        requireProvider();
        return scan(false);
    }

    private void requireProvider() {
        if (!registry.enabled()) {
            throw new IllegalStateException("credential provider is disabled; enable the trustplane provider first");
        }
    }

    private Report scan(boolean dryRun) {
        List<Row> rows = new ArrayList<>();
        boolean errors = false;
        for (User u : users.findAll().stream().sorted(Comparator.comparing(User::getPhone)).toList()) {
            Row r;
            try {
                r = dryRun ? classify(u) : backfill(u);
            } catch (AuthProviderException e) {
                errors = true;
                r = new Row(u.getId(), u.getPhone(), status(u), "FAILED", null,
                        "provider refused (" + e.status() + ")");
                log.warn("[identity-backfill] user {} failed: {}", u.getId(), tail(e.getMessage()));
            } catch (RuntimeException e) {
                errors = true;
                r = new Row(u.getId(), u.getPhone(), status(u), "FAILED", null, tail(e.getMessage()));
                log.warn("[identity-backfill] user {} failed: {}", u.getId(), e.getMessage());
            }
            rows.add(r);
        }
        return new Report(rows.size(), rows, errors);
    }

    private Row backfill(User u) {
        var provider = registry.selected();
        String loginName = u.getUsername() == null || u.getUsername().isBlank() ? u.getPhone() : u.getUsername();
        String imported = importableHash(userPassword(u));

        IdentityLink existing = links.findByUserId(u.getId()).stream()
                .filter(l -> provider.key().equals(l.getProvider()))
                .findFirst().orElse(null);
        if (existing != null) {
            return switch (existing.getStatus()) {
                case ACTIVE -> new Row(u.getId(), u.getPhone(), status(u), "SKIPPED", "ACTIVE",
                        "link " + existing.getSubject() + " already active");
                case PENDING -> reissue(u, provider, existing, loginName);
                default -> new Row(u.getId(), u.getPhone(), status(u), "SKIPPED", existing.getStatus().name(),
                        existing.getStatus() + " link is an admin decision, not backfilled");
            };
        }
        Enrolment enrolment = provider.enrol(new EnrolRequest(u.getId(), loginName, u.getPhone(),
                u.getName(), imported));
        IdentityLink link = new IdentityLink();
        link.setUserId(u.getId());
        link.setProvider(provider.key());
        link.setIssuer(provider.issuer());
        link.setSubject(enrolment.subject());
        link.setStatus(IdentityLink.Status.PENDING);
        link.setCreatedBy("backfill:identity");
        boolean delivered = deliver(u.getId(), enrolment);
        links.save(link);
        if (imported != null) {
            // the imported case flips to ACTIVE through the provider's CREDENTIAL_SET event;
            // replaying activateFromCredentialSet here makes the row immediately consistent
            // even if the event is still in flight (idempotent: it only acts on PENDING links)
            lifecycle.activateFromCredentialSet(enrolment.subject(), u.getId(), Instant.now());
        }
        IdentityLink stored = links.findById(link.getId()).orElse(link);
        String resolution = imported != null ? "IMPORTED_ACTIVE" : delivered ? "SETUP_LINK_DELIVERED" : "SETUP_LINK_NOT_DELIVERED";
        log.info("[identity-backfill] user {} -> {} as {}", u.getId(), enrolment.subject(), resolution);
        return new Row(u.getId(), u.getPhone(), status(u), resolution, stored.getStatus().name(), null);
    }

    private Row reissue(User u, AuthProvider provider,
                        IdentityLink existing, String loginName) {
        Enrolment reset = provider.resetCredential(existing.getSubject(), ResetScope.LINK);
        boolean delivered = deliver(u.getId(), reset);
        log.info("[identity-backfill] user {} link re-issued (delivered={})", u.getId(), delivered);
        return new Row(u.getId(), u.getPhone(), status(u),
                delivered ? "SETUP_LINK_DELIVERED" : "SETUP_LINK_NOT_DELIVERED", "PENDING", "re-issued");
    }

    private boolean deliver(String uid, Enrolment enrolment) {
        if (enrolment.oneTimeLink() == null || enrolment.oneTimeLink().isBlank()) return true; // imported, no link
        if (sinks == null || sinks.isEmpty()) return false;
        LinkDelivery.Request request = new LinkDelivery.Request(uid, enrolment.subject(), "ENROL",
                enrolment.oneTimeLink(), enrolment.expiresAt());
        try {
            for (LinkDelivery sink : sinks) sink.deliver(request);
            return true;
        } catch (RuntimeException e) {
            log.warn("[identity-backfill] link delivery for user {} failed: {}", uid, tail(e.getMessage()));
            return false;
        }
    }

    private Row classify(User u) {
        IdentityLink existing = links.findByUserId(u.getId()).stream()
                .filter(l -> registry.selected().key().equals(l.getProvider()))
                .findFirst().orElse(null);
        String resolution = existing == null
                ? (importableHash(userPassword(u)) != null ? "IMPORTED_ACTIVE" : "SETUP_LINK")
                : existing.getStatus().name();
        return new Row(u.getId(), u.getPhone(), status(u), resolution, null, null);
    }

    /**
     * The core stores Spring's delegating format: {@code {bcrypt}$2a$…} is a real hash the
     * provider can copy ({@code {bcrypt}} stripped, P3.10); {@code {noop}} delegates mean no
     * usable credential (the user gets a set-up link); anything else is never guessed.
     */
    static String importableHash(String stored) {
        if (stored == null || stored.isBlank()) return null;
        if (stored.startsWith("{noop}")) return null;
        if (stored.startsWith("{bcrypt}")) stored = stored.substring("{bcrypt}".length());
        if (stored.startsWith("$2a$") || stored.startsWith("$2b$") || stored.startsWith("$2y$")) {
            return stored.length() == 60 ? stored : null;
        }
        return null;
    }

    private static String userPassword(User u) {
        return u.getPasswordHash();
    }

    private static String status(User u) {
        return u.getStatus() == null ? "" : u.getStatus().name();
    }

    private static String tail(String message) {
        if (message == null) return "";
        return message.length() <= 160 ? message : message.substring(message.length() - 160);
    }
}
