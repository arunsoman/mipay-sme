package com.mipay.sme.service;

import com.mipay.sme.common.ConflictException;
import com.mipay.sme.common.NotFoundException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.core.Merchant;
import com.mipay.sme.domain.core.Supplier;
import com.mipay.sme.domain.core.SupplierBankAccount;
import com.mipay.sme.domain.core.User;
import com.mipay.sme.domain.ledger.AccountMapping;
import com.mipay.sme.domain.ledger.BalanceAccount;
import com.mipay.sme.domain.ledger.Connector;
import com.mipay.sme.domain.payments.QrCode;
import com.mipay.sme.domain.payments.ShortCode;
import com.mipay.sme.repository.AccountMappingRepository;
import com.mipay.sme.repository.BalanceAccountRepository;
import com.mipay.sme.repository.ConnectorRepository;
import com.mipay.sme.repository.MerchantRepository;
import com.mipay.sme.repository.QrCodeRepository;
import com.mipay.sme.repository.ShortCodeRepository;
import com.mipay.sme.repository.SupplierBankAccountRepository;
import com.mipay.sme.repository.SupplierRepository;
import com.mipay.sme.repository.UserRepository;
import com.mipay.sme.web.Dtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;

/**
 * Onboarding and approvals (UAT SB p.8, TC-ONB-01..04):
 * KYC -> TPTS approval -> balance account + QR + short-code provisioning.
 * Bank account changes require an approver distinct from the requester.
 */
@Service
@RequiredArgsConstructor
public class OnboardingService {

    private final UserRepository userRepo;
    private final MerchantRepository merchantRepo;
    private final SupplierRepository supplierRepo;
    private final SupplierBankAccountRepository bankAccountRepo;
    private final BalanceAccountRepository accountRepo;
    private final AccountMappingRepository mappingRepo;
    private final ConnectorRepository connectorRepo;
    private final QrCodeRepository qrRepo;
    private final ShortCodeRepository shortCodeRepo;
    private final ComplianceService complianceService;
    private final AuditService auditService;

    private final SecureRandom random = new SecureRandom();

    @Transactional
    public Dtos.Message registerMerchant(Dtos.RegisterMerchantRequest req) {
        if (userRepo.existsByPhone(req.phone())) {
            throw new ConflictException("Phone already registered");
        }
        User user = new User();
        user.setName(req.ownerName());
        user.setPhone(req.phone());
        user.setRole(Enums.UserRole.MERCHANT);
        user.setStatus(Enums.UserStatus.ACTIVE);
        userRepo.save(user);

        Merchant m = new Merchant();
        m.setUserId(user.getId());
        m.setBusinessName(req.businessName());
        m.setBusinessType(req.businessType());
        m.setRegistrationNumber(req.registrationNumber());
        m.setOwnerName(req.ownerName());
        m.setOwnerIdNumber(req.ownerIdNumber());
        m.setOwnerPhone(req.phone());
        m.setKycStatus(Enums.KycStatus.PENDING);
        m.setApprovalStatus(Enums.ApprovalStatus.PENDING);
        merchantRepo.save(m);

        auditService.record(user.getId(), "MERCHANT", "merchant_registered", "merchant", m.getId(),
                null, null, "self registration", false, null);
        return new Dtos.Message("Registration received - pending TPTS approval (UAT SB p.8)", null);
    }

    @Transactional
    public Dtos.Message registerSupplier(Dtos.RegisterSupplierRequest req) {
        if (userRepo.existsByPhone(req.phone())) {
            throw new ConflictException("Phone already registered");
        }
        User user = new User();
        user.setName(req.ownerName());
        user.setPhone(req.phone());
        user.setRole(Enums.UserRole.SUPPLIER);
        user.setStatus(Enums.UserStatus.ACTIVE);
        userRepo.save(user);

        Supplier s = new Supplier();
        s.setUserId(user.getId());
        s.setBusinessName(req.businessName());
        s.setRegistrationNumber(req.registrationNumber());
        s.setOwnerName(req.ownerName());
        s.setOwnerPhone(req.phone());
        s.setApprovalStatus(Enums.ApprovalStatus.PENDING);
        supplierRepo.save(s);

        SupplierBankAccount account = new SupplierBankAccount();
        account.setSupplier(s);
        account.setBankName(req.bankName());
        account.setAccountNumber(req.accountNumber());
        account.setAccountHolder(req.accountHolder());
        account.setStatus(Enums.BankAccountStatus.PENDING_APPROVAL);
        account.setRequestedBy(user.getId());
        account.setRequestNote("initial onboarding");
        bankAccountRepo.save(account);

        auditService.record(user.getId(), "SUPPLIER", "supplier_registered", "supplier", s.getId(),
                null, null, "bank account pending approval", false, null);
        return new Dtos.Message("Registration received - supplier and bank account pending approval (UAT SB p.8)", null);
    }

