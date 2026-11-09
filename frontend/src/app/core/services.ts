import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import * as D from './dto';

/** Generic problem error carrying the parsed ProblemDetail. */
export class ApiError extends Error {
  constructor(public problem: { title: string; detail: string; code?: string; fields?: Record<string, string> }) {
    super(problem.detail || problem.title);
  }
}

@Injectable({ providedIn: 'root' })
export class MerchantService {
  private readonly http = inject(HttpClient);

  private get<T>(url: string, params?: HttpParams) { return firstValueFrom(this.http.get<T>(url, { params })); }
  private post<T>(url: string, body: unknown = {}) { return firstValueFrom(this.http.post<T>(url, body)); }

  dashboard = () => this.get<D.DashboardView>('/api/merchant/dashboard');
  balance = () => this.get<D.BalanceView>('/api/merchant/balance');
  ledger = (page = 0, size = 100) => this.get<D.LedgerRow[]>('/api/merchant/balance/entries', new HttpParams({ fromObject: { page, size } }));
  payInfo = () => this.get<D.PayInfo>('/api/merchant/accept-info');
  billingPreview = (amount: number) => this.get<D.FeePreview>('/api/merchant/billing-preview', new HttpParams({ fromObject: { amount } }));

  acceptPayment = (b: { amount: number; channel: string; payerRef?: string; itemId?: string; itemQuantity?: number }) =>
    this.post<{ paymentId: string; amount: number; label: string }>('/api/merchant/payments', b);
  payments = (page = 0, size = 20) => this.get<D.PaymentRow[]>('/api/merchant/payments', new HttpParams({ fromObject: { page, size } }));
  paymentDetail = (id: string) => this.get<D.PaymentDetailRow>(`/api/merchant/payments/${id}`);
  simulate = (id: string, action: string) => this.post<D.PaymentRow>(`/api/merchant/payments/${id}/simulate`, { action });
  refund = (id: string, amount: number, reason: string) => this.post<D.RefundRow>(`/api/merchant/payments/${id}/refund`, { amount, reason });
  speakerLog = () => this.get<D.SpeakerLogRow[]>('/api/merchant/speaker-log');

  invoices = () => this.get<D.InvoiceListRow[]>('/api/merchant/invoices');
  invoiceDetail = (id: string) => this.get<D.InvoiceDetailRow>(`/api/merchant/invoices/${id}`);
  payInvoice = (id: string, route?: string, outcome?: string) => this.post<D.InvoiceDetailRow>(`/api/merchant/invoices/${id}/pay`, { route, outcome });
  retryTimeout = (id: string, outcome?: string) => this.post<D.InvoiceDetailRow>(`/api/merchant/invoices/${id}/retry-timeout`, { outcome });

  catalogue = () => this.get<D.CatalogueRow[]>('/api/merchant/catalogue');
  placeOrder = (supplierId: string, items: { catalogueItemId: string; quantity: number; note?: string }[]) =>
    this.post<D.OrderDetailRow>('/api/merchant/orders', { supplierId, items });
  orders = () => this.get<D.OrderRow[]>('/api/merchant/orders');
  orderDetail = (id: string) => this.get<D.OrderDetailRow>(`/api/merchant/orders/${id}`);

  lenders = () => this.get<D.LendersView[]>('/api/merchant/lenders');
  grantConsent = (lenderId: string) => this.post<D.ConsentRow>('/api/merchant/advance/consent', { lenderId });
  consents = () => this.get<D.ConsentRow[]>('/api/merchant/advance/consents');
  withdrawConsent = (id: string) => this.post<D.Message>(`/api/merchant/advance/consents/${id}/withdraw`);
  requestAdvance = (lenderId: string, purpose: string) => this.post<D.AssessmentView>('/api/merchant/advance/request', { lenderId, purpose });
  eligibility = () => this.get<D.EligibilityView>('/api/merchant/advance/eligibility');
  currentAdvance = () => this.get<D.AdvanceDetailView>('/api/merchant/advance');
  acceptAdvance = (id: string) => this.post<D.Message>(`/api/merchant/advance/${id}/accept`);

