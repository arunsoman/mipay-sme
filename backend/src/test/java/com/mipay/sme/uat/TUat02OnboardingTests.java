package com.mipay.sme.uat;

import com.mipay.sme.common.ConflictException;
import com.mipay.sme.common.SimException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.AuditLog;
import com.mipay.sme.domain.core.Merchant;
import com.mipay.sme.domain.core.Supplier;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.web.Dtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UAT SB p.8 acceptance tests — onboarding, approvals and the second-person rule.
 */
@DisplayName("Onboarding & approvals (UAT SB p.8) — TC-ONB")
class TUat02OnboardingTests extends UatSupport {

    @Test
    @DisplayName("TC-ONB-01 · Merchant KYC → TPTS approval provisions balance account, QR, short code")
    void tcOnb01_merchantKycAndOnboarding() throws Exception {
        String phone = phone();
        onboarding.registerMerchant(new Dtos.RegisterMerchantRequest(phone,
                "Ndapewa Trading " + seq(), "Ndapewa Haufiku", "85040100111", "RETAIL", "REG-2026-000111"));
        Merchant m = merchants.findByOwnerPhone(phone).orElseThrow();

        assertThat(m.getKycStatus()).isEqualTo(Enums.KycStatus.PENDING);
        assertThat(m.getApprovalStatus()).isEqualTo(Enums.ApprovalStatus.PENDING);

        Dtos.ApprovalResult r = onboarding.approveMerchant(m.getId(), true, "CLEAR", "uat-ops-1", "TPTS_OPS");
        assertThat(r.status()).isEqualTo("APPROVED");

        Merchant approved = merchants.findById(m.getId()).orElseThrow();
        assertThat(approved.getKycStatus()).isEqualTo(Enums.KycStatus.VERIFIED);
        assertThat(approved.getApprovalStatus()).isEqualTo(Enums.ApprovalStatus.APPROVED);
        assertThat(approved.getApprovedBy()).isEqualTo("uat-ops-1");
        assertThat(approved.getApprovedAt()).isNotNull();

        // balance account + issuer mapping + QR + short code (M04/M07/M08 ready)
        assertThat(accounts.findByOwnerTypeAndOwnerId(Enums.OwnerType.MERCHANT, m.getId())).isPresent();
        assertThat(qrCodes.findTopByMerchantIdAndStatusOrderByCreatedAtDesc(m.getId(), Enums.QrStatus.ACTIVE))
                .isPresent();
        assertThat(qrCodes.findTopByMerchantIdAndStatusOrderByCreatedAtDesc(m.getId(), Enums.QrStatus.ACTIVE).orElseThrow()
                .getQrPayload()).startsWith("mipay://qr/");
        assertThat(shortCodes.findTopByMerchantIdAndStatusOrderByCreatedAtDesc(m.getId(), Enums.QrStatus.ACTIVE)).isPresent();
        assertThat(mappings.findAll().stream()
                .anyMatch(x -> m.getId().equals(x.getMerchantId()))).isTrue();

        // privileged action audited (BC p.18)
        assertThat(auditRepo.search(false, PageRequest.of(0, 200)).getContent().stream()
                .anyMatch(a -> "merchant_approved".equals(a.getAction()) && m.getId().equals(a.getEntityId())))
                .isTrue();
    }

    @Test
    @DisplayName("TC-ONB-02 · Supplier onboarding: owners, receiving account approval, catalogue provisioning")
    void tcOnb02_supplierOnboarding() {
        var s = newApprovedSupplier("Maize meal 12.5kg", "180.00");

        Supplier approvedSupplier = suppliers.findById(s.getId()).orElseThrow(); // fresh read
        assertThat(approvedSupplier.getApprovalStatus()).isEqualTo(Enums.ApprovalStatus.APPROVED);
        // receiving (credit side) account pending → independently approved inside the helper
        assertThat(accounts.findByOwnerTypeAndOwnerId(Enums.OwnerType.SUPPLIER, s.getId())).isPresent();
        assertThat(accounts.findByOwnerTypeAndOwnerId(Enums.OwnerType.SUPPLIER, s.getId()).orElseThrow()
                .getIssuerAccountRef()).startsWith("NDB-S-");
        // KYC identity check recorded (BC p.10)
        assertThat(complianceChecksFor("supplier", s.getId())).isNotEmpty();

        // nothing pending left for this supplier
        assertThat(bankAccounts.findBySupplierIdAndStatus(s.getId(), Enums.BankAccountStatus.PENDING_APPROVAL))
                .isEmpty();
    }

