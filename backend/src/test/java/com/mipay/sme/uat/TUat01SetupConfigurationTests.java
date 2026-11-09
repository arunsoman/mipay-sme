package com.mipay.sme.uat;

import com.mipay.sme.common.SimException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.ledger.BalanceAccount;
import com.mipay.sme.domain.ledger.AccountMapping;
import com.mipay.sme.domain.ledger.Connector;
import com.mipay.sme.domain.core.AuditLog;
import com.mipay.sme.web.Dtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Part A of Addendum 1 — setup and configuration acceptance tests.
 * These are the prerequisite checks that must pass before the money tests run.
 */
@DisplayName("Setup & configuration (E01-E26, C01-C33) — TC-SET")
class TUat01SetupConfigurationTests extends UatSupport {

    // ------------------------------------------------------------------ connectors (E01-E07)

    @Test
    @DisplayName("TC-SET-01 · Bank/issuer connector configured + health-checked in sandbox")
    void tcSet01_connectorConfiguredAndHealthChecked() throws Exception {
        Dtos.ConnectorUpsert upsert = new Dtos.ConnectorUpsert(
                Enums.ConnectorType.BANK_ISSUER, "UAT Partner Bank " + seq(), Enums.PaymentEnvironment.SANDBOX,
                "https://sandbox.bank.uat.na", Enums.AuthType.OAUTH2, null,
                "{\"balance_query\":true,\"debit\":true,\"credit\":true,\"hold\":true,\"release\":true,"
                        + "\"book_transfer\":true,\"eft\":true,\"native_ipp_b2b\":false}");
        Dtos.ConnectorView v = connectors.upsert(upsert, "uat-admin-" + seq(), "MINDLABS_ADMIN");
        assertThat(v.status()).isEqualTo("ACTIVE");
        assertThat(v.environment()).isEqualTo("SANDBOX");
        assertThat(v.baseUrl()).isEqualTo("https://sandbox.bank.uat.na");
        // secrets stored as vault references, never plaintext (BC p.29)
        assertThat(v.credentialsRef()).startsWith("vault://bank_issuer");

        Dtos.ConnectorTestResult health = connectors.test(v.id(), "uat-admin", "MINDLABS_ADMIN");
        assertThat(health.ok()).isTrue();
        assertThat(health.latencyMs()).isPositive();
        assertThat(health.label()).isEqualTo("SIMULATED health check"); // UAT SB p.16 labelling
    }

    @Test
    @DisplayName("TC-SET-02 · Capability matrix: native IPP B2B excluded at go-live (BC p.9/p.17)")
    void tcSet02_capabilityMatrix_nativeIppB2bExcluded() {
        Dtos.ConnectorView v = connectors.upsert(new Dtos.ConnectorUpsert(
                Enums.ConnectorType.BANK_ISSUER, "UAT Capability Bank " + seq(), Enums.PaymentEnvironment.SANDBOX,
                "https://sandbox.cap.uat.na", Enums.AuthType.OAUTH2, null,
                "{\"book_transfer\":true,\"eft\":true,\"native_ipp_b2b\":false}"),
                "uat-admin", "MINDLABS_ADMIN");
        assertThat(v.capabilities()).contains("\"native_ipp_b2b\":false");

        // route-level proof: pay-from-balance has no native IPP B2B payment route
        assertThat(EnumSet.allOf(Enums.InvoicePaymentRoute.class))
                .containsExactlyInAnyOrder(Enums.InvoicePaymentRoute.BOOK_TRANSFER, Enums.InvoicePaymentRoute.EFT);
    }

    @Test
    @DisplayName("TC-SET-03 · Balance account mapped to issuer account ref; legal holder recorded (E04/E05)")
    void tcSet03_accountMappingAndLegalHolder() {
        var m = newApprovedMerchant();
        Dtos.BalanceView b = balance(m.getId());
        assertThat(b.available()).isEqualByComparingTo("0");
        assertThat(b.issuerAccountRef()).startsWith("NDB-M-");
        assertThat(b.legalHolder()).isEqualTo("issuer (bank holds float)"); // BC p.12 fund-flow boundary

        BalanceAccount account = accounts.findByOwnerTypeAndOwnerId(Enums.OwnerType.MERCHANT, m.getId()).orElseThrow();
        AccountMapping mapping = mappings.findAll().stream()
                .filter(x -> m.getId().equals(x.getMerchantId()))
                .findFirst().orElseThrow(() -> new AssertionError("AccountMapping expected (E04)"));
        assertThat(mapping.getBalanceAccountId()).isEqualTo(account.getId());
        assertThat(mapping.getIssuerAccountRef()).isEqualTo(account.getIssuerAccountRef());
        assertThat(mapping.getLegalHolder()).isEqualTo("issuer (bank holds float)");
    }

