package com.mipay.sme.web;

import com.mipay.sme.common.NotFoundException;
import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.Enums.OrgType;
import com.mipay.sme.domain.core.Supplier;
import com.mipay.sme.domain.org.OrgBankAccount;
import com.mipay.sme.repository.OrderRepository;
import com.mipay.sme.repository.OrgBankAccountRepository;
import com.mipay.sme.repository.OrganizationRepository;
import com.mipay.sme.repository.SupplierCatalogueItemRepository;
import com.mipay.sme.repository.SupplierRepository;
import com.mipay.sme.repository.InvoiceRepository;
import com.mipay.sme.repository.InvoicePaymentRepository;
import com.mipay.sme.security.Current;
import com.mipay.sme.service.B2BPaymentService;
import com.mipay.sme.service.StatementService;
import com.mipay.sme.web.Dtos;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mipay.sme.service.AuditService;

/** Supplier / wholesaler portal API (S01-S08, BC p.9). */
@RestController
@RequestMapping("/api/supplier")
@RequiredArgsConstructor
public class SupplierController {

    private final SupplierRepository supplierRepo;
    private final OrgBankAccountRepository bankAccountRepo;
    private final OrganizationRepository organizationRepo;
    private final com.mipay.sme.service.OrgProvisioningService orgProvisioning;
    private final com.mipay.sme.service.OrgAccess orgAccess;
    private final SupplierCatalogueItemRepository catalogueRepo;
    private final OrderRepository orderRepo;
    private final InvoiceRepository invoiceRepo;
    private final InvoicePaymentRepository invoicePaymentRepo;
    private final B2BPaymentService b2bService;
    private final StatementService statementService;
    private final AuditService auditService;
    private final com.mipay.sme.service.OnboardingService onboardingService;

    private String sid() {
        return Current.supplierId();
    }

    /** Org of the caller's supplier (bank accounts are org-scoped since V4). */
    private String ownOrgId() {
        Supplier s = supplierRepo.findById(sid()).orElseThrow(() -> new NotFoundException("Supplier not found"));
        return orgProvisioning.ensureEntityOrg(Enums.OrgType.SUPPLIER, s.getId(),
                s.getBusinessName(), s.getUserId()).getId();
    }

    @GetMapping("/profile")
    public Dtos.SupplierProfile profile() {
        Supplier s = supplierRepo.findById(sid()).orElseThrow(() -> new NotFoundException("Supplier not found"));
        return new Dtos.SupplierProfile(s.getId(), s.getBusinessName(), s.getOwnerPhone(), s.getApprovalStatus());
    }

    @GetMapping("/bank-accounts")
    public List<Dtos.BankAccountRow> bankAccounts() {
        return bankAccountRepo.findByOrgIdOrderByCreatedAtDesc(ownOrgId()).stream()
                .map(b -> new Dtos.BankAccountRow(b.getId(), b.getBankName(), b.getAccountNumber(),
                        b.getAccountHolder(), b.getStatus().name(), b.getCreatedAt()))
                .toList();
    }

    /** Change receiving account -> PENDING_APPROVAL, independent approval required (S03, TC-ONB-03). */
    @PostMapping("/bank-accounts/change")
    public Dtos.ApprovalResult changeBankAccount(@Valid @RequestBody Dtos.BankAccountChangeRequest req) {
        return onboardingService.changeOrgBankAccount(ownOrgId(), req, Current.get().userId());
    }

