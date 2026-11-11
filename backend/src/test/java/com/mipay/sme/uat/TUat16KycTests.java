package com.mipay.sme.uat;

import com.mipay.sme.domain.Enums;
import com.mipay.sme.web.Dtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KYC & Compliance acceptance tests — maps the KYC section of the PRD
 * (docs/KYC-COMPLIANCE-SPEC.md): screening at approval, sanctions-block,
 * risk-based review cycle, trigger-event re-screening and the ops read model.
 * Sandbox note: every screening provider is SIMULATED and labelled.
 */
@DisplayName("KYC & compliance (KYC spec §1-§5) — TC-KYC-01..06")
class TUat16KycTests extends UatSupport {

    @Test
    @DisplayName("TC-KYC-01 · Approval records IDENTITY + SANCTIONS checks and rates the merchant (SIMULATED)")
    void tcKyc01_approvalRecordsChecks() {
        var m = newApprovedMerchant(); // "retail" -> LOW risk, 36m review (sandbox rules)
        var rows = compliance.kycChecks("merchant", m.getId());
        assertThat(rows).extracting(r -> r.checkType()).contains("IDENTITY", "SANCTIONS");
        assertThat(rows).allSatisfy(r -> assertThat(r.result()).isEqualTo("PASS"));
        assertThat(rows).allSatisfy(r -> assertThat(r.details()).contains("simulated"));
        assertThat(m.getKycStatus()).isEqualTo(Enums.KycStatus.VERIFIED);
        assertThat(m.getKycRiskRating()).isEqualTo(Enums.KycRiskRating.LOW);
        assertThat(m.getKycNextReviewDate()).isAfter(LocalDate.now().plusMonths(35)); // ~36m cycle
        // every KYC decision is audited with actor + review-cycle note (TC-KYC-13 audit trail)
        var kycAudits = auditRepo.findAll().stream()
                .filter(a -> m.getId().equals(a.getEntityId()) && a.getAction().startsWith("kyc_"))
                .toList();
        assertThat(kycAudits).isNotEmpty();
        assertThat(kycAudits).allSatisfy(a -> {
            assertThat(a.getActorId()).isNotBlank();
            assertThat(a.getMeta()).contains("review cycle");
        });
    }

    @Test
    @DisplayName("TC-KYC-03 · Sanctions FAIL blocks approval: BLOCKED, KYC REJECTED, not provisioned")
    void tcKyc03_sanctionsFailBlocks() {
        var m = newPendingMerchant();
        Dtos.ApprovalResult r = onboarding.approveMerchant(m.getId(), true, "fail",
                "uat-blocker-" + seq(), "TPTS_OPS");
        assertThat(r.status()).isEqualTo("BLOCKED");
        assertThat(r.message()).contains("Sanctions check failed");
        var reloaded = merchants.findById(m.getId()).orElseThrow();
        assertThat(reloaded.getKycStatus()).isEqualTo(Enums.KycStatus.REJECTED);
        assertThat(reloaded.getApprovalStatus()).isNotEqualTo(Enums.ApprovalStatus.APPROVED);
        // blocked onboarding is auditable
        assertThat(auditRepo.findAll().stream()
                .filter(a -> "merchant_approval_blocked".equals(a.getAction()) && m.getId().equals(a.getEntityId()))
                .count()).isEqualTo(1);
        // the failed screening is recorded, so the panel shows the evidence
        assertThat(compliance.kycChecks("merchant", m.getId()).stream()
                .anyMatch(c -> "SANCTIONS".equals(c.checkType())
                        && Enums.KycResult.FAIL.name().equals(c.result()))).isTrue();
    }

    @Test
    @DisplayName("TC-KYC-20 · Risk rules: cash-heavy or sanctions-review trade rates HIGH with a 12m cycle")
    void tcKyc20_riskRules() {
        var m = newPendingMerchant("Cash and Carry " + seq(), "CASH", "REG-CASH-" + seq());
        Dtos.ApprovalResult r = onboarding.approveMerchant(m.getId(), true, "CLEAR",
                "uat-rater-" + seq(), "TPTS_OPS");
        assertThat(r.status()).isEqualTo("APPROVED");
        var reloaded = merchants.findById(m.getId()).orElseThrow();
        assertThat(reloaded.getKycRiskRating()).isEqualTo(Enums.KycRiskRating.HIGH);
        assertThat(reloaded.getKycNextReviewDate()).isBefore(LocalDate.now().plusMonths(13)); // 12m cycle
    }

    @Test
    @DisplayName("TC-KYC-10 · Receiving-account change is a trigger event: SANCTIONS re-screening row + audit")
    void tcKyc10_bankChangeRescreens() {
        var s = newApprovedSupplier("Re-screen item", "10.00");
        int before = compliance.kycChecks("supplier", s.getId()).size();
        // owner requests a bank change (requester), another ops user approves (second-person rule)
        Dtos.ApprovalResult change = onboarding.changeOrgBankAccount(orgOfEntity(Enums.OrgType.SUPPLIER, s.getId()).getId(),
                new Dtos.BankAccountChangeRequest("FNB", "3000" + String.format("%05d", seq() % 100_000),
                        "UAT Supplier " + seq(), "switch requested"),
                "uat-requester-" + seq());
        assertThat(change.status()).isEqualTo("PENDING_APPROVAL");
        var pending = bankAccounts.findByOrgIdAndStatus(orgOfEntity(Enums.OrgType.SUPPLIER, s.getId()).getId(), Enums.BankAccountStatus.PENDING_APPROVAL)
                .orElseThrow();
        Dtos.ApprovalResult approved = onboarding.approveBankAccount(pending.getId(), true,
                "uat-approver-" + seq(), "TPTS_OPS");
        assertThat(approved.status()).isEqualTo("APPROVED");
        // exactly one new SANCTIONS re-screening row for the trigger event
        var rows = compliance.kycChecks("supplier", s.getId());
        assertThat(rows.size()).isEqualTo(before + 1);
        assertThat(rows.get(0).checkType()).isEqualTo("SANCTIONS");
        assertThat(rows.get(0).details()).contains("bank-change re-screening");
        // two trigger events audited: the fixture's initial account approval + this change
        // (every receiving-account activation re-opens screening per KYC spec §4)
        assertThat(auditRepo.findAll().stream()
                .filter(a -> "kyc_rescreened".equals(a.getAction()) && s.getId().equals(a.getEntityId()))
                .count()).isEqualTo(2);
    }

    @Test
    @DisplayName("TC-KYC-13 · Ops read model: GET /api/ops/kyc/{type}/{id} returns the checks (newest first)")
    void tcKyc13_opsReadModel() throws Exception {
        var s = newApprovedSupplier("Read-model item", "5.00");
        String opsToken = passwordToken("0827777777", "ops123");
        MvcResult res = bearerGet("/api/ops/kyc/supplier/" + s.getId(), opsToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].entityType").value("supplier"))
                .andExpect(jsonPath("$[0].entityId").value(s.getId()))
                .andExpect(jsonPath("$[0].checkType").value("IDENTITY"))
                .andExpect(jsonPath("$[0].result").value("PASS"))
                .andReturn();
        String body = res.getResponse().getContentAsString();
        assertThat(body).contains("IDENTITY");
        // role gate: merchants cannot read the ops KYC panel
        String merchToken = merchantToken(seedMerchant().getOwnerPhone());
        bearerGet("/api/ops/kyc/supplier/" + s.getId(), merchToken)
                .andExpect(status().isForbidden());
    }
}
