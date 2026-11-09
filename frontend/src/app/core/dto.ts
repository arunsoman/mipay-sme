/**
 * TypeScript mirror of backend web/Dtos.java records the UI consumes.
 * Field names MUST equal the JSON contract; add a field here the moment a
 * screen needs it (backend record = source of truth).
 */

// =====================================================================
// env / auth
// =====================================================================
export interface OtpResult { phone: string; hint?: string | null; simulated: boolean; }
export interface TokenView {
  token: string; tokenType: string; expiresInSeconds: number;
  role: string; merchantId?: string | null; supplierId?: string | null; lenderId?: string | null;
}
export interface MeView {
  userId: string; name: string; role: string; phone: string;
  merchantId?: string | null; supplierId?: string | null; lenderId?: string | null;
  environment: string; simulated: boolean;
}

// =====================================================================
// ledger / dashboard
// =====================================================================
export interface BalanceView { available: number; pending: number; held: number; owed: number; issuerAccountRef?: string; legalHolder?: string; }
export interface LedgerRow { id: string; entryType: string; amount: number; currency: string; source: string; status: string; reference: string; createdAt: string; }
export interface DashboardView { available: number; pending: number; held: number; owed: number; salesToday: number; costsToday: number; profitToday: number; recent: RecentRow[]; }
export interface RecentRow { label: string; amount: number; at: string; status: string; }
export interface WithdrawalRow { id: string; amount: number; status: string; requestedAt: string; processedAt?: string | null; }
export interface PayInfo { qrPayload?: string; shortCode?: string; cardTerminalId?: string; }

// =====================================================================
// payments
// =====================================================================
export interface PaymentRow { id: string; channel: string; amount: number; currency: string; status: string; payerRef?: string; createdAt: string; confirmedAt?: string | null; }
export interface PaymentDetailRow extends PaymentRow { merchantName?: string; events?: EventRow[]; }
export interface EventRow { eventType: string; payloadHash?: string; meta?: string; createdAt: string; }
export interface RefundRow { id: string; paymentId: string; amount: number; reason?: string; status: string; createdAt: string; }
export interface SpeakerLogRow { paymentId: string; amount: number; confirmedAt: string; }

// =====================================================================
// b2b
// =====================================================================
export interface CatalogueRow { id: string; supplierId: string; supplierName?: string; productName: string; unit?: string; unitPrice: number; status: string; }
export interface OrderRow { id: string; merchantName?: string; supplierName?: string; status: string; total?: number; createdAt: string; }
export interface OrderItemRow { id: string; productName: string; unit?: string; quantity: number; unitPrice: number; lineTotal?: number; }
export interface OrderDetailRow extends OrderRow { items: OrderItemRow[]; total: number; }
export interface InvoiceListRow { id: string; invoiceNumber: string; uniqueReference: string; counterpartyName: string; amount: number; currency: string; status: string; issuedAt: string; }
export interface InvoicePaymentRow { id: string; amount: number; route?: string; status: string; bankReference?: string; createdAt: string; confirmedAt?: string | null; }
export interface InvoiceDetailRow extends InvoiceListRow { payments: InvoicePaymentRow[]; }

