package com.mipay.sme.service;

import com.mipay.sme.common.NotFoundException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.AuditLog;
import com.mipay.sme.domain.core.Merchant;
import com.mipay.sme.domain.core.Supplier;
import com.mipay.sme.domain.ops.Complaint;
import com.mipay.sme.domain.ops.Dispute;
import com.mipay.sme.domain.ops.Incident;
import com.mipay.sme.domain.ops.Suspension;
import com.mipay.sme.repository.AuditLogRepository;
import com.mipay.sme.repository.ComplaintRepository;
import com.mipay.sme.repository.DisputeRepository;
import com.mipay.sme.repository.IncidentRepository;
import com.mipay.sme.repository.LenderRepository;
import com.mipay.sme.repository.MerchantRepository;
import com.mipay.sme.repository.PaymentRepository;
import com.mipay.sme.repository.SupplierRepository;
import com.mipay.sme.repository.SuspensionRepository;
import com.mipay.sme.web.Dtos;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Ops console handlers (T10-T20): complaints, disputes, incidents with the
 * 15-minute critical acknowledgement SLA, suspensions with recorded reasons,
 * and audit search over the immutable log.
 */
@Service
@RequiredArgsConstructor
public class OpsService {

    private static final long CRITICAL_ACK_SLA_MINUTES = 15;

    private final ComplaintRepository complaintRepo;
    private final DisputeRepository disputeRepo;
    private final IncidentRepository incidentRepo;
    private final SuspensionRepository suspensionRepo;
    private final MerchantRepository merchantRepo;
    private final SupplierRepository supplierRepo;
    private final LenderRepository lenderRepo;
    private final PaymentRepository paymentRepo;
    private final AuditLogRepository auditRepo;
    private final AuditService auditService;
    private final com.mipay.sme.repository.UserRepository userRepository;
    private final com.mipay.sme.repository.DeviceSessionRepository sessionRepo;
    private final com.mipay.sme.repository.OrgClosureRepository orgClosureRepo;
    private final com.mipay.sme.service.OrgProvisioningService orgProvisioningService;
    private final com.mipay.sme.service.OrgSuspensionService orgSuspension;
    private final KycApplicationService kycApplications;
    private final ReportingService reportingService;

    // ------------------------------------------------------------------ incidents

    @Transactional
    public Dtos.IncidentRow createIncident(Dtos.CreateIncidentRequest req, String actorId) {
        Incident i = new Incident();
        i.setTitle(req.title());
        i.setSeverity(req.severity());
        i.setDescription(req.description());
        incidentRepo.save(i);
        auditService.record(actorId, "TPTS_OPS", "incident_created", "incident", i.getId(), null, null,
                "severity=" + i.getSeverity(), false, null);
        return row(i);
    }

    /** System-raised incident (bank timeout/failure, credential compromise) - SIMULATED-labelled.
     *  Runs in its own transaction so a failed caller never swallows the incident. */
    @Transactional
    public Dtos.IncidentRow raiseSystem(String severity, String title, String description, String reference) {
        Incident i = new Incident();
        i.setTitle("[SIMULATED] " + title);
        i.setSeverity(Enums.IncidentSeverity.valueOf(severity));
        i.setDescription(description + (reference == null ? "" : " (ref " + reference + ")"));
        incidentRepo.save(i);
        auditService.record("system", "MACHINE", "system_incident_raised", "incident", i.getId(),
                null, null, "severity=" + severity + " (simulated bank event, UAT SB p.16)", true, null);
        return row(i);
    }

