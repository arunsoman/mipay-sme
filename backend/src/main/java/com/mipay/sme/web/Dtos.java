package com.mipay.sme.web;

import com.mipay.sme.domain.Enums;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Single place for every API response/request DTO (Java records).
 *
 * N+1 avoidance strategy:
 * - List screens use JPQL constructor projections (single query, joins inside).
 * - Aggregates (sums, nets) use group-by queries returning Object[] rows.
 * - Batch fetches use findByXxxIn(Collection) over collected ids.
 * - FK writes use repository.getReferenceById (proxy, no select).
 * - Entities are never serialized to JSON; controllers only return DTOs,
 *   so no lazy relation can leak into a response.
 */
public final class Dtos {

    private Dtos() { }

    // =====================================================================
    // Environment / auth
    // =====================================================================

    public record EnvView(String environment, boolean simulated, String banner) { }

    public record MeView(String userId, String name, String role, String phone,
                         String merchantId, String supplierId, String lenderId,
                         String environment, boolean simulated) { }

    public record OtpRequest(@NotBlank String phone) { }

    public record OtpVerify(@NotBlank String phone, @NotBlank String code) { }

    public record PasswordLogin(@NotBlank String phone, @NotBlank String password) { }

    public record OtpResult(String phone, String hint, boolean simulated) { }

    public record TokenView(String token, String tokenType, long expiresInSeconds, String role) { }

    public record Message(String message, String label) { }

    // =====================================================================
    // Onboarding requests
    // =====================================================================

    public record RegisterMerchantRequest(
            @NotBlank String phone,
            @NotBlank String businessName,
            @NotBlank String ownerName,
            @NotBlank String ownerIdNumber,
            String businessType,
            String registrationNumber) { }

    public record RegisterSupplierRequest(
            @NotBlank String phone,
            @NotBlank String businessName,
            @NotBlank String ownerName,
            String registrationNumber,
            @NotBlank String bankName,
            @NotBlank String accountNumber,
            @NotBlank String accountHolder) { }

    // =====================================================================
    // Onboarding / ops queues
    // =====================================================================

    public record MerchantApprovalRow(String id, String businessName, String ownerName, String ownerPhone,
                                      String businessType, String registrationNumber,
                                      Enums.KycStatus kycStatus, Enums.ApprovalStatus approvalStatus,
                                      Instant createdAt, Enums.KycRiskRating kycRiskRating,
                                      LocalDate kycNextReviewDate) { }

    public record SupplierApprovalRow(String id, String businessName, String ownerName, String ownerPhone,
                                      String registrationNumber, Enums.ApprovalStatus approvalStatus,
                                      Instant createdAt, Enums.KycRiskRating kycRiskRating,
                                      LocalDate kycNextReviewDate) { }

    public record BankApprovalRow(String id, String supplierName, String bankName, String accountNumber,
                                  String accountHolder, Enums.BankAccountStatus status,
                                  String requestedBy, Instant createdAt) { }

    public record ApprovalRequest(@NotNull Boolean approve, String note, String sanctionsResult) { }

    public record ApprovalResult(String id, String status, String message) { }

    public record BankApprovalRequest(@NotNull Boolean approve, String note) { }

    // =====================================================================
    // Balance + dashboard
    // =====================================================================

    public record BalanceView(BigDecimal available, BigDecimal pending, BigDecimal held, BigDecimal owed,
                              String issuerAccountRef, String legalHolder) { }

    public record LedgerRow(String id, String entryType, BigDecimal amount, String currency,
                            String source, String status, String reference, Instant createdAt) { }

    public record RecentRow(String label, BigDecimal amount, Instant at, String status) { }

    public record DashboardView(BigDecimal available, BigDecimal pending, BigDecimal held, BigDecimal owed,
                                BigDecimal salesToday, BigDecimal costsToday, BigDecimal profitToday,
                                List<RecentRow> recent) { }

    public record WithdrawalRow(String id, BigDecimal amount, String status, Instant requestedAt, Instant processedAt) { }

