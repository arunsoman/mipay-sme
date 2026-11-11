package com.mipay.sme.service;

import com.mipay.sme.common.BadRequestException;
import com.mipay.sme.common.ConflictException;
import com.mipay.sme.common.ForbiddenException;
import com.mipay.sme.common.NotFoundException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.kyc.KycApplication;
import com.mipay.sme.domain.kyc.KycApplication.Status;
import com.mipay.sme.domain.org.Organization;
import com.mipay.sme.repository.KycApplicationRepository;
import com.mipay.sme.repository.KycSearchIndexRepository;
import com.mipay.sme.repository.OrgClosureRepository;
import com.mipay.sme.repository.OrgSubtreeRow;
import com.mipay.sme.repository.OrganizationRepository;
import com.mipay.sme.repository.KycProviderEventRepository;
import com.mipay.sme.repository.KycVerificationRepository;
import com.mipay.sme.repository.UserRepository;
import com.mipay.sme.security.Current;
import com.mipay.sme.web.Dtos;
import com.mipay.sme.service.kyc.KycDocValidator;
import com.mipay.sme.service.kyc.KycJson;
import com.mipay.sme.service.kyc.KycMasterService;
import com.mipay.sme.service.kyc.KycRuleValidators;
import com.mipay.sme.service.kyc.KycSecretProtector;
import com.mipay.sme.service.kyc.KycValidationContext;
import com.mipay.sme.service.kyc.KycWorkflowEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * KYC applications on the master (§3-§9): profile resolution, schema-driven
 * validation of a raw JSON document, SECRET encryption + blind index, the
 * workflow engine on submit and task decisions. Documents stay one JSON object
 * per application; drafts may be incomplete, never malformed, unknown keys are
 * rejected and hidden fields are stripped (§4).
 *
 * Who may do what (anything outside the caller's subtree is 404):
 *  - edit / submit: the org's own ADMIN, its parent org's ADMIN, platform staff
 *  - read in full: the same callers; higher ancestors read a masked copy
 *  - act on tasks: per the stage's actor rule (workflow engine)
 */
@Service
@RequiredArgsConstructor
public class KycApplicationService {

    private static final List<Status> OPEN =
            List.of(Status.DRAFT, Status.ENTRY_CHECK, Status.VERIFYING, Status.IN_REVIEW);

    private final KycApplicationRepository repo;
    private final OrganizationRepository orgRepo;
    private final OrgClosureRepository closureRepo;
    private final com.mipay.sme.repository.SuspensionRepository suspensionRepo;
    private final UserRepository userRepo;
    private final KycSearchIndexRepository blindIndexRepo;
    private final OrgCallerService callerService;
    private final AuditService auditService;
    private final OrgUserService orgUsers;
    private final KycMasterService master;
    private final KycDocValidator docValidator;
    private final KycSecretProtector protector;
    private final KycWorkflowEngine engine;
    private final com.mipay.sme.service.kyc.KycPluginRegistry plugins;
    private final com.mipay.sme.service.kyc.KycGoLiveService goLive;
    private final OrgGoLiveBridge goLiveBridge;
    private final KycVerificationRepository verificationRepo;
    private final KycProviderEventRepository providerEventRepo;

    private enum Relation { PLATFORM, SELF, PARENT, ANCESTOR }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public Dtos.KycApplicationView view(Current.Principal caller, String orgId) {
        Organization org = org(orgId);
        Relation rel = relation(caller, org);
        // pending checks inside the timeout window wait; past the window they
        // surface as errors and the flow resumes (§6.2 timeouts)
        repo.findByOrgIdAndStatusIn(org.getId(), OPEN).stream()
                .filter(a -> a.getStatus() == Status.VERIFYING)
                .findFirst()
                .ifPresent(engine::resumePending);
        KycApplication app = openApplication(orgId);
        return toView(caller, org, app, rel);
    }

    @Transactional(readOnly = true)
    public List<Dtos.KycSummaryRow> list(Current.Principal caller, String status, Boolean suspended) {
        Status st = status == null || status.isBlank() ? null : parseStatus(status);
        List<KycApplication> apps;
        if (callerService.platformStaff(caller)) {
            apps = status == null || status.isBlank()
                    ? (suspended == null
                        ? repo.findByStatusInOrderByUpdatedAtDesc(OPEN)
                        : repo.findAll(org.springframework.data.domain.Sort.by(
                            org.springframework.data.domain.Sort.Direction.DESC, "updatedAt")))
                    : repo.findByStatusOrderByUpdatedAtDesc(st);
        } else {
            Organization own = callerService.orgOf(caller);
            if (own == null) throw new NotFoundException("Caller has no organization");
            List<String> ids = closureRepo.findSubtree(own.getId()).stream()
                    .map(r -> r.getOrg().getId()).toList();
            apps = suspended == null
                    ? repo.findByOrgIdInAndStatusIn(ids, OPEN)
                    : repo.findByOrgIdIn(ids);
        }
        return apps.stream()
                .filter(a -> suspended == null || a.isSuspended() == suspended)
                .sorted(Comparator.comparing(KycApplication::getUpdatedAt).reversed())
                .map(a -> new Dtos.KycSummaryRow(a.getId(), a.getOrgId(), a.getOrgType().name(), a.getOrgName(),
                        a.getParentOrgId(), a.getTreePath(), a.getDepth(), a.isSuspended(), a.getStatus().name(),
                        a.getSubmittedAt(), a.getDecidedAt(), a.getUpdatedAt()))
                .toList();
    }

    // ------------------------------------------------------------------ writes

    @Transactional
    public Dtos.KycApplicationView saveDraft(Current.Principal caller, String orgId, Map<String, Object> body,
                                             Long expectedVersion) {
        Organization org = org(orgId);
        Relation rel = relation(caller, org);
        assertEditor(caller, rel);

        KycApplication app = repo.findByOrgIdAndStatusIn(org.getId(), OPEN)
                .stream().findFirst().orElse(null);
        KycMasterService.ProfileBundle bundle = resolveBundle(org,
                value(docOf(body), "businessProfile", "entityType"));

        KycDocValidator.Result result = docValidator.validateSave(docOf(body), bundle,
                contextOf(org), uniqueChecker(app == null ? null : app.getId(), org.getId()),
                app == null ? null : app.getId(), org.getId(),
                app == null ? null : app.getEntityType(), org.getOrgType().name());
        List<KycValidationContext.Problem> errors = result.problems().stream()
                .filter(KycValidationContext.Problem::isError).toList();
        if (!errors.isEmpty()) {
            throw new BadRequestException("KYC_INVALID", messages(errors));
        }

        String before;
        if (app == null) {
            app = new KycApplication();
            app.setOrgId(org.getId());
            app.setCreatedBy(caller.userId());
            app.setStatus(Status.DRAFT);
            before = null;
        } else {
            if (app.getStatus() != Status.DRAFT) {
                throw new ConflictException("KYC_LOCKED", "Only a DRAFT application can be edited (now "
                        + app.getStatus() + ")");
            }
            if (expectedVersion != null && expectedVersion != app.getVersion()) {
                throw new ConflictException("KYC_VERSION_CONFLICT",
                        "KYC application changed since it was loaded (version " + app.getVersion() + ")");
            }
            before = app.getStatus().name();
        }
        app.setStatus(Status.DRAFT);
        applyProfile(app, bundle, value(result.doc(), "businessProfile", "entityType"));
        // store the encrypted form: SECRET values never appear in plaintext (§8)
        app.setApplicationJson(KycJson.write(
                protector.encryptSecrets(new LinkedHashMap<>(result.doc()), bundle, app.getId())));
        app.setUpdatedBy(caller.userId());
        app.setDecidedAt(null);
        app.setDecidedBy(null);
        fillTreeDetails(app, org);
        repo.save(app);
        // the first save must write the blind index as well (§8)
        protector.indexSecrets(app.getId(), bundle, KycJson.parseMap(app.getApplicationJson()));

        auditService.record(caller.userId(), caller.role(), "kyc_application_saved", "organization", org.getId(),
                before, Status.DRAFT.name(), null, false, null);
        return toView(caller, org, app, rel);
    }

    @Transactional
    public Dtos.KycApplicationView submit(Current.Principal caller, String orgId, Boolean sendToChecker) {
        Organization org = org(orgId);
        Relation rel = relation(caller, org);
        assertEditor(caller, rel);
        KycApplication app = repo.findByOrgIdAndStatusIn(org.getId(), OPEN).stream()
                .filter(a -> a.getStatus() == Status.DRAFT)
                .findFirst()
                .orElseThrow(() -> new ConflictException("KYC_NOT_DRAFT",
                        "Only a DRAFT application can be submitted"));

        KycMasterService.ProfileBundle bundle = bundleOf(app);
        Map<String, Object> doc = protector.decryptSecrets(new LinkedHashMap<>(
                KycJson.parseMap(app.getApplicationJson())), bundle);

        KycDocValidator.Result result = docValidator.validateSubmit(doc, bundle,
                contextOf(org), uniqueChecker(app.getId(), org.getId()), app.getId(), org.getId(),
                app.getEntityType(), app.getOrgType() == null ? null : app.getOrgType().name());
        List<KycValidationContext.Problem> errors = result.problems().stream()
                .filter(KycValidationContext.Problem::isError).toList();
        if (!errors.isEmpty()) {
            throw new BadRequestException("KYC_INCOMPLETE", messages(errors));
        }

        // persist corrections (derived fields, legacy pads); freeze as submitted below
        app.setApplicationJson(KycJson.write(
                protector.encryptSecrets(new LinkedHashMap<>(result.doc()), bundle, app.getId())));
        repo.save(app);

        engine.startFlow(app, caller, Boolean.TRUE.equals(sendToChecker), new LinkedHashMap<>(result.doc()));
        auditService.record(caller.userId(), caller.role(), "kyc_application_submitted", "organization",
                app.getOrgId(), Status.DRAFT.name(), app.getStatus().name(), null, false, null);
        return toView(caller, org, app, rel);
    }

    /** §6.4: a DRAFT or ENTRY_CHECK application may be withdrawn by its owners. */
    @Transactional
    public Dtos.KycApplicationView withdraw(Current.Principal caller, String orgId) {
        Organization org = org(orgId);
        Relation rel = relation(caller, org);
        assertEditor(caller, rel);
        KycApplication app = repo.findByOrgIdAndStatusIn(org.getId(), OPEN)
                .stream().filter(a -> a.getStatus() == Status.DRAFT
                        || a.getStatus() == Status.ENTRY_CHECK)
                .findFirst()
                .orElseThrow(() -> new ConflictException("KYC_NOT_WITHDRAWABLE",
                        "Only a DRAFT or ENTRY_CHECK application can be withdrawn"));
        app.setStatus(Status.WITHDRAWN);
        // retention: 5 years from the final state (§17)
        app.setRetainUntil(java.time.Instant.now().plusSeconds(5L * 365 * 24 * 3600));
        repo.save(app);
        auditService.record(caller.userId(), caller.role(), "kyc_withdrawn", "organization",
                org.getId(), app.getStatus().name(), Status.WITHDRAWN.name(), null, false, null);
        return toView(caller, org, app, rel);
    }

    /** §7 provider callback: deduped by (provider, event_id); verified manually for now (§7). */
    @Transactional
    public Map<String, Object> providerEvent(Current.Principal caller, String key,
                                             Map<String, Object> body) {
        String eventId = (String) body.getOrDefault("event_id", body.getOrDefault("eventId", null));
        String status = String.valueOf(body.getOrDefault("status", "CLEAR"));
        String detail = (String) body.get("detail");
        if (eventId == null) {
            throw new com.mipay.sme.common.BadRequestException("MISSING_EVENT_ID",
                    "event_id is required");
        }
        // dedupe: an event already processed is ignored
        if (providerEventRepo.findByProviderAndEventId(key, eventId).isPresent()) {
            return Map.of("status", "ignored", "reason", "duplicate event");
        }
        var ev = new com.mipay.sme.domain.kyc.KycProviderEvent();
        ev.setProvider(key);
        ev.setEventId(eventId);
        ev.setPayload(com.mipay.sme.service.kyc.KycJson.write(body));
        ev.setReceivedAt(java.time.Instant.now());
        ev.setProcessed(true);
        providerEventRepo.save(ev);
        auditService.record(caller.userId(), caller.role(), "kyc_provider_event", "organization",
                null, null, null, "provider=" + key + ";event=" + eventId + ";status=" + status,
                false, null);
        return Map.of("status", "recorded", "event", eventId);
    }

    /** Task decisions go through the engine; direct decisions are removed (§12). */
    @Transactional
    public Dtos.KycApplicationView decideTask(Current.Principal caller, String taskId, String outcome, String note) {
        KycApplication app = engine.decide(taskId, caller, outcome, note);
        Organization org = org(app.getOrgId());
        return toView(caller, org, app, relation(caller, org));
    }

    @Transactional(readOnly = true)
    public List<Dtos.KycTaskRow> inbox(Current.Principal caller) {
        return engine.inbox(caller).stream()
                .map(t -> {
                    KycApplication app = repo.findById(t.getApplicationId()).orElse(null);
                    return new Dtos.KycTaskRow(t.getId(), app == null ? null : app.getOrgId(),
                            app == null ? null : app.getOrgName(),
                            t.getKind().name(), t.getStageKey(), t.getStageSeq(),
                            t.getActorRule(), app == null ? null : app.getStatus().name(),
                            t.getCreatedAt(), t.getNote());
                })
                .toList();
    }

    /** Called by onboarding (two-call flow, §12): an empty DRAFT keyed to the child org. */
    @Transactional
    public String createDraftAtOnboarding(Organization child, String requestingUserId) {
        KycApplication app = repo.findByOrgIdAndStatusIn(child.getId(), OPEN)
                .stream().findFirst().orElse(null);
        if (app != null) return app.getId();
        KycMasterService.ProfileBundle bundle = master.resolveByOrg(child.getOrgType().name(),
                "NA", null);
        app = new KycApplication();
        app.setOrgId(child.getId());
        app.setCreatedBy(requestingUserId);
        app.setStatus(Status.DRAFT);
        applyProfile(app, bundle, null);
        app.setApplicationJson(KycJson.write(new LinkedHashMap<>()));
        fillTreeDetails(app, child);
        repo.save(app);
        return app.getId();
    }

    /** Called after suspend/lift for every org in the subtree (kept from V7). */
    /** Legacy bridge (USE_KYC_WORKFLOW=false): onboarding verdict still drives the old flow.
     *  approve=false terminates the application; approve=true hands the open application
     *  to the engine (ops-review task via contributor plan is created by startFlow). */
    @Transactional
    public void onOnboardingDecision(String orgId, boolean approve, Current.Principal reviewer, String note) {
        KycApplication app = repo.findByOrgIdAndStatusIn(orgId, OPEN).stream().findFirst().orElse(null);
        if (app == null) return;
        if (!approve) {
            goLive.terminate(app, Enums.OrgStatus.REJECTED,
                    note == null ? "onboarding-rejected" : note,
                    reviewer == null ? null : reviewer.userId());
            return;
        }
        if (app.getStatus() == Status.DRAFT) {
            engine.startFlow(app, reviewer, true, Map.of());
        }
    }

    public void syncSuspension(String orgId) {
        if (orgId == null) return;
        List<String> ids = closureRepo.findSubtree(orgId).stream().map(r -> r.getOrg().getId()).toList();
        if (ids.isEmpty()) ids = List.of(orgId);
        for (KycApplication app : repo.findByOrgIdIn(ids)) {
            boolean held = held(app.getOrgId());
            if (app.isSuspended() != held) {
                app.setSuspended(held);
                repo.save(app);
            }
        }
    }

    // ------------------------------------------------------------------ engine helpers

    public void save(KycApplication app) {
        repo.save(app);
    }

    public String orgTypeOf(KycApplication app) {
        return app.getOrgType() == null ? "MERCHANT" : app.getOrgType().name();
    }

    public String parentOfOrg(String orgId) {
        return orgRepo.findById(orgId).map(Organization::getParentId).orElse(null);
    }

    // removed: go-live provisioning lives in OrgGoLiveBridge (single implementation)

    public boolean held(String orgId) {
        Set<String> ids = new HashSet<>();
        ids.add(orgId);
        closureRepo.findAncestors(orgId).forEach(r -> ids.add(r.getOrg().getId()));
        return !suspensionRepo.findByOrgIdInAndLiftedAtIsNull(new java.util.ArrayList<>(ids)).isEmpty();
    }

    // ------------------------------------------------------------------ internals

    private KycApplication openApplication(String orgId) {
        return repo.findFirstByOrgIdAndStatusInOrderByCreatedAtDesc(orgId, OPEN)
                .orElseGet(() -> repo.findFirstByOrgIdOrderByCreatedAtDesc(orgId).orElse(null));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> docOf(Map<String, Object> body) {
        return body == null ? new LinkedHashMap<>() : body;
    }

    private static Object value(Map<String, Object> doc, String section, String key) {
        if (doc == null) return null;
        Object body = doc.get(section);
        return body instanceof Map<?, ?> m ? m.get(key) : null;
    }

    private KycMasterService.ProfileBundle resolveBundle(Organization org, Object entityType) {
        return master.resolveByOrg(org.getOrgType().name(),
                org.getCountry() == null ? "NA" : org.getCountry(),
                org.getLicenceType() == null ? "PSP" : org.getLicenceType(),
                entityType == null ? null : entityType.toString());
    }

    /** Re-resolves the profile's CURRENT schema version for a DRAFT (§12 upgrade). */
    @Transactional
    public Dtos.KycApplicationView upgrade(Current.Principal caller, String orgId) {
        Organization org = org(orgId);
        Relation rel = relation(caller, org);
        assertEditor(caller, rel);
        KycApplication app = repo.findByOrgIdAndStatusIn(org.getId(), OPEN).stream()
                .filter(a -> a.getStatus() == Status.DRAFT)
                .findFirst()
                .orElseThrow(() -> new ConflictException("KYC_NOT_DRAFT",
                        "Only a DRAFT application can be upgraded"));
        KycMasterService.ProfileBundle current = resolveBundle(org, app.getEntityType());
        if (current.schema().getId().equals(app.getSchemaVersionId())) {
            return toView(caller, org, app, rel);
        }
        validateAgainstNewSchema(app, current, org);
        applyProfile(app, current, app.getEntityType());
        app.setNeedsUpgrade(false);
        repo.save(app);
        auditService.record(caller.userId(), caller.role(), "kyc_schema_upgraded", "organization", org.getId(),
                null, current.schema().getCode() + " v" + current.schema().getVersion(), null, false, null);
        return toView(caller, org, app, rel);
    }

    /** An upgraded draft must still be shape-valid for the new schema. */
    private void validateAgainstNewSchema(KycApplication app, KycMasterService.ProfileBundle current,
                                          Organization org) {
        Map<String, Object> doc = protector.decryptSecrets(
                KycJson.parseMap(app.getApplicationJson()), bundleOf(app));
        KycDocValidator.Result result = docValidator.validateSave(doc, current, contextOf(org),
                uniqueChecker(app.getId(), org.getId()), app.getId(), org.getId(),
                app.getEntityType(), app.getOrgType() == null ? null : app.getOrgType().name());
        List<KycValidationContext.Problem> errors = result.problems().stream()
                .filter(KycValidationContext.Problem::isError).toList();
        if (!errors.isEmpty()) {
            throw new BadRequestException("KYC_INVALID", messages(errors));
        }
        app.setApplicationJson(KycJson.write(
                protector.encryptSecrets(new LinkedHashMap<>(result.doc()), current, app.getId())));
    }

    private KycMasterService.ProfileBundle bundleOf(KycApplication app) {
        if (app.getSchemaVersionId() != null) {
            return master.bySchemaVersion(app.getSchemaVersionId());
        }
        return master.resolveByOrg(app.getOrgType() == null ? "MERCHANT" : app.getOrgType().name(),
                app.getCountry() == null ? "NA" : app.getCountry(),
                app.getLicenceType() == null ? "PSP" : app.getLicenceType(),
                app.getEntityType());
    }

    private void applyProfile(KycApplication app, KycMasterService.ProfileBundle bundle, Object entityType) {
        app.setCountry(bundle.profile().getCountry());
        app.setLicenceType(bundle.profile().getLicenceType());
        app.setSubjectType(bundle.profile().getSubjectType());
        if (entityType != null) app.setEntityType(entityType.toString());
        app.setProfileId(bundle.profile().getId());
        app.setSchemaVersionId(bundle.schema().getId());
    }

    private Map<String, Object> contextOf(Organization org) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("orgType", org == null || org.getOrgType() == null ? "" : org.getOrgType().name());
        context.put("country", org == null || org.getCountry() == null ? "NA" : org.getCountry());
        context.put("licenceType", org == null || org.getLicenceType() == null ? "PSP" : org.getLicenceType());
        return context;
    }

    /**
     * UNIQUE_IN_PLATFORM (§3.3): registrations against organizations.registration_number
     * (normalised, ACTIVE orgs) and TAX/owner identities against the blind index.
     */
    private KycRuleValidators.KycUniqueChecker uniqueChecker(String applicationId, String orgId) {
        return (canonicalTag, normalizedValue, currentAppId, currentOrgId) -> {
            if ("REG_NUMBER".equals(canonicalTag) || "REGISTRATION_NUMBER".equals(canonicalTag)) {
                return orgRepo.existsByNormalizedRegistrationNumber(normalizedValue,
                        currentOrgId, Enums.OrgStatus.ACTIVE);
            }
            if ("TAX_ID".equals(canonicalTag) || "UBO_ID_NUMBER".equals(canonicalTag)) {
                String mac = protector.hmac(canonicalTag, normalizedValue);
                return currentAppId != null
                        && blindIndexRepo.existsByCanonicalTagAndHmacAndApplicationIdNot(
                        canonicalTag, mac, currentAppId);
            }
            return false;
        };
    }

    private void fillTreeDetails(KycApplication app, Organization org) {
        app.setOrgType(org.getOrgType());
        app.setOrgName(org.getName());
        app.setParentOrgId(org.getParentId());
        app.setCountry(org.getCountry() == null ? "NA" : org.getCountry());
        List<OrgSubtreeRow> anc = closureRepo.findAncestors(org.getId());   // root first, self included
        if (anc.isEmpty()) {
            app.setTreePath(org.getId());
            app.setDepth(0);
        } else {
            app.setTreePath(anc.stream().map(r -> r.getOrg().getId()).collect(Collectors.joining("/")));
            app.setDepth(anc.stream().mapToInt(OrgSubtreeRow::getDepth).max().orElse(0));
        }
        app.setSuspended(held(org.getId()));
    }

    private Organization org(String orgId) {
        return orgRepo.findById(orgId).orElseThrow(() -> new NotFoundException("Organization not found"));
    }

    private Relation relation(Current.Principal caller, Organization target) {
        if (callerService.platformStaff(caller)) return Relation.PLATFORM;
        Organization own = callerService.orgOf(caller);
        if (own == null) throw new NotFoundException("Organization not found");
        if (own.getId().equals(target.getId())) return Relation.SELF;
        if (own.getId().equals(target.getParentId())) return Relation.PARENT;
        if (closureRepo.existsByAncestorIdAndDescendantId(own.getId(), target.getId())) return Relation.ANCESTOR;
        throw new NotFoundException("Organization not found");
    }

    private void assertEditor(Current.Principal caller, Relation rel) {
        if (rel == Relation.PLATFORM) return;
        if ((rel == Relation.SELF || rel == Relation.PARENT) && caller.isOrgAdmin()) return;
        throw new ForbiddenException("KYC_EDIT_AUTHORITY",
                "Only the organization's ADMIN, its parent organization's ADMIN or platform staff edit KYC");
    }

    private boolean isEditorRelation(Current.Principal caller, Relation rel) {
        return rel == Relation.PLATFORM
                || ((rel == Relation.SELF || rel == Relation.PARENT) && caller.isOrgAdmin());
    }

    private Dtos.KycApplicationView toView(Current.Principal caller, Organization org, KycApplication app, Relation rel) {
        List<Dtos.OrgPathRow> path = closureRepo.findAncestors(org.getId()).stream()
                .map(r -> new Dtos.OrgPathRow(r.getOrg().getId(), r.getOrg().getOrgType().name(),
                        r.getOrg().getName(), r.getDepth()))
                .toList();
        boolean editorRel = isEditorRelation(caller, rel);
        boolean masked = rel == Relation.ANCESTOR;
        if (app == null) {
            int depth = path.stream().mapToInt(Dtos.OrgPathRow::depth).max().orElse(0);
            return new Dtos.KycApplicationView(null, org.getId(), org.getOrgType().name(), org.getName(),
                    org.getParentId(), path, depth, appHeld(org.getId()), "NOT_STARTED",
                    null, null, null, null, null, null, null, 0, masked,
                    editorRel, false, false, null, null);
        }
        com.fasterxml.jackson.databind.JsonNode doc = KycJson.readTree(app.getApplicationJson());
        com.fasterxml.jackson.databind.JsonNode submitted =
                "DRAFT".equals(app.getStatus().name()) ? null : doc;
        if (masked) {
            KycMasterService.ProfileBundle bundle = bundleOf(app);
            if (bundle != null) {
                Map<String, Object> maskedDoc = protector.maskForAncestor(
                        new LinkedHashMap<>(protector.decryptSecrets(
                                KycJson.parseMap(app.getApplicationJson()), bundle)), bundle);
                doc = KycJson.readTree(maskedDoc);
                submitted = doc;
            }
        } else {
            KycMasterService.ProfileBundle bundle = bundleOf(app);
            if (app.getApplicationJson() != null) {
                Map<String, Object> plainDoc = protector.decryptSecrets(
                        KycJson.parseMap(app.getApplicationJson()), bundle);
                doc = KycJson.readTree(plainDoc);
                submitted = "DRAFT".equals(app.getStatus().name()) ? null : doc;
            }
        }
        boolean editable = editorRel && app.getStatus() == Status.DRAFT;
        return new Dtos.KycApplicationView(app.getId(), org.getId(), org.getOrgType().name(), org.getName(),
                app.getParentOrgId(), path, app.getDepth(), appHeld(org.getId()), app.getStatus().name(),
                doc, submitted,
                app.getSubmittedBy(), app.getSubmittedAt(), app.getDecidedBy(), app.getDecidedAt(),
                app.getDecisionNote(), app.getVersion(), masked, editable,
                editable && app.getStatus() == Status.DRAFT, false, app.getCreatedAt(), app.getUpdatedAt());
    }

    private boolean appHeld(String orgId) {
        Set<String> ids = new HashSet<>();
        ids.add(orgId);
        closureRepo.findAncestors(orgId).forEach(r -> ids.add(r.getOrg().getId()));
        return !suspensionRepo.findByOrgIdInAndLiftedAtIsNull(new java.util.ArrayList<>(ids)).isEmpty();
    }

    private String messages(List<KycValidationContext.Problem> problems) {
        return problems.stream()
                .map(p -> p.fieldPath() == null || p.fieldPath().isEmpty()
                        ? p.message() : p.fieldPath() + ": " + p.message())
                .collect(Collectors.joining("; "));
    }

    private static Status parseStatus(String s) {
        try {
            return Status.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("KYC_STATUS_INVALID",
                    "status must be one of DRAFT, ENTRY_CHECK, VERIFYING, IN_REVIEW, APPROVED, REJECTED, BLOCKED, WITHDRAWN");
        }
    }
}
