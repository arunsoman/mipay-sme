package com.mipay.sme.domain;

/**
 * All platform enumerations in one place. Persisted as @Enumerated(STRING).
 */
public final class Enums {

    private Enums() { }

    /** Max depth a node may reach by org type: PLATFORM ∞, MANUFACTURER 4, DISTRIBUTOR 3, SUPPLIER 2, MERCHANT 1. */
    public static int maxDepthOf(OrgType type) {
        return switch (type) {
            case PLATFORM -> Integer.MAX_VALUE;
            case MANUFACTURER -> 4;
            case DISTRIBUTOR -> 3;
            case SUPPLIER -> 2;
            case MERCHANT -> 1;
            case LENDER -> 0;
        };
    }

    // ---------- identity ----------
    public enum UserRole { MERCHANT, SUPPLIER, LENDER, MANUFACTURER, DISTRIBUTOR, TPTS_OPS, MINDLABS_ADMIN }
    public enum UserStatus { PENDING, ACTIVE, SUSPENDED }

    // ---------- org hierarchy ----------
    public enum OrgType { PLATFORM, MANUFACTURER, DISTRIBUTOR, SUPPLIER, MERCHANT, LENDER }

    /** Org lifecycle. Suspensions are derived from active holds (own / ancestor). */
    public enum OrgStatus { PENDING, ACTIVE, REJECTED, BLOCKED }

    public enum OnboardingKind { ORG_ONBOARDING, ADMIN_RECOVERY }

    public enum OnboardingDecision { APPROVE, REJECT }

    /** Review status of an onboarding request [M4]. */
    public enum OnboardingStatus { PENDING_REVIEW, APPROVED, REJECTED, BLOCKED }

    /** org_rights rows: DIRECT = may onboard itself; CEILING = subtree-wide upper bound. */
    public enum OrgRightKind { DIRECT, CEILING }

    /** Role of a user inside their org: ADMIN may onboard/staff/decide; STAFF is operational. */
    public enum OrgMemberRole { ADMIN, STAFF }
    public enum TenantType { TPTS, MINDLABS, ISSUER, LENDER, SUPPLIER }
    public enum TenantStatus { ACTIVE, SUSPENDED }

    // ---------- onboarding / approval ----------
    public enum KycStatus { PENDING, VERIFIED, REJECTED }
    /** Risk-based review cycle (KYC spec §5): LOW 36m / MEDIUM 24m / HIGH 12m review month. */
    public enum KycRiskRating { LOW, MEDIUM, HIGH }
    public enum ApprovalStatus { PENDING, APPROVED, SUSPENDED, REJECTED }
    public enum BankAccountStatus { PENDING_APPROVAL, APPROVED, REJECTED, REVOKED }
    public enum ItemStatus { PENDING_APPROVAL, APPROVED, RETIRED }

    // ---------- ledgers ----------
    public enum OwnerType { MERCHANT, SUPPLIER }
    public enum LedgerEntryType { CREDIT, DEBIT }
    public enum SubledgerSource { PAYMENT, CARD, ADVANCE, REPAYMENT, WITHDRAWAL, REFUND, FEE, SUPPLIER_PAYMENT, OTHER }
    public enum SubledgerStatus { PENDING_CONFIRMATION, CONFIRMED, REVERSED }
    public enum AccountStatus { ACTIVE, FROZEN, CLOSED }
    public enum HoldStatus { ACTIVE, CONVERTED, RELEASED, EXPIRED }
    public enum WithdrawalStatus { REQUESTED, APPROVED, PAID, FAILED }

    // ---------- acceptance ----------
    public enum PaymentChannel { QR, SHORT_CODE, CARD }
    public enum PaymentStatus { INITIATED, PENDING, CONFIRMED, FAILED, REFUNDED }
    public enum PaymentEventType { INITIATED, CALLBACK, CONFIRMED, TIMEOUT, FAILED, REFUNDED, DUPLICATE_IGNORED }
    public enum RefundStatus { REQUESTED, PROCESSED, FAILED }
    public enum QrStatus { ACTIVE, ROTATED, REVOKED }
    public enum TerminalStatus { ACTIVE, INACTIVE }
    public enum InventoryItemStatus { ACTIVE, RETIRED }