    /** Withdrawal as a closed two-variant outcome: paid in bank simulation, or blocked with machine code. */
    public sealed interface WithdrawalResult permits WithdrawalApproved, WithdrawalBlocked { }

    public record WithdrawalApproved(String id, BigDecimal amount, String bankReference,
                                     String status) implements WithdrawalResult { }

    public record WithdrawalBlocked(String code, String label) implements WithdrawalResult { }

    /** Connector probe: closed pair (ok / failed) (C32). */
    public sealed interface ConnectorOutcome permits ConnectorProbeOk, ConnectorProbeFailed { }

    public record ConnectorProbeOk(String connectorId, Integer latencyMs, Boolean simulated)
            implements ConnectorOutcome { }

    public record ConnectorProbeFailed(String connectorId, String code, String error) implements ConnectorOutcome { }

    public record WithdrawalRequest(@NotNull @Positive BigDecimal amount) { }

    public record PayInfo(String qrPayload, String shortCode, String cardTerminalId) { }

    public record LenderRow(String id, String name, String licenceRef) { }

    public record DeviceRow(String id, String deviceFingerprint, String mfaMethod, Instant expiresAt, Instant createdAt) { }

    // =====================================================================
    // Access & sessions (X04) + bank-simulator controls (X-Sim-Scenario)
    // =====================================================================

    public record AccessUserRow(String userId, String phone, String role, String status, long activeSessions) { }

    public record AccessRevokeResult(int revoked, String incidentId) { }

    public record SimControlsView(String defaultScenario, String bankReferenceSeq, boolean simulatorEnabled) { }

    public record SimControlsRequest(@NotBlank String scenario) { }

    public record SettingsView(MerchantApprovalRow profile, List<DeviceRow> devices, String shortCode, String qrPayload) { }

    // =====================================================================
    // Accept payment - sealed result hierarchy
    // =====================================================================

    public sealed interface PaymentOpResult permits PaymentConfirmed, PaymentPending, PaymentFailed { }

    public record PaymentConfirmed(String paymentId, BigDecimal amount, String currency,
                                   String label) implements PaymentOpResult { }

    public record PaymentPending(String paymentId, BigDecimal amount, String label) implements PaymentOpResult { }

    public record PaymentFailed(String paymentId, BigDecimal amount, String label) implements PaymentOpResult { }

    public record AcceptPaymentRequest(@NotNull @Positive BigDecimal amount,
                                       @NotNull Enums.PaymentChannel channel,
                                       String payerRef,
                                       String itemId,
                                       BigDecimal itemQuantity) { }

    public record PaymentRow(String id, String channel, BigDecimal amount, String currency, String status,
                             String payerRef, Instant createdAt, Instant confirmedAt) { }

    public record EventRow(String eventType, String payloadHash, String meta, Instant createdAt) { }

    public record PaymentDetailRow(String id, String merchantName, String channel, BigDecimal amount,
                                   String status, String payerRef, Instant createdAt, Instant confirmedAt,
                                   List<EventRow> events) { }

    public record PaymentOpsRow(String id, String merchantName, Enums.PaymentChannel channel,
                                BigDecimal amount, Enums.PaymentStatus status, Instant createdAt) { }

    public record RefundRequest(@NotNull @Positive BigDecimal amount, String reason) { }

    public record RefundRow(String id, String paymentId, BigDecimal amount, String reason,
                            String status, Instant createdAt) { }

    // =====================================================================
    // B2B stock purchase - sealed pay-from-balance outcome (M16..M19, M28)
    // =====================================================================

    public sealed interface InvoicePayResult permits InvoicePaid, InvoicePending, InvoiceFailed, InvoiceInsufficient { }

    public record InvoicePaid(String invoicePaymentId, BigDecimal amount, String bankReference,
                              String label) implements InvoicePayResult { }

    public record InvoicePending(String invoicePaymentId, BigDecimal amount,
                                 String label) implements InvoicePayResult { }

    public record InvoiceFailed(String invoicePaymentId, BigDecimal amount,
                                String label) implements InvoicePayResult { }

