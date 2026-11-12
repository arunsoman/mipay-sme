package com.mipay.sme.service;

import com.mipay.sme.common.NotFoundException;
import com.mipay.sme.config.AppProperties;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.admin.ApiClient;
import com.mipay.sme.domain.admin.Backup;
import com.mipay.sme.domain.admin.Deployment;
import com.mipay.sme.domain.admin.NotificationTemplate;
import com.mipay.sme.domain.admin.RestoreTest;
import com.mipay.sme.domain.core.SecuritySetting;
import com.mipay.sme.domain.fees.FeeTariff;
import com.mipay.sme.domain.ledger.LimitProfile;
import com.mipay.sme.repository.ApiClientRepository;
import com.mipay.sme.repository.BackupRepository;
import com.mipay.sme.repository.DeploymentRepository;
import com.mipay.sme.repository.EligibilityRuleRepository;
import com.mipay.sme.repository.FeeTariffRepository;
import com.mipay.sme.repository.LimitProfileRepository;
import com.mipay.sme.repository.NotificationTemplateRepository;
import com.mipay.sme.repository.RestoreTestRepository;
import com.mipay.sme.repository.SecuritySettingRepository;
import com.mipay.sme.web.Dtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Configuration + admin console services (C01-C24, E30-E45, X10-X17).
 * Every config change is audited with before/after (TC-SET-21).
 */
@Service
@RequiredArgsConstructor
public class AdminService {

    private final FeeTariffRepository tariffRepo;
    private final LimitProfileRepository limitRepo;
    private final EligibilityRuleRepository ruleRepo;
    private final SecuritySettingRepository settingRepo;
    private final NotificationTemplateRepository templateRepo;
    private final ApiClientRepository apiClientRepo;
    private final DeploymentRepository deploymentRepo;
    private final BackupRepository backupRepo;
    private final RestoreTestRepository restoreRepo;
    private final com.mipay.sme.repository.UserRepository userRepo;
    private final com.mipay.sme.repository.DeviceSessionRepository sessionRepo;
    private final com.mipay.sme.repository.IdentityLinkRepository linkRepo;
    private final com.mipay.sme.security.auth.AuthProviderRegistry authRegistry;
    private final OpsService opsService;
    private final com.mipay.sme.service.BankSimulator bankSimulator;
    private final AppProperties props;
    private final AuditService auditService;

    private final SecureRandom random = new SecureRandom();

    // ------------------------------------------------------------------ tariffs (C01-C05)

    @Transactional(readOnly = true)
    public List<Dtos.TariffRow> tariffs() {
        return tariffRepo.findAllByOrderByCodeAsc().stream()
                .map(t -> new Dtos.TariffRow(t.getId(), t.getCode(), t.getRateType().name(), t.getValue(),
                        t.getExternalCost(), t.getCostBasis().name(), t.getNote(),
                        t.getEffectiveFrom(), t.getEffectiveTo()))
                .toList();
    }

    @Transactional
    public Dtos.TariffRow upsertTariff(Dtos.TariffUpsert req, String actorId, String actorRole) {
        var existing = tariffRepo.findTopByCodeOrderByEffectiveFromDesc(req.code()).orElse(null);
        if (existing != null) {
            existing.setValue(req.value());
            existing.setExternalCost(req.externalCost());
            existing.setCostBasis(req.costBasis());
            existing.setNote(req.note());
            existing.setEffectiveFrom(req.effectiveFrom() == null ? LocalDate.now() : req.effectiveFrom());
            existing.setApprovedBy(actorId);
            tariffRepo.save(existing);
            auditService.record(actorId, actorRole, "tariff_updated", "fee_tariff", existing.getId(),
                    String.valueOf(existing.getValue()), String.valueOf(req.value()), req.code(), false, null);
            return toRow(existing);
        }
        FeeTariff t = new FeeTariff();
        t.setCode(req.code());
        t.setRateType(req.rateType());
        t.setValue(req.value());
        t.setExternalCost(req.externalCost());
        t.setCostBasis(req.costBasis());
        t.setNote(req.note());
        t.setEffectiveFrom(req.effectiveFrom() == null ? LocalDate.now() : req.effectiveFrom());
        t.setApprovedBy(actorId);
        tariffRepo.save(t);
        auditService.record(actorId, actorRole, "tariff_created", "fee_tariff", t.getId(),
                null, t.getCode(), "value=" + req.value(), false, null);
        return toRow(t);
    }