    @Transactional
    public Dtos.ApprovalResult approveMerchant(String merchantId, boolean approve, String sanctionsResult,
                                               String approverId, String approverRole) {
        Merchant m = merchantRepo.findById(merchantId).orElseThrow(() -> new NotFoundException("Merchant not found"));
        if (approve) {
            Enums.KycResult sanctions = "fail".equalsIgnoreCase(sanctionsResult)
                    ? Enums.KycResult.FAIL : Enums.KycResult.PASS;
            complianceService.recordCheck("merchant", m.getId(), Enums.KycCheckType.IDENTITY,
                    Enums.KycResult.PASS, "simulated KYC provider");
            complianceService.recordCheck("merchant", m.getId(), Enums.KycCheckType.SANCTIONS,
                    sanctions, "simulated sanctions screening");
            if (sanctions == Enums.KycResult.FAIL) {
                m.setKycStatus(Enums.KycStatus.REJECTED);
                merchantRepo.save(m);
                auditService.record(approverId, approverRole, "merchant_approval_blocked", "merchant", m.getId(),
                        m.getKycStatus().name(), m.getKycStatus().name(), "sanctions fail (TC-OPS-26)", false, null);
                return new Dtos.ApprovalResult(m.getId(), "BLOCKED",
                        "Sanctions check failed; onboarding blocked (TC-OPS-26)");
            }
            m.setKycStatus(Enums.KycStatus.VERIFIED);
            m.setApprovalStatus(Enums.ApprovalStatus.APPROVED);
            m.setApprovedBy(approverId);
            m.setApprovedAt(Instant.now());
            applyRiskRating(m, sanctions, approverId, approverRole);
            merchantRepo.save(m);

            provision(m);
            auditService.record(approverId, approverRole, "merchant_approved", "merchant", m.getId(),
                    "PENDING", "APPROVED", null, false, null);
            return new Dtos.ApprovalResult(m.getId(), "APPROVED",
                    "Merchant approved; balance account, QR code and short code provisioned");
        } else {
            m.setKycStatus(Enums.KycStatus.REJECTED);
            merchantRepo.save(m);
            auditService.record(approverId, approverRole, "merchant_rejected", "merchant", m.getId(),
                    "PENDING", "REJECTED", null, false, null);
            return new Dtos.ApprovalResult(m.getId(), "REJECTED", "Registration rejected by TPTS ops");
        }
    }

    /** Risk-based review cycle (KYC spec §5): sanctions-review or cash-heavy trade → HIGH.
     *  LOW 36m / MEDIUM 24m / HIGH 12m next-review date. */
    private void applyRiskRating(Merchant m, Enums.KycResult sanctions, String approverId, String approverRole) {
        boolean manualSanctions = sanctions == Enums.KycResult.MANUAL_REVIEW;
        String type = (m.getBusinessType() == null ? "" : m.getBusinessType().toLowerCase());
        boolean cashHeavy = type.contains("cash") || type.contains("gambling") || type.contains("forex");
        Enums.KycRiskRating rating = manualSanctions || cashHeavy ? Enums.KycRiskRating.HIGH
                : type.isBlank() ? Enums.KycRiskRating.MEDIUM : Enums.KycRiskRating.LOW;
        m.setKycRiskRating(rating);
        int cycleMonths = rating == Enums.KycRiskRating.HIGH ? 12 : rating == Enums.KycRiskRating.MEDIUM ? 24 : 36;
        m.setKycNextReviewDate(java.time.LocalDate.now().plusMonths(cycleMonths));
        auditService.record(approverId, approverRole, "kyc_risk_rated", "merchant", m.getId(),
                null, rating.name(), "review cycle " + cycleMonths + "m (KYC spec §5)", false, null);
    }

