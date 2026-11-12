package com.mipay.sme.service;

import com.mipay.sme.domain.core.AuditLog;
import com.mipay.sme.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Immutable audit trail writer (BC p.18). Every privileged action and every
 * SIMULATED bank/lender event is recorded here (E34, TC-SET-28).
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    public static final String LABEL_SIMULATED = "SIMULATED";

    private final AuditLogRepository auditRepo;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Transactional
    public AuditLog record(String actorId, String actorRole, String action, String entityType, String entityId,
                           String beforeHash, String afterHash, String meta, boolean simulated, String ip) {
        AuditLog log = new AuditLog();
        log.setActorId(actorId);
        log.setActorRole(actorRole);
        // cutover plan §3.12: the audit identity is what the actor IS (org/platform kind) plus
        // the acting session's assurance, not just the role string. The context comes from the
        // resolved gateway identity when the actor acted through the IdP.
        log.setActorKind(actorKind(actorId, actorRole));
        com.mipay.sme.security.Current.Principal p = currentPrincipal();
        if (p != null) {
            log.setLinkId(p.linkId());
            log.setAcr(p.acr());
        }
        log.setAction(action);
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        log.setBeforeHash(beforeHash);
        log.setAfterHash(afterHash);
        log.setMeta(meta);
        log.setSimulated(simulated);
        log.setIpAddress(ip);
        return auditRepo.save(log);
    }

    /** The actor's kind for the audit: their home org's type, PLATFORM for platform staff. */
    private String actorKind(String actorId, String actorRole) {
        try {
            if (actorId == null || actorId.isBlank()) return "SYSTEM";
            var row = jdbc.queryForList(
                    "SELECT o.org_type FROM users u JOIN organizations o ON o.id = u.org_id WHERE u.id = ? LIMIT 1",
                    actorId);
            return row.isEmpty() ? "SYSTEM" : String.valueOf(row.get(0).get("org_type")).replace("MERCHANT", "ORG")
                    .replace("SUPPLIER", "ORG").replace("LENDER", "ORG");
        } catch (Exception e) {
            return "SYSTEM";   // audit must never fail the action it is recording
        }
    }

    private com.mipay.sme.security.Current.Principal currentPrincipal() {
        try {
            return com.mipay.sme.security.Current.get();
        } catch (Exception e) {
            return null;   // system jobs and seeds act without a resolved session
        }
    }

    @Transactional
    public AuditLog sim(String actorId, String actorRole, String action, String entityType, String entityId, String meta, String ip) {
        return record(actorId, actorRole, action, entityType, entityId, null, null,
                meta == null ? LABEL_SIMULATED : LABEL_SIMULATED + " | " + meta, true, ip);
    }
}