    private Dtos.TariffRow toRow(FeeTariff t) {
        return new Dtos.TariffRow(t.getId(), t.getCode(), t.getRateType().name(), t.getValue(),
                t.getExternalCost(), t.getCostBasis().name(), t.getNote(), t.getEffectiveFrom(), t.getEffectiveTo());
    }

    // ------------------------------------------------------------------ limits (C10-C14)

    @Transactional(readOnly = true)
    public List<Dtos.LimitView> limits() {
        return limitRepo.findAllByOrderByMerchantIdAsc().stream()
                .map(p -> new Dtos.LimitView(p.getId(), p.getMerchantId(), p.getMaxBalance(), p.getDailyDebitCap(),
                        p.getWithdrawalCap(), p.getAdvanceCap(), p.getArrearsTriggerPct()))
                .toList();
    }

    @Transactional
    public Dtos.LimitView upsertLimit(Dtos.LimitUpsert req, String actorId, String actorRole) {
        LimitProfile p = limitRepo.findByMerchantId(req.merchantId())
                .orElseGet(() -> {
                    LimitProfile np = new LimitProfile();
                    np.setMerchantId(req.merchantId());
                    return np;
                });
        String before = String.valueOf(p.getWithdrawalCap());
        p.setMaxBalance(req.maxBalance());
        p.setDailyDebitCap(req.dailyDebitCap());
        p.setWithdrawalCap(req.withdrawalCap());
        p.setAdvanceCap(req.advanceCap());
        p.setArrearsTriggerPct(req.arrearsTriggerPct());
        limitRepo.save(p);
        auditService.record(actorId, actorRole, "limit_updated", "limit_profile", p.getId(),
                before, String.valueOf(p.getWithdrawalCap()), "merchant=" + req.merchantId(), false, null);
        return new Dtos.LimitView(p.getId(), p.getMerchantId(), p.getMaxBalance(), p.getDailyDebitCap(),
                p.getWithdrawalCap(), p.getAdvanceCap(), p.getArrearsTriggerPct());
    }

    // ------------------------------------------------------------------ eligibility rules (C23)

    @Transactional(readOnly = true)
    public List<Dtos.RuleRow> rules() {
        return ruleRepo.findAll().stream()
                .map(r -> new Dtos.RuleRow(r.getId(), r.getRuleCode(), r.getEnabled(), r.getDefinition()))
                .toList();
    }

    @Transactional
    public Dtos.RuleRow toggleRule(String ruleCode, boolean enabled, String actorId, String actorRole) {
        var rule = ruleRepo.findByRuleCode(ruleCode)
                .orElseThrow(() -> new NotFoundException("Rule not found"));
        String before = String.valueOf(rule.getEnabled());
        rule.setEnabled(enabled);
        ruleRepo.save(rule);
        auditService.record(actorId, actorRole, "rule_toggled", "eligibility_rule", rule.getId(),
                before, String.valueOf(enabled), ruleCode, false, null);
        return new Dtos.RuleRow(rule.getId(), rule.getRuleCode(), rule.getEnabled(), rule.getDefinition());
    }

    // ------------------------------------------------------------------ settings (E44, C03)

    @Transactional(readOnly = true)
    public List<Dtos.SettingRow> settings() {
        return settingRepo.findAllByOrderBySettingKeyAsc().stream()
                .map(s -> new Dtos.SettingRow(s.getSettingKey(), s.getSettingValue(),
                        String.valueOf(s.getUpdatedAt())))
                .toList();
    }

    @Transactional
    public Dtos.SettingRow upsertSetting(Dtos.SettingUpsert req, String actorId, String actorRole) {
        SecuritySetting s = settingRepo.findBySettingKey(req.key())
                .orElseGet(() -> {
                    SecuritySetting ns = new SecuritySetting();
                    ns.setSettingKey(req.key());
                    return ns;
                });
        String before = s.getSettingValue();
        s.setSettingValue(req.value());
        s.setUpdatedBy(actorId);
        s.setUpdatedAt(Instant.now());
        settingRepo.save(s);
        auditService.record(actorId, actorRole, "setting_updated", "security_setting", s.getId(),
                before, req.value(), req.key(), false, null);
        return new Dtos.SettingRow(s.getSettingKey(), s.getSettingValue(), String.valueOf(s.getUpdatedAt()));
    }

