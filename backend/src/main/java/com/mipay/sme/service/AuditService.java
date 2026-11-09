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

    @Transactional
    public AuditLog record(String actorId, String actorRole, String action, String entityType, String entityId,
                           String beforeHash, String afterHash, String meta, boolean simulated, String ip) {
        AuditLog log = new AuditLog();
        log.setActorId(actorId);
        log.setActorRole(actorRole);
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

    @Transactional
    public AuditLog sim(String actorId, String actorRole, String action, String entityType, String entityId, String meta, String ip) {
        return record(actorId, actorRole, action, entityType, entityId, null, null,
                meta == null ? LABEL_SIMULATED : LABEL_SIMULATED + " | " + meta, true, ip);
    }
}