    public record InvoiceInsufficient(BigDecimal shortfall, String label) implements InvoicePayResult { }

    public record PayInvoiceRequest(Enums.InvoicePaymentRoute route, String outcome) { }

    public record InvoiceListRow(String id, String invoiceNumber, String uniqueReference, String counterpartyName,
                                 BigDecimal amount, String currency, Enums.InvoiceStatus status, Instant issuedAt) { }

    public record InvoicePaymentRow(String id, BigDecimal amount, String route, String status,
                                    String bankReference, Instant createdAt, Instant confirmedAt) { }

    public record InvoiceDetailRow(String id, String invoiceNumber, String uniqueReference, String supplierName,
                                   String merchantName, BigDecimal amount, String currency, String status,
                                   Instant issuedAt, List<InvoicePaymentRow> payments) { }

    public record CreateOrderRequest(@NotNull String supplierId,
                                     @NotNull List<CartLine> items) { }

    public record CartLine(@NotBlank String catalogueItemId, @NotNull @Positive BigDecimal quantity) { }

    public record OrderRow(String id, String merchantName, String supplierName,
                           Enums.OrderStatus status, Instant createdAt) { }

    public record OrderItemRow(String id, String productName, String unit, BigDecimal quantity,
                               BigDecimal unitPrice, BigDecimal lineTotal) { }

    public record OrderDetailRow(String id, String merchantName, String supplierName, String status,
                                 Instant createdAt, List<OrderItemRow> items, BigDecimal total) { }

    public record CatalogueRow(String id, String supplierId, String supplierName, String productName,
                               String unit, BigDecimal unitPrice, Enums.ItemStatus status) { }

    public record CatalogueUpsert(String productName, String unit, @NotNull @Positive BigDecimal unitPrice) { }

    // =====================================================================
    // Credit / advances
    // =====================================================================

    public record ConsentRequest(@NotBlank String lenderId, List<String> dataFields) { }

    public record ConsentRow(String consentId, String merchantId, String merchantName, String purpose,
                             Enums.ConsentStatus status, Instant grantedAt) { }

    public record RequestAdvanceRequest(@Positive BigDecimal amount, String purpose, @NotBlank String lenderId) { }

    public record EligibilityView(LocalDate windowStart, LocalDate windowEnd, BigDecimal eligibleTotal,
                                  Integer tradingDays, BigDecimal avgMonthlyNetReceipts,
                                  List<String> rulesApplied) { }

    public record AssessmentView(String assessmentId, String merchantId, LocalDate windowStart, LocalDate windowEnd,
                                 BigDecimal eligibleTotal, Integer tradingDays, BigDecimal avgMonthlyNetReceipts,
                                 BigDecimal score, Enums.AssessmentDecision decision, BigDecimal offerPrincipal,
                                 BigDecimal offerRepayment, BigDecimal repaymentRate, List<String> reasonCodes) { }

    public record AdvanceRow(String advanceId, String merchantName, BigDecimal principalAmount,
                             BigDecimal totalRepayment, Enums.AdvanceStatus status, Instant createdAt) { }

    public record AdvanceStatusRow(String id, Enums.AdvanceStatus status, BigDecimal requestedAmount,
                                   BigDecimal principalAmount, BigDecimal totalRepayment, BigDecimal repaymentRate,
                                   BigDecimal remainingOwed, Instant createdAt, Instant fundedAt) { }

    public record OfferView(String advanceId, BigDecimal requestedAmount, BigDecimal offerPrincipal,
                            BigDecimal totalRepayment, BigDecimal repaymentRate,
                            long illustrativeDaysAtAvg, long illustrativeDaysAtHalf) { }

    public record AdvanceDetailView(AdvanceStatusRow advance, List<CollectionRow> repayments) { }

    public record CollectionRow(String id, String merchantName, BigDecimal amount,
                                BigDecimal eligibleSalesBase, LocalDate collectionDate) { }