    // ------------------------------------------------------------------ templates (C40-C42)

    @Transactional(readOnly = true)
    public List<Dtos.TemplateRow> templates() {
        return templateRepo.findAllByOrderByEventCodeAsc().stream()
                .map(t -> new Dtos.TemplateRow(t.getId(), t.getChannel().name(), t.getEventCode(), t.getBody(),
                        t.getLocale(), t.getStatus().name()))
                .toList();
    }

    @Transactional
    public Dtos.TemplateRow upsertTemplate(Dtos.TemplateUpsert req, String actorId, String actorRole) {
        var existing = templateRepo.findByEventCodeAndChannelAndStatus(req.eventCode(), req.channel(),
                Enums.TemplateStatus.ACTIVE);
        NotificationTemplate t = existing.orElseGet(NotificationTemplate::new);
        t.setChannel(req.channel());
        t.setEventCode(req.eventCode());
        t.setBody(req.body());
        templateRepo.save(t);
        auditService.record(actorId, actorRole, "template_saved", "notification_template", t.getId(),
                null, req.eventCode(), req.channel().name(), false, null);
        return new Dtos.TemplateRow(t.getId(), t.getChannel().name(), t.getEventCode(), t.getBody(),
                t.getLocale(), t.getStatus().name());
    }

    // ------------------------------------------------------------------ api clients (X10)

    @Transactional(readOnly = true)
    public List<Dtos.ApiClientView> apiClients() {
        return apiClientRepo.findAllByOrderByCreatedAtDesc().stream().map(this::clientRow).toList();
    }

    @Transactional
    public Dtos.ApiClientView createClient(Dtos.ApiClientCreate req, String actorId, String actorRole) {
        ApiClient c = new ApiClient();
        c.setClientName(req.clientName());
        c.setClientId("mipay-" + String.format("%08X", random.nextLong() & 0xFFFFFFFFL));
        c.setScopes(req.scopes() == null ? "[\"ledger:read\"]" : String.join(",", req.scopes()));
        c.setRateLimitTps(req.rateLimitTps() == null ? 20 : req.rateLimitTps());
        apiClientRepo.save(c);
        auditService.record(actorId, actorRole, "api_client_created", "api_client", c.getId(),
                null, c.getClientId(), "scopes=" + c.getScopes(), false, null);
        return clientRow(c);
    }

    @Transactional
    public Dtos.ApiClientView revokeClient(String id, String actorId, String actorRole) {
        ApiClient c = apiClientRepo.findById(id).orElseThrow(() -> new NotFoundException("Client not found"));
        c.setStatus(Enums.ApiClientStatus.REVOKED);
        apiClientRepo.save(c);
        auditService.record(actorId, actorRole, "api_client_revoked", "api_client", c.getId(),
                "ACTIVE", "REVOKED", null, false, null);
        return clientRow(c);
    }

    private Dtos.ApiClientView clientRow(ApiClient c) {
        return new Dtos.ApiClientView(c.getId(), c.getClientName(), c.getClientId(), c.getScopes(),
                c.getStatus().name(), c.getRateLimitTps(), c.getLastRotatedAt(), c.getCreatedAt());
    }

    // ------------------------------------------------------------------ deployments / backups (X12/X14)

    @Transactional
    public Dtos.DeploymentRow deploy(Dtos.DeployRequest req, String actorId, String actorRole) {
        Deployment d = new Deployment();
        d.setVersion(req.version());
        d.setEnvironment(req.environment());
        d.setStatus(Enums.DeployStatus.SUCCESS);
        d.setNotes(req.notes());
        d.setDeployedBy(actorId);
        deploymentRepo.save(d);
        auditService.record(actorId, actorRole, "deployed", "deployment", d.getId(), null, d.getVersion(), null, false, null);
        return new Dtos.DeploymentRow(d.getId(), d.getVersion(), d.getEnvironment(), d.getStatus().name(),
                d.getNotes(), d.getDeployedAt());
    }

