package com.mipay.sme.service;

import com.mipay.sme.common.NotFoundException;
import com.mipay.sme.common.SimException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.ledger.BalanceAccount;
import com.mipay.sme.domain.ledger.BalanceHold;
import com.mipay.sme.domain.ledger.IssuerBalanceLedgerEntry;
import com.mipay.sme.domain.ledger.MerchantSubledgerEntry;
import com.mipay.sme.repository.AdvanceRepository;
import com.mipay.sme.web.Dtos;
import com.mipay.sme.repository.AdvanceRepaymentRepository;
import com.mipay.sme.repository.BalanceAccountRepository;
import com.mipay.sme.repository.BalanceHoldRepository;
import com.mipay.sme.repository.IssuerBalanceLedgerRepository;
import com.mipay.sme.repository.MerchantSubledgerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Core money movement. Rules enforced here:
 * - issuer ledger is authoritative; the merchant subledger is the mirrored view (BC p.12);
 * - idempotency keys mean duplicate callbacks never move money twice (BC p.18, TC-ACC-04);
 * - pending is never spendable (TC-BAL-02); holds reduce available (TC-BAL-03).
 */
@Service
@RequiredArgsConstructor
public class LedgerService {

    private final IssuerBalanceLedgerRepository issuerRepo;
    private final MerchantSubledgerRepository subRepo;
    private final BalanceAccountRepository accountRepo;
    private final BalanceHoldRepository holdRepo;
    private final AdvanceRepository advanceRepo;
    private final AdvanceRepaymentRepository repayRepo;

    public BalanceAccount account(Enums.OwnerType ownerType, String ownerId) {
        return accountRepo.findByOwnerTypeAndOwnerId(ownerType, ownerId)
                .orElseThrow(() -> new NotFoundException("No balance account for " + ownerType + " " + ownerId));
    }

    /**
     * Post a confirmed issuer entry plus the mirrored subledger entry under one
     * idempotency key. Re-posting the same key returns the original entry and
     * moves no money (idempotent replay).
     */
    @Transactional
    public IssuerBalanceLedgerEntry postConfirmed(BalanceAccount account,
                                                  Enums.LedgerEntryType entryType,
                                                  BigDecimal amount,
                                                  Enums.SubledgerSource source,
                                                  String sourceRefId,
                                                  String issuerReference,
                                                  String idempotencyKey) {
        Optional<IssuerBalanceLedgerEntry> existing = issuerRepo.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        IssuerBalanceLedgerEntry e = new IssuerBalanceLedgerEntry();
        e.setBalanceAccountId(account.getId());
        e.setOwnerType(account.getOwnerType());
        e.setOwnerId(account.getOwnerId());
        e.setEntryType(entryType);
        e.setAmount(requirePositive(amount));
        e.setCurrency(account.getCurrency());
        e.setIssuerReference(issuerReference);
        e.setIdempotencyKey(idempotencyKey);
        issuerRepo.save(e);

        MerchantSubledgerEntry s = subledgerFrom(account, entryType, requirePositive(amount), source, sourceRefId,
                Enums.SubledgerStatus.CONFIRMED);
        s.setIssuerLedgerId(e.getId());
        subRepo.save(s);

        return e;
    }

    /** Subledger-only pending entry - card sales before the acquirer releases (UAT SB p.9). */
    @Transactional
    public MerchantSubledgerEntry postPending(BalanceAccount account,
                                              Enums.LedgerEntryType entryType,
                                              BigDecimal amount,
                                              Enums.SubledgerSource source,
                                              String sourceRefId) {
        MerchantSubledgerEntry s = subledgerFrom(account, entryType, requirePositive(amount), source, sourceRefId,
                Enums.SubledgerStatus.PENDING_CONFIRMATION);
        return subRepo.save(s);
    }

