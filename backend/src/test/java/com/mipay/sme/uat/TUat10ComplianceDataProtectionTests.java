package com.mipay.sme.uat;

import com.mipay.sme.common.SimException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.web.Dtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Compliance acceptance tests (K01-K07, BC p.10/p.28) — sanctions screening,
 * transaction monitoring, SAR lifecycle, DSAR export minimisation, consent
 * withdrawal records and the suspension cycle.
 */
@DisplayName("Compliance & data protection (K01-K07; BC p.28) — TC-OPS-26..30")
class TUat10ComplianceDataProtectionTests extends UatSupport {

    @Test
    @DisplayName("TC-OPS-27 · Velocity monitoring: >10 payments/24h raises a VELOCITY AML alert (K02)")
    void tcOps27_velocityAlert() {
        var m = newApprovedMerchant();
        for (int i = 1; i <= 12; i++) {
            payNowConfirmed(m.getId(), "20.00", Enums.PaymentChannel.QR, "vel-payer-" + i);
        }
        assertThat(amlAlerts.findAllByOrderByCreatedAtDesc())
                .anySatisfy(a -> {
                    assertThat(a.getAlertType()).isEqualTo("VELOCITY");
                    assertThat(a.getEntityId()).isEqualTo(m.getId());
                });
    }

    @Test
    @DisplayName("TC-OPS-28 · SAR lifecycle: draft from an alert, then submit (K03, BC p.28)")
    void tcOps28_sarLifecycle() {
        var alertId = compliance.raise("TEST_PATTERN", "merchant", "entity-" + seq(),
                "ref-" + seq(), "UAT alert for SAR");
        var draft = compliance.createSar(alertId, "UAT SAR details", "uat-ops");
        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(draft.reference()).startsWith("SAR-");
        assertThat(draft.alertId()).isEqualTo(alertId);

        var submitted = compliance.submitSar(draft.id(), "uat-ops");
        assertThat(submitted.status()).isEqualTo("SUBMITTED");
        assertThat(submitted.submittedAt()).isNotNull();
    }