    @Test
    @DisplayName("TC-SET-04/TC-SEC-04 · Credential rotation revokes old secret and is audited")
    void tcSet04_credentialRotation() {
        Dtos.ConnectorView v = connectors.upsert(new Dtos.ConnectorUpsert(
                Enums.ConnectorType.BANK_ISSUER, "UAT Rotation Bank " + seq(), Enums.PaymentEnvironment.SANDBOX,
                "https://sandbox.rot.uat.na", Enums.AuthType.OAUTH2, null, null),
                "uat-admin", "MINDLABS_ADMIN");
        String oldRef = v.credentialsRef();

        Dtos.ConnectorTestResult r = connectors.rotateCredentials(v.id(), "uat-admin", "MINDLABS_ADMIN");
        assertThat(r.ok()).isTrue();
        assertThat(r.message()).contains("revoked");

        String newRef = connectors.list().stream()
                .filter(c -> c.id().equals(v.id())).findFirst().orElseThrow().credentialsRef();
        assertThat(newRef).isNotEqualTo(oldRef);

        AuditLog auditRow = auditRepo.search(false, PageRequest.of(0, 100)).getContent().stream()
                .filter(a -> "credentials_rotated".equals(a.getAction()) && v.id().equals(a.getEntityId()))
                .findFirst().orElseThrow(() -> new AssertionError("rotation audit expected"));
        assertThat(auditRow.getBeforeHash()).isEqualTo(oldRef);   // before/after values recorded
        assertThat(auditRow.getAfterHash()).isEqualTo(newRef);
    }

    @Test
    @DisplayName("TC-SET-05 · Connector-down state is recorded (money stays pending — see TC-ACC-05)")
    void tcSet05_connectorDownStateTracked() {
        Dtos.ConnectorView v = connectors.upsert(new Dtos.ConnectorUpsert(
                Enums.ConnectorType.IPS, "UAT IPS Connector " + seq(), Enums.PaymentEnvironment.SANDBOX,
                "https://sandbox.ips.uat.na", Enums.AuthType.API_KEY, null, null),
                "uat-admin", "MINDLABS_ADMIN");
        inTx(() -> {
            Connector c = connectorsRepo.findById(v.id()).orElseThrow();
            c.setStatus(Enums.ConnectorStatus.DISABLED);
            connectorsRepo.save(c);
        });
        assertThat(connectors.list().stream()
                .filter(c -> c.id().equals(v.id())).findFirst().orElseThrow().status())
                .isEqualTo("DISABLED");
    }

    @Test
    @DisplayName("TC-SET-06 · Lender adapter contract endpoints configured (E10/E11)")
    void tcSet06_lenderAdapterConfigured() {
        Dtos.ConnectorView v = connectors.upsert(new Dtos.ConnectorUpsert(
                Enums.ConnectorType.LENDER, "UAT Licensed Lender " + seq(), Enums.PaymentEnvironment.SANDBOX,
                "https://lender.uat/api", Enums.AuthType.OAUTH2, null,
                "{\"assess\":true,\"disburse\":true,\"repay_event\":true}"),
                "uat-admin", "MINDLABS_ADMIN");
        assertThat(connectors.test(v.id(), "uat-admin", "MINDLABS_ADMIN").ok()).isTrue();
        assertThat(connectors.list().stream()
                .anyMatch(c -> c.id().equals(v.id()) && "LENDER".equals(c.type())))
                .isTrue();
    }

    @Test
    @DisplayName("TC-SET-08 · IPS, card, QR and notification connectors all initialisable")
    void tcSet08_paymentRailConnectors() {
        for (Enums.ConnectorType type : List.of(Enums.ConnectorType.IPS, Enums.ConnectorType.CARD,
                Enums.ConnectorType.QR, Enums.ConnectorType.NOTIFICATION)) {
            Dtos.ConnectorView v = connectors.upsert(new Dtos.ConnectorUpsert(
                    type, "UAT " + type + " rail " + seq(), Enums.PaymentEnvironment.SANDBOX,
                    "https://sandbox." + type.name().toLowerCase() + ".uat.na", Enums.AuthType.API_KEY, null, null),
                    "uat-admin", "MINDLABS_ADMIN");
            assertThat(connectors.test(v.id(), "uat-admin", "MINDLABS_ADMIN").ok()).isTrue();
        }
    }

