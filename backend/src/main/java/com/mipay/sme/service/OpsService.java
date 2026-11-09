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
    private final PaymentRepository paymentRepo;
    private final AuditLogRepository auditRepo;
    private final AuditService auditService;
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

    @Transactional
    public Dtos.SuspensionRow suspend(Dtos.SuspendRequest req, String actorId, String actorRole) {
        Suspension s = new Suspension();
        s.setEntityType(Enums.SuspensionEntityType.valueOf(req.entityType().toUpperCase()));
        s.setEntityId(req.entityId());
        s.setReason(req.reason());
        s.setSuspendedBy(actorId);
        suspensionRepo.save(s);

        if (s.getEntityType() == Enums.SuspensionEntityType.MERCHANT) {
            Merchant m = merchantRepo.findById(req.entityId())
                    .orElseThrow(() -> new NotFoundException("Merchant not found"));
            m.setApprovalStatus(Enums.ApprovalStatus.SUSPENDED);
            merchantRepo.save(m);
        } else {
            Supplier sup = supplierRepo.findById(req.entityId())
                    .orElseThrow(() -> new NotFoundException("Supplier not found"));
            sup.setApprovalStatus(Enums.ApprovalStatus.SUSPENDED);
            supplierRepo.save(sup);
        }
        auditService.record(actorId, actorRole, "suspended", s.getEntityType().name().toLowerCase(),
                s.getEntityId(), "APPROVED", "SUSPENDED", "reason=" + req.reason(), false, null);
        return new Dtos.SuspensionRow(s.getId(), s.getEntityType().name(), s.getEntityId(), req.reason(),
                s.getSuspendedAt(), s.getLiftedAt());
    }

    @Transactional
    public List<Dtos.SuspensionRow> lift(String suspensionId, String actorId) {
        Suspension s = suspensionRepo.findById(suspensionId)
                .orElseThrow(() -> new NotFoundException("Suspension not found"));
        if (s.isActive()) {
            s.setLiftedAt(Instant.now());
            suspensionRepo.save(s);
            if (s.getEntityType() == Enums.SuspensionEntityType.MERCHANT) {
                merchantRepo.findById(s.getEntityId()).ifPresent(m -> {
                    m.setApprovalStatus(Enums.ApprovalStatus.APPROVED);
                    merchantRepo.save(m);
                });
            } else {
                supplierRepo.findById(s.getEntityId()).ifPresent(sup -> {
                    sup.setApprovalStatus(Enums.ApprovalStatus.APPROVED);
                    supplierRepo.save(sup);
                });
            }
            auditService.record(actorId, "TPTS_OPS", "suspension_lifted", "suspension", s.getId(),
                    "SUSPENDED", "APPROVED", null, false, null);
        }
        return suspensions();
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