// =====================================================================
// credit
// =====================================================================
export interface ConsentRow { consentId: string; merchantId?: string | null; merchantName?: string | null; purpose?: string; status: string; grantedAt?: string; }
export interface EligibilityView {
  windowStart: string; windowEnd: string; eligibleTotal: number; tradingDays: number;
  avgMonthlyNetReceipts: number; rulesApplied: string[];
}
export interface MerchantRecordsView {
  businessName: string; windowStart: string; windowEnd: string;
  eligibleTotal: number; tradingDays: number; avgMonthlyNetReceipts: number;
  monthly: MonthTotal[];
}
export interface MonthTotal { month: string; netReceipts: number; }
export interface AssessmentView {
  assessmentId: string; merchantId: string; windowStart: string; windowEnd: string;
  eligibleTotal: number; tradingDays: number; avgMonthlyNetReceipts: number;
  score: number; decision: 'APPROVED' | 'DECLINED'; offerPrincipal: number;
  offerRepayment: number; repaymentRate: number; reasonCodes: string[];
}
export interface AdvanceStatusRow {
  id: string; status: string; requestedAmount?: number; principalAmount: number;
  totalRepayment: number; repaymentRate: number; remainingOwed: number; createdAt: string; fundedAt?: string | null;
}
export interface CollectionRow { id: string; merchantName: string; amount: number; eligibleSalesBase?: number; collectionDate: string; }
export interface AdvanceDetailView { advance: AdvanceStatusRow; repayments: CollectionRow[]; }
export interface LenderAdvanceRow { id: string; merchantName: string; merchantId: string; principal: number; outstanding: number; status: string; fundedAt?: string | null; cohort: string; }
export interface ArrearsRow { id: string; merchantName?: string; amount: number; daysPastDue?: number; status: string; openedAt?: string; }
export interface SnapshotRow { id?: string; totalOwed?: number; arrearsAmount?: number; arrearsPct?: number; at?: string; }
export interface PortfolioRow { merchantName?: string; outstanding?: number; status?: string; }
export interface PortfolioView { outstandingTotal: number; owedTotal: number; arrearsAmount: number; arrearsPct: number; snapshots: SnapshotRow[]; arrears: ArrearsRow[]; }

// =====================================================================
// ops / admin
// =====================================================================
export interface MerchantApprovalRow { id: string; businessName: string; ownerName: string; ownerPhone: string; businessType?: string; registrationNumber?: string; kycStatus: string; approvalStatus: string; createdAt: string; }
export interface SupplierApprovalRow { id: string; businessName: string; ownerName: string; ownerPhone: string; registrationNumber?: string; approvalStatus: string; createdAt: string; }
export interface BankApprovalRow { id: string; supplierName: string; bankName: string; accountNumber: string; accountHolder: string; status: string; requestedBy?: string; createdAt: string; }
export interface ApprovalResult { id?: string; status: string; message: string; }
export interface ReconRunRow { id: string; runDate: string; issuerTotal: number; subledgerTotal: number; creditLedgerTotal: number; breaksCount: number; status: string; createdAt: string; }
export interface BreakRow { id: string; breakType: string; amount: number; reference?: string; description?: string; status: string; resolvedAt?: string | null; }
export type RunResult = RunClean | RunWithBreaks | RunBlocked;
export interface RunClean { runId: string; label?: string; }
export interface RunWithBreaks { runId: string; breakCount: number; breaks: BreakRow[]; label?: string; }
export interface RunBlocked { runId: string; code: string; label?: string; }
export interface IncidentRow { id: string; title: string; severity: string; description?: string; status: string; createdAt: string; acknowledgedAt?: string | null; acknowledgedLate?: boolean | null; resolvedAt?: string | null; }
export interface ComplaintRow { id: string; merchantName?: string | null; supplierName?: string | null; category: string; description: string; status: string; assignedTo?: string | null; createdAt: string; }
export interface DisputeRow { id: string; paymentId?: string; reason?: string; status: string; createdAt: string; amount?: number; }
export interface SuspensionRow { id: string; entityType: string; entityId: string; reason: string; suspendedAt: string; liftedAt?: string | null; }
export interface AuditRow { id: string; action: string; actorId?: string | null; actorRole?: string | null; entityType?: string; entityId?: string; meta?: string | null; simulated?: boolean; createdAt: string; }
export interface AuditPage { rows: AuditRow[]; total: number; page: number; }
export interface SettlementRow { id?: string; settlementDate: string; cycle: string; totalAmount: number; status: string; }
export interface FeeReportRow { id: string; feeCode: string; amountBilled: number; amountCollected: number; reportDate: string; externalCost?: number; contribution?: number; }
export interface StatementRow { id: string; ownerType: string; ownerId: string; periodStart: string; periodEnd: string; generatedAt: string; }
export interface ConnectorView { id: string; type: string; name: string; environment: string; baseUrl?: string; status: string; lastHealthCheck?: string | null; healthLatencyMs?: number | null; details?: string | null; }
export interface ConnectorTestRow { connectorId: string; name?: string; ok: boolean; latencyMs?: number | null; error?: string | null; label?: string; }
export interface TariffRow { id: string; code: string; rateType: string; value: number; externalCost?: number | null; costBasis: string; note?: string | null; effectiveFrom: string; effectiveTo?: string | null; }
export interface LimitRow { id?: string; merchantId: string; maxBalance?: number; dailyDebitCap?: number; withdrawalCap?: number; advanceCap?: number; arrearsTriggerPct?: number; }
export interface RuleRow { id: string; ruleCode: string; enabled: boolean; definition?: string | null; }
export interface SettingRow { key: string; value: string; updatedAt: string; }
export interface TemplateRow { id: string; channel: string; eventCode: string; body: string; locale?: string; status?: string; }
export interface ScenarioRow { id: string; name: string; description: string; action: string; isLabelled?: boolean; enabled?: boolean; }
export interface StepResult { step: string; ok: boolean; detail?: string; }
export interface SceneRunResult { scene: string; steps: StepResult[]; }
export interface ApiClientRow { id: string; clientName: string; clientId: string; scopes: string; status: string; rateLimitTps?: number | null; lastRotatedAt?: string | null; createdAt: string; }
export interface DeploymentRow { id: string; version: string; environment: string; status: string; notes?: string | null; deployedAt: string; }
export interface BackupRow { id: string; backupType: string; startedAt: string; completedAt?: string | null; status: string; }
export interface RestoreTestRow { id: string; backupId: string; testedAt: string; rtoAchievedMinutes?: number | null; rpoAchievedMinutes?: number | null; ok?: boolean; }
export interface AmlRow { id: string; alertType: string; entityType?: string; entityId?: string; details?: string | null; status?: string; createdAt: string; }
export interface SarRow { id: string; alertId?: string; reference?: string; status?: string; submittedAt?: string | null; createdAt: string; }
export interface DsarRow { id: string; merchantId: string; status: string; requestedAt: string; completedAt?: string | null; }
export interface SarCreate { alertId: string; details?: string; }

