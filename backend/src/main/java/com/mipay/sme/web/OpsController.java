package com.mipay.sme.web;

import com.mipay.sme.config.AppProperties;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.security.Current;
import com.mipay.sme.security.rbac.Perm;
import com.mipay.sme.security.rbac.TenantSource;
import com.mipay.sme.security.rbac.Verb;
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
    @Perm(resource = "ops.kyc_check", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "View KYC checks for an entity")
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
    @Perm(resource = "ops.kyc_check", verb = Verb.CREATE, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Record a KYC check")
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

    @Perm(resource = "ops.merchant_approval", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "View merchant approval queue")
    @GetMapping("/approvals/merchants")
    public List<Dtos.MerchantApprovalRow> merchantQueue() {
        // legacy queue leaves out orgs onboarded through the tree (parent != root) [plan C1]
        return merchantRepoRef.findQueue(Enums.ApprovalStatus.PENDING).stream()
                .filter(r -> isLegacyRow("MERCHANT", r.id()))
                .toList();
    }

    @Perm(resource = "ops.merchant_approval", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "View all merchant approval rows")
    @GetMapping("/approvals/merchants/all")
    public List<Dtos.MerchantApprovalRow> merchantsAll() {
        return merchantRepoRef.findAllRows();
    }

    @Perm(resource = "ops.merchant_approval", verb = Verb.AUTHORIZE, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Approve or reject a merchant")
    @PostMapping("/approvals/merchants/{id}")
    public Dtos.ApprovalResult approveMerchant(@PathVariable String id,
                                               @Valid @RequestBody Dtos.ApprovalRequest req) {
        return onboardingService.approveMerchant(id, req.approve(), req.sanctionsResult(),
                actor(), "TPTS_OPS");
    }

    @Perm(resource = "ops.supplier_approval", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "View supplier approval queue")
    @GetMapping("/approvals/suppliers")
    public List<Dtos.SupplierApprovalRow> supplierQueue() {
        return onboardingService.supplierQueue().stream()
                .filter(r -> isLegacyRow("SUPPLIER", r.id()))
                .toList();
    }

    @Perm(resource = "ops.supplier_approval", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "View all supplier approval rows")
    @GetMapping("/approvals/suppliers/all")
    public List<Dtos.SupplierApprovalRow> suppliersAll() {
        return onboardingService.suppliersAllRows();
    }

    @Perm(resource = "ops.supplier_approval", verb = Verb.AUTHORIZE, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Approve or reject a supplier")
    @PostMapping("/approvals/suppliers/{id}")
    public Dtos.ApprovalResult approveSupplier(@PathVariable String id,
                                               @Valid @RequestBody Dtos.ApprovalRequest req) {
        return onboardingService.approveSupplier(id, req.approve(), actor(), "TPTS_OPS");
    }

    @Perm(resource = "ops.bank_account_approval", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "View bank account approval queue")
    @GetMapping("/approvals/bank-accounts")
    public List<Dtos.BankApprovalRow> bankQueue() {
        return onboardingService.bankQueue();
    }

    @Perm(resource = "ops.bank_account_approval", verb = Verb.AUTHORIZE, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Approve or reject a bank account")
    @PostMapping("/approvals/bank-accounts/{id}")
    public Dtos.ApprovalResult approveBankAccount(@PathVariable String id,
                                                  @Valid @RequestBody Dtos.BankApprovalRequest req) {
        return onboardingService.approveBankAccount(id, req.approve(), actor(), "TPTS_OPS");
    }

    // ------------------------------------------------------------------ org onboarding review [ops]

    /** Review queue for tree onboarding requests (TPTS_OPS). */
    @Perm(resource = "ops.onboarding_request", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "View onboarding request queue")
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

    @Perm(resource = "ops.onboarding_request", verb = Verb.AUTHORIZE, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Decide an onboarding request")
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

    @Perm(resource = "ops.recon", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "View reconciliation runs")
    @GetMapping("/recon/runs")
    public List<Dtos.ReconRunRow> reconRuns() {
        return reconService.runs();
    }

    @Perm(resource = "ops.recon", verb = Verb.EXECUTE, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Run reconciliation")
    @PostMapping("/recon/run")
    public Dtos.RunResult runRecon() {
        return reconService.run();
    }

    @Perm(resource = "ops.recon", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "View reconciliation breaks")
    @GetMapping("/recon/runs/{id}/breaks")
    public List<Dtos.BreakRow> breaks(@PathVariable String id) {
        return reconService.breaks(id);
    }

    @Perm(resource = "ops.recon", verb = Verb.MODIFY, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Resolve a reconciliation break")
    @PostMapping("/recon/breaks/{id}/resolve")
    public Dtos.BreakRow resolveBreak(@PathVariable String id) {
        return reconService.resolveBreak(id, actor());
    }

    // ------------------------------------------------------------------ incidents (T16)

    @Perm(resource = "ops.incident", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "View incidents")
    @GetMapping("/incidents")
    public List<Dtos.IncidentRow> incidents() {
        return opsService.incidents();
    }

    @Perm(resource = "ops.incident", verb = Verb.CREATE, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Create an incident")
    @PostMapping("/incidents")
    public Dtos.IncidentRow createIncident(@Valid @RequestBody Dtos.CreateIncidentRequest req) {
        return opsService.createIncident(req, actor());
    }

    @Perm(resource = "ops.incident", verb = Verb.MODIFY, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Acknowledge an incident")
    @PostMapping("/incidents/{id}/ack")
    public Dtos.IncidentRow ack(@PathVariable String id) {
        return opsService.acknowledge(id, actor());
    }

    @Perm(resource = "ops.incident", verb = Verb.MODIFY, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Resolve an incident")
    @PostMapping("/incidents/{id}/resolve")
    public Dtos.IncidentRow resolveIncident(@PathVariable String id) {
        return opsService.resolveIncident(id, actor());
    }

    // ------------------------------------------------------------------ complaints / disputes (T07/T10/T11)

    @Perm(resource = "ops.complaint", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "View complaints")
    @GetMapping("/complaints")
    public List<Dtos.ComplaintRow> complaints() {
        return opsService.complaints();
    }

    @Perm(resource = "ops.complaint", verb = Verb.CREATE, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Create a complaint")
    @PostMapping("/complaints")
    public Dtos.ComplaintRow createComplaint(@Valid @RequestBody Dtos.CreateComplaintRequest req) {
        return opsService.createComplaint(req, actor());
    }

    @Perm(resource = "ops.complaint", verb = Verb.MODIFY, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Assign a complaint")
    @PostMapping("/complaints/{id}/assign")
    public Dtos.Message assign(@PathVariable String id, @RequestParam(required = false) String assignee) {
        opsService.assignComplaint(id, assignee == null ? actor() : assignee);
        return new Dtos.Message("Complaint assigned", null);
    }

    @Perm(resource = "ops.complaint", verb = Verb.MODIFY, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Resolve a complaint")
    @PostMapping("/complaints/{id}/resolve")
    public Dtos.Message resolveComplaint(@PathVariable String id,
                                         @RequestParam(required = false) String resolution) {
        opsService.resolveComplaint(id, resolution, actor());
        return new Dtos.Message("Complaint resolved", null);
    }

    @Perm(resource = "ops.dispute", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "View disputes")
    @GetMapping("/disputes")
    public List<Dtos.DisputeRow> disputes() {
        return opsService.disputes();
    }

    @Perm(resource = "ops.dispute", verb = Verb.CREATE, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Create a dispute")
    @PostMapping("/disputes")
    public Dtos.Message createDispute(@Valid @RequestBody Dtos.CreateDisputeRequest req) {
        opsService.createDispute(req.paymentId(), req.reason(), req.details(), actor());
        return new Dtos.Message("Dispute created and tracked", null);
    }

    @Perm(resource = "ops.dispute", verb = Verb.MODIFY, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Resolve a dispute")
    @PostMapping("/disputes/{id}/resolve")
    public Dtos.Message resolveDispute(@PathVariable String id, @RequestParam boolean won) {
        opsService.resolveDispute(id, won, actor());
        return new Dtos.Message("Dispute resolved as " + (won ? "WON" : "LOST"), null);
    }

    // ------------------------------------------------------------------ suspensions (T15)

    @Perm(resource = "ops.suspension", verb = Verb.ADMINISTER, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Suspend an org")
    @PostMapping("/suspend")
    public Dtos.SuspensionRow suspend(@Valid @RequestBody Dtos.SuspendRequest req) {
        return opsService.suspend(req, actor(), "TPTS_OPS");
    }

    @Perm(resource = "ops.suspension", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "View suspensions")
    @GetMapping("/suspensions")
    public List<Dtos.SuspensionRow> suspensions() {
        return opsService.suspensions();
    }

    @Perm(resource = "ops.suspension", verb = Verb.ADMINISTER, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Lift a suspension")
    @PostMapping("/suspensions/{id}/lift")
    public List<Dtos.SuspensionRow> lift(@PathVariable String id) {
        return opsService.lift(id, actor());
    }

    // ------------------------------------------------------------------ audit + reports + statements

    @Perm(resource = "ops.audit", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "View audit log")
    @GetMapping("/audit")
    public Dtos.AuditPage audit(@RequestParam(defaultValue = "false") boolean simulated,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "25") int size) {
        return opsService.audit(simulated, page, size);
    }

    @Perm(resource = "ops.report", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "View settlement report")
    @GetMapping("/reports/settlement")
    public Dtos.SettlementRow settlement(@RequestParam(required = false) String date,
                                         @RequestParam(required = false) String cycle) {
        LocalDate d = date == null ? LocalDate.now() : LocalDate.parse(date);
        return reportingService.settlement(d, cycle);
    }

    @Perm(resource = "ops.report", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "View fee reports")
    @GetMapping("/reports/fees")
    public List<Dtos.FeeReportRow> feeReports(@RequestParam(required = false) String date) {
        LocalDate d = date == null ? LocalDate.now() : LocalDate.parse(date);
        return reportingService.feeReports(d);
    }

    @Perm(resource = "ops.report", verb = Verb.EXECUTE, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Generate a statement")
    @PostMapping("/statements/generate")
    public Dtos.StatementRow generateStatement(@Valid @RequestBody Dtos.GenerateStatementRequest req) {
        return statementService.generate(req.ownerType(), req.ownerId(), req.from(), req.to(), actor());
    }

    @Perm(resource = "ops.monitoring", verb = Verb.EXECUTE, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Record monitoring touchpoint")
    @PostMapping("/monitoring/touch")
    public Dtos.Message touch() {
        opsService.touchMetrics();
        return new Dtos.Message("Metrics recorded", props.isSimulatedEnvironment() ? "SIMULATED metrics" : null);
    }

    @Perm(resource = "ops.report", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "View payments")
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