    public record MerchantRecordsView(String merchantName, LocalDate windowStart, LocalDate windowEnd,
                                      BigDecimal eligibleTotal, Integer tradingDays,
                                      BigDecimal avgMonthlyNetReceipts, List<MonthTotal> monthly) { }

    public record MonthTotal(String month, BigDecimal netReceipts) { }

    public record PortfolioView(BigDecimal outstandingTotal, BigDecimal owedTotal, BigDecimal arrearsAmount,
                                BigDecimal arrearsPct, List<SnapshotRow> snapshots, List<ArrearsRow> arrears) { }

    public record SnapshotRow(String snapshotId, LocalDate snapshotDate, BigDecimal outstandingPrincipal,
                              BigDecimal arrearsAmount) { }

    public record ArrearsRow(String caseId, String advanceId, Integer daysPastDue, String status) { }

    public record AssessRequest(@NotBlank String merchantId) { }

    public record DecideRequest(@NotBlank Enums.AssessmentDecision decision, Integer offeredAmount) { }

    public record SweepRequest(String date) { }

    // =====================================================================
    // Fees
    // =====================================================================

    public record FeePreview(BigDecimal amount, BigDecimal billed, BigDecimal externalCost, BigDecimal contribution,
                             BigDecimal ipsCustomerFee, BigDecimal switchingProvision, BigDecimal sponsorUnquoted,
                             List<String> labels) { }

    public record FeeTxRow(String id, String feeCode, BigDecimal amount, String reference,
                           String status, Instant createdAt) { }

    // =====================================================================
    // Reconciliation
    // =====================================================================

    public record ReconRunRow(String id, LocalDate runDate, BigDecimal issuerTotal, BigDecimal subledgerTotal,
                              BigDecimal creditLedgerTotal, Integer breaksCount, String status, Instant createdAt) { }

    public record BreakRow(String id, String breakType, BigDecimal amount, String reference,
                           String description, String status, Instant resolvedAt) { }

    /** Recon run outcome as a closed set: matched, breaks found, or blocked (BC p.18). */
    public sealed interface RunResult permits RunClean, RunWithBreaks, RunBlocked { }

    public record RunClean(String runId, String label) implements RunResult { }

    public record RunWithBreaks(String runId, int breakCount, List<BreakRow> breaks,
                                String label) implements RunResult { }

    public record RunBlocked(String runId, String code, String label) implements RunResult { }

    // =====================================================================
    // Operations
    // =====================================================================

    public record IncidentRow(String id, String title, Enums.IncidentSeverity severity, String description,
                              Enums.IncidentStatus status, Instant createdAt, Instant acknowledgedAt,
                              Boolean acknowledgedLate, Instant resolvedAt) { }

    public record CreateIncidentRequest(@NotBlank String title, @NotNull Enums.IncidentSeverity severity,
                                        String description) { }

    public record ComplaintRow(String id, String merchantName, String supplierName, String category,
                               String description, Enums.ComplaintStatus status, String assignedTo,
                               Instant createdAt) { }

    public record CreateComplaintRequest(String merchantId, String supplierId, @NotBlank String category,
                                         @NotBlank String description) { }

    public record DisputeRow(String id, String merchantId, String merchantName, String reason,
                             String details, Enums.DisputeStatus status, Instant createdAt) { }

    public record CreateDisputeRequest(String paymentId, @NotBlank String reason, String details) { }

    public record SuspendRequest(@NotBlank String entityType, @NotBlank String entityId, @NotBlank String reason) { }

    public record SuspensionRow(String id, String entityType, String entityId, String reason,
                                Instant suspendedAt, Instant liftedAt) { }

    public record SettlementRow(String id, LocalDate settlementDate, String cycle,
                                BigDecimal totalAmount, String status) { }

    public record FeeReportRow(String id, LocalDate reportDate, String feeCode,
                               BigDecimal amountBilled, BigDecimal amountCollected) { }

    public record StatementRow(String id, String ownerType, String ownerId, LocalDate periodStart,
                               LocalDate periodEnd, String fileRef, Instant generatedAt) { }

