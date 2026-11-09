package com.mipay.sme.uat;

import com.mipay.sme.common.ConflictException;
import com.mipay.sme.common.SimException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.ledger.BalanceHold;
import com.mipay.sme.domain.payments.Payment;
import com.mipay.sme.domain.core.Merchant;
import com.mipay.sme.domain.core.Supplier;
import com.mipay.sme.web.Dtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UAT SB pp.10-11 / BC p.9 — B2B stock purchase: order → invoice with unique
 * reference → pay from balance. Atomicity, idempotency, timeout, hold, failure,
 * insufficient balance without overdraft, concurrency, refunds.
 */
@DisplayName("Pay supplier from balance (UAT SB pp.10-11; BC p.9) — TC-B2B")
class TUat05B2bPaymentTests extends UatSupport {

    private static final String OPENING = "10000.00";

    private Merchant m;
    private Supplier s;
    private String cableItemId;

    @BeforeEach
    void setUp() {
        m = newApprovedMerchant();
        credit(m.getId(), OPENING);
        s = newApprovedSupplier("UAT USB-C cable 1m", "250.00");
        cableItemId = catalogueItemByProductName(s, "UAT USB-C cable 1m");
    }

    @Test
    @DisplayName("TC-B2B-01 · Supplier invoice carries unique reference from a verified supplier")
    void tcB2b01_invoiceUniqueReferenceVerifiedSupplier() {
        var inv = orderAndInvoice(m.getId(), s, cableItemId, bd("20")); // 20 × 250 = 5000

        assertThat(inv.uniqueReference()).matches("KAM-[0-9A-F]{4}-[0-9A-F]{4}");
        assertThat(inv.invoiceNumber()).startsWith("INV-");
        assertThat(inv.supplierName()).isEqualTo(s.getBusinessName());
        assertThat(inv.amount()).isEqualByComparingTo("5000.00");
        assertThat(inv.currency()).isEqualTo("NAD");
        assertThat(invoices.findByUniqueReference(inv.uniqueReference()).orElseThrow().getId())
                .isEqualTo(inv.id()); // unique reference is resolvable (UAT SB p.10)
    }

