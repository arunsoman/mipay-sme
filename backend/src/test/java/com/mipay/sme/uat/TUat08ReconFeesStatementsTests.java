package com.mipay.sme.uat;

import com.mipay.sme.common.NotFoundException;
import com.mipay.sme.common.SimException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.Merchant;
import com.mipay.sme.domain.core.Supplier;
import com.mipay.sme.web.Dtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UAT SB p.14 / BC p.18 — three-ledger reconciliation, fees applied exactly
 * once, statements and withdrawals, stale holds, settlement/fee reports.
 */
@DisplayName("Reconciliation, fees & statements (UAT SB p.14; BC p.18) — TC-RCN")
class TUat08ReconFeesStatementsTests extends UatSupport {

    private Merchant m;
    private Supplier s;

    @BeforeEach
    void setUp() {
        assertReconciliationClean(); // money tests require a clean recon state
        m = newApprovedMerchant();
        s = newApprovedSupplier("UAT Recon stock", "250.00");
    }

    @Test
    @DisplayName("TC-RCN-01 · End-of-day run: three ledgers match after mixed operations")
    void tcRcn01_threeLedgersMatched() {
        credit(m.getId(), "10000.00");
        payNowConfirmed(m.getId(), "60.00", Enums.PaymentChannel.QR, "r1");
        payNowConfirmed(m.getId(), "40.00", Enums.PaymentChannel.SHORT_CODE, "r2");

        var sale = payNowConfirmed(m.getId(), "60.00", Enums.PaymentChannel.QR, "r3");
        payments.refund(m.getId(), sale.getId(), new Dtos.RefundRequest(bd("60.00"), "recon refund"));

        var inv = orderAndInvoice(m.getId(), s, catalogueItemByProductName(s, "UAT Recon stock"), bd("8")); // 2,000
        payInvoiceOk(m.getId(), inv.id());

        var card = cardPaymentPending(m.getId(), "120.00");
        payments.simulate(m.getId(), card.getId(), "confirm"); // issues card fee once

        withdrawals.withdraw(m.getId(), bd("100.00"), "uat-ops", "TPTS_OPS");

        Dtos.RunResult run = recon.run();
        Dtos.RunClean clean = (Dtos.RunClean) run; // clean run carries the matched run id
        assertThat(breakRepo.findByRunId(clean.runId())).isEmpty();

        // second column: issuer net equals the mirrored subledger net for every owner
        assertThat(ledger.issuerNetPerOwner().get(m.getId()))
                .isEqualByComparingTo(ledger.confirmedNetPerOwner().get(m.getId()));

        // third column: an advance makes the credited-owed column non-trivial —
        // owed = principal 1,000 + risk fee 5% = 1,050 for the merchant under test
        var lender = newLender(bd("0.10"), bd("0.05"), null, bd("25000"));
        advances.grantConsent(m.getId(), lender.getId());
        seedHistory(m.getId(), 45, "3000.00"); // eligible history for the assessment
        debit(m.getId(), "135000.00"); // history is real confirmed money — sweep it back out
        Dtos.AssessmentView ass = advances.requestAssessment(m.getId(), lender.getId(), bd("1000.00"));
        String offerId = advanceRepo.findFirstByMerchantIdAndStatusOrderByCreatedAtDesc(
                m.getId(), Enums.AdvanceStatus.OFFERED).orElseThrow().getId();
        advances.accept(m.getId(), offerId);
        advances.fund(offerId, "uat-lender", "LENDER");
        assertThat(ledger.owedPerMerchant().getOrDefault(m.getId(), BigDecimal.ZERO))
                .isEqualByComparingTo("1050.00");

        // available sanity after the mix: 10,000 open + 60 + 40 + card 120 sales − 60 refund
        // − 2,000 supplier invoice − 100 withdrawal + 1,000 advance funding = 9,120
        // (the 1.50% card fee is platform revenue, not a merchant debit — covered by TC-OPS-14)
        assertThat(available(m.getId())).isEqualByComparingTo("9120.00");
    }

    @Test
    @DisplayName("TC-RCN-02 · Unexplained difference: break flagged, release blocked until resolved (BC p.18)")
    void tcRcn02_breaksBlockRelease() {
        credit(m.getId(), "500.00");
        simulation.injectBreak("uat-admin", "MINDLABS_ADMIN"); // issuer-only credit N$777.77, no mirror

        Dtos.RunResult run = recon.run();
        Dtos.RunWithBreaks broken = (Dtos.RunWithBreaks) run;
        assertThat(broken.breakCount()).isGreaterThanOrEqualTo(1);
        var brk = breakRepo.findByRunId(broken.runId()).stream()
                .filter(b -> b.getBreakType() == Enums.BreakType.MISSING_CREDIT)
                .findFirst().orElseThrow();
        assertThat(brk.getAmount()).isEqualByComparingTo("777.77");
        assertThat(brk.getStatus()).isEqualTo(Enums.BreakStatus.OPEN);

        // withdrawals are blocked while the break is open (BC p.18)
        assertThatThrownBy(() -> withdrawals.withdraw(m.getId(), bd("10.00"), "uat-ops", "TPTS_OPS"))
                .isInstanceOfSatisfying(SimException.class,
                        e -> assertThat(e.getCode()).isEqualTo("RECON_BLOCKED"));

        // resolving the break unblocks release
        recon.resolveBreak(brk.getId(), "uat-ops");
        var w = withdrawals.withdraw(m.getId(), bd("100.00"), "uat-ops", "TPTS_OPS");
        assertThat(w.status()).isEqualTo("PAID");
        assertThat(available(m.getId())).isEqualByComparingTo("400.00");

        // restore clean state for the rest of the suite
        simulation.clearBreaks("uat-ops", "TPTS_OPS");
        assertReconciliationClean();
    }

