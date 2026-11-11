package com.mipay.sme.web;

import com.mipay.sme.common.ForbiddenException;
import com.mipay.sme.common.NotFoundException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.credit.Advance;
import com.mipay.sme.repository.AdvanceRepaymentRepository;
import com.mipay.sme.repository.AdvanceRepository;
import com.mipay.sme.repository.AmlAlertRepository;
import com.mipay.sme.repository.CreditAssessmentRepository;
import com.mipay.sme.repository.CreditConsentRepository;
import com.mipay.sme.repository.LenderCreditLedgerRepository;
import com.mipay.sme.security.Current;
import com.mipay.sme.security.rbac.Perm;
import com.mipay.sme.security.rbac.TenantSource;
import com.mipay.sme.security.rbac.Verb;
import com.mipay.sme.service.AdvanceService;
import com.mipay.sme.service.PaymentService;
import com.mipay.sme.web.Dtos;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Lender web portal API (L01-L07). All merchant-level data is consent-gated:
 * without an ACTIVE consent for THIS lender, the records view is refused (BC p.12).
 */
@RestController
@RequestMapping("/api/lender")
@RequiredArgsConstructor
public class LenderController {

    private final AdvanceService advanceService;
    private final AdvanceRepository advanceRepo;
    private final AdvanceRepaymentRepository repayRepo;
    private final CreditConsentRepository consentRepo;
    private final CreditAssessmentRepository assessmentRepo;
    private final LenderCreditLedgerRepository creditLedgerRepo;
    private final AmlAlertRepository amlRepo;
    private final PaymentService paymentService;
    private final com.mipay.sme.repository.PaymentRepository paymentRepo;
    private final com.mipay.sme.repository.RefundRepository refundRepo;
    private final com.mipay.sme.repository.MerchantRepository merchantRepo;
    private final com.mipay.sme.repository.InvoicePaymentRepository invoicePaymentRepo;
    private final com.mipay.sme.service.AuditService auditService;

    private String lid() {
        return Current.lenderId();
    }

    /** L02: consent queue (merchants sharing records with this lender). */
    @Perm(resource = "lender.advance", verb = Verb.READ, tenantSource = TenantSource.SELF, tenant = "null",
          description = "List active consents for this lender")
    @GetMapping("/consents")
    public List<Dtos.ConsentRow> consents() {
        return consentRepo.consentsForLender(lid(), Enums.ConsentStatus.ACTIVE);
    }

    /** L03: verified records view - consent-gated (TC-REC-02/03, BC p.12). */
    @Perm(resource = "lender.assessment", verb = Verb.READ, tenantSource = TenantSource.SELF, tenant = "null",
          description = "View consent-gated merchant records for this lender")
    @GetMapping("/records/{merchantId}")
    public Dtos.MerchantRecordsView records(@PathVariable String merchantId) {
        boolean consented = consentRepo.existsByMerchantIdAndLenderIdAndStatus(
                merchantId, lid(), Enums.ConsentStatus.ACTIVE);
        if (!consented) {
            throw new ForbiddenException("No active consent for this lender - records withheld (BC p.12)");
        }
        var merchant = merchantRepo.findById(merchantId)
                .orElseThrow(() -> new NotFoundException("Merchant not found"));

        LocalDate windowEnd = LocalDate.now(ZoneOffset.UTC);
        LocalDate windowStart = windowEnd.minusDays(90);
        Instant from = windowStart.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = windowEnd.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<com.mipay.sme.domain.payments.Payment> payments =
                paymentRepo.confirmedInRange(merchantId, Enums.PaymentStatus.CONFIRMED, from, to);
        List<com.mipay.sme.domain.payments.Refund> refunds =
                refundRepo.findByMerchantIdOrderByCreatedAtDesc(merchantId).stream()
                        .filter(r -> !r.getCreatedAt().isBefore(from) && r.getCreatedAt().isBefore(to))
                        .toList();

        // seasonality in one pass over already-fetched rows
        Map<YearMonth, BigDecimal> monthly = new LinkedHashMap<>();
        for (var p : payments) {
            monthly.merge(YearMonth.from(p.getCreatedAt().atZone(ZoneOffset.UTC)), p.getAmount(), BigDecimal::add);
        }
        for (var r : refunds) {
            monthly.merge(YearMonth.from(r.getCreatedAt().atZone(ZoneOffset.UTC)), r.getAmount().negate(), BigDecimal::add);
        }
        List<Dtos.MonthTotal> monthlyRows = monthly.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new Dtos.MonthTotal(e.getKey().toString(), e.getValue()))
                .toList();