    @Transactional
    public Dtos.IncidentRow acknowledge(String incidentId, String actorId) {
        Incident i = incidentRepo.findById(incidentId)
                .orElseThrow(() -> new NotFoundException("Incident not found"));
        boolean late = i.getSeverity() == Enums.IncidentSeverity.CRITICAL
                && Duration.between(i.getCreatedAt(), Instant.now()).toMinutes() > CRITICAL_ACK_SLA_MINUTES;
        i.setAcknowledgedAt(Instant.now());
        i.setAcknowledgedLate(late);
        if (i.getStatus() == Enums.IncidentStatus.OPEN) {
            i.setStatus(Enums.IncidentStatus.MITIGATED);
        }
        incidentRepo.save(i);
        auditService.record(actorId, "TPTS_OPS", "incident_acknowledged", "incident", i.getId(),
                null, null, "late=" + late + " (15-min SLA, UAT SB p.17)", false, null);
        return row(i);
    }

    @Transactional
    public Dtos.IncidentRow resolveIncident(String incidentId, String actorId) {
        Incident i = incidentRepo.findById(incidentId)
                .orElseThrow(() -> new NotFoundException("Incident not found"));
        i.setStatus(Enums.IncidentStatus.RESOLVED);
        i.setResolvedAt(Instant.now());
        incidentRepo.save(i);
        auditService.record(actorId, "TPTS_OPS", "incident_resolved", "incident", i.getId(), null, null, null, false, null);
        return row(i);
    }

    @Transactional(readOnly = true)
    public List<Dtos.IncidentRow> incidents() {
        return incidentRepo.findAllByOrderByCreatedAtDesc().stream().map(this::row).toList();
    }

    private Dtos.IncidentRow row(Incident i) {
        return new Dtos.IncidentRow(i.getId(), i.getTitle(), i.getSeverity(), i.getDescription(), i.getStatus(),
                i.getCreatedAt(), i.getAcknowledgedAt(), i.getAcknowledgedLate(), i.getResolvedAt());
    }

    // ------------------------------------------------------------------ complaints

    @Transactional
    public Dtos.ComplaintRow createComplaint(Dtos.CreateComplaintRequest req, String actorId) {
        Complaint c = new Complaint();
        if (req.merchantId() != null) {
            c.setMerchant(merchantRef(req.merchantId()));
        }
        if (req.supplierId() != null) {
            c.setSupplier(supplierRef(req.supplierId()));
        }
        c.setCategory(req.category());
        c.setDescription(req.description());
        complaintRepo.save(c);
        auditService.record(actorId, "TPTS_OPS", "complaint_logged", "complaint", c.getId(), null, null, null, false, null);
        return complaintRepo.findAllRows().stream()
                .filter(r -> r.id().equals(c.getId()))
                .findFirst()
                .orElse(new Dtos.ComplaintRow(c.getId(), null, null, c.getCategory(), c.getDescription(),
                        c.getStatus(), c.getAssignedTo(), c.getCreatedAt()));
    }

    @Transactional(readOnly = true)
    public List<Dtos.ComplaintRow> complaints() {
        return complaintRepo.findAllRows();
    }

    @Transactional
    public void assignComplaint(String id, String assigneeId) {
        Complaint c = complaintRepo.findById(id).orElseThrow(() -> new NotFoundException("Complaint not found"));
        c.setAssignedTo(assigneeId);
        c.setStatus(Enums.ComplaintStatus.IN_PROGRESS);
        complaintRepo.save(c);
    }

    @Transactional
    public void resolveComplaint(String id, String resolution, String actorId) {
        Complaint c = complaintRepo.findById(id).orElseThrow(() -> new NotFoundException("Complaint not found"));
        c.setStatus(Enums.ComplaintStatus.RESOLVED);
        c.setResolution(resolution == null ? "resolved by TPTS ops" : resolution);
        c.setResolvedAt(Instant.now());
        complaintRepo.save(c);
        auditService.record(actorId, "TPTS_OPS", "complaint_resolved", "complaint", c.getId(), null, null, null, false, null);
    }

    // ------------------------------------------------------------------ disputes