    @Test
    @DisplayName("TC-SET-23 · Stale holds are expired by the recon run and flagged (C22)")
    void tcSet23_staleHoldExpired() {
        int n = seq();
        var hold = ledger.createHold(m.getId(), bd("500.00"), "STALE-" + n, -1);
        assertThat(available(m.getId())).isEqualByComparingTo("0"); // expired hold still consumes

        Dtos.RunResult run = recon.run();
        assertThat(statusOf(run)).isEqualTo("BREAKS_FOUND");
        assertThat(breakRepo.findByRunId(((Dtos.RunWithBreaks) run).runId())).anySatisfy(b -> {
            assertThat(b.getBreakType()).isEqualTo(Enums.BreakType.STALE_HOLD);
            assertThat(b.getAmount()).isEqualByComparingTo("500.00");
        });
        assertThat(holds.findById(hold.getId()).orElseThrow().getStatus())
                .isEqualTo(Enums.HoldStatus.EXPIRED); // auto-expired by the run
        assertThat(available(m.getId())).isEqualByComparingTo("0");

        for (var b : breakRepo.findByStatus(Enums.BreakStatus.OPEN)) {
            recon.resolveBreak(b.getId(), "cleanup");
        }
    }

    @Test
    @DisplayName("TC-RCN-03 · Fees applied exactly once per event; replay changes nothing")
    void tcRcn03_feesAppliedOnce() {
        var card = cardPaymentPending(m.getId(), "120.00");
        payments.simulate(m.getId(), card.getId(), "confirm");
        var feeRow = feeTx.findByFeeCodeAndReference("card_service", "pay/" + card.getId()).orElseThrow();
        // engine math at the deployed tariff — BC p.16 contracted 2.50% (finding #7 fixed)
        assertThat(feeRow.getAmount()).isEqualByComparingTo("3.00");
        assertThat(feeRow.getStatus()).isEqualTo(Enums.FeeStatus.APPLIED);

        // replayed confirmation (duplicate path) still yields the single fee row
        payments.simulate(m.getId(), card.getId(), "confirm"); // duplicate callback
        assertThat(feeTx.findByFeeCodeAndReference("card_service", "pay/" + card.getId()).orElseThrow().getId())
                .isEqualTo(feeRow.getId());

        // advance service fee applied once on funding; same-event re-application is a no-op
        var lender = newLender(bd("0.10"), bd("0.05"), null, bd("25000"));
        var a = acceptedAdvance(m.getId(), lender, "1000.00", "1050.00");
        advances.fund(a.getId(), "uat-lender", "LENDER");
        String advanceRef = "advance/" + a.getId();
        var advanceFee = feeTx.findByFeeCodeAndReference("advance_service", advanceRef).orElseThrow();
        assertThat(advanceFee.getAmount()).isEqualByComparingTo("50.00"); // 5% of principal

        fees.applyOnce(merchants.findById(m.getId()).orElseThrow(), "advance_service", bd("1000.00"), advanceRef);
        assertThat(feeTx.findByFeeCodeAndReference("advance_service", advanceRef).orElseThrow().getId())
                .isEqualTo(advanceFee.getId());
    }

    @Test
    @DisplayName("TC-SET-15 · Fee reversal on full refund; re-reversal is a no-op")
    void tcSet15_feeReversalOnFullRefund() {
        var card = cardPaymentPending(m.getId(), "120.00");
        payments.simulate(m.getId(), card.getId(), "confirm");
        assertThat(feeTx.findByFeeCodeAndReference("card_service", "pay/" + card.getId()).orElseThrow()
                .getStatus()).isEqualTo(Enums.FeeStatus.APPLIED);

        payments.refund(m.getId(), card.getId(), new Dtos.RefundRequest(bd("120.00"), "full refund"));

        assertThat(feeTx.findByFeeCodeAndReference("card_service", "pay/" + card.getId()).orElseThrow()
                .getStatus()).isEqualTo(Enums.FeeStatus.REVERSED);
        assertThat(paymentRepo.findById(card.getId()).orElseThrow().getStatus())
                .isEqualTo(Enums.PaymentStatus.REFUNDED);

        fees.reverse("card_service", "pay/" + card.getId());
        assertThat(feeTx.findByFeeCodeAndReference("card_service", "pay/" + card.getId()).orElseThrow()
                .getStatus()).isEqualTo(Enums.FeeStatus.REVERSED);
    }

