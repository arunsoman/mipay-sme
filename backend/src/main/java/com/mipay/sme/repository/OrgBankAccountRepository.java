package com.mipay.sme.repository;

import com.mipay.sme.domain.Enums.BankAccountStatus;
import com.mipay.sme.domain.org.OrgBankAccount;
import com.mipay.sme.web.Dtos;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrgBankAccountRepository extends JpaRepository<OrgBankAccount, String> {

    List<OrgBankAccount> findByOrgIdOrderByCreatedAtDesc(String orgId);

    Optional<OrgBankAccount> findByOrgIdAndStatus(String orgId, BankAccountStatus status);

    /** Approval queue with org names joined in one query (T03). */
    @Query("""
            select new com.mipay.sme.web.Dtos$BankApprovalRow(b.id,
                    b.org.name, b.bankName, b.accountNumber, b.accountHolder, b.status,
                    b.requestedBy, b.createdAt)
            from OrgBankAccount b
            where b.status = :status
            order by b.createdAt asc
            """)
    List<Dtos.BankApprovalRow> findQueue(@Param("status") BankAccountStatus status);
}
