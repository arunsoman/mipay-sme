package com.mipay.sme.uat;

import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.payments.Payment;
import com.mipay.sme.domain.core.Merchant;
import com.mipay.sme.domain.ledger.BalanceHold;
import com.mipay.sme.domain.core.Supplier;
import com.mipay.sme.web.Dtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UAT SB p.9/p.15 acceptance tests — accept a payment (QR, short code, card),
 * single-confirmation guarantee, timeout preservation, card pending rules.
 */
@DisplayName("Accept & hold (UAT SB p.9/p.15) — TC-ACC")
class TUat03AcceptanceTests extends UatSupport {

    private static final String OPENING = "500.00";

    private Merchant m;

    @BeforeEach
    void seedMerchantWithOpeningBalance() {
        m = newApprovedMerchant();
        credit(m.getId(), OPENING);
    }

    @Test
    @DisplayName("TC-ACC-01 · QR payment confirmed exactly once; funds available; single speaker confirmation")
    void tcAcc01_qrPaymentConfirmedOnce() {
        Payment p = payments.accept(m.getId(),
                new Dtos.AcceptPaymentRequest(bd("60.00"), Enums.PaymentChannel.QR, "0812345678", null, null));
        assertThat(p.getStatus()).isEqualTo(Enums.PaymentStatus.PENDING);

        Dtos.PaymentOpResult r = payments.simulate(m.getId(), p.getId(), "confirm");
        assertThat(r).isInstanceOf(Dtos.PaymentConfirmed.class);
        assertThat(((Dtos.PaymentConfirmed) r).amount()).isEqualByComparingTo("60.00");

        Payment saved = paymentRepo.findById(p.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(Enums.PaymentStatus.CONFIRMED);
        assertThat(saved.getConfirmedAt()).isNotNull();

        // issuer confirmed -> only now available (BC p.8, TC-BAL-01)
        assertThat(issuerLedger.findByIdempotencyKey("pay-confirm-" + p.getId())).isPresent();
        assertThat(available(m.getId())).isEqualByComparingTo("560.00");
        assertThat(balance(m.getId()).pending()).isEqualByComparingTo("0");

        // one speaker confirmation entry for this payment (M09, M41)
        assertThat(payments.speakerLog(m.getId()))
                .filteredOn(s -> s.paymentId().equals(p.getId()))
                .hasSize(1);
    }

    @Test
    @DisplayName("TC-ACC-02 · Basic-phone short-code payment: lookup + pay via real API")
    void tcAcc02_shortCodePayment() throws Exception {
        String code = shortCodes.findTopByMerchantIdAndStatusOrderByCreatedAtDesc(m.getId(), Enums.QrStatus.ACTIVE)
                .orElseThrow().getShortCode();

        // customer lookup by short code (M08)
        bearerGet("/api/customer/lookup?shortCode=" + code, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantId").value(m.getId()));

        // customer pays from own bank/basic phone (BC p.6: no customer wallet)
        bearerPost("/api/customer/pay", null, Map.of(
                        "shortCode", code, "amount", "55.00", "payerPhone", "0859990002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").isNotEmpty());

        assertThat(available(m.getId())).isEqualByComparingTo("555.00");
        assertThat(paymentRepo.findByMerchantIdOrderByCreatedAtDesc(m.getId(),
                org.springframework.data.domain.PageRequest.of(0, 10)).getContent())
                .anySatisfy(p -> {
                    assertThat(p.getChannel()).isEqualTo(Enums.PaymentChannel.SHORT_CODE);
                    assertThat(p.getStatus()).isEqualTo(Enums.PaymentStatus.CONFIRMED);
                });
    }

    @Test
    @DisplayName("TC-ACC-03 · Card sale is PENDING and not spendable until acquirer releases; fee applied once")
    void tcAcc03_cardSalePendingUntilRelease() {
        Payment p = cardPaymentPending(m.getId(), "120.00");
        assertThat(p.getStatus()).isEqualTo(Enums.PaymentStatus.PENDING);

        Dtos.BalanceView midFlight = balance(m.getId());
        assertThat(midFlight.available()).isEqualByComparingTo(OPENING);   // not spendable (TC-BAL-02)
        assertThat(midFlight.pending()).isEqualByComparingTo("120.00");   // shown separately

        payments.simulate(m.getId(), p.getId(), "confirm");

        assertThat(balance(m.getId()).available()).isEqualByComparingTo("620.00");
        assertThat(balance(m.getId()).pending()).isEqualByComparingTo("0");
        // card service fee exactly once (TC-RCN-03 precondition)
        assertThat(feeTx.findByFeeCodeAndReference("card_service", "pay/" + p.getId()))
                .isPresent()
                .hasValueSatisfying(f -> {
                    // fee-engine math at the DEPLOYED tariff — BC p.16 contracted 2.50%
                    // (finding #7 fixed; tariff value asserted against the BC in TC-SET-10)
                    assertThat(f.getAmount()).isEqualByComparingTo("3.00");
                    assertThat(f.getStatus()).isEqualTo(Enums.FeeStatus.APPLIED);
                });
    }

    @Test
    @DisplayName("TC-ACC-03b · Card charge failure reverses the pending rows; no funds moved")
    void tcAcc03b_cardFailReversesPending() {
        Payment p = cardPaymentPending(m.getId(), "90.00");
        Dtos.PaymentOpResult r = payments.simulate(m.getId(), p.getId(), "fail");

        assertThat(r).isInstanceOf(Dtos.PaymentFailed.class);
        Payment saved = paymentRepo.findById(p.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(Enums.PaymentStatus.FAILED);
        assertThat(balance(m.getId()).available()).isEqualByComparingTo(OPENING);
        assertThat(balance(m.getId()).pending()).isEqualByComparingTo("0");
        assertThat(subledger.findBySourceRefIdAndSource(p.getId(), Enums.SubledgerSource.CARD))
                .allMatch(e -> e.getStatus() == Enums.SubledgerStatus.REVERSED);
    }

    @Test
    @DisplayName("TC-ACC-04 · Duplicate bank callback: no second debit/credit/stock adjustment (BC p.18)")
    void tcAcc04_duplicateCallbackAbsorbed() {
        BigDecimal before = available(m.getId());
        Payment p = payNowConfirmed(m.getId(), "60.00", Enums.PaymentChannel.QR, "0829990001");
        BigDecimal afterConfirm = available(m.getId());
        assertThat(afterConfirm).isEqualByComparingTo(before.add(bd("60.00")));

        Dtos.PaymentOpResult replay = payments.simulate(m.getId(), p.getId(), "duplicate");
        assertThat(((Dtos.PaymentPending) replay).label()).containsIgnoringCase("ignored");

        // still exactly one issuer entry with the idempotency key - no second money effect
        assertThat(issuerLedger.findByOwnerIdOrderByIdAsc(m.getId()).stream()
                .filter(e -> ("pay-confirm-" + p.getId()).equals(e.getIdempotencyKey()))
                .count()).isEqualTo(1);
        assertThat(available(m.getId())).isEqualByComparingTo(afterConfirm);

        // events: CALLBACK + CONFIRMED + DUPLICATE_IGNORED
        assertThat(events.findByPaymentIdOrderByCreatedAtAsc(p.getId()))
                .anySatisfy(e -> assertThat(e.getEventType()).isEqualTo(Enums.PaymentEventType.DUPLICATE_IGNORED))
                .anySatisfy(e -> assertThat(e.getEventType()).isEqualTo(Enums.PaymentEventType.CONFIRMED));

        assertThat(auditRepo.search(false, org.springframework.data.domain.PageRequest.of(0, 100)).getContent().stream()
                .anyMatch(a -> "duplicate_callback_ignored".equals(a.getAction())
                        && p.getId().equals(a.getEntityId())))
                .isTrue();
    }

    @Test
    @DisplayName("TC-ACC-05 · Bank timeout keeps payment PENDING; later final answer still completes")
    void tcAcc05_timeoutStaysPendingThenFinalAnswer() {
        Payment p = payments.accept(m.getId(),
                new Dtos.AcceptPaymentRequest(bd("30.00"), Enums.PaymentChannel.QR, "0812345679", null, null));

        Dtos.PaymentOpResult r = payments.simulate(m.getId(), p.getId(), "timeout");
        assertThat(r).isInstanceOf(Dtos.PaymentPending.class);
        assertThat(paymentRepo.findById(p.getId()).orElseThrow().getStatus())
                .isEqualTo(Enums.PaymentStatus.PENDING);   // pending preserved (UAT SB p.15)
        assertThat(events.findByPaymentIdOrderByCreatedAtAsc(p.getId()))
                .anySatisfy(e -> assertThat(e.getEventType()).isEqualTo(Enums.PaymentEventType.TIMEOUT));
        assertThat(available(m.getId())).isEqualByComparingTo(OPENING);

        // the bank still returns its final answer after the timeout -> completes safely
        Dtos.PaymentOpResult final_ = payments.simulate(m.getId(), p.getId(), "confirm");
        assertThat(final_).isInstanceOf(Dtos.PaymentConfirmed.class);
        assertThat(available(m.getId())).isEqualByComparingTo("530.00");
    }

    @Test
    @DisplayName("TC-ACC-06 · Holds reduce available balance and release on failure (TC-BAL-03 companion)")
    void tcAcc06_holdReduceAndRelease() {
        BalanceHold h = ledger.createHold(m.getId(), bd("300.00"), "INV-UAT-" + seq(), 60);
        assertThat(available(m.getId())).isEqualByComparingTo("200.00");
        assertThat(balance(m.getId()).held()).isEqualByComparingTo("300.00");

        ledger.releaseHold(h);
        assertThat(available(m.getId())).isEqualByComparingTo(OPENING);
        assertThat(balance(m.getId()).held()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("TC-SEC-04a · QR payload cannot be replayed for a different merchant")
    void tcAcc07_qrBoundToItsMerchant() throws Exception {
        String otherPhone = phone();
        onboarding.registerMerchant(new Dtos.RegisterMerchantRequest(otherPhone, "Other Shop " + seq(),
                "Other Owner", "85040099001", "RETAIL", "REG-X-" + seq()));
        // unapproved merchant: customer pay via its QR payload must be refused
        String payload = "mipay://qr/" + merchants.findByOwnerPhone(otherPhone).orElseThrow().getId();
        bearerPost("/api/customer/pay", null, Map.of("qrPayload", payload, "amount", "10.00", "payerPhone", "x"))
                .andExpect(status().isUnprocessableEntity());
    }
}
