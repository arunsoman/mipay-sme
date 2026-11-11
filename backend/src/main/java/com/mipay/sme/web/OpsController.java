package com.mipay.sme.web;

import com.mipay.sme.config.AppProperties;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.security.Current;
import com.mipay.sme.service.OnboardingService;
import com.mipay.sme.service.OpsService;
import com.mipay.sme.service.ReconciliationService;
import com.mipay.sme.service.ReportingService;
import com.mipay.sme.service.StatementService;
import com.mipay.sme.web.Dtos;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** TPTS operations console API (T01-T20). */
@RestController
@RequestMapping("/api/ops")
@RequiredArgsConstructor
public class OpsController {

    private final OnboardingService onboardingService;
    private final com.mipay.sme.service.OrgOnboardingService orgOnboardingService;
    private final com.mipay.sme.repository.OnboardingRequestRepository onboardingRequestRepo;
    private final com.mipay.sme.repository.OrganizationRepository orgRepo;
    private final OpsService opsService;
    private final ReconciliationService reconService;
    private final ReportingService reportingService;
    private final StatementService statementService;
    private final com.mipay.sme.service.ComplianceService complianceService;
    private final AppProperties props;

    private String actor() {
        return Current.get().userId();
    }

    // ------------------------------------------------------------------ approvals (T02-T04)

    /** Recorded KYC checks (IDENTITY/SANCTIONS) for a merchant or supplier - ops review panel. */
    @GetMapping("/kyc/{entityType}/{entityId}")
    public List<Dtos.KycCheckRow> kycChecks(@PathVariable String entityType, @PathVariable String entityId) {
        // KYC is recorded for any onboarded entity (merchant/supplier via legacy approval,
        // distributor/manufacturer/lender via org onboarding). All are readable here.
        if (!Set.of("merchant", "supplier", "distributor", "manufacturer", "lender").contains(entityType)) {
            throw new com.mipay.sme.common.NotFoundException("Unknown KYC entity type " + entityType);
        }
        return complianceService.kycChecks(entityType, entityId);
    }

    /** Record a KYC check on an entity (manual ops review; IDENTITY/SANCTIONS/ADVERSE_MEDIA). */
    @PostMapping("/kyc/{entityType}/{entityId}")
    public Dtos.KycCheckRow recordKycCheck(@PathVariable String entityType,
                                           @PathVariable String entityId,
                                           @Valid @RequestBody Dtos.KycCheckRequest req) {
        if (!Set.of("merchant", "supplier", "distributor", "manufacturer", "lender").contains(entityType)) {
            throw new com.mipay.sme.common.NotFoundException("Unknown KYC entity type " + entityType);
        }
        Enums.KycCheckType type = Enums.KycCheckType.valueOf(req.checkType().toUpperCase());
        Enums.KycResult result = Enums.KycResult.valueOf(req.result().toUpperCase());
        com.mipay.sme.domain.compliance.KycCheck check = complianceService.recordCheck(
                entityType, entityId, type, result, req.details());
        return new Dtos.KycCheckRow(check.getId(), check.getEntityType(), check.getEntityId(),
                check.getCheckType().name(), check.getResult().name(), check.getDetails(),
                check.getCheckedAt());
    }

    @GetMapping("/approvals/merchants")
    public List<Dtos.MerchantApprovalRow> merchantQueue() {
        // legacy queue leaves out orgs onboarded through the tree (parent != root) [plan C1]
        return merchantRepoRef.findQueue(Enums.ApprovalStatus.PENDING).stream()
                .filter(r -> isLegacyRow("MERCHANT", r.id()))
                .toList();
    }

    @GetMapping("/approvals/merchants/all")
    public List<Dtos.MerchantApprovalRow> merchantsAll() {
        return merchantRepoRef.findAllRows();
    }

    @PostMapping("/approvals/merchants/{id}")
    public Dtos.ApprovalResult approveMerchant(@PathVariable String id,
                                               @Valid @RequestBody Dtos.ApprovalRequest req) {
        return onboardingService.approveMerchant(id, req.approve(), req.sanctionsResult(),
                actor(), "TPTS_OPS");
    }

    @GetMapping("/approvals/suppliers")
    public List<Dtos.SupplierApprovalRow> supplierQueue() {
        return onboardingService.supplierQueue().stream()
                .filter(r -> isLegacyRow("SUPPLIER", r.id()))
                .toList();
    }

    @GetMapping("/approvals/suppliers/all")
    public List<Dtos.SupplierApprovalRow> suppliersAll() {
        return onboardingService.suppliersAllRows();
    }