    // ---------- B2B ----------
    public enum InvoiceStatus { ISSUED, PARTIALLY_PAID, PAID, CANCELLED }
    public enum InvoicePaymentRoute { BOOK_TRANSFER, EFT }
    public enum InvoicePaymentStatus { HELD, PROCESSING, CONFIRMED, FAILED, TIMED_OUT }
    public enum OrderStatus { DRAFT, SUBMITTED, INVOICED, FULFILLED, CANCELLED }

    // ---------- credit ----------
    public enum ConsentStatus { ACTIVE, WITHDRAWN }
    public enum LenderStatus { ACTIVE, SUSPENDED }
    public enum AssessmentDecision { APPROVED, DECLINED, MANUAL_REVIEW }
    public enum AdvanceStatus { OFFERED, ACCEPTED, FUNDED, REPAYING, CLOSED, DEFAULTED }

    // ---------- fees ----------
    public enum FeeTariffCode { CARD_SERVICE, ADVANCE_SERVICE, SOFTWARE, WHOLESALE_SUBSCRIPTION }
    public enum RateType { PERCENTAGE, FIXED }
    public enum CostBasis { CONTRACTED, PROVISION, UNQUOTED }
    public enum FeeStatus { APPLIED, REVERSED }

    // ---------- reconciliation ----------
    public enum ReconStatus { MATCHED, BREAKS_FOUND }
    public enum BreakType { MISSING_CREDIT, DUPLICATE_DEBIT, STALE_HOLD, ORPHAN_PAYMENT }
    public enum BreakStatus { OPEN, RESOLVED }

    // ---------- operations ----------
    public enum ComplaintStatus { OPEN, IN_PROGRESS, RESOLVED, CLOSED }
    public enum DisputeStatus { OPEN, WON, LOST }
    public enum SettlementStatus { PENDING, SETTLED }
    public enum IncidentSeverity { CRITICAL, HIGH, MEDIUM, LOW }
    public enum IncidentStatus { OPEN, MITIGATED, RESOLVED }
    public enum SuspensionEntityType { MERCHANT, SUPPLIER, LENDER, ORG }

    // ---------- merchant tools ----------
    public enum StockMovementType { SALE, DELIVERY, ADJUSTMENT, WASTE }
    public enum StockAlertType { LOW_STOCK, OUT_OF_STOCK }
    public enum KhataEntryType { CHARGE, PAYMENT }
    public enum KhataStatus { ACTIVE, SETTLED, WRITTEN_OFF }
    public enum TabStatus { OPEN, PAID, OVERDUE, WRITTEN_OFF }

    // ---------- connectors / environment ----------
    public enum ConnectorType { BANK_ISSUER, LENDER, IPS, CARD, QR, KYC, NOTIFICATION }
    public enum ConnectorStatus { ACTIVE, DISABLED, FAILED }
    public enum PaymentEnvironment { SANDBOX, UAT, PRODUCTION }
    public enum AuthType { OAUTH2, API_KEY, MTLS }

    // ---------- admin ----------
    public enum TemplateChannel { SMS, WHATSAPP, SPEAKER, EMAIL, PUSH }
    public enum TemplateStatus { ACTIVE, DRAFT }
    public enum SimAction { CALLBACK, TIMEOUT, FAILURE, DUPLICATE, REFUND, FUND_ADVANCE }
    public enum DeployStatus { SUCCESS, FAILED, ROLLED_BACK }
    public enum BackupType { FULL, INCREMENTAL }
    public enum BackupStatus { SUCCESS, FAILED }
    public enum ApiClientStatus { ACTIVE, REVOKED }

    // ---------- compliance ----------
    public enum KycCheckType { IDENTITY, SANCTIONS, ADVERSE_MEDIA }
    public enum KycResult { PASS, FAIL, MANUAL_REVIEW }
    public enum AmlStatus { OPEN, INVESTIGATING, CLOSED }
    public enum SarStatus { DRAFT, SUBMITTED, ACKNOWLEDGED }
    public enum DsarStatus { RECEIVED, PROCESSING, COMPLETED }
}
