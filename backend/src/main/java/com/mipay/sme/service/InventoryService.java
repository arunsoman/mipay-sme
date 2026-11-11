package com.mipay.sme.service;

import com.mipay.sme.common.NotFoundException;
import com.mipay.sme.common.SimException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.Enums.OrgType;
import com.mipay.sme.domain.b2b.Order;
import com.mipay.sme.domain.b2b.OrderItem;
import com.mipay.sme.domain.core.Supplier;
import com.mipay.sme.domain.core.SupplierCatalogueItem;
import com.mipay.sme.domain.ops.InventoryItem;
import com.mipay.sme.domain.ops.ReorderRule;
import com.mipay.sme.domain.ops.StockAlert;
import com.mipay.sme.domain.ops.StockMovement;
import com.mipay.sme.repository.InventoryItemRepository;
import com.mipay.sme.repository.OrderItemRepository;
import com.mipay.sme.repository.MerchantRepository;
import com.mipay.sme.repository.OrderRepository;
import com.mipay.sme.repository.ReorderRuleRepository;
import com.mipay.sme.repository.StockAlertRepository;
import com.mipay.sme.repository.StockMovementRepository;
import com.mipay.sme.repository.SupplierCatalogueItemRepository;
import com.mipay.sme.repository.SupplierRepository;
import com.mipay.sme.web.Dtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Merchant inventory + automatic reorder (K05/K06). Stock movements are
 * immutable audit rows; the stock balance is materialised on the item for
 * cheap reads. Low-stock alerts are upserted: raised once, resolved on
 * recovery (TC-OPS-02). Reorder places a SUBMITTED order via the approved
 * preferred supplier's catalogue match (K06).
 */
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryItemRepository itemRepo;
    private final com.mipay.sme.service.OrgAccess orgAccess;
    private final StockMovementRepository movementRepo;
    private final StockAlertRepository alertRepo;
    private final ReorderRuleRepository ruleRepo;
    private final SupplierRepository supplierRepo;
    private final SupplierCatalogueItemRepository catalogueRepo;
    private final OrderRepository orderRepo;
    private final MerchantRepository merchantRepo;
    private final OrderItemRepository orderItemRepo;

    @Transactional
    public Dtos.InventoryRow addItem(String merchantId, String name, String unit, BigDecimal reorderLevel,
                                     BigDecimal openingStock) {
        InventoryItem item = new InventoryItem();
        item.setMerchantId(merchantId);
        item.setName(name);
        item.setUnit(unit);
        item.setReorderLevel(reorderLevel);
        item.setCurrentStock(zeroIfNull(openingStock));
        itemRepo.save(item);
        evaluateAlerts(merchantId);
        return row(item);
    }

    @Transactional
    public void recordSale(String merchantId, String itemId, BigDecimal quantity, String reference) {
        move(merchantId, itemId, quantity.negate(), Enums.StockMovementType.SALE, reference);
    }

    @Transactional
    public void recordDelivery(String merchantId, String productName, BigDecimal quantity, String reference) {
        InventoryItem item = itemRepo.findByMerchantIdAndName(merchantId, productName);
        if (item == null) {
            return; // this catalogue product is not tracked by the merchant
        }
        move(merchantId, item.getId(), quantity, Enums.StockMovementType.DELIVERY, reference);
    }

    @Transactional
    public Dtos.InventoryRow adjust(String merchantId, String itemId, Enums.StockMovementType type,
                                    BigDecimal quantity, String reference) {
        boolean outward = type == Enums.StockMovementType.SALE || type == Enums.StockMovementType.WASTE;
        move(merchantId, itemId, outward ? quantity.negate() : quantity, type, reference);
        InventoryItem item = itemRepo.findById(itemId).orElseThrow(() -> new NotFoundException("Item not found"));
        return row(item);
    }

    private void move(String merchantId, String itemId, BigDecimal signedQty, Enums.StockMovementType type,
                      String reference) {
        InventoryItem item = itemRepo.findById(itemId).orElseThrow(() -> new NotFoundException("Item not found"));
        if (!item.getMerchantId().equals(merchantId)) {
            throw new NotFoundException("Item not found");
        }
        BigDecimal newStock = item.getCurrentStock().add(signedQty);
        item.setCurrentStock(newStock.max(BigDecimal.ZERO));
        itemRepo.save(item);

        StockMovement mv = new StockMovement();
        mv.setItemId(itemId);
        mv.setMovementType(type);
        mv.setQuantity(signedQty.abs());
        mv.setReference(reference);
        movementRepo.save(mv);

        evaluateAlerts(merchantId);
    }

    /** Alert upsert - resolves recovered items, raises low/out alerts (TC-OPS-02). */
    @Transactional
    public void evaluateAlerts(String merchantId) {
        List<InventoryItem> items = itemRepo.findByMerchantIdAndStatus(merchantId, Enums.InventoryItemStatus.ACTIVE);
        List<StockAlert> open = alertRepo.findByMerchantIdAndResolvedFalse(merchantId);
        Map<String, StockAlert> openByItem = open.stream()
                .collect(Collectors.toMap(StockAlert::getItemId, Function.identity()));

        for (InventoryItem item : items) {
            BigDecimal level = item.getReorderLevel();
            if (level == null) {
                continue;
            }
            boolean below = item.getCurrentStock().compareTo(level) <= 0;
            StockAlert existing = openByItem.get(item.getId());
            if (below && existing == null) {
                StockAlert alert = new StockAlert();
                alert.setMerchantId(merchantId);
                alert.setItemId(item.getId());
                alert.setAlertType(item.getCurrentStock().signum() == 0
                        ? Enums.StockAlertType.OUT_OF_STOCK : Enums.StockAlertType.LOW_STOCK);
                alertRepo.save(alert);
            } else if (!below && existing != null) {
                existing.setResolved(true);
                alertRepo.save(existing);
            }
        }
    }

    /** One-tap reorder (K06): creates a SUBMITTED order from the item's reorder rule. */
    @Transactional
    public String reorder(String merchantId, String itemId) {
        ReorderRule rule = ruleRepo.findByItemIdAndMerchantId(itemId, merchantId)
                .orElseThrow(() -> new NotFoundException("No reorder rule for item"));
        Supplier supplier = supplierRepo.findById(rule.getPreferredSupplierId())
                .orElseThrow(() -> new NotFoundException("Preferred supplier not found"));
        if (supplier.getApprovalStatus() != Enums.ApprovalStatus.APPROVED) {
            throw new SimException("SUPPLIER_NOT_APPROVED", "Preferred supplier is not approved");
        }
        orgAccess.requireOperationalForEntity(Enums.OrgType.SUPPLIER, supplier.getId());   // [H1]
        InventoryItem item = itemRepo.findById(itemId).orElseThrow(() -> new NotFoundException("Item not found"));
        List<SupplierCatalogueItem> catalogue = catalogueRepo
                .findBySupplierIdOrderByProductName(supplier.getId());
        SupplierCatalogueItem match = catalogue.stream()
                .filter(c -> c.getProductName().equalsIgnoreCase(item.getName()))
                .findFirst()
                .orElseThrow(() -> new SimException("NO_CATALOGUE_MATCH",
                        "No approved catalogue item matches '" + item.getName() + "'"));

        Order order = new Order();
        order.setMerchant(merchantRepo.getReferenceById(merchantId));
        order.setSupplier(supplier);
        order.setStatus(Enums.OrderStatus.SUBMITTED);
        orderRepo.save(order);

        OrderItem oi = new OrderItem();
        oi.setOrderId(order.getId());
        oi.setCatalogueItemId(match.getId());
        oi.setProductName(match.getProductName());
        oi.setUnit(match.getUnit());
        oi.setQuantity(zeroIfNull(rule.getOrderQuantity(), BigDecimal.ONE));
        oi.setUnitPrice(match.getUnitPrice());
        orderItemRepo.save(oi);
        return order.getId();
    }

    private BigDecimal zeroIfNull(BigDecimal v, BigDecimal fallback) {
        return v == null ? fallback : v;
    }

    private String stateFor(InventoryItem item) {
        if (item.getReorderLevel() == null) {
            return "OK";
        }
        if (item.getCurrentStock().signum() == 0) {
            return "OUT_OF_STOCK";
        }
        return item.getCurrentStock().compareTo(item.getReorderLevel()) <= 0 ? "LOW_STOCK" : "OK";
    }

    private Dtos.InventoryRow row(InventoryItem item) {
        return new Dtos.InventoryRow(item.getId(), item.getName(), item.getUnit(), item.getCurrentStock(),
                item.getReorderLevel(), stateFor(item));
    }

    private static BigDecimal zeroIfNull(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
