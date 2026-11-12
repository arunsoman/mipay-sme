import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { StatusBadge } from '../../design-system/atoms/status-badge';
import { MpCard } from '../../design-system/atoms/mp-card';
import { MpButton } from '../../design-system/atoms/mp-button';
import { MpSpinner } from '../../design-system/atoms/mp-spinner';
import { PageHeader } from '../../design-system/organisms/page-header';
import { MpTable, MpColumn } from '../../design-system/organisms/mp-table';
import { MpEntityForm, MpFieldSchema } from '../../design-system/organisms/mp-entity-form';
import { EmptyState } from '../../design-system/molecules/empty-state';
import { KpiRow } from '../../design-system/organisms/kpi-row';
import { ApprovalActions } from '../../design-system/organisms/approval-actions';
import { MpPerm } from '../../../../projects/shared/web-core/perm-directive';
import { OpsService } from '../../core/services';
import * as D from '../../core/dto';

function wireMsg(e: unknown): string {
  const err = (e as { error?: { detail?: string; title?: string } })?.error ?? {};
  return String(err['detail'] || err['title'] || 'unknown');
}

/* ================= T01-T04 - registrations & bank approvals ================= */
@Component({
  selector: 'ops-approvals',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpCard, MpTable, MpSpinner, MpButton, StatusBadge, PageHeader, ApprovalActions, EmptyState, MpPerm],
  template: `
    <page-header title="Approvals" subtitle="New merchants, suppliers and bank account changes waiting for a decision. Pick a row to see its KYC checks." />
    <mp-card title="Merchants" subtitle="Pending merchants can be approved or rejected" class="mt">
      @if (merchants(); as m) {
        <mp-table [cols]="merchCols" [rows]="m" [selectable]="true" (rowClick)="pick('merchant', $event)" empty="No merchants waiting for approval" />
        @for (row of m; track row.id) {
          @if (isPENDING(row.approvalStatus)) {
            <approval-actions [status]="row.approvalStatus" perm="ops.merchant_approval:authorize" (decide)="approveMerchant(row.id, $event)" />
          }
        }
      } @else { <mp-spinner label="Loading merchants…" /> }
    </mp-card>
    <mp-card title="Suppliers" subtitle="Pending suppliers can be approved or rejected" class="mt">
      @if (suppliers(); as s) {
        <mp-table [cols]="supCols" [rows]="s" [selectable]="true" (rowClick)="pick('supplier', $event)" empty="No suppliers waiting for approval" />
        @for (row of s; track row.id) {
          @if (isPENDING(row.approvalStatus)) {
            <approval-actions [status]="row.approvalStatus" perm="ops.supplier_approval:authorize" (decide)="approveSupplier(row.id, $event)" />
          }
        }
      } @else { <mp-spinner label="Loading suppliers…" /> }
    </mp-card>
    <mp-card title="Bank account changes" class="mt">
      @if (banks(); as b) {
        <mp-table [cols]="bankCols" [rows]="b" [selectable]="true" (rowClick)="selBankId.set($event)" empty="No bank change requests" />
        @if (selBank(); as row) {
          @if (isPENDING(row.status)) {
            <approval-actions [status]="row.status" perm="ops.bank_account_approval:authorize" (decide)="approveBank(row.id, $event)" />
          } @else { <div class="dim mt">Selected request is already {{ row.status }}.</div> }
        }
      } @else { <mp-spinner label="Loading bank account changes…" /> }
    </mp-card>

    <!-- KYC review panel (recorded IDENTITY/SANCTIONS checks; read model) -->
    @if (kycEntity(); as ke) {
      <mp-card [title]="'KYC checks - ' + ke.label" class="mt">
        @if (kycRows(); as rows) {
          @if (rows.length) {
            <mp-table [cols]="kycCols" [rows]="rows" empty="No KYC checks recorded" />
          } @else {
            <empty-state icon="⛨" title="No KYC checks recorded yet"
              hint="Checks are recorded automatically (IDENTITY + SANCTIONS) when ops approves this entity." />
          }
          <div class="dim mt">The person who asked for a change can’t approve it.</div>
        } @else { <mp-spinner label="Loading KYC checks..." /> }
      </mp-card>
    }
  `,
  styles: [`.mt { margin-top: var(--sp-3); }`],
})
export class OpsApprovals implements OnInit {
  private readonly api = inject(OpsService);
  readonly merchCols: MpColumn[] = [
    { key: 'businessName', label: 'Business' },
    { key: 'ownerName', label: 'Owner' },
    { key: 'ownerPhone', label: 'Phone' },
    { key: 'businessType', label: 'Type' },
    { key: 'kycStatus', label: 'KYC' },
    { key: 'approvalStatus', label: 'Status', type: 'status' },
    { key: 'createdAt', label: 'Requested', type: 'time' },
  ];
  readonly supCols: MpColumn[] = [
    { key: 'businessName', label: 'Business' },
    { key: 'ownerName', label: 'Owner' },
    { key: 'registrationNumber', label: 'Registration no.' },
    { key: 'approvalStatus', label: 'Status', type: 'status' },
    { key: 'createdAt', label: 'Requested', type: 'time' },
  ];
  readonly bankCols: MpColumn[] = [
    { key: 'supplierName', label: 'Supplier' },
    { key: 'bankName', label: 'Bank' },
    { key: 'accountNumber', label: 'Account' },
    { key: 'accountHolder', label: 'Holder' },
    { key: 'status', label: 'Status', type: 'status' },
    { key: 'requestedBy', label: 'Requested by' },
  ];
  readonly kycCols: MpColumn[] = [
    { key: 'checkedAt', label: 'Checked', type: 'time' },
    { key: 'checkType', label: 'Check' },
    { key: 'result', label: 'Result', type: 'status' },
    { key: 'details', label: 'Details' },
  ];

