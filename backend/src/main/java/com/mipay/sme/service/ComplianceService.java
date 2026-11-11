package com.mipay.sme.service;

import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.compliance.AmlAlert;
import com.mipay.sme.domain.compliance.DsarRequest;
import com.mipay.sme.domain.compliance.KycCheck;
import com.mipay.sme.domain.compliance.SarReport;
import com.mipay.sme.domain.core.Merchant;
import com.mipay.sme.domain.payments.Payment;
import com.mipay.sme.repository.AmlAlertRepository;
import com.mipay.sme.repository.DsarRequestRepository;
import com.mipay.sme.repository.KycCheckRepository;
import com.mipay.sme.repository.SarReportRepository;
import com.mipay.sme.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.mipay.sme.web.Dtos;

/**
 * KYC/AML/sanctions/monitoring/compliance services (K01-K05, BC p.10/p.28).
 * Alerts are raised on self-transfers and abnormal velocity; the advance
 * assessment also consults the same rules for circular payment exclusion.
 */
@Service
@RequiredArgsConstructor
public class ComplianceService {

    public static final long VELOCITY_THRESHOLD_PER_DAY = 10;

    private final KycCheckRepository kycRepo;
    private final AmlAlertRepository amlRepo;
    private final SarReportRepository sarRepo;
    private final DsarRequestRepository dsarRepo;
    private final PaymentRepository paymentRepo;
    private final com.mipay.sme.repository.InvoiceRepository invoiceRepo;
    private final com.mipay.sme.repository.CreditConsentRepository consentRepo;
    private final com.mipay.sme.repository.WithdrawalRepository withdrawalRepo;
    private final com.mipay.sme.repository.StatementRepository statementRepo;
    private final com.mipay.sme.repository.MerchantRepository merchantRepo;
    private final AuditService auditService;

    @Transactional
    public KycCheck recordCheck(String entityType, String entityId, Enums.KycCheckType type,
                                Enums.KycResult result, String details) {
        KycCheck check = new KycCheck();
        check.setEntityType(entityType);
        check.setEntityId(entityId);
        check.setCheckType(type);
        check.setResult(result);
        check.setDetails(details);
        return kycRepo.save(check);
    }

    /** Read model for the ops KYC panel (DTO-only; one query, no lazy fields). */
    @Transactional
    public List<Dtos.KycCheckRow> kycChecks(String entityType, String entityId) {
        return kycRepo.findByEntityTypeAndEntityIdOrderByCheckedAtDesc(entityType, entityId).stream()
                .map(k -> new Dtos.KycCheckRow(k.getId(), k.getEntityType(), k.getEntityId(),
                        k.getCheckType().name(), k.getResult().name(), k.getDetails(), k.getCheckedAt()))
                .toList();
    }

    /** Transaction monitoring on every accepted payment (K02). */
    @Transactional
    public String screenPayment(Merchant merchant, Payment payment) {
        if (payment.getPayerRef() != null
                && merchant.getOwnerPhone() != null
                && payment.getPayerRef().equals(merchant.getOwnerPhone())) {
            return raise("SELF_TRANSFER", "merchant", merchant.getId(), payment.getId(),
                    "Payer phone equals merchant owner phone; circular/self-transfer excluded from eligibility (BC p.10)");
        }
        long today = paymentRepo.countByMerchantIdAndCreatedAtAfter(merchant.getId(),
                Instant.now().minus(Duration.ofHours(24)));
        if (today > VELOCITY_THRESHOLD_PER_DAY) {
            return raise("VELOCITY", "merchant", merchant.getId(), payment.getId(),
                    today + " payments in 24h exceeds threshold " + VELOCITY_THRESHOLD_PER_DAY);
        }
        return null;
    }

    @Transactional
    public String raise(String alertType, String entityType, String entityId, String referenceId, String details) {
        AmlAlert alert = new AmlAlert();
        alert.setAlertType(alertType);
        alert.setEntityType(entityType);
        alert.setEntityId(entityId);
        alert.setReferenceId(referenceId);
        alert.setDetails(details);
        alert.setStatus(Enums.AmlStatus.OPEN);
        amlRepo.save(alert);
        auditService.record("system", "MACHINE", "aml_alert_raised", "aml_alert", alert.getId(), null, null, alertType, false, null);
        return alert.getId();
    }

    @Transactional
    public Dtos.AmlAlertRow closeAlert(String alertId) {
        AmlAlert alert = amlRepo.findById(alertId).orElseThrow(() -> new com.mipay.sme.common.NotFoundException("Alert not found"));
        alert.setStatus(Enums.AmlStatus.CLOSED);
        alert.setClosedAt(Instant.now());
        amlRepo.save(alert);
        return new Dtos.AmlAlertRow(alert.getId(), alert.getAlertType(), alert.getEntityType(), alert.getEntityId(),
                alert.getDetails(), alert.getStatus().name(), alert.getCreatedAt());
    }

    // ------------------------------------------------------------------ SAR