    @Test
    @DisplayName("TC-B2B-02 · Pay from balance is atomic: one debit, one supplier credit, invoice PAID")
    void tcB2b02_payFromBalanceAtomic() {
        var inv = orderAndInvoice(m.getId(), s, cableItemId, bd("20"));
        BigDecimal supplierBefore = available(s.getId());

        Dtos.InvoicePaid paid = payInvoiceOk(m.getId(), inv.id());
        assertThat(paid.bankReference()).startsWith("BT-");
        assertThat(paid.amount()).isEqualByComparingTo("5000.00");

        // exactly one debit row + one credit row for this payment
        assertThat(subledger.findBySourceRefIdAndSource(paid.invoicePaymentId(), Enums.SubledgerSource.SUPPLIER_PAYMENT))
                .hasSize(2)
                .extracting(e -> e.getEntryType())
                .containsExactlyInAnyOrder(Enums.LedgerEntryType.DEBIT, Enums.LedgerEntryType.CREDIT);

        // issuer authoritative entries: exactly one of each, matching amounts
        assertThat(issuerLedger.findByIdempotencyKey("b2b-debit-" + paid.invoicePaymentId()).orElseThrow().getAmount())
                .isEqualByComparingTo("5000.00");
        assertThat(issuerLedger.findByIdempotencyKey("b2b-credit-" + paid.invoicePaymentId()).orElseThrow().getAmount())
                .isEqualByComparingTo("5000.00");

        // ledger result state
        assertThat(available(m.getId())).isEqualByComparingTo("5000.00");
        assertThat(available(s.getId())).isEqualByComparingTo(supplierBefore.add(bd("5000.00")));

        var invoice = invoiceEntity(inv.id());
        assertThat(invoice.getStatus()).isEqualTo(Enums.InvoiceStatus.PAID);

        // hold converted; invoice payment CONFIRMED with booking reference
        Dtos.InvoiceDetailRow detail = b2b.invoiceDetail(inv.id());
        assertThat(detail.payments()).hasSize(1);
        assertThat(detail.payments().get(0).status()).isEqualTo("CONFIRMED");
        assertThat(detail.payments().get(0).route()).isEqualTo("BOOK_TRANSFER"); // default route (BC p.9)

        // second pay attempt refuses (invoice no longer payable)
        assertThatThrownBy(() -> b2b.payInvoice(m.getId(), inv.id(), null, "confirm"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("TC-B2B-03 · Replayed bank confirmation absorbed by idempotency — no double debit/credit")
    void tcB2b03_duplicateBankConfirmationAbsorbed() {
        var inv = orderAndInvoice(m.getId(), s, cableItemId, bd("20"));
        Dtos.InvoicePaid paid = payInvoiceOk(m.getId(), inv.id());

        BigDecimal merchantAfter = available(m.getId());
        BigDecimal supplierAfter = available(s.getId());

        Dtos.InvoicePayResult replay = simulation.simulateInvoicePayment(
                paid.invoicePaymentId(), "confirm", "uat-admin", "MINDLABS_ADMIN");
        assertThat(((Dtos.InvoicePaid) replay).label()).contains("Already confirmed");

        assertThat(available(m.getId())).isEqualByComparingTo(merchantAfter);
        assertThat(available(s.getId())).isEqualByComparingTo(supplierAfter);
        assertThat(subledger.findBySourceRefIdAndSource(paid.invoicePaymentId(), Enums.SubledgerSource.SUPPLIER_PAYMENT))
                .hasSize(2);
        assertThat(invoiceEntity(inv.id()).getStatus()).isEqualTo(Enums.InvoiceStatus.PAID);
    }

    @Test
    @DisplayName("TC-B2B-04 · Bank timeout: hold preserved, invoice unpaid; retry with final answer completes")
    void tcB2b04_timeoutHoldPreservedThenRetry() {
        var inv = orderAndInvoice(m.getId(), s, cableItemId, bd("20"));

        Dtos.InvoicePayResult r = b2b.payInvoice(m.getId(), inv.id(), null, "timeout");
        assertThat(r).isInstanceOf(Dtos.InvoicePending.class);
        assertThat(r instanceof Dtos.InvoicePending ? ((Dtos.InvoicePending) r).label() : "").containsIgnoringCase("hold preserved");

        // timeout-safe: hold PRESERVED, invoice unpaid
        assertThat(ledger.activeHolds(m.getId())).hasSize(1);
        assertThat(available(m.getId())).isEqualByComparingTo("5000.00"); // still held, not lost
        assertThat(invoiceEntity(inv.id()).getStatus()).isEqualTo(Enums.InvoiceStatus.ISSUED);
        var ip = invoicePayments.findByInvoiceIdOrderByCreatedAtDesc(inv.id()).get(0);
        assertThat(ip.getStatus()).isEqualTo(Enums.InvoicePaymentStatus.TIMED_OUT);

        // later the bank confirms -> retried and completes atomically
        Dtos.InvoicePayResult retry = b2b.retryTimedOut(m.getId(), inv.id(), "confirm");
        assertThat(retry).isInstanceOf(Dtos.InvoicePaid.class);
        assertThat(invoiceEntity(inv.id()).getStatus()).isEqualTo(Enums.InvoiceStatus.PAID);
        assertThat(available(m.getId())).isEqualByComparingTo("5000.00");
        assertThat(available(s.getId())).isEqualByComparingTo("5000.00");
        assertThat(ledger.activeHolds(m.getId())).isEmpty();
    }

    @Test
    @DisplayName("TC-B2B-05 · Insufficient balance refused — shortfall reported, no overdraft (UAT SB p.11)")
    void tcB2b05_insufficientBalanceRefused() {
        debit(m.getId(), "6000.00"); // reduce to N$4,000 available
        var inv = orderAndInvoice(m.getId(), s, cableItemId, bd("32")); // 8,000

        Dtos.InvoicePayResult r = b2b.payInvoice(m.getId(), inv.id(), null, "confirm");
        assertThat(r).isInstanceOf(Dtos.InvoiceInsufficient.class);
        assertThat(((Dtos.InvoiceInsufficient) r).shortfall()).isEqualByComparingTo("4000.00");

        // no money moved, no hold, invoice still open
        assertThat(available(m.getId())).isEqualByComparingTo("4000.00");
        assertThat(ledger.activeHolds(m.getId())).isEmpty();
        assertThat(invoicePayments.findByInvoiceIdOrderByCreatedAtDesc(inv.id())).isEmpty();
        assertThat(invoiceEntity(inv.id()).getStatus()).isEqualTo(Enums.InvoiceStatus.ISSUED);
    }

    @Test
    @DisplayName("TC-B2B-06 · Insufficient → advance funded → issuer confirms → payment allowed (Option B)")
    void tcB2b06_advanceUnlocksBalance() {
        // trading history so the assessment can approve: 40 days × N$1,500 — history is
        // real confirmed money, so it is swept back out to keep the arithmetic exact
        seedHistory(m.getId(), 40, "1500.00");
        debit(m.getId(), "66000.00"); // available = 10,000 + 60,000 − 66,000 = 4,000

        var lender = newLender(bd("0.10"), bd("0.05"), bd("2000"), bd("25000"));
        advances.grantConsent(m.getId(), lender.getId());

        Dtos.AssessmentView view = advances.requestAssessment(m.getId(), lender.getId(), null);
        assertThat(view.decision()).isEqualTo(Enums.AssessmentDecision.APPROVED);
        // lesser-of cap: min(25,000; 20% × 20,000 avg; 25,000) = 4,000
        assertThat(view.offerPrincipal()).isEqualByComparingTo("4000.00");

        String offerId = advanceRepo.findFirstByMerchantIdAndStatusOrderByCreatedAtDesc(
                m.getId(), Enums.AdvanceStatus.OFFERED).orElseThrow().getId();
        advances.accept(m.getId(), offerId);
        advances.fund(offerId, "uat-lender", "LENDER");
        assertThat(available(m.getId())).isEqualByComparingTo("8000.00"); // issuer confirmed the proceeds

        var inv = orderAndInvoice(m.getId(), s, cableItemId, bd("32")); // 8,000
        Dtos.InvoicePaid paid = payInvoiceOk(m.getId(), inv.id());
        assertThat(paid.amount()).isEqualByComparingTo("8000.00");
        assertThat(invoiceEntity(inv.id()).getStatus()).isEqualTo(Enums.InvoiceStatus.PAID);
        assertThat(available(m.getId())).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("TC-B2B-07 · Bank failure: hold released, invoice stays unpaid, retry possible")
    void tcB2b07_failureReleasesHold() {
        var inv = orderAndInvoice(m.getId(), s, cableItemId, bd("20"));

        Dtos.InvoicePayResult r = b2b.payInvoice(m.getId(), inv.id(), null, "fail");
        assertThat(r).isInstanceOf(Dtos.InvoiceFailed.class);

        assertThat(ledger.activeHolds(m.getId())).isEmpty(); // hold released (TC-B2B-07)
        assertThat(balance(m.getId()).held()).isEqualByComparingTo("0");
        assertThat(available(m.getId())).isEqualByComparingTo(OPENING);
        assertThat(invoiceEntity(inv.id()).getStatus()).isEqualTo(Enums.InvoiceStatus.ISSUED);

        // after failure the invoice can be paid again (recovering flow)
        payInvoiceOk(m.getId(), inv.id());
        assertThat(invoiceEntity(inv.id()).getStatus()).isEqualTo(Enums.InvoiceStatus.PAID);
        assertThat(available(m.getId())).isEqualByComparingTo("5000.00");
    }

    @Test
    @DisplayName("TC-B2B-08 · Concurrent orders: only one can spend the available amount")
    void tcB2b08_concurrentOrdersOneSpendOnly() throws Exception {
        var invA = orderAndInvoice(m.getId(), s, cableItemId, bd("12")); // 3,000
        var invB = orderAndInvoice(m.getId(), s, cableItemId, bd("12")); // 3,000
        debit(m.getId(), "6000.00"); // spendable capped at exactly one invoice: 4,000
        assertThat(available(m.getId())).isEqualByComparingTo("4000.00");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<Dtos.InvoicePayResult> first = () -> {
            ready.countDown();
            start.await();
            return b2b.payInvoice(m.getId(), invA.id(), null, "confirm");
        };
        Callable<Dtos.InvoicePayResult> second = () -> {
            ready.countDown();
            start.await();
            return b2b.payInvoice(m.getId(), invB.id(), null, "confirm");
        };
        try {
            Future<Dtos.InvoicePayResult> f1 = pool.submit(first);
            Future<Dtos.InvoicePayResult> f2 = pool.submit(second);
            ready.await();
            start.countDown();

            Dtos.InvoicePayResult r1 = f1.get(30, TimeUnit.SECONDS);
            Dtos.InvoicePayResult r2 = f2.get(30, TimeUnit.SECONDS);

            boolean firstWins = r1 instanceof Dtos.InvoicePaid && r2 instanceof Dtos.InvoiceInsufficient;
            boolean secondWins = r2 instanceof Dtos.InvoicePaid && r1 instanceof Dtos.InvoiceInsufficient;
            assertThat(firstWins ^ secondWins).as("r1=%s r2=%s", r1, r2).isTrue();

            var paidRef = (firstWins ? invA : invB);
            var refusedRef = (firstWins ? invB : invA);
            assertThat(invoiceEntity(paidRef.id()).getStatus()).isEqualTo(Enums.InvoiceStatus.PAID);
            assertThat(invoiceEntity(refusedRef.id()).getStatus()).isEqualTo(Enums.InvoiceStatus.ISSUED);

            // ledger must be exactly one debit + one supplier credit of the winning amount
            assertThat(available(m.getId())).isEqualByComparingTo("1000.00");
            assertThat(available(s.getId())).isEqualByComparingTo("3000.00");
            assertThat(issuerLedger.findByOwnerIdOrderByIdAsc(m.getId()).stream()
                    .filter(e -> e.getEntryType() == Enums.LedgerEntryType.DEBIT)
                    .filter(e -> e.getIssuerReference().startsWith("BT-"))
                    .count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("TC-B2B-09 · Refund linked to original sale: adjusts records, no double credit")
    void tcB2b09_refundLinkedToOriginalSale() {
        Payment sale = payNowConfirmed(m.getId(), "100.00", Enums.PaymentChannel.QR, "0888999001");

        var partial = payments.refund(m.getId(), sale.getId(), new Dtos.RefundRequest(bd("40.00"), "damaged"));
        assertThat(partial.status()).isEqualTo("PROCESSED");
        assertThat(partial.paymentId()).isEqualTo(sale.getId());
        assertThat(available(m.getId())).isEqualByComparingTo("10060.00"); // 10,000 + 100 sale − 40 refund

        // over-refund refused
        assertThatThrownBy(() -> payments.refund(m.getId(), sale.getId(),
                new Dtos.RefundRequest(bd("61.00"), "over")))
                .isInstanceOfSatisfying(SimException.class,
                        e -> assertThat(e.getCode()).isEqualTo("REFUND_AMOUNT"));

        // full remainder closes the payment
        payments.refund(m.getId(), sale.getId(), new Dtos.RefundRequest(bd("60.00"), "rest"));
        assertThat(paymentRepo.findById(sale.getId()).orElseThrow().getStatus())
                .isEqualTo(Enums.PaymentStatus.REFUNDED);

        // refunds of anything that is not a confirmed payment are refused
        Payment unconfirmed = cardPaymentPending(m.getId(), "90.00");
        assertThatThrownBy(() -> payments.refund(m.getId(), unconfirmed.getId(),
                new Dtos.RefundRequest(bd("10.00"), "not confirmed")))
                .isInstanceOf(SimException.class);
    }

    @Test
    @DisplayName("TC-B2B-10 · Hold lifecycle is visible on the merchant; expiry guard exists")
    void tcB2b10_holdLifecycleVisible() {
        var inv = orderAndInvoice(m.getId(), s, cableItemId, bd("4"));
        payInvoiceOk(m.getId(), inv.id()); // hold converted on success
        assertThat(ledger.activeHolds(m.getId())).isEmpty();

        // direct hold stays active and expirable (C11, TC-SET-23)
        int n = seq();
        BalanceHold h = ledger.createHold(m.getId(), bd("1.00"), "DIRECT-" + n, 60);
        assertThat(h.getStatus()).isEqualTo(Enums.HoldStatus.ACTIVE);
        ledger.expireHold(h);
        assertThat(holds.findById(h.getId()).orElseThrow().getStatus()).isEqualTo(Enums.HoldStatus.EXPIRED);
    }
}
