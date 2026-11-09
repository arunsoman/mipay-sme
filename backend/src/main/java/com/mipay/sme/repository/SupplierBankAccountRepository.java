package com.mipay.sme.repository;

import com.mipay.sme.domain.Enums.BankAccountStatus;
import com.mipay.sme.domain.core.SupplierBankAccount;
import com.mipay.sme.web.Dtos;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SupplierBankAccountRepository extends JpaRepository<SupplierBankAccount, String> {

    List<SupplierBankAccount> findBySupplierIdOrderByCreatedAtDesc(String supplierId);

    Optional<SupplierBankAccount> findBySupplierIdAndStatus(String supplierId, BankAccountStatus status);

    List<SupplierBankAccount> findBySupplierIdAndStatusIn(String supplierId, List<BankAccountStatus> statuses);

    /** Approval queue with supplier names joined in one query (T03). */
    @Query("""
            select new com.mipay.sme.web.Dtos$BankApprovalRow(b.id,
                    b.supplier.businessName, b.bankName, b.accountNumber, b.accountHolder, b.status,
                    b.requestedBy, b.createdAt)
            from SupplierBankAccount b
            where b.status = :status
            order by b.createdAt asc
            """)
    List<Dtos.BankApprovalRow> findQueue(@Param("status") BankAccountStatus status);

    List<SupplierBankAccount> findAllByOrderByCreatedAtDesc();
}