    /** Card flow: issuer released -> promote pending subledger rows to confirmed + issuer entry. */
    @Transactional
    public IssuerBalanceLedgerEntry confirmPending(String merchantId, String sourceRefId,
                                                   Enums.SubledgerSource source,
                                                   String issuerReference, String idempotencyKey) {
        List<MerchantSubledgerEntry> rows = subRepo.findBySourceRefIdAndSource(sourceRefId, source).stream()
                .filter(r -> Objects.equals(r.getOwnerId(), merchantId))
                .toList();
        for (MerchantSubledgerEntry row : rows) {
            row.setStatus(Enums.SubledgerStatus.CONFIRMED);
            subRepo.save(row);
        }
        if (rows.isEmpty()) {
            throw new NotFoundException("No pending subledger entries for " + sourceRefId);
        }
        BigDecimal amount = rows.stream().map(MerchantSubledgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BalanceAccount account = account(Enums.OwnerType.MERCHANT, merchantId);
        return postIssuerOnly(account, rows.get(0).getEntryType(), amount, issuerReference, idempotencyKey);
    }

    /** Card flow failure: reverse the pending rows (no money moved). */
    @Transactional
    public void reversePending(String merchantId, String sourceRefId, Enums.SubledgerSource source) {
        subRepo.findBySourceRefIdAndSource(sourceRefId, source).stream()
                .filter(r -> Objects.equals(r.getOwnerId(), merchantId))
                .forEach(r -> {
                    r.setStatus(Enums.SubledgerStatus.REVERSED);
                    subRepo.save(r);
                });
    }

    /** Issuer-only entry, used when the mirrored rows were posted as pending earlier. */
    @Transactional
    public IssuerBalanceLedgerEntry postIssuerOnly(BalanceAccount account, Enums.LedgerEntryType entryType,
                                                   BigDecimal amount, String issuerReference, String idempotencyKey) {
        return issuerRepo.findByIdempotencyKey(idempotencyKey).orElseGet(() -> {
            IssuerBalanceLedgerEntry e = new IssuerBalanceLedgerEntry();
            e.setBalanceAccountId(account.getId());
            e.setOwnerType(account.getOwnerType());
            e.setOwnerId(account.getOwnerId());
            e.setEntryType(entryType);
            e.setAmount(requirePositive(amount));
            e.setCurrency(account.getCurrency());
            e.setIssuerReference(issuerReference);
            e.setIdempotencyKey(idempotencyKey);
            return issuerRepo.save(e);
        });
    }

    private MerchantSubledgerEntry subledgerFrom(BalanceAccount account, Enums.LedgerEntryType entryType,
                                                 BigDecimal amount, Enums.SubledgerSource source, String sourceRefId,
                                                 Enums.SubledgerStatus status) {
        MerchantSubledgerEntry s = new MerchantSubledgerEntry();
        s.setBalanceAccountId(account.getId());
        s.setOwnerType(account.getOwnerType());
        s.setOwnerId(account.getOwnerId());
        s.setEntryType(entryType);
        s.setAmount(amount);
        s.setCurrency(account.getCurrency());
        s.setSource(source);
        s.setSourceRefId(sourceRefId);
        s.setStatus(status);
        return s;
    }

    private BigDecimal requirePositive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new SimException("NON_POSITIVE_AMOUNT", "Amount must be greater than zero");
        }
        return amount;
    }

    // ---------------------------------------------------------------------
    // Balance reads - group-by aggregates and small sums, never N+1
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Map<String, BigDecimal> confirmedNetPerOwner() {
        return group(subRepo.netByStatusGroupOwner(Enums.SubledgerStatus.CONFIRMED, Enums.LedgerEntryType.CREDIT));
    }

    @Transactional(readOnly = true)
    public Map<String, BigDecimal> pendingNetPerOwner() {
        return group(subRepo.netByStatusGroupOwner(Enums.SubledgerStatus.PENDING_CONFIRMATION, Enums.LedgerEntryType.CREDIT));
    }

    @Transactional(readOnly = true)
    public Map<String, BigDecimal> issuerNetPerOwner() {
        Map<String, BigDecimal> out = new java.util.LinkedHashMap<>();
        for (Object[] row : issuerRepo.netByOwnerGroup(Enums.LedgerEntryType.CREDIT)) {
            String ownerId = (String) row[0];
            BigDecimal sum = (BigDecimal) row[1];
            out.put(ownerId, sum == null ? BigDecimal.ZERO : sum);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, BigDecimal> activeHoldsPerMerchant() {
        Map<String, BigDecimal> out = new java.util.LinkedHashMap<>();
        for (Object[] row : holdRepo.holdsSumByMerchant(Enums.HoldStatus.ACTIVE)) {
            String ownerId = (String) row[0];
            BigDecimal sum = (BigDecimal) row[1];
            out.put(ownerId, sum == null ? BigDecimal.ZERO : sum);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Dtos.BalanceView balanceView(String merchantId) {
        BigDecimal confirmedNet = confirmedNetPerOwner().getOrDefault(merchantId, BigDecimal.ZERO);
        BigDecimal pendingNet = pendingNetPerOwner().getOrDefault(merchantId, BigDecimal.ZERO);
        BigDecimal held = activeHoldsPerMerchant().getOrDefault(merchantId, BigDecimal.ZERO);
        BigDecimal owed = owedPerMerchant().getOrDefault(merchantId, BigDecimal.ZERO);
        BigDecimal available = confirmedNet.subtract(held).max(BigDecimal.ZERO);

        BalanceAccount account = account(Enums.OwnerType.MERCHANT, merchantId);
        return new Dtos.BalanceView(available, pendingNet.max(BigDecimal.ZERO), held, owed,
                account.getIssuerAccountRef(), account.getLegalHolder());
    }

    /** Spendable = confirmed credit net minus active holds (no overdraft, no pending, BC p.11). */
    @Transactional(readOnly = true)
    public BigDecimal availableFor(String ownerId) {
        BigDecimal confirmedNet = confirmedNetPerOwner().getOrDefault(ownerId, BigDecimal.ZERO);
        BigDecimal held = activeHoldsPerMerchant().getOrDefault(ownerId, BigDecimal.ZERO);
        return confirmedNet.subtract(held).max(BigDecimal.ZERO);
    }

    @Transactional(readOnly = true)
    public Map<String, BigDecimal> owedPerMerchant() {
        Map<String, BigDecimal> out = new java.util.LinkedHashMap<>();
        for (Object[] row : advanceRepo.owedSumGroupByMerchant(repayingStatuses())) {
            String ownerId = (String) row[0];
            BigDecimal sum = row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1];
            out.merge(ownerId, sum, BigDecimal::add);
        }
        for (Object[] row : repayRepo.repaidSumGroupByMerchant()) {
            String ownerId = (String) row[0];
            BigDecimal sum = row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1];
            out.merge(ownerId, sum.negate(), BigDecimal::add);
        }
        out.replaceAll((k, v) -> v.max(BigDecimal.ZERO));
        return out;
    }

    private List<Enums.AdvanceStatus> repayingStatuses() {
        return List.of(Enums.AdvanceStatus.FUNDED, Enums.AdvanceStatus.REPAYING);
    }

    private Map<String, BigDecimal> group(List<Object[]> rows) {
        Map<String, BigDecimal> out = new java.util.LinkedHashMap<>();
        for (Object[] row : rows) {
            String ownerId = (String) row[0];
            BigDecimal sum = (BigDecimal) row[1];
            out.put(ownerId, sum == null ? BigDecimal.ZERO : sum);
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // Holds
    // ---------------------------------------------------------------------

    @Transactional
    public BalanceHold createHold(String merchantId, BigDecimal amount, String reference, int ttlMinutes) {
        BalanceHold hold = new BalanceHold();
        hold.setMerchantId(merchantId);
        hold.setAmount(requirePositive(amount));
        hold.setReason("SUPPLIER_PAYMENT");
        hold.setReference(reference);
        hold.setStatus(Enums.HoldStatus.ACTIVE);
        hold.setExpiresAt(Instant.now().plusSeconds(ttlMinutes * 60L));
        return holdRepo.save(hold);
    }

    @Transactional
    public void convertHold(BalanceHold hold) {
        hold.setStatus(Enums.HoldStatus.CONVERTED);
        holdRepo.save(hold);
    }

    @Transactional
    public void releaseHold(BalanceHold hold) {
        hold.setStatus(Enums.HoldStatus.RELEASED);
        holdRepo.save(hold);
    }

    @Transactional
    public void expireHold(BalanceHold hold) {
        hold.setStatus(Enums.HoldStatus.EXPIRED);
        holdRepo.save(hold);
    }

    @Transactional(readOnly = true)
    public List<BalanceHold> activeHolds(String merchantId) {
        return holdRepo.findByMerchantIdAndStatus(merchantId, Enums.HoldStatus.ACTIVE);
    }
}