  inventory = () => this.get<D.InventoryRow[]>('/api/merchant/inventory');
  addInventoryItem = (name: string, unit: string, reorderLevel: string, openingStock: string) =>
    this.post<D.InventoryRow>('/api/merchant/inventory', { name, unit, reorderLevel, openingStock });
  adjustStock = (b: { itemId: string; movementType: string; quantity: number; reference?: string }) =>
    this.post<D.InventoryRow>('/api/merchant/inventory/adjust', b);
  stockAlerts = () => this.get<D.StockAlertRow[]>('/api/merchant/inventory/alerts');
  reorderRules = () => this.get<D.ReorderRow[]>('/api/merchant/inventory/rules');
  reorder = (itemId: string) => this.post<D.Message>(`/api/merchant/inventory/${itemId}/reorder`);

  khata = () => this.get<D.KhataRow[]>('/api/merchant/khata');
  createKhata = (customerName: string, customerPhone: string) => this.post<D.KhataRow>('/api/merchant/khata', { customerName, customerPhone });
  khataEntries = (id: string) => this.get<D.KhataEntryRow[]>(`/api/merchant/khata/${id}/entries`);
  addKhataEntry = (id: string, b: { entryType: string; amount: number; dueDate?: string }) => this.post<D.KhataEntryRow>(`/api/merchant/khata/${id}/entries`, b);
  remindKhata = (id: string) => this.post<D.RemindResult>(`/api/merchant/khata/${id}/remind`);

  tabs = () => this.get<D.TabRow[]>('/api/merchant/tabs');
  createTab = (b: { customerName: string; customerPhone: string; amount: number; description?: string; dueDate?: string }) => this.post<D.TabRow>('/api/merchant/tabs', b);
  tabEntries = (id: string) => this.get<D.TabEntryRow[]>(`/api/merchant/tabs/${id}/entries`);
  addTabEntry = (id: string, b: { amount: number; description?: string }) => this.post<D.TabEntryRow>(`/api/merchant/tabs/${id}/entries`, b);
  settleTab = (id: string) => this.post<D.TabRow>(`/api/merchant/tabs/${id}/settle`);
  remindTab = (id: string) => this.post<D.RemindResult>(`/api/merchant/tabs/${id}/remind`);

  withdraw = (amount: number) => this.post<D.WithdrawalRow>('/api/merchant/withdrawals', { amount });
  withdrawals = () => this.get<D.WithdrawalRow[]>('/api/merchant/withdrawals');

  statements = () => this.get<D.StatementRow[]>('/api/merchant/statements');
  generateStatement = (from: string, to: string) => this.post<D.StatementRow>('/api/merchant/statements', { from, to });
  statementText = (id: string) => firstValueFrom(this.http.get(`/api/merchant/statements/${id}/download`, { responseType: 'text' }));

  settings = () => this.get<D.SettingsView>('/api/merchant/settings');
  devices = () => this.get<D.DeviceRow[]>('/api/merchant/devices');
}

@Injectable({ providedIn: 'root' })
export class SupplierService {
  private readonly http = inject(HttpClient);

  private get<T>(url: string, params?: HttpParams) { return firstValueFrom(this.http.get<T>(url, { params })); }
  private post<T>(url: string, body: unknown = {}) { return firstValueFrom(this.http.post<T>(url, body)); }

  profile = () => this.get<D.MerchantApprovalRow>('/api/supplier/profile');
  bankAccounts = () => this.get<D.BankApprovalRow[]>('/api/supplier/bank-accounts');
  changeBankAccount = (b: { bankName: string; accountNumber: string; accountHolder: string; note?: string }) =>
    this.post<D.ApprovalResult>('/api/supplier/bank-accounts/change', b);
  revokeBankAccount = (id: string) => this.post<void>(`/api/supplier/bank-accounts/${id}/revoke`);

  catalogue = () => this.get<D.CatalogueRow[]>('/api/supplier/catalogue');
  addToCatalogue = (b: { productName: string; unit: string; unitPrice: number }) => this.post<D.CatalogueRow>('/api/supplier/catalogue', b);
  retireItem = (id: string) => this.post<void>(`/api/supplier/catalogue/${id}/retire`);

