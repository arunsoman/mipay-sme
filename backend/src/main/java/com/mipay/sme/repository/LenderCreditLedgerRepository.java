package com.mipay.sme.repository;

import com.mipay.sme.domain.Enums.LedgerEntryType;
import com.mipay.sme.domain.credit.LenderCreditLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface LenderCreditLedgerRepository extends JpaRepository<LenderCreditLedgerEntry, String> {

    List<LenderCreditLedgerEntry> findByAdvanceIdOrderByCreatedAtDesc(String advanceId);

    Optional<LenderCreditLedgerEntry> findTopByAdvanceIdOrderByCreatedAtDesc(String advanceId);

    /** Net owed per lender (CREDIT adds owed, DEBIT reduces owed). */
    @Query("select coalesce(sum(case when e.entryType = :credit then e.amount else -e.amount end), 0) from LenderCreditLedgerEntry e where e.lenderId = :lenderId")
    BigDecimal netOwedForLender(@Param("lenderId") String lenderId, @Param("credit") LedgerEntryType credit);

    /** Grand total for the reconciliation third-ledger column. */
    @Query("select coalesce(sum(case when e.entryType = :credit then e.amount else -e.amount end), 0) from LenderCreditLedgerEntry e")
    BigDecimal netOwedTotal(@Param("credit") LedgerEntryType credit);
}