        long days = Math.max(30, windowEnd.toEpochDay() - windowStart.toEpochDay());
        BigDecimal eligibleTotal = monthlyRows.stream()
                .map(Dtos.MonthTotal::netReceipts)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avgMonthly = eligibleTotal.multiply(new BigDecimal("30"))
                .divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP);
        var tradingDays = payments.stream()
                .map(p -> p.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate())
                .distinct()
                .count();

        return new Dtos.MerchantRecordsView(merchant.getBusinessName(), windowStart, windowEnd,
                eligibleTotal, (int) tradingDays, avgMonthly, monthlyRows);
    }

    /** L04: assessment run (lender decision API is simulated in the sandbox). */
    @Perm(resource = "lender.assessment", verb = Verb.EXECUTE, tenantSource = TenantSource.SELF, tenant = "null",
          description = "Run a credit assessment for a merchant")
    @PostMapping("/assess")
    public Dtos.AssessmentView assess(@Valid @RequestBody Dtos.AssessRequest req) {
        return advanceService.requestAssessment(req.merchantId(), lid(), null);
    }

    /** Assessments history for the lender. */
    @Perm(resource = "lender.assessment", verb = Verb.READ, tenantSource = TenantSource.SELF, tenant = "null",
          description = "List credit assessments for this lender")
    @GetMapping("/assessments")
    public List<com.mipay.sme.domain.credit.CreditAssessment> assessments() {
        return assessmentRepo.findByLenderIdOrderByCreatedAtDesc(lid());
    }

    /** L04: explicit decision override on the pending assessment/offer. */
    @Perm(resource = "lender.advance", verb = Verb.AUTHORIZE, tenantSource = TenantSource.SELF, tenant = "null",
          description = "Decline an offered advance")
    @PostMapping("/advances/{id}/decline")
    public Dtos.Message declineOffer(@PathVariable String id) {
        Advance advance = advanceRepo.findById(id).orElseThrow(() -> new NotFoundException("Advance not found"));
        if (!advance.getLender().getId().equals(lid())) {
            throw new NotFoundException("Advance not found");
        }
        if (advance.getStatus() != Enums.AdvanceStatus.OFFERED) {
            throw new com.mipay.sme.common.SimException("ADVANCE_STATE", "Only offered advances can be declined");
        }
        advanceRepo.delete(advance);
        Current.Principal actor = Current.get();
        auditService.record(actor.userId(), "LENDER", "advance_offer_declined", "advance", id,
                "OFFERED", "REMOVED", "merchant can re-apply after new records", false, null);
        return new Dtos.Message("Offer declined and withdrawn", null);
    }

    /** L05: disbursement - lender transfers capital; issuer confirms (TC-ADV-02). */
    @Perm(resource = "lender.advance", verb = Verb.AUTHORIZE, tenantSource = TenantSource.SELF, tenant = "null",
          description = "Fund (disburse) an accepted advance")
    @PostMapping("/advances/{id}/fund")
    public Dtos.Message fund(@PathVariable String id) {
        com.mipay.sme.security.MoneyGuard.requireAdmin(com.mipay.sme.security.Current.get());
        Advance advance = advanceRepo.findById(id).orElseThrow(() -> new NotFoundException("Advance not found"));
        if (!advance.getLender().getId().equals(lid())) {
            throw new NotFoundException("Advance not found");
        }
        Current.Principal actor = Current.get();
        return advanceService.fund(id, actor.userId(), actor.role());
    }

    /** L10: portfolio with merchant names, outstanding and arrears. */
    @Perm(resource = "lender.portfolio", verb = Verb.READ, tenantSource = TenantSource.SELF, tenant = "null",
          description = "View lender's portfolio with merchant names, outstanding and arrears")
    @GetMapping("/portfolio")
    public Dtos.PortfolioView portfolio() {
        return advanceService.portfolio(lid());
    }

    @Perm(resource = "lender.portfolio", verb = Verb.EXECUTE, tenantSource = TenantSource.SELF, tenant = "null",
          description = "Take a portfolio snapshot")
    @PostMapping("/portfolio/snapshot")
    public Dtos.SnapshotRow snapshot() {
        return advanceService.snapshot(lid());
    }

    /** L13: disbursement history. */
    @Perm(resource = "lender.advance", verb = Verb.READ, tenantSource = TenantSource.SELF, tenant = "null",
          description = "List advances for this lender")
    @GetMapping("/advances")
    public List<Dtos.LenderAdvanceRow> advances() {
        return advanceRepo.findForLender(lid());
    }

    /** L06: collections view. */
    @Perm(resource = "lender.collection", verb = Verb.READ, tenantSource = TenantSource.SELF, tenant = "null",
          description = "List collections for this lender")
    @GetMapping("/collections")
    public List<Dtos.CollectionRow> collections() {
        return repayRepo.findForLender(lid());
    }

    /** L06: run the daily sweep for a date (collections engine). */
    @Perm(resource = "lender.collection", verb = Verb.EXECUTE, tenantSource = TenantSource.SELF, tenant = "null",
          description = "Run the daily collections sweep")
    @PostMapping("/sweep")
    public List<Dtos.StepResult> sweep(@RequestBody(required = false) Dtos.SweepRequest req) {
        com.mipay.sme.security.MoneyGuard.requireAdmin(com.mipay.sme.security.Current.get());
        LocalDate date = req == null || req.date() == null || req.date().isBlank()
                ? LocalDate.now(ZoneOffset.UTC)
                : LocalDate.parse(req.date());
        return advanceService.sweep(lid(), date);
    }
}