    @PostMapping("/bank-accounts/{id}/revoke")
    public void revokeBankAccount(@PathVariable String id) {
        OrgBankAccount b = bankAccountRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("Not found"));
        if (!b.getOrg().getId().equals(ownOrgId())) {
            throw new NotFoundException("Not found");
        }
        b.setStatus(Enums.BankAccountStatus.REVOKED);
        bankAccountRepo.save(b);
        auditService.record(Current.get().userId(), "SUPPLIER", "bank_account_revoked",
                "org_bank_account", b.getId(), b.getStatus().name(), "REVOKED", null, false, null);
    }

    @GetMapping("/catalogue")
    public List<Dtos.CatalogueRow> catalogue() {
        String id = sid();
        return catalogueRepo.findBySupplierIdOrderByProductName(id).stream()
                .map(c -> new Dtos.CatalogueRow(c.getId(), id, name(id), c.getProductName(), c.getUnit(),
                        c.getUnitPrice(), c.getStatus()))
                .toList();
    }

    /** Add catalogue item (S04); goes live when the supplier is approved. */
    @PostMapping("/catalogue")
    public Dtos.CatalogueRow addToCatalogue(@Valid @RequestBody Dtos.CatalogueUpsert req) {
        Supplier supplier = supplierRepo.findById(sid()).orElseThrow(() -> new NotFoundException("Supplier not found"));
        orgAccess.requireOperationalForEntity(Enums.OrgType.SUPPLIER, supplier.getId());   // [H1] write action
        var item = new com.mipay.sme.domain.core.SupplierCatalogueItem();
        item.setSupplier(supplier);
        item.setProductName(req.productName());
        item.setUnit(req.unit());
        item.setUnitPrice(req.unitPrice());
        item.setStatus(supplier.getApprovalStatus() == Enums.ApprovalStatus.APPROVED
                ? Enums.ItemStatus.APPROVED : Enums.ItemStatus.PENDING_APPROVAL);
        catalogueRepo.save(item);
        return new Dtos.CatalogueRow(item.getId(), supplier.getId(), supplier.getBusinessName(),
                item.getProductName(), item.getUnit(), item.getUnitPrice(), item.getStatus());
    }

    @PostMapping("/catalogue/{id}/retire")
    public void retireItem(@PathVariable String id) {
        var item = catalogueRepo.findById(id).orElseThrow(() -> new NotFoundException("Item not found"));
        if (!item.getSupplier().getId().equals(sid())) {
            throw new NotFoundException("Item not found");
        }
        item.setStatus(Enums.ItemStatus.RETIRED);
        catalogueRepo.save(item);
    }

    @GetMapping("/orders")
    public List<Dtos.OrderRow> orders() {
        return orderRepo.findForSupplier(sid());
    }

    @GetMapping("/orders/{id}")
    public Dtos.OrderDetailRow orderDetail(@PathVariable String id) {
        return b2bService.orderDetail(id);
    }

    /** S06: issue invoice with the unique reference (TC-B2B-01). */
    @PostMapping("/orders/{id}/invoice")
    public Dtos.InvoiceDetailRow issueInvoice(@PathVariable String id) {
        return b2bService.issueInvoice(sid(), id);
    }

    @GetMapping("/invoices")
    public List<Dtos.InvoiceListRow> invoices() {
        return invoiceRepo.findForSupplier(sid(), java.util.List.of(Enums.InvoiceStatus.values()));
    }

    @GetMapping("/invoices/{id}")
    public Dtos.InvoiceDetailRow invoiceDetail(@PathVariable String id) {
        var invoice = invoiceRepo.findById(id).orElseThrow(() -> new NotFoundException("Invoice not found"));
        if (!invoice.getSupplier().getId().equals(sid())) {
            throw new NotFoundException("Invoice not found");
        }
        return b2bService.invoiceDetail(id);
    }

    /** Payments received (S07): supplier credits, batched from all invoices (no N+1). */
    @GetMapping("/payments")
    public List<Dtos.InvoicePaymentRow> paymentsReceived() {
        return invoicePaymentRepo.findBySupplierIdOrderByCreatedAtDesc(sid(), PageRequest.of(0, 50)).stream()
                .map(ip -> new Dtos.InvoicePaymentRow(ip.getId(), ip.getAmount(), ip.getRoute().name(),
                        ip.getStatus().name(), ip.getBankReference(), ip.getCreatedAt(), ip.getConfirmedAt()))
                .toList();
    }

    @GetMapping("/statements")
    public List<Dtos.StatementRow> statements() {
        return statementService.list("supplier", sid());
    }

    @PostMapping("/statements")
    public Dtos.StatementRow generateStatement(@Valid @RequestBody Dtos.GenerateStatementRequest req) {
        return statementService.generate("supplier", sid(), req.from(), req.to(), Current.get().userId());
    }

    @GetMapping(value = "/statements/{id}/download", produces = MediaType.TEXT_PLAIN_VALUE)
    public String download(@PathVariable String id) {
        var s = statementService.statementFile(id, "supplier", sid());
        return statementService.downloadText("supplier", sid(), s.getPeriodStart(), s.getPeriodEnd());
    }

    private String name(String supplierId) {
        return supplierRepo.findById(supplierId).map(Supplier::getBusinessName).orElse(null);
    }
}
