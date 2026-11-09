import { ChangeDetectionStrategy, Component, OnInit, inject, signal, computed } from '@angular/core';
import { CurrencyAmount } from '../../design-system/atoms/currency-amount';
import { StatusBadge } from '../../design-system/atoms/status-badge';
import { MpCard } from '../../design-system/atoms/mp-card';
import { MpButton } from '../../design-system/atoms/mp-button';
import { MpSpinner } from '../../design-system/atoms/mp-spinner';
import { PageHeader } from '../../design-system/organisms/page-header';
import { MpTable, MpColumn } from '../../design-system/organisms/mp-table';
import { KpiRow } from '../../design-system/organisms/kpi-row';
import { LenderService } from '../../core/services';
import * as D from '../../core/dto';

/* ================= lender home: consents + records (L01/L02) ================= */
@Component({
  selector: 'lender-home',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpCard, MpTable, MpSpinner, MpButton, StatusBadge, KpiRow, CurrencyAmount, PageHeader],
  template: `
    <page-header title="Consents & merchant records" subtitle="L01: consent queue; L02: records only AFTER consent (90d window, khata never shared - TC-ADV-02)" />
    <mp-card title="Data consents" class="mt">
      @if (consents(); as c) {
        <mp-table [cols]="consentCols" [rows]="c" [selectable]="true" (rowClick)="view($event)" empty="No consents yet" />
      } @else { <mp-spinner label="Loading consents..." /> }
    </mp-card>

    @if (records(); as r) {
      <mp-card [title]="'Merchant records: ' + r.businessName" [subtitle]="'window ' + r.windowStart + ' to ' + r.windowEnd" class="mt">
        <kpi-row [tiles]="[
          { label: 'Eligible total', amount: r.eligibleTotal, sub: r.tradingDays + ' trading days' },
          { label: 'Avg monthly net', amount: r.avgMonthlyNetReceipts, sub: 'basis = 20% of this' },
        ]" />
        <mp-table [cols]="monthCols" [rows]="r.monthly" class="mt" empty="No monthly data (simulated history)" />
      </mp-card>
      <mp-card title="Request assessment" class="mt">
        <mp-button (onClick)="assess()">Assess {{ r.businessName }} (C02)</mp-button>
      </mp-card>
    } @else if (!consents()?.length) {
      <mp-card class="mt"><div class="dim">Ask a merchant to grant data consent from their dashboard, then refresh.</div></mp-card>
    }
    @if (offer(); as o) {
      <mp-card title="Offer (C03)" class="mt">
        <div class="row">
          <status-badge [status]="o.decision === 'APPROVED' ? 'OFFERED' : 'DECLINED'" />
          <span>Offer: <currency-amount [amount]="o.offerPrincipal" /></span>
          <span>total repay <currency-amount [amount]="o.offerRepayment" /> at {{ (o.repaymentRate * 100).toFixed(0) }}%/day</span>
          <span class="dim">lesser of 20% x avg monthly / N$25k / lender limit</span>
        </div>
        <div class="dim">{{ (o.reasonCodes ?? []).join(' - ') }}</div>
        <div class="row mt">
          <mp-button variant="ghost" (onClick)="declineNote()">Decline (L05)</mp-button>
        </div>
      </mp-card>
    }
  `,
  styles: [`.row { display: flex; align-items: center; gap: var(--sp-3); flex-wrap: wrap; }`],
})
export class LenderHome implements OnInit {
  private readonly api = inject(LenderService);
  private readonly advancesSig = signal<D.LenderAdvanceRow[]>([]);
  readonly consentCols: MpColumn[] = [
    { key: 'consentId', label: 'Consent' },
    { key: 'merchantName', label: 'Merchant' },
    { key: 'purpose', label: 'Purpose' },
    { key: 'status', label: 'Status', type: 'status' },
    { key: 'grantedAt', label: 'Granted', type: 'time' },
  ];
  readonly monthCols: MpColumn[] = [
    { key: 'month', label: 'Month' },
    { key: 'netReceipts', label: 'Net receipts', type: 'money', align: 'right' },
  ];

  private readonly consentsSig = signal<D.ConsentRow[] | null>(null);
  private readonly recordsSig = signal<D.MerchantRecordsView | null>(null);
  private readonly offerSig = signal<D.AssessmentView | null>(null);
  consents() { return this.consentsSig(); }
  records() { return this.recordsSig(); }
  offer() { return this.offerSig(); }

  ngOnInit() { void this.refresh(); }
  refresh() {
    void this.api.consents().then((c) => this.consentsSig.set(c));
    void this.api.advances().then((a) => this.advancesSig.set(a)).catch(() => null);
  }

  async view(consentId: string) {
    const c = (this.consentsSig() ?? []).find((x) => x.consentId === consentId);
    const merchantId = c?.merchantId;
    if (!merchantId) { alert('Consent has no merchant reference'); return; }
    try { this.recordsSig.set(await this.api.recordsView(merchantId)); }
    catch (e) {
      const err = (e as { error?: { detail?: string } })?.error ?? {};
      alert('Records need an ACTIVE consent: ' + (err['detail'] ?? 'denied'));
    }
  }

  async assess() {
    const c = (this.consentsSig() ?? []).find((x) => x.status === 'ACTIVE');
    if (!c?.merchantId) { alert('No ACTIVE consent to assess'); return; }
    try {
      this.offerSig.set(await this.api.assess(c.merchantId!));
    } catch (e) {
      const err = (e as { error?: { detail?: string } })?.error ?? {};
      alert('Assessment failed: ' + (err['detail'] ?? 'unknown'));
    }
  }

