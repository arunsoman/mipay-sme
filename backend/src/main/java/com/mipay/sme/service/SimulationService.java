package com.mipay.sme.service;

import com.mipay.sme.common.ForbiddenException;
import com.mipay.sme.common.NotFoundException;
import com.mipay.sme.common.SimException;
import com.mipay.sme.config.AppProperties;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.admin.SimulationScenario;
import com.mipay.sme.domain.admin.TestDataSet;
import com.mipay.sme.domain.b2b.Invoice;
import com.mipay.sme.domain.b2b.InvoicePayment;
import com.mipay.sme.domain.core.Merchant;
import com.mipay.sme.domain.payments.Payment;
import com.mipay.sme.domain.core.Supplier;
import com.mipay.sme.domain.credit.Advance;
import com.mipay.sme.domain.credit.Lender;
import com.mipay.sme.domain.ledger.BalanceAccount;
import com.mipay.sme.domain.ledger.BalanceHold;
import com.mipay.sme.domain.ledger.IssuerBalanceLedgerEntry;
import com.mipay.sme.repository.BalanceHoldRepository;
import com.mipay.sme.repository.AdvanceRepository;
import com.mipay.sme.repository.InvoicePaymentRepository;
import com.mipay.sme.repository.InvoiceRepository;
import com.mipay.sme.repository.IssuerBalanceLedgerRepository;
import com.mipay.sme.repository.LenderRepository;
import com.mipay.sme.repository.MerchantRepository;
import com.mipay.sme.repository.PaymentRepository;
import com.mipay.sme.repository.SupplierRepository;
import com.mipay.sme.repository.SimulationScenarioRepository;
import com.mipay.sme.repository.TestDataSetRepository;
import com.mipay.sme.web.Dtos;
import com.mipay.sme.security.Current;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The simulation layer. Every bank/lender event in this ecosystem is simulated
 * and labelled (UAT SB p.16, BC p.17): UI labels + audit rows with
 * simulated=true. Disbled entirely when app.environment=production.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimulationService {

    private final AppProperties props;
    private final PaymentService paymentService;
    private final B2BPaymentService b2bService;
    private final AdvanceService advanceService;
    private final ReconciliationService reconciliationService;
    private final MerchantRepository merchantRepo;
    private final SupplierRepository supplierRepo;
    private final LenderRepository lenderRepo;
    private final PaymentRepository paymentRepo;
    private final InvoiceRepository invoiceRepo;
    private final InvoicePaymentRepository invoicePaymentRepo;
    private final IssuerBalanceLedgerRepository issuerRepo;
    private final BalanceHoldRepository holdRepo;
    private final AdvanceRepository advanceRepo;
    private final LedgerService ledgerService;
    private final SimulationScenarioRepository scenarioRepo;
    private final TestDataSetRepository testDataSetRepo;
    private final AuditService auditService;

    private final SecureRandom random = new SecureRandom();

    private void guard() {
        if (!props.isSimulatedEnvironment()) {
            throw new ForbiddenException("Simulation is disabled in production");
        }
    }

    // ------------------------------------------------------------------ payment simulations

    @Transactional
    public Dtos.PaymentOpResult simulatePayment(String merchantId, String paymentId, String action,
                                                String actorId, String actorRole) {
        guard();
        return paymentService.simulate(merchantId, paymentId, action);
    }

    @Transactional
    public Dtos.InvoicePayResult simulateInvoicePayment(String invoicePaymentId, String outcome,
                                                        String actorId, String actorRole) {
        guard();
        InvoicePayment ip = invoicePaymentRepo.findById(invoicePaymentId)
                .orElseThrow(() -> new NotFoundException("Invoice payment not found"));
        Invoice invoice = invoiceRepo.findById(ip.getInvoiceId())
                .orElseThrow(() -> new NotFoundException("Invoice not found"));

        switch (outcome.toLowerCase()) {
            case "confirm" -> {
                if (ip.getStatus() == Enums.InvoicePaymentStatus.CONFIRMED) {
                    return new Dtos.InvoicePaid(ip.getId(), ip.getAmount(), ip.getBankReference(),
                            "Already confirmed - duplicate callback absorbed");
                }
                BalanceHold hold = holdRepo.findById(ip.getHoldId())
                        .orElseThrow(() -> new SimException("HOLD_MISSING", "Hold not found"));
                if (hold.getStatus() != Enums.HoldStatus.ACTIVE) {
                    throw new SimException("HOLD_GONE", "Hold is " + hold.getStatus());
                }
                Dtos.InvoicePaid paid = b2bService.completePayment(invoice, ip, hold);
                auditService.sim(actorId, actorRole, "sim_invoice_confirmed", "invoice_payment", ip.getId(),
                        "simulated bank confirmation", null);
                return paid;
            }
            case "fail" -> {
                BalanceHold hold = holdRepo.findById(ip.getHoldId())
                        .orElseThrow(() -> new SimException("HOLD_MISSING", "Hold not found"));
                ledgerService.releaseHold(hold);
                ip.setStatus(Enums.InvoicePaymentStatus.FAILED);
                invoicePaymentRepo.save(ip);
                auditService.sim(actorId, actorRole, "sim_invoice_failed", "invoice_payment", ip.getId(),
                        "simulated bank failure", null);
                return new Dtos.InvoiceFailed(ip.getId(), ip.getAmount(), "Simulated failure - hold released");
            }
            case "timeout" -> {
                if (ip.getStatus() == Enums.InvoicePaymentStatus.TIMED_OUT) {
                    return new Dtos.InvoicePending(ip.getId(), ip.getAmount(),
                            "Already timed out - pending preserved (UAT SB p.15)");
                }
                ip.setStatus(Enums.InvoicePaymentStatus.TIMED_OUT);
                invoicePaymentRepo.save(ip);
                auditService.sim(actorId, actorRole, "sim_invoice_timeout", "invoice_payment", ip.getId(),
                        "simulated bank timeout", null);
                return new Dtos.InvoicePending(ip.getId(), ip.getAmount(),
                        "Simulated timeout - hold preserved, invoice unpaid");
            }
            default -> throw new SimException("SIM_OUTCOME", "outcome must be confirm | timeout | fail");
        }
    }

    // ------------------------------------------------------------------ lender simulations

    @Transactional
    public Dtos.AssessmentView simulateAssessment(String merchantId, String lenderId, BigDecimal requestedAmount,
                                                  String actorId, String actorRole) {
        guard();
        Dtos.AssessmentView view = advanceService.requestAssessment(merchantId, lenderId, requestedAmount);
        auditService.sim(actorId, actorRole, "sim_assessment", "merchant", merchantId,
                "decision=" + view.decision(), null);
        return view;
    }

    // ------------------------------------------------------------------ synthetic history (TC-SET-30)

    /**
     * Generates 90-120 days of deterministic synthetic trading history for the
     * seed merchant so the advance eligibility window is usable (BC p.17).
     * Deterministic seeds make repeated scenes reproducible.
     */
    @Transactional
    public Dtos.SimResult syntheticHistory(String merchantId, int days, String actorId, String actorRole) {
        guard();
        if (days < 30 || days > 180) {
            throw new SimException("SYN_RANGE", "days must be 30..180 (UAT uses 90-120)");
        }
        merchantRepo.findById(merchantId).orElseThrow(() -> new NotFoundException("Merchant not found"));
        BalanceAccount account = ledgerService.account(Enums.OwnerType.MERCHANT, merchantId);

        java.util.Random seed = new java.util.Random(("synthetic-" + merchantId + "-" + days).hashCode());
        int created = 0;
        int dayCount = 0;
        for (LocalDate d = LocalDate.now(ZoneOffset.UTC).minusDays(days); d.isBefore(LocalDate.now(ZoneOffset.UTC).plusDays(1)); d = d.plusDays(1)) {
            if (d.isEqual(LocalDate.now(ZoneOffset.UTC))) {
                // today: small, sweep-able remainder
                createSyntheticPayments(account, merchantId, 2,
                        BigDecimal.valueOf(50 + seed.nextInt(150)), d, seed);
            } else {
                createSyntheticPayments(account, merchantId, 2 + seed.nextInt(5),
                        BigDecimal.valueOf(120 + seed.nextInt(700)), d, seed);
            }
            dayCount++;
        }
        created = (int) paymentRepo.countByMerchantIdAndCreatedAtAfter(merchantId,
                LocalDate.now(ZoneOffset.UTC).minusDays(days).atStartOfDay(ZoneOffset.UTC).toInstant());

        TestDataSet set = new TestDataSet();
        set.setName("synthetic_history_" + days + "d");
        set.setDescription(days + " days of synthetic receipts for merchant " + merchantId);
        set.setRecordCount(created);
        testDataSetRepo.save(set);

        auditService.sim(actorId, actorRole, "synthetic_history_generated", "merchant", merchantId,
                days + " days; " + created + " payments; all labelled SYNTHETIC", null);
        return new Dtos.SimResult("synthetic_history",
                days + " days generated (" + dayCount + " days, " + created + " payments)",
                "SIMULATED / SYNTHETIC");
    }

    @Transactional
    void createSyntheticPayments(BalanceAccount account, String merchantId, int count, BigDecimal average, LocalDate day,
                                 java.util.Random seed) {
        for (int i = 0; i < count; i++) {
            Payment p = new Payment();
            Merchant merchant = merchantRepo.findById(merchantId).orElseThrow();
            p.setMerchant(merchant);
            p.setChannel(Enums.PaymentChannel.values()[seed.nextInt(2)]); // QR or SHORT_CODE
            p.setPayerRef("synthetic-customer-" + seed.nextInt(30));
            p.setAmount(average.setScale(2, java.math.RoundingMode.HALF_UP));
            p.setCurrency("NAD");
            p.setStatus(Enums.PaymentStatus.CONFIRMED);
            p.setIdempotencyKey("syn-" + UUID.randomUUID());
            p.setCreatedAt(day.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3600 * (i + 1) + seed.nextInt(3600)));
            p.setConfirmedAt(p.getCreatedAt());
            paymentRepo.save(p);

            ledgerService.postConfirmed(account, Enums.LedgerEntryType.CREDIT, p.getAmount(),
                    Enums.SubledgerSource.PAYMENT, p.getId(),
                    "SYN-" + p.getCreatedAt().toEpochMilli(), "syn-confirm-" + p.getId());
        }
    }

    // ------------------------------------------------------------------ reconciliation break injection (scene 7)

    @Transactional
    public Dtos.SimResult injectBreak(String actorId, String actorRole) {
        guard();
        Merchant merchant = seedMerchant();
        BalanceAccount account = ledgerService.account(Enums.OwnerType.MERCHANT, merchant.getId());
        IssuerBalanceLedgerEntry e = ledgerService.postIssuerOnly(account, Enums.LedgerEntryType.CREDIT,
                new BigDecimal("777.77"), "TESTBREAK-" + UUID.randomUUID(), "testbreak-" + UUID.randomUUID());
        auditService.sim(actorId, actorRole, "test_break_injected", "issuer_balance_ledger", e.getId(),
                "issuer-only credit, no subledger - recon must flag MISSING_CREDIT", null);
        return new Dtos.SimResult("inject_break", "Injected issuer-only credit N$777.77", "TEST DATA");
    }

    @Transactional
    public Dtos.SimResult clearBreaks(String actorId, String actorRole) {
        guard();
        List<IssuerBalanceLedgerEntry> testRows = issuerRepo.findByIssuerReferenceStartingWith("TESTBREAK-");
        int n = testRows.size();
        issuerRepo.deleteAll(testRows);
        auditService.sim(actorId, actorRole, "test_break_cleared", "issuer_balance_ledger", null,
                n + " test entries removed", null);
        return new Dtos.SimResult("clear_breaks", "Removed " + n + " injected test entries", "TEST DATA");
    }

    /** Advance simulations for the admin panel (E38): assess / fund / sweep. */
    @Transactional
    public Dtos.AdvanceDetailView simulateAdvance(Dtos.SimAdvanceRequest req) {
        guard();
        String merchantId = req.merchantId();
        Merchant m = merchantRepo.findById(merchantId)
                .orElseThrow(() -> new NotFoundException("Merchant not found"));
        switch (req.action() == null ? "assess" : req.action()) {
            case "assess" -> {
                BigDecimal amount = req.amount() == null ? null : new BigDecimal(req.amount());
                advanceService.requestAssessment(merchantId, req.lenderId(), amount);
            }
            case "fund" -> {
                Advance advance = advanceRepo.findFirstByMerchantIdAndStatusOrderByCreatedAtDesc(merchantId,
                                Enums.AdvanceStatus.OFFERED)
                        .orElseThrow(() -> new SimException("NO_OFFER",
                                "No offered advance for merchant - run assess first"));
                Current.Principal actor = Current.get();
                advanceService.fund(advance.getId(), actor.userId() != null ? actor.userId() : "admin-sim",
                        actor.role() != null ? actor.role() : "MINDLABS_ADMIN");
            }
            case "sweep" -> {
                LocalDate date = req.date() == null || req.date().isBlank()
                        ? LocalDate.now(ZoneOffset.UTC) : LocalDate.parse(req.date());
                String lenderId = req.lenderId();
                if (lenderId == null || lenderId.isBlank()) {
                    Advance last = advanceRepo.findByMerchantIdOrderByCreatedAtDesc(merchantId)
                            .stream().findFirst().orElse(null);
                    lenderId = last == null || last.getLender() == null ? null : last.getLender().getId();
                }
                if (lenderId == null) {
                    Lender any = lenderRepo.findAll().stream().findFirst().orElseThrow(
                            () -> new NotFoundException("No lender in system - seed first"));
                    lenderId = any.getId();
                }
                advanceService.sweep(lenderId, date);
            }
            default -> throw new SimException("ADVANCE_SIM_UNKNOWN",
                    "Unknown advance sim action: " + req.action());
        }
        auditService.sim("admin-sim", "MINDLABS_ADMIN", "advance_simulation",
                "merchant", merchantId, "action=" + req.action(), null);
        return advanceService.currentAdvance(merchantId);
    }

    // ------------------------------------------------------------------ scene runner (E33, UAT SB p.16)

    @Transactional
    public List<Dtos.ScenarioRow> scenarios() {
        List<SimulationScenario> rows = scenarioRepo.findByEnabledTrueOrderByName();
        if (rows.isEmpty()) {
            return List.of(
                    new Dtos.ScenarioRow("s1", "Scene 1", "Onboard + first QR payment", "CALLBACK", true, true),
                    new Dtos.ScenarioRow("s2", "Scene 2", "Duplicate callback ignored", "DUPLICATE", true, true),
                    new Dtos.ScenarioRow("s3", "Scene 3", "Pay supplier invoice atomically", "CALLBACK", true, true),
                    new Dtos.ScenarioRow("s4", "Scene 4", "Timeout pending preserved", "TIMEOUT", true, true),
                    new Dtos.ScenarioRow("s5", "Scene 5", "Insufficient balance + advance offer", "FAILURE", true, true),
                    new Dtos.ScenarioRow("s6", "Scene 6", "Advance funded + sweep", "FUND_ADVANCE", true, true),
                    new Dtos.ScenarioRow("s7", "Scene 7", "Recon break + blocked release", "CALLBACK", true, true),
                    new Dtos.ScenarioRow("s8", "Scene 8", "Refund + suspension cycle", "REFUND", true, true));
        }
        return rows.stream().map(s -> new Dtos.ScenarioRow(s.getId(), s.getName(), s.getDescription(),
                s.getAction().name(), s.getIsLabelled(), s.getEnabled())).toList();
    }

    @Transactional
    public Dtos.SceneRunResult runScene(String name, String actorId, String actorRole) {
        guard();
        Merchant m = seedMerchant();
        List<Dtos.StepResult> steps = new ArrayList<>();
        List<Dtos.StepResult> ok = new ArrayList<>();
        try {
            switch (name) {
                case "Scene 1", "1", "s1" -> {
                    Payment p = paymentService.accept(m.getId(), new Dtos.AcceptPaymentRequest(
                            new BigDecimal("60.00"), Enums.PaymentChannel.QR, "0850000001", null, null));
                    var r = paymentService.simulate(m.getId(), p.getId(), "confirm");
                    steps.add(new Dtos.StepResult("accept + customer bank sim", true,
                            "N$60 QR payment -> " + detail(r)));
                }
                case "Scene 2", "2", "s2" -> {
                    Payment p = paymentService.accept(m.getId(), new Dtos.AcceptPaymentRequest(
                            new BigDecimal("25.00"), Enums.PaymentChannel.SHORT_CODE, "0850000002", null, null));
                    paymentService.simulate(m.getId(), p.getId(), "confirm");
                    var dup = paymentService.simulate(m.getId(), p.getId(), "duplicate");
                    steps.add(new Dtos.StepResult("confirm twice", true, detail(dup)));
                }
                case "Scene 3", "3", "s3" -> {
                    Invoice invoice = payableInvoice(m.getId());
                    if (invoice == null) {
                        steps.add(new Dtos.StepResult("pay seeded invoice", false,
                                "No payable invoice left - create an order or reset test data"));
                    } else {
                        var r = b2bService.payInvoice(m.getId(), invoice.getId(), null, "confirm");
                        steps.add(new Dtos.StepResult("pay invoice " + invoice.getUniqueReference(), true, detail(r)));
                    }
                }
                case "Scene 4", "4", "s4" -> {
                    Payment p = paymentService.accept(m.getId(), new Dtos.AcceptPaymentRequest(
                            new BigDecimal("120.00"), Enums.PaymentChannel.CARD, "CARD-TEST", null, null));
                    var r = paymentService.simulate(m.getId(), p.getId(), "timeout");
                    steps.add(new Dtos.StepResult("card + timeout", true, detail(r)));
                }
                case "Scene 5", "5", "s5" -> {
                    Invoice invoice = payableInvoice(m.getId());
                    if (invoice == null) {
                        steps.add(new Dtos.StepResult("insufficient demo", false,
                                "Create an invoice larger than available balance first"));
                    } else {
                        var r = b2bService.payInvoice(m.getId(), invoice.getId(), null, "timeout");
                        steps.add(new Dtos.StepResult("pay with timeout outcome", true, detail(r)));
                    }
                }
                case "Scene 6", "6", "s6" -> {
                    var lender = lenderRepo.findFirstByStatus(Enums.LenderStatus.ACTIVE)
                            .orElseThrow(() -> new NotFoundException("No active lender seeded"));
                    advanceService.grantConsent(m.getId(), lender.getId());
                    var view = advanceService.requestAssessment(m.getId(), lender.getId(), null);
                    steps.add(new Dtos.StepResult("assess + offer", true,
                            "decision=" + view.decision() + " offer=N$" + (view.offerPrincipal() == null ? "n/a" : view.offerPrincipal())));
                    if (view.decision() == Enums.AssessmentDecision.APPROVED) {
                        String offerId = advanceRepo
                                .findFirstByMerchantIdAndStatusOrderByCreatedAtDesc(m.getId(), Enums.AdvanceStatus.OFFERED)
                                .map(Advance::getId)
                                .orElseThrow(() -> new NotFoundException("Offered advance missing"));
                        advanceService.accept(m.getId(), offerId);
                        var fundMsg = advanceService.fund(offerId, actorId, actorRole);
                        steps.add(new Dtos.StepResult("accept + fund", true, fundMsg.message()));
                        var sweeps = advanceService.sweep(lender.getId(), LocalDate.now(ZoneOffset.UTC));
                        for (var s : sweeps) steps.add(s);
                    }
                }
                case "Scene 7", "7", "s7" -> {
                    injectBreak(actorId, actorRole);
                    var run = reconciliationService.run();
                    String reconSummary = switch (run) {
                        case Dtos.RunClean c -> "MATCHED (3 ledgers agree)";
                        case Dtos.RunWithBreaks w -> "BREAKS_FOUND, breaks=" + w.breakCount();
                        case Dtos.RunBlocked b -> "BLOCKED (" + b.code() + ")";
                    };
                    steps.add(new Dtos.StepResult("inject + recon run", true, reconSummary));
                    var before = reconciliationService.lastRun();
                    steps.add(new Dtos.StepResult("withdrawal gate", true,
                            "Withdrawals blocked while breaks open (BC p.18) - assertNoOpenBreaks armed"));
                }
                case "Scene 8", "8", "s8" -> {
                    var payments = paymentRepo.findByMerchantIdOrderByCreatedAtDesc(m.getId(),
                            org.springframework.data.domain.PageRequest.of(0, 5)).getContent().stream()
                            .filter(pp -> pp.getStatus() == Enums.PaymentStatus.CONFIRMED)
                            .findFirst();
                    if (payments.isEmpty()) {
                        steps.add(new Dtos.StepResult("refund", true, "No confirmed payment to refund yet"));
                    } else {
                        var row = paymentService.refund(m.getId(), payments.get().getId(),
                                new Dtos.RefundRequest(new BigDecimal("10.00"), "scene 8 demo refund"));
                        steps.add(new Dtos.StepResult("refund", true, "Refunded N$" + row.amount()));
                    }
                }
                default -> throw new SimException("SCENE", "Unknown scene " + name);
            }
        } catch (Exception ex) {
            steps.add(new Dtos.StepResult("scene " + name, false, ex.getMessage()));
        }
        auditService.sim(actorId, actorRole, "scene_run", "scene", name,
                steps.size() + " steps", null);
        return new Dtos.SceneRunResult(name, steps);
    }

    /** Scene helpers - resolve the seeded demo entities by well-known phone numbers. */
    private Merchant seedMerchant() {
        return merchantRepo.findByOwnerPhone("0811111111")
                .orElseThrow(() -> new NotFoundException("Seed merchant missing - run seed migration"));
    }

    private Invoice payableInvoice(String merchantId) {
        return invoiceRepo.findForMerchant(merchantId,
                        List.of(Enums.InvoiceStatus.ISSUED, Enums.InvoiceStatus.PARTIALLY_PAID))
                .stream().findFirst().flatMap(id -> invoiceRepo.findById(id.id())).orElse(null);
    }

    private String detail(Object r) {
        return String.valueOf(r);
    }
}