// =====================================================================
// merchant tools
// =====================================================================
export interface InventoryRow { id: string; name: string; unit?: string; currentStock: number; reorderLevel?: number | null; state: string; }
export interface StockAlertRow { id: string; itemName?: string; alertType: string; resolved: boolean; createdAt: string; }
export interface ReorderRow { id: string; itemName: string; reorderLevel?: number; preferredSupplierId?: string | null; supplierName?: string | null; orderQuantity?: number; }
export interface KhataRow { id: string; customerName: string; customerPhone: string; outstanding: number; status: string; }
export interface KhataEntryRow { id: string; entryType: string; amount: number; dueDate?: string | null; settledAt?: string | null; createdAt: string; }
export interface TabRow { id: string; customerName: string; customerPhone: string; totalAmount: number; status: string; dueDate?: string | null; }
export interface TabEntryRow { id: string; amount: number; description?: string | null; createdAt: string; }
export interface DeviceRow { id: string; deviceFingerprint?: string; mfaMethod?: string; expiresAt?: string | null; createdAt: string; }
export interface SettingsView { profile?: MerchantApprovalRow | null; devices?: DeviceRow[]; shortCode?: string | null; qrPayload?: string | null; }
export interface LendersView { id: string; name: string; collectionRate?: number; serviceFeeRate?: number; lenderLimit?: number; }
export interface Message { message: string; label?: string | null; }
export interface RemindResult { channel: string; to: string; body: string; label?: string | null; }
export interface FeePreview {
  amount: number; billed: number; externalCost?: number; contribution?: number;
  ipsCustomerFee?: number; switchingProvision?: number; sponsorUnquoted?: number; labels: string[];
}
export interface SimResult { action: string; detail: string; label?: string; }
export interface TestDataSetRow { id?: string; name: string; description?: string; recordCount?: number; createdAt?: string; }
export interface HealthView { database?: string; tps?: number; openCriticalIncidents?: number; label?: string; }
// X04 access console + bank-sim controls (merged from reference implementation)
export interface AccessUserRow { userId: string; phone: string; role: string; status: string; activeSessions: number; }
export interface AccessRevokeResult { revoked: number; incidentId: string; }
export interface SimControlsView { defaultScenario: string; bankReferenceSeq?: string | null; simulatorEnabled: boolean; }
// KYC checks read model (ops approvals review panel)
export interface KycCheckRow { id: string; entityType: string; entityId: string; checkType: string; result: string; details?: string | null; checkedAt: string; }