    @Transactional
    public Dtos.SarRow createSar(String alertId, String details, String actorId) {
        SarReport sar = new SarReport();
        sar.setAlertId(alertId);
        sar.setReference("SAR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        sar.setDetails(details);
        sar.setStatus(Enums.SarStatus.DRAFT);
        sar.setReportedBy(actorId);
        sarRepo.save(sar);
        auditService.record(actorId, "MINDLABS_ADMIN", "sar_draft", "sar", sar.getId(), null, null, null, false, null);
        return toRow(sar);
    }

    @Transactional
    public Dtos.SarRow submitSar(String sarId, String actorId) {
        SarReport sar = sarRepo.findById(sarId).orElseThrow(() -> new com.mipay.sme.common.NotFoundException("SAR not found"));
        sar.setStatus(Enums.SarStatus.SUBMITTED);
        sar.setSubmittedAt(Instant.now());
        sarRepo.save(sar);
        auditService.record(actorId, "MINDLABS_ADMIN", "sar_submitted", "sar", sar.getId(), null, null, null, false, null);
        return toRow(sar);
    }

    private Dtos.SarRow toRow(SarReport sar) {
        return new Dtos.SarRow(sar.getId(), sar.getAlertId(), sar.getReference(), sar.getStatus().name(),
                sar.getSubmittedAt(), sar.getCreatedAt());
    }

    // ------------------------------------------------------------------ DSAR

    @Transactional
    public Dtos.DsarRow createDsar(String merchantId, String actorId) {
        DsarRequest dsar = new DsarRequest();
        dsar.setMerchantId(merchantId);
        dsar.setRequestedBy(actorId);
        dsarRepo.save(dsar);
        return new Dtos.DsarRow(dsar.getId(), dsar.getMerchantId(), dsar.getStatus().name(),
                dsar.getRequestedAt(), dsar.getCompletedAt());
    }

    /** Data subject export (K04): aggregates only - never customer-level Khata (BC p.12). */
    @Transactional
    public Dtos.DsarExport exportDsar(String dsarId, LedgerService ledgerService) {
        DsarRequest dsar = dsarRepo.findById(dsarId)
                .orElseThrow(() -> new com.mipay.sme.common.NotFoundException("DSAR not found"));
        String mid = dsar.getMerchantId();
        Map<String, Object> sections = new LinkedHashMap<>();
        sections.put("balance", ledgerService.balanceView(mid));
        sections.put("payments", paymentRepo.findByMerchantIdOrderByCreatedAtDesc(mid,
                org.springframework.data.domain.PageRequest.of(0, 200)).getContent().stream()
                .map(p -> Map.of("id", p.getId(), "channel", p.getChannel() == null ? "?" : p.getChannel().name(),
                        "amount", p.getAmount(), "status", p.getStatus().name(), "at", String.valueOf(p.getCreatedAt())))
                .toList());
        sections.put("invoices", invoiceRepo.findForMerchant(mid, List.of(Enums.InvoiceStatus.values())));
        sections.put("consents", consentRepo.findByMerchantIdOrderByGrantedAtDesc(mid));
        sections.put("withdrawals", withdrawalRepo.findByMerchantIdOrderByRequestedAtDesc(mid,
                org.springframework.data.domain.PageRequest.of(0, 100)).getContent());
        sections.put("statements", statementRepo.findByOwnerTypeAndOwnerIdOrderByGeneratedAtDesc("merchant", mid));
        dsar.setStatus(Enums.DsarStatus.COMPLETED);
        dsar.setCompletedAt(Instant.now());
        dsarRepo.save(dsar);
        String businessName = merchantRepo.findById(mid)
                .map(com.mipay.sme.domain.core.Merchant::getBusinessName).orElse(mid);
        return new Dtos.DsarExport(dsar.getId(), mid, businessName, sections);
    }

    // ------------------------------------------------------------------ reads for admin console

    @Transactional(readOnly = true)
    public List<Dtos.AmlAlertRow> amlAlerts() {
        return amlRepo.findAllByOrderByCreatedAtDesc().stream().map(a -> new Dtos.AmlAlertRow(a.getId(),
                a.getAlertType(), a.getEntityType(), a.getEntityId(), a.getDetails(),
                a.getStatus() == null ? "OPEN" : a.getStatus().name(), a.getCreatedAt())).toList();
    }

    @Transactional(readOnly = true)
    public List<Dtos.SarRow> sars() {
        return sarRepo.findAllByOrderByCreatedAtDesc().stream().map(this::toRow).toList();
    }

    @Transactional(readOnly = true)
    public List<Dtos.DsarRow> dsars() {
        return dsarRepo.findAllByOrderByRequestedAtDesc().stream()
                .map(d -> new Dtos.DsarRow(d.getId(), d.getMerchantId(), d.getStatus().name(),
                        d.getRequestedAt(), d.getCompletedAt()))
                .toList();
    }


    /**
     * Risk rating + review cycle (KYC spec §5): shared by the legacy approval and
     * the tree decision flow (moved here per the hierarchy plan).
     */
    @Transactional
    public void applyRiskRating(com.mipay.sme.domain.core.Merchant m, Enums.KycResult sanctions,
                                String actorId, String actorRole) {
        boolean manualSanctions = sanctions == Enums.KycResult.MANUAL_REVIEW;
        String type = (m.getBusinessType() == null ? "" : m.getBusinessType().toLowerCase());
        boolean cashHeavy = type.contains("cash") || type.contains("gambling") || type.contains("forex");
        Enums.KycRiskRating rating = manualSanctions || cashHeavy ? Enums.KycRiskRating.HIGH
                : type.isBlank() ? Enums.KycRiskRating.MEDIUM : Enums.KycRiskRating.LOW;
        m.setKycRiskRating(rating);
        int cycleMonths = rating == Enums.KycRiskRating.HIGH ? 12 : rating == Enums.KycRiskRating.MEDIUM ? 24 : 36;
        m.setKycNextReviewDate(java.time.LocalDate.now().plusMonths(cycleMonths));
        auditService.record(actorId, actorRole, "kyc_risk_rated", "merchant", m.getId(),
                null, rating.name(), "review cycle " + cycleMonths + "m (KYC spec §5)", false, null);
    }
}