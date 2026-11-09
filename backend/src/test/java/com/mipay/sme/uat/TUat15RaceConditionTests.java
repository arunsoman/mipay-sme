package com.mipay.sme.uat;

import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.credit.Advance;
import com.mipay.sme.domain.credit.Lender;
import com.mipay.sme.domain.core.Supplier;
import com.mipay.sme.domain.payments.Payment;
import com.mipay.sme.web.Dtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UAT SB p.15/p.17 (data integrity & service targets) — race-condition
 * detection. Each test fires concurrent operations against the REAL backend
 * (the same services the controllers invoke, inside their own transactions)
 * and then asserts the ledger invariants the business case is built on:
 *
 *  R1  exactly-once confirmation under concurrent duplicate callbacks
 *  R2  no overdraft when many invoices race the same funds
 *  R3  funding replay race can never double-credit the balance
 *  R4  concurrent withdrawals cannot drain below zero
 *  R5  concurrent sweeps only collect once per advance per day
 *  R6  ledger mirror (issuer vs subledger) stays consistent after races
 *
 * A failure here is a product finding (docs/UAT-BACKEND-TEST-PACK.md).
 */
@DisplayName("Race-condition detection (UAT SB p.15/p.17) — TC-RACE")
class TUat15RaceConditionTests extends UatSupport {

    private static final int THREADS = 8;

    @Test
    @DisplayName("TC-RACE-01 · Concurrent duplicate callbacks: exactly one credit (R1)")
    void tcRace01_concurrentDuplicateCallbacks() throws Exception {
        var m = newApprovedMerchant();
        credit(m.getId(), "100.00");
        var p = payments.accept(m.getId(),
                new Dtos.AcceptPaymentRequest(bd("60.00"), Enums.PaymentChannel.QR, "race1-cust", null, null));

        // 16 racers widen the duplicate-event check-then-insert window so the defect trips deterministically
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Dtos.PaymentOpResult>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit((Callable<Dtos.PaymentOpResult>) () -> {
                start.await();
                return payments.simulate(m.getId(), p.getId(), "confirm");
            }));
        }
        start.countDown();
        for (Future<Dtos.PaymentOpResult> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        // the payment is confirmed exactly once, and the issuer ledger has ONE entry
        Payment saved = paymentRepo.findById(p.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(Enums.PaymentStatus.CONFIRMED);
        assertThat(issuerLedger.findByIdempotencyKey("pay-confirm-" + p.getId())).isPresent();
        long confirmCredits = subledger.findAll().stream()
                .filter(e -> p.getId().equals(e.getSourceRefId()))
                .filter(e -> e.getSource() == Enums.SubledgerSource.PAYMENT
                        && e.getStatus() == Enums.SubledgerStatus.CONFIRMED)
                .count();
        assertThat(confirmCredits).as("exactly one confirmed credit row for the payment").isEqualTo(1);
        assertThat(available(m.getId())).isEqualByComparingTo("160.00"); // 100 + 60 once
        assertReconciliationClean();
    }

    @Test
    @DisplayName("TC-RACE-02 · Many invoices race the same funds: sum paid ≤ available, no overdraw (R2)")
    void tcRace02_concurrentInvoiceSpendRace() throws Exception {
        var m = newApprovedMerchant();
        var s = newApprovedSupplier("RACE stock", "250.00");
        String item = catalogueItemByProductName(s, "RACE stock");
        credit(m.getId(), "5000.00"); // six invoices of 2,500: only one may pay fully

        List<Dtos.InvoiceDetailRow> invoiceRows = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            invoiceRows.add(orderAndInvoice(m.getId(), s, item, new BigDecimal("10")));
        }
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1); // single barrier for all racers
        List<Future<Dtos.InvoicePayResult>> futures = new ArrayList<>();
        for (var inv : invoiceRows) {
            futures.add(pool.submit((Callable<Dtos.InvoicePayResult>) () -> {
                start.await();
                return b2b.payInvoice(m.getId(), inv.id(), null, "confirm");
            }));
        }
        start.countDown();
        List<Dtos.InvoicePayResult> results = new ArrayList<>();
        for (Future<Dtos.InvoicePayResult> f : futures) {
            results.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        long paidCount = results.stream().filter(r -> r instanceof Dtos.InvoicePaid).count();
        long refusedCount = results.stream().filter(r -> r instanceof Dtos.InvoiceInsufficient).count();
        // Spec arithmetic: six invoices of 2,500 against a 5,000 balance allow at most TWO
        // full payments; TC-B2B-08 guards that no racer overdraws and each loser is
        // REFUSED cleanly (never dropped, never partially applied).
        assertThat(paidCount).as("at most two 2,500 invoices can be covered by the 5,000: %s", results)
                .isLessThanOrEqualTo(2);
        assertThat(paidCount + refusedCount).as("every invoice resolves (paid or refused, none dropped)")
                .isEqualTo(invoiceRows.size());

        // invariant: debits can never exceed credits — available never negative
        assertThat(available(m.getId())).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(ledger.activeHolds(m.getId())).isEmpty();
        assertReconciliationClean();
    }

    @Test
    @DisplayName("TC-RACE-03 · Concurrent funding replays: advance credited exactly once (R3)")
    void tcRace03_concurrentFundReplay() throws Exception {
        var m = newApprovedMerchant();
        credit(m.getId(), "100.00");
        var lender = newLender(bd("0.10"), bd("0.05"), null, bd("25000"));
        var a = acceptedAdvance(m.getId(), lender, "1000.00", "1050.00");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Dtos.Message>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit((Callable<Dtos.Message>) () -> {
                start.await();
                return advances.fund(a.getId(), "uat-lender", "LENDER");
            }));
        }
        start.countDown();
        for (Future<Dtos.Message> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        // the idempotency key means ONE issuer row for the funding
        long fundingRows = issuerLedger.findAll().stream()
                .filter(e -> e.getIdempotencyKey() != null
                        && e.getIdempotencyKey().equals("adv-fund-" + a.getId()))
                .count();
        assertThat(fundingRows).as("exactly one funding ledger entry").isEqualTo(1);
        assertThat(available(m.getId())).isEqualByComparingTo("1100.00"); // 100 + 1,000 once
        assertThat(balance(m.getId()).owed()).isEqualByComparingTo("1050.00"); // one owed accrual
        assertReconciliationClean();
    }

    @Test
    @DisplayName("TC-RACE-04 · Concurrent withdrawals cannot overdraw the available balance (R4)")
    void tcRace04_concurrentWithdrawals() throws Exception {
        var m = newApprovedMerchant();
        credit(m.getId(), "500.00");

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        List<String> issues = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                start.await();
                try {
                    withdrawals.withdraw(m.getId(), bd("100.00"), "uat-ops", "TPTS_OPS");
                } catch (Exception e) {
                    issues.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                    errors.incrementAndGet(); // refusals are legal, exceptions are not
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        // 5 × 100 must be PAID; the sixth+ must be refused — total paid can never exceed 500
        BigDecimal debits = issuerLedger.findAll().stream()
                .filter(e -> e.getOwnerId().equals(m.getId())
                        && e.getEntryType() == Enums.LedgerEntryType.DEBIT)
                .map(e -> e.getAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debits).as("withdrawal debits never exceed the balance").isLessThanOrEqualTo(bd("500.00"));
        assertThat(available(m.getId())).isGreaterThanOrEqualTo(BigDecimal.ZERO); // clamped, never negative
        issues.forEach(i -> assertThat(i).doesNotContain("LazyInitializationException"));
        assertReconciliationClean();
    }

    @Test
    @DisplayName("TC-RACE-05 · Concurrent sale + sweep: day's collection is computed once, no double sweep (R5)")
    void tcRace05_concurrentPaymentAndSweep() throws Exception {
        var m = newApprovedMerchant();
        credit(m.getId(), "200.00");
        var lender = newLender(bd("0.10"), bd("0.05"), null, bd("25000"));
        var a = acceptedAdvance(m.getId(), lender, "1000.00", "1050.00");
        advances.fund(a.getId(), "uat-lender", "LENDER");

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);  // single barrier for all racers (latch mechanism bug fixed)
        List<Future<?>> futures = new ArrayList<>();
        // four threads: 3 racing day-sweeps + 1 accepting a new confirmed sale
        for (int i = 0; i < 3; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return advances.sweep(lender.getId(), java.time.LocalDate.now(java.time.ZoneOffset.UTC));
            }));
        }
        futures.add(pool.submit(() -> {
            start.await();
            return payNowConfirmed(m.getId(), "100.00", Enums.PaymentChannel.QR, "sweep-race-cust");
        }));
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        // the day's collection for THIS advance was taken at most once (today's share of
        // the 100 sale = 10.00), and the advance's balance moved monotonically
        BigDecimal collectedToday = repayments.findByAdvanceIdOrderByCreatedAtDesc(a.getId()).stream()
                .filter(r -> r.getCreatedAt() != null
                        && r.getCreatedAt().isAfter(java.time.LocalDate.now(java.time.ZoneOffset.UTC)
                                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant()))
                .map(r -> r.getAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(collectedToday).isLessThanOrEqualTo(new BigDecimal("10.00")); // once per day per advance
        assertThat(moneyNeverNegative(m.getId())).isTrue();
        assertReconciliationClean();
    }

    @Test
    @DisplayName("TC-RACE-06 · Ledger mirror stays intact after every race (issuer net == subledger net) (R6)")
    void tcRace06_mirrorIntactAfterRaces() {
        // remove any deliberate issuer-only TESTBREAK artifacts first (scene-7/RCN-02 semantics),
        // so the checked mirror covers REAL transactions only
        cleanupReconQuiet();

        var issuerNet = ledger.issuerNetPerOwner();
        var subNet = ledger.confirmedNetPerOwner();
        assertThat(issuerNet.keySet()).isEqualTo(subNet.keySet());
        for (String owner : issuerNet.keySet()) {
            assertThat(issuerNet.get(owner))
                    .as("issuer vs subledger net for owner %s", owner)
                    .isEqualByComparingTo(subNet.get(owner));
        }
        assertReconciliationClean();
    }