    /** Balance account + issuer mapping + QR + short code (E04/E05, M07, M43). */
    @Transactional
    /** Supplier receiving account: credit side of the three-ledger rule (BC p.9, TC-B2B-05). */
    public void provisionSupplier(Supplier s) {
        BalanceAccount account = accountRepo.findByOwnerTypeAndOwnerId(Enums.OwnerType.SUPPLIER, s.getId())
                .orElseGet(BalanceAccount::new);
        account.setOwnerType(Enums.OwnerType.SUPPLIER);
        account.setOwnerId(s.getId());
        account.setCurrency("NAD");
        account.setStatus(Enums.AccountStatus.ACTIVE);
        account.setIssuerAccountRef("NDB-S-" + randomDigits(6));
        account.setLegalHolder("issuer (bank holds float)");
        accountRepo.save(account);
    }

    public void provision(Merchant m) {
        BalanceAccount account = accountRepo.findByOwnerTypeAndOwnerId(Enums.OwnerType.MERCHANT, m.getId())
                .orElseGet(BalanceAccount::new);
        account.setOwnerType(Enums.OwnerType.MERCHANT);
        account.setOwnerId(m.getId());
        account.setCurrency("NAD");
        account.setStatus(Enums.AccountStatus.ACTIVE);
        account.setIssuerAccountRef("NDB-M-" + randomDigits(6));
        account.setLegalHolder("issuer (bank holds float)");
        accountRepo.save(account);

        Connector bank = connectorRepo.findFirstByConnectorTypeAndStatusOrderByCreatedAtDesc(
                Enums.ConnectorType.BANK_ISSUER, Enums.ConnectorStatus.ACTIVE).orElse(null);
        AccountMapping mapping = new AccountMapping();
        mapping.setMerchantId(m.getId());
        mapping.setBalanceAccountId(account.getId());
        mapping.setConnectorId(bank == null ? null : bank.getId());
        mapping.setIssuerAccountRef(account.getIssuerAccountRef());
        mapping.setLegalHolder(account.getLegalHolder());
        mappingRepo.save(mapping);

        QrCode qr = new QrCode();
        qr.setMerchantId(m.getId());
        qr.setQrPayload("mipay://qr/" + m.getId());
        qr.setStatus(Enums.QrStatus.ACTIVE);
        qrRepo.save(qr);

        ShortCode code = new ShortCode();
        code.setMerchantId(m.getId());
        code.setShortCode("MP" + randomDigits(6));
        while (shortCodeRepo.existsByShortCode(code.getShortCode())) {
            code.setShortCode("MP" + randomDigits(6));
        }
        code.setStatus(Enums.QrStatus.ACTIVE);
        shortCodeRepo.save(code);
    }

    @Transactional
    public Dtos.ApprovalResult approveSupplier(String supplierId, boolean approve, String actorId, String actorRole) {
        // Pessimistic lock serialises concurrent approvals; a second approver after the
        // first is short-circuited - the receiving account is provisioned exactly once
        // (TC-ONB-02, TC-RACE-13).
        Supplier s = supplierRepo.findWithLockById(supplierId)
                .orElseThrow(() -> new NotFoundException("Supplier not found"));
        if (approve && s.getApprovalStatus() == Enums.ApprovalStatus.APPROVED) {
            return new Dtos.ApprovalResult(s.getId(), "APPROVED",
                    "Supplier already approved - duplicate approval ignored");
        }
        if (approve) {
            s.setApprovalStatus(Enums.ApprovalStatus.APPROVED);
            supplierRepo.save(s);
            provisionSupplier(s);
            complianceService.recordCheck("supplier", s.getId(), Enums.KycCheckType.IDENTITY,
                    Enums.KycResult.PASS, "simulated KYC provider");
            // risk rating + review cycle (KYC spec §5): suppliers are MEDIUM by default (bank risk)
            s.setKycRiskRating(Enums.KycRiskRating.MEDIUM);
            s.setKycNextReviewDate(java.time.LocalDate.now().plusMonths(24));
            supplierRepo.save(s);
            auditService.record(actorId, actorRole, "kyc_risk_rated", "supplier", s.getId(),
                    null, "MEDIUM", "review cycle 24m (KYC spec §5)", false, null);
            auditService.record(actorId, actorRole, "supplier_approved", "supplier", s.getId(),
                    "PENDING", "APPROVED", null, false, null);
            return new Dtos.ApprovalResult(s.getId(), "APPROVED",
                    "Supplier approved; bank account approval handled separately (UAT SB p.8)");
        }
        s.setApprovalStatus(Enums.ApprovalStatus.SUSPENDED);
        supplierRepo.save(s);
        auditService.record(actorId, actorRole, "supplier_rejected", "supplier", s.getId(),
                "PENDING", "REJECTED", null, false, null);
        return new Dtos.ApprovalResult(s.getId(), "REJECTED", "Supplier registration rejected");
    }