  private readonly merchSig = signal<D.MerchantApprovalRow[] | null>(null);
  private readonly supSig = signal<D.SupplierApprovalRow[] | null>(null);
  private readonly bankSig = signal<D.BankApprovalRow[] | null>(null);
  readonly selBankId = signal<string | null>(null);
  readonly selBank = computed(() => (this.bankSig() ?? []).find((b) => b.id === this.selBankId()) ?? null);
  merchants() { return this.merchSig(); }
  suppliers() { return this.supSig(); }
  banks() { return this.bankSig(); }

  // KYC review panel state
  readonly kycEntity = signal<{ entityType: 'merchant' | 'supplier'; entityId: string; label: string } | null>(null);
  private readonly kycSig = signal<D.KycCheckRow[] | null>(null);
  kycRows() { return this.kycSig(); }

  /** Row selection on the merchant/supplier queue opens the KYC checks panel. */
  pick(entityType: 'merchant' | 'supplier', entityId: string) {
    if (this.kycEntity()?.entityId === entityId && this.kycEntity()?.entityType === entityType) {
      this.kycEntity.set(null); // toggle off
      return;
    }
    const row = ((entityType === 'merchant' ? this.merchSig() : this.supSig()) ?? [])
      .find((r) => r.id === entityId);
    this.kycEntity.set({ entityType, entityId, label: row ? row.businessName : entityId });
    this.kycSig.set(null);
    void this.api.kycChecks(entityType, entityId).then((rows) => this.kycSig.set(rows));
  }

  ngOnInit() { void this.refresh(); }
  refresh() {
    // full directory (approved + pending): approval actions render only for PENDING rows,
    // and approved entities carry their recorded KYC checks for the review panel
    void this.api.allMerchants().then((m) => this.merchSig.set(m));
    void this.api.allSuppliers().then((s) => this.supSig.set(s));
    void this.api.bankQueue().then((b) => this.bankSig.set(b));
  }

  isPENDING(v: string) { return v === 'PENDING'; }

  async approveMerchant(id: string, d: { approve: boolean }) {
    try { await this.api.approveMerchant(id, d); } catch (e) { alert(wireMsg(e)); }
    this.refresh();
  }
  async approveSupplier(id: string, d: { approve: boolean }) {
    try { await this.api.approveSupplier(id, d); } catch (e) { alert(wireMsg(e)); }
    this.refresh();
  }
  async approveBank(id: string, d: { approve: boolean }) {
    try { await this.api.approveBankAccount(id, d); } catch (e) { alert(wireMsg(e)); }
    this.selBankId.set(null);
    this.refresh();
  }
}