  orders = () => this.get<D.OrderRow[]>('/api/supplier/orders');
  orderDetail = (id: string) => this.get<D.OrderDetailRow>(`/api/supplier/orders/${id}`);
  issueInvoice = (orderId: string) => this.post<D.InvoiceDetailRow>(`/api/supplier/orders/${orderId}/invoice`);

  invoices = () => this.get<D.InvoiceListRow[]>('/api/supplier/invoices');
  invoiceDetail = (id: string) => this.get<D.InvoiceDetailRow>(`/api/supplier/invoices/${id}`);
  paymentsReceived = () => this.get<D.InvoicePaymentRow[]>('/api/supplier/payments');

  statements = () => this.get<D.StatementRow[]>('/api/supplier/statements');
  generateStatement = (from: string, to: string) => this.post<D.StatementRow>('/api/supplier/statements', { from, to });
}

@Injectable({ providedIn: 'root' })
export class LenderService {
  private readonly http = inject(HttpClient);

  private get<T>(url: string, params?: HttpParams) { return firstValueFrom(this.http.get<T>(url, { params })); }
  private post<T>(url: string, body: unknown = {}) { return firstValueFrom(this.http.post<T>(url, body)); }

  consents = () => this.get<D.ConsentRow[]>('/api/lender/consents');
  recordsView = (merchantId: string) => this.get<D.MerchantRecordsView>(`/api/lender/records/${merchantId}`);
  assess = (merchantId: string) => this.post<D.AssessmentView>('/api/lender/assess', { merchantId });
  assessments = () => this.get<unknown[]>('/api/lender/assessments');
  declines = (id: string) => this.post<D.Message>(`/api/lender/advances/${id}/decline`);
  fund = (id: string) => this.post<D.Message>(`/api/lender/advances/${id}/fund`);
  portfolio = () => this.get<D.PortfolioView>('/api/lender/portfolio');
  snapshot = () => this.post<D.SnapshotRow>('/api/lender/portfolio/snapshot');
  advances = () => this.get<D.LenderAdvanceRow[]>('/api/lender/advances');
  collections = () => this.get<D.CollectionRow[]>('/api/lender/collections');
  sweep = (date?: string) => this.post<D.StepResult[]>('/api/lender/sweep', { date });
}

@Injectable({ providedIn: 'root' })
export class OpsService {
  private readonly http = inject(HttpClient);

  private get<T>(url: string, params?: HttpParams) { return firstValueFrom(this.http.get<T>(url, { params })); }
  private post<T>(url: string, body: unknown = {}) { return firstValueFrom(this.http.post<T>(url, body)); }

  merchantQueue = () => this.get<D.MerchantApprovalRow[]>('/api/ops/approvals/merchants');
  allMerchants = () => this.get<D.MerchantApprovalRow[]>('/api/ops/approvals/merchants/all');
  supplierQueue = () => this.get<D.SupplierApprovalRow[]>('/api/ops/approvals/suppliers');
  allSuppliers = () => this.get<D.SupplierApprovalRow[]>('/api/ops/approvals/suppliers/all');
  bankQueue = () => this.get<D.BankApprovalRow[]>('/api/ops/approvals/bank-accounts');
  kycChecks = (entityType: 'merchant' | 'supplier', entityId: string) =>
    this.get<D.KycCheckRow[]>(`/api/ops/kyc/${entityType}/${entityId}`);
  approveMerchant = (id: string, b: { approve: boolean; sanctionsResult?: string }) => this.post<D.ApprovalResult>(`/api/ops/approvals/merchants/${id}`, b);
  approveSupplier = (id: string, b: { approve: boolean; note?: string }) => this.post<D.ApprovalResult>(`/api/ops/approvals/suppliers/${id}`, b);
  approveBankAccount = (id: string, b: { approve: boolean; note?: string }) => this.post<D.ApprovalResult>(`/api/ops/approvals/bank-accounts/${id}`, b);

  reconRuns = () => this.get<D.ReconRunRow[]>('/api/ops/recon/runs');
  reconRun = () => this.post<D.RunResult>('/api/ops/recon/run');
  breaks = (runId: string) => this.get<D.BreakRow[]>(`/api/ops/recon/runs/${runId}/breaks`);
  resolveBreak = (id: string) => this.post<D.BreakRow>(`/api/ops/recon/breaks/${id}/resolve`);

  incidents = () => this.get<D.IncidentRow[]>('/api/ops/incidents');
  createIncident = (b: { title: string; severity: string; description?: string }) => this.post<D.IncidentRow>('/api/ops/incidents', b);
  ackIncident = (id: string) => this.post<D.IncidentRow>(`/api/ops/incidents/${id}/ack`);
  resolveIncident = (id: string) => this.post<D.IncidentRow>(`/api/ops/incidents/${id}/resolve`);

  complaints = () => this.get<D.ComplaintRow[]>('/api/ops/complaints');
  createComplaint = (b: { category: string; description: string; merchantId?: string; supplierId?: string }) => this.post<D.ComplaintRow>('/api/ops/complaints', b);
  resolveComplaint = (id: string, resolution: string) => this.post<D.Message>(`/api/ops/complaints/${id}/resolve?resolution=${encodeURIComponent(resolution)}`);
  assignComplaint = (id: string, assignee?: string) => this.post<D.Message>(`/api/ops/complaints/${id}/assign${assignee ? `?assignee=${encodeURIComponent(assignee)}` : ''}`);

  disputes = () => this.get<D.DisputeRow[]>('/api/ops/disputes');
  createDispute = (b: { paymentId: string; reason: string; details?: string }) => this.post<D.Message>('/api/ops/disputes', b);
  resolveDispute = (id: string, won: boolean) => this.post<D.Message>(`/api/ops/disputes/${id}/resolve?won=${won}`);

  suspensions = () => this.get<D.SuspensionRow[]>('/api/ops/suspensions');
  suspend = (b: { entityType: string; entityId: string; reason: string }) => this.post<D.SuspensionRow>('/api/ops/suspend', b);
  lift = (id: string) => this.post<D.SuspensionRow[]>(`/api/ops/suspensions/${id}/lift`);

  audit = (simulated: boolean, page = 0, size = 25) =>
    this.get<D.AuditPage>('/api/ops/audit', new HttpParams({ fromObject: { simulated: String(simulated), page, size } }));

  settlement = (date?: string) => this.get<D.SettlementRow>('/api/ops/reports/settlement', date ? new HttpParams({ fromObject: { date } }) : undefined);
  feeReports = (date?: string) => this.get<D.FeeReportRow[]>('/api/ops/reports/fees', date ? new HttpParams({ fromObject: { date } }) : undefined);

  generateStatement = (ownerType: string, ownerId: string, from: string, to: string) =>
    this.post<D.StatementRow>('/api/ops/statements/generate', { ownerType, ownerId, from, to });

  touchMetrics = () => this.post<D.Message>('/api/ops/monitoring/touch');
  payments = () => this.get<unknown[]>('/api/ops/payments');
}

@Injectable({ providedIn: 'root' })
export class AdminService {
  private readonly http = inject(HttpClient);

  private get<T>(url: string, params?: HttpParams) { return firstValueFrom(this.http.get<T>(url, { params })); }
  private post<T>(url: string, body: unknown = {}) { return firstValueFrom(this.http.post<T>(url, body)); }

  connectors = () => this.get<D.ConnectorView[]>('/api/admin/connectors');
  upsertConnector = (b: Record<string, unknown>) => this.post<D.ConnectorView>('/api/admin/connectors', b);
  testConnector = (id: string) => this.post<D.ConnectorTestRow>(`/api/admin/connectors/${id}/test`);
  rotateConnector = (id: string) => this.post<D.ConnectorView>(`/api/admin/connectors/${id}/rotate`);

  tariffs = () => this.get<D.TariffRow[]>('/api/admin/tariffs');
  upsertTariff = (b: Record<string, unknown>) => this.post<D.TariffRow>('/api/admin/tariffs', b);
  limits = () => this.get<D.LimitRow[]>('/api/admin/limits');
  upsertLimit = (b: Record<string, unknown>) => this.post<D.LimitRow>('/api/admin/limits', b);
  rules = () => this.get<D.RuleRow[]>('/api/admin/rules');
  toggleRule = (code: string, enabled: boolean) => this.post<D.RuleRow>(`/api/admin/rules/${code}`, { enabled });
  settings = () => this.get<D.SettingRow[]>('/api/admin/settings');
  upsertSetting = (key: string, value: string) => this.post<D.SettingRow>('/api/admin/settings', { key, value });
  templates = () => this.get<D.TemplateRow[]>('/api/admin/templates');
  upsertTemplate = (b: Record<string, unknown>) => this.post<D.TemplateRow>('/api/admin/templates', b);

  scenarios = () => this.get<D.ScenarioRow[]>('/api/admin/scenarios');
  injectBreak = (b: { breakType: string; amount?: number; reference?: string }) => this.post<D.Message>('/api/admin/sim/inject-break', b);
  clearBreaks = () => this.post<D.Message>('/api/admin/sim/clear-breaks');
  syntheticHistory = () => this.post<D.SimResult>('/api/admin/sim/synthetic-history', { days: 120 });
  runScene = (scene: string) => this.post<D.SceneRunResult>('/api/admin/scenes/run', { scene });

  apiClients = () => this.get<D.ApiClientRow[]>('/api/admin/api-clients');
  createClient = (b: { clientName: string; scopes?: string[]; rateLimitTps?: number }) => this.post<D.ApiClientRow>('/api/admin/api-clients', b);
  revokeClient = (id: string) => this.post<D.ApiClientRow>(`/api/admin/api-clients/${id}/revoke`);

  deployments = () => this.get<D.DeploymentRow[]>('/api/admin/deployments');
  deploy = (b: { version: string; environment: string; notes?: string }) => this.post<D.DeploymentRow>('/api/admin/deployments', b);
  rollback = (version: string) => this.post<D.DeploymentRow>('/api/admin/deployments/rollback', { version });

  backupNow = () => this.post<D.BackupRow>('/api/admin/backups');
  backups = () => this.get<D.BackupRow[]>('/api/admin/backups');
  restoreTest = (backupId: string) => this.post<D.RestoreTestRow>(`/api/admin/backups/${backupId}/restore-test`);
  restoreTests = () => this.get<D.RestoreTestRow[]>('/api/admin/restore-tests');

  amlAlerts = () => this.get<D.AmlRow[]>('/api/admin/compliance/aml-alerts');
  sars = () => this.get<D.SarRow[]>('/api/admin/compliance/sars');
  createSar = (alertId: string, details: string) => this.post<D.SarRow>('/api/admin/compliance/sars', { alertId, details });
  submitSar = (id: string) => this.post<D.SarRow>(`/api/admin/compliance/sars/${id}/submit`);
  dsars = () => this.get<D.DsarRow[]>('/api/admin/compliance/dsars');
  createDsar = (merchantId: string) => this.post<D.DsarRow>('/api/admin/compliance/dsars', { merchantId });

  health = () => this.get<D.HealthView>('/api/admin/health');

  // X04 access console (reference implementation merge)
  accessUsers = () => this.get<D.AccessUserRow[]>('/api/admin/access');
  revokeUserAccess = (userId: string) => this.post<D.AccessRevokeResult>(`/api/admin/access/${userId}/revoke`);
  // bank-sim controls (reference implementation merge)
  simControls = () => this.get<D.SimControlsView>('/api/admin/sim/controls');
  setSimControls = (scenario: string) => this.post<D.SimControlsView>('/api/admin/sim/controls', { scenario });
}

/** Public self-onboarding (UAT SB p.8; no auth) - TPTS approval happens in the ops console. */
@Injectable({ providedIn: 'root' })
export class OnboardingService {
  private readonly http = inject(HttpClient);

  registerMerchant = (b: { phone: string; businessName: string; ownerName: string; ownerIdNumber: string; businessType?: string; registrationNumber?: string }) =>
    firstValueFrom(this.http.post<D.Message>('/api/onboarding/merchant', b));
  registerSupplier = (b: { phone: string; businessName: string; ownerName: string; registrationNumber?: string; bankName: string; accountNumber: string; accountHolder: string }) =>
    firstValueFrom(this.http.post<D.Message>('/api/onboarding/supplier', b));
}