    @Transactional
    public Dtos.DeploymentRow rollback(String version, String actorId, String actorRole) {
        Deployment d = new Deployment();
        d.setVersion(version);
        d.setEnvironment("sandbox");
        d.setStatus(Enums.DeployStatus.ROLLED_BACK);
        d.setNotes("rollback of " + version);
        d.setDeployedBy(actorId);
        deploymentRepo.save(d);
        auditService.record(actorId, actorRole, "deploy_rolled_back", "deployment", d.getId(),
                null, version, null, false, null);
        return new Dtos.DeploymentRow(d.getId(), d.getVersion(), d.getEnvironment(), d.getStatus().name(),
                d.getNotes(), d.getDeployedAt());
    }

    @Transactional(readOnly = true)
    public List<Dtos.DeploymentRow> deployments() {
        return deploymentRepo.findAllByOrderByDeployedAtDesc().stream()
                .map(d -> new Dtos.DeploymentRow(d.getId(), d.getVersion(), d.getEnvironment(),
                        d.getStatus().name(), d.getNotes(), d.getDeployedAt()))
                .toList();
    }

    @Transactional
    public Dtos.BackupRow backupNow(String actorId, String actorRole) {
        Backup b = new Backup();
        b.setBackupType(Enums.BackupType.INCREMENTAL);
        b.setStatus(Enums.BackupStatus.SUCCESS);
        b.setCompletedAt(Instant.now());
        backupRepo.save(b);
        auditService.sim(actorId, actorRole, "backup_taken", "backup", b.getId(), "incremental", null);
        return new Dtos.BackupRow(b.getId(), b.getBackupType().name(), b.getStartedAt(), b.getCompletedAt(),
                b.getStatus().name());
    }

    /** Restore test with RTO 2h / RPO 0 evidence (X14, TC-SEC-03, TC-OPS-24). */
    @Transactional
    public Dtos.RestoreTestRow restoreTest(String backupId, String actorId, String actorRole) {
        backupRepo.findById(backupId).orElseThrow(() -> new NotFoundException("Backup not found"));
        RestoreTest t = new RestoreTest();
        t.setBackupId(backupId);
        t.setRtoAchievedMinutes(95 + random.nextInt(20));   // < 120 min target
        t.setRpoAchievedMinutes(0);                        // zero data loss target
        t.setOk(true);
        t.setTestedBy(actorId);
        restoreRepo.save(t);
        auditService.sim(actorId, actorRole, "restore_test", "restore_test", t.getId(),
                "RTO=" + t.getRtoAchievedMinutes() + "min RPO=" + t.getRpoAchievedMinutes() + "min", null);
        return new Dtos.RestoreTestRow(t.getId(), t.getBackupId(), t.getTestedAt(), t.getRtoAchievedMinutes(),
                t.getRpoAchievedMinutes(), t.getOk());
    }

