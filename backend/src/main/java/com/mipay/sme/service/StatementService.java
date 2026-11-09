package com.mipay.sme.service;

import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.ops.Statement;
import com.mipay.sme.repository.MerchantSubledgerRepository;
import com.mipay.sme.repository.StatementRepository;
import com.mipay.sme.web.Dtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Merchant / supplier statements (M26, T14, BC p.18). */
@Service
@RequiredArgsConstructor
public class StatementService {

    private final StatementRepository statementRepo;
    private final MerchantSubledgerRepository subRepo;
    private final LedgerService ledgerService;
    private final AuditService auditService;

    @Transactional
    public Dtos.StatementRow generate(String ownerType, String ownerId, LocalDate from, LocalDate to, String actorId) {
        Statement s = new Statement();
        s.setOwnerType(ownerType);
        s.setOwnerId(ownerId);
        s.setPeriodStart(from);
        s.setPeriodEnd(to);
        s.setFileRef("generated://statement/" + UUID.randomUUID());
        s.setGeneratedBy(actorId);
        statementRepo.save(s);
        auditService.record(actorId, ownerType.toUpperCase(), "statement_generated", "statement", s.getId(),
                null, null, from + ".." + to, false, null);
        return row(s);
    }

    /** Deterministic regeneration from the ledger keeps downloads consistent without file storage. */
    @Transactional(readOnly = true)
    public String downloadText(String ownerType, String ownerId, LocalDate from, LocalDate to) {
        Instant fromTs = from.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant toTs = to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        List<com.mipay.sme.domain.ledger.MerchantSubledgerEntry> rows =
                subRepo.findAllByOwnerIdAndCreatedAtBetweenOrderByCreatedAtDesc(ownerId, fromTs, toTs);
        StringBuilder sb = new StringBuilder();
        sb.append("MiPay statement for ").append(ownerType).append(" ").append(ownerId).append('\n');
        sb.append("Period: ").append(from).append(" to ").append(to).append('\n');
        sb.append("type,time,amount,status,source\n");
        for (com.mipay.sme.domain.ledger.MerchantSubledgerEntry r : rows) {
            sb.append(r.getEntryType()).append(',')
                    .append(r.getCreatedAt()).append(',')
                    .append(r.getAmount()).append(',')
                    .append(r.getStatus()).append(',')
                    .append(r.getSource()).append('\n');
        }
        BigDecimal net = rows.stream()
                .map(r -> r.getEntryType() == Enums.LedgerEntryType.CREDIT ? r.getAmount() : r.getAmount().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        sb.append("Net total: ").append(net).append('\n');
        return sb.toString();
    }

    @Transactional(readOnly = true)
    public Statement statementFile(String statementId, String ownerType, String ownerId) {
        Statement s = statementRepo.findById(statementId)
                .orElseThrow(() -> new com.mipay.sme.common.NotFoundException("Statement not found"));
        if (!s.getOwnerType().equals(ownerType) || !s.getOwnerId().equals(ownerId)) {
            throw new com.mipay.sme.common.NotFoundException("Statement not found");
        }
        return s;
    }

    @Transactional(readOnly = true)
    public List<Dtos.StatementRow> list(String ownerType, String ownerId) {
        return statementRepo.findByOwnerTypeAndOwnerIdOrderByGeneratedAtDesc(ownerType, ownerId).stream()
                .map(this::row)
                .toList();
    }

    private Dtos.StatementRow row(Statement s) {
        return new Dtos.StatementRow(s.getId(), s.getOwnerType(), s.getOwnerId(), s.getPeriodStart(),
                s.getPeriodEnd(), s.getFileRef(), s.getGeneratedAt());
    }
}
