package com.mipay.sme.repository;

import com.mipay.sme.domain.Enums.OwnerType;
import com.mipay.sme.domain.ledger.BalanceAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import org.springframework.data.repository.query.Param;

public interface BalanceAccountRepository extends JpaRepository<BalanceAccount, String> {

    Optional<BalanceAccount> findByOwnerTypeAndOwnerId(OwnerType ownerType, String ownerId);

    /** Pessimistic row lock for money moves (TC-B2B-08 concurrent orders). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from BalanceAccount a where a.ownerType = :ownerType and a.ownerId = :ownerId")
    Optional<BalanceAccount> lockByOwner(@Param("ownerType") OwnerType ownerType, @Param("ownerId") String ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from BalanceAccount a where a.id = :id")
    Optional<BalanceAccount> lockById(@Param("id") String id);
}
