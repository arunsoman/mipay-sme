package com.mipay.sme.repository;

import com.mipay.sme.domain.b2b.Order;
import com.mipay.sme.web.Dtos;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, String> {

    /** Pessimistic row lock - serialises concurrent invoice issuance per order (TC-RACE-07). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findWithLockById(@Param("id") String id);

    /** Supplier's incoming orders with merchant name joined in one query. */
    @Query("""
            select new com.mipay.sme.web.Dtos$OrderRow(
                o.id, o.merchant.businessName, o.supplier.businessName, o.status, o.createdAt)
            from Order o
            where o.supplier.id = :supplierId
            order by o.createdAt desc
            """)
    List<Dtos.OrderRow> findForSupplier(@Param("supplierId") String supplierId);

    @Query("""
            select new com.mipay.sme.web.Dtos$OrderRow(
                o.id, o.merchant.businessName, o.supplier.businessName, o.status, o.createdAt)
            from Order o
            where o.merchant.id = :merchantId
            order by o.createdAt desc
            """)
    List<Dtos.OrderRow> findForMerchant(@Param("merchantId") String merchantId);
}