/* ================= T05-T08 - reconciliation + break gate ================= */
@Component({
  selector: 'ops-recon',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpCard, MpTable, MpSpinner, MpButton, StatusBadge, PageHeader, MpPerm],
  template: `
    <page-header title="Reconciliation" subtitle="Checks that the bank, MiPay and the credit ledger agree. While a mismatch is open, withdrawals are paused." />
    <div class="row mt">
      <mp-button *mpPerm="'ops.recon:execute'" (onClick)="runRecon()">Run a check now</mp-button>
    </div>
    @if (lastRun(); as r) {
      <mp-card title="Last run outcome" class="mt">
        <div class="row">
          <status-badge [status]="lastStatus()" />
          <span>Run {{ r.runId.slice(0, 8) }}</span>
          @if ('breakCount' in r) { <span><b>{{ r.breakCount }}</b> break(s): withdrawals are BLOCKED until resolved.</span> }
          @if ('code' in r) { <span class="dim">{{ r.code }}: run blocked while a previous run has open breaks</span> }
        </div>
        @if ('breaks' in r && r.breaks?.length) {
          <mp-table [cols]="breakCols" [rows]="r.breaks! ?? r.breaks" class="mt" />
        }
      </mp-card>
    }
    <mp-card title="Past checks" class="mt">
      @if (runs(); as runs) {
        <mp-table [cols]="runCols" [rows]="runs" [selectable]="true" (rowClick)="selectRun($event)" empty="No runs yet" />
      } @else { <mp-spinner label="Loading runs..." /> }
    </mp-card>
    <mp-card [title]="selRun() ? 'Mismatches in this check' : 'Pick a check to see its mismatches'" class="mt">
      @if (breaks(); as b) {
        <mp-table [cols]="breakCols" [rows]="b" empty="Everything matched" />
      } @else if (selRun()) { <mp-spinner label="Loading…" /> }
    </mp-card>
    @if (doneMsg(); as m) { <div class="ok">{{ m }}</div> }
  `,
  styles: [`
    .row { display: flex; align-items: center; gap: var(--sp-3); }
    .ok { color: #16a34a; margin-top: var(--sp-3); }
    .mt { margin-top: var(--sp-3); }
  `],
})
export class OpsRecon implements OnInit {
  private readonly api = inject(OpsService);
  readonly runCols: MpColumn[] = [
    { key: 'id', label: 'Run' },
    { key: 'issuerTotal', label: 'Bank', type: 'money', align: 'right' },
    { key: 'subledgerTotal', label: 'MiPay', type: 'money', align: 'right' },
    { key: 'creditLedgerTotal', label: 'Credit ledger', type: 'money', align: 'right' },
    { key: 'breaksCount', label: 'Mismatches', align: 'right' },
    { key: 'status', label: 'Status', type: 'status' },
  ];
  readonly breakCols: MpColumn[] = [
    { key: 'id', label: 'Mismatch' },
    { key: 'breakType', label: 'Type' },
    { key: 'amount', label: 'Amount', type: 'money', align: 'right' },
    { key: 'reference', label: 'Reference' },
    { key: 'description', label: 'Detail' },
    { key: 'status', label: 'Status', type: 'status' },
  ];

  private readonly runsSig = signal<D.ReconRunRow[] | null>(null);
  private readonly lastRunSig = signal<D.RunResult | null>(null);
  private readonly breaksSig = signal<D.BreakRow[] | null>(null);
  private readonly selRunSig = signal<string | null>(null);
  private readonly doneMsgSig = signal('');
  runs() { return this.runsSig(); }
  lastRun() { return this.lastRunSig(); }

  lastBreakCount(): number | null {
    const r = this.lastRunSig();
    return r && 'breakCount' in r ? r.breakCount : null;
  }
  lastBlockedCode(): string | null {
    const r = this.lastRunSig();
    return r && 'code' in r ? r.code : null;
  }
  lastBreaks(): D.BreakRow[] | null {
    const r = this.lastRunSig();
    return r && 'breaks' in r ? (r.breaks ?? null) : null;
  }
  breaks() { return this.breaksSig(); }
  selRun() { return this.selRunSig(); }
  doneMsg() { return this.doneMsgSig(); }

  lastStatus(): string {
    const r = this.lastRunSig();
    if (!r) return 'PENDING';
    return 'status' in r ? (r as { status?: string }).status ?? 'BREAKS_FOUND' : 'CLEAN';
  }
  selectRun(id: string) { this.selRunSig.set(id); void this.loadBreaks(); }

  ngOnInit() { void this.refresh(); }
  refresh() { void this.api.reconRuns().then((r) => this.runsSig.set(r)); }

  async runRecon() {
    this.doneMsgSig.set('');
    try {
      const result = await this.api.reconRun();
      this.lastRunSig.set(result);
      this.refresh();
      void this.api.reconRuns().then((runs) => {
        const latest = runs[0];
        if (latest) {
          this.selRunSig.set(latest.id);
          void this.loadBreaks();
        }
      });
    } catch (e) { alert('Recon run failed: ' + wireMsg(e)); }
  }

  async loadBreaks() {
    const id = this.selRunSig();
    if (!id) return;
    void this.api.breaks(id).then((b) => this.breaksSig.set(b));
  }
}

/* ================= T09-T11 - incidents ================= */
@Component({
  selector: 'ops-incidents',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpCard, MpTable, MpSpinner, MpButton, PageHeader, MpEntityForm, MpPerm],
  template: `
    <page-header title="Incidents" subtitle="Log a problem, acknowledge it within 15 minutes, then resolve it" />
    <mp-card title="All incidents" class="mt">
      @if (rows(); as r) {
        <mp-table [cols]="cols" [rows]="r" [selectable]="true" (rowClick)="selSig.set($event)" empty="No incidents logged" />
        <div class="row mt">
          <mp-button *mpPerm="'ops.incident:modify'" (onClick)="ack()" [disabled]="!selSig()">Acknowledge</mp-button>
          <mp-button *mpPerm="'ops.incident:modify'" (onClick)="resolve()" [disabled]="!selSig()">Resolve</mp-button>
          <span class="dim">Selected: {{ selSig()?.slice(0, 8) }}</span>
        </div>
      } @else { <mp-spinner label="Loading incidents..." /> }
      @if (done(); as m) { <div class="ok">{{ m }}</div> }
    </mp-card>
    <mp-entity-form #cf class="mt" [schema]="schema" submitLabel="Log incident" *mpPerm="'ops.incident:create'" (submitted)="create($event)" />
  `,
  styles: [`
    .row { display: flex; align-items: center; gap: var(--sp-3); }
    .ok { color: #16a34a; margin-top: var(--sp-3); }
    .mt { margin-top: var(--sp-3); }
  `],
})
export class OpsIncidents implements OnInit {
  private readonly api = inject(OpsService);
  readonly cols: MpColumn[] = [
    { key: 'createdAt', label: 'When', type: 'time' },
    { key: 'severity', label: 'Severity' },
    { key: 'title', label: 'Title' },
    { key: 'status', label: 'Status', type: 'status' },
    { key: 'acknowledgedAt', label: 'Acknowledged', type: 'time' },
    { key: 'acknowledgedLate', label: 'Late' },
    { key: 'resolvedAt', label: 'Resolved', type: 'time' },
  ];
  readonly schema: MpFieldSchema[] = [
    { key: 'title', label: 'Title', kind: 'text', required: true },
    { key: 'severity', label: 'Severity', kind: 'select', required: true,
      options: [{ value: 'CRITICAL', label: 'CRITICAL' }, { value: 'HIGH', label: 'HIGH' },
                { value: 'MEDIUM', label: 'MEDIUM' }, { value: 'LOW', label: 'LOW' }] },
    { key: 'description', label: 'Description', kind: 'text' },
  ];

  private readonly rowsSig = signal<D.IncidentRow[] | null>(null);
  readonly selSig = signal<string | null>(null);
  private readonly doneSig = signal('');
  rows() { return this.rowsSig(); }
  done() { return this.doneSig(); }

  private readonly cf = viewChild('cf');

  ngOnInit() { void this.refresh(); }
  refresh() { void this.api.incidents().then((r) => this.rowsSig.set(r)); }

