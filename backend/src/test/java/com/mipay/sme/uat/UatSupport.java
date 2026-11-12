package com.mipay.sme.uat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.mipay.sme.config.AppProperties;
import com.mipay.sme.security.Current;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.AuditLog;
import com.mipay.sme.domain.core.Merchant;
import com.mipay.sme.domain.core.Supplier;
import com.mipay.sme.domain.org.OrgBankAccount;
import com.mipay.sme.domain.org.Organization;
import com.mipay.sme.domain.core.SupplierCatalogueItem;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.domain.credit.Advance;
import com.mipay.sme.domain.credit.Lender;
import com.mipay.sme.domain.ledger.BalanceAccount;
import com.mipay.sme.domain.ledger.BalanceHold;
import com.mipay.sme.domain.ledger.Connector;
import com.mipay.sme.domain.ledger.LimitProfile;
import com.mipay.sme.domain.ops.Incident;
import com.mipay.sme.domain.b2b.Invoice;
import com.mipay.sme.domain.payments.Payment;
import com.mipay.sme.repository.AccountMappingRepository;
import com.mipay.sme.repository.AdvanceRepaymentRepository;
import com.mipay.sme.repository.AdvanceRepository;
import com.mipay.sme.repository.AmlAlertRepository;
import com.mipay.sme.repository.AuditLogRepository;
import com.mipay.sme.repository.BalanceAccountRepository;
import com.mipay.sme.repository.BalanceHoldRepository;
import com.mipay.sme.repository.ConnectorRepository;
import com.mipay.sme.repository.ConsentWithdrawalRepository;
import com.mipay.sme.repository.CreditConsentRepository;
import com.mipay.sme.repository.DeviceSessionRepository;
import com.mipay.sme.repository.FeeTransactionRepository;
import com.mipay.sme.repository.IncidentRepository;
import com.mipay.sme.repository.InventoryItemRepository;
import com.mipay.sme.repository.InvoicePaymentRepository;
import com.mipay.sme.repository.InvoiceRepository;
import com.mipay.sme.repository.IssuerBalanceLedgerRepository;
import com.mipay.sme.repository.KhataAccountRepository;
import com.mipay.sme.repository.KhataEntryRepository;
import com.mipay.sme.repository.LenderCreditLedgerRepository;
import com.mipay.sme.repository.LenderRepository;
import com.mipay.sme.repository.LimitProfileRepository;
import com.mipay.sme.repository.MerchantRepository;
import com.mipay.sme.repository.MerchantSubledgerRepository;
import com.mipay.sme.repository.OrderItemRepository;
import com.mipay.sme.repository.OrderRepository;
import com.mipay.sme.repository.PaymentEventRepository;
import com.mipay.sme.repository.PaymentRepository;
import com.mipay.sme.repository.QrCodeRepository;
import com.mipay.sme.repository.ReconciliationBreakRepository;
import com.mipay.sme.repository.ReconciliationRunRepository;
import com.mipay.sme.repository.RefundRepository;
import com.mipay.sme.repository.ReorderRuleRepository;
import com.mipay.sme.repository.ShortCodeRepository;
import com.mipay.sme.repository.SimulationScenarioRepository;
import com.mipay.sme.repository.StatementRepository;
import com.mipay.sme.repository.StockAlertRepository;
import com.mipay.sme.repository.StockMovementRepository;
import com.mipay.sme.repository.OrgBankAccountRepository;
import com.mipay.sme.repository.OrganizationRepository;
import com.mipay.sme.repository.OnboardingRequestRepository;
import com.mipay.sme.repository.SupplierCatalogueItemRepository;
import com.mipay.sme.repository.SupplierRepository;
import com.mipay.sme.repository.TabEntryRepository;
import com.mipay.sme.repository.TabRepository;
import com.mipay.sme.repository.TestDataSetRepository;
import com.mipay.sme.repository.UserRepository;
import com.mipay.sme.repository.WithdrawalRepository;
import com.mipay.sme.security.JwtService;
import com.mipay.sme.service.AdminService;
import com.mipay.sme.service.AdvanceService;
import com.mipay.sme.service.B2BPaymentService;
import com.mipay.sme.service.ComplianceService;
import com.mipay.sme.service.ConnectorService;
import com.mipay.sme.service.FeeService;
import com.mipay.sme.service.InventoryService;
import com.mipay.sme.service.LedgerService;
import com.mipay.sme.service.MerchantViewsService;
import com.mipay.sme.service.OnboardingService;
import com.mipay.sme.service.OpsService;
import com.mipay.sme.service.PaymentService;
import com.mipay.sme.service.ReconciliationService;
import com.mipay.sme.service.ReportingService;
import com.mipay.sme.service.SimulationService;
import com.mipay.sme.service.StatementService;
import com.mipay.sme.service.WithdrawalService;
import com.mipay.sme.web.Dtos;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;