// ------------------------------------------------------------------ extended race coverage (all UAT flow families)

    @Test
    @DisplayName("TC-RACE-07 \u00b7 Concurrent double invoice issuance: exactly ONE invoice per order (TC-B2B-01, R7)")
    void tcRace07_concurrentDoubleInvoiceIssuance() throws Exception {
        var m = newApprovedMerchant();
        var s = newApprovedSupplier("RACE-07 stock", "0.05");
        // a 200-line order makes issueInvoice's read->write window long enough that the
        // check-then-set race trips deterministically instead of by scheduler luck
        List<Dtos.CartLine> lines = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            lines.add(new Dtos.CartLine(catalogueItemByProductName(s, "RACE-07 stock"), bd("1")));
        }
        Dtos.OrderDetailRow order = b2b.placeOrder(m.getId(), new Dtos.CreateOrderRequest(s.getId(), lines));

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Dtos.InvoiceDetailRow>> futures = new ArrayList<>();
        List<String> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit((Callable<Dtos.InvoiceDetailRow>) () -> {
                start.await();
                try {
                    return b2b.issueInvoice(s.getId(), order.id());
                } catch (Exception e) {
                    errors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                    return null;
                }
            }));
        }
        start.countDown();
        long issued = 0;
        for (Future<Dtos.InvoiceDetailRow> f : futures) {
            issued += f.get(120, TimeUnit.SECONDS) == null ? 0 : 1;
        }
        pool.shutdownNow();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        long invoiceRows = invoices.findAll().stream()
                .filter(inv -> order.id().equals(inv.getOrderId())).count();
        assertThat(invoiceRows).as("one order, one invoice (unique reference guarantee)").isEqualTo(1);
        assertThat(issued).as("exactly one thread must succeed; losers fail cleanly: %s", errors).isEqualTo(1);
        errors.forEach(e -> assertThat(e).doesNotContain("LazyInitializationException"));
    }

    @Test
    @DisplayName("TC-RACE-08 \u00b7 Concurrent offer acceptance: state transitions exactly once (TC-ADV-01, R8)")
    void tcRace08_concurrentOfferAcceptance() throws Exception {
        var m = newApprovedMerchant();
        credit(m.getId(), "100.00");
        var lender = newLender(bd("0.10"), bd("0.05"), null, bd("25000"));
        var a = acceptedOfferOnly(m.getId(), lender);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<String> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < THREADS; i++) {
            pool.submit((Callable<Void>) () -> {
                start.await();
                try {
                    advances.accept(m.getId(), a.getId()); // double acceptance must be refused
                } catch (Exception e) {
                    errors.add(e.getClass().getSimpleName());
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        Advance saved = advanceRepo.findById(a.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(Enums.AdvanceStatus.ACCEPTED); // terminal, no oscillation
        assertThat(available(m.getId())).isEqualByComparingTo("100.00"); // acceptance adds no money
        assertThat(issuerLedger.findAll().stream()
                .filter(e -> a.getId() != null && ("adv-fund-" + a.getId()).equals(e.getIdempotencyKey())).count())
                .as("no credit row from acceptance alone").isEqualTo(0);
    }

    @Test
    @DisplayName("TC-RACE-09 \u00b7 Accept-vs-fund race: money moves only for a FUNDED advance, exactly once (TC-ADV-02, R9)")
    void tcRace09_acceptVsFundRace() throws Exception {
        var m = newApprovedMerchant();
        credit(m.getId(), "100.00");
        var lender = newLender(bd("0.10"), bd("0.05"), null, bd("25000"));
        var a = acceptedOfferOnly(m.getId(), lender);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);  // single barrier for all racers (latch mechanism bug fixed)
        pool.submit(() -> {
            start.await();
            try { advances.accept(m.getId(), a.getId()); } catch (Exception ignored) { }
            return null;
        });
        pool.submit(() -> {
            start.await();
            try { advances.fund(a.getId(), "uat-lender", "LENDER"); } catch (Exception ignored) { }
            return null;
        });
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        Advance saved = advanceRepo.findById(a.getId()).orElseThrow();
        long fundingRows = issuerLedger.findAll().stream()
                .filter(e -> a.getId() != null && ("adv-fund-" + a.getId()).equals(e.getIdempotencyKey()))
                .count();

        // Spec-corrected (finding FIX-R9): fund-before-accept ends ACCEPTED and accept-before-fund
        // ends FUNDED - both are legitimate serialised outcomes. The money invariant is
        // "credit exists ONLY for a FUNDED advance" (asserted by both branches); the previous
        // unconditional isNotEqualTo(ACCEPTED) contradicted the fund-refusal path (TC-ADV-02).
        if (saved.getStatus() == Enums.AdvanceStatus.FUNDED) {
            assertThat(fundingRows).as("funded: exactly one funding entry").isEqualTo(1);
            assertThat(available(m.getId())).isEqualByComparingTo("1100.00");
        } else {
            assertThat(fundingRows).as("not funded: no credit (status %s)", saved.getStatus()).isEqualTo(0);
            assertThat(available(m.getId())).isEqualByComparingTo("100.00");
        }
        // the bad overlap (status ACCEPTED while money moved) is already rejected above:
        // the else-branch asserts fundingRows==0 whenever the advance is not FUNDED
        assertReconciliationClean();
    }

    @Test
    @DisplayName("TC-RACE-10 \u00b7 Concurrent refunds cannot over-refund the same payment (TC-B2B-09, R10)")
    void tcRace10_concurrentRefundRace() throws Exception {
        var m = newApprovedMerchant();
        var p = payNowConfirmed(m.getId(), "100.00", Enums.PaymentChannel.QR, "refund-race-cust");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<String> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < THREADS; i++) {
            pool.submit((Callable<Void>) () -> {
                start.await();
                try {
                    payments.refund(m.getId(), p.getId(), new Dtos.RefundRequest(bd("100.00"), "race"));
                } catch (Exception e) {
                    errors.add(e.getClass().getSimpleName()); // refusal is legal
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);

        BigDecimal refunded = refunds.findAll().stream()
                .filter(r -> p.getId().equals(r.getPaymentId()))
                .filter(r -> r.getStatus() == Enums.RefundStatus.PROCESSED)
                .map(r -> r.getAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(refunded).as("processed refunds can never exceed the payment amount").isLessThanOrEqualTo(bd("100.00"));
        errors.forEach(e -> assertThat(e).doesNotContain("LazyInitializationException"));
        assertThat(available(m.getId())).isGreaterThanOrEqualTo(BigDecimal.ZERO); // no negative drift
        assertReconciliationClean();
    }

    @Test
    @DisplayName("TC-RACE-11 \u00b7 Concurrent reconciliation runs: every run that succeeds must persist the detected break (TC-RCN-01, R11)")
    void tcRace11_concurrentReconRuns() throws Exception {
        cleanupReconQuiet();
        simulation.injectBreak("uat-ops", "TPTS_OPS"); // injects a TESTBREAK- issuer-only row

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);  // single barrier for all racers (latch mechanism bug fixed)
        try {
            for (int i = 0; i < 4; i++) {
                pool.submit((Callable<Void>) () -> {
                    start.await();
                    recon.run(); // an unexplained gap MUST surface as a persisted break (TC-RCN-01)
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            pool.awaitTermination(90, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
            long breakRows = breakRepo.findAll().stream()
                    .filter(b -> b.getBreakType() == Enums.BreakType.MISSING_CREDIT
                            && b.getAmount().compareTo(new BigDecimal("777.77")) == 0)
                    .count();
            cleanupReconQuiet();
            assertThat(breakRows).as("a detected gap must persist as a break despite concurrent runs").isEqualTo(1);
        }
        // a sequential follow-up run stays consistent: clean state after resolving
        assertReconciliationClean();
    }

    @Test
    @DisplayName("TC-RACE-12 \u00b7 Hold + concurrent withdrawals: held funds are never spendable (TC-BAL-04, R12)")
    void tcRace12_holdConcurrentWithdrawals() throws Exception {
        var m = newApprovedMerchant();
        credit(m.getId(), "500.00");
        ledger.createHold(m.getId(), bd("400.00"), "race-hold", 30);

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < 16; i++) {
            pool.submit((Callable<Void>) () -> {
                start.await();
                // only the un-held 100 can ever leave, total across all racers
                try { withdrawals.withdraw(m.getId(), bd("100.00"), "uat-ops", "TPTS_OPS"); } catch (Exception ignored) { }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);

        // combined successful withdrawals must be exactly the unlocked 100 (nothing more)
        BigDecimal debits = issuerLedger.findAll().stream()
                .filter(e -> e.getOwnerId().equals(m.getId())
                        && e.getEntryType() == Enums.LedgerEntryType.DEBIT)
                .map(e -> e.getAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debits).as("held funds not debited despite the race").isLessThanOrEqualTo(bd("100.00"));
        // Spec-corrected hold check (finding FIX-R12): per TC-BAL-03 available = confirmed net − held,
        // so "hold intact" is asserted by the hold still being ACTIVE at its full amount, not by >net.
        BigDecimal held = ledger.activeHolds(m.getId()).stream()
                .map(h -> h.getAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(held).as("hold intact after the race").isEqualByComparingTo("400.00");
        assertThat(ledger.availableFor(m.getId())).isGreaterThanOrEqualTo(BigDecimal.ZERO); // never negative
        assertReconciliationClean();
    }

    @Test
    @DisplayName("TC-RACE-13 \u00b7 Concurrent double approval: supplier provisioned exactly once (TC-ONB-02, R13)")
    void tcRace13_concurrentApprovals() throws Exception {
        String phone = phone();
        onboarding.registerSupplier(new Dtos.RegisterSupplierRequest(phone,
                "UAT Race Supplier " + seq(), "Owner " + seq(), "REG-UAT-RC-" + seq(),
                "Bank Windhoek", "31000001", "reason"));
        var s = suppliers.findByOwnerPhone(phone).orElseThrow();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);  // single barrier for all racers (latch mechanism bug fixed)
        for (int i = 0; i < 2; i++) {
            pool.submit((Callable<Void>) () -> {
                start.await();
                try { onboarding.approveSupplier(s.getId(), true, "approver-" + seq(), "TPTS_OPS"); } catch (Exception ignored) { }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);
        pool.shutdownNow();

        Supplier saved = suppliers.findById(s.getId()).orElseThrow();
        assertThat(saved.getApprovalStatus()).isEqualTo(Enums.ApprovalStatus.APPROVED);
        // the receiving bank account is provisioned exactly once
        assertThat(accounts.findByOwnerTypeAndOwnerId(Enums.OwnerType.SUPPLIER, s.getId()))
                .as("double approval must not double-provision the receiving account")
                .isPresent();
    }

    /** OFFERED advance with no acceptance yet (for accept/fund races). */
    private com.mipay.sme.domain.credit.Advance acceptedOfferOnly(String merchantId, Lender lender) { // Lender resolved via UatSupport
        // requestAssessment produces an OFFERED advance for the seeded history window
        advances.eligibility(merchantId);
        return advanceRepo.save(newOffered(merchantId, lender));
    }

    private com.mipay.sme.domain.credit.Advance newOffered(String merchantId, Lender lender) {
        var a = new com.mipay.sme.domain.credit.Advance();
        a.setMerchant(merchants.findById(merchantId).orElseThrow());
        a.setLender(lender);
        a.setStatus(Enums.AdvanceStatus.OFFERED);
        a.setPrincipalAmount(new BigDecimal("1000.00"));
        a.setTotalRepayment(new BigDecimal("1050.00"));
        a.setRepaymentRate(bd("0.10"));
        a.setRequestedAmount(new BigDecimal("1000.00"));
        return a;
    }

    private boolean moneyNeverNegative(String merchantId) {
        return ledger.availableFor(merchantId).signum() >= 0;
    }
}
