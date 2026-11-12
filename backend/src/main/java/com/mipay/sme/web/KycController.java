package com.mipay.sme.web;

import com.mipay.sme.security.Current;
import com.mipay.sme.security.rbac.Perm;
import com.mipay.sme.security.rbac.PublicEndpoint;
import com.mipay.sme.security.rbac.TenantSource;
import com.mipay.sme.security.rbac.Verb;
import com.mipay.sme.service.KycApplicationService;
import com.mipay.sme.service.OrgTreeService;
import com.mipay.sme.service.kyc.KycSchemaFacade;
import com.mipay.sme.service.OrgCallerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * KYC API (§12): schema-driven endpoints on /api/kyc with the legacy
 * /api/orgs/... paths kept as aliases so the portals keep working while they
 * migrate. The document body is schema-shaped JSON — unknown keys are
 * rejected and hidden fields are stripped by the master.
 */
@RestController
@RequiredArgsConstructor
public class KycController {

    private final KycApplicationService kyc;
    private final KycSchemaFacade schema;
    private final com.mipay.sme.service.OrgTreeService tree;
    private final OrgCallerService callerService;

    // ---------------------------------------------------------------- legacy aliases (still used by the portals)

    @Perm(resource = "kyc.application", verb = Verb.READ, tenantSource = TenantSource.SELF, tenant = "null",
          description = "List KYC applications for caller's org")
    @GetMapping("/api/orgs/kyc")
    public List<Dtos.KycSummaryRow> list(@RequestParam(required = false) String status,
                                         @RequestParam(required = false) Boolean suspended) {
        return kyc.list(Current.get(), status, suspended);
    }

    @Perm(resource = "kyc.application", verb = Verb.READ, tenantSource = TenantSource.SELF, tenant = "null",
          description = "View caller's own org KYC application")
    @GetMapping("/api/orgs/me/kyc")
    public Dtos.KycApplicationView mine() {
        return kyc.view(Current.get(), ownOrgId());
    }

    /** §12 GET /api/kyc/orgs/{id} — full view incl. profile, stage, tasks and verifications. */
    @Perm(resource = "kyc.application", verb = Verb.READ, tenantSource = TenantSource.PATH, tenant = "#id",
          description = "View a specific org's KYC application (PATH tenant)")
    @GetMapping({"/api/orgs/{id}/kyc", "/api/kyc/orgs/{id}"})
    public Dtos.KycApplicationView get(@PathVariable String id) {
        return kyc.view(Current.get(), id);
    }

    /** §12 PUT /api/kyc/orgs/{id}?expectedVersion= — the body is the schema-shaped JSON. */
    @Perm(resource = "kyc.application", verb = Verb.MODIFY, tenantSource = TenantSource.PATH, tenant = "#id",
          description = "Save KYC draft for an org (PATH tenant)")
    @PutMapping({"/api/orgs/{id}/kyc", "/api/kyc/orgs/{id}"})
    public Dtos.KycApplicationView save(@PathVariable String id, @RequestBody Map<String, Object> body,
                                        @RequestParam(required = false) Long expectedVersion) {
        return kyc.saveDraft(Current.get(), id, new LinkedHashMap<>(body), expectedVersion);
    }

    /** §12 POST /api/kyc/orgs/{id}/submit {sendToChecker?} */
    @Perm(resource = "kyc.application", verb = Verb.SUBMIT, tenantSource = TenantSource.PATH, tenant = "#id",
          description = "Submit KYC application for review (PATH tenant)")
    @PostMapping({"/api/orgs/{id}/kyc/submit", "/api/kyc/orgs/{id}/submit"})
    public Dtos.KycApplicationView submit(@PathVariable String id,
                                          @RequestBody(required = false) Map<String, Object> body) {
        boolean sendToChecker = body != null && Boolean.TRUE.equals(body.get("sendToChecker"));
        return kyc.submit(Current.get(), id, sendToChecker);
    }

    // ---------------------------------------------------------------- §12 surface

    @Perm(resource = "kyc.application", verb = Verb.READ, tenantSource = TenantSource.SELF, tenant = "null",
          description = "View caller's own org KYC application (v2)")
    @GetMapping("/api/kyc/orgs/me")
    public Dtos.KycApplicationView mineV2() {
        return kyc.view(Current.get(), ownOrgId());
    }

    @Perm(resource = "kyc.application", verb = Verb.READ, tenantSource = TenantSource.SELF, tenant = "null",
          description = "List KYC applications (v2, caller's org)")
    @GetMapping("/api/kyc/orgs")
    public List<Dtos.KycSummaryRow> listV2(@RequestParam(required = false) String status,
                                           @RequestParam(required = false) Boolean suspended) {
        return kyc.list(Current.get(), status, suspended);
    }