    private java.util.List<com.mipay.sme.domain.compliance.KycCheck> complianceChecksFor(String entityType, String entityId) {
        return kycCheckRepo.findAll().stream()
                .filter(c -> entityType.equals(c.getEntityType()) && entityId.equals(c.getEntityId()))
                .toList();
    }

    @org.springframework.beans.factory.annotation.Autowired
    com.mipay.sme.repository.KycCheckRepository kycCheckRepo;  // plain JpaRepository — used via findAll()

    @Test
    @DisplayName("TC-ONB-03 · Supplier bank-account change requires an approver different from the requester")
    void tcOnb03_bankChangeSecondPersonRule() {
        var s = newApprovedSupplier("Cooking oil 750ml", "26.00");
        User supplierUser = users.findById(s.getUserId()).orElseThrow();

        Dtos.ApprovalResult request = onboarding.changeSupplierBankAccount(s.getId(),
                new Dtos.BankAccountChangeRequest("Nedbank", "20009988", "UAT Supplier " + seq(),
                        "new receiving account"),
                supplierUser.getId());
        assertThat(request.status()).isEqualTo("PENDING_APPROVAL");

        // same user cannot request and approve (independence rule, UAT SB p.8)
        assertThatThrownBy(() ->
                onboarding.approveBankAccount(request.id(), true, supplierUser.getId(), "SUPPLIER"))
                .isInstanceOf(ConflictException.class)
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("INDEPENDENCE_RULE"));

        // different approver succeeds; previous approved account is revoked
        Dtos.ApprovalResult ok = onboarding.approveBankAccount(request.id(), true, "uat-ops-2", "TPTS_OPS");
        assertThat(ok.status()).isEqualTo("APPROVED");
        var rows = bankAccounts.findBySupplierIdOrderByCreatedAtDesc(s.getId());
        assertThat(rows.stream().filter(a -> a.getStatus() == Enums.BankAccountStatus.APPROVED).count())
                .isEqualTo(1);
        assertThat(rows).anyMatch(a -> a.getStatus() == Enums.BankAccountStatus.REVOKED);

        // approval recorded with before/after (BC p.18)
        assertThat(auditRepo.search(false, PageRequest.of(0, 100)).getContent().stream()
                .anyMatch(a -> "bank_account_approved".equals(a.getAction())
                        && request.id().equals(a.getEntityId())))
                .isTrue();
    }

    @Test
    @DisplayName("TC-ONB-04 · Unapproved merchant refused: payments, orders and credit all blocked")
    void tcOnb04_unapprovedMerchantRefused() {
        Merchant m = newPendingMerchant();

        assertThatThrownBy(() -> payments.accept(m.getId(),
                new Dtos.AcceptPaymentRequest(bd("10.00"), Enums.PaymentChannel.QR, "payer", null, null)))
                .isInstanceOfSatisfying(SimException.class,
                        e -> assertThat(e.getCode()).isEqualTo("MERCHANT_NOT_APPROVED"));
        assertThat(paymentRepo.countByMerchantIdAndCreatedAtAfter(m.getId(), now())).isZero();

        assertThatThrownBy(() -> b2b.placeOrder(m.getId(), new Dtos.CreateOrderRequest(seedSupplier().getId(),
                java.util.List.of(new Dtos.CartLine("any", BigDecimal.ONE)))))
                .isInstanceOf(SimException.class);

        var lender = newLender(bd("0.10"), bd("0.05"), null, bd("25000"));
        assertThatThrownBy(() -> advances.requestAssessment(m.getId(), lender.getId(), null))
                .isInstanceOf(SimException.class);
    }

    @Test
    @DisplayName("TC-OPS-26 · Sanctions screening failure blocks onboarding (K01, BC p.28)")
    void tcOps26_sanctionsFailureBlocksOnboarding() {
        Merchant m = newPendingMerchant();
        Dtos.ApprovalResult blocked = onboarding.approveMerchant(m.getId(), true, "fail",
                "uat-ops-3", "TPTS_OPS");
        assertThat(blocked.status()).isEqualTo("BLOCKED");
        assertThat(merchants.findById(m.getId()).orElseThrow().getKycStatus())
                .isEqualTo(Enums.KycStatus.REJECTED);

        assertThatThrownBy(() -> payments.accept(m.getId(),
                new Dtos.AcceptPaymentRequest(bd("10.00"), Enums.PaymentChannel.QR, "payer", null, null)))
                .isInstanceOf(SimException.class);

        assertThat(auditRepo.search(false, PageRequest.of(0, 100)).getContent().stream()
                .anyMatch(a -> "merchant_approval_blocked".equals(a.getAction())
                        && m.getId().equals(a.getEntityId())))
                .isTrue();
    }
}
