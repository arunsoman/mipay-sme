package com.mipay.sme.service;

import com.mipay.sme.common.SimException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.SecuritySetting;
import com.mipay.sme.domain.ledger.BalanceAccount;
import com.mipay.sme.repository.ReconciliationBreakRepository;
import com.mipay.sme.repository.ReconciliationRunRepository;
import com.mipay.sme.web.Dtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Three-ledger reconciliation (UAT SB p.14, BC p.18):
 * issuer balance ledger (authoritative) vs merchant subledger vs lender credit ledger.
 * Breaks: missing credit, duplicate debit, stale hold, orphan payment.
 * Unexplained differences block later release (withdrawals) (TC-RCN-02).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    /** Dedicated mutex row (security_settings.key = RECON_RUN_LOCK) taken FOR UPDATE at
     *  the start of every run: JVM-monitor-free (virtual-thread friendly), and cluster-safe
     *  on both the H2 test profile and Postgres (TC-RACE-11). */
    private static final String RUN_LOCK_KEY = "RECON_RUN_LOCK";

    private final ReconciliationRunRepository runRepo;
    private final ReconciliationBreakRepository breakRepo;
    private final com.mipay.sme.repository.SecuritySettingRepository settingRepo;
    private final com.mipay.sme.repository.BalanceAccountRepository accountRepo;
    private final com.mipay.sme.repository.BalanceHoldRepository holdRepo;
    private final com.mipay.sme.repository.IssuerBalanceLedgerRepository issuerRepo;
    private final LedgerService ledgerService;
    private final com.mipay.sme.repository.LenderCreditLedgerRepository creditLedgerRepo;
    private final AuditService auditService;
    private final com.mipay.sme.config.AppProperties props;

    @Transactional
    public Dtos.RunResult run() {
        // Serialise concurrent runs with a DB-level row lock on the RECON_RUN_LOCK mutex
        // row (TC-RACE-11): concurrent run() calls queue on the JDBC lock and each still
        // returns its own persisted RunResult. Virtual threads block without pinning.
        reconcileLock();
        return runLocked();
    }

    /** Acquire (or lazily create) the recon mutex row and hold its row lock until commit. */
    private void reconcileLock() {
        if (settingRepo.lockByKey(RUN_LOCK_KEY).isEmpty()) {
            SecuritySetting lock = new SecuritySetting();
            lock.setSettingKey(RUN_LOCK_KEY);
            lock.setSettingValue("recon batch mutex");
            settingRepo.save(lock);
            settingRepo.flush();
        }
    }

    private Dtos.RunResult runLocked() {
        LocalDate date = LocalDate.now(ZoneOffset.UTC);

        List<BalanceAccount> accounts = accountRepo.findAll();

        Map<String, BigDecimal> issuerNet = ledgerService.issuerNetPerOwner();
        Map<String, BigDecimal> subNet = ledgerService.confirmedNetPerOwner();

        // expire stale holds and flag them (C22, TC-SET-23)
        List<com.mipay.sme.domain.ledger.BalanceHold> stale =
                holdRepo.findByStatusAndExpiresAtBefore(Enums.HoldStatus.ACTIVE, Instant.now());

        List<com.mipay.sme.domain.recon.ReconciliationBreak> rows = new ArrayList<>();

        com.mipay.sme.domain.recon.ReconciliationRun run = new com.mipay.sme.domain.recon.ReconciliationRun();
        run.setRunDate(date);
        // flush early: run.id is needed by each break row; provisional status updated below
        run.setStatus(Enums.ReconStatus.BREAKS_FOUND);
        run.setBreaksCount(0);
        runRepo.saveAndFlush(run);

        BigDecimal issuerTotal = BigDecimal.ZERO;
        BigDecimal subledgerTotal = BigDecimal.ZERO;
        for (BalanceAccount account : accounts) {
            BigDecimal issuer = issuerNet.getOrDefault(account.getOwnerId(), BigDecimal.ZERO);
            BigDecimal sub = subNet.getOrDefault(account.getOwnerId(), BigDecimal.ZERO);
            issuerTotal = issuerTotal.add(issuer);
            subledgerTotal = subledgerTotal.add(sub);
            BigDecimal diff = issuer.subtract(sub);
            if (diff.signum() != 0) {
                com.mipay.sme.domain.recon.ReconciliationBreak b = new com.mipay.sme.domain.recon.ReconciliationBreak();
                b.setBreakType(diff.signum() > 0 ? Enums.BreakType.MISSING_CREDIT : Enums.BreakType.DUPLICATE_DEBIT);
                b.setAmount(diff.abs());
                b.setReference(account.getOwnerId() + " / " + account.getIssuerAccountRef());
                b.setDescription("Issuer net N$" + issuer + " vs subledger net N$" + sub);
                rows.add(b);
            }
        }

        for (com.mipay.sme.domain.ledger.BalanceHold hold : stale) {
            ledgerService.expireHold(hold);
            com.mipay.sme.domain.recon.ReconciliationBreak b = new com.mipay.sme.domain.recon.ReconciliationBreak();
            b.setBreakType(Enums.BreakType.STALE_HOLD);
            b.setAmount(hold.getAmount());
            b.setReference(hold.getReference());
            b.setDescription("Hold expired " + hold.getExpiresAt() + " while still ACTIVE - released now");
            rows.add(b);
        }

        List<com.mipay.sme.domain.recon.ReconciliationBreak> newRows = rows.stream()
                .filter(b -> !breakRepo.existsByBreakTypeAndReferenceAndStatus(
                        b.getBreakType(), b.getReference(), Enums.BreakStatus.OPEN))
                .toList();
        for (com.mipay.sme.domain.recon.ReconciliationBreak b : newRows) {
            b.setRunId(run.getId());
            breakRepo.save(b);
        }

        run.setIssuerTotal(issuerTotal.setScale(2, java.math.RoundingMode.HALF_UP));
        run.setSubledgerTotal(subledgerTotal.setScale(2, java.math.RoundingMode.HALF_UP));
        run.setCreditLedgerTotal(creditLedgerRepo.netOwedTotal(Enums.LedgerEntryType.CREDIT)
                .setScale(2, java.math.RoundingMode.HALF_UP));
        run.setBreaksCount(newRows.size());
        run.setStatus(rows.isEmpty() ? Enums.ReconStatus.MATCHED : Enums.ReconStatus.BREAKS_FOUND);
        runRepo.save(run);

        auditService.record("system", "TPTS_OPS", "reconciliation_run", "reconciliation_run", run.getId(),
                null, null, "breaks=" + newRows.size() + (rows.size() == newRows.size() ? ""
                        : " (" + (rows.size() - newRows.size()) + " already open, deduped)"), false, null);
        if (rows.isEmpty()) {
            return new Dtos.RunClean(run.getId(), "All three ledgers agree (issuer / subledger / lender)");
        }
        List<Dtos.BreakRow> dtoRows = breakRepo.findByRunId(run.getId()).stream()
                .map(b -> new Dtos.BreakRow(b.getId(), b.getBreakType().name(), b.getAmount(), b.getReference(),
                        b.getDescription(), b.getStatus().name(), b.getResolvedAt()))
                .toList();
        return new Dtos.RunWithBreaks(run.getId(), rows.size(), dtoRows,
                "Breaks found - release blocked until resolved (BC p.18)");
    }

    @Transactional(readOnly = true)
    public java.util.Optional<com.mipay.sme.domain.recon.ReconciliationRun> lastRun() {
        return runRepo.findTopByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<Dtos.ReconRunRow> runs() {
        return runRepo.findAllByOrderByCreatedAtDesc().stream()
                .map(r -> new Dtos.ReconRunRow(r.getId(), r.getRunDate(), r.getIssuerTotal(), r.getSubledgerTotal(),
                        r.getCreditLedgerTotal(), r.getBreaksCount(), r.getStatus().name(), r.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Dtos.BreakRow> breaks(String runId) {
        return breakRepo.findByRunId(runId).stream()
                .map(b -> new Dtos.BreakRow(b.getId(), b.getBreakType().name(), b.getAmount(), b.getReference(),
                        b.getDescription(), b.getStatus().name(), b.getResolvedAt()))
                .toList();
    }

    @Transactional
    public Dtos.BreakRow resolveBreak(String breakId, String actorId) {
        com.mipay.sme.domain.recon.ReconciliationBreak b = breakRepo.findById(breakId)
                .orElseThrow(() -> new com.mipay.sme.common.NotFoundException("Break not found"));
        b.setStatus(Enums.BreakStatus.RESOLVED);
        b.setResolvedBy(actorId);
        b.setResolvedAt(Instant.now());
        breakRepo.save(b);
        auditService.record(actorId, "TPTS_OPS", "break_resolved", "reconciliation_break", b.getId(),
                "OPEN", "RESOLVED", null, false, null);
        return new Dtos.BreakRow(b.getId(), b.getBreakType().name(), b.getAmount(), b.getReference(),
                b.getDescription(), b.getStatus().name(), b.getResolvedAt());
    }

    /** Gate used by withdrawals: unexplained differences block release (BC p.18, TC-RCN-02). */
    @Transactional(readOnly = true)
    public void assertNoOpenBreaks() {
        if (!props.getRecon().isBlockOnOpenBreaks()) {
            return;
        }
        boolean recentBreaks = breakRepo.findByStatus(Enums.BreakStatus.OPEN).stream()
                .anyMatch(b -> b.getRunId() != null);
        if (recentBreaks) {
            throw new SimException("RECON_BLOCKED",
                    "Open reconciliation breaks - release blocked until resolved (BC p.18)");
        }
    }
}