    @Test
    @DisplayName("TC-RCN-04 · Statement download matches the ledger; withdrawal carries a bank reference")
    void tcRcn04_statementAndWithdrawal() {
        credit(m.getId(), "1000.00");
        payNowConfirmed(m.getId(), "60.00", Enums.PaymentChannel.QR, "stmt-1");
        var card = cardPaymentPending(m.getId(), "120.00");
        payments.simulate(m.getId(), card.getId(), "confirm");

        var w = withdrawals.withdraw(m.getId(), bd("1000.00"), "uat-ops", "TPTS_OPS");
        assertThat(w.status()).isEqualTo("PAID");
        assertThat(w.processedAt()).isNotNull();
        assertThat(withdrawalRepo.findByMerchantIdOrderByRequestedAtDesc(m.getId(),
                org.springframework.data.domain.PageRequest.of(0, 10)).getContent())
                .anySatisfy(ww -> assertThat(ww.getBankReference()).startsWith("WDL-"));

        Dtos.StatementRow s = statements.generate("merchant", m.getId(),
                LocalDate.now(ZoneOffset.UTC).minusDays(1), LocalDate.now(ZoneOffset.UTC), "uat-ops");
        assertThat(statements.list("merchant", m.getId())).extracting(Dtos.StatementRow::id)
                .contains(s.id());

        String text = statements.downloadText("merchant", m.getId(),
                LocalDate.now(ZoneOffset.UTC).minusDays(1), LocalDate.now(ZoneOffset.UTC));
        assertThat(text).contains("Net total: 180.00"); // credits 1,180 − debit 1,000
        assertThat(text).contains("WITHDRAWAL");

        // ownership: reading/finding another owner's statement refuses
        var other = newApprovedMerchant();
        assertThatThrownBy(() -> statements.statementFile(s.id(), "merchant", other.getId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("TC-OPS-13 · Daily settlement equals confirmed receipts minus confirmed supplier payments")
    void tcOps13_settlementMath() {
        // platform-wide baselines now (tests run sequentially, so deltas are exact)
        var dayNow = LocalDate.now(ZoneOffset.UTC);
        var from = dayNow.atStartOfDay(ZoneOffset.UTC).toInstant();
        var to = dayNow.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        BigDecimal receiptsBefore = paymentRepo.confirmedSumPlatform(
                Enums.PaymentStatus.CONFIRMED, from, to);
        BigDecimal outBefore = invoicePayments.confirmedSumPlatform(
                Enums.InvoicePaymentStatus.CONFIRMED, from, to);

        BigDecimal settlementBefore = reports.settlement(LocalDate.now(ZoneOffset.UTC), "CYCLE_1").totalAmount();

        credit(m.getId(), "1000.00");
        payNowConfirmed(m.getId(), "60.00", Enums.PaymentChannel.QR, "st-1");
        var inv = orderAndInvoice(m.getId(), s, catalogueItemByProductName(s, "UAT Recon stock"), bd("1")); // 250
        payInvoiceOk(m.getId(), inv.id());

        var row = reports.settlement(LocalDate.now(ZoneOffset.UTC), "CYCLE_1");

        BigDecimal receiptsAfter = paymentRepo.confirmedSumPlatform(
                Enums.PaymentStatus.CONFIRMED, from, to);
        BigDecimal outAfter = invoicePayments.confirmedSumPlatform(
                Enums.InvoicePaymentStatus.CONFIRMED, from, to);
        BigDecimal delta = receiptsAfter.subtract(receiptsBefore).subtract(outAfter.subtract(outBefore));
        assertThat(row.totalAmount()).isEqualByComparingTo(settlementBefore.add(delta)); // before + receipts − supplier payments
        assertThat(row.status()).isEqualTo("SETTLED");
        assertThat(row.cycle()).isEqualTo("CYCLE_1");
    }

    @Test
    @DisplayName("TC-OPS-14 · Fee reports: billed equals collected per code for the day")
    void tcOps14_feeReports() {
        var day = LocalDate.now(ZoneOffset.UTC);
        java.math.BigDecimal billedBefore = reports.feeReports(day).stream()
                .filter(r -> "card_service".equals(r.feeCode()))
                .map(Dtos.FeeReportRow::amountBilled).reduce(BigDecimal.ZERO, BigDecimal::add);

        var card = cardPaymentPending(m.getId(), "120.00");
        payments.simulate(m.getId(), card.getId(), "confirm"); // creates card_service fee 1.80 today

        java.util.List<Dtos.FeeReportRow> rows = reports.feeReports(day);
        assertThat(rows).isNotEmpty();
        java.math.BigDecimal billedAfter = rows.stream()
                .filter(r -> "card_service".equals(r.feeCode()))
                .map(Dtos.FeeReportRow::amountBilled).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(billedAfter.subtract(billedBefore))
                .as("exactly one card_service fee on N$120 at the deployed tariff (BC p.16 2.50%, finding #7 fixed)")
                .isEqualByComparingTo("3.00");
        assertThat(rows).allSatisfy(r ->
                assertThat(r.amountCollected()).isEqualByComparingTo(r.amountBilled()));
    }
}