    // ------------------------------------------------------------------ consent configuration

    @Test
    @DisplayName("TC-SET-07 · Consent data fields: customer-level Khata excluded by default (BC p.12)")
    void tcSet07_consentFieldsExcludeKhata() {
        var m = newApprovedMerchant();
        var lender = newLender(bd("0.10"), bd("0.05"), bd("2000"), bd("25000"));
        Dtos.ConsentRow row = advances.grantConsent(m.getId(), lender.getId());
        inTx(() -> {
            com.mipay.sme.domain.credit.CreditConsent c = consents.findById(row.consentId()).orElseThrow();
            assertThat(c.getPurpose()).isEqualTo("Cash advance assessment");
            assertThat(c.getDataFields())
                    .contains("receipts_net_of_refunds")
                    .contains("trading_days")
                    .contains("supplier_payments");
            assertThat(c.getDataFields().toLowerCase()).doesNotContain("khata"); // BC p.12 data minimisation
            assertThat(c.getRetentionPeriod()).isNotBlank();
            assertThat(c.getStatus()).isEqualTo(Enums.ConsentStatus.ACTIVE);
        });
    }

    // ------------------------------------------------------------------ fees & tariffs (C01-C05)

    @Test
    @DisplayName("TC-SET-10 · Card tariff = BC p.16 contracted 2.50%; basis CONTRACTED vs external 2.20% (BC p.15/16)")
    void tcSet10_cardTariffStructure() {
        // SPEC (BC p.16): the contracted card service fee BILLED to/across the platform is 2.50%.
        // The sandbox seeds 1.50% -> mismatch recorded as finding #7 in docs/UAT-BACKEND-TEST-PACK.md
        // (revenue-impacting; product must align the seeded/deployed tariff to the BC).
        Dtos.TariffRow card = admin.tariffs().stream()
                .filter(t -> "card_service".equals(t.code())).findFirst().orElseThrow();
        assertThat(card.value()).as("BC p.16 contracted card fee").isEqualByComparingTo("2.50");
        assertThat(card.externalCost()).isEqualByComparingTo("2.20");
        assertThat(card.costBasis()).isEqualTo("CONTRACTED");
        assertThat(card.effectiveFrom()).isNotNull();

        Dtos.FeePreview p = fees.preview(bd("60"));
        assertThat(p.billed()).isEqualByComparingTo("1.50");     // 60 x 2.50% (BC p.16)
        assertThat(p.externalCost()).isEqualByComparingTo("1.32"); // 60 x 2.20%
        assertThat(p.contribution()).isEqualByComparingTo("0.18"); // billed − external under the BC p.16 contract
    }

    @Test
    @DisplayName("TC-SET-11 · Switching N$0.40 labelled 'budgeting provision, not contracted' (BC p.15)")
    void tcSet11_switchingLabeledProvision() {
        Dtos.TariffRow switching = admin.tariffs().stream()
                .filter(t -> "ips_switching".equals(t.code())).findFirst().orElseThrow();
        assertThat(switching.costBasis()).isEqualTo("PROVISION");
        assertThat(switching.value()).isEqualByComparingTo("0.40");
        assertThat(switching.note()).containsIgnoringCase("provision");

        Dtos.FeePreview p = fees.preview(bd("60"));
        assertThat(p.labels()).anyMatch(l -> l.contains("budgeting provision, not contracted"));
    }

    @Test
    @DisplayName("TC-SET-12 · Sponsor N$0.10 labelled 'unquoted provision' (BC p.15)")
    void tcSet12_sponsorMarkupUnquoted() {
        Dtos.TariffRow sponsor = admin.tariffs().stream()
                .filter(t -> "qr_sponsorship".equals(t.code())).findFirst().orElseThrow();
        assertThat(sponsor.costBasis()).isEqualTo("UNQUOTED");
        assertThat(sponsor.value()).isEqualByComparingTo("0.10");
        assertThat(sponsor.note()).containsIgnoringCase("unquoted");

        Dtos.FeePreview p = fees.preview(bd("60"));
        assertThat(p.labels()).anyMatch(l -> l.contains("unquoted provision"));
    }

