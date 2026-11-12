package com.mipay.sme.security.auth;

import com.mipay.sme.domain.core.AuditLog;
import com.mipay.sme.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Applies verified IdP auth events, exactly once: the transactional event-id dedup
 * (auth_events_seen) and the state change commit together, so a redelivery can never
 * double-apply. Unrecognized types are accepted (200) and logged — the IdP's delivery
 * loop otherwise retries forever.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthEventIngestService {
    private final JdbcTemplate jdbc;
    private final UserLifecycleService lifecycle;
    private final AuditLogRepository audits;

    @Transactional
    public void apply(IdpEventVerifier.AuthEvent event) {
        int claimed = jdbc.update(
                "INSERT INTO auth_events_seen (event_id, provider, subject, event_type, occurred_at) "
                        + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (event_id) DO NOTHING",
                event.id(), event.provider(), event.subject(), event.type(), java.sql.Timestamp.from(event.at()));
        if (claimed == 0) {
            log.debug("auth event {} redelivered; already applied", event.id());
            return;
        }
        switch (event.type()) {
            case "CREDENTIAL_SET" -> lifecycle.activateFromCredentialSet(event.subject(), event.uid(), event.at());
            case "LOCKED", "UNLOCKED" -> audit(event, event.type());
            default -> log.warn("auth event {} has unknown type {}; accepted, no-op", event.id(), event.type());
        }
    }

    private void audit(IdpEventVerifier.AuthEvent event, String lockAction) {
        AuditLog auditLog = new AuditLog();
        auditLog.setId(java.util.UUID.randomUUID().toString());
        auditLog.setActorId(event.uid());
        auditLog.setActorRole("system:idp-event");
        auditLog.setAction("CREDENTIAL_" + lockAction);
        auditLog.setEntityType("auth_account");
        auditLog.setEntityId(event.subject());
        auditLog.setMeta("{\"event_id\":\"" + event.id() + "\"}");
        auditLog.setCreatedAt(Instant.now());
        audits.save(auditLog);
    }
}
