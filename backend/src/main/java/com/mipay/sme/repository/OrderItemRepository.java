package com.mipay.sme.repository;

import com.mipay.sme.domain.b2b.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface OrderItemRepository extends JpaRepository<OrderItem, String> {

    /** Batch fetch for order lists and invoice totals - avoids N+1. */
    List<OrderItem> findByOrderIdIn(Collection<String> orderIds);

    List<OrderItem> findByOrderId(String orderId);
}