    /** Second-person rule: approver must differ from requester (UAT SB p.8, TC-ONB-03). */
    @Transactional
    public Dtos.ApprovalResult approveBankAccount(String accountId, boolean approve,
                                                  String approverId, String approverRole) {
        SupplierBankAccount b = bankAccountRepo.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Bank account request not found"));
        if (approve) {
            if (b.getRequestedBy() != null && b.getRequestedBy().equals(approverId)) {
                throw new ConflictException("INDEPENDENCE_RULE",
                        "Same user cannot request and approve a receiving account (second-person rule)");
            }
            bankAccountRepo.findBySupplierIdAndStatus(b.getSupplier().getId(), Enums.BankAccountStatus.APPROVED)
                    .ifPresent(current -> {
                        current.setStatus(Enums.BankAccountStatus.REVOKED);
                        bankAccountRepo.save(current);
                    });
            b.setStatus(Enums.BankAccountStatus.APPROVED);
            b.setApprovedBy(approverId);
            b.setApprovedAt(Instant.now());
            bankAccountRepo.save(b);
            // trigger-event re-screening (KYC spec §4): a receiving-account change re-opens sanctions screening
            complianceService.recordCheck("supplier", b.getSupplier().getId(), Enums.KycCheckType.SANCTIONS,
                    Enums.KycResult.PASS, "bank-change re-screening (trigger event, SIMULATED)");
            auditService.record(approverId, approverRole, "bank_account_approved", "supplier_bank_account", b.getId(),
                    "PENDING_APPROVAL", "APPROVED", "independent second-person approval", false, null);
            auditService.record(approverId, approverRole, "kyc_rescreened", "supplier", b.getSupplier().getId(),
                    null, "SANCTIONS/PASS", "trigger event: receiving-account change (KYC spec §4)", false, null);
            return new Dtos.ApprovalResult(b.getId(), "APPROVED", "Receiving account approved and activated");
        }
        b.setStatus(Enums.BankAccountStatus.REJECTED);
        b.setApprovedBy(approverId);
        b.setApprovedAt(Instant.now());
        bankAccountRepo.save(b);
        auditService.record(approverId, approverRole, "bank_account_rejected", "supplier_bank_account", b.getId(),
                "PENDING_APPROVAL", "REJECTED", null, false, null);
        return new Dtos.ApprovalResult(b.getId(), "REJECTED", "Bank account request rejected");
    }

    @Transactional
    public Dtos.ApprovalResult changeSupplierBankAccount(String supplierId, Dtos.BankAccountChangeRequest req,
                                                         String requesterId) {
        Supplier s = supplierRepo.findById(supplierId).orElseThrow(() -> new NotFoundException("Supplier not found"));
        SupplierBankAccount account = new SupplierBankAccount();
        account.setSupplier(s);
        account.setBankName(req.bankName());
        account.setAccountNumber(req.accountNumber());
        account.setAccountHolder(req.accountHolder());
        account.setStatus(Enums.BankAccountStatus.PENDING_APPROVAL);
        account.setRequestedBy(requesterId);
        account.setRequestNote(req.note() == null ? "receiving account change" : req.note());
        bankAccountRepo.save(account);
        auditService.record(requesterId, "SUPPLIER", "bank_account_change_requested", "supplier_bank_account",
                account.getId(), null, "PENDING_APPROVAL", "independent approval required", false, null);
        return new Dtos.ApprovalResult(account.getId(), "PENDING_APPROVAL",
                "Change request saved - pending independent TPTS approval (UAT SB p.8)");
    }

    @Transactional(readOnly = true)
    public List<Dtos.SupplierApprovalRow> supplierQueue() {
        return supplierRepoQueue(Enums.ApprovalStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public List<Dtos.SupplierApprovalRow> suppliersAllRows() {
        return supplierRepoQueue(null);
    }

    private List<Dtos.SupplierApprovalRow> supplierRepoQueue(com.mipay.sme.domain.Enums.ApprovalStatus status) {
        return status == null ? supplierRepo.findAllRows() : supplierRepo.findQueue(status);
    }

    @Transactional(readOnly = true)
    public List<Dtos.BankApprovalRow> bankQueue() {
        // one join query - supplier names inlined, no per-row supplier fetch (no N+1)
        return bankAccountRepo.findQueue(Enums.BankAccountStatus.PENDING_APPROVAL);
    }

    private String randomDigits(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(random.nextInt(10));
        return sb.toString();
    }
}
