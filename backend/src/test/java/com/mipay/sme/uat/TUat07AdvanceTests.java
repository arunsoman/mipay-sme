package com.mipay.sme.uat;

import com.mipay.sme.common.SimException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.credit.Advance;
import com.mipay.sme.web.Dtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UAT SB pp.12-13 / BC p.10-11 — the cash advance lifecycle: consent →
 * assessment → offer → acceptance (no money) → funding (money moves) →
 * daily repayment sweeps on eligible sales.
 */
@DisplayName("Cash advances (UAT SB pp.12-13; BC p.10-11) — TC-ADV")
class TUat07AdvanceTests extends UatSupport {

    // ------------------------------------------------------------------ offer → fund

    @Test
    @DisplayName("TC-ADV-01 · Lender approval alone adds no money (UAT SB p.13)")
    void tcAdv01_approvalAddsNoMoney() {
        var m = newApprovedMerchant();
        credit(m.getId(), "500.00");
        var lender = newLender(bd("0.10"), bd("0.05"), bd("2000"), bd("25000"));
        advances.grantConsent(m.getId(), lender.getId());
        seedHistory(m.getId(), 40, "1500.00"); // so the assessment can approve
        BigDecimal withHistory = available(m.getId()); // history posts real confirmed money

        Dtos.AssessmentView view = advances.requestAssessment(m.getId(), lender.getId(), null);
        assertThat(view.decision()).isEqualTo(Enums.AssessmentDecision.APPROVED);

        Dtos.Message accepted = advanceAccept(m.getId());
        assertThat(accepted.message()).contains("balance unchanged");

        // offer + acceptance moved no money (delta around the seeded history)
        assertThat(available(m.getId())).isEqualByComparingTo(withHistory);
        assertThat(balance(m.getId()).owed()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("TC-ADV-02 · Funded advance becomes available only after the issuer confirms proceeds")
    void tcAdv02_fundingMakesAvailable() {
        var m = newApprovedMerchant();
        credit(m.getId(), "500.00");
        var lender = newLender(bd("0.10"), bd("0.05"), bd("2000"), bd("25000"));
        advances.grantConsent(m.getId(), lender.getId());

        Advance a = acceptedAdvance(m.getId(), lender, "1000.00", "1050.00");
        advances.fund(a.getId(), "uat-lender", "LENDER");

        assertThat(available(m.getId())).isEqualByComparingTo("1500.00");
        var saved = advanceRepo.findById(a.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(Enums.AdvanceStatus.FUNDED);
        assertThat(saved.getFundedAt()).isNotNull();

        // third ledger: single CREDIT of the total repayment obligation
        inTx(() -> {
            var top = creditLedger.findTopByAdvanceIdOrderByCreatedAtDesc(a.getId()).orElseThrow();
            assertThat(top.getEntryType()).isEqualTo(Enums.LedgerEntryType.CREDIT);
            assertThat(top.getAmount()).isEqualByComparingTo("1050.00");
            assertThat(top.getBalanceAfter()).isEqualByComparingTo("1050.00");
        });
        assertThat(balance(m.getId()).owed()).isEqualByComparingTo("1050.00");

        // funding replay is absorbed by the single-effect guarantee — no second credit (BUG-R3 fix:
        // bank-callback replays are absorbed, not refused; matches TC-ACC-04 single-effect rule)
        Dtos.Message replay = advances.fund(a.getId(), "uat-lender", "LENDER");
        assertThat(replay.message()).contains("Duplicate funding callback ignored");
        assertThat(available(m.getId())).isEqualByComparingTo("1500.00");
    }

    // ------------------------------------------------------------------ repayment sweeps

    @Test
    @DisplayName("TC-ADV-03 · Sweep collects the rate of eligible daily sales; owed reduces; idempotent per day")
    void tcAdv03_repaymentSweepOnEligibleSales() {
        var m = newApprovedMerchant();
        var lender = newLender(bd("0.10"), bd("0.05"), bd("2000"), bd("25000"));
        Advance a = acceptedAdvance(m.getId(), lender, "1000.00", "1050.00");
        advances.fund(a.getId(), "uat-lender", "LENDER");

        LocalDate day1 = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        historyPayment(m.getId(), day1, "6000.00", "c1");
        historyPayment(m.getId(), day1, "4000.00", "c2");

        List<Dtos.StepResult> steps = advances.sweep(lender.getId(), day1);
        assertThat(steps).anySatisfy(s -> {
            assertThat(s.ok()).isTrue();
            assertThat(s.detail()).contains("Collected N$1000.00"); // 10% × 10,000
        });

        var repaymentRow = repayments.findByAdvanceIdOrderByCreatedAtDesc(a.getId()).get(0);
        assertThat(repaymentRow.getAmount()).isEqualByComparingTo("1000.00");
        assertThat(repaymentRow.getEligibleSalesBase()).isEqualByComparingTo("10000.00");

        var funded = advanceRepo.findById(a.getId()).orElseThrow();
        assertThat(funded.getStatus()).isEqualTo(Enums.AdvanceStatus.REPAYING);
        assertThat(advances.remainingOwed(funded)).isEqualByComparingTo("50.00");
        assertThat(balance(m.getId()).owed()).isEqualByComparingTo("50.00");

        // duplicate sweep for the same day never collects twice (idempotent)
        assertThat(advances.sweep(lender.getId(), day1))
                .anySatisfy(s -> assertThat(s.detail()).contains("already applied"));
        assertThat(repayments.findByAdvanceIdOrderByCreatedAtDesc(a.getId())).hasSize(1);

        // second day closes the advance exactly at the remaining balance
        LocalDate day2 = LocalDate.now(ZoneOffset.UTC);
        payNowConfirmed(m.getId(), "500.00", Enums.PaymentChannel.QR, "day2-customer-" + seq());
        List<Dtos.StepResult> steps2 = advances.sweep(lender.getId(), day2);
        assertThat(steps2).anySatisfy(s -> assertThat(s.detail()).contains("Collected N$50.00"));

        var closed = advanceRepo.findById(a.getId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(Enums.AdvanceStatus.CLOSED);
        assertThat(advances.remainingOwed(closed)).isEqualByComparingTo("0");
        assertThat(balance(m.getId()).owed()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("TC-ADV-05 · Zero-sales day: no collection, balance and owed unchanged")
    void tcAdv05_zeroSalesDay() {
        var m = newApprovedMerchant();
        var lender = newLender(bd("0.10"), bd("0.05"), null, bd("25000"));
        Advance a = acceptedAdvance(m.getId(), lender, "1000.00", "1050.00");
        advances.fund(a.getId(), "uat-lender", "LENDER");
        BigDecimal availableBefore = available(m.getId());

        List<Dtos.StepResult> steps = advances.sweep(lender.getId(), LocalDate.now(ZoneOffset.UTC));
        assertThat(steps).anySatisfy(s -> {
            assertThat(s.ok()).isTrue();
            assertThat(s.detail()).contains("Zero eligible sales");
        });

        assertThat(repayments.findByAdvanceIdOrderByCreatedAtDesc(a.getId())).isEmpty();
        assertThat(available(m.getId())).isEqualByComparingTo(availableBefore);
        assertThat(balance(m.getId()).owed()).isEqualByComparingTo("1050.00");

        // sweeping with no active advances is also safe
        var empty = newLender(bd("0.10"), bd("0.05"), null, bd("25000"));
        assertThat(advances.sweep(empty.getId(), LocalDate.now(ZoneOffset.UTC)))
                .anySatisfy(s -> assertThat(s.detail()).contains("nothing to collect"));
    }

    @Test
    @DisplayName("TC-ADV-04 · Refunds adjust the repayment base — net-of-refund, no over-collection")
    void tcAdv04_refundNetsRepaymentBase() {
        var m = newApprovedMerchant();
        var lender = newLender(bd("0.10"), bd("0.05"), null, bd("25000"));
        Advance a = acceptedAdvance(m.getId(), lender, "1000.00", "1050.00");
        advances.fund(a.getId(), "uat-lender", "LENDER");

        var sale = payNowConfirmed(m.getId(), "1000.00", Enums.PaymentChannel.QR, "base-cust-" + seq());
        payments.refund(m.getId(), sale.getId(), new Dtos.RefundRequest(bd("400.00"), "damaged"));

        List<Dtos.StepResult> steps = advances.sweep(lender.getId(), LocalDate.now(ZoneOffset.UTC));
        assertThat(steps).anySatisfy(s -> assertThat(s.detail()).contains("Collected N$60.00"));

        var row = repayments.findByAdvanceIdOrderByCreatedAtDesc(a.getId()).get(0);
        assertThat(row.getEligibleSalesBase()).isEqualByComparingTo("600.00"); // 1,000 − 400
        assertThat(row.getAmount()).isEqualByComparingTo("60.00");             // 10% × net base
    }

    @Test
    @DisplayName("TC-ADV-06 · Advance proceeds are never counted as sales (BC p.10)")
    void tcAdv06_proceedsExcludedFromSales() {
        var m = newApprovedMerchant();
        BigDecimal salesBefore = advances.eligibility(m.getId()).eligibleTotal(); // zero history

        var lender = newLender(bd("0.10"), bd("0.05"), null, bd("25000"));
        Advance a = acceptedAdvance(m.getId(), lender, "2000.00", "2100.00");
        advances.fund(a.getId(), "uat-lender", "LENDER");

        BigDecimal salesAfter = advances.eligibility(m.getId()).eligibleTotal();
        assertThat(salesAfter).isEqualByComparingTo(salesBefore); // verified receipts unchanged
        assertThat(available(m.getId())).isEqualByComparingTo("2000.00"); // but funds ARE available
    }

    // ------------------------------------------------------------------ caps & declines

    @Test
    @DisplayName("TC-SET-24 · Offer capped by lesser-of rule: min(N$25,000, 20% avg monthly, lender limit)")
    void tcSet24_lesserOfCap() {
        var m = newApprovedMerchant();
        seedHistory(m.getId(), 60, "900.00"); // eligible 54,000 → avg monthly 18,000 → share cap 3,600

        var lender = newLender(bd("0.10"), bd("0.05"), bd("2000"), bd("25000"));
        advances.grantConsent(m.getId(), lender.getId());

        Dtos.AssessmentView v = advances.requestAssessment(m.getId(), lender.getId(), null);
        assertThat(v.offerPrincipal()).isEqualByComparingTo("3600.00");   // min applied
        assertThat(v.offerRepayment()).isEqualByComparingTo("3780.00");   // + 5% service fee
        assertThat(v.reasonCodes()).contains("LESSER_OF_CAP_APPLIED");

        // asking for more than the cap yields exactly the cap
        Dtos.AssessmentView capped = advances.requestAssessment(m.getId(), lender.getId(), bd("5000"));
        assertThat(capped.offerPrincipal()).isEqualByComparingTo("3600.00");

        // asking for less than the cap yields exactly the request
        Dtos.AssessmentView modest = advances.requestAssessment(m.getId(), lender.getId(), bd("1000"));
        assertThat(modest.offerPrincipal()).isEqualByComparingTo("1000.00");

        // a smaller lender limit becomes the binding cap
        var smallLender = newLender(bd("0.10"), bd("0.05"), bd("2000"), bd("2000"));
        advances.grantConsent(m.getId(), smallLender.getId());
        Dtos.AssessmentView lenderCapped = advances.requestAssessment(m.getId(), smallLender.getId(), null);
        assertThat(lenderCapped.offerPrincipal()).isEqualByComparingTo("2000.00");
    }

    @Test
    @DisplayName("TC-SET-24b · Assessment declines without sufficient history or revenue")
    void tcSet24_declinePaths() {
        // < 30 trading days → INSUFFICIENT_HISTORY
        var m1 = newApprovedMerchant();
        seedHistory(m1.getId(), 10, "5000.00");
        var lender = newLender(bd("0.10"), bd("0.05"), null, bd("25000"));
        advances.grantConsent(m1.getId(), lender.getId());
        Dtos.AssessmentView d1 = advances.requestAssessment(m1.getId(), lender.getId(), null);
        assertThat(d1.decision()).isEqualTo(Enums.AssessmentDecision.DECLINED);
        assertThat(d1.reasonCodes()).contains("INSUFFICIENT_HISTORY");
        assertThat(d1.offerPrincipal()).isNull();

        // avg monthly below the product threshold → LOW_REVENUE
        var m2 = newApprovedMerchant();
        seedHistory(m2.getId(), 40, "50.00"); // avg 667 < 750
        advances.grantConsent(m2.getId(), lender.getId());
        Dtos.AssessmentView d2 = advances.requestAssessment(m2.getId(), lender.getId(), null);
        assertThat(d2.decision()).isEqualTo(Enums.AssessmentDecision.DECLINED);
        assertThat(d2.reasonCodes()).contains("LOW_REVENUE");
    }

    // ------------------------------------------------------------------ arrears guard (C14/BC p.19)

    @Test
    @DisplayName("TC-SET-25 · New draws pause when 30-day arrears exceed the trigger (BC p.19)")
    void tcSet25_arrearsPause() {
        // advance A in arrears: funded 45 days ago, nothing repaid
        var m = newApprovedMerchant();
        var lender = newLender(bd("0.10"), bd("0.05"), null, bd("25000"));
        Advance a = acceptedAdvance(m.getId(), lender, "5000.00", "5250.00");
        advances.fund(a.getId(), "uat-lender", "LENDER");
        inTx(() -> {
            var saved = advanceRepo.findById(a.getId()).orElseThrow();
            saved.setFundedAt(Instant.now().minusSeconds(45L * 24 * 3600));
            advanceRepo.save(saved);
        });

        BigDecimal arrears = advances.arrearsAmount(lender.getId());
        assertThat(arrears).isEqualByComparingTo("5250.00");

        // a further draw for this lender is paused
        var m2 = newApprovedMerchant();
        advances.grantConsent(m2.getId(), lender.getId());
        assertThatThrownBy(() -> advances.requestAssessment(m2.getId(), lender.getId(), null))
                .isInstanceOfSatisfying(SimException.class,
                        e -> assertThat(e.getCode()).isEqualTo("ARREARS_PAUSED"));

        // portfolio evidence (L10/L11): open arrears case with days past due
        advances.snapshot(lender.getId());
        var portfolio = advances.portfolio(lender.getId());
        assertThat(portfolio.arrearsAmount()).isEqualByComparingTo("5250.00");
        assertThat(portfolio.arrearsPct()).isEqualByComparingTo("100.00");
        assertThat(portfolio.arrears()).isNotEmpty();
        assertThat(portfolio.arrears().get(0).daysPastDue()).isGreaterThanOrEqualTo(44);
    }

    // ------------------------------------------------------------------ helpers

    private Dtos.Message advanceAccept(String merchantId) {
        String offerId = advanceRepo.findFirstByMerchantIdAndStatusOrderByCreatedAtDesc(
                merchantId, Enums.AdvanceStatus.OFFERED).orElseThrow().getId();
        return advances.accept(merchantId, offerId);
    }
}
