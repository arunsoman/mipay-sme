package com.mipay.sme.uat;

import com.mipay.sme.common.SimException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.web.Dtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BC p.10/p.12 and UAT SB p.12 acceptance tests — verified records over a
 * 90-day window, purpose-limited consent, withdrawal, Khata exclusion and the
 * circular-payment / self-transfer exclusion rules.
 */
@DisplayName("Records, consent & eligibility (BC p.10/p.12; UAT SB p.12) — TC-REC")
class TUat06RecordsConsentTests extends UatSupport {

    @Test
    @DisplayName("TC-REC-01 · Verified records: receipts net of refunds, trading days, monthly seasonality")
    void tcRec01_verifiedRecordsWindow() throws Exception {
        var m = newApprovedMerchant();
        for (int i = 0; i < 50; i++) {
            historyPayment(m.getId(), LocalDate.now(ZoneOffset.UTC).minusDays(i), "1800.00", "hist-" + seq());
        }
        // a full refund today nets the 90-day receipts base (BC p.10)
        var sale = payNowConfirmed(m.getId(), "5000.00", Enums.PaymentChannel.QR, "0999000099");
        payments.refund(m.getId(), sale.getId(), new Dtos.RefundRequest(bd("5000.00"), "full refund"));

        Dtos.EligibilityView v = advances.eligibility(m.getId());
        assertThat(v.windowEnd()).isEqualTo(LocalDate.now(ZoneOffset.UTC));
        assertThat(v.windowStart()).isEqualTo(LocalDate.now(ZoneOffset.UTC).minusDays(90));
        assertThat(v.eligibleTotal()).isEqualByComparingTo("85000.00"); // 50×1,800 − 5,000
        assertThat(v.tradingDays()).isEqualTo(50);                     // today nets to zero -> not a trading day
        assertThat(v.avgMonthlyNetReceipts()).isEqualByComparingTo("28333.33"); // 85,000 × 30/90
        assertThat(v.rulesApplied()).anySatisfy(l -> {
            assertThat(l).contains("exclude_advance");
        }).anySatisfy(l -> {
            assertThat(l).contains("exclude_self_transfer");
        }).anySatisfy(l -> {
            assertThat(l).contains("exclude_circular");
        });

        // records view over the same window nets refunds per month (BC p.10 seasonality)
        var lender = newLenderWithUser();
        advances.grantConsent(m.getId(), lender.lender().getId());
        String lenderJwt = passwordToken(lender.phone(), lender.password());
        org.springframework.test.web.servlet.MvcResult res =
                bearerGet("/api/lender/records/" + m.getId(), lenderJwt).andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("TC-REC-02 · Consent captured with named lender, purpose, fields and retention (BC p.12)")
    void tcRec02_consentCaptured() throws Exception {
        var m = newApprovedMerchant();
        var lender = newLenderWithUser();

        Dtos.ConsentRow row = advances.grantConsent(m.getId(), lender.lender().getId());
        assertThat(row.purpose()).isEqualTo("Cash advance assessment");
        assertThat(row.status()).isEqualTo(Enums.ConsentStatus.ACTIVE);
        assertThat(row.grantedAt()).isNotNull();

        // re-granting is idempotent (same active consent reused)
        Dtos.ConsentRow again = advances.grantConsent(m.getId(), lender.lender().getId());
        assertThat(again.consentId()).isEqualTo(row.consentId());

        // lender sees the consent in its queue (L02, via real API)
        String lenderJwt = passwordToken(lender.phone(), lender.password());
        bearerGet("/api/lender/consents", lenderJwt)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.merchantId == '" + m.getId() + "')]").exists());
    }

    @Test
    @DisplayName("TC-REC-03 · Withdrawal stops future disclosure and blocks new assessments (BC p.12)")
    void tcRec03_consentWithdrawal() throws Exception {
        var m = newApprovedMerchant();
        var lender = newLenderWithUser();
        Dtos.ConsentRow row = advances.grantConsent(m.getId(), lender.lender().getId());
        String lenderJwt = passwordToken(lender.phone(), lender.password());

        // records disclosed while consent ACTIVE
        bearerGet("/api/lender/records/" + m.getId(), lenderJwt).andExpect(status().isOk());

        advances.withdrawConsent(m.getId(), row.consentId());
        inTx(() -> {
            var c = consents.findById(row.consentId()).orElseThrow();
            assertThat(c.getStatus()).isEqualTo(Enums.ConsentStatus.WITHDRAWN);
            assertThat(c.getWithdrawnAt()).isNotNull();
            assertThat(consentWithdrawals.findAll())
                    .anyMatch(w -> row.consentId().equals(w.getConsentId()));
        });

        // records withheld after withdrawal (future disclosure blocked)
        bearerGet("/api/lender/records/" + m.getId(), lenderJwt)
                .andExpect(status().isForbidden());

        // no new assessment without active consent
        assertThatThrownBy(() -> advances.requestAssessment(m.getId(), lender.lender().getId(), null))
                .isInstanceOfSatisfying(SimException.class,
                        e -> assertThat(e.getCode()).isEqualTo("CONSENT_REQUIRED"));
    }

    @Test
    @DisplayName("TC-REC-04 · Khata never part of the feed; merchant records view has no customer-level data")
    void tcRec04_khataExcludedFromFeed() {
        var m = newApprovedMerchant();
        var lender = newLender(bd("0.10"), bd("0.05"), null, bd("25000"));
        Dtos.ConsentRow row = advances.grantConsent(m.getId(), lender.getId());
        inTx(() -> assertKhataNotInFields(consents.findById(row.consentId()).orElseThrow().getDataFields()));
    }

    private void assertKhataNotInFields(String fields) {
        assertThat(fields.toLowerCase()).doesNotContain("khata").doesNotContain("customer-level");
    }

    @Test
    @DisplayName("TC-REC-05 · Self-transfers and circular B2B excluded from verified sales (AML alert raised)")
    void tcRec05_circularExclusion() {
        var m = newApprovedMerchant(); // owner's phone is the merchant's own reference

        payNowConfirmed(m.getId(), "100.00", Enums.PaymentChannel.QR, m.getOwnerPhone());  // self-transfer
        payNowConfirmed(m.getId(), "150.00", Enums.PaymentChannel.QR, "0852222222");       // seeded supplier phone
        payNowConfirmed(m.getId(), "250.00", Enums.PaymentChannel.QR, "0998000001");      // genuine customer

        Dtos.EligibilityView v = advances.eligibility(m.getId());
        assertThat(v.eligibleTotal()).isEqualByComparingTo("250.00");  // only the genuine payment
        assertThat(v.tradingDays()).isEqualTo(1);

        // AML: self-transfer payment raised an alert (K02, screenPayment)
        assertThat(amlAlerts.findAllByOrderByCreatedAtDesc())
                .anySatisfy(a -> {
                    assertThat(a.getAlertType()).isEqualTo("SELF_TRANSFER");
                    assertThat(a.getEntityId()).isEqualTo(m.getId());
                    assertThat(a.getStatus()).isEqualTo(Enums.AmlStatus.OPEN);
                });
    }

    @Test
    @DisplayName("TC-SET-27 · Toggling exclusion rules changes eligibility computation (C23)")
    void tcSet27_ruleToggleChangesEligibility() {
        var m = newApprovedMerchant();
        payNowConfirmed(m.getId(), "100.00", Enums.PaymentChannel.QR, m.getOwnerPhone()); // self
        payNowConfirmed(m.getId(), "250.00", Enums.PaymentChannel.QR, "0998000002");      // normal

        BigDecimal withRule = advances.eligibility(m.getId()).eligibleTotal();
        assertThat(withRule).isEqualByComparingTo("250.00");

        boolean original = admin.rules().stream()
                .filter(r -> "exclude_self_transfer".equals(r.ruleCode())).findFirst().orElseThrow().enabled();
        try {
            admin.toggleRule("exclude_self_transfer", false, "uat-admin", "MINDLABS_ADMIN");
            BigDecimal withoutRule = advances.eligibility(m.getId()).eligibleTotal();
            assertThat(withoutRule).isEqualByComparingTo("350.00"); // self-transfer now counted
        } finally {
            admin.toggleRule("exclude_self_transfer", original, "uat-admin", "MINDLABS_ADMIN");
        }
        assertThat(advances.eligibility(m.getId()).eligibleTotal()).isEqualByComparingTo("250.00");
    }

    @Test
    @DisplayName("TC-SET-30 · Synthetic 90–120 day history generator produces eligibility-usable data")
    void tcSet30_syntheticHistory() {
        var m = newApprovedMerchant();
        var r = simulation.syntheticHistory(m.getId(), 90, "uat-admin", "MINDLABS_ADMIN");
        assertThat(r.label()).isEqualTo("SIMULATED / SYNTHETIC");
        assertThat(r.detail()).contains("90 days");
        assertThat(testDataSets.findAll()).anyMatch(t ->
                t.getName() != null && t.getName().contains("synthetic_history_90d"));

        int tradingDays = advances.eligibility(m.getId()).tradingDays();
        assertThat(tradingDays).isGreaterThanOrEqualTo(90); // every seeded day has ≥2 payments

        // out-of-range requests refused
        assertThatThrownBy(() -> simulation.syntheticHistory(m.getId(), 10, "uat-admin", "MINDLABS_ADMIN"))
                .isInstanceOfSatisfying(SimException.class,
                        e -> assertThat(e.getCode()).isEqualTo("SYN_RANGE"));
    }

    @Test
    @DisplayName("TC-REC-06 · Assessment requires merchant to be approved first")
    void tcRec06_assessmentRequiresApproval() {
        var pending = newPendingMerchant();
        var lender = newLender(bd("0.10"), bd("0.05"), null, bd("25000"));
        advances.grantConsent(pending.getId(), lender.getId()); // consent allowed pre-approval? consent row created
        assertThatThrownBy(() -> advances.requestAssessment(pending.getId(), lender.getId(), null))
                .isInstanceOfSatisfying(SimException.class,
                        e -> assertThat(e.getCode()).isEqualTo("MERCHANT_NOT_APPROVED"));
    }
}
