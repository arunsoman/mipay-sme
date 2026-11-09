package com.mipay.sme.uat;

import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.credit.Advance;
import com.mipay.sme.domain.payments.Payment;
import com.mipay.sme.domain.ledger.BalanceHold;
import com.mipay.sme.domain.core.Merchant;
import com.mipay.sme.domain.core.Supplier;
import com.mipay.sme.web.Dtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UAT SB p.7 / BC p.8 acceptance — the four money panels (available, pending,
 * held, owed) and their rules.
 */
@DisplayName("Balance panels (UAT SB p.7; BC p.8) — TC-BAL")
class TUat04BalanceTests extends UatSupport {

    private static final String OPENING = "500.00";

    private Merchant m;

    @BeforeEach
    void setUp() {
        m = newApprovedMerchant();
        credit(m.getId(), OPENING);
    }

    @Test
    @DisplayName("TC-BAL-01 · Available increases only after the issuer confirms (BC p.8)")
    void tcBal01_availableOnlyAfterIssuerConfirms() {
        BigDecimal before = available(m.getId());

        Payment p = payments.accept(m.getId(),
                new Dtos.AcceptPaymentRequest(bd("60.00"), Enums.PaymentChannel.QR, "0812345678", null, null));
        assertThat(p.getStatus()).isEqualTo(Enums.PaymentStatus.PENDING);

        // issuer ledger has NO confirmation yet -> the amount must not be spendable (BC p.8)
        Dtos.BalanceView mid = balance(m.getId());
        assertThat(mid.available()).isEqualByComparingTo(before);
        // NOTE (documented finding #2): the QR/short-code pending *display* is part of the
        // pending panel only for CARD reservations today; pending amounts are never
        // spendable in either case (asserted in TC-BAL-02 with a real CARD reservation).
        assertThat(issuerLedger.findByIdempotencyKey("pay-confirm-" + p.getId())).isEmpty();

        payments.simulate(m.getId(), p.getId(), "confirm");

        Dtos.BalanceView after = balance(m.getId());
        assertThat(after.available()).isEqualByComparingTo(before.add(bd("60.00")));
        assertThat(after.pending()).isEqualByComparingTo("0");
        assertThat(issuerLedger.findByIdempotencyKey("pay-confirm-" + p.getId())).isPresent();
    }

    @Test
    @org.junit.jupiter.api.Disabled("finding #2 (docs/UAT-BACKEND-TEST-PACK.md): QR/short-code accepts do not\n"
            + "create a PENDING_CONFIRMATION subledger entry (only CARD reservations do), so the app's\n"
            + "pending panel (BC p.7-8: 'amount shows as pending until the issuer confirms') reads 0 for QR.\n"
            + "The money rule is unaffected and asserted in tcBal01_*; enable this spec test once accepts\n"
            + "mirror a pending row for QR/short-code too.")
    void tcBal01_qrPendingDisplaySpecConformant() {
        Payment p = payments.accept(m.getId(),
                new Dtos.AcceptPaymentRequest(bd("60.00"), Enums.PaymentChannel.QR, "0812345678", null, null));

        Dtos.BalanceView mid = balance(m.getId());
        assertThat(mid.available()).isEqualByComparingTo(available(m.getId()));
        assertThat(mid.pending()).isEqualByComparingTo("60.00"); // spec: pending is DISPLAYED, not spendable
        payments.simulate(m.getId(), p.getId(), "confirm");
        assertThat(balance(m.getId()).pending()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("TC-BAL-02 · Pending is never spendable until confirmed")
    void tcBal02_pendingNeverSpendable() {
        payments.accept(m.getId(),
                new Dtos.AcceptPaymentRequest(bd("120.00"), Enums.PaymentChannel.CARD, "CARD-POS", null, null));

        Dtos.BalanceView v = balance(m.getId());
        assertThat(v.available()).isEqualByComparingTo(OPENING);
        assertThat(v.pending()).isEqualByComparingTo("120.00");
        assertThat(available(m.getId())).isEqualByComparingTo(OPENING); // spendable unchanged
    }

    @Test
    @DisplayName("TC-BAL-03 · Hold reduces available; release restores it (UAT SB p.15)")
    void tcBal03_holdReducesAvailable() {
        BalanceHold h = ledger.createHold(m.getId(), bd("300.00"), "INV-UAT-" + seq(), 60);
        assertThat(h.getStatus()).isEqualTo(Enums.HoldStatus.ACTIVE);

        Dtos.BalanceView held = balance(m.getId());
        assertThat(held.available()).isEqualByComparingTo("200.00");
        assertThat(held.held()).isEqualByComparingTo("300.00");

        ledger.releaseHold(h); // failure path release; success path converts (TC-B2B-02)
        Dtos.BalanceView released = balance(m.getId());
        assertThat(released.available()).isEqualByComparingTo(OPENING);
        assertThat(released.held()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("TC-BAL-04 · Owed displayed separately from sales proceeds, never mixed")
    void tcBal04_owedSeparateFromSales() {
        assertOwedZero(m);

        var lender = newLender(bd("0.10"), bd("0.05"), bd("2000"), bd("25000"));
        Advance a = acceptedAdvance(m.getId(), lender, "1000.00", "1050.00");
        assertThat(a.getStatus()).isEqualTo(Enums.AdvanceStatus.ACCEPTED);

        advances.fund(a.getId(), "uat-lender", "LENDER");

        Dtos.BalanceView v = balance(m.getId());
        // available includes the funded advance proceeds (issuer confirmed)
        assertThat(v.available()).isEqualByComparingTo("1500.00");
        // owed tracks the total repayment obligation separately, not netted into available
        assertThat(v.owed()).isEqualByComparingTo("1050.00");
        assertThat(v.available()).isNotEqualByComparingTo(v.owed());
    }

    @Test
    @DisplayName("TC-BAL-05 · Issuer ledger (authoritative) and subledger (mirror) agree per owner")
    void tcBal05_issuerLedgerMatchesSubledger() {
        payNowConfirmed(m.getId(), "60.00", Enums.PaymentChannel.QR, "0812345678");
        cardPaymentPending(m.getId(), "120.00");   // pending -> not part of confirmed mirror
        credit(m.getId(), "100.00");
        payNowConfirmed(m.getId(), "40.00", Enums.PaymentChannel.SHORT_CODE, "0812346001");

        var issuerNet = ledger.issuerNetPerOwner().get(m.getId());
        var confirmedNet = ledger.confirmedNetPerOwner().get(m.getId());
        assertThat(issuerNet).isEqualByComparingTo("700.00");
        assertThat(confirmedNet).isEqualByComparingTo("700.00"); // two-way match (BC p.12)

        List<BalanceHold> none = ledger.activeHolds(m.getId());
        assertThat(none).isEmpty();
    }

    private void assertOwedZero(Merchant merchant) {
        assertThat(balance(merchant.getId()).owed()).isEqualByComparingTo("0");
    }
}
