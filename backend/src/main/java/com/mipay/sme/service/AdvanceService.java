package com.mipay.sme.service;

import com.mipay.sme.common.NotFoundException;
import com.mipay.sme.common.SimException;
import com.mipay.sme.config.AppProperties;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.ledger.BalanceAccount;
import com.mipay.sme.domain.credit.Advance;
import com.mipay.sme.domain.credit.AdvanceRepayment;
import com.mipay.sme.domain.credit.ArrearsCase;
import com.mipay.sme.domain.credit.ConsentWithdrawal;
import com.mipay.sme.domain.credit.CreditAssessment;
import com.mipay.sme.domain.credit.CreditConsent;
import com.mipay.sme.domain.credit.Lender;
import com.mipay.sme.domain.credit.LenderCreditLedgerEntry;
import com.mipay.sme.domain.core.Merchant;
import com.mipay.sme.domain.payments.Payment;
import com.mipay.sme.domain.payments.Refund;
import com.mipay.sme.repository.AdvanceRepaymentRepository;
import com.mipay.sme.repository.AdvanceRepository;
import com.mipay.sme.repository.ArrearsCaseRepository;
import com.mipay.sme.repository.AmlAlertRepository;
import com.mipay.sme.repository.ConsentWithdrawalRepository;
import com.mipay.sme.repository.CreditAssessmentRepository;
import com.mipay.sme.repository.IssuerBalanceLedgerRepository;
import com.mipay.sme.repository.CreditConsentRepository;
import com.mipay.sme.repository.EligibilityRuleRepository;
import com.mipay.sme.repository.LenderCreditLedgerRepository;
import com.mipay.sme.repository.LenderRepository;
import com.mipay.sme.repository.MerchantRepository;
import com.mipay.sme.repository.PaymentRepository;
import com.mipay.sme.repository.PortfolioSnapshotRepository;
import com.mipay.sme.repository.RefundRepository;
import com.mipay.sme.repository.SupplierRepository;
import com.mipay.sme.repository.VintageAnalysisRepository;
import com.mipay.sme.web.Dtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Cash advance lifecycle (BC p.10-p.11, UAT SB pp.12-13):
 *
 * - consent is purpose-limited and withdrawable (TC-REC-02/03);
 * - assessment uses verified records over a 90-day window with exclusion
 *   rules: advance proceeds, self-transfers, circular B2B (TC-REC-05, TC-SET-27);
 * - the offer respects the lesser-of cap rule: min(N$25,000, 20% of verified
 *   average monthly net receipts, lender limit) (TC-SET-24, BC p.10);
 * - approval alone adds no money; funding does (TC-ADV-01/02, UAT SB p.13);
 * - repayment collects N% of each day's eligible net receipts, capped by the
 *   remaining owed and the lender's max daily deduction; zero-sales days
 *   collect nothing (TC-ADV-03/05); refunds net against the base (TC-ADV-04);
 * - new draws pause when 30-day arrears exceed the configured share (C13, BC p.19).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdvanceService {

    private static final String CONSENT_PURPOSE = "Cash advance assessment";
    public static final List<Enums.AdvanceStatus> OWED_STATUSES =
            List.of(Enums.AdvanceStatus.FUNDED, Enums.AdvanceStatus.REPAYING);

    private final AdvanceRepository advanceRepo;
    private final AdvanceRepaymentRepository repayRepo;
    private final LenderCreditLedgerRepository creditLedgerRepo;
    private final IssuerBalanceLedgerRepository issuerRepo;
    private final CreditConsentRepository consentRepo;
    private final ConsentWithdrawalRepository withdrawalRepo;
    private final CreditAssessmentRepository assessmentRepo;
    private final LenderRepository lenderRepo;
    private final EligibilityRuleRepository ruleRepo;
    private final PaymentRepository paymentRepo;
    private final RefundRepository refundRepo;
    private final SupplierRepository supplierRepo;
    private final MerchantRepository merchantRepo;
    private final AmlAlertRepository amlRepo;
    private final PortfolioSnapshotRepository snapshotRepo;
    private final ArrearsCaseRepository arrearsCaseRepo;
    private final VintageAnalysisRepository vintageRepo;
    private final LedgerService ledgerService;
    private final FeeService feeService;
    private final AuditService auditService;
    private final AppProperties props;

    // ------------------------------------------------------------------ consent

    @Transactional
    public Dtos.ConsentRow grantConsent(String merchantId, String lenderId) {
        Optional<CreditConsent> existing = consentRepo.findFirstByMerchantIdAndLenderIdAndStatusOrderByGrantedAtDesc(
                merchantId, lenderId, Enums.ConsentStatus.ACTIVE);
        if (existing.isPresent()) {
            return toRow(existing.get());
        }
        Lender lender = lenderRepo.findById(lenderId)
                .orElseThrow(() -> new NotFoundException("Lender not found"));
        CreditConsent consent = new CreditConsent();
        consent.setMerchant(merchantRepo.getReferenceById(merchantId));
        consent.setLender(lender);
        consent.setPurpose(CONSENT_PURPOSE);
        // customer-level Khata is never shared (BC p.12, TC-SET-07)
        consent.setDataFields("[\"receipts_net_of_refunds\",\"trading_days\",\"supplier_payments\"]");
        consent.setRetentionPeriod("90 days after advance closure");
        consent.setStatus(Enums.ConsentStatus.ACTIVE);
        consentRepo.save(consent);

        auditService.record(merchantId, "MERCHANT", "consent_granted", "credit_consent", consent.getId(),
                null, null, "lender=" + lender.getName() + "; Khata excluded", false, null);
        return toRow(consent);
    }

    @Transactional
    public Dtos.Message withdrawConsent(String merchantId, String consentId) {
        CreditConsent consent = consentRepo.findById(consentId)
                .orElseThrow(() -> new NotFoundException("Consent not found"));
        if (!consent.getMerchant().getId().equals(merchantId)) {
            throw new NotFoundException("Consent not found");
        }
        consent.setStatus(Enums.ConsentStatus.WITHDRAWN);
        consent.setWithdrawnAt(Instant.now());
        consentRepo.save(consent);

        ConsentWithdrawal w = new ConsentWithdrawal();
        w.setConsentId(consent.getId());
        w.setReason("merchant withdrew consent");
        withdrawalRepo.save(w);

        auditService.record(merchantId, "MERCHANT", "consent_withdrawn", "credit_consent", consent.getId(),
                "ACTIVE", "WITHDRAWN", "future disclosure blocked (TC-REC-03)", false, null);
        return new Dtos.Message("Consent withdrawn - future sharing blocked; withdrawal recorded", null);
    }

    private Dtos.ConsentRow toRow(CreditConsent consent) {
        return new Dtos.ConsentRow(consent.getId(), consent.getMerchant().getId(),
                consent.getMerchant().getBusinessName(), consent.getPurpose(), consent.getStatus(),
                consent.getGrantedAt());
    }

    // ------------------------------------------------------------------ assessment + offer

    /**
     * Merchant requests an advance (UAT SB p.11/p.13). The lender decision API is
     * simulated inside the sandbox: assessment -> decision -> offer. No money
     * moves until funding is confirmed by the issuer (TC-ADV-01/02).
     */
    @Transactional
    public Dtos.AssessmentView requestAssessment(String merchantId, String lenderId, BigDecimal requestedAmount) {
        Merchant merchant = approvedMerchant(merchantId);
        CreditConsent consent = consentRepo
                .findFirstByMerchantIdAndLenderIdAndStatusOrderByGrantedAtDesc(
                        merchantId, lenderId, Enums.ConsentStatus.ACTIVE)
                .orElseThrow(() -> new SimException("CONSENT_REQUIRED",
                        "Active consent required - show the consent screen first (UAT SB p.12)"));
        Lender lender = consent.getLender();

        checkArrearsPause(lender.getId());

        LocalDate windowEnd = LocalDate.now(ZoneOffset.UTC);
        LocalDate windowStart = windowEnd.minusDays(90);
        Stats stats = computeStats(merchant, windowStart, windowEnd, true);

        if (stats.tradingDays() < 30) {
            return persistDeclined(merchant, lender, consent, windowStart, windowEnd, stats,
                    List.of("INSUFFICIENT_HISTORY", "at least 30 trading days required"));
        }
        if (stats.avgMonthly().compareTo(new BigDecimal("750")) < 0) {
            return persistDeclined(merchant, lender, consent, windowStart, windowEnd, stats,
                    List.of("LOW_REVENUE", "average monthly net receipts below the product threshold"));
        }

        BigDecimal shareCap = props.getAdvance().getMonthlyShare()
                .multiply(stats.avgMonthly()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal cap = min(props.getAdvance().getMaxPrincipal(), shareCap, orZero(lender.getLenderLimit()));
        BigDecimal offer = (requestedAmount == null ? cap : requestedAmount.min(cap))
                .setScale(2, RoundingMode.HALF_UP);
        if (offer.signum() <= 0) {
            return persistDeclined(merchant, lender, consent, windowStart, windowEnd, stats,
                    List.of("CAP_ZERO", "lesser-of cap rule leaves no available principal"));
        }

        BigDecimal totalRepayment = offer.add(offer.multiply(orZero(lender.getServiceFeeRate()))
                .setScale(2, RoundingMode.HALF_UP));
        BigDecimal rate = orRate(lender.getCollectionRate());

        List<String> reasons = List.of("RECEIPTS_VERIFIED", "TRADING_DAYS_OK", "LESSER_OF_CAP_APPLIED");
        return persistOffer(merchant, lender, consent, windowStart, windowEnd, stats,
                Enums.AssessmentDecision.APPROVED, offer, totalRepayment, rate, reasons);
    }

    /** Credit readiness view (M21) - computed from own records only, no consent needed. */
    @Transactional(readOnly = true)
    public Dtos.EligibilityView eligibility(String merchantId) {
        Merchant merchant = merchantRepo.findById(merchantId)
                .orElseThrow(() -> new NotFoundException("Merchant not found"));
        LocalDate windowEnd = LocalDate.now(ZoneOffset.UTC);
        LocalDate windowStart = windowEnd.minusDays(90);
        Stats stats = computeStats(merchant, windowStart, windowEnd, false);
        return new Dtos.EligibilityView(windowStart, windowEnd, stats.eligibleTotal(), stats.tradingDays(),
                stats.avgMonthly(), stats.rulesApplied());
    }

    /** Internal aggregation record - computed once, in one pass, no N+1. */
    private record Stats(BigDecimal eligibleTotal, int tradingDays, BigDecimal avgMonthly, List<String> rulesApplied) { }

    private Stats computeStats(Merchant merchant, LocalDate windowStart, LocalDate windowEnd, boolean raiseAlerts) {
        Instant from = windowStart.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = windowEnd.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);

        List<Payment> payments = paymentRepo.confirmedInRange(merchant.getId(),
                Enums.PaymentStatus.CONFIRMED, from, to);
        List<Refund> refunds = refundRepo.findByMerchantIdOrderByCreatedAtDesc(merchant.getId());
        List<Refund> refundsInWindow = refunds.stream()
                .filter(r -> !r.getCreatedAt().isBefore(from) && r.getCreatedAt().isBefore(to))
                .filter(r -> r.getStatus() == Enums.RefundStatus.PROCESSED)
                .toList();

        boolean excludeSelf = isRuleEnabled("exclude_self_transfer");
        boolean excludeCircular = isRuleEnabled("exclude_circular");
        boolean excludeAdvance = isRuleEnabled("exclude_advance");

        Set<String> supplierPhones = excludeCircular
                ? supplierRepo.findAll().stream()
                        .map(s -> s.getOwnerPhone())
                        .filter(p -> p != null)
                        .collect(Collectors.toSet())
                : Set.of();

        List<String> rulesApplied = new ArrayList<>();
        if (excludeAdvance) {
            rulesApplied.add("exclude_advance: proceeds with source=ADVANCE never counted as sales");
        }
        if (excludeSelf) {
            rulesApplied.add("exclude_self_transfer: payer equals owner phone");
        }
        if (excludeCircular) {
            rulesApplied.add("exclude_circular: pays involving supplier-owned phones detected as circular B2B");
        }

        BigDecimal eligible = BigDecimal.ZERO;
        Set<LocalDate> days = new HashSet<>();
        Set<String> suspiciousPayers = new HashSet<>();

        for (Payment p : payments) {
            // exclude_advance is structurally guaranteed: only CREDIT entries with source
            // PAYMENT/CARD exist in this query; ADVANCE-source credits live in the subledger,
            // which is never part of the sales base (TC-ADV-06).
            boolean self = excludeSelf && p.getPayerRef() != null && p.getPayerRef().equals(merchant.getOwnerPhone());
            boolean circular = excludeCircular && p.getPayerRef() != null && supplierPhones.contains(p.getPayerRef());
            if (self || circular) {
                suspiciousPayers.add(p.getPayerRef());
                continue;
            }
            eligible = eligible.add(p.getAmount());
            days.add(p.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate());
        }

        BigDecimal refundTotal = refundsInWindow.stream()
                .map(Refund::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        eligible = eligible.subtract(refundTotal).max(BigDecimal.ZERO);

        if (raiseAlerts && !suspiciousPayers.isEmpty()) {
            com.mipay.sme.domain.compliance.AmlAlert alert = new com.mipay.sme.domain.compliance.AmlAlert();
            alert.setAlertType("CIRCULAR_PATTERN");
            alert.setEntityType("merchant");
            alert.setEntityId(merchant.getId());
            alert.setDetails("Circular/self-transfer payers observed: " + suspiciousPayers);
            alert.setStatus(Enums.AmlStatus.OPEN);
            amlRepo.save(alert);
        }

        long windowDays = Math.max(30, windowEnd.toEpochDay() - windowStart.toEpochDay());
        BigDecimal avgMonthly = eligible.multiply(new BigDecimal("30"))
                .divide(BigDecimal.valueOf(windowDays), 2, RoundingMode.HALF_UP);
        return new Stats(eligible, days.size(), avgMonthly, rulesApplied);
    }

    private boolean isRuleEnabled(String code) {
        return ruleRepo.findByRuleCode(code)
                .map(com.mipay.sme.domain.recon.EligibilityRule::getEnabled)
                .orElse(true);
    }

    @Transactional
    Dtos.AssessmentView persistDeclined(Merchant merchant, Lender lender, CreditConsent consent,
                                        LocalDate windowStart, LocalDate windowEnd, Stats stats,
                                        List<String> reasons) {
        return persist(merchant, lender, consent, windowStart, windowEnd, stats,
                Enums.AssessmentDecision.DECLINED, null, null, null, reasons);
    }

    @Transactional
    Dtos.AssessmentView persistOffer(Merchant merchant, Lender lender, CreditConsent consent,
                                     LocalDate windowStart, LocalDate windowEnd, Stats stats,
                                     Enums.AssessmentDecision decision, BigDecimal offerPrincipal,
                                     BigDecimal totalRepayment, BigDecimal repaymentRate, List<String> reasons) {
        return persist(merchant, lender, consent, windowStart, windowEnd, stats,
                decision, offerPrincipal, totalRepayment, repaymentRate, reasons);
    }

    private Dtos.AssessmentView persist(Merchant merchant, Lender lender, CreditConsent consent,
                                        LocalDate windowStart, LocalDate windowEnd, Stats stats,
                                        Enums.AssessmentDecision decision, BigDecimal offerPrincipal,
                                        BigDecimal totalRepayment, BigDecimal repaymentRate, List<String> reasons) {
        CreditAssessment a = new CreditAssessment();
        a.setMerchant(merchant);
        a.setLender(lender);
        a.setConsentId(consent == null ? null : consent.getId());
        a.setWindowStart(windowStart);
        a.setWindowEnd(windowEnd);
        a.setEligibleTotal(stats.eligibleTotal());
        a.setEligibleTradingDays(stats.tradingDays());
        a.setAvgMonthlyNetReceipts(stats.avgMonthly());
        a.setScore(new BigDecimal("500").add(stats.avgMonthly()
                .divide(new BigDecimal("100"), 0, RoundingMode.DOWN)
                .min(new BigDecimal("250"))));
        a.setDecision(decision);
        a.setReasonCodes(String.join(",", reasons));
        assessmentRepo.save(a);

        if (decision == Enums.AssessmentDecision.APPROVED && offerPrincipal != null) {
            Advance offer = new Advance();
            offer.setMerchant(merchant);
            offer.setLender(lender);
            offer.setAssessmentId(a.getId());
            offer.setRequestedAmount(offerPrincipal);
            offer.setPrincipalAmount(offerPrincipal);
            offer.setTotalRepayment(totalRepayment);
            offer.setRepaymentRate(repaymentRate == null ? props.getAdvance().getDefaultRate() : repaymentRate);
            offer.setPurpose(CONSENT_PURPOSE);
            offer.setStatus(Enums.AdvanceStatus.OFFERED);
            advanceRepo.save(offer);
            auditService.sim("system", "LENDER", "advance_offered", "advance", offer.getId(),
                    "lesser-of cap applied (TC-SET-24)", null);
        }

        return new Dtos.AssessmentView(a.getId(), merchant.getId(), windowStart, windowEnd,
                stats.eligibleTotal(), stats.tradingDays(), stats.avgMonthly(), a.getScore(), decision,
                offerPrincipal, totalRepayment,
                repaymentRate == null ? props.getAdvance().getDefaultRate() : repaymentRate, reasons);
    }

    // ------------------------------------------------------------------ accept + fund

    @Transactional
    public Dtos.Message accept(String merchantId, String advanceId) {
        // Pessimistic lock: two racers (or accept-vs-fund) serialise here (TC-RACE-08/09).
        Advance advance = advanceRepo.findWithLockById(advanceId)
                .orElseThrow(() -> new NotFoundException("Advance not found"));
        if (!advance.getMerchant().getId().equals(merchantId)) {
            throw new NotFoundException("Advance not found");
        }
        if (advance.getStatus() != Enums.AdvanceStatus.OFFERED) {
            throw new SimException("ADVANCE_STATE", "Advance is " + advance.getStatus() + " (not open for acceptance)");
        }
        advance.setStatus(Enums.AdvanceStatus.ACCEPTED);
        advanceRepo.save(advance);
        auditService.record(advance.getMerchant().getId(), "MERCHANT", "advance_accepted", "advance", advance.getId(),
                "OFFERED", "ACCEPTED", "approval alone adds no money (TC-ADV-01)", false, null);
        return new Dtos.Message("Advance accepted - awaiting lender funding; balance unchanged (TC-ADV-01)", null);
    }

    /** Lender (or simulation panel) funds the advance; issuer confirms -> available (TC-ADV-02). */
    @Transactional
    public Dtos.Message fund(String advanceId, String actorId, String actorRole) {
        // Pessimistic row lock serialises funding replays: the status check +
        // single ledger credit are atomic (TC-RACE-03, exactly-once funding).
        Advance advance = advanceRepo.findWithLockById(advanceId)
                .orElseThrow(() -> new NotFoundException("Advance not found"));
        if (advance.getStatus() != Enums.AdvanceStatus.ACCEPTED) {
            // A re-delivered bank funding callback after a successful disbursement is
            // ABSORBED (single-effect guarantee, TC-ACC-04), not an error: manual
            // re-clicks with no disbursement on record stay refused (TC-ADV-02).
            if (issuerRepo.existsByIdempotencyKey("adv-fund-" + advance.getId())) {
                auditService.sim(actorId == null ? "system" : actorId,
                        actorRole == null ? "LENDER" : actorRole,
                        "advance_funding_replay_absorbed", "advance", advance.getId(),
                        "duplicate funding callback - no second credit", null);
                return new Dtos.Message(
                        "Duplicate funding callback ignored - balance unchanged (single-effect guarantee)",
                        "SIMULATED lender disbursement");
            }
            throw new SimException("ADVANCE_STATE", "Only accepted advances can be funded (status " + advance.getStatus() + ")");
        }

        advance.setStatus(Enums.AdvanceStatus.FUNDED);
        advance.setFundedAt(Instant.now());
        advanceRepo.save(advance);

        Merchant merchant = advance.getMerchant();
        BalanceAccount account = ledgerService.account(Enums.OwnerType.MERCHANT, merchant.getId());
        // idempotency: replaying the funding callback never double-credits (bank-style key)
        ledgerService.postConfirmed(account, Enums.LedgerEntryType.CREDIT, advance.getPrincipalAmount(),
                Enums.SubledgerSource.ADVANCE, advance.getId(),
                "ADV-" + advance.getId().substring(0, 8), "adv-fund-" + advance.getId());

        BigDecimal prevOwed = creditLedgerRepo.findTopByAdvanceIdOrderByCreatedAtDesc(advance.getId())
                .map(LenderCreditLedgerEntry::getBalanceAfter)
                .orElse(BigDecimal.ZERO);
        BigDecimal newOwed = prevOwed.add(advance.getTotalRepayment()).setScale(2, RoundingMode.HALF_UP);
        saveCreditLedger(advance, Enums.LedgerEntryType.CREDIT, advance.getTotalRepayment(), newOwed);

        feeService.applyOnce(merchant, FeeService.CODE_ADVANCE_SERVICE, advance.getPrincipalAmount(),
                "advance/" + advance.getId());

        auditService.sim(actorId == null ? "system" : actorId, actorRole == null ? "LENDER" : actorRole,
                "advance_funded", "advance", advance.getId(),
                "proceed N$" + advance.getPrincipalAmount() + " confirmed by issuer; owed now N$" + newOwed, null);
        return new Dtos.Message("Advance funded - available balance increased by N$" + advance.getPrincipalAmount()
                + " (TC-ADV-02)", "SIMULATED lender disbursement");
    }

    private void saveCreditLedger(Advance advance, Enums.LedgerEntryType type, BigDecimal amount, BigDecimal balanceAfter) {
        LenderCreditLedgerEntry e = new LenderCreditLedgerEntry();
        e.setAdvanceId(advance.getId());
        e.setLenderId(advance.getLender().getId());
        e.setMerchantId(advance.getMerchant().getId());
        e.setEntryType(type);
        e.setAmount(amount);
        e.setBalanceAfter(balanceAfter);
        creditLedgerRepo.save(e);
    }

    // ------------------------------------------------------------------ arrears guard (C13, BC p.19)

    @Transactional
    public void checkArrearsPause(String lenderId) {
        BigDecimal owed = creditLedgerRepo.netOwedForLender(lenderId, Enums.LedgerEntryType.CREDIT);
        BigDecimal arrears = arrearsAmount(lenderId);
        BigDecimal trigger = defaultArrearsTrigger();
        if (owed.signum() > 0 && arrears.divide(owed, 4, RoundingMode.HALF_UP).compareTo(trigger) > 0) {
            throw new SimException("ARREARS_PAUSED",
                    "30-day arrears exceed " + trigger.multiply(new BigDecimal("100")).stripTrailingZeros()
                            + "% - new draws paused for this lender (BC p.19)");
        }
    }

    private BigDecimal defaultArrearsTrigger() {
        return new BigDecimal("0.05"); // seeded default limit profile (C10/C14)
    }

    /** 30-day arrears: active advances funded >30d ago with no repayment in 30 days. */
    @Transactional(readOnly = true)
    public BigDecimal arrearsAmount(String lenderId) {
        List<Advance> active = advanceRepo.activeForLender(lenderId, OWED_STATUSES);
        Instant cutoff = Instant.now().minusSeconds(30L * 24 * 3600);
        BigDecimal arrears = BigDecimal.ZERO;
        for (Advance advance : active) {
            BigDecimal remaining = remainingOwed(advance);
            if (remaining.signum() <= 0 || advance.getFundedAt() == null || advance.getFundedAt().isAfter(cutoff)) {
                continue;
            }
            Optional<AdvanceRepayment> last = repayRepo.findByAdvanceIdOrderByCreatedAtDesc(advance.getId())
                    .stream().findFirst();
            boolean noRecentRepayment = last.isEmpty() || last.get().getCreatedAt().isBefore(cutoff);
            if (noRecentRepayment) {
                arrears = arrears.add(remaining);
            }
        }
        return arrears.setScale(2, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ daily repayment sweep

    /**
     * Collect advance.repaymentRate of that day's eligible net receipts for every
     * active advance of the lender. Idempotent per (advance, day); zero-sales
     * days collect nothing (TC-ADV-05); refunds net against the base (TC-ADV-04).
     */
    @Transactional
    public List<Dtos.StepResult> sweep(String lenderId, LocalDate date) {
        Lender lender = lenderRepo.findById(lenderId)
                .orElseThrow(() -> new NotFoundException("Lender not found"));
        List<Advance> active = advanceRepo.activeForLender(lenderId, OWED_STATUSES);

        List<Dtos.StepResult> steps = new ArrayList<>();
        if (active.isEmpty()) {
            steps.add(new Dtos.StepResult("sweep " + date, true,
                    "No active advances - nothing to collect (zero-sales safe)"));
            return steps;
        }
        Instant from = date.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        for (Advance advance : active) {
            steps.add(sweepAdvance(advance, lender, date, from, to));
        }
        auditService.sim("system", "LENDER", "repayment_sweep", "lender", lenderId,
                "swept " + active.size() + " advance(s) for " + date, null);
        return steps;
    }

    @Transactional
    Dtos.StepResult sweepAdvance(Advance advance, Lender lender, LocalDate date, Instant from, Instant to) {
        // Pessimistic lock re-loads the advance: concurrent sweeps (and the concurrent
        // sale posting) serialise per advance, so the once-per-day check is race-free
        // (TC-RACE-05 - the day's collection is computed at most once).
        Advance locked = advanceRepo.findWithLockById(advance.getId()).orElseThrow();
        advance = locked;
        if (repayRepo.existsByAdvanceIdAndCollectionDate(advance.getId(), date)) {
            return new Dtos.StepResult("advance " + advance.getId().substring(0, 8), true,
                    "Sweep already applied for " + date + " (idempotent)");
        }

        Merchant merchant = advance.getMerchant();
        List<Payment> payments = paymentRepo.confirmedInRange(merchant.getId(),
                Enums.PaymentStatus.CONFIRMED, from, to);
        boolean excludeSelf = isRuleEnabled("exclude_self_transfer");
        BigDecimal base = payments.stream()
                .filter(p -> !excludeSelf || p.getPayerRef() == null || !p.getPayerRef().equals(merchant.getOwnerPhone()))
                .map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal refundTotal = refundRepo.findByMerchantIdOrderByCreatedAtDesc(merchant.getId()).stream()
                .filter(r -> r.getStatus() == Enums.RefundStatus.PROCESSED)
                .filter(r -> !r.getCreatedAt().isBefore(from) && r.getCreatedAt().isBefore(to))
                .map(Refund::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        base = base.subtract(refundTotal).max(BigDecimal.ZERO);

        BigDecimal remaining = remainingOwed(advance);
        if (remaining.signum() <= 0) {
            closeAdvance(advance);
            return new Dtos.StepResult("advance " + advance.getId().substring(0, 8), true,
                    "Already fully repaid; advance closed");
        }
        if (base.signum() == 0) {
            return new Dtos.StepResult("advance " + advance.getId().substring(0, 8), true,
                    "Zero eligible sales on " + date + " - no collection (TC-ADV-05)");
        }

        BigDecimal byRate = base.multiply(advance.getRepaymentRate()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal maxDaily = orZero(lender.getMaxDailyDeduction());
        BigDecimal deduction = maxDaily.signum() > 0 ? byRate.min(maxDaily) : byRate;
        if (deduction.signum() <= 0) {
            return new Dtos.StepResult("advance " + advance.getId().substring(0, 8), true,
                    "Deduction rounds to zero on " + date);
        }

        AdvanceRepayment r = new AdvanceRepayment();
        r.setAdvance(advance);
        r.setMerchantId(merchant.getId());
        r.setAmount(deduction);
        r.setEligibleSalesBase(base);
        r.setCollectionDate(date);
        repayRepo.save(r);

        BigDecimal prev = creditLedgerRepo.findTopByAdvanceIdOrderByCreatedAtDesc(advance.getId())
                .map(LenderCreditLedgerEntry::getBalanceAfter)
                .orElse(remaining.add(deduction));
        saveCreditLedger(advance, Enums.LedgerEntryType.DEBIT, deduction, prev.subtract(deduction).setScale(2, RoundingMode.HALF_UP));

        if (remaining.subtract(deduction).signum() <= 0) {
            closeAdvance(advance);
        } else if (advance.getStatus() == Enums.AdvanceStatus.FUNDED) {
            advance.setStatus(Enums.AdvanceStatus.REPAYING);
            advanceRepo.save(advance);
        }
        return new Dtos.StepResult("advance " + advance.getId().substring(0, 8), true,
                "Collected N$" + deduction + " of N$" + base + " eligible base (rate " + advance.getRepaymentRate() + ")");
    }

    @Transactional
    public void closeAdvance(Advance advance) {
        if (advance.getStatus() == Enums.AdvanceStatus.CLOSED) {
            return;
        }
        Enums.AdvanceStatus before = advance.getStatus();
        advance.setStatus(Enums.AdvanceStatus.CLOSED);
        advance.setClosedAt(Instant.now());
        advanceRepo.save(advance);

        arrearsCaseRepo.findByAdvanceId(advance.getId()).stream()
                .filter(c -> c.getStatus() == Enums.BreakStatus.OPEN)
                .forEach(c -> {
                    c.setStatus(Enums.BreakStatus.RESOLVED);
                    c.setResolvedAt(Instant.now());
                    arrearsCaseRepo.save(c);
                });
        auditService.record("system", "LENDER", "advance_closed", "advance", advance.getId(),
                before.name(), "CLOSED", "fully repaid", false, null);
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public BigDecimal remainingOwed(Advance advance) {
        BigDecimal repaid = repayRepo.repaidTotalByAdvance(advance.getId());
        return advance.getTotalRepayment().subtract(repaid).max(BigDecimal.ZERO);
    }

    /** Merchant advance tracker (M24/M25): current advance + collections. */
    @Transactional(readOnly = true)
    public Dtos.AdvanceDetailView currentAdvance(String merchantId) {
        List<Advance> all = advanceRepo.findByMerchantIdOrderByCreatedAtDesc(merchantId);
        Advance current = all.stream()
                .filter(a -> a.getStatus() != Enums.AdvanceStatus.CLOSED)
                .findFirst()
                .orElse(all.isEmpty() ? null : all.get(0));
        if (current == null) {
            return new Dtos.AdvanceDetailView(null, List.of());
        }
        Dtos.AdvanceStatusRow row = new Dtos.AdvanceStatusRow(current.getId(), current.getStatus(),
                orZero(current.getRequestedAmount()), current.getPrincipalAmount(), current.getTotalRepayment(),
                current.getRepaymentRate(), remainingOwed(current), current.getCreatedAt(), current.getFundedAt());
        List<Dtos.CollectionRow> repayments = repayRepo.findByAdvanceIdOrderByCreatedAtDesc(current.getId()).stream()
                .map(r -> new Dtos.CollectionRow(r.getId(), null, r.getAmount(), r.getEligibleSalesBase(),
                        r.getCollectionDate()))
                .toList();
        return new Dtos.AdvanceDetailView(row, repayments);
    }

    // ------------------------------------------------------------------ lender portfolio (L10-L12)

    @Transactional
    public Dtos.PortfolioView portfolio(String lenderId) {
        List<Dtos.LenderAdvanceRow> rows = advanceRepo.findForLender(lenderId);
        BigDecimal owed = creditLedgerRepo.netOwedForLender(lenderId, Enums.LedgerEntryType.CREDIT)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal arrears = arrearsAmount(lenderId);
        BigDecimal pct = owed.signum() == 0 ? BigDecimal.ZERO
                : arrears.divide(owed, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));

        List<Dtos.SnapshotRow> snapshots = snapshotRepo.findByLenderIdOrderBySnapshotDateDesc(lenderId).stream()
                .map(s -> new Dtos.SnapshotRow(s.getId(), s.getSnapshotDate(),
                        orZero(s.getOutstandingPrincipal()), orZero(s.getArrearsAmount())))
                .toList();
        List<Dtos.ArrearsRow> arrearsRows = arrearsCaseRepo.findByLenderIdOrderByOpenedAtDesc(lenderId).stream()
                .map(c -> new Dtos.ArrearsRow(c.getId(), c.getAdvanceId(), c.getDaysPastDue(),
                        c.getStatus().name()))
                .toList();
        return new Dtos.PortfolioView(sumPrincipal(rows), owed, arrears, pct, snapshots, arrearsRows);
    }

    private BigDecimal sumPrincipal(List<Dtos.LenderAdvanceRow> rows) {
        return rows.stream()
                .filter(r -> "FUNDED".equals(r.status()) || "REPAYING".equals(r.status())
                        || "ACCEPTED".equals(r.status()) || "OFFERED".equals(r.status()))
                .map(Dtos.LenderAdvanceRow::principal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** Snapshot + arrears cases + vintage analysis refresh (L10-L12, BC p.23). */
    @Transactional
    public Dtos.SnapshotRow snapshot(String lenderId) {
        Dtos.PortfolioView view = portfolio(lenderId);

        com.mipay.sme.domain.credit.PortfolioSnapshot snap = new com.mipay.sme.domain.credit.PortfolioSnapshot();
        snap.setLenderId(lenderId);
        snap.setSnapshotDate(LocalDate.now(ZoneOffset.UTC));
        snap.setOutstandingPrincipal(view.outstandingTotal());
        snap.setArrearsAmount(view.arrearsAmount());
        snapshotRepo.save(snap);

        // open arrears cases for offending advances (idempotent per advance)
        List<Advance> active = advanceRepo.activeForLender(lenderId, OWED_STATUSES);
        Instant cutoff = Instant.now().minusSeconds(30L * 24 * 3600);
        for (Advance advance : active) {
            BigDecimal remaining = remainingOwed(advance);
            boolean fundedLongAgo = advance.getFundedAt() != null && advance.getFundedAt().isBefore(cutoff);
            Optional<AdvanceRepayment> last = repayRepo.findByAdvanceIdOrderByCreatedAtDesc(advance.getId())
                    .stream().findFirst();
            boolean noRecentRepayment = last.isEmpty() || last.get().getCreatedAt().isBefore(cutoff);
            boolean inArrears = remaining.signum() > 0 && fundedLongAgo && noRecentRepayment;

            Optional<ArrearsCase> open = arrearsCaseRepo.findByAdvanceId(advance.getId()).stream()
                    .filter(c -> c.getStatus() == Enums.BreakStatus.OPEN)
                    .findFirst();
            if (inArrears && open.isEmpty()) {
                ArrearsCase c = new ArrearsCase();
                c.setAdvanceId(advance.getId());
                c.setLenderId(lenderId);
                c.setDaysPastDue(daysPastDue(advance, last));
                c.setStatus(Enums.BreakStatus.OPEN);
                arrearsCaseRepo.save(c);
            } else if (!inArrears && open.isPresent()) {
                open.get().setStatus(Enums.BreakStatus.RESOLVED);
                open.get().setResolvedAt(Instant.now());
                arrearsCaseRepo.save(open.get());
            }
        }

        vintage(lenderId);
        return new Dtos.SnapshotRow(snap.getId(), snap.getSnapshotDate(), snap.getOutstandingPrincipal(),
                snap.getArrearsAmount());
    }

    /** Loss by origination cohort (L12, BC p.23). */
    @Transactional
    public void vintage(String lenderId) {
        List<Advance> all = advanceRepo.findByLenderId(lenderId);
        Map<YearMonth, List<Advance>> cohorts = all.stream()
                .filter(a -> a.getFundedAt() != null)
                .collect(Collectors.groupingBy(a -> YearMonth.from(
                        a.getFundedAt().atZone(ZoneOffset.UTC).toLocalDate()),
                        LinkedHashMap::new, Collectors.toList()));
        vintageRepo.deleteAll();
        for (Map.Entry<YearMonth, List<Advance>> e : cohorts.entrySet()) {
            com.mipay.sme.domain.credit.VintageAnalysis v = new com.mipay.sme.domain.credit.VintageAnalysis();
            v.setLenderId(lenderId);
            v.setCohortMonth(e.getKey().atDay(1));
            BigDecimal originations = e.getValue().stream()
                    .map(Advance::getPrincipalAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal losses = e.getValue().stream()
                    .filter(a -> a.getStatus() == Enums.AdvanceStatus.DEFAULTED)
                    .map(Advance::getPrincipalAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            v.setOriginations(originations);
            v.setLosses(losses);
            v.setLossRate(originations.signum() == 0 ? BigDecimal.ZERO
                    : losses.divide(originations, 4, RoundingMode.HALF_UP));
            vintageRepo.save(v);
        }
    }

    private int daysPastDue(Advance advance, Optional<AdvanceRepayment> lastRepayment) {
        Instant since = lastRepayment.map(AdvanceRepayment::getCreatedAt)
                .orElseGet(() -> Optional.ofNullable(advance.getFundedAt()).orElse(Instant.now()));
        return Math.max(0, (int) java.time.Duration.between(since, Instant.now()).toDays());
    }

    // ------------------------------------------------------------------ helpers

    private Merchant approvedMerchant(String merchantId) {
        Merchant m = merchantRepo.findById(merchantId)
                .orElseThrow(() -> new NotFoundException("Merchant not found"));
        if (m.getApprovalStatus() != Enums.ApprovalStatus.APPROVED) {
            throw new SimException("MERCHANT_NOT_APPROVED", "Merchant must be approved before applying (UAT SB p.8)");
        }
        return m;
    }

    private BigDecimal min(BigDecimal a, BigDecimal b, BigDecimal c) {
        return a.min(b).min(c);
    }

    private BigDecimal orZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private BigDecimal orRate(BigDecimal v) {
        return v == null ? props.getAdvance().getDefaultRate() : v;
    }
}
