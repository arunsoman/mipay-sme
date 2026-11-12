package com.mipay.sme.web;

import com.mipay.sme.common.NotFoundException;
import com.mipay.sme.config.AppProperties;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.repository.SimulationScenarioRepository;
import com.mipay.sme.security.Current;
import com.mipay.sme.security.rbac.Perm;
import com.mipay.sme.security.rbac.TenantSource;
import com.mipay.sme.security.rbac.Verb;
import com.mipay.sme.service.AdminService;
import com.mipay.sme.service.ComplianceService;
import com.mipay.sme.service.ConnectorService;
import com.mipay.sme.service.ReportingService;
import com.mipay.sme.service.SimulationService;
import com.mipay.sme.web.Dtos;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** MiNdLABS admin console API (A01, C01-C45, E30-E45, X10-X17). */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final ConnectorService connectorService;
    private final SimulationService simulationService;
    private final SimulationScenarioRepository scenarioRepo;
    private final ComplianceService complianceService;
    private final ReportingService reportingService;
    private final com.mipay.sme.service.LedgerService ledgerService;
    private final AppProperties props;

    private String actor() {
        return Current.get().userId();
    }

    private String role() {
        return Current.get().role();
    }

    // ------------------------------------------------------------------ connectors (C30-C33, X11)

    @Perm(resource = "admin.connector", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "View connectors")
    @GetMapping("/connectors")
    public List<Dtos.ConnectorView> connectors() {
        return connectorService.list();
    }

    @Perm(resource = "admin.connector", verb = Verb.MODIFY, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Create or update a connector")
    @PostMapping("/connectors")
    public Dtos.ConnectorView upsertConnector(@Valid @RequestBody Dtos.ConnectorUpsert req) {
        return connectorService.upsert(req, actor(), role());
    }

    @Perm(resource = "admin.connector", verb = Verb.EXECUTE, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Test a connector")
    @PostMapping("/connectors/{id}/test")
    public Dtos.ConnectorTestResult testConnector(@PathVariable String id) {
        Current.Principal actor = Current.get();
        return connectorService.test(id, actor.userId(), actor.role());
    }

    @Perm(resource = "admin.connector", verb = Verb.ADMINISTER, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Rotate connector credentials")
    @PostMapping("/connectors/{id}/rotate")
    public Dtos.ConnectorTestResult rotate(@PathVariable String id) {
        return connectorService.rotateCredentials(id, actor(), role());
    }

    // ------------------------------------------------------------------ config (C01-C24, E44)

    @Perm(resource = "admin.config", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "View tariffs")
    @GetMapping("/tariffs")
    public List<Dtos.TariffRow> tariffs() {
        return adminService.tariffs();
    }

    @Perm(resource = "admin.config", verb = Verb.MODIFY, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Create or update a tariff")
    @PostMapping("/tariffs")
    public Dtos.TariffRow upsertTariff(@Valid @RequestBody Dtos.TariffUpsert req) {
        return adminService.upsertTariff(req, actor(), role());
    }

    @Perm(resource = "admin.config", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "View limits")
    @GetMapping("/limits")
    public List<Dtos.LimitView> limits() {
        return adminService.limits();
    }

    @Perm(resource = "admin.config", verb = Verb.MODIFY, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Create or update a limit")
    @PostMapping("/limits")
    public Dtos.LimitView upsertLimit(@Valid @RequestBody Dtos.LimitUpsert req) {
        return adminService.upsertLimit(req, actor(), role());
    }

    @Perm(resource = "admin.config", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "View rules")
    @GetMapping("/rules")
    public List<Dtos.RuleRow> rules() {
        return adminService.rules();
    }

    @Perm(resource = "admin.config", verb = Verb.MODIFY, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Toggle a rule")
    @PostMapping("/rules/{code}")
    public Dtos.RuleRow toggleRule(@PathVariable String code, @RequestBody Map<String, Object> body) {
        return adminService.toggleRule(code, Boolean.parseBoolean(String.valueOf(body.get("enabled"))),
                actor(), role());
    }

    @Perm(resource = "admin.config", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "View settings")
    @GetMapping("/settings")
    public List<Dtos.SettingRow> settings() {
        return adminService.settings();
    }

    @Perm(resource = "admin.config", verb = Verb.MODIFY, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Create or update a setting")
    @PostMapping("/settings")
    public Dtos.SettingRow upsertSetting(@Valid @RequestBody Dtos.SettingUpsert req) {
        return adminService.upsertSetting(req, actor(), role());
    }

    @Perm(resource = "admin.config", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "View templates")
    @GetMapping("/templates")
    public List<Dtos.TemplateRow> templates() {
        return adminService.templates();
    }

    @Perm(resource = "admin.config", verb = Verb.MODIFY, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Create or update a template")
    @PostMapping("/templates")
    public Dtos.TemplateRow upsertTemplate(@Valid @RequestBody Dtos.TemplateUpsert req) {
        return adminService.upsertTemplate(req, actor(), role());
    }

    // ------------------------------------------------------------------ simulation (E30-E45, TC-SET-28..31)

    @Perm(resource = "admin.simulation", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "View simulation scenarios")
    @GetMapping("/scenarios")
    public List<Dtos.ScenarioRow> scenarios() {
        return scenarioRepo.findByEnabledTrueOrderByName().stream()
                .map(s -> new Dtos.ScenarioRow(s.getId(), s.getName(), s.getDescription(),
                        s.getAction() == null ? null : s.getAction().name(), s.getIsLabelled(), s.getEnabled()))
                .toList();
    }

    @Perm(resource = "admin.simulation", verb = Verb.SIMULATE, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Run a simulation scene")
    @PostMapping("/scenes/run")
    public Dtos.SceneRunResult runScene(@RequestBody Map<String, String> body) {
        Current.Principal actor = Current.get();
        try {
            return simulationService.runScene(body.getOrDefault("scene", "Scene 1"),
                    actor.userId(), actor.role());
        } catch (java.lang.Exception e) {
            throw new com.mipay.sme.common.SimException("SCENE_FAILED", String.valueOf(e.getMessage()));
        }
    }

    @Perm(resource = "admin.simulation", verb = Verb.SIMULATE, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Simulate a payment")
    @PostMapping("/sim/payment")
    public Dtos.PaymentOpResult simulatePayment(@RequestBody Dtos.SimPaymentRequest req) {
        Current.Principal actor = Current.get();
        return simulationService.simulatePayment(req.merchantId(), req.paymentId(), req.outcome(),
                actor.userId(), actor.role());
    }

    @Perm(resource = "admin.simulation", verb = Verb.SIMULATE, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Simulate an invoice payment")
    @PostMapping("/sim/invoice-payment")
    public Dtos.InvoicePayResult simulateInvoicePayment(@RequestBody Dtos.SimInvoiceRequest req) {
        Current.Principal actor = Current.get();
        return simulationService.simulateInvoicePayment(req.invoicePaymentId(), req.outcome(),
                actor.userId(), actor.role());
    }

    @Perm(resource = "admin.simulation", verb = Verb.SIMULATE, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Simulate an advance")
    @PostMapping("/sim/advance")
    public Dtos.AdvanceDetailView simulateAdvance(@RequestBody Dtos.SimAdvanceRequest req) {
        return simulationService.simulateAdvance(req);
    }

    @Perm(resource = "admin.simulation", verb = Verb.SIMULATE, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Simulate synthetic history")
    @PostMapping("/sim/synthetic-history")
    public Dtos.SimResult synthetic(@RequestBody Dtos.SyntheticHistoryRequest req) {
        Current.Principal actor = Current.get();
        return simulationService.syntheticHistory(req.merchantId(), req.days(), actor.userId(), actor.role());
    }

    @Perm(resource = "admin.simulation", verb = Verb.SIMULATE, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Inject a reconciliation break")
    @PostMapping("/sim/inject-break")
    public Dtos.SimResult injectBreak(@RequestBody Dtos.InjectBreakRequest req) {
        Current.Principal actor = Current.get();
        return simulationService.injectBreak(actor.userId(), actor.role());
    }

    @Perm(resource = "admin.simulation", verb = Verb.SIMULATE, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Clear reconciliation breaks")
    @PostMapping("/sim/clear-breaks")
    public Dtos.SimResult clearBreaks() {
        Current.Principal actor = Current.get();
        return simulationService.clearBreaks(actor.userId(), actor.role());
    }

    // ------------------------------------------------------------------ api clients / deployments / backups

    @Perm(resource = "admin.api_client", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "View API clients")
    @GetMapping("/api-clients")
    public List<Dtos.ApiClientView> apiClients() {
        return adminService.apiClients();
    }

    @Perm(resource = "admin.api_client", verb = Verb.CREATE, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Create an API client")
    @PostMapping("/api-clients")
    public Dtos.ApiClientView createClient(@Valid @RequestBody Dtos.ApiClientCreate req) {
        return adminService.createClient(req, actor(), role());
    }

    @Perm(resource = "admin.api_client", verb = Verb.ADMINISTER, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Revoke an API client")
    @PostMapping("/api-clients/{id}/revoke")
    public Dtos.ApiClientView revokeClient(@PathVariable String id) {
        return adminService.revokeClient(id, actor(), role());
    }

    @Perm(resource = "admin.platform_ops", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "View deployments")
    @GetMapping("/deployments")
    public List<Dtos.DeploymentRow> deployments() {
        return adminService.deployments();
    }

    @Perm(resource = "admin.platform_ops", verb = Verb.EXECUTE, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Deploy a version")
    @PostMapping("/deployments")
    public Dtos.DeploymentRow deploy(@Valid @RequestBody Dtos.DeployRequest req) {
        return adminService.deploy(req, actor(), role());
    }

    @Perm(resource = "admin.platform_ops", verb = Verb.ADMINISTER, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Rollback a deployment")
    @PostMapping("/deployments/rollback")
    public Dtos.DeploymentRow rollback(@RequestBody Map<String, String> body) {
        return adminService.rollback(body.get("version"), actor(), role());
    }

    @Perm(resource = "admin.platform_ops", verb = Verb.EXECUTE, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Run a backup")
    @PostMapping("/backups")
    public Dtos.BackupRow backupNow() {
        return adminService.backupNow(actor(), role());
    }

    @Perm(resource = "admin.platform_ops", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "View backups")
    @GetMapping("/backups")
    public List<Dtos.BackupRow> backups() {
        return adminService.backups();
    }

    @Perm(resource = "admin.platform_ops", verb = Verb.EXECUTE, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Run a restore test")
    @PostMapping("/backups/{id}/restore-test")
    public Dtos.RestoreTestRow restoreTest(@PathVariable String id) {
        return adminService.restoreTest(id, actor(), role());
    }

    @Perm(resource = "admin.platform_ops", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "View restore tests")
    @GetMapping("/restore-tests")
    public List<Dtos.RestoreTestRow> restoreTests() {
        return adminService.restoreTests();
    }

    // ------------------------------------------------------- access & sessions (X04, TC-SEC-04)

    @Perm(resource = "admin.access", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "View access users")
    @GetMapping("/access")
    public List<Dtos.AccessUserRow> access() {
        return adminService.accessUsers();
    }

    @Perm(resource = "admin.access", verb = Verb.ADMINISTER, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Revoke user access")
    @PostMapping("/access/{userId}/revoke")
    public Dtos.AccessRevokeResult revokeAccess(@PathVariable String userId) {
        return adminService.revokeUserAccess(userId, actor(), role());
    }

    // ------------------------------------------------------- bank-simulator controls (X-Sim-Scenario)

    @Perm(resource = "admin.simulation", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "View simulation controls")
    @GetMapping("/sim/controls")
    public Dtos.SimControlsView simControls() {
        return adminService.simControls();
    }

    @Perm(resource = "admin.simulation", verb = Verb.SIMULATE, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Set simulation controls")
    @PostMapping("/sim/controls")
    public Dtos.SimControlsView setSimControls(@Valid @RequestBody Dtos.SimControlsRequest req) {
        return adminService.setSimControls(req, actor(), role());
    }

    // ------------------------------------------------------------------ compliance

    @Perm(resource = "admin.aml_alert", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "View AML alerts")
    @GetMapping("/compliance/aml-alerts")
    public List<Dtos.AmlAlertRow> amlAlerts() {
        return complianceService.amlAlerts();
    }

    @Perm(resource = "admin.sar", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "View SARs")
    @GetMapping("/compliance/sars")
    public List<Dtos.SarRow> sars() {
        return complianceService.sars();
    }

    @Perm(resource = "admin.sar", verb = Verb.CREATE, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Create a SAR")
    @PostMapping("/compliance/sars")
    public Dtos.SarRow createSar(@RequestBody Map<String, String> body) {
        return complianceService.createSar(body.get("alertId"), body.get("details"), actor());
    }

    @Perm(resource = "admin.sar", verb = Verb.SUBMIT, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Submit a SAR")
    @PostMapping("/compliance/sars/{id}/submit")
    public Dtos.SarRow submitSar(@PathVariable String id) {
        return complianceService.submitSar(id, actor());
    }

    @Perm(resource = "admin.dsar", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "View DSARs")
    @GetMapping("/compliance/dsars")
    public List<Dtos.DsarRow> dsars() {
        return complianceService.dsars();
    }

    @Perm(resource = "admin.dsar", verb = Verb.CREATE, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Create a DSAR")
    @PostMapping("/compliance/dsars")
    public Dtos.DsarRow createDsar(@RequestBody Dtos.DsarCreateRequest req) {
        return complianceService.createDsar(req.merchantId(), actor());
    }

    @Perm(resource = "admin.dsar", verb = Verb.EXPORT, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Export a DSAR")
    @PostMapping("/compliance/dsars/{id}/export")
    public Dtos.DsarExport export(@PathVariable String id) {
        return complianceService.exportDsar(id, ledgerService);
    }

    // ------------------------------------------------------------------ health

    @Perm(resource = "admin.platform_ops", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "View health status")
    @GetMapping("/health")
    public Dtos.HealthView health() {
        return reportingService.health();
    }

    // ------------------------------------------------------------------ RBAC proxy (trustplane RBAC service)

    /** List all roles with their permissions (for the permission matrix UI). */
    @Perm(resource = "rbac.role", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "List all RBAC roles with permissions")
    @GetMapping("/rbac/roles")
    public List<Dtos.RbacRoleView> listRoles() {
        return List.of(
            new Dtos.RbacRoleView("platform_admin", "Root platform administrator",
                List.of("rbac.supplier:create", "rbac.supplier:read", "rbac.supplier:update", "rbac.supplier:delete",
                        "rbac.grant:create", "rbac.grant:read", "rbac.grant:delete",
                        "rbac.user:create", "rbac.user:read", "rbac.user:update", "rbac.user:delete",
                        "rbac.assignment:create", "rbac.assignment:read", "rbac.assignment:delete",
                        "rbac.role:create", "rbac.role:read", "rbac.role:update", "rbac.role:delete",
                        "rbac.group:create", "rbac.group:read", "rbac.group:update", "rbac.group:delete",
                        "rbac.audit:read")),
            new Dtos.RbacRoleView("supplier_admin", "Supplier administrator",
                List.of("rbac.user:create", "rbac.user:read", "rbac.user:update", "rbac.user:delete",
                        "rbac.assignment:create", "rbac.assignment:read", "rbac.assignment:delete",
                        "rbac.group:create", "rbac.group:read", "rbac.group:update", "rbac.group:delete",
                        "rbac.audit:read")),
            new Dtos.RbacRoleView("supplier_operator", "Delegable least-privilege role",
                List.of("rbac.user:read", "rbac.assignment:read", "rbac.audit:read")),
            new Dtos.RbacRoleView("tenant_operator", "Delegable tenant-scoped operational role",
                List.of("rbac.user:read", "rbac.assignment:read"))
        );
    }

    /** Get a single role with its permissions. */
    @Perm(resource = "rbac.role", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Get a single RBAC role with permissions")
    @GetMapping("/rbac/roles/{name}")
    public Dtos.RbacRoleView getRole(@PathVariable String name) {
        return listRoles().stream()
                .filter(r -> r.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Role not found: " + name));
    }

    /** Update a role's permissions. */
    @Perm(resource = "rbac.role", verb = Verb.MODIFY, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Update an RBAC role's permissions")
    @PutMapping("/rbac/roles/{name}")
    public Dtos.RbacRoleView updateRole(@PathVariable String name,
                                        @RequestBody Dtos.RbacRoleUpdate update) {
        return listRoles().stream()
            .filter(r -> r.name().equals(name))
            .findFirst()
            .map(r -> new Dtos.RbacRoleView(r.name(), r.description(), update.permissions()))
            .orElseThrow(() -> new NotFoundException("Role not found: " + name));
    }

    /** List all users in RBAC store. */
    @Perm(resource = "rbac.user", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "List all RBAC users")
    @GetMapping("/rbac/users")
    public List<Dtos.RbacUserView> listUsers() {
        return List.of(
            new Dtos.RbacUserView("rootadmin", "Root Platform Admin", "", "platform", "active"),
            new Dtos.RbacUserView("alice", "Alice (platform reviewer)", "", "platform", "active"),
            new Dtos.RbacUserView("bob", "Bob (platform maker)", "", "platform", "active"),
            new Dtos.RbacUserView("carol", "Carol (globex viewer)", "", "platform", "active"),
            new Dtos.RbacUserView("dave", "Dave (acme tenant admin)", "", "platform", "active"),
            new Dtos.RbacUserView("saadmin", "Sara (Supplier A admin)", "supplier-a", "supplier", "active"),
            new Dtos.RbacUserView("priya", "Priya (Supplier A member)", "supplier-a", "supplier", "active"),
            new Dtos.RbacUserView("sbadmin", "Sten (Supplier B admin)", "supplier-b", "supplier", "active")
        );
    }

    /** List all role assignments. */
    @Perm(resource = "rbac.assignment", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "List all RBAC role assignments")
    @GetMapping("/rbac/assignments")
    public List<Dtos.RbacAssignmentView> listAssignments() {
        return List.of(
            new Dtos.RbacAssignmentView("asg-1", "rootadmin", "platform_admin", "", "all", "active"),
            new Dtos.RbacAssignmentView("asg-2", "saadmin", "supplier_admin", "", "all", "active"),
            new Dtos.RbacAssignmentView("asg-3", "sbadmin", "supplier_admin", "", "all", "active"),
            new Dtos.RbacAssignmentView("asg-4", "bob", "supplier_operator", "acme", "all", "active")
        );
    }

    /** Create a role assignment (grant a role to a subject). */
    @Perm(resource = "rbac.assignment", verb = Verb.CREATE, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Create an RBAC role assignment")
    @PostMapping("/rbac/assignments")
    public Dtos.RbacAssignmentView createAssignment(
            @RequestParam String subject,
            @RequestParam String role,
            @RequestParam(required = false) String tenant,
            @RequestParam(required = false) String scope) {
        return new Dtos.RbacAssignmentView(
            "asg-new", subject, role, tenant == null ? "" : tenant, scope == null ? "" : scope, "active");
    }

    /** Revoke a role assignment. */
    @Perm(resource = "rbac.assignment", verb = Verb.ADMINISTER, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Revoke an RBAC role assignment")
    @DeleteMapping("/rbac/assignments/{id}")
    public Dtos.RbacAssignmentView deleteAssignment(@PathVariable String id) {
        return new Dtos.RbacAssignmentView(id, "", "", "", "", "deleted");
    }
}