  async create(v: Record<string, string>) {
    try {
      await this.api.createIncident({ title: v['title'], severity: v['severity'], description: v['description'] });
      (this.cf() as unknown as MpEntityForm).done('Incident logged');
      this.refresh();
    } catch (e) { (this.cf() as unknown as MpEntityForm).failWire(e); }
  }
  async ack() { if (this.selSig()) { await this.api.ackIncident(this.selSig()!); this.doneSig.set('Acknowledged'); this.refresh(); } }
  async resolve() { if (this.selSig()) { await this.api.resolveIncident(this.selSig()!); this.doneSig.set('Resolved'); this.refresh(); } }
}

/* ================= T12-T15/T16-T17 - service desk: complaints, disputes, suspensions ================= */
@Component({
  selector: 'ops-desk',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpCard, MpTable, MpSpinner, MpButton, StatusBadge, PageHeader, MpEntityForm, FormsModule, MpPerm],
  template: `
    <page-header title="Complaints & disputes" subtitle="Customer complaints, payment disputes and account suspensions" />
    <div class="grid2">
      <mp-card title="Complaints" class="mt">
        @if (complaints(); as c) {
          <mp-table [cols]="compCols" [rows]="c" [selectable]="true" (rowClick)="selComp.set($event)" empty="No complaints" />
          <div class="row mt">
            <mp-button *mpPerm="'ops.complaint:modify'" variant="ghost" (onClick)="assign()" [disabled]="!selComp()">Assign to me</mp-button>
            <mp-button *mpPerm="'ops.complaint:modify'" (onClick)="resolveComp()" [disabled]="!selComp()">Resolve</mp-button>
          </div>
        } @else { <mp-spinner label="Loading..." /> }
      </mp-card>
      <mp-card title="Disputes" class="mt">
        @if (disputes(); as d) {
          <mp-table [cols]="dispCols" [rows]="d" [selectable]="true" (rowClick)="selDisp.set($event)" empty="No disputes" />
          <div class="row mt">
            <mp-button *mpPerm="'ops.dispute:modify'" (onClick)="resolveDisp(true)" [disabled]="!selDisp()">Resolve in favour</mp-button>
            <mp-button *mpPerm="'ops.dispute:modify'" variant="ghost" (onClick)="resolveDisp(false)" [disabled]="!selDisp()">Resolve against</mp-button>
          </div>
        } @else { <mp-spinner label="Loading..." /> }
      </mp-card>
      <mp-card title="Suspensions" class="mt">
        @if (suspensions(); as s) {
          <mp-table [cols]="suspCols" [rows]="s" [selectable]="true" (rowClick)="selSusp.set($event)" empty="No suspensions" />
          <div class="row mt"><mp-button *mpPerm="'ops.suspension:administer'" (onClick)="lift()" [disabled]="!selSusp()">Lift suspension</mp-button></div>
        } @else { <mp-spinner label="Loading..." /> }
      </mp-card>
      <div class="colgap">
        <mp-entity-form #icf [schema]="incSchema" submitLabel="Log complaint" *mpPerm="'ops.complaint:create'" (submitted)="createComp($event)" />
        <mp-entity-form #dcf [schema]="dispSchema" submitLabel="Open dispute" *mpPerm="'ops.dispute:create'" (submitted)="createDisp($event)" />
        <mp-entity-form #scf [schema]="suspSchema" submitLabel="Suspend account" *mpPerm="'ops.suspension:administer'" (submitted)="suspend($event)" />
        <div class="dim">A suspended account can’t sign in until the suspension is lifted.</div>
      </div>
    </div>
  `,
  styles: [`
    .grid2 { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--sp-4); align-items: start; }
    @media (max-width: 900px) { .grid2 { grid-template-columns: minmax(0, 1fr); } }
    .row { display: flex; gap: var(--sp-3); }
    .colgap { display: grid; gap: var(--sp-3); }
    .mt { margin-top: var(--sp-3); }
  `],
})
export class OpsDesk implements OnInit {
  private readonly api = inject(OpsService);
  readonly compCols: MpColumn[] = [
    { key: 'createdAt', label: 'When', type: 'time' },
    { key: 'category', label: 'Category' },
    { key: 'description', label: 'Detail' },
    { key: 'assignedTo', label: 'Owner' },
    { key: 'status', label: 'Status', type: 'status' },
  ];
  readonly dispCols: MpColumn[] = [
    { key: 'createdAt', label: 'When', type: 'time' },
    { key: 'paymentId', label: 'Payment' },
    { key: 'reason', label: 'Reason' },
    { key: 'status', label: 'Status', type: 'status' },
  ];
  readonly suspCols: MpColumn[] = [
    { key: 'suspendedAt', label: 'When', type: 'time' },
    { key: 'entityType', label: 'Account' },
    { key: 'entityId', label: 'ID' },
    { key: 'reason', label: 'Reason' },
    { key: 'liftedAt', label: 'Lifted', type: 'time' },
  ];
  readonly incSchema: MpFieldSchema[] = [
    { key: 'category', label: 'Category', kind: 'select', required: true,
      options: [{ value: 'ONBOARDING', label: 'Onboarding' }, { value: 'PAYMENT', label: 'Payment' },
                { value: 'ADVANCE', label: 'Advance' }, { value: 'DATA', label: 'Data consent' },
                { value: 'OTHER', label: 'Other' }] },
    { key: 'description', label: 'Description', kind: 'text', required: true },
  ];
  readonly dispSchema: MpFieldSchema[] = [
    { key: 'paymentId', label: 'Payment reference', kind: 'text', required: true },
    { key: 'reason', label: 'Reason', kind: 'text', required: true },
  ];
  readonly suspSchema: MpFieldSchema[] = [
    { key: 'entityType', label: 'Account type', kind: 'select', required: true,
      options: [{ value: 'MERCHANT', label: 'Merchant' }, { value: 'SUPPLIER', label: 'Supplier' }] },
    { key: 'entityId', label: 'Merchant or supplier ID', kind: 'text', required: true },
    { key: 'reason', label: 'Reason', kind: 'text', required: true },
  ];