    @PostMapping("/approvals/suppliers/{id}")
    public Dtos.ApprovalResult approveSupplier(@PathVariable String id,
                                               @Valid @RequestBody Dtos.ApprovalRequest req) {
        return onboardingService.approveSupplier(id, req.approve(), actor(), "TPTS_OPS");
    }

    @GetMapping("/approvals/bank-accounts")
    public List<Dtos.BankApprovalRow> bankQueue() {
        return onboardingService.bankQueue();
    }

    @PostMapping("/approvals/bank-accounts/{id}")
    public Dtos.ApprovalResult approveBankAccount(@PathVariable String id,
                                                  @Valid @RequestBody Dtos.BankApprovalRequest req) {
        return onboardingService.approveBankAccount(id, req.approve(), actor(), "TPTS_OPS");
    }

    // ------------------------------------------------------------------ org onboarding review [ops]

    /** Review queue for tree onboarding requests (TPTS_OPS). */
    @GetMapping("/onboarding-requests")
    public List<Dtos.OnboardingRequestRow> onboardingRequests(
            @RequestParam(defaultValue = "PENDING_REVIEW") String status) {
        Enums.OnboardingStatus st = Enums.OnboardingStatus.valueOf(status.toUpperCase());
        return onboardingRequestRepo.findByStatusOrderByCreatedAtDesc(st).stream()
                .map(r -> new Dtos.OnboardingRequestRow(r.getId(), r.getKind().name(), r.getStatus().name(),
                        r.getOrgId(), orgName(r.getOrgId()), orgType(r.getOrgId()), r.getParentOrgId(),
                        r.getRequestedBy(), r.getRequestedByRole(), r.getRequestedByOrgId(),
                        r.getReviewedBy(), r.getStatus().name(),
                        metaPart(r.getMeta(), "direct="), metaPart(r.getMeta(), "ceiling="),
                        r.getNote(), r.getMeta(), r.getCreatedAt(), r.getReviewedAt()))
                .toList();
    }

    @PostMapping("/onboarding-requests/{id}/decision")
    public Dtos.OrgDecisionResult decideOnboarding(@PathVariable String id,
                                                   @RequestBody Dtos.OrgDecideRequest req) {
        return orgOnboardingService.decide(id, Current.get(), Boolean.TRUE.equals(req.approve()),
                req.note(), req.sanctionsResult());
    }

    private String orgName(String orgId) {
        return orgRepo.findById(orgId).map(o -> o.getName()).orElse(null);
    }

    private String orgType(String orgId) {
        return orgRepo.findById(orgId).map(o -> o.getOrgType().name()).orElse(null);
    }

    private String metaPart(String meta, String key) {
        if (meta == null) return null;
        for (String part : meta.split(";")) {
            if (part.startsWith(key)) return part.substring(key.length());
        }
        return null;
    }

    /** True when the org is under the root or has no org binding yet. */
    private boolean isLegacyRow(String type, String entityId) {
        return orgRepo.findByOrgTypeAndEntityId(Enums.OrgType.valueOf(type), entityId)
                .map(o -> com.mipay.sme.service.OrgProvisioningService.ORG_ROOT.equals(o.getParentId()))
                .orElse(true);
    }

    // ------------------------------------------------------------------ reconciliation (T05)

    @GetMapping("/recon/runs")
    public List<Dtos.ReconRunRow> reconRuns() {
        return reconService.runs();
    }

    @PostMapping("/recon/run")
    public Dtos.RunResult runRecon() {
        return reconService.run();
    }

    @GetMapping("/recon/runs/{id}/breaks")
    public List<Dtos.BreakRow> breaks(@PathVariable String id) {
        return reconService.breaks(id);
    }

    @PostMapping("/recon/breaks/{id}/resolve")
    public Dtos.BreakRow resolveBreak(@PathVariable String id) {
        return reconService.resolveBreak(id, actor());
    }

    // ------------------------------------------------------------------ incidents (T16)

    @GetMapping("/incidents")
    public List<Dtos.IncidentRow> incidents() {
        return opsService.incidents();
    }

    @PostMapping("/incidents")
    public Dtos.IncidentRow createIncident(@Valid @RequestBody Dtos.CreateIncidentRequest req) {
        return opsService.createIncident(req, actor());
    }

    @PostMapping("/incidents/{id}/ack")
    public Dtos.IncidentRow ack(@PathVariable String id) {
        return opsService.acknowledge(id, actor());
    }