  /** L05: pending advances can be declined by the lender (audit: advance_offer_declined). */
  declineNote() {
    const adv = (this.advancesSig() ?? []).find((a) => a.status === 'OFFERED');
    if (!adv) { alert('No OFFERED advance to decline'); return; }
    void this.api.declines(adv.id).then(() => this.refresh());
  }
}

/* ================= L06/L07 - portfolio ================= */
@Component({
  selector: 'lender-portfolio',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpCard, MpTable, MpSpinner, MpButton, KpiRow, PageHeader],
  template: `
    <page-header title="Portfolio" subtitle="L06: outstanding / owed / arrears; L07: snapshots (C42)" [subtitle]="'L06/L07 portfolio + arrears'" />
    @if (port(); as p) {
      <kpi-row [tiles]="[
        { label: 'Outstanding', amount: p.outstandingTotal ?? 0, sub: 'principal in the field' },
        { label: 'Owed total', amount: p.owedTotal ?? 0, sub: 'principal + fees' },
        { label: 'Arrears', amount: p.arrearsAmount ?? 0, sub: (p.arrearsPct ?? 0) + '% of book' },
      ]" />
      <mp-card title="Advances" class="mt">
        <mp-table [cols]="advCols" [rows]="adv()" empty="No advances funded yet" />
      </mp-card>
      <mp-card title="Arrears" class="mt">
        <mp-table [cols]="arrCols" [rows]="p.arrears ?? []" empty="No arrears (all healthy SIMULATED book)" />
      </mp-card>
      <div class="row mt">
        <mp-button (onClick)="snap()">Take snapshot (L07)</mp-button>
      </div>
      @if (snapResult(); as s) { <pre class="wire">{{ pretty(s) }}</pre> }
    } @else { <mp-spinner label="Loading portfolio..." /> }
  `,
  styles: [`
    .row { display: flex; gap: var(--sp-3); }
    .wire { background: var(--mp-bg); padding: var(--sp-3); border-radius: var(--radius-1); font-size: var(--fs-sm); margin-top: var(--sp-3); max-height: 200px; overflow: auto; }
  `],
})
export class LenderPortfolio implements OnInit {
  private readonly api = inject(LenderService);
  readonly advCols: MpColumn[] = [
    { key: 'merchantName', label: 'Merchant' },
    { key: 'principal', label: 'Principal', type: 'money', align: 'right' },
    { key: 'outstanding', label: 'Outstanding', type: 'money', align: 'right' },
    { key: 'status', label: 'Status', type: 'status' },
    { key: 'fundedAt', label: 'Funded', type: 'time' },
    { key: 'cohort', label: 'Cohort' },
  ];
  readonly arrCols: MpColumn[] = [
    { key: 'merchantName', label: 'Merchant' },
    { key: 'amount', label: 'Arrears', type: 'money', align: 'right' },
    { key: 'daysPastDue', label: 'Days past due', align: 'right' },
    { key: 'status', label: 'Status', type: 'status' },
  ];

  private readonly portSig = signal<D.PortfolioView | null>(null);
  private readonly advSig = signal<D.LenderAdvanceRow[]>([]);
  private readonly snapSig = signal<unknown>(null);
  port() { return this.portSig(); }
  adv() { return this.advSig(); }
  snapResult() { return this.snapSig(); }
  pretty(x: unknown): string { return JSON.stringify(x, null, 2); }

  ngOnInit() { void this.refresh(); }
  refresh() {
    void this.api.portfolio().then((p) => this.portSig.set(p as D.PortfolioView));
    void this.api.advances().then((a) => this.advSig.set(a));
  }

  async snap() {
    this.snapSig.set(await this.api.snapshot());
    void this.refresh();
  }
}

/* ================= L08-L10 collections ================= */
@Component({
  selector: 'lender-collections',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpCard, MpTable, MpSpinner, MpButton, StatusBadge, PageHeader],
  template: `
    <page-header title="Collections" subtitle="L08: 10% of eligible daily receipts + arrears follow-ups (TC-ADV-04)" />
    <div class="row mt">
      <mp-button (onClick)="sweep()">Run daily sweep (L09)</mp-button>
      <mp-button variant="ghost" (onClick)="refresh()">Refresh</mp-button>
    </div>
    @if (steps(); as s) {
      <mp-card title="Sweep result" class="mt">
        <ul>
          @for (st of s; track st.step) { <li><status-badge [status]="st.ok ? 'CONFIRMED' : 'FAILED'" /> {{ st.step }} - {{ st.detail ?? '' }}</li> }
        </ul>
      </mp-card>
    }
    <mp-card title="Collections" class="mt">
      @if (rows(); as r) {
        <mp-table [cols]="cols" [rows]="r" empty="No collections yet - run the sweep" />
      } @else { <mp-spinner label="Loading collections..." /> }
    </mp-card>
  `,
  styles: [`.row { display: flex; gap: var(--sp-3); }`],
})
export class LenderCollections implements OnInit {
  private readonly api = inject(LenderService);
  readonly cols: MpColumn[] = [
    { key: 'collectionDate', label: 'Day', type: 'date' },
    { key: 'merchantName', label: 'Merchant' },
    { key: 'amount', label: 'Collected', type: 'money', align: 'right' },
    { key: 'eligibleSalesBase', label: 'Base', type: 'money', align: 'right' },
  ];

  private readonly rowsSig = signal<D.CollectionRow[] | null>(null);
  private readonly stepsSig = signal<D.StepResult[] | null>(null);
  rows() { return this.rowsSig(); }
  steps() { return this.stepsSig(); }

  ngOnInit() { void this.refresh(); }
  refresh() { void this.api.collections().then((r) => this.rowsSig.set(r)); }

  async sweep() {
    this.stepsSig.set(await this.api.sweep());
    void this.refresh();
  }
}