  private readonly complainsSig = signal<D.ComplaintRow[] | null>(null);
  private readonly disputesSig = signal<D.DisputeRow[] | null>(null);
  private readonly suspSig = signal<D.SuspensionRow[] | null>(null);
  readonly selComp = signal<string | null>(null);
  readonly selDisp = signal<string | null>(null);
  readonly selSusp = signal<string | null>(null);
  complaints() { return this.complainsSig(); }
  disputes() { return this.disputesSig(); }
  suspensions() { return this.suspSig(); }

  private readonly icf = viewChild('icf');
  private readonly dcf = viewChild('dcf');
  private readonly scf = viewChild('scf');

  ngOnInit() { void this.refresh(); }
  refresh() {
    void this.api.complaints().then((c) => this.complainsSig.set(c));
    void this.api.disputes().then((d) => this.disputesSig.set(d));
    void this.api.suspensions().then((s) => this.suspSig.set(s));
  }

  async createComp(v: Record<string, string>) {
    try {
      await this.api.createComplaint({ category: v['category'], description: v['description'] });
      (this.icf() as unknown as MpEntityForm).done('Complaint logged and unassigned');
      this.refresh();
    } catch (e) { (this.icf() as unknown as MpEntityForm).failWire(e); }
  }
  async createDisp(v: Record<string, string>) {
    try {
      await this.api.createDispute({ paymentId: v['paymentId'], reason: v['reason'] });
      (this.dcf() as unknown as MpEntityForm).done('Dispute opened (card 2-phase reversals only)');
      this.refresh();
    } catch (e) { (this.dcf() as unknown as MpEntityForm).failWire(e); }
  }
  async suspend(v: Record<string, string>) {
    try {
      await this.api.suspend({ entityType: v['entityType'], entityId: v['entityId'], reason: v['reason'] });
      (this.scf() as unknown as MpEntityForm).done('Entity suspended');
      this.refresh();
    } catch (e) { (this.scf() as unknown as MpEntityForm).failWire(e); }
  }
  async assign() { if (this.selComp()) { await this.api.assignComplaint(this.selComp()!); this.refresh(); } }
  async resolveComp() { if (this.selComp()) { await this.api.resolveComplaint(this.selComp()!, 'resolved via service desk'); this.refresh(); } }
  async resolveDisp(won: boolean) { if (this.selDisp()) { await this.api.resolveDispute(this.selDisp()!, won); this.refresh(); } }
  async lift() { if (this.selSusp()) { await this.api.lift(this.selSusp()!); this.refresh(); } }
}