    /** Resolves the profile and returns sections, fields, conditions and rules for client hints. */
    @Perm(resource = "kyc.form", verb = Verb.READ, tenantSource = TenantSource.SELF, tenant = "null",
          description = "Get KYC schema for caller's org")
    @GetMapping("/api/kyc/schema")
    public Map<String, Object> schemaForOwnOrg(@RequestParam(name = "entityType", required = false) String entityType) {
        return schema.schemaFor(ownOrgId(), entityType);
    }

    @Perm(resource = "kyc.form", verb = Verb.READ, tenantSource = TenantSource.PATH, tenant = "#orgId",
          description = "Get KYC schema for a specific org (PATH tenant)")
    @GetMapping("/api/kyc/schema/{orgId}")
    public Map<String, Object> schemaForOrg(@PathVariable String orgId,
                                            @RequestParam(name = "entityType", required = false) String entityType) {
        // object-level check: only orgs in the caller's own subtree (uniform 404 otherwise, no enumeration)
        tree.visible(Current.get(), orgId);
        return schema.schemaFor(orgId, entityType);
    }

    /** The KYC form for an org type, before the org exists (the onboarding wizard). */
    @Perm(resource = "kyc.form", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Get the KYC schema an org type fills in")
    @GetMapping("/api/kyc/schema/types/{orgType}")
    public Map<String, Object> schemaForType(@PathVariable String orgType,
                                             @RequestParam(name = "entityType", required = false) String entityType) {
        String t = orgType == null ? "" : orgType.toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("MANUFACTURER", "DISTRIBUTOR", "SUPPLIER", "MERCHANT", "LENDER").contains(t)) {
            throw new com.mipay.sme.common.BadRequestException("ORG_TYPE_REQUIRED", "unknown org type " + orgType);
        }
        return schema.schemaForType(t, "NA", entityType);
    }

    /** Large lists (MCC, branches) with parent filtering, search and paging. */
    @Perm(resource = "kyc.form", verb = Verb.READ, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Read KYC reference lists (MCC, branches, etc.)")
    @GetMapping("/api/kyc/lists/{code}")
    public Map<String, Object> list(@PathVariable String code,
                                    @RequestParam(name = "parent", required = false) String parent,
                                    @RequestParam(name = "q", required = false) String q,
                                    @RequestParam(name = "page", defaultValue = "0") int page,
                                    @RequestParam(name = "size", defaultValue = "50") int size) {
        return schema.listItems(code, parent, q, page, size);
    }

    /** §12 POST /api/kyc/orgs/{id}/upgrade — move a DRAFT to the profile's current schema version. */
    @Perm(resource = "kyc.application", verb = Verb.MODIFY, tenantSource = TenantSource.PATH, tenant = "#id",
          description = "Upgrade KYC application schema version (PATH tenant)")
    @PostMapping("/api/kyc/orgs/{id}/upgrade")
    public Dtos.KycApplicationView upgrade(@PathVariable String id) {
        return kyc.upgrade(Current.get(), id);
    }

    /** Withdraw a DRAFT or ENTRY_CHECK application (§6.4). */
    @Perm(resource = "kyc.application", verb = Verb.DELETE, tenantSource = TenantSource.PATH, tenant = "#id",
          description = "Withdraw a KYC application (PATH tenant)")
    @PostMapping("/api/kyc/orgs/{id}/withdraw")
    public Dtos.KycApplicationView withdraw(@PathVariable String id) {
        return kyc.withdraw(Current.get(), id);
    }

    /** Approval inbox: every open task the caller may act on (§12). */
    @Perm(resource = "kyc.task", verb = Verb.READ, tenantSource = TenantSource.SELF, tenant = "null",
          description = "List open KYC review tasks for caller")
    @GetMapping("/api/kyc/tasks")
    public List<Dtos.KycTaskRow> inbox() {
        return kyc.inbox(Current.get());
    }

    /** §7 provider callbacks — signature verified, deduped by provider_ref. */
    @PublicEndpoint(reason = "provider webhook callback - service identity (signature verified)")
    @PostMapping("/api/kyc/providers/{key}/events")
    public Map<String, Object> providerEvent(@PathVariable String key,
                                             @RequestBody Map<String, Object> body) {
        return kyc.providerEvent(Current.get(), key, body);
    }

    /** Task decisions: APPROVE / RETURN / REJECT / BLOCK (§12). */
    @Perm(resource = "kyc.task", verb = Verb.AUTHORIZE, tenantSource = TenantSource.NONE, tenant = "null",
          description = "Decide a KYC review task (platform staff)")
    @PostMapping("/api/kyc/tasks/{taskId}/decision")
    public Dtos.KycApplicationView decide(@PathVariable String taskId,
                                          @Valid @RequestBody Dtos.KycDecisionRequestV2 body) {
        return kyc.decideTask(Current.get(), taskId, body.outcome(), body.note());
    }

    private String ownOrgId() {
        var org = callerService.orgOf(Current.get());
        if (org == null) {
            throw new com.mipay.sme.common.NotFoundException("Caller has no organization");
        }
        return org.getId();
    }
}