    public record GenerateStatementRequest(@NotBlank String ownerType, @NotBlank String ownerId,
                                           @NotNull LocalDate from, @NotNull LocalDate to) { }

    public record AuditRow(String id, String actorRole, String action, String entityType, String entityId,
                           String meta, Boolean simulated, Instant createdAt) { }

    public record AuditPage(List<AuditRow> rows, long totalElements, int page) { }

    // =====================================================================
    // Merchant tools
    // =====================================================================

    public record InventoryRow(String id, String name, String unit, BigDecimal currentStock,
                               BigDecimal reorderLevel, String alert) { }

    public record StockAdjustRequest(@NotBlank String itemId, @NotBlank Enums.StockMovementType movementType,
                                     @NotNull @Positive BigDecimal quantity, String reference) { }

    public record StockAlertRow(String id, String itemName, String alertType, Boolean resolved, Instant createdAt) { }

    public record ReorderRow(String id, String itemName, BigDecimal reorderLevel, String preferredSupplierId,
                             String preferredSupplierName, BigDecimal orderQuantity) { }

    public record ReorderRequest(@NotBlank String itemId) { }

    public record KhataRow(String id, String customerName, String customerPhone, BigDecimal outstanding,
                           String status) { }

    public record KhataEntryRow(String id, String entryType, BigDecimal amount, LocalDate dueDate,
                                Instant settledAt, Instant createdAt) { }

    public record KhataAccountRequest(@NotBlank String customerName, String customerPhone) { }

    public record KhataEntryRequest(@NotBlank Enums.KhataEntryType entryType, @NotNull @Positive BigDecimal amount,
                                    LocalDate dueDate) { }

    public record TabRow(String id, String customerName, String customerPhone, BigDecimal totalAmount,
                         String status, LocalDate dueDate) { }

    public record TabEntryRow(String id, BigDecimal amount, String description, Instant createdAt) { }

    public record TabCreateRequest(@NotBlank String customerName, String customerPhone,
                                   @NotNull @Positive BigDecimal amount, @NotBlank String description,
                                   LocalDate dueDate) { }

    public record TabAddEntryRequest(@NotNull @Positive BigDecimal amount, String description) { }

    public record RemindResult(String channel, String to, String body, String label) { }

    // =====================================================================
    // Supplier portal
    // =====================================================================

    public record BankAccountRow(String id, String bankName, String accountNumber, String accountHolder,
                                 String status, Instant createdAt) { }

    public record BankAccountChangeRequest(@NotBlank String bankName, @NotBlank String accountNumber,
                                           @NotBlank String accountHolder, String note) { }

    public record SupplierProfile(String id, String businessName, String ownerPhone,
                                  Enums.ApprovalStatus approvalStatus) { }

    // =====================================================================
    // Admin: connectors, tariffs, limits, rules, templates, simulation
    // =====================================================================

    public record ConnectorView(String id, String type, String name, String environment, String baseUrl,
                                String authType, String credentialsRef, String capabilities, String status,
                                Instant lastHealthCheck, Integer healthLatencyMs) { }

    public record ConnectorUpsert(@NotBlank Enums.ConnectorType connectorType, @NotBlank String name,
                                  @NotBlank Enums.PaymentEnvironment environment, String baseUrl,
                                  Enums.AuthType authType, String credentialsRef, String capabilities) { }

    public record ConnectorTestResult(String connectorId, String name, boolean ok, Integer latencyMs,
                                      String message, String label) { }

    public record TariffRow(String id, String code, String rateType, BigDecimal value, BigDecimal externalCost,
                            String costBasis, String note, LocalDate effectiveFrom, LocalDate effectiveTo) { }

    public record TariffUpsert(@NotBlank String code, @NotBlank Enums.RateType rateType,
                               @NotNull @Positive BigDecimal value, BigDecimal externalCost,
                               @NotNull Enums.CostBasis costBasis, String note, LocalDate effectiveFrom) { }

    public record LimitView(String id, String merchantId, BigDecimal maxBalance, BigDecimal dailyDebitCap,
                            BigDecimal withdrawalCap, BigDecimal advanceCap, BigDecimal arrearsTriggerPct) { }