/* ================= T18/T19 - settlement + fee reports ================= */
@Component({
  selector: 'ops-reports',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpCard, MpTable, MpButton, PageHeader, KpiRow, FormsModule],
  template: `
    <page-header title="Reports" subtitle="Daily settlement and the fees earned for a day" />
    <div class="row mt">
      <label>Date (YYYY-MM-DD, blank = today):</label>
      <input class="in" [(ngModel)]="date" placeholder="e.g. 2025-03-14" />
      <mp-button (onClick)="load()">Load</mp-button>
    </div>
    @if (settle(); as s) {
      <mp-card title="Settlement" class="mt">
        <kpi-row [tiles]="[
          { label: 'Cycle', amount: s.cycle ?? '-', money: false, sub: s.settlementDate },
          { label: 'Settled', amount: s.totalAmount ?? 0, sub: s.status },
        ]" />
      </mp-card>
    }
    <mp-card title="Fees" class="mt">
      <mp-table [cols]="feeCols" [rows]="fees() ?? []" empty="No fees billed for this date" />
    </mp-card>
  `,
  styles: [`
    .row { display: flex; align-items: center; gap: var(--sp-3); }
    .in { padding: 8px; border: 1px solid var(--mp-border); border-radius: var(--radius-1); font: inherit; }
    .mt { margin-top: var(--sp-3); }
  `],
})
export class OpsReports implements OnInit {
  private readonly api = inject(OpsService);
  readonly feeCols: MpColumn[] = [
    { key: 'feeCode', label: 'Fee code' },
    { key: 'amountBilled', label: 'Billed', type: 'money', align: 'right' },
    { key: 'externalCost', label: 'External cost', type: 'money', align: 'right' },
    { key: 'contribution', label: 'MiPay contribution', type: 'money', align: 'right' },
    { key: 'reportDate', label: 'Date', type: 'date' },
  ];

  date = '';
  private readonly settleSig = signal<D.SettlementRow | null>(null);
  private readonly feesSig = signal<D.FeeReportRow[] | null>(null);
  settle() { return this.settleSig(); }
  fees() { return this.feesSig(); }

  ngOnInit() { void this.load(); }
  load() {
    void this.api.settlement(this.date || undefined).then((s) => this.settleSig.set(s));
    void this.api.feeReports(this.date || undefined).then((f) => this.feesSig.set(f));
  }
}

/* ================= T20 - audit trail with SIMULATED filter ================= */
@Component({
  selector: 'ops-audit',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpTable, MpButton, PageHeader, MpSpinner, FormsModule],
  template: `
    <page-header title="Audit trail" subtitle="Who did what, and when" />
    <div class="row mt">
      <label class="dim"><input type="checkbox" [(ngModel)]="simOnly" (change)="load()" /> Simulated events only</label>
      <mp-button variant="ghost" (onClick)="nextPage()">Next page</mp-button>
      <span class="dim">page {{ page() + 1 }} / total {{ total() }}</span>
    </div>
    @if (rows(); as r) {
      <mp-table [cols]="cols" [rows]="r" empty="No audit entries" />
    } @else { <mp-spinner label="Loading…" /> }
  `,
  styles: [`
    .row { display: flex; align-items: center; gap: var(--sp-4); }
  `],
})
export class OpsAudit implements OnInit {
  private readonly api = inject(OpsService);
  readonly cols: MpColumn[] = [
    { key: 'createdAt', label: 'When', type: 'time' },
    { key: 'action', label: 'Action' },
    { key: 'actorRole', label: 'Actor' },
    { key: 'entityType', label: 'Account' },
    { key: 'simulated', label: 'Sim' },
    { key: 'meta', label: 'Meta' },
  ];
  simOnly = true;
  private pageSig = signal(0);
  page() { return this.pageSig(); }
  nextPage() { this.pageSig.set(Math.min(this.pageSig() + 1, Math.max(0, Math.floor(this.total() / 25) - 1))); void this.load(); }
  totalSig = signal(0);
  total() { return this.totalSig(); }

  private readonly rowsSig = signal<D.AuditRow[] | null>(null);
  rows() { return this.rowsSig(); }

  ngOnInit() { void this.load(); }
  async load() {
    const res = await this.api.audit(this.simOnly, this.pageSig(), 25);
    this.rowsSig.set(res.rows ?? []);
    this.totalSig.set(res.total ?? 0);
  }
}