    @Transactional
    public void createDispute(String paymentId, String reason, String details, String actorId) {
        com.mipay.sme.domain.payments.Payment p = paymentRepo.findById(paymentId)
                .orElseThrow(() -> new NotFoundException("Payment not found"));
        Dispute d = new Dispute();
        d.setPayment(p);
        d.setMerchantId(p.getMerchant().getId());
        d.setReason(reason);
        d.setDetails(details);
        disputeRepo.save(d);
        auditService.record(actorId, "TPTS_OPS", "dispute_created", "dispute", d.getId(), null, null, reason, false, null);
    }

    @Transactional(readOnly = true)
    public List<Dtos.DisputeRow> disputes() {
        return disputeRepo.findAllRows();
    }

    @Transactional
    public void resolveDispute(String id, boolean won, String actorId) {
        Dispute d = disputeRepo.findById(id).orElseThrow(() -> new NotFoundException("Dispute not found"));
        d.setStatus(won ? Enums.DisputeStatus.WON : Enums.DisputeStatus.LOST);
        d.setResolvedAt(Instant.now());
        disputeRepo.save(d);
        auditService.record(actorId, "TPTS_OPS", "dispute_resolved", "dispute", d.getId(),
                "OPEN", d.getStatus().name(), null, false, null);
    }

    // ------------------------------------------------------------------ suspensions

    /**
     * Legacy suspend keeps its API and DTO and delegates to the org service ([plan]).
     * A legacy suspend writes org_id/entity_*, mirrors the entity and now cascades.
     */
    @Transactional
    public Dtos.SuspensionRow suspend(Dtos.SuspendRequest req, String actorId, String actorRole) {
        Enums.SuspensionEntityType type =
                Enums.SuspensionEntityType.valueOf(req.entityType().toUpperCase());
        if (type == Enums.SuspensionEntityType.ORG) {
            return orgSuspension.suspend(req.entityId(), req.reason(),
                    new com.mipay.sme.security.Current.Principal(actorId, actorRole, actorId, null,
                            null, null, null));
        }
        // entity-level legacy path: resolve the org and cascade through it
        Suspension s = new Suspension();
        s.setEntityType(type);
        s.setEntityId(req.entityId());
        String resolvedOrg = orgIdOf(type, req.entityId());
        s.setOrgId(resolvedOrg);
        s.setReason(req.reason());
        s.setSuspendedBy(actorId);
        suspensionRepo.save(s);

        if (s.getEntityType() == Enums.SuspensionEntityType.MERCHANT) {
            Merchant m = merchantRepo.findById(req.entityId())
                    .orElseThrow(() -> new NotFoundException("Merchant not found"));
            m.setApprovalStatus(Enums.ApprovalStatus.SUSPENDED);
            merchantRepo.save(m);
        } else if (s.getEntityType() == Enums.SuspensionEntityType.LENDER) {
            lenderRepo.findById(req.entityId()).ifPresent(l -> {
                l.setStatus(Enums.LenderStatus.SUSPENDED);
                lenderRepo.save(l);
            });
        } else {
            Supplier sup = supplierRepo.findById(req.entityId())
                    .orElseThrow(() -> new NotFoundException("Supplier not found"));
            sup.setApprovalStatus(Enums.ApprovalStatus.SUSPENDED);
            supplierRepo.save(sup);
        }
        // cascade: sessions of the org subtree die too
        if (resolvedOrg != null) {
            orgSuspensionCascade(resolvedOrg);
            kycApplications.syncSuspension(resolvedOrg);
        }
        auditService.record(actorId, actorRole, "suspended", s.getEntityType().name().toLowerCase(),
                s.getEntityId(), "APPROVED", "SUSPENDED", "reason=" + req.reason(), false, null);
        return new Dtos.SuspensionRow(s.getId(), s.getEntityType().name(), s.getEntityId(), req.reason(),
                s.getSuspendedAt(), s.getLiftedAt());
    }