    @Transactional(readOnly = true)
    public List<Dtos.BackupRow> backups() {
        return backupRepo.findAllByOrderByStartedAtDesc().stream()
                .map(b -> new Dtos.BackupRow(b.getId(), b.getBackupType().name(), b.getStartedAt(),
                        b.getCompletedAt(), b.getStatus().name()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Dtos.RestoreTestRow> restoreTests() {
        return restoreRepo.findAllByOrderByTestedAtDesc().stream()
                .map(t -> new Dtos.RestoreTestRow(t.getId(), t.getBackupId(), t.getTestedAt(),
                        t.getRtoAchievedMinutes(), t.getRpoAchievedMinutes(), t.getOk()))
                .toList();
    }

    // ------------------------------------------------------------- access & sessions (X04, TC-SEC-04)

    /** Platform users with their LIVE session counts (jti-backed). */
    @Transactional(readOnly = true)
    public List<Dtos.AccessUserRow> accessUsers() {
        Instant now = Instant.now();
        return userRepo.findAll().stream()
                .sorted(java.util.Comparator.comparing(com.mipay.sme.domain.core.User::getPhone))
                .map(u -> new Dtos.AccessUserRow(u.getId(), u.getPhone(), u.getRole().name(),
                        u.getStatus().name(), sessionRepo.countByUserIdAndExpiresAtAfter(u.getId(), now)))
                .toList();
    }

    /** A user's credential links (identity_links) and device sessions (cut-over P3.9). */
    @Transactional(readOnly = true)
    public Dtos.AccessDetail accessDetail(String userId) {
        com.mipay.sme.domain.core.User u = userRepo.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        Instant now = Instant.now();
        List<Dtos.AccessLinkRow> links = linkRepo.findByUserId(userId).stream()
                .map(l -> new Dtos.AccessLinkRow(l.getId(), l.getProvider(), l.getSubject(),
                        l.getStatus().name(), l.isIdpEnabled(), l.getActivatedAt(), l.getLastLoginAt()))
                .toList();
        List<Dtos.AccessSessionRow> sessions = sessionRepo.findByUserId(userId).stream()
                .sorted(java.util.Comparator.comparing(
                        com.mipay.sme.domain.core.DeviceSession::getCreatedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())).reversed())
                .map(s -> new Dtos.AccessSessionRow(s.getId(), s.getDeviceFingerprint(),
                        s.getCreatedAt(), s.getExpiresAt(),
                        s.getExpiresAt() != null && s.getExpiresAt().isAfter(now)))
                .toList();
        return new Dtos.AccessDetail(u.getId(), u.getPhone(), u.getRole().name(), u.getStatus().name(), links, sessions);
    }

    /**
     * Disables ONE credential link: the provider account is disabled and the link row flips
     * to DISABLED, without suspending the user (who may hold other live links, P3.9). The
     * provider command is direct here, not via the outbox: the admin decision is immediate.
     */
    @Transactional
    public Dtos.AccessDisableResult disableLink(String linkId, String actorId, String actorRole) {
        com.mipay.sme.domain.core.IdentityLink link = linkRepo.findById(linkId)
                .orElseThrow(() -> new NotFoundException("Identity link not found"));
        if (authRegistry.enabled() && link.getSubject() != null && !link.getSubject().isBlank()) {
            var provider = authRegistry.selected();
            if (link.isIdpEnabled()) provider.disable(link.getSubject(), "admin disabled credential");
            // a PENDING (enrolled, never set-up) link has no live credential to disable at the IdP
        }
        link.setStatus(com.mipay.sme.domain.core.IdentityLink.Status.DISABLED);
        linkRepo.save(link);
        auditService.record(actorId, actorRole, "credential_disabled", "identity_link", linkId,
                "status=" + link.getStatus(), "status=DISABLED", "link=" + link.getSubject(), false, null);
        return new Dtos.AccessDisableResult(linkId, link.getStatus().name());
    }

    /**
     * Credential-compromise response (BC p.27): revoke every live session of a
     * user; the next request with any of those tokens becomes 401. Raises a
     * critical incident the ops desk must acknowledge within the 15-min SLA.
     */
    @Transactional
    public Dtos.AccessRevokeResult revokeUserAccess(String targetUserId, String actorId, String actorRole) {
        com.mipay.sme.domain.core.User target = userRepo.findById(targetUserId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        long revoked = sessionRepo.deleteByUserId(targetUserId);
        // cut-over P3.9: revocation also reaches the credential provider session set
        if (authRegistry.enabled()) {
            var provider = authRegistry.selected();
            linkRepo.findByUserId(targetUserId).stream()
                    .filter(l -> l.isIdpEnabled() && l.getSubject() != null && !l.getSubject().isBlank())
                    .forEach(l -> provider.revokeSessions(l.getSubject()));
        }
        Dtos.IncidentRow incident = opsService.raiseSystem("CRITICAL", "All sessions revoked for "
                        + target.getPhone(), "Credential-compromise response: " + revoked
                        + " active session(s) of role " + target.getRole() + " revoked by Mindlabs admin.",
                targetUserId);
        auditService.record(actorId, actorRole, "access_revoked", "user", targetUserId,
                "sessions=" + revoked, "sessions=0", "incident=" + incident.id(), false, null);
        return new Dtos.AccessRevokeResult((int) revoked, incident.id());
    }

    // ------------------------------------------------------------- bank-simulator controls (X-Sim-Scenario)

    @Transactional(readOnly = true)
    public Dtos.SimControlsView simControls() {
        return new Dtos.SimControlsView(bankSimulator.defaultScenario().name(),
                bankSimulator.nextBankReference(), props.isSimulatedEnvironment());
    }

    @Transactional
    public Dtos.SimControlsView setSimControls(Dtos.SimControlsRequest req, String actorId, String actorRole) {
        var scenario = bankSimulator.parse(req.scenario());
        bankSimulator.setDefaultScenario(scenario == com.mipay.sme.service.BankSimulator.Scenario.MANUAL
                ? com.mipay.sme.service.BankSimulator.Scenario.MANUAL : scenario, actorId, actorRole);
        return simControls();
    }

}
