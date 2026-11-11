package com.mipay.sme.config;

import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.Merchant;
import com.mipay.sme.domain.core.Supplier;
import com.mipay.sme.domain.credit.Lender;
import com.mipay.sme.domain.fees.FeeTariff;
import com.mipay.sme.domain.ledger.LimitProfile;
import com.mipay.sme.domain.ops.InventoryItem;
import com.mipay.sme.domain.recon.EligibilityRule;
import com.mipay.sme.repository.EligibilityRuleRepository;
import com.mipay.sme.repository.FeeTariffRepository;
import com.mipay.sme.repository.LenderRepository;
import com.mipay.sme.repository.LimitProfileRepository;
import com.mipay.sme.repository.MerchantRepository;
import com.mipay.sme.repository.SupplierCatalogueItemRepository;
import com.mipay.sme.repository.SupplierRepository;
import com.mipay.sme.repository.UserRepository;
import com.mipay.sme.service.AdvanceService;
import com.mipay.sme.service.InventoryService;
import com.mipay.sme.service.LedgerService;
import com.mipay.sme.service.OnboardingService;
import com.mipay.sme.service.SimulationService;
import com.mipay.sme.web.Dtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.mipay.sme.domain.core.User;
import com.mipay.sme.domain.core.SecuritySetting;
import com.mipay.sme.repository.SecuritySettingRepository;
import com.mipay.sme.repository.InventoryItemRepository;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Sandbox seed runner (UAT SB scene data, TC-SET-24..26). Idempotent: runs only
 * in a simulated environment and only when the user table is empty. Every
 * seeded artefact is SIMULATED-labelled in the audit trail.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private final AppProperties props;
    private final UserRepository userRepo;
    private final MerchantRepository merchantRepo;
    private final SupplierRepository supplierRepo;
    private final SupplierCatalogueItemRepository catalogueRepo;
    private final LenderRepository lenderRepo;
    private final FeeTariffRepository tariffRepo;
    private final EligibilityRuleRepository ruleRepo;
    private final LimitProfileRepository limitRepo;
    private final SecuritySettingRepository settingRepo;
    private final InventoryItemRepository inventoryItemRepo;
    private final PasswordEncoder passwordEncoder;

    private final OnboardingService onboardingService;
    private final LedgerService ledgerService;
    private final SimulationService simulationService;
    private final InventoryService inventoryService;
    private final AdvanceService advanceService;
    private final com.mipay.sme.repository.ConnectorRepository connectorRepo;
    private final com.mipay.sme.service.OrgProvisioningService orgProvisioning;
    private final com.mipay.sme.repository.UserPlatformFunctionRepository platformFunctions;

    private static final String SEED_ACTOR = "seed-runner";
    private static final String OPS = "TPTS_OPS";
    private static final String ADMIN = "MINDLABS_ADMIN";

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!props.isSimulatedEnvironment()) {
            log.info("DataSeeder skipped - production environment");
            return;
        }
        if (userRepo.count() > 0) {
            log.info("DataSeeder skipped - users already present");
            return;
        }
        seed();
        log.info("Sandbox seed complete: users/merchant/supplier/lender/tariffs/rules/limits/inventory ready");
    }

    void seed() {
        // ---------------------------------------------------------------- connectors first (C30)
        seedConnector(Enums.ConnectorType.BANK_ISSUER, "NDB Issuer (sandbox)", "https://sim.ndb.mipay.na",
                Enums.AuthType.OAUTH2);
        seedConnector(Enums.ConnectorType.CARD, "Acquirer Card Gateway (sandbox)", "https://sim.card.mipay.na",
                Enums.AuthType.API_KEY);

        // ---------------------------------------------------------------- staff accounts (X01)
        User ops = new User();
        ops.setName("TPTS Ops One");
        ops.setPhone("0827777777");
        ops.setEmail("ops@mipay.na");
        ops.setPasswordHash(passwordEncoder.encode("ops123"));
        ops.setRole(Enums.UserRole.TPTS_OPS);
        ops.setStatus(Enums.UserStatus.ACTIVE);
        userRepo.save(ops);

        User admin = new User();
        admin.setName("MiNdLABS Admin");
        admin.setPhone("0819999999");
        admin.setEmail("admin@mipay.na");
        admin.setPasswordHash(passwordEncoder.encode("admin123"));
        admin.setRole(Enums.UserRole.MINDLABS_ADMIN);
        admin.setStatus(Enums.UserStatus.ACTIVE);
        userRepo.save(admin);

        // [H6] staff usernames for IdP username login; platform staff bind to the
        // platform root as ADMIN (mirrors the V4 postgres backfill)
        com.mipay.sme.domain.org.Organization root = orgProvisioning.ensurePlatformRoot();
        ops.setUsername("ops-admin-ops");
        ops.setOrgId(root.getId());
        ops.setOrgRole(Enums.OrgMemberRole.ADMIN);
        userRepo.save(ops);
        admin.setUsername("ops-admin-mindlabs");
        admin.setOrgId(root.getId());
        admin.setOrgRole(Enums.OrgMemberRole.ADMIN);
        userRepo.save(admin);

        // §9 platform functions: reviewers act on staging tasks through user_platform_functions.
        // MINDLABS_ADMIN intentionally holds no review function (the design's invariant).
        com.mipay.sme.domain.kyc.UserPlatformFunction f = new com.mipay.sme.domain.kyc.UserPlatformFunction();
        f.setUserId(ops.getId());
        f.setFunction("OPS_REVIEWER");
        platformFunctions.save(f);

        // §6.6: one compliance user holds COMPLIANCE_OFFICER + MLRO; MINDLABS_ADMIN
        // intentionally holds no review function (the design's invariant).
        User comp = new User();
        comp.setName("Compliance Officer (sandbox)");
        comp.setPhone("0834444444");
        comp.setEmail("compliance@mipay.na");
        comp.setPasswordHash(passwordEncoder.encode("compliance123"));
        comp.setRole(Enums.UserRole.TPTS_OPS);
        comp.setStatus(Enums.UserStatus.ACTIVE);
        comp.setUsername("compliance-admin");
        comp.setOrgId(root.getId());
        comp.setOrgRole(Enums.OrgMemberRole.ADMIN);
        userRepo.save(comp);
        com.mipay.sme.domain.kyc.UserPlatformFunction cf = new com.mipay.sme.domain.kyc.UserPlatformFunction();
        cf.setUserId(comp.getId());
        cf.setFunction("COMPLIANCE_OFFICER");
        platformFunctions.save(cf);
        com.mipay.sme.domain.kyc.UserPlatformFunction mf = new com.mipay.sme.domain.kyc.UserPlatformFunction();
        mf.setUserId(comp.getId());
        mf.setFunction("MLRO");
        platformFunctions.save(mf);

        User senior = new User();
        senior.setName("Senior Approver (sandbox)");
        senior.setPhone("0835555555");
        senior.setEmail("senior@mipay.na");
        senior.setPasswordHash(passwordEncoder.encode("senior123"));
        senior.setRole(Enums.UserRole.TPTS_OPS);
        senior.setStatus(Enums.UserStatus.ACTIVE);
        senior.setUsername("senior-admin");
        senior.setOrgId(root.getId());
        senior.setOrgRole(Enums.OrgMemberRole.ADMIN);
        userRepo.save(senior);
        com.mipay.sme.domain.kyc.UserPlatformFunction sf = new com.mipay.sme.domain.kyc.UserPlatformFunction();
        sf.setUserId(senior.getId());
        sf.setFunction("SENIOR_APPROVER");
        platformFunctions.save(sf);

        // ---------------------------------------------------------------- lender (L01)
        User lenderUser = new User();
        lenderUser.setName("FNB Credit SME");
        lenderUser.setPhone("0813333333");
        lenderUser.setEmail("lender@mipay.na");
        lenderUser.setPasswordHash(passwordEncoder.encode("lender123"));
        lenderUser.setRole(Enums.UserRole.LENDER);
        lenderUser.setStatus(Enums.UserStatus.ACTIVE);
        userRepo.save(lenderUser);

        Lender lender = new Lender();
        lender.setName("FNB SME Credit (SIMULATED lender)");
        lender.setLicenceRef("NAM-FIN-00099");
        lender.setUserId(lenderUser.getId());
        lender.setStatus(Enums.LenderStatus.ACTIVE);
        lender.setCollectionRate(new BigDecimal("0.10"));   // 10% of eligible daily receipts (BC p.11)
        lender.setServiceFeeRate(new BigDecimal("0.05"));   // N$10,000 -> N$10,500 service fee
        lender.setMaxDailyDeduction(new BigDecimal("2000"));
        lender.setLenderLimit(new BigDecimal("25000"));
        lenderRepo.save(lender);

        // ---------------------------------------------------------------- merchant scene 1 (TC-SET-01, TC-ONB-01)
        onboardingService.registerMerchant(new Dtos.RegisterMerchantRequest(
                "0811111111", "Trendy Cell (Windhoek)", "Maria Shipanga",
                "85041200123", "RETAIL", "REG-2024-000111"));
        Merchant merchant = merchantRepo.findByOwnerPhone("0811111111").orElseThrow();
        onboardingService.approveMerchant(merchant.getId(), true, "CLEAR", SEED_ACTOR, OPS);

        // opening balance so the merchant can transact immediately
        var account = ledgerService.account(Enums.OwnerType.MERCHANT, merchant.getId());
        ledgerService.postConfirmed(account, Enums.LedgerEntryType.CREDIT, new BigDecimal("500.00"),
                Enums.SubledgerSource.OTHER, "seed", "SEED-OPEN-M-001", "seed-open-m-001");

        // ---------------------------------------------------------------- supplier scene 2 (TC-ONB-02)
        onboardingService.registerSupplier(new Dtos.RegisterSupplierRequest(
                "0852222222", "Nam Wholesale CC (Otjiwarongo)", "Pieter Visagie",
                "REG-2024-000222", "Bank Windhoek", "3001122334", "Nam Wholesale CC"));
        Supplier supplier = supplierRepo.findByOwnerPhone("0852222222").orElseThrow();
        User supplierUser = userRepo.findByPhone("0852222222").orElseThrow();
        supplierUser.setPasswordHash(passwordEncoder.encode("supplier123"));
        userRepo.save(supplierUser);
        // first bank account auto-approved by the seeder acting as an independent approver
        var pendingAccount = onboardingService.bankQueue().stream().findFirst().orElse(null);
        if (pendingAccount != null) {
            onboardingService.approveBankAccount(pendingAccount.id(), true, SEED_ACTOR, ADMIN);
        }
        onboardingService.approveSupplier(supplier.getId(), true, SEED_ACTOR, OPS);

        // approved, in-stock catalogue items
        var items = java.util.List.of(
                new Dtos.CatalogueUpsert("Nokia 105 (dual SIM), pack", "each", new BigDecimal("260.00")),
                new Dtos.CatalogueUpsert("Oukitel 20,000mAh power bank", "each", new BigDecimal("340.00")),
                new Dtos.CatalogueUpsert("USB-C cable 1m", "each", new BigDecimal("45.00")));
        for (Dtos.CatalogueUpsert upsert : items) {
            var item = new com.mipay.sme.domain.core.SupplierCatalogueItem();
            item.setSupplier(supplier);
            item.setProductName(upsert.productName());
            item.setUnit(upsert.unit());
            item.setUnitPrice(upsert.unitPrice());
            item.setStatus(Enums.ItemStatus.APPROVED);
            catalogueRepo.save(item);
        }

        // ---------------------------------------------------------------- merchant consent + records (TC-REC-02)
        advanceService.grantConsent(merchant.getId(), lender.getId());

        // ---------------------------------------------------------------- inventory + reorder rule (K05/K06)
        String itemId = seedItem(merchant.getId(), "USB-C cable 1m", "each", "3",
                "R0001", 20, "M01");
        if (itemId != null) {
            log.debug("seed inventory item {}", itemId);
        }

        // ---------------------------------------------------------------- fees (BC p.15, TC-SET-02)
        // BC p.16 (finding #7 fixed): contracted card service fee billed to the platform is 2.50%.
        seedTariff("card_service", Enums.RateType.PERCENTAGE, new BigDecimal("2.50"),
                new BigDecimal("2.20"), Enums.CostBasis.CONTRACTED,
                "Card acquiring; billed-contracted contribution basis (BC p.16)");
        seedTariff("ips_switching", Enums.RateType.FIXED, new BigDecimal("0.40"),
                null, Enums.CostBasis.PROVISION,
                "Per-transaction switching fee; quoted provisionally (BC p.15)");
        seedTariff("qr_sponsorship", Enums.RateType.FIXED, new BigDecimal("0.10"),
                null, Enums.CostBasis.UNQUOTED,
                "QR sponsorship fee; unquoted external cost (BC p.15)");
        seedTariff("advance_service", Enums.RateType.PERCENTAGE, new BigDecimal("5.00"),
                null, Enums.CostBasis.CONTRACTED,
                "One-off advance service fee on principal (factor pricing)");

        // ---------------------------------------------------------------- eligibility rules (R1-R5, BC p.12)
        seedRule("R1", "merchant.status == ACTIVE && approvalStatus == APPROVED", true);
        seedRule("R2", "confirmed_net_receipts(90d) > 0 && trading_days >= 30", true);
        seedRule("R3", "avg_monthly_net_receipts > 0 - cap = min(25000, 20% * avgMonthly, lenderLimit)", true);
        seedRule("R4", "no OPEN arrears case for merchant", true);
        seedRule("exclude_advance_proceeds", "repayment base excludes advance funding credits", true);
        seedRule("exclude_self_transfer", "repayment base excludes self-transfers (AML pattern)", true);
        seedRule("exclude_circular", "repayment base excludes circular supplier payments", true);
        seedRule("exclude_refunds", "repayment base excludes refund flows", true);
        seedRule("khata_excluded", "Khata (basic-phone credit) is never included in lender feed (BC p.12)", true);

        // ---------------------------------------------------------------- merchant limits (C10)
        LimitProfile limit = new LimitProfile();
        limit.setMerchantId(merchant.getId());
        limit.setMaxBalance(new BigDecimal("100000"));
        limit.setDailyDebitCap(new BigDecimal("5000"));
        limit.setWithdrawalCap(new BigDecimal("20000"));
        limit.setAdvanceCap(new BigDecimal("25000"));
        limit.setArrearsTriggerPct(new BigDecimal("5"));
        limitRepo.save(limit);

        // ---------------------------------------------------------------- notification templates (C40)
        seedSetting("mfa_policy", "PIN + SMS OTP required for balances and withdrawals (C03)");
        seedSetting("statement_retention", "statements downloadable within 60 minutes of generation");

        // ---------------------------------------------------------------- synthetic 120-day history (TC-SET-26)
        simulationService.syntheticHistory(merchant.getId(), 120, SEED_ACTOR, ADMIN);

        log.info("Seed identifiers: merchant phone 0811111111 (OTP 123456), supplier 0852222222/supplier123, "
                + "lender 0813333333/lender123, ops 0827777777/ops123, admin 0819999999/admin123");
    }

    private void seedTariff(String code, Enums.RateType rateType, BigDecimal value, BigDecimal externalCost,
                            Enums.CostBasis basis, String note) {
        var existing = tariffRepo.findTopByCodeOrderByEffectiveFromDesc(code);
        if (existing.isEmpty()) {
            FeeTariff t = new FeeTariff();
            t.setCode(code);
            t.setRateType(rateType);
            t.setValue(value);
            t.setExternalCost(externalCost);
            t.setCostBasis(basis);
            t.setNote(note);
            t.setEffectiveFrom(LocalDate.now().minusDays(30));
            tariffRepo.save(t);
        }
    }

    private void seedRule(String ruleCode, String definition, boolean enabled) {
        if (ruleRepo.findByRuleCode(ruleCode).isEmpty()) {
            EligibilityRule r = new EligibilityRule();
            r.setRuleCode(ruleCode);
            r.setDefinition(definition);
            r.setEnabled(enabled);
            r.setEffectiveFrom(LocalDate.now().minusDays(30));
            ruleRepo.save(r);
        }
    }

    private void seedSetting(String key, String value) {
        if (settingRepo.findBySettingKey(key).isEmpty()) {
            SecuritySetting s = new SecuritySetting();
            s.setSettingKey(key);
            s.setSettingValue(value);
            settingRepo.save(s);
        }
    }

    private void seedConnector(Enums.ConnectorType type, String name, String baseUrl, Enums.AuthType authType) {
        if (connectorRepo.findByConnectorType(type).isEmpty()) {
            com.mipay.sme.domain.ledger.Connector c = new com.mipay.sme.domain.ledger.Connector();
            c.setConnectorType(type);
            c.setName(name);
            c.setEnvironment(Enums.PaymentEnvironment.SANDBOX);
            c.setBaseUrl(baseUrl);
            c.setAuthType(authType);
            c.setStatus(Enums.ConnectorStatus.ACTIVE);
            connectorRepo.save(c);
        }
    }

    private String seedItem(String merchantId, String name, String unit, String reorderLevel,
                            String ruleId, Integer orderQuantity, String preferredSupplierNote) {
        InventoryItem item = new InventoryItem();
        item.setMerchantId(merchantId);
        item.setName(name);
        item.setUnit(unit);
        item.setReorderLevel(new BigDecimal(reorderLevel));
        item.setCurrentStock(new BigDecimal("12"));
        item.setStatus(Enums.InventoryItemStatus.ACTIVE);
        inventoryItemRepo.save(item);
        return item.getId();
    }
}