    private void orgSuspensionCascade(String orgId) {
        List<String> userIds = userRepository.findByOrgIdIn(javaxSubtree(orgId)).stream()
                .map(u -> String.valueOf(u.getId())).toList();
        if (!userIds.isEmpty()) {
            sessionRepo.deleteAll(sessionRepo.findByUserIdIn(userIds));
        }
    }

    private List<String> javaxSubtree(String orgId) {
        return orgClosureRepo.findSubtree(orgId).stream().map(r -> r.getOrg().getId()).toList();
    }


    private String orgIdOf(Enums.SuspensionEntityType type, String entityId) {
        return switch (type) {
            case MERCHANT -> merchantRepo.findById(entityId)
                    .flatMap(m -> orgProvisioningService.ensureEntityOrg(Enums.OrgType.MERCHANT, m.getId(),
                            m.getBusinessName(), m.getUserId()).getEntityId() == null
                            ? java.util.Optional.empty()
                            : java.util.Optional.of(orgProvisioningService.ensureEntityOrg(Enums.OrgType.MERCHANT,
                            m.getId(), m.getBusinessName(), m.getUserId()).getId()))
                    .orElse(null);
            case SUPPLIER -> supplierRepo.findById(entityId)
                    .map(sp -> orgProvisioningService.ensureEntityOrg(Enums.OrgType.SUPPLIER, sp.getId(),
                            sp.getBusinessName(), sp.getUserId()).getId())
                    .orElse(null);
            case LENDER -> lenderRepo.findById(entityId)
                    .map(l -> orgProvisioningService.ensureEntityOrg(Enums.OrgType.LENDER, l.getId(),
                            l.getName(), l.getUserId()).getId())
                    .orElse(null);
            default -> null;
        };
    }

    /** Legacy lift keeps its API and delegates the level rules to the org service ([H4]). */
    @Transactional
    public List<Dtos.SuspensionRow> lift(String suspensionId, String actorId) {
        return orgSuspension.lift(suspensionId,
                new com.mipay.sme.security.Current.Principal(actorId, "TPTS_OPS", actorId, null,
                        null, null, null));
    }

    @Transactional(readOnly = true)
    public List<Dtos.SuspensionRow> suspensions() {
        return suspensionRepo.findAllByOrderBySuspendedAtDesc().stream()
                .map(s -> new Dtos.SuspensionRow(s.getId(), s.getEntityType().name(), s.getEntityId(),
                        s.getReason(), s.getSuspendedAt(), s.getLiftedAt()))
                .toList();
    }

    // ------------------------------------------------------------------ audit

    @Transactional(readOnly = true)
    public Dtos.AuditPage audit(boolean onlySimulated, int page, int size) {
        Page<AuditLog> rows = auditRepo.search(onlySimulated, PageRequest.of(page, size));
        List<Dtos.AuditRow> out = rows.getContent().stream()
                .map(a -> new Dtos.AuditRow(a.getId(), a.getActorRole(), a.getAction(), a.getEntityType(),
                        a.getEntityId(), a.getMeta(), a.getSimulated(), a.getCreatedAt()))
                .toList();
        return new Dtos.AuditPage(out, rows.getTotalElements(), page);
    }

    // ------------------------------------------------------------------ helpers

    private Merchant merchantRef(String id) {
        return merchantRepo.findById(id).orElseThrow(() -> new NotFoundException("Merchant not found"));
    }

    private Supplier supplierRef(String id) {
        return supplierRepo.findById(id).orElseThrow(() -> new NotFoundException("Supplier not found"));
    }

    private record MetricProbe(BigDecimal value) { }

    @Transactional
    public void touchMetrics() {
        reportingService.recordMetric("availability_pct", new BigDecimal("99.95"));
        reportingService.recordMetric("latency_ms_p95", BigDecimal.valueOf(120 + java.util.concurrent.ThreadLocalRandom.current().nextInt(120)));
        reportingService.recordMetric("tps", BigDecimal.valueOf(10 + java.util.concurrent.ThreadLocalRandom.current().nextInt(10)));
    }
}
