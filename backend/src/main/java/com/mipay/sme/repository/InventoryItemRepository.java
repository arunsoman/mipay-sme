package com.mipay.sme.repository;

import com.mipay.sme.domain.Enums.InventoryItemStatus;
import com.mipay.sme.domain.ops.InventoryItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, String> {

    List<InventoryItem> findByMerchantIdAndStatus(String merchantId, InventoryItemStatus status);

    List<InventoryItem> findByMerchantId(String merchantId);

    InventoryItem findByMerchantIdAndName(String merchantId, String name);
}