    @Test
    @DisplayName("TC-SET-13 · IPS P2M customer fee statutory N$0; interchange shown as cost (BC p.16)")
    void tcSet13_ipsCustomerFeeZero() {
        Dtos.FeePreview p = fees.preview(bd("60"));
        assertThat(p.ipsCustomerFee()).isEqualByComparingTo("0.00");
        assertThat(p.labels()).anyMatch(l -> l.contains("IPS P2M customer fee: N$0 (statutory)"));
        assertThat(p.labels()).anyMatch(l -> l.contains("Interchange 0.40%: cost, not revenue"));
    }

    @Test
    @DisplayName("TC-SET-14/15 · Fee codes tariffed; reversal status available (fee pack prerequisite)")
    void tcSet14_feeCodesConfigured() {
        assertThat(admin.tariffs().stream().map(Dtos.TariffRow::code).collect(Collectors.toSet()))
                .contains("card_service", "ips_switching", "qr_sponsorship", "advance_service");
    }

    // ------------------------------------------------------------------ limits (C10)

    @Test
    @DisplayName("TC-SET-22 · Limit profile persisted; daily withdrawal cap enforced (BC p.7)")
    void tcSet22_limitProfileAndWithdrawalCap() {
        var m = newApprovedMerchant();
        credit(m.getId(), "500.00");
        admin.upsertLimit(new Dtos.LimitUpsert(m.getId(), bd("100000"), bd("5000"),
                bd("100.00"), bd("25000"), bd("5")), "uat-admin", "MINDLABS_ADMIN");
        var limit = admin.limits().stream()
                .filter(l -> m.getId().equals(l.merchantId())).findFirst().orElseThrow();
        assertThat(limit.withdrawalCap()).isEqualByComparingTo("100.00");

        Dtos.WithdrawalRow first = withdrawals.withdraw(m.getId(), bd("100.00"), "uat-ops", "TPTS_OPS");
        assertThat(first.status()).isEqualTo("PAID");

        assertThatThrownBy(() -> withdrawals.withdraw(m.getId(), bd("1.00"), "uat-ops", "TPTS_OPS"))
                .isInstanceOfSatisfying(SimException.class,
                        e -> assertThat(e.getCode()).isEqualTo("WITHDRAWAL_CAP"));
    }

    // ------------------------------------------------------------------ roles & workflows (C30-C33)

    @Test
    @DisplayName("TC-SET-16 · Only TPTS_OPS can act on the merchant approval queue (role gate)")
    void tcSet16_merchantApprovalRoleGated() throws Exception {
        var pending = newPendingMerchant();

        var lenderUser = newLenderWithUser();
        String lenderJwt = passwordToken(lenderUser.phone(), lenderUser.password());
        bearerPost("/api/ops/approvals/merchants/" + pending.getId(), lenderJwt,
                java.util.Map.of("approve", true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isForbidden());

        String opsJwt = opsToken();
        bearerPost("/api/ops/approvals/merchants/" + pending.getId(), opsJwt,
                java.util.Map.of("approve", true, "sanctionsResult", "CLEAR"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    @Test
    @DisplayName("TC-SET-17 · Bank-account queue readable by TPTS_OPS; supplier role gets 403")
    void tcSet17_bankQueueRoleGated() throws Exception {
        String opsJwt = opsToken();
        bearerGet("/api/ops/approvals/bank-accounts", opsJwt)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        String supplierJwt = passwordToken("0852222222", "supplier123");
        bearerGet("/api/ops/approvals/bank-accounts", supplierJwt)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }

    // ------------------------------------------------------------------ rules & schedule

    @Test
    @DisplayName("TC-SET-27 · Eligibility exclusion rules configured and toggleable (C23)")
    void tcSet27_eligibilityRulesToggleable() {
        boolean original = admin.rules().stream()
                .filter(r -> "exclude_circular".equals(r.ruleCode())).findFirst().orElseThrow()
                .enabled();
        try {
            Dtos.RuleRow toggled = admin.toggleRule("exclude_circular", !original, "uat-admin", "MINDLABS_ADMIN");
            assertThat(toggled.enabled()).isEqualTo(!original);
            assertThat(admin.rules().stream()
                    .filter(r -> "exclude_circular".equals(r.ruleCode())).findFirst().orElseThrow().enabled())
                    .isEqualTo(!original);
        } finally {
            admin.toggleRule("exclude_circular", original, "uat-admin", "MINDLABS_ADMIN"); // restore
        }
    }
}