import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared harness for the MiPay SME UAT compliance pack (backend tests).
 *
 * One Spring context is shared across the suite (H2 in-memory, MODE=PostgreSQL);
 * each test class builds its own isolated fixtures through the real services
 * (registration, approval, provisioning), so the pack is re-runnable in any order.
 *
 * Money-critical flows exercise the service layer (the same code the controllers
 * invoke); authorisation, role-gating and consent-gating behaviour is asserted
 * through the real HTTP layer with MockMvc.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("uat")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class UatSupport {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis() % 10_000_000L);

    // ------------------------------------------------------------------ services

    @Autowired protected org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired protected com.mipay.sme.config.DataSeeder seeder;
    @Autowired protected com.mipay.sme.config.KycMasterSeed kycMasterSeed;
    @Autowired protected com.mipay.sme.service.KycApplicationService kycApps;
    @Autowired protected com.mipay.sme.service.kyc.KycWorkflowEngine kycEngine;
    @Autowired protected com.mipay.sme.repository.KycApplicationRepository kycRepo;
    @Autowired protected com.mipay.sme.repository.KycTaskRepository kycTasks;

    /**
     * Class isolation, ported from the reference implementation's UatBase (flyway.clean + migrate
     * before each class): wipe every business table after the class, then restore the sandbox
     * seed, so the shared in-memory H2 never carries cross-class state. The pack's documented
     * goal is order-independence; before this reset, later classes saw tariffs mutated /
     * recon state left by earlier ones.
     */
    @AfterAll
    void cleanSharedDatabase() {
        var tables = jdbc.query(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_TYPE = 'BASE TABLE'",
                (rs, i) -> rs.getString(1));
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        for (String t : tables) {
            jdbc.execute("DELETE FROM \"" + t + "\"");
        }
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
        // restore the sandbox seed so the next class boots from the same baseline
        seeder.run(new org.springframework.boot.DefaultApplicationArguments());
        // the KYC master is wiped with everything else — re-seed (H2 runs migrations never)
        kycMasterSeed.run(new org.springframework.boot.DefaultApplicationArguments());
    }

    @Autowired protected OnboardingService onboarding;
    @Autowired protected LedgerService ledger;
    @Autowired protected PaymentService payments;
    @Autowired protected B2BPaymentService b2b;
    @Autowired protected AdvanceService advances;
    @Autowired protected FeeService fees;
    @Autowired protected ReconciliationService recon;
    @Autowired protected WithdrawalService withdrawals;
    @Autowired protected StatementService statements;
    @Autowired protected SimulationService simulation;
    @Autowired protected ComplianceService compliance;
    @Autowired protected ConnectorService connectors;
    @Autowired protected AdminService admin;
    @Autowired protected OpsService ops;
    @Autowired protected ReportingService reports;
    @Autowired protected InventoryService inventory;
    @Autowired protected MerchantViewsService views;

    // ------------------------------------------------------------------ security / infra

    @Autowired protected JwtService jwt;
    @Autowired protected AppProperties appProperties;
    @Autowired protected ObjectMapper om;
    @Autowired protected MockMvc mockMvc;
    @Autowired protected PlatformTransactionManager txm;
    @Autowired protected PasswordEncoder passwordEncoder;

    // ------------------------------------------------------------------ repositories

    @Autowired protected UserRepository users;
    @Autowired protected MerchantRepository merchants;
    @Autowired protected SupplierRepository suppliers;
    @Autowired protected OrgBankAccountRepository bankAccounts;
    @Autowired protected OrganizationRepository orgs;
    @Autowired protected com.mipay.sme.service.OrgOnboardingService orgOnboard;
    @Autowired protected com.mipay.sme.service.OrgTreeService orgTree;
    @Autowired protected com.mipay.sme.service.OrgProvisioningService orgProvisioning;
    @Autowired protected com.mipay.sme.service.OrgSuspensionService orgSusp;
    @Autowired protected com.mipay.sme.service.OrgRightsService orgRights;
    @Autowired protected com.mipay.sme.service.OrgLifecycleService lifecycle;
    @Autowired protected com.mipay.sme.service.OrgCallerService orgCaller;
    @Autowired protected com.mipay.sme.service.ActivationService activation;
    @Autowired protected OnboardingRequestRepository onboardingRequests;
    @Autowired protected com.mipay.sme.repository.SuspensionRepository suspensionRepo;
    @Autowired protected com.mipay.sme.repository.ActivationTokenRepository activationTokens;
    @Autowired protected com.mipay.sme.repository.SimOutboxRepository simOutbox;
    @Autowired protected com.mipay.sme.service.AuthService auth;
    @Autowired protected SupplierCatalogueItemRepository catalogue;
    @Autowired protected BalanceAccountRepository accounts;
    @Autowired protected AccountMappingRepository mappings;
    @Autowired protected IssuerBalanceLedgerRepository issuerLedger;
    @Autowired protected MerchantSubledgerRepository subledger;
    @Autowired protected BalanceHoldRepository holds;
    @Autowired protected LimitProfileRepository limitProfiles;
    @Autowired protected WithdrawalRepository withdrawalRepo;
    @Autowired protected ConnectorRepository connectorsRepo;
    @Autowired protected PaymentRepository paymentRepo;
    @Autowired protected PaymentEventRepository events;
    @Autowired protected RefundRepository refunds;
    @Autowired protected QrCodeRepository qrCodes;
    @Autowired protected ShortCodeRepository shortCodes;
    @Autowired protected OrderRepository orders;
    @Autowired protected OrderItemRepository orderItems;
    @Autowired protected InvoiceRepository invoices;
    @Autowired protected InvoicePaymentRepository invoicePayments;
    @Autowired protected CreditConsentRepository consents;
    @Autowired protected ConsentWithdrawalRepository consentWithdrawals;
    @Autowired protected LenderRepository lenders;
    @Autowired protected AdvanceRepository advanceRepo;
    @Autowired protected AdvanceRepaymentRepository repayments;
    @Autowired protected LenderCreditLedgerRepository creditLedger;
    @Autowired protected FeeTransactionRepository feeTx;
    @Autowired protected ReconciliationRunRepository reconRuns;
    @Autowired protected ReconciliationBreakRepository breakRepo;
    @Autowired protected AuditLogRepository auditRepo;
    @Autowired protected DeviceSessionRepository sessions;
    @Autowired protected InventoryItemRepository inventoryItems;
    @Autowired protected StockMovementRepository stockMovements;
    @Autowired protected StockAlertRepository stockAlerts;
    @Autowired protected ReorderRuleRepository reorderRules;
    @Autowired protected KhataAccountRepository khata;
    @Autowired protected KhataEntryRepository khataEntries;
    @Autowired protected TabRepository tabs;
    @Autowired protected TabEntryRepository tabEntries;
    @Autowired protected IncidentRepository incidentRepo;
    @Autowired protected StatementRepository statementRepo;
    @Autowired protected TestDataSetRepository testDataSets;
    @Autowired protected SimulationScenarioRepository scenarioRepo;
    @Autowired protected AmlAlertRepository amlAlerts;

    // ------------------------------------------------------------------ fixtures

    protected static int seq() {
        return (int) SEQ.incrementAndGet();
    }

    /** Unique Namibian-format mobile number per fixture. */
    protected static String phone() {
        return "085" + String.format("%07d", seq() % 10_000_000);
    }

    protected static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    /** Approved merchant with balance account, QR code and short code provisioned (TC-ONB-01). */
    protected Merchant newApprovedMerchant() {
        String phone = phone();
        onboarding.registerMerchant(new Dtos.RegisterMerchantRequest(phone,
                "UAT Merchant " + seq(), "Owner of Merchant " + seq(),
                "85041" + String.format("%05d", seq() % 100_000), "RETAIL", "REG-UAT-" + seq()));
        Merchant m = merchants.findByOwnerPhone(phone).orElseThrow();
        Dtos.ApprovalResult r = onboarding.approveMerchant(m.getId(), true, "CLEAR",
                "uat-approver-" + seq(), "TPTS_OPS");
        assertThat(r.status()).isEqualTo("APPROVED");
        return merchants.findById(m.getId()).orElseThrow();
    }

    /** Registered but not yet approved merchant (pending TPTS approval). */
    protected Merchant newPendingMerchant() {
        return newPendingMerchant("Pending Merchant " + seq(), "RETAIL", "REG-UAT-" + seq());
    }

    /** Registered, not-approved merchant with explicit business name/type (KYC risk-rule fixtures). */
    protected Merchant newPendingMerchant(String businessName, String businessType, String registrationNumber) {
        String phone = phone();
        onboarding.registerMerchant(new Dtos.RegisterMerchantRequest(phone,
                businessName, "Owner of " + businessName,
                "85045" + String.format("%05d", seq() % 100_000), businessType, registrationNumber));
        return merchants.findByOwnerPhone(phone).orElseThrow();
    }

    /** Approved supplier with an independently approved receiving account + one approved catalogue item. */
    protected Supplier newApprovedSupplier(String productName, String unitPrice) {
        String phone = phone();
        onboarding.registerSupplier(new Dtos.RegisterSupplierRequest(phone,
                "UAT Supplier " + seq(), "Supplier Owner " + seq(), "REG-UAT-S-" + seq(),
                "Bank Windhoek", "3000" + String.format("%05d", seq() % 100_000), "UAT Supplier " + seq()));
        Supplier s = suppliers.findByOwnerPhone(phone).orElseThrow();

        OrgBankAccount pending = bankAccounts
                .findByOrgIdAndStatus(orgOfEntity(Enums.OrgType.SUPPLIER, s.getId()).getId(), Enums.BankAccountStatus.PENDING_APPROVAL)
                .orElseThrow(() -> new AssertionError("bank account request should be pending (TC-ONB-02)"));
        // approver must differ from requester (second-person rule, TC-ONB-03)
        Dtos.ApprovalResult bank = onboarding.approveBankAccount(pending.getId(), true,
                "uat-tpts-approver-" + seq(), "TPTS_OPS");
        assertThat(bank.status()).isEqualTo("APPROVED");

        Dtos.ApprovalResult approved = onboarding.approveSupplier(s.getId(), true,
                "uat-tpts-approver-" + seq(), "TPTS_OPS");
        assertThat(approved.status()).isEqualTo("APPROVED");

        SupplierCatalogueItem item = new SupplierCatalogueItem();
        item.setSupplier(suppliers.findById(s.getId()).orElseThrow());
        item.setProductName(productName);
        item.setUnit("each");
        item.setUnitPrice(bd(unitPrice));
        item.setStatus(Enums.ItemStatus.APPROVED);
        catalogue.save(item);
        return s;
    }

    /** Simulated licensed lender; null maxDaily means "no daily deduction cap". */
    protected Lender newLender(BigDecimal collectionRate, BigDecimal serviceFeeRate,
                               BigDecimal maxDailyDeduction, BigDecimal lenderLimit) {
        Lender l = new Lender();
        l.setName("UAT Lender " + seq());
        l.setLicenceRef("NAM-UAT-" + seq());
        l.setStatus(Enums.LenderStatus.ACTIVE);
        l.setCollectionRate(collectionRate);
        l.setServiceFeeRate(serviceFeeRate);
        l.setMaxDailyDeduction(maxDailyDeduction);
        l.setLenderLimit(lenderLimit);
        return lenders.save(l);
    }

    /** Lender with a portal user able to log in (used for consent-gated API tests). */
    protected record LenderWithUser(Lender lender, String phone, String password) {
    }

    protected LenderWithUser newLenderWithUser() {
        String uphone = phone();
        String password = "uat-lender-pass";
        User u = new User();
        u.setName("UAT Lender User " + seq());
        u.setPhone(uphone);
        u.setRole(Enums.UserRole.LENDER);
        u.setStatus(Enums.UserStatus.ACTIVE);
        u.setPasswordHash(passwordEncoder.encode(password));
        users.save(u);
        Lender l = newLender(bd("0.10"), bd("0.05"), bd("2000"), bd("25000"));
        l.setUserId(u.getId());
        lenders.save(l);
        return new LenderWithUser(l, uphone, password);
    }

    // ------------------------------------------------------------------ money helpers

    /** Opening/top-up credit confirmed by the issuer (source OTHER, like the sandbox seeder). */
    protected void credit(String merchantId, String amount) {
        int n = seq();
        ledger.postConfirmed(ledger.account(Enums.OwnerType.MERCHANT, merchantId),
                Enums.LedgerEntryType.CREDIT, bd(amount), Enums.SubledgerSource.OTHER,
                "uat-credit-" + n, "UAT-OPEN-" + String.format("%08d", n), "uat-credit-key-" + n);
    }

    /** Confirmed issuer debit — used to *reduce* available funds in test scenarios. */
    protected void debit(String merchantId, String amount) {
        int n = seq();
        ledger.postConfirmed(ledger.account(Enums.OwnerType.MERCHANT, merchantId),
                Enums.LedgerEntryType.DEBIT, bd(amount), Enums.SubledgerSource.OTHER,
                "uat-debit-" + n, "UAT-OUT-" + String.format("%08d", n), "uat-debit-key-" + n);
    }

    protected Dtos.BalanceView balance(String merchantId) {
        return ledger.balanceView(merchantId);
    }

    protected BigDecimal available(String merchantId) {
        return ledger.availableFor(merchantId);
    }

    /** Accept + simulated bank confirm: the standard happy path; funds become available. */
    protected Payment payNowConfirmed(String merchantId, String amount, Enums.PaymentChannel channel,
                                      String payerRef) {
        Payment p = payments.accept(merchantId, new Dtos.AcceptPaymentRequest(bd(amount), channel, payerRef, null, null));
        Dtos.PaymentOpResult r = payments.simulate(merchantId, p.getId(), "confirm");
        assertThat(r).as("confirm should succeed for %s", p.getId()).isInstanceOf(Dtos.PaymentConfirmed.class);
        return paymentRepo.findById(p.getId()).orElseThrow();
    }

    /** Card sale left pending (never confirmed) for pending-vs-available assertions. */
    protected Payment cardPaymentPending(String merchantId, String amount) {
        return payments.accept(merchantId,
                new Dtos.AcceptPaymentRequest(bd(amount), Enums.PaymentChannel.CARD, "CARD-POS", null, null));
    }

    /** Back-dated confirmed receipt + mirrored ledger row (synthetic-history style, deterministic). */
    protected Payment historyPayment(String merchantId, LocalDate day, String amount, String payerRef) {
        int n = seq();
        Payment p = new Payment();
        p.setMerchant(merchants.getReferenceById(merchantId));
        p.setChannel(Enums.PaymentChannel.QR);
        p.setPayerRef(payerRef);
        p.setAmount(bd(amount));
        p.setCurrency("NAD");
        p.setStatus(Enums.PaymentStatus.CONFIRMED);
        p.setIdempotencyKey("uat-hist-" + n);
        p.setCreatedAt(day.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3600));
        p.setConfirmedAt(p.getCreatedAt());
        paymentRepo.save(p);
        ledger.postConfirmed(ledger.account(Enums.OwnerType.MERCHANT, merchantId),
                Enums.LedgerEntryType.CREDIT, p.getAmount(), Enums.SubledgerSource.PAYMENT, p.getId(),
                "HIST-" + String.format("%08d", n), "uat-hist-key-" + n);
        return p;
    }

    /** Controlled trading history: `days` distinct trading days ending today. */
    protected void seedHistory(String merchantId, int days, String dailyAmount) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (int i = 0; i < days; i++) {
            historyPayment(merchantId, today.minusDays(i), dailyAmount, "hist-payer-" + seq());
        }
    }

    /** Direct (setup) advance in ACCEPTED state, ready for funding — skips the consent history gate on purpose. */
    protected Advance acceptedAdvance(String merchantId, Lender lender, String principal, String totalRepayment) {
        Advance a = new Advance();
        a.setMerchant(merchants.getReferenceById(merchantId));
        a.setLender(lender);
        a.setRequestedAmount(bd(principal));
        a.setPrincipalAmount(bd(principal));
        a.setTotalRepayment(bd(totalRepayment));
        a.setRepaymentRate(bd("0.10"));
        a.setPurpose("Cash advance assessment");
        a.setStatus(Enums.AdvanceStatus.ACCEPTED);
        return advanceRepo.save(a);
    }

    /** Placed order + supplier-issued invoice with unique reference. */
    protected Dtos.InvoiceDetailRow orderAndInvoice(String merchantId, Supplier supplier,
                                                    String catalogueItemId, BigDecimal quantity) {
        Dtos.OrderDetailRow order = b2b.placeOrder(merchantId, new Dtos.CreateOrderRequest(
                supplier.getId(), List.of(new Dtos.CartLine(catalogueItemId, quantity))));
        assertThat(order.status()).isEqualTo("SUBMITTED");
        Dtos.InvoiceDetailRow invoice = b2b.issueInvoice(supplier.getId(), order.id());
        assertThat(invoice.status()).isEqualTo("ISSUED");
        return invoice;
    }

    protected String catalogueItemByProductName(Supplier supplier, String productName) {
        return catalogue.findBySupplierIdOrderByProductName(supplier.getId()).stream()
                .filter(i -> i.getProductName().equals(productName))
                .findFirst()
                .map(SupplierCatalogueItem::getId)
                .orElseThrow(() -> new AssertionError("catalogue item missing: " + productName));
    }

    protected Dtos.InvoicePaid payInvoiceOk(String merchantId, String invoiceId) {
        Dtos.InvoicePayResult r = b2b.payInvoice(merchantId, invoiceId, null, "confirm");
        assertThat(r).isInstanceOf(Dtos.InvoicePaid.class);
        return (Dtos.InvoicePaid) r;
    }

    // ------------------------------------------------------------------ reconciliation hygiene

    /** Same as assertReconciliationClean but without assertions - safe mid-race cleanup (finally blocks). */
    protected void cleanupReconQuiet() {
        simulation.clearBreaks("uat-quiet-cleanup", "TPTS_OPS");
        for (var b : breakRepo.findByStatus(Enums.BreakStatus.OPEN)) {
            try {
                recon.resolveBreak(b.getId(), "uat-quiet-cleanup");
            } catch (Exception ignored) {
                // a race test may leave inconsistent state; never mask the original failure
            }
        }
        try {
            recon.run();
        } catch (Exception ignored) {
        }
    }

    /** Removes injected test-break rows, resolves any open breaks, then proves a clean MATCHED run. */
    protected void assertReconciliationClean() {
        simulation.clearBreaks("uat-cleanup", "TPTS_OPS");
        for (var b : breakRepo.findByStatus(Enums.BreakStatus.OPEN)) {
            recon.resolveBreak(b.getId(), "uat-cleanup");
        }
        Dtos.RunResult run = recon.run();
        assertThat(statusOf(run)).as("recon must be clean before/after break tests").isEqualTo("MATCHED");
    }

    // ------------------------------------------------------------------ JSON / HTTP helpers

    protected String json(Object o) {
        try {
            return om.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected org.springframework.test.web.servlet.ResultActions call(String method, String url,
                                                                      String bearer, String body) throws Exception {
        MockHttpServletRequestBuilder b = switch (method) {
            case "POST" -> org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url);
            case "GET" -> org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url);
            default -> throw new IllegalArgumentException(method);
        };
        if (bearer != null) {
            b.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        }
        if (body != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(b);
    }

    /** Authenticated GET via the real HTTP stack (role/consent gates). */
    protected org.springframework.test.web.servlet.ResultActions bearerGet(String url, String bearer) throws Exception {
        MockHttpServletRequestBuilder b = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url);
        if (bearer != null) {
            b.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        }
        return mockMvc.perform(b);
    }

    /** Authenticated POST via the real HTTP stack (role/consent gates). */
    protected org.springframework.test.web.servlet.ResultActions bearerPost(String url, String bearer, Object body) throws Exception {
        MockHttpServletRequestBuilder b = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url);
        if (bearer != null) {
            b.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        }
        if (body != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(json(body));
        }
        return mockMvc.perform(b);
    }

    /** Real merchant OTP authentication: sandbox hint carries the fixed dev OTP (SIMULATED, UAT SB p.8). */
    protected String merchantToken(String phone) throws Exception {
        MvcResult otp = call("POST", "/api/auth/otp", null, json(Map.of("phone", phone)))
                .andReturn();
        String hint = JsonPath.read(otp.getResponse().getContentAsString(), "$.hint");
        MvcResult verify = call("POST", "/api/auth/otp/verify", null,
                json(Map.of("phone", phone, "code", hint))).andReturn();
        return JsonPath.read(verify.getResponse().getContentAsString(), "$.token");
    }

    /** Real tenant (ops/lender/supplier/admin) password login. */
    protected String passwordToken(String phone, String password) throws Exception {
        MvcResult res = call("POST", "/api/auth/login", null, json(Map.of("phone", phone, "password", password)))
                .andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.token");
    }

    protected String opsToken() throws Exception {
        return passwordToken("0827777777", "ops123");
    }

    protected String adminToken() throws Exception {
        return passwordToken("0819999999", "admin123");
    }

    // ------------------------------------------------------------------ misc

    /** One transaction for direct entity mutations the API itself cannot trigger (test setup only). */
    protected void inTx(Runnable work) {
        new TransactionTemplate(txm).executeWithoutResult(status -> work.run());
    }

    protected Merchant seedMerchant() {
        return merchants.findByOwnerPhone("0811111111")
                .orElseThrow(() -> new AssertionError("seed merchant missing"));
    }

    protected Supplier seedSupplier() {
        return suppliers.findByOwnerPhone("0852222222")
                .orElseThrow(() -> new AssertionError("seed supplier missing"));
    }

    protected Instant now() {
        return Instant.now();
    }


    protected String statusOf(Dtos.RunResult run) {
        if (run instanceof Dtos.RunClean) return "MATCHED";
        if (run instanceof Dtos.RunWithBreaks) return "BREAKS_FOUND";
        if (run instanceof Dtos.RunBlocked b) return b.code();
        return "UNKNOWN";
    }
    protected Invoice invoiceEntity(String invoiceId) {
        return invoices.findById(invoiceId).orElseThrow(() -> new AssertionError("invoice missing"));
    }

    /**
     * Org of a supplier/merchant/lender entity id. H2 tests skip Flyway so the
     * V4 backfill never runs; provisioning is idempotent and self-healing.
     */
    protected Organization orgOfEntity(com.mipay.sme.domain.Enums.OrgType type, String entityId) {
        return orgProvisioning.ensureEntityOrg(type, entityId,
                type.name().toLowerCase() + "-entity-" + entityId);
    }

    // ------------------------------------------------------------------ org-hierarchy helpers

    /** 10-arg principal for service calls (org fields resolved from the user row). */
    protected com.mipay.sme.security.Current.Principal principalOf(User u) {
        String orgType = u.getOrgId() == null ? null
                : orgs.findById(u.getOrgId()).map(o -> o.getOrgType().name()).orElse(null);
        return new com.mipay.sme.security.Current.Principal(u.getId(), u.getRole().name(),
                u.getName(), u.getPhone(), null, null, null, u.getOrgId(), orgType,
                u.getOrgRole() == null ? null : u.getOrgRole().name());
    }

    protected com.mipay.sme.service.OrgAccess orgAccess() {
        return orgAccessSvc;
    }

    @Autowired protected com.mipay.sme.service.OrgAccess orgAccessSvc;

    // ------------------------------------------------------------------ KYC (§6/§12)

    /**
     * §6.5/§12: a child org goes live through one gate — a valid KYC document
     * is saved + submitted and platform review APPROVEs the ops-review task.
     * With `outcome` non-null the same flow exercises REJECT/BLOCK/RETURN.
     */
    protected void kycGoLive(Current.Principal submitter, String orgId, Current.Principal reviewer,
                             String outcome, String note) {
        kycApps.saveDraft(submitter, orgId, kycDoc(orgId), null);
        kycApps.submit(submitter, orgId, false);
        approveOpenTasks(orgId, reviewer, outcome, note);
    }

    protected void kycGoLive(Current.Principal submitter, String orgId, Current.Principal reviewer) {
        kycGoLive(submitter, orgId, reviewer, "APPROVE", null);
    }

    /** Approves (or rejects) every open task of the org's OPEN application. */
    protected void approveOpenTasks(String orgId, Current.Principal reviewer, String outcome, String note) {
        for (int round = 0; round < 5; round++) {
            var app = kycRepo.findFirstByOrgIdAndStatusInOrderByCreatedAtDesc(orgId,
                    com.mipay.sme.domain.kyc.KycApplication.OPEN_STATUSES).orElse(null);
            if (app == null) return;
            var open = kycTasks.findByApplicationIdAndSubmissionNoAndStatus(
                    app.getId(), app.getSubmissionNo(), com.mipay.sme.domain.kyc.KycTask.Status.OPEN);
            if (open.isEmpty()) return;
            for (var task : open) {
                kycApps.decideTask(reviewer, task.getId(), outcome == null ? "APPROVE" : outcome, note);
            }
        }
    }

    /** A schema-valid NA/PSP/BUSINESS v3 document (merchant data capture spec); values are unique per seq(). */
    protected Map<String, Object> kycDoc(String orgId) {
        int n = seq();
        String tin = "10" + String.format("%06d", n % 1_000_000);
        String legal = "UAT KYC Entity " + n;
        Map<String, Object> doc = new java.util.LinkedHashMap<>();
        Map<String, Object> businessProfile = new java.util.LinkedHashMap<>();
        businessProfile.put("legalBusinessName", legal);
        businessProfile.put("tradingName", "UAT Store " + n);
        businessProfile.put("entityType", "PRIVATE_LIMITED");
        businessProfile.put("registrationNumber", "CC/2019/" + String.format("%06d", n % 1_000_000));
        businessProfile.put("incorporationDate", "2019-03-14");
        businessProfile.put("taxIdentificationNumber", tin);
        doc.put("businessProfile", businessProfile);

        Map<String, Object> operations = new java.util.LinkedHashMap<>();
        operations.put("mcc", "5411");
        operations.put("websiteOrStorefront", "https://uat-store-" + n + ".example.na");
        operations.put("expectedMonthlyVolume", "50K_250K");
        operations.put("averageTicketSize", 1200);
        doc.put("operationalDetails", operations);

        Map<String, Object> address = new java.util.LinkedHashMap<>();
        address.put("addressType", "REGISTERED");
        address.put("streetLine", "12 Sam Nujoma Drive");
        address.put("town", "Windhoek");
        address.put("region", "Khomas");
        address.put("postalCode", "9000");
        address.put("country", "NA");
        doc.put("addresses", List.of(address));

        Map<String, Object> ubo = new java.util.LinkedHashMap<>();
        ubo.put("fullLegalName", "Owner Holder " + n);
        ubo.put("dateOfBirth", "1985-04-12");
        ubo.put("nationality", "NA");
        ubo.put("residentialAddress", "4 Robert Mugabe Ave, Windhoek");
        ubo.put("phoneNumber", "0812345" + String.format("%03d", n % 1000));
        ubo.put("emailAddress", "owner" + n + "@example.na");
        ubo.put("identificationType", "NATIONAL_ID");
        ubo.put("identificationNumber", "85041200123");
        ubo.put("ownershipPercentage", 100);
        ubo.put("politicallyExposedPerson", false);
        doc.put("ubos", List.of(ubo));

        Map<String, Object> bank = new java.util.LinkedHashMap<>();
        bank.put("beneficiaryName", legal);
        bank.put("bankCode", "BANK_WINDHOEK");
        bank.put("institutionCode", "481972");
        bank.put("accountNumber", "8" + String.format("%09d", n % 1_000_000_000));
        bank.put("accountType", "CURRENT");
        doc.put("bankAccounts", List.of(bank));
        return doc;
    }

    protected com.mipay.sme.security.Current.Principal opsPrincipal() {
        return principalOf(users.findAll().stream()
                .filter(u -> u.getRole() == Enums.UserRole.TPTS_OPS).findFirst().orElseThrow());
    }

    protected com.mipay.sme.security.Current.Principal platformAdminPrincipal() {
        return principalOf(users.findAll().stream()
                .filter(u -> u.getRole() == Enums.UserRole.MINDLABS_ADMIN).findFirst().orElseThrow());
    }

    /** Username/password login ([H6]). */
    protected Dtos.TokenView loginWithPassword(String identifier, String password) {
        return auth.loginWithPassword(identifier, password);
    }

    /** OA1: the operator org the seeder parks platform staff in — onboarding hangs under it. */
    protected com.mipay.sme.domain.org.Organization operatorOrg() {
        return orgs.findByOrgTypeAndEntityId(Enums.OrgType.PLATFORM, "mindlabs").orElseThrow();
    }
}