    public record LimitUpsert(String merchantId, BigDecimal maxBalance, BigDecimal dailyDebitCap,
                              BigDecimal withdrawalCap, BigDecimal advanceCap, BigDecimal arrearsTriggerPct) { }

    public record RuleRow(String id, String ruleCode, Boolean enabled, String definition) { }

    public record RuleToggle(@NotNull Boolean enabled) { }

    public record SettingRow(String key, String value, String updatedAt) { }

    public record SettingUpsert(@NotBlank String key, @NotBlank String value) { }

    public record TemplateRow(String id, String channel, String eventCode, String body, String locale, String status) { }

    public record TemplateUpsert(@NotBlank Enums.TemplateChannel channel, @NotBlank String eventCode,
                                 @NotBlank String body) { }

    public record ScenarioRow(String id, String name, String description, String action,
                              Boolean isLabelled, Boolean enabled) { }

    public record StepResult(String step, boolean ok, String detail) { }

    public record SceneRunResult(String scene, List<StepResult> steps) { }

    public record ApiClientView(String id, String clientName, String clientId, String scopes, String status,
                                Integer rateLimitTps, Instant lastRotatedAt, Instant createdAt) { }

    public record ApiClientCreate(@NotBlank String clientName, List<String> scopes, Integer rateLimitTps) { }

    public record DeploymentRow(String id, String version, String environment, String status,
                                String notes, Instant deployedAt) { }

    public record DeployRequest(@NotBlank String version, @NotBlank String environment, String notes) { }

    public record BackupRow(String id, String backupType, Instant startedAt, Instant completedAt, String status) { }

    public record RestoreTestRow(String id, String backupId, Instant testedAt, Integer rtoAchievedMinutes,
                                 Integer rpoAchievedMinutes, Boolean ok) { }

    public record SyntheticHistoryRequest(@NotBlank String merchantId, @NotNull @Positive Integer days) { }

    public record SimTrigger(@NotBlank String action) { }

    public record SimResult(String action, String detail, String label) { }

    public record HealthView(String environment, BigDecimal availabilityPct, BigDecimal p95LatencyMs,
                             BigDecimal tps, Integer openCriticalIncidents, String label) { }

    // =====================================================================
    // Compliance
    // =====================================================================

    public record AmlAlertRow(String id, String alertType, String entityType, String entityId,
                              String details, String status, Instant createdAt) { }

    public record KycCheckRow(String id, String entityType, String entityId, String checkType,
                              String result, String details, Instant checkedAt) { }

    public record SarRow(String id, String alertId, String reference, String status,
                         Instant submittedAt, Instant createdAt) { }

    public record SarCreate(String alertId, String details) { }

    public record DsarRow(String id, String merchantId, String status, Instant requestedAt, Instant completedAt) { }

    public record DsarExport(String dsarId, String merchantId, String businessName, java.util.Map<String, Object> sections) { }

    // =====================================================================
    // Customer simulator (customer pays from own bank / basic phone)
    // =====================================================================

    public record CustomerPayRequest(String shortCode, String qrPayload, @NotNull @Positive BigDecimal amount,
                                     String payerPhone) { }

    public record SpeakerLogRow(String paymentId, BigDecimal amount, Instant confirmedAt) { }

    // =====================================================================
    // Simulation panel (E30-E45) - admin sim requests
    // =====================================================================

    public record SimPaymentRequest(String actorId, String merchantId, String paymentId, String outcome) { }

    public record SimInvoiceRequest(String merchantId, String invoicePaymentId, String outcome) { }

    public record SimAdvanceRequest(String action, String merchantId, String lenderId, String amount, String date) { }

    public record InjectBreakRequest(@NotNull @Positive BigDecimal amount, String reference) { }

    public record DsarCreateRequest(@NotBlank String merchantId) { }

    public record LenderAdvanceRow(String id, String merchantName, String merchantId, BigDecimal principal,
                                   BigDecimal outstanding, String status, Instant fundedAt, String cohort) { }
}
