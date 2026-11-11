package com.mipay.sme.service;

import com.mipay.sme.common.NotFoundException;
import com.mipay.sme.common.SimException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.Enums.OrgType;
import com.mipay.sme.domain.ledger.BalanceAccount;
import com.mipay.sme.domain.ledger.Withdrawal;
import com.mipay.sme.repository.BalanceAccountRepository;
import com.mipay.sme.repository.LimitProfileRepository;
import com.mipay.sme.repository.WithdrawalRepository;
import com.mipay.sme.web.Dtos;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Merchant withdrawals (M05, BC p.18):
 * blocked while reconciliation has open breaks; subject to the withdrawal cap;
 * executed as a bank debit with a reference; idempotent per attempt key.
 */
@Service
@RequiredArgsConstructor
public class WithdrawalService {

    private final WithdrawalRepository withdrawalRepo;
    private final com.mipay.sme.service.OrgAccess orgAccess;
    private final BalanceAccountRepository accountRepo;
    private final LimitProfileRepository limitProfileRepo;
    private final LedgerService ledgerService;
    private final ReconciliationService reconciliationService;
    private final AuditService auditService;
    private final SecureRandom random = new SecureRandom();

    @Transactional
    public Dtos.WithdrawalRow withdraw(String merchantId, BigDecimal amount, String actorId, String actorRole) {
        reconciliationService.assertNoOpenBreaks();   // unexplained differences block release (BC p.18)
        orgAccess.requireOperationalForEntity(Enums.OrgType.MERCHANT, merchantId);   // [H1]: money movement [plan]

        // Pessimistic row lock serialises concurrent withdrawals so the
        // availability check + debit are atomic (TC-RACE-04: no overdraft, BC p.9).
        BalanceAccount account = accountRepo.lockByOwner(Enums.OwnerType.MERCHANT, merchantId)
                .orElseThrow(() -> new NotFoundException("No balance account for merchant"));
        BigDecimal available = ledgerService.availableFor(merchantId);
        if (available.compareTo(amount) < 0) {
            throw new SimException("INSUFFICIENT_BALANCE", "Withdrawal exceeds available balance");
        }

        BigDecimal cap = withdrawCapFor(merchantId);
        if (cap != null) {
            Instant dayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
            BigDecimal today = withdrawalRepo.sumToday(merchantId, List.of(Enums.WithdrawalStatus.REQUESTED,
                    Enums.WithdrawalStatus.APPROVED, Enums.WithdrawalStatus.PAID), dayStart);
            if (today.add(amount).compareTo(cap) > 0) {
                throw new SimException("WITHDRAWAL_CAP", "Daily withdrawal cap reached (" + cap + ")");
            }
        }

        Withdrawal w = new Withdrawal();
        w.setMerchantId(merchantId);
        w.setAmount(amount);
        w.setStatus(Enums.WithdrawalStatus.REQUESTED);
        withdrawalRepo.save(w);

        // bank executes immediately in the simulation; reference recorded for the ledger
        String bankReference = "WDL-" + String.format("%06d", random.nextInt(899999) + 100000);
        ledgerService.postConfirmed(account, Enums.LedgerEntryType.DEBIT, amount,
                Enums.SubledgerSource.WITHDRAWAL, w.getId(), bankReference, "wdl-" + w.getId());
        w.setStatus(Enums.WithdrawalStatus.PAID);
        w.setBankReference(bankReference);
        w.setProcessedAt(Instant.now());
        withdrawalRepo.save(w);

        auditService.sim(actorId, actorRole, "withdrawal_paid", "withdrawal", w.getId(),
                "N$" + amount + " via " + bankReference, null);
        return row(w);
    }

    @Transactional(readOnly = true)
    public List<Dtos.WithdrawalRow> list(String merchantId) {
        Page<Withdrawal> rows = withdrawalRepo.findByMerchantIdOrderByRequestedAtDesc(merchantId, PageRequest.of(0, 50));
        return rows.getContent().stream().map(this::row).toList();
    }

    private BigDecimal withdrawCapFor(String merchantId) {
        return limitProfileRepo.findByMerchantId(merchantId)
                .map(com.mipay.sme.domain.ledger.LimitProfile::getWithdrawalCap)
                .orElseGet(() -> limitProfileRepo.findByMerchantId(null)
                        .flatMap(p -> java.util.Optional.ofNullable(p.getWithdrawalCap()))
                        .orElse(null));
    }

    private Dtos.WithdrawalRow row(Withdrawal w) {
        return new Dtos.WithdrawalRow(w.getId(), w.getAmount(), w.getStatus().name(), w.getRequestedAt(),
                w.getProcessedAt());
    }
}
