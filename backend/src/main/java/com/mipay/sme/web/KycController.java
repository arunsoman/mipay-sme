package com.mipay.sme.web;

import com.mipay.sme.security.Current;
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

    @GetMapping("/api/orgs/kyc")
    public List<Dtos.KycSummaryRow> list(@RequestParam(required = false) String status,
                                         @RequestParam(required = false) Boolean suspended) {
        return kyc.list(Current.get(), status, suspended);
    }

    @GetMapping("/api/orgs/me/kyc")
    public Dtos.KycApplicationView mine() {
        return kyc.view(Current.get(), ownOrgId());
    }

    /** §12 GET /api/kyc/orgs/{id} — full view incl. profile, stage, tasks and verifications. */
    @GetMapping({"/api/orgs/{id}/kyc", "/api/kyc/orgs/{id}"})
    public Dtos.KycApplicationView get(@PathVariable String id) {
        return kyc.view(Current.get(), id);
    }

    /** §12 PUT /api/kyc/orgs/{id}?expectedVersion= — the body is the schema-shaped JSON. */
    @PutMapping({"/api/orgs/{id}/kyc", "/api/kyc/orgs/{id}"})
    public Dtos.KycApplicationView save(@PathVariable String id, @RequestBody Map<String, Object> body,
                                        @RequestParam(required = false) Long expectedVersion) {
        return kyc.saveDraft(Current.get(), id, new LinkedHashMap<>(body), expectedVersion);
    }

    /** §12 POST /api/kyc/orgs/{id}/submit {sendToChecker?} */
    @PostMapping({"/api/orgs/{id}/kyc/submit", "/api/kyc/orgs/{id}/submit"})
    public Dtos.KycApplicationView submit(@PathVariable String id,
                                          @RequestBody(required = false) Map<String, Object> body) {
        boolean sendToChecker = body != null && Boolean.TRUE.equals(body.get("sendToChecker"));
        return kyc.submit(Current.get(), id, sendToChecker);
    }

    // ---------------------------------------------------------------- §12 surface

    @GetMapping("/api/kyc/orgs/me")
    public Dtos.KycApplicationView mineV2() {
        return kyc.view(Current.get(), ownOrgId());
    }

    @GetMapping("/api/kyc/orgs")
    public List<Dtos.KycSummaryRow> listV2(@RequestParam(required = false) String status,
                                           @RequestParam(required = false) Boolean suspended) {
        return kyc.list(Current.get(), status, suspended);
    }

    /** Resolves the profile and returns sections, fields, conditions and rules for client hints. */
    @GetMapping("/api/kyc/schema")
    public Map<String, Object> schemaForOwnOrg(@RequestParam(name = "entityType", required = false) String entityType) {
        return schema.schemaFor(ownOrgId(), entityType);
    }

    @GetMapping("/api/kyc/schema/{orgId}")
    public Map<String, Object> schemaForOrg(@PathVariable String orgId,
                                            @RequestParam(name = "entityType", required = false) String entityType) {
        // object-level check: only orgs in the caller's own subtree (uniform 404 otherwise, no enumeration)
        tree.visible(Current.get(), orgId);
        return schema.schemaFor(orgId, entityType);
    }

    /** Large lists (MCC, branches) with parent filtering, search and paging. */
    @GetMapping("/api/kyc/lists/{code}")
    public Map<String, Object> list(@PathVariable String code,
                                    @RequestParam(name = "parent", required = false) String parent,
                                    @RequestParam(name = "q", required = false) String q,
                                    @RequestParam(name = "page", defaultValue = "0") int page,
                                    @RequestParam(name = "size", defaultValue = "50") int size) {
        return schema.listItems(code, parent, q, page, size);
    }

    /** §12 POST /api/kyc/orgs/{id}/upgrade — move a DRAFT to the profile's current schema version. */
    @PostMapping("/api/kyc/orgs/{id}/upgrade")
    public Dtos.KycApplicationView upgrade(@PathVariable String id) {
        return kyc.upgrade(Current.get(), id);
    }

    /** Withdraw a DRAFT or ENTRY_CHECK application (§6.4). */
    @PostMapping("/api/kyc/orgs/{id}/withdraw")
    public Dtos.KycApplicationView withdraw(@PathVariable String id) {
        return kyc.withdraw(Current.get(), id);
    }

    /** Approval inbox: every open task the caller may act on (§12). */
    @GetMapping("/api/kyc/tasks")
    public List<Dtos.KycTaskRow> inbox() {
        return kyc.inbox(Current.get());
    }

    /** §7 provider callbacks — signature verified, deduped by provider_ref. */
    @PostMapping("/api/kyc/providers/{key}/events")
    public Map<String, Object> providerEvent(@PathVariable String key,
                                             @RequestBody Map<String, Object> body) {
        return kyc.providerEvent(Current.get(), key, body);
    }

    /** Task decisions: APPROVE / RETURN / REJECT / BLOCK (§12). */
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
