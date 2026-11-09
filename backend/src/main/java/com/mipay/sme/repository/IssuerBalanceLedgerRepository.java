package com.mipay.sme.repository;

import com.mipay.sme.domain.Enums.LedgerEntryType;
import com.mipay.sme.domain.ledger.IssuerBalanceLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface IssuerBalanceLedgerRepository extends JpaRepository<IssuerBalanceLedgerEntry, String> {

    /** Idempotency: posting with the same key returns the original entry, never a second effect. */
    Optional<IssuerBalanceLedgerEntry> findByIdempotencyKey(String idempotencyKey);

    boolean existsByIdempotencyKey(String idempotencyKey);

    /** Net position per owner across every account - group-by instead of N+1. */
    @Query("""
            select e.ownerId, sum(case when e.entryType = :credit then e.amount else -e.amount end)
            from IssuerBalanceLedgerEntry e
            group by e.ownerId
            """)
    List<Object[]> netByOwnerGroup(@Param("credit") LedgerEntryType credit);

    List<IssuerBalanceLedgerEntry> findByIssuerReferenceStartingWith(String prefix);

    List<IssuerBalanceLedgerEntry> findByIssuerReference(String issuerReference);

    List<IssuerBalanceLedgerEntry> findByOwnerIdOrderByIdAsc(String ownerId);
}
