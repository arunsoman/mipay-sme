package com.mipay.sme.service.kyc;

import com.mipay.sme.common.BadRequestException;
import com.mipay.sme.common.ConflictException;
import com.mipay.sme.common.ForbiddenException;
import com.mipay.sme.common.NotFoundException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.domain.kyc.KycApplication;
import com.mipay.sme.domain.kyc.KycSubmission;
import com.mipay.sme.domain.kyc.KycTask;
import com.mipay.sme.domain.kyc.KycVerification;
import com.mipay.sme.domain.kyc.UserPlatformFunction;
import com.mipay.sme.repository.KycApplicationRepository;
import com.mipay.sme.repository.KycSubmissionRepository;
import com.mipay.sme.repository.KycTaskRepository;
import com.mipay.sme.repository.KycVerificationRepository;
import com.mipay.sme.repository.UserPlatformFunctionRepository;
import com.mipay.sme.repository.UserRepository;
import com.mipay.sme.security.Current;
import com.mipay.sme.service.AuditService;
import com.mipay.sme.service.OrgCallerService;
import com.mipay.sme.service.OrgSuspensionService;
import com.mipay.sme.service.OrgUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The workflow engine (§6.2-6.4): submission plans (via the configurable
 * plugin + contributors), the entry maker-checker, verification runs, stage
 * approvals and the invariants no plugin can override. The final APPROVE
 * triggers the single gate (KycGoLiveService).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KycWorkflowEngine {

    private final KycPluginRegistry plugins;
    private final KycMasterService master;
    private final KycTaskRepository tasks;
    private final KycSubmissionRepository submissions;
    private final KycVerificationRepository verifications;
    private final KycApplicationRepository apps;
    private final UserPlatformFunctionRepository functions;
    private final UserRepository users;
    private final OrgCallerService callerService;
    private final OrgUserService orgUsers;
    private final OrgSuspensionService suspensions;
    private final KycGoLiveService goLive;
    private final AuditService auditService;
    private final com.mipay.sme.repository.OrgClosureRepository closureRepo;
    private final com.mipay.sme.repository.OrganizationRepository orgs;

    // ---------------------------------------------------------------- submit

    /**
     * Runs the plan at submit time (§6.2): entry check, verification, stage 1.
     * Called from KycApplicationService with the frozen document already stored.
     */
    @Transactional
    public KycApplication startFlow(KycApplication app, Current.Principal maker, boolean sendToChecker,
                                    Map<String, Object> doc) {
        // suspended orgs freeze before anything moves
        if (suspensions.isHeld(app.getOrgId())) {
            throw new ConflictException("ORG_SUSPENDED", "The organization is suspended");
        }
        KycMasterService.ProfileBundle bundle = master.bySchemaVersion(app.getSchemaVersionId());
        com.mipay.sme.service.kyc.KycPluginContracts.KycWorkflow.WorkflowContext ctx =
                workflowContext(app, bundle, doc, Map.of(), List.of());

        com.mipay.sme.service.kyc.KycPluginContracts.WorkflowPlan plan = planOrFallback(app, bundle, ctx);

        app.setWorkflowKey(bundle.profile().getWorkflowKey());
        app.setWorkflowVersion(bundle.profile().getWorkflowVersion());
        app.setWorkflowPlan(KycJson.write(plan));
        app.setSubmissionNo(coalesceSubmissionNo(app));
        // invariants 1/2 are checked against the application row: the maker must
        // be set here, never only on the frozen submission copy
        app.setSubmittedBy(maker.userId());
        apps.save(app);

        // freeze the submitted document per submission (§9)
        KycSubmission submission = submissions.findByApplicationIdAndSubmissionNo(app.getId(), app.getSubmissionNo())
                .orElseGet(() -> {
                    KycSubmission s = new KycSubmission();
                    s.setApplicationId(app.getId());
                    s.setSubmissionNo(app.getSubmissionNo());
                    return s;
                });
        submission.setDoc(KycJson.write(doc));
        submission.setSubmittedBy(maker.userId());
        submission.setSubmittedAt(Instant.now());
        submissions.save(submission);

        // entry maker-checker (§6.2)
        boolean entryCheck = plan.entryCheck() == com.mipay.sme.service.kyc.KycPluginContracts.EntryCheck.REQUIRED
                || (plan.entryCheck() == com.mipay.sme.service.kyc.KycPluginContracts.EntryCheck.OPTIONAL
                    && sendToChecker);
        if (entryCheck) {
            String makerOrgId = orgIdOf(maker);
            if (makerOrgId == null || orgUsers.countActiveAdmins(makerOrgId) < 2) {
                throw new ConflictException("ENTRY_CHECKER_UNAVAILABLE",
                        "No other active ADMIN in the maker's organization can check the submission");
            }
            openEntryCheckTask(app, maker);
            app.setStatus(KycApplication.Status.ENTRY_CHECK);
            auditService.record(maker.userId(), maker.role(), "kyc_submitted_entry_check", "organization",
                    app.getOrgId(), "DRAFT", "ENTRY_CHECK", null, false, null);
            apps.save(app);
            return app;
        }

        return advanceToStages(app, maker, plan, doc);
    }

    /** Optional check skipped: verification + stage 1 immediately. */
    @Transactional
    public KycApplication advanceToStages(KycApplication app, Current.Principal actor,
                                          com.mipay.sme.service.kyc.KycPluginContracts.WorkflowPlan plan,
                                          Map<String, Object> doc) {
        // verification runs here (§7): one row per check, provider named by the profile
        boolean settled = runVerification(app, plan, doc);
        if (!settled) {
            // a check is still PENDING inside checkTimeoutHours: stay in VERIFYING
            // and resume later (no manual stage yet, §6.2 timeouts)
            app.setStatus(KycApplication.Status.VERIFYING);
            app.setCurrentStage(null);
            apps.save(app);
            return app;
        }

        KycMasterService.ProfileBundle bundle = master.bySchemaVersion(app.getSchemaVersionId());
        // risk is scored BEFORE contributors so the plan can gain the extra stages (§5.3)
        scoreRisk(app, bundle, doc);

        com.mipay.sme.service.kyc.KycPluginContracts.KycWorkflow.WorkflowContext ctx =
                workflowContext(app, bundle, doc, resultsOf(app), actedByOf(app));

        // plans only grow: contributors re-run with verification results (HIT may add stages)
        if (!applyContributors(app, plan, ctx)) {
            return app;   // WORKFLOW_ERROR fallback path: stays put, ops-review task open (§6.3.7)
        }

        if (!plan.approvalRequired()) {
            // "No approval" can only ever auto-approve clean cases (§6.2)
            if (resultsOf(app).values().stream().allMatch("CLEAR"::equals)) {
                String went = goLive.goLive(app, actor.userId());
                app.setStatus(KycApplication.Status.APPROVED);
                app.setCurrentStage(null);
                apps.save(app);
                return app;
            }
            openFallbackOpsReviewTask(app, "checks not all clear");
            app.setStatus(KycApplication.Status.IN_REVIEW);
            app.setCurrentStage("ops-review");
            apps.save(app);
            return app;
        }

        openNextStageTask(app, 0);
        KycTask first = tasks.findByApplicationIdAndSubmissionNoAndKindAndStatus(
                app.getId(), app.getSubmissionNo(), KycTask.Kind.STAGE, KycTask.Status.OPEN)
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("No stage task opened"));
        app.setStatus(KycApplication.Status.IN_REVIEW);
        app.setCurrentStage(first.getStageKey());
        apps.save(app);
        return app;
    }

    /** A plugin threw: the app stays where it is and an ops-review WORKFLOW_ERROR task opens (§6.3.7). */
    private com.mipay.sme.service.kyc.KycPluginContracts.WorkflowPlan planOrFallback(
            KycApplication app, KycMasterService.ProfileBundle bundle,
            com.mipay.sme.service.kyc.KycPluginContracts.KycWorkflow.WorkflowContext ctx) {
        try {
            return plugins.workflow(bundle.profile().getWorkflowKey()).plan(ctx);
        } catch (Exception e) {
            log.warn("workflow plugin {} failed on application {}: {}",
                    bundle.profile().getWorkflowKey(), app.getId(), e.getMessage());
            // invariant 7: the application stays where it is; one WORKFLOW_ERROR
            // ops-review task opens and the flow does NOT advance
            openFallbackOpsReviewTask(app, "WORKFLOW_ERROR: " + e.getMessage());
            apps.save(app);
            com.mipay.sme.service.kyc.KycPluginContracts.WorkflowPlan fallback =
                    new com.mipay.sme.service.kyc.KycPluginContracts.WorkflowPlan(true,
                            com.mipay.sme.service.kyc.KycPluginContracts.EntryCheck.NONE,
                            List.of(),
                            List.of(new com.mipay.sme.service.kyc.KycPluginContracts.StageDef("ops-review", 1,
                                    new com.mipay.sme.service.kyc.KycPluginContracts.ActorRule(
                                            com.mipay.sme.service.kyc.KycPluginContracts.Relation.PLATFORM,
                                            null, "OPS_REVIEWER"),
                                    List.of("APPROVE", "RETURN", "REJECT", "BLOCK"),
                                    com.mipay.sme.service.kyc.KycPluginContracts.OnReturn.DRAFT)));
            app.setWorkflowPlan(KycJson.write(fallback));
            apps.save(app);
            return fallback;
        }
    }

    // ---------------------------------------------------------------- decisions

    /** Approve / RETURN / REJECT / BLOCK on an open task (§6.4), limited to the stage's outcomes. */
    @Transactional
    public KycApplication decide(String taskId, Current.Principal actor, String outcome, String note) {
        KycTask task = tasks.findById(taskId)
                .orElseThrow(() -> new NotFoundException("Task not found"));
        if (task.getStatus() != KycTask.Status.OPEN) {
            throw new ConflictException("TASK_NOT_OPEN", "This task was already actioned");
        }
        KycApplication app = apps.findById(task.getApplicationId())
                .orElseThrow(() -> new NotFoundException("KYC application not found"));

        if (suspensions.isHeld(app.getOrgId())) {
            throw new ConflictException("ORG_SUSPENDED", "The organization is suspended; workflow frozen");
        }

        resolveActor(actor, task, app);
        assertNoDoubleAct(task, actor);

        String oc = outcome == null ? "" : outcome.toUpperCase();
        List<String> allowed = allowedOutcomes(task);
        if (!allowed.contains(oc)) {
            throw new BadRequestException("OUTCOME_NOT_ALLOWED",
                    "Outcome '" + outcome + "' is not allowed on stage " + task.getStageKey()
                            + "; allowed: " + String.join(", ", allowed));
        }

        KycTask.Outcome real = switch (oc) {
            case "CONFIRM" -> KycTask.Outcome.CONFIRM;
            case "SEND_BACK" -> KycTask.Outcome.SEND_BACK;
            case "APPROVE" -> KycTask.Outcome.APPROVE;
            case "RETURN" -> KycTask.Outcome.RETURN;
            case "REJECT" -> KycTask.Outcome.REJECT;
            case "BLOCK" -> KycTask.Outcome.BLOCK;
            default -> throw new BadRequestException("OUTCOME_NOT_ALLOWED", "Unknown outcome " + outcome);
        };

        task.setStatus(KycTask.Status.DONE);
        task.setOutcome(real.name());
        task.setActedBy(actor.userId());
        task.setActedAt(Instant.now());
        task.setNote(note);
        tasks.save(task);

        auditService.record(actor.userId(), actor.role(), "kyc_task_" + real.name().toLowerCase(),
                "organization", app.getOrgId(), null, null,
                "task=" + task.getStageKey() + (note == null ? "" : " note=" + note), false, null);
        apps.save(app);

        switch (task.getKind()) {
            case ENTRY_CHECK -> handleEntryCheckOutcome(app, task, real, actor, note);
            case STAGE -> handleStageOutcome(app, task, real, actor, note);
        }
        return app;
    }

    private void handleEntryCheckOutcome(KycApplication app, KycTask task, KycTask.Outcome outcome,
                                         Current.Principal actor, String note) {
        if (outcome == KycTask.Outcome.CONFIRM) {
            Map<String, Object> doc = frozenDoc(app);
            com.mipay.sme.service.kyc.KycPluginContracts.WorkflowPlan plan =
                    readPlan(app);
            app.setStatus(KycApplication.Status.VERIFYING);
            app.setCurrentStage(null);
            apps.save(app);
            advanceToStages(app, actor, plan, doc);
        } else { // SEND_BACK
            cancelOpenTasks(app);
            app.setStatus(KycApplication.Status.DRAFT);
            app.setCurrentStage(null);
            apps.save(app);
        }
    }

    private void handleStageOutcome(KycApplication app, KycTask task, KycTask.Outcome outcome,
                                    Current.Principal actor, String note) {
        switch (outcome) {
            case APPROVE -> {
                // contributors re-run after each approval (a HIT may add stages, plans only grow)
                KycMasterService.ProfileBundle bundle = master.bySchemaVersion(app.getSchemaVersionId());
                com.mipay.sme.service.kyc.KycPluginContracts.KycWorkflow.WorkflowContext ctx =
                        workflowContext(app, bundle, frozenDoc(app), resultsOf(app), actedByOf(app));
                com.mipay.sme.service.kyc.KycPluginContracts.WorkflowPlan plan = readPlan(app);
                if (!applyContributors(app, plan, ctx)) {
                    return;   // WORKFLOW_ERROR: frozen with the ops-review task (§6.3.7)
                }

                // only after the plan grows do we look for the next stage
                boolean last = openNextStageTask(app, task.getStageSeq());
                if (last) {
                    // risk rating + review cycle before the gate (§6.5); scored at
                    // submission too, refreshed here for the record
                    scoreRisk(app, bundle, frozenDoc(app));
                    com.mipay.sme.service.kyc.KycPluginContracts.KycRiskScorer scorer =
                            plugins.riskScorer(bundle.profile().getRiskScorer());
                    var score = scorer.score(workflowContext(app, bundle, frozenDoc(app),
                            resultsOf(app), actedByOf(app)));
                    app.setNextReviewDate(score.nextReviewAt());

                    goLive.goLive(app, actor.userId());
                    app.setStatus(KycApplication.Status.APPROVED);
                    app.setCurrentStage(null);
                    app.setDecidedBy(actor.userId());
                    app.setDecidedAt(Instant.now());
                    app.setDecisionNote(note);
                    apps.save(app);
                } else {
                    app.setStatus(KycApplication.Status.IN_REVIEW);
                    app.setCurrentStage(openStageKey(app));
                    apps.save(app);
                }
            }
            case RETURN -> {
                // §6.4: onReturn PREVIOUS_STAGE sends it back to the prior stage
                // task (reopened); otherwise it goes to DRAFT for editing.
                boolean toPrevious = previousStageOnReturn(app, task);
                cancelOpenTasks(app);
                if (toPrevious) {
                    app.setStatus(KycApplication.Status.IN_REVIEW);
                    app.setCurrentStage(previousStageKey(app, task.getStageSeq()));
                } else {
                    app.setStatus(KycApplication.Status.DRAFT);
                    app.setCurrentStage(null);
                }
                apps.save(app);
            }
            case REJECT -> {
                app.setStatus(KycApplication.Status.REJECTED);
                app.setCurrentStage(null);
                app.setDecidedBy(actor.userId());
                app.setDecidedAt(Instant.now());
                app.setDecisionNote(note);
                // §17: rejected applications are kept 5 years from the decision
                app.setRetainUntil(Instant.now().plusSeconds(5L * 365 * 24 * 3600));
                apps.save(app);
                goLive.terminate(app, Enums.OrgStatus.REJECTED, "KYC rejected", actor.userId());
            }
            case BLOCK -> {
                app.setStatus(KycApplication.Status.BLOCKED);
                app.setCurrentStage(null);
                app.setDecidedBy(actor.userId());
                app.setDecidedAt(Instant.now());
                app.setDecisionNote(note);
                app.setRetainUntil(Instant.now().plusSeconds(5L * 365 * 24 * 3600));
                apps.save(app);
                goLive.terminate(app, Enums.OrgStatus.BLOCKED, "KYC blocked", actor.userId());
            }
            default -> throw new BadRequestException("OUTCOME_NOT_ALLOWED", "Not a stage outcome");
        }
    }

    // ---------------------------------------------------------------- inbox

    /** Every open task the caller is entitled to act on (§12). */
    @Transactional(readOnly = true)
    public List<KycTask> inbox(Current.Principal caller) {
        List<KycTask> open = tasks.findByStatusOrderByStageSeqAsc(KycTask.Status.OPEN).stream()
                .filter(t -> t.getApplicationId() != null)
                .toList();
        List<KycTask> out = new ArrayList<>();
        for (KycTask t : open) {
            try {
                KycApplication app = apps.findById(t.getApplicationId()).orElse(null);
                if (app == null) continue;
                resolveActor(caller, t, app);
                out.add(t);
            } catch (ForbiddenException | NotFoundException e) {
                // not this actor's task
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- verification

    private boolean runVerification(KycApplication app,
                                    com.mipay.sme.service.kyc.KycPluginContracts.WorkflowPlan plan,
                                    Map<String, Object> doc) {
        List<KycVerification> results = verifications.findByApplicationIdAndSubmissionNo(app.getId(), app.getSubmissionNo());
        if (!results.isEmpty()) {
            return results.stream().allMatch(v -> v.getStatus() == KycVerification.Status.CLEAR);
        }
        KycMasterService.ProfileBundle bundle = master.bySchemaVersion(app.getSchemaVersionId());
        KycFacts facts = factsOf(app, bundle, doc);
        boolean allClear = true;
        for (String checkType : plan.verification()) {
            String providerKey = String.valueOf(asMap(KycJson.parseMap(bundle.profile().getCheckProviders()))
                    .getOrDefault(checkType, "simulated"));
            KycPluginContracts.KycCheckProvider provider = plugins.checkProvider(providerKey);
            KycPluginContracts.KycCheckProvider.CheckRequest req =
                    new KycPluginContracts.KycCheckProvider.CheckRequest(
                            app.getId(), app.getSubmissionNo(), "org", checkType,
                            app.getCountry() == null ? "NA" : app.getCountry(), facts.byTag());
            KycPluginContracts.KycCheckProvider.CheckResult result;
            try {
                result = provider.request(req);
            } catch (RuntimeException e) {
                log.warn("check provider {} failed on application {}: {}", providerKey, app.getId(), e.getMessage());
                result = new KycPluginContracts.KycCheckProvider.CheckResult(
                        KycPluginContracts.KycCheckProvider.Status.ERROR,
                        "provider failure: " + e.getMessage());
            }
            KycVerification row = verifications
                    .findByApplicationIdAndSubmissionNoAndCheckTypeAndSubject(app.getId(), app.getSubmissionNo(),
                            checkType, "org")
                    .orElseGet(() -> {
                        KycVerification v = new KycVerification();
                        v.setApplicationId(app.getId());
                        v.setSubmissionNo(app.getSubmissionNo());
                        v.setCheckType(checkType);
                        v.setSubject("org");
                        v.setProvider(providerKey);
                        v.setRequestedAt(Instant.now());
                        v.setRequestedBy(app.getSubmittedBy());
                        return v;
                    });
            row.setStatus(result == null ? KycVerification.Status.ERROR : map(result.status()));
            row.setDetail(result == null ? null : result.detail());
            if (row.getStatus() != KycVerification.Status.CLEAR) allClear = false;
            row.setCompletedAt(Instant.now());
            verifications.save(row);
        }
        return allClear;
    }

    private KycVerification.Status map(KycPluginContracts.KycCheckProvider.Status status) {
        return switch (status) {
            case PENDING -> KycVerification.Status.PENDING;
            case CLEAR -> KycVerification.Status.CLEAR;
            case HIT -> KycVerification.Status.HIT;
            case ERROR -> KycVerification.Status.ERROR;
        };
    }

    // ---------------------------------------------------------------- helpers

    private void openEntryCheckTask(KycApplication app, Current.Principal maker) {
        KycTask task = new KycTask();
        task.setApplicationId(app.getId());
        task.setSubmissionNo(app.getSubmissionNo());
        task.setKind(KycTask.Kind.ENTRY_CHECK);
        task.setStageKey("entry-check");
        task.setStageSeq(0);
        // the checker must be an ADMIN of the MAKER's org, not the maker (§6.2)
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("relation", "SELF_ORG");
        rule.put("orgRole", "ADMIN");
        rule.put("orgId", orgIdOf(maker));
        task.setActorRule(KycJson.write(rule));
        task.setStatus(KycTask.Status.OPEN);
        task.setCreatedAt(Instant.now());
        task.setUpdatedAt(Instant.now());
        tasks.save(task);
    }

    /** @return true when this was the last sequential stage (no further task opened). */
    private boolean openNextStageTask(KycApplication app, int afterSeq) {
        com.mipay.sme.service.kyc.KycPluginContracts.WorkflowPlan plan = readPlan(app);
        int nextSeq = Integer.MAX_VALUE;
        for (com.mipay.sme.service.kyc.KycPluginContracts.StageDef stage : plan.stages()) {
            if (stage.seq() > afterSeq && stage.seq() < nextSeq) nextSeq = stage.seq();
        }
        if (nextSeq == Integer.MAX_VALUE) return true;
        final int next = nextSeq;
        com.mipay.sme.service.kyc.KycPluginContracts.StageDef stage = plan.stages().stream()
                .filter(s -> s.seq() == next).findFirst().orElseThrow();
        boolean exists = tasks.findByApplicationIdAndSubmissionNoAndStatus(
                app.getId(), app.getSubmissionNo(), KycTask.Status.OPEN).stream()
                .anyMatch(t -> t.getStageKey().equals(stage.key()));
        if (!exists) {
            KycTask t = new KycTask();
            t.setApplicationId(app.getId());
            t.setSubmissionNo(app.getSubmissionNo());
            t.setKind(KycTask.Kind.STAGE);
            t.setStageKey(stage.key());
            t.setStageSeq(stage.seq());
            t.setActorRule(KycJson.write(stage.actor().toMap()));
            t.setStatus(KycTask.Status.OPEN);
            t.setCreatedAt(Instant.now());
            t.setUpdatedAt(Instant.now());
            tasks.save(t);
        }
        return false;
    }

    private void openFallbackOpsReviewTask(KycApplication app, String reason) {
        KycTask t = new KycTask();
        t.setApplicationId(app.getId());
        t.setSubmissionNo(app.getSubmissionNo());
        t.setKind(KycTask.Kind.STAGE);
        t.setStageKey("ops-review");
        t.setStageSeq(90);
        t.setActorRule(KycJson.write(Map.of("relation", "PLATFORM", "function", "OPS_REVIEWER")));
        t.setStatus(KycTask.Status.OPEN);
        t.setNote(reason);
        t.setCreatedAt(Instant.now());
        t.setUpdatedAt(Instant.now());
        tasks.save(t);
    }

    private void cancelOpenTasks(KycApplication app) {
        tasks.findByApplicationIdAndSubmissionNoAndStatus(app.getId(), app.getSubmissionNo(), KycTask.Status.OPEN)
                .forEach(t -> {
                    t.setStatus(KycTask.Status.CANCELLED);
                    t.setUpdatedAt(Instant.now());
                    tasks.save(t);
                });
    }

    /** Contributors add stages on top of what's already open (plans only grow, §6.3.6). @return false when a contributor failed (WORKFLOW_ERROR path). */
    private boolean applyContributors(KycApplication app,
                                      com.mipay.sme.service.kyc.KycPluginContracts.WorkflowPlan plan,
                                      com.mipay.sme.service.kyc.KycPluginContracts.KycWorkflow.WorkflowContext ctx) {
        KycMasterService.ProfileBundle bundle = master.bySchemaVersion(app.getSchemaVersionId());
        Map<String, Object> wfConfig = KycJson.parseMap(bundle.profile().getWorkflowConfig());
        Object contributors = wfConfig.get("contributors");
        if (!(contributors instanceof List<?> list)) return true;
        try {
            return applyContributorsInner(app, plan, ctx, list);
        } catch (WorkflowErrorException e) {
            app.setStatus(KycApplication.Status.IN_REVIEW);
            app.setCurrentStage("ops-review");
            apps.save(app);
            return false;
        }
    }

    private boolean applyContributorsInner(KycApplication app,
                                           com.mipay.sme.service.kyc.KycPluginContracts.WorkflowPlan plan,
                                           com.mipay.sme.service.kyc.KycPluginContracts.KycWorkflow.WorkflowContext ctx,
                                           List<?> list) {
        int maxSeq = plan.stages().stream().mapToInt(
                com.mipay.sme.service.kyc.KycPluginContracts.StageDef::seq).max().orElse(1);
        for (Object c : list) {
            for (com.mipay.sme.service.kyc.KycPluginContracts.StageDef extra : pluginsContributorStages(app, c, ctx, plan, null)) {
                final boolean[] added = {false};
                plan.stages().forEach(existing -> {
                    if (existing.key().equals(extra.key())) added[0] = true;
                });
                if (added[0]) continue;   // plans only grow
                maxSeq++;
                com.mipay.sme.service.kyc.KycPluginContracts.StageDef appended =
                        new com.mipay.sme.service.kyc.KycPluginContracts.StageDef(extra.key(), maxSeq,
                                extra.actor(), extra.outcomes(), extra.onReturn());
                plan.stages().add(appended);
            }
        }
        app.setWorkflowPlan(KycJson.write(plan));
        apps.save(app);
        return true;
    }

    /** invariant 7: a throwing contributor freezes the flow with an ops-review WORKFLOW_ERROR task. */
    private List<com.mipay.sme.service.kyc.KycPluginContracts.StageDef> pluginsContributorStages(
            KycApplication app, Object contributorKey,
            com.mipay.sme.service.kyc.KycPluginContracts.KycWorkflow.WorkflowContext ctx,
            com.mipay.sme.service.kyc.KycPluginContracts.WorkflowPlan plan,
            @SuppressWarnings("unused") KycMasterService.ProfileBundle bundle) {
        try {
            return plugins.contributor(String.valueOf(contributorKey)).extraStages(ctx);
        } catch (RuntimeException e) {
            log.warn("contributor {} failed on application {}: {}",
                    contributorKey, app.getId(), e.getMessage());
            openFallbackOpsReviewTask(app, "WORKFLOW_ERROR: " + e.getMessage() + " (contributor " + contributorKey + ")");
            auditService.record("system", "PLATFORM", "kyc_workflow_error", "organization", app.getOrgId(),
                    app.getStatus().name(), app.getStatus().name(),
                    "contributor=" + contributorKey + ";error=" + e.getMessage(), false, null);
            throw new WorkflowErrorException();
        }
    }

    /** internal control-flow marker for the contributor failure path. */
    private static class WorkflowErrorException extends RuntimeException { }

    // ---------------------------------------------------------------- actor resolution

    /** Invariant 3/5: the actor's org must be operational and the task's rule must name them. */
    private void resolveActor(Current.Principal actor, KycTask task, KycApplication app) {
        Map<String, Object> rule = KycJson.parseMap(task.getActorRule());
        String relation = String.valueOf(rule.getOrDefault("relation", "PLATFORM"));

        switch (relation) {
            case "PLATFORM" -> {
                if (!callerService.platformStaff(actor)) {
                    throw new ForbiddenException("PLATFORM_REQUIRED", "Platform staff only");
                }
                // invariant 1: the submitter never acts on their own application's checks (§12)
                if (app.getSubmittedBy() != null && actor.userId().equals(app.getSubmittedBy())) {
                    throw new ForbiddenException("MAKER_CANNOT_ACT", "The submitter cannot act on their own submission");
                }
                String function = text(rule.get("function"));
                if (function != null && !function.isBlank()
                        && !functions.existsByUserIdAndFunction(actor.userId(), function)) {
                    throw new ForbiddenException("NO_FUNCTION",
                            "User does not hold the platform function " + function
                                    + " (MINDLABS_ADMIN has no review function)");
                }
            }
            case "SELF_ORG" -> {
                String orgId = text(rule.get("orgId"));
                if (orgId == null || !orgId.equals(orgIdOf(actor))) {
                    throw new ForbiddenException("ACTOR_RULE", "Only users of the maker's organization may act");
                }
                if (!"ADMIN".equals(actor.orgRole())) {
                    throw new ForbiddenException("ACTOR_RULE", "Only an ADMIN of the maker's organization may act");
                }
                // the maker never acts on their own application's checks (invariant 1)
                if (app.getSubmittedBy() != null && actor.userId().equals(app.getSubmittedBy())) {
                    throw new ForbiddenException("MAKER_CANNOT_ACT", "The maker cannot act on their own submission");
                }
            }
            case "PARENT_ORG" -> {
                requireOrgRelation(actor, app, true);
            }
            case "ANCESTOR_ORG" -> {
                requireOrgRelation(actor, app, false);
            }
            default -> throw new ForbiddenException("ACTOR_RULE", "Unknown relation " + relation);
        }
    }

    private void requireOrgRelation(Current.Principal actor, KycApplication app, boolean parentOnly) {
        String callerOrgId = orgIdOf(actor);
        if (callerOrgId == null) throw new ForbiddenException("ACTOR_RULE", "Caller has no organization");
        if (callerOrgId.equals(app.getOrgId())) {
            throw new ForbiddenException("ACTOR_RULE", "The org itself may not act on this task");
        }
        boolean parent = callerOrgId.equals(parentOf(app.getOrgId()));
        boolean ancestor = !parent
                && closureRepo.existsByAncestorIdAndDescendantId(callerOrgId, app.getOrgId());
        if (parentOnly ? !parent : !(parent || ancestor)) {
            throw new ForbiddenException("ACTOR_RULE", "Caller's organization is not related");
        }
        assertOperationalOrg(callerOrgId);
    }

    private void assertOperationalOrg(String orgId) {
        if (suspensions.isHeld(orgId)) {
            throw new ForbiddenException("ACTOR_RULE", "The actor's organization is suspended");
        }
    }

    private void assertNoDoubleAct(KycTask task, Current.Principal actor) {
        // invariant 2: one user acts on at most one task per application+submission
        List<KycTask> all = tasks.findByApplicationIdAndSubmissionNoOrderByStageSeqAsc(
                task.getApplicationId(), task.getSubmissionNo());
        boolean sameUserActed = all.stream().anyMatch(t ->
                actor.userId().equals(t.getActedBy()) && !t.getId().equals(task.getId()));
        if (sameUserActed) {
            throw new ConflictException("DOUBLE_ACT",
                    "One user may act at most once per application submission");
        }
    }

    private List<String> allowedOutcomes(KycTask task) {
        if (task.getKind() == KycTask.Kind.ENTRY_CHECK) return List.of("CONFIRM", "SEND_BACK");
        // the stage's allowed outcomes live on the plan
        com.mipay.sme.service.kyc.KycPluginContracts.WorkflowPlan plan = readPlan(app(task));
        return plan.stages().stream().filter(s -> s.key().equals(task.getStageKey()))
                .findFirst().map(com.mipay.sme.service.kyc.KycPluginContracts.StageDef::outcomes)
                .orElseGet(() -> List.of("APPROVE", "RETURN", "REJECT", "BLOCK"));
    }

    private KycApplication app(KycTask task) {
        return apps.findById(task.getApplicationId()).orElseThrow(
                () -> new NotFoundException("KYC application not found"));
    }

    private com.mipay.sme.service.kyc.KycPluginContracts.WorkflowPlan readPlan(KycApplication app) {
        com.mipay.sme.service.kyc.KycPluginContracts.WorkflowPlan plan =
                KycJson.parsePlan(app.getWorkflowPlan());
        if (plan == null) {
            throw new ConflictException("WORKFLOW_PLAN_MISSING", "Application has no stored plan");
        }
        return plan;
    }

    private Map<String, Object> frozenDoc(KycApplication app) {
        KycSubmission submission = submissions.findByApplicationIdAndSubmissionNo(
                app.getId(), app.getSubmissionNo()).orElse(null);
        if (submission == null) {
            return KycJson.parseMap(app.getApplicationJson());
        }
        return KycJson.parseMap(submission.getDoc());
    }

    private Map<String, String> resultsOf(KycApplication app) {
        Map<String, String> out = new LinkedHashMap<>();
        verifications.findByApplicationIdAndSubmissionNo(app.getId(), app.getSubmissionNo())
                .forEach(v -> out.put(v.getCheckType(), v.getStatus().name()));
        return out;
    }

    private List<String> actedByOf(KycApplication app) {
        return tasks.findByApplicationIdAndSubmissionNo(app.getId(), app.getSubmissionNo()).stream()
                .filter(t -> t.getActedBy() != null)
                .map(KycTask::getActedBy)
                .toList();
    }

    private com.mipay.sme.service.kyc.KycPluginContracts.KycWorkflow.WorkflowContext workflowContext(
            KycApplication app, KycMasterService.ProfileBundle bundle, Map<String, Object> doc,
            Map<String, String> checkResults, List<String> actedBy) {
        KycFacts facts = factsOf(app, bundle, doc);
        String orgType = orgTypeOf(app);
        return new com.mipay.sme.service.kyc.KycPluginContracts.KycWorkflow.WorkflowContext(
                app.getId(), app.getOrgId(), orgType, app.getEntityType(),
                app.getCountry() == null ? "NA" : app.getCountry(),
                app.getLicenceType() == null ? "PSP" : app.getLicenceType(),
                app.getSchemaVersionId(), app.getSubmissionNo(),
                facts, checkResults, actedBy, app.getRiskRating());
    }

    private KycFacts factsOf(KycApplication app, KycMasterService.ProfileBundle bundle, Map<String, Object> doc) {
        KycValidationContext ctx = new KycValidationContext(bundle, null, doc,
                KycJson.parseMap("{\"orgType\":\"" + orgTypeOf(app) + "\"}"));
        Map<String, Object> byTag = new LinkedHashMap<>();
        for (Map.Entry<String, com.mipay.sme.domain.kyc.KycField> e : bundle.allFieldsBySectionKey().entrySet()) {
            com.mipay.sme.domain.kyc.KycField field = e.getValue();
            if (field.getCanonicalTag() == null) continue;
            if (field.getSensitivity() == com.mipay.sme.domain.kyc.KycField.Sensitivity.SECRET) continue;
            String[] parts = e.getKey().split("\\.", 2);
            if (parts.length < 2) continue;
            Object body = doc.get(parts[0]);
            Object value = body instanceof Map<?, ?> m ? ((Map<?, ?>) m).get(parts[1]) : null;
            if (value == null && body instanceof List<?> l) {
                for (Object item : l) {
                    if (item instanceof Map<?, ?> m) {
                        Object v = ((Map<?, ?>) m).get(parts[1]);
                        if (v != null) {
                            value = v;
                            break;
                        }
                    }
                }
            }
            if (value != null) byTag.put(field.getCanonicalTag(), value);
        }
        return new KycFacts(byTag, doc, app.getEntityType(), orgTypeOf(app),
                app.getCountry(), app.getLicenceType(), app.getId(), app.getOrgId());
    }

    /** Risk is scored before contributors so plugins can react (§5.3); rating stored on the app. */
    private void scoreRisk(KycApplication app, KycMasterService.ProfileBundle bundle, Map<String, Object> doc) {
        try {
            com.mipay.sme.service.kyc.KycPluginContracts.KycRiskScorer scorer =
                    plugins.riskScorer(bundle.profile().getRiskScorer());
            var score = scorer.score(workflowContext(app, bundle, doc, resultsOf(app), actedByOf(app)));
            if (score.rating() != null) {
                app.setRiskRating(score.rating());
                apps.save(app);
            }
        } catch (Exception e) {
            log.warn("risk scoring failed on application {}: {}", app.getId(), e.getMessage());
        }
    }

    private Map<String, Object> asMap(Map<String, Object> source) {
        return source == null ? Map.of() : source;
    }



    /** True when the just-finished stage's onReturn rule is PREVIOUS_STAGE (§6.4). */
    private boolean previousStageOnReturn(KycApplication app, KycTask task) {
        com.mipay.sme.service.kyc.KycPluginContracts.WorkflowPlan plan = readPlan(app);
        return plan.stages().stream()
                .filter(s -> s.key().equals(task.getStageKey()))
                .findFirst()
                .map(s -> s.onReturn() == com.mipay.sme.service.kyc.KycPluginContracts.OnReturn.PREVIOUS_STAGE)
                .orElse(false);
    }

    /** The stage key immediately before `afterSeq`, or null when none (-> DRAFT). */
    private String previousStageKey(KycApplication app, int afterSeq) {
        com.mipay.sme.service.kyc.KycPluginContracts.WorkflowPlan plan = readPlan(app);
        List<Integer> seqs = plan.stages().stream()
                .map(com.mipay.sme.service.kyc.KycPluginContracts.StageDef::seq)
                .filter(seq -> seq < afterSeq)
                .toList();
        if (seqs.isEmpty()) return null;
        int prev = seqs.stream().max(Integer::compare).orElse(0);
        return plan.stages().stream()
                .filter(s -> s.seq() == prev)
                .findFirst()
                .map(com.mipay.sme.service.kyc.KycPluginContracts.StageDef::key)
                .orElse(null);
    }
    /**
     * PENDING checks inside checkTimeoutHours simply wait (§6.2/§7); on resume
     * the pending checks are requested again and the flow advances when every
     * row settles. Called from the view path while the status is VERIFYING.
     */
    @Transactional
    public void resumePending(KycApplication app) {
        if (app.getStatus() != KycApplication.Status.VERIFYING) return;
        List<KycVerification> results = verifications
                .findByApplicationIdAndSubmissionNo(app.getId(), app.getSubmissionNo());
        boolean anyPending = results.stream().anyMatch(v -> v.getStatus() == KycVerification.Status.PENDING);
        if (anyPending) {
            KycMasterService.ProfileBundle bundle = master.bySchemaVersion(app.getSchemaVersionId());
            long timeoutHours = KycJson.parseMap(bundle.profile().getWorkflowConfig())
                    .get("checkTimeoutHours") instanceof Number n ? n.intValue() : 24;
            boolean expired = results.stream()
                    .filter(v -> v.getStatus() == KycVerification.Status.PENDING)
                    .anyMatch(v -> v.getRequestedAt() != null
                            && v.getRequestedAt().isBefore(Instant.now().minusSeconds(timeoutHours * 3600)));
            if (!expired) return;   // still inside the window: leave VERIFYING
            // past the window the pendings become ERROR → the manual fallback runs
            for (KycVerification v : results) {
                if (v.getStatus() == KycVerification.Status.PENDING) {
                    v.setStatus(KycVerification.Status.ERROR);
                    v.setDetail("check pending past the " + timeoutHours + "h window");
                    v.setCompletedAt(Instant.now());
                    verifications.save(v);
                }
            }
        }
        // all rows settled: finish the flow with the frozen document
        KycMasterService.ProfileBundle bundle = master.bySchemaVersion(app.getSchemaVersionId());
        com.mipay.sme.service.kyc.KycPluginContracts.WorkflowPlan plan = readPlan(app);
        advanceToStages(app, platformPrincipal(), plan, frozenDoc(app));
    }

    /** System principal for engine-internal resumption: the platform, acting as ops. */
    private Current.Principal platformPrincipal() {
        return new Current.Principal("system", "TPTS_OPS", "KYC Engine", null,
                null, null, null, null, null, null);
    }

    /**
     * Each submission is a NEW version (§6.4): RETURN + resubmit bumps the
     * number, which re-runs the entry check and verification and re-freezes the
     * document. The unique (application_id, submission_no, acted_by) index then
     * lets the reviewer who returned it act again on the new version.
     */
    private int coalesceSubmissionNo(KycApplication app) {
        return submissions.findMaxSubmissionNo(app.getId()) + 1;
    }

    private String text(Object o) {
        return o == null || "null".equals(o.toString()) ? null : o.toString();
    }

    private String orgIdOf(Current.Principal actor) {
        return actor.orgId() != null && !"null".equals(actor.orgId())
                ? actor.orgId()
                : (callerService.orgOf(actor) == null ? null : callerService.orgOf(actor).getId());
    }

    private String parentOf(String orgId) {
        // direct predecessor in the tree (closure at depth 1), else parent_id
        String parent = closureRepo.findDirectParent(orgId);
        if (parent == null) {
            var org = orgs.findById(orgId).orElse(null);
            parent = org == null ? null : org.getParentId();
        }
        return parent;
    }

    private String orgTypeOf(KycApplication app) {
        return app.getOrgType() == null ? "MERCHANT" : app.getOrgType().name();
    }

    private String openStageKey(KycApplication app) {
        return tasks.findByApplicationIdAndSubmissionNoAndKindAndStatus(
                app.getId(), app.getSubmissionNo(), KycTask.Kind.STAGE, KycTask.Status.OPEN)
                .stream().findFirst().map(KycTask::getStageKey).orElse(null);
    }
}