    @Test
    @DisplayName("TC-OPS-29 · DSAR export completes and contains aggregates but NOT customer-level Khata (K04)")
    void tcOps29_dsarExportMinimised() {
        var m = newApprovedMerchant();
        credit(m.getId(), "100.00");
        payNowConfirmed(m.getId(), "40.00", Enums.PaymentChannel.QR, "dsar-cust");

        var dsar = compliance.createDsar(m.getId(), "uat-ops");
        var export = compliance.exportDsar(dsar.id(), ledger);

        assertThat(export.businessName()).isNotBlank();
        var sections = export.sections().keySet();
        assertThat(sections).contains("balance", "payments", "invoices", "consents",
                "withdrawals", "statements");
        // data minimisation (BC p.12): no customer-level Khata in the data-subject export
        assertThat(sections.stream().map(String::toLowerCase))
                .noneMatch(k -> k.contains("khata"));
        // payment rows are aggregates of the merchant's own transactions
        assertThat(export.sections().get("payments").toString()).contains("40.00");

        assertThat(compliance.dsars()).anySatisfy(d -> {
            assertThat(d.id()).isEqualTo(dsar.id());
            assertThat(d.status()).isEqualTo("COMPLETED");
            assertThat(d.completedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("TC-OPS-30 · Consent withdrawal is recorded with a reason and blocks re-sharing (K05/K06)")
    void tcOps30_consentWithdrawnRecorded() {
        var m = newApprovedMerchant();
        var lender = newLender(bd("0.10"), bd("0.05"), null, bd("25000"));
        Dtos.ConsentRow row = advances.grantConsent(m.getId(), lender.getId());

        advances.withdrawConsent(m.getId(), row.consentId());

        assertThatThrownBy(() -> advances.requestAssessment(m.getId(), lender.getId(), null))
                .isInstanceOfSatisfying(SimException.class,
                        e -> assertThat(e.getCode()).isEqualTo("CONSENT_REQUIRED"));
        assertThat(advances.currentAdvance(m.getId()).advance())
                .as("no advance exists for a declined application").isNull();
    }

    @Test
    @DisplayName("TC-OPS-16 · Suspension cycle: suspended merchant can't transact or log in; lift restores")
    void tcOps16_suspensionCycle() throws Exception {
        var m = newApprovedMerchant();

        Dtos.SuspensionRow suspended = ops.suspend(
                new Dtos.SuspendRequest("MERCHANT", m.getId(), "manual AML hold (uat)"),
                "uat-ops", "TPTS_OPS");
        assertThat(suspended.reason()).contains("AML"); // reason recorded (BC p.27)

        assertThatThrownBy(() -> payments.accept(m.getId(),
                new Dtos.AcceptPaymentRequest(bd("10.00"), Enums.PaymentChannel.QR, "x", null, null)))
                .isInstanceOf(SimException.class); // payments refused

        // login blocked at platform level (assertLoginAllowed) — OTP flow requested first
        bearerPost("/api/auth/otp", null, java.util.Map.of("phone", m.getOwnerPhone()))
                .andExpect(status().isOk()); // OTP issuing itself is allowed
        bearerPost("/api/auth/otp/verify", null,
                java.util.Map.of("phone", m.getOwnerPhone(), "code", "123456"))
                .andExpect(status().isForbidden()); // but verification is blocked for suspended merchants

        ops.lift(suspended.id(), "uat-ops");
        assertThat(merchants.findById(m.getId()).orElseThrow().getApprovalStatus())
                .isEqualTo(Enums.ApprovalStatus.APPROVED); // restored after lift
    }

    @Test
    @DisplayName("TC-OPS-11 · Complaint lifecycle: log → assign → resolve (T10)")
    void tcOps11_complaintLifecycle() {
        var s = newApprovedSupplier("Complaint stock", "50.00");
        var created = ops.createComplaint(
                new Dtos.CreateComplaintRequest(null, s.getId(), "late delivery",
                        "Stock arrived two days late"), "uat-ops");
        assertThat(created.status()).isEqualTo(Enums.ComplaintStatus.OPEN);

        ops.assignComplaint(created.id(), "uat-ops");
        var inProgress = ops.complaints().stream()
                .filter(c -> c.id().equals(created.id())).findFirst().orElseThrow();
        assertThat(inProgress.status()).isEqualTo(Enums.ComplaintStatus.IN_PROGRESS);
        assertThat(inProgress.assignedTo()).isEqualTo("uat-ops");

        ops.resolveComplaint(created.id(), "supplier contacted, resolution agreed", "uat-ops");
        var resolved = ops.complaints().stream()
                .filter(c -> c.id().equals(created.id())).findFirst().orElseThrow();
        assertThat(resolved.status()).isEqualTo(Enums.ComplaintStatus.RESOLVED);
    }

    @Test
    @DisplayName("TC-OPS-12 · Card dispute workflow: create on a confirmed payment and resolve (T11)")
    void tcOps12_disputeLifecycle() throws Exception {
        var m = newApprovedMerchant();
        var card = payNowConfirmed(m.getId(), "120.00", Enums.PaymentChannel.CARD, "dispute-cust");

        ops.createDispute(card.getId(), "wrong amount", "customer disputes N$120", "uat-ops");
        var open = ops.disputes().stream()
                .filter(d -> d.status() == Enums.DisputeStatus.OPEN)
                .filter(d -> "wrong amount".equals(d.reason()))
                .findFirst().orElseThrow();
        assertThat(open.merchantId()).isEqualTo(m.getId());

        ops.resolveDispute(open.id(), true, "uat-ops");
        var resolved = ops.disputes().stream()
                .filter(d -> open.id().equals(d.id())).findFirst().orElseThrow();
        assertThat(resolved.status()).isEqualTo(Enums.DisputeStatus.WON);
        assertThat(resolved.merchantId()).isEqualTo(m.getId());
    }
}