    @PostMapping("/incidents/{id}/resolve")
    public Dtos.IncidentRow resolveIncident(@PathVariable String id) {
        return opsService.resolveIncident(id, actor());
    }

    // ------------------------------------------------------------------ complaints / disputes (T07/T10/T11)

    @GetMapping("/complaints")
    public List<Dtos.ComplaintRow> complaints() {
        return opsService.complaints();
    }

    @PostMapping("/complaints")
    public Dtos.ComplaintRow createComplaint(@Valid @RequestBody Dtos.CreateComplaintRequest req) {
        return opsService.createComplaint(req, actor());
    }

    @PostMapping("/complaints/{id}/assign")
    public Dtos.Message assign(@PathVariable String id, @RequestParam(required = false) String assignee) {
        opsService.assignComplaint(id, assignee == null ? actor() : assignee);
        return new Dtos.Message("Complaint assigned", null);
    }

    @PostMapping("/complaints/{id}/resolve")
    public Dtos.Message resolveComplaint(@PathVariable String id,
                                         @RequestParam(required = false) String resolution) {
        opsService.resolveComplaint(id, resolution, actor());
        return new Dtos.Message("Complaint resolved", null);
    }

    @GetMapping("/disputes")
    public List<Dtos.DisputeRow> disputes() {
        return opsService.disputes();
    }

    @PostMapping("/disputes")
    public Dtos.Message createDispute(@Valid @RequestBody Dtos.CreateDisputeRequest req) {
        opsService.createDispute(req.paymentId(), req.reason(), req.details(), actor());
        return new Dtos.Message("Dispute created and tracked", null);
    }

    @PostMapping("/disputes/{id}/resolve")
    public Dtos.Message resolveDispute(@PathVariable String id, @RequestParam boolean won) {
        opsService.resolveDispute(id, won, actor());
        return new Dtos.Message("Dispute resolved as " + (won ? "WON" : "LOST"), null);
    }

    // ------------------------------------------------------------------ suspensions (T15)

    @PostMapping("/suspend")
    public Dtos.SuspensionRow suspend(@Valid @RequestBody Dtos.SuspendRequest req) {
        return opsService.suspend(req, actor(), "TPTS_OPS");
    }

    @GetMapping("/suspensions")
    public List<Dtos.SuspensionRow> suspensions() {
        return opsService.suspensions();
    }

    @PostMapping("/suspensions/{id}/lift")
    public List<Dtos.SuspensionRow> lift(@PathVariable String id) {
        return opsService.lift(id, actor());
    }

    // ------------------------------------------------------------------ audit + reports + statements

    @GetMapping("/audit")
    public Dtos.AuditPage audit(@RequestParam(defaultValue = "false") boolean simulated,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "25") int size) {
        return opsService.audit(simulated, page, size);
    }

    @GetMapping("/reports/settlement")
    public Dtos.SettlementRow settlement(@RequestParam(required = false) String date,
                                         @RequestParam(required = false) String cycle) {
        LocalDate d = date == null ? LocalDate.now() : LocalDate.parse(date);
        return reportingService.settlement(d, cycle);
    }

    @GetMapping("/reports/fees")
    public List<Dtos.FeeReportRow> feeReports(@RequestParam(required = false) String date) {
        LocalDate d = date == null ? LocalDate.now() : LocalDate.parse(date);
        return reportingService.feeReports(d);
    }

    @PostMapping("/statements/generate")
    public Dtos.StatementRow generateStatement(@Valid @RequestBody Dtos.GenerateStatementRequest req) {
        return statementService.generate(req.ownerType(), req.ownerId(), req.from(), req.to(), actor());
    }

    @PostMapping("/monitoring/touch")
    public Dtos.Message touch() {
        opsService.touchMetrics();
        return new Dtos.Message("Metrics recorded", props.isSimulatedEnvironment() ? "SIMULATED metrics" : null);
    }

    @GetMapping("/payments")
    public List<Dtos.PaymentOpsRow> payments() {
        return paymentRepoRef.latestForOps().stream()
                .limit(50)
                .map(p -> new Dtos.PaymentOpsRow(p.getId(), p.getMerchant().getBusinessName(),
                        p.getChannel(), p.getAmount(), p.getStatus(), p.getCreatedAt()))
                .toList();
    }

    // repos wired via fields below (assigned by constructor)
    private final com.mipay.sme.repository.MerchantRepository merchantRepoRef;
    private final com.mipay.sme.repository.PaymentRepository paymentRepoRef;
}
