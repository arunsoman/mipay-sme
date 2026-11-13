import { MpAlert } from '../../design-system/molecules/alert';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal, viewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { CurrencyAmount } from '../../design-system/atoms/currency-amount';
import { StatusBadge } from '../../design-system/atoms/status-badge';
import { MpCard } from '../../design-system/atoms/mp-card';
import { MpButton } from '../../design-system/atoms/mp-button';
import { MpSpinner } from '../../design-system/atoms/mp-spinner';
import { PageHeader } from '../../design-system/organisms/page-header';
import { KpiRow } from '../../design-system/organisms/kpi-row';
import { EventTimeline } from '../../design-system/organisms/event-timeline';
import { MpTable, MpColumn } from '../../design-system/organisms/mp-table';
import { MpEntityForm, MpFieldSchema } from '../../design-system/organisms/mp-entity-form';
import { MpPerm } from '../../../../projects/shared/web-core/perm-directive';
import { MerchantService } from '../../core/services';
import { SimulatedBanner } from '../../design-system/molecules/simulated-banner';
import * as D from '../../core/dto';

function money(v: number | string | undefined | null): string {
  return 'N$ ' + Number(v ?? 0).toFixed(2);
}
function str(x: unknown): string { return JSON.stringify(x, null, 2); }
function wireMsg(e: unknown): string {
  const err = (e as { error?: { detail?: string; title?: string } })?.error ?? {};
  return String(err['detail'] || err['title'] || 'unknown');
}

/* =====================================================================
 * M04 - dashboard: one aggregate call, no N+1 (recent + balance + today)
 * ===================================================================== */
@Component({
  selector: 'merchant-dashboard',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CommonModule, KpiRow, MpCard, StatusBadge, CurrencyAmount, MpSpinner, PageHeader],
  template: `
    @if (view(); as d) {
      <page-header [title]="'Dashboard'" subtitle="M04 - balance, today's movement, recent payments (NAD)" />
      <kpi-row [tiles]="tiles(d)" />
      <mp-card title="Recent activity" class="mt">
        <ul class="rec">
          @for (r of d.recent; track $index) {
            <li>
              <span class="lbl">{{ r.label }}</span>
              <span class="mono"><currency-amount [amount]="r.amount" /></span>
              <span class="dim">{{ r.at | date: 'EEE d MMM, HH:mm' }}</span>
              <status-badge [status]="r.status" />
            </li>
          } @empty { <li class="dim">No recent activity - accept a payment to see it here instantly</li> }
        </ul>
      </mp-card>
    } @else { <mp-spinner label="Loading dashboard (single aggregate call)..." /> }
  `,
  styles: [`
    ul.rec { list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; }
    .rec li { display: flex; align-items: baseline; gap: var(--sp-4); }
    .lbl { min-width: 190px; font-weight: 600; }
    .mt { margin-top: var(--sp-4); }
  `],
})
export class MerchantDashboard implements OnInit {
  private readonly api = inject(MerchantService);
  private readonly viewSig = signal<D.DashboardView | null>(null);
  view() { return this.viewSig(); }
  tiles(d: D.DashboardView) {
    return [
      { label: 'Available', amount: d.available, sub: 'confirmed minus holds (TC-BAL-01)' },
      { label: 'Pending', amount: d.pending, sub: 'card 2-phase, not spendable' },
      { label: 'Advance owed', amount: d.owed, sub: '10% of eligible sales daily' },
      { label: 'Sales today', amount: d.salesToday, sub: 'profit ' + money(d.profitToday) },
    ];
  }
  ngOnInit() { void this.api.dashboard().then((d) => this.viewSig.set(d)).catch(() => this.viewSig.set(null)); }
}

/* =====================================================================
 * M06-M09 accept + E31 callback simulator panel
 * ===================================================================== */
@Component({
  selector: 'merchant-accept',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpAlert, FormsModule, MpCard, PageHeader, MpButton, StatusBadge, MpPerm],
  template: `
    <page-header title="Accept payment" subtitle="M06: QR / short code / card. Card posts PENDING until the issuer confirms (TC-ACC-01)" />
    <div class="grid2">
      <mp-card title="New payment">
        <form class="stack" (ngSubmit)="create()">
          <label>Amount (N$)</label>
          <input class="in" [(ngModel)]="amount" name="amount" type="number" min="1" step="0.01" required />
          <label>Type</label>
          <select class="in" [(ngModel)]="channel" name="channel">
            <option value="QR">QR code</option>
            <option value="SHORT_CODE">Short code</option>
            <option value="CARD">Card</option>
          </select>
          <label>Payer reference</label>
          <input class="in" [(ngModel)]="payerRef" name="payerRef" placeholder="customer phone / card mask" />
          <mp-button type="submit" *mpPerm="'merchant.payment:execute'" [loading]="busy()">Create payment</mp-button>
        </form>
        @if (last(); as p) {
          <div class="created">
            Payment <b>{{ p.paymentId.slice(0, 8) }}</b> <status-badge [status]="p.status" /> -
            now pay it as a customer from the <a href="/customer">customer simulator</a>, or replay the callback here.
          </div>
        }
        @if (acceptErr()) {<mp-alert kind="bad">{{ acceptErr() }}</mp-alert>}
      </mp-card>
      <mp-card title="Issuer / acquirer callback simulator" subtitle="Exactly one confirmation per payment (E31, idempotency-key)">
        @if (lastId(); as id) {
          <div class="simbtns">
            <ng-container *mpPerm="'merchant.payment:simulate'">
              @for (a of actions; track a) {
                <mp-button [variant]="a === 'confirm' ? 'primary' : (a === 'fail' ? 'danger' : 'ghost')"
                           (onClick)="simulate(a)">{{ a.toUpperCase() }}</mp-button>
              }
            </ng-container>
          </div>
          @if (simRes(); as s) { <status-badge [status]="s.status" /> <p class="dim">{{ s.label }}</p> }
          @else { <p class="dim">Create a payment, then replay a callback kind.</p> }
        } @else {
          <p class="dim">Create a payment first, then replay real callback kinds here.</p>
        }
        @if (info(); as pi) {
          <div class="payinfo">
            <div class="dim">Your receive points now:</div>
            <div>QR: <b class="mono">{{ pi.qrPayload || '-' }}</b></div>
            <div>Short code: <b>{{ pi.shortCode || '-' }}</b></div>
            <div>Card terminal: <b>{{ pi.cardTerminalId || '-' }}</b></div>
          </div>
        }
      </mp-card>
    </div>
  `,
  styles: [`
    .stack { display: grid; gap: 6px; }
    label { font-size: var(--fs-sm); color: var(--mp-text-dim); font-weight: 600; margin-top: 4px; }
    .in { padding: 8px; border: 1px solid var(--mp-border); border-radius: var(--radius-1); font: inherit; width: 100%; }
    .simbtns { display: flex; gap: var(--sp-2); margin-bottom: var(--sp-3); }
    .payinfo { margin-top: var(--sp-4); font-size: var(--fs-sm); display: grid; gap: 3px; }
    .mono { font-family: monospace; }
  `],
})
export class MerchantAccept implements OnInit {
  private readonly api = inject(MerchantService);

  readonly busy = signal(false);
  readonly lastSig = signal<{ paymentId: string; status: string } | null>(null);
  readonly simSig = signal<{ status: string; label: string } | null>(null);
  readonly infoSig = signal<D.PayInfo | null>(null);
  readonly acceptErr = signal('');

  amount = 60.00; channel = 'QR'; payerRef = '';
  readonly actions = ['confirm', 'duplicate', 'timeout', 'fail'] as const;

  last() { return this.lastSig(); }
  lastId() { return this.lastSig()?.paymentId ?? null; }
  simRes() { return this.simSig(); }
  info() { return this.infoSig(); }

  ngOnInit() { void this.api.payInfo().then((p) => this.infoSig.set(p)).catch(() => null); }

  async create() {
    this.acceptErr.set('');
    this.busy.set(true);
    try {
      const out = await this.api.acceptPayment({ amount: Number(this.amount), channel: this.channel, payerRef: this.payerRef || undefined });
      this.lastSig.set({ paymentId: out.paymentId, status: 'PENDING' });
    } catch (e) { this.acceptErr.set(wireMsg(e)); }
    finally { this.busy.set(false); }
  }

  async simulate(action: string) {
    const id = this.lastId();
    if (!id) return;
    const res = await this.api.simulate(id, action);
    this.simSig.set({
      status: res.status ?? (action === 'confirm' ? 'CONFIRMED' : action === 'fail' ? 'FAILED' : 'PENDING'),
      label: (res as { label?: string; detail?: string }).label ?? (res as { detail?: string }).detail ?? '',
    });
  }
}

/* =====================================================================
 * M11 - payments list / M10 detail
 * ===================================================================== */
@Component({
  selector: 'merchant-payments',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PageHeader, MpTable],
  template: `
    <page-header title="Payments" subtitle="Every payment with channel and status (M11)" />
    <mp-table [cols]="cols" [rows]="rows()" [selectable]="true" (rowClick)="open($event)" empty="No payments yet - accept one first" loading="Loading payments..." />
  `,
})
export class MerchantPayments implements OnInit {
  private readonly api = inject(MerchantService);
  private readonly router = inject(Router);
  readonly cols: MpColumn[] = [
    { key: 'id', label: 'Ref', type: 'link' },
    { key: 'channel', label: 'Channel' },
    { key: 'amount', label: 'Amount', type: 'money', align: 'right' },
    { key: 'payerRef', label: 'Payer' },
    { key: 'createdAt', label: 'Created', type: 'time' },
    { key: 'status', label: 'Status', type: 'status' },
  ];
  private readonly rowsSig = signal<D.PaymentRow[] | null>(null);
  rows() { return this.rowsSig(); }
  ngOnInit() { void this.api.payments().then((r) => this.rowsSig.set(r)); }
  open(id: string) { void this.router.navigate(['/merchant/payments', id]); }
}

@Component({
  selector: 'merchant-payment-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CommonModule, MpCard, PageHeader, KpiRow, EventTimeline, MpSpinner, MpButton, FormsModule, MpPerm],
  template: `
    @if (p(); as pay) {
      <page-header [title]="'Payment ' + pay.id.slice(0, 8)" subtitle="M10 - full trail with issuer events and idempotency keys (TC-ACC-05)" />
      <kpi-row [tiles]="[
        { label: 'Amount', amount: pay.amount, sub: pay.channel + ' - ' + (pay.payerRef ?? '') },
        { label: 'Status', amount: pay.status, money: false, sub: 'single confirmation guarantee' },
      ]" />
      <mp-card title="Event trail" class="mt">
        <event-timeline [events]="pay.events ?? []" />
      </mp-card>
      <mp-card title="Refund (M12 - full or partial, card 2-phase only)" class="mt">
        <div class="row">
          <input class="in" [(ngModel)]="refundAmount" type="number" placeholder="Amount" />
          <input class="in" [(ngModel)]="refundReason" placeholder="Reason" />
          <mp-button *mpPerm="'merchant.payment:execute'" (onClick)="refund()">Refund</mp-button>
        </div>
        @if (refundMsg(); as m) {<div class="dim mt">{{ m }}</div>}
      </mp-card>
    } @else { <mp-spinner label="Loading payment..." /> }
  `,
  styles: [`
    .row { display: flex; gap: var(--sp-3); align-items: center; }
    .in { padding: 8px; border: 1px solid var(--mp-border); border-radius: var(--radius-1); font: inherit; flex: 1; }
  `],
})
export class MerchantPaymentDetail implements OnInit {
  private readonly api = inject(MerchantService);
  private readonly route = inject(ActivatedRoute);
  private readonly pSig = signal<D.PaymentDetailRow | null>(null);
  private readonly refundSig = signal('');
  p() { return this.pSig(); }
  refundMsg() { return this.refundSig(); }
  refundAmount = 0; refundReason = '';

  ngOnInit() {
    const id = this.route.snapshot.paramMap.get('id')!;
    void this.api.paymentDetail(id).then((x) => this.pSig.set(x));
  }

  async refund() {
    const p = this.pSig();
    if (!p) return;
    try {
      const r = await this.api.refund(p.id, Number(this.refundAmount), this.refundReason || 'customer refund');
      this.refundSig.set('Refund ' + r.status + (r.status === 'FAILED' ? ' - only card 2-phase payments refund (wallet payments are push, not pull)' : ''));
    } catch (e) { this.refundSig.set(wireMsg(e)); }
  }
}

/* =====================================================================
 * M05 balance + M26 withdrawal + ledger
 * ===================================================================== */
@Component({
  selector: 'merchant-balance',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CommonModule, MpCard, PageHeader, KpiRow, MpTable, MpSpinner, MpEntityForm, MpPerm],
  template: `
    @if (bal(); as b) {
      <page-header title="Balance & withdrawal" subtitle="M05: confirmed / pending / held / owed + M26 release to the issuer bank account" />
      <kpi-row [tiles]="[
        { label: 'Available', amount: b.available, sub: b.issuerAccountRef ?? '' },
        { label: 'Pending', amount: b.pending, sub: 'not spendable (TC-BAL-02)' },
        { label: 'Held', amount: b.held, sub: 'invoice holds reduce available' },
        { label: 'Advance owed', amount: b.owed, sub: '10% daily collections' },
      ]" />
      <div class="grid2 mt">
        <mp-entity-form #wf [schema]="withdrawSchema" submitLabel="Request withdrawal" *mpPerm="'merchant.withdrawal:execute'" (submitted)="withdraw($event)" />
        <mp-card title="Withdrawal history">
          <mp-table [cols]="wdCols" [rows]="withdrawals() ?? []" empty="No withdrawals yet" />
        </mp-card>
      </div>
      <mp-card title="Wallet movement" class="mt">
        <mp-table [cols]="ledgerCols" [rows]="ledger()" empty="No movement yet" loading="Loading ledger (paged projection)..." />
      </mp-card>
    } @else { <mp-spinner label="Loading balance..." /> }
  `,
  styles: [`
    .mt { margin-top: var(--sp-4); }
  `],
})
export class MerchantBalance implements OnInit {
  private readonly api = inject(MerchantService);
  readonly withdrawSchema: MpFieldSchema[] = [
    { key: 'amount', label: 'Withdrawal amount (N$)', kind: 'number', required: true, hint: 'withdrawal cap + recon gate apply on release' },
  ];
  readonly wdCols: MpColumn[] = [
    { key: 'requestedAt', label: 'Requested', type: 'time' },
    { key: 'amount', label: 'Amount', type: 'money', align: 'right' },
    { key: 'status', label: 'Status', type: 'status' },
  ];
  readonly ledgerCols: MpColumn[] = [
    { key: 'createdAt', label: 'When', type: 'time' },
    { key: 'entryType', label: 'Type' },
    { key: 'amount', label: 'Amount', type: 'money', align: 'right' },
    { key: 'source', label: 'Source' },
    { key: 'reference', label: 'Source ref' },
    { key: 'status', label: 'Status', type: 'status' },
  ];

  private readonly balSig = signal<D.BalanceView | null>(null);
  private readonly ledSig = signal<D.LedgerRow[] | null>(null);
  private readonly wdSig = signal<D.WithdrawalRow[] | null>(null);
  bal() { return this.balSig(); }
  ledger() { return this.ledSig(); }
  withdrawals() { return this.wdSig(); }

  private readonly wf = viewChild('wf');

  ngOnInit() { void this.refresh(); }
  refresh() {
    void this.api.balance().then((b) => this.balSig.set(b));
    void this.api.ledger().then((r) => this.ledSig.set(r));
    void this.api.withdrawals().then((r) => this.wdSig.set(r));
  }

  async withdraw(v: Record<string, string>) {
    try {
      const w = await this.api.withdraw(Number(v['amount'] ?? 0));
      (this.wf() as MpEntityForm).done((w as { message?: string }).message ?? 'Withdrawal paid to bank (SIMULATED payout)');
      this.refresh();
    } catch (e) {
      (this.wf() as MpEntityForm).failWire(e);
      this.refresh();
    }
  }
}

/* =====================================================================
 * M13-M15 orders + M16-M19 pay supplier invoices (holds)
 * ===================================================================== */
@Component({
  selector: 'merchant-orders',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpAlert, PageHeader, MpTable, MpCard, MpButton, MpSpinner, CurrencyAmount, FormsModule, MpPerm],
  template: `
    <page-header title="Stock orders & supplier invoices" subtitle="M13 order from catalogue, M16 pay invoice from balance (funds held, then settled atomically)" />
    <div class="grid2 mt">
      <mp-card title="Stock catalogue">
        @if (catalogue(); as c) {
          <mp-table [cols]="catCols" [rows]="c" empty="No approved catalogue items" />
          <div class="row mt">
            <label class="dim">Order from:</label>
            <select class="in" [(ngModel)]="orderItemId">
              <option value="">Choose item...</option>
              @for (i of c; track i.id) { <option [value]="i.supplierId + ':' + i.id">{{ i.productName }} at {{ i.unitPrice }}</option> }
            </select>
            <mp-button *mpPerm="'merchant.order:create'" (onClick)="placeOrder()" [disabled]="!orderItemId">Order 1 unit</mp-button>
          </div>
        } @else { <mp-spinner label="Loading catalogue..." /> }
      </mp-card>
      <mp-card title="Pay supplier invoice">
        @if (invoices(); as invs) {
          <mp-table [cols]="invCols" [rows]="invs" [selectable]="true" (rowClick)="selInvId.set($event)" empty="No open supplier invoices" />
          @if (selInv(); as inv) {
            <div class="kvrow"><span class="dim">Ref</span><b>{{ inv.uniqueReference }}</b></div>
            <div class="kvrow"><span class="dim">Amount</span><currency-amount [amount]="inv.amount" /></div>
            <div class="row mt">
              <mp-button *mpPerm="'merchant.invoice:execute'" (onClick)="payInvoice('EFT')">Pay via EFT</mp-button>
              <mp-button *mpPerm="'merchant.invoice:execute'" variant="ghost" (onClick)="payInvoice('BOOK_TRANSFER')">Pay via book transfer</mp-button>
            </div>
            <p class="dim">Native IPP is NOT routed for B2B at go-live (BOOK_TRANSFER/EFT only).</p>
            @if (payMsg(); as m) {<mp-alert kind="ok">{{ m }}</mp-alert>}
          } @else { <p class="dim">Select an invoice to pay (from your balance; funds held until the supplier bank transfer clears).</p> }
        } @else { <mp-spinner label="Loading invoices..." /> }
      </mp-card>
    </div>
    <mp-card title="Orders" class="mt">
      <mp-table [cols]="orderCols" [rows]="orders()" empty="No orders placed yet" loading="Loading orders..." />
    </mp-card>
  `,
  styles: [`
    .grid2 { display: grid; grid-template-columns: 1.1fr 1fr; gap: var(--sp-4); align-items: start; }
    .row { display: flex; align-items: center; gap: var(--sp-2); }
    .in { padding: 8px; border: 1px solid var(--mp-border); border-radius: var(--radius-1); font: inherit; min-width: 190px; }
    .kvrow { display: flex; gap: var(--sp-3); padding: 2px 0; }
  `],
})
export class MerchantOrders implements OnInit {
  private readonly api = inject(MerchantService);
  orderItemId = '';
  readonly catCols: MpColumn[] = [
    { key: 'productName', label: 'Product' },
    { key: 'unitPrice', label: 'Unit price', type: 'money', align: 'right' },
    { key: 'status', label: 'Status', type: 'status' },
  ];
  readonly invCols: MpColumn[] = [
    { key: 'uniqueReference', label: 'Ref' },
    { key: 'counterpartyName', label: 'Supplier' },
    { key: 'amount', label: 'Amount', type: 'money', align: 'right' },
    { key: 'status', label: 'Status', type: 'status' },
  ];
  readonly orderCols: MpColumn[] = [
    { key: 'id', label: 'Order' },
    { key: 'supplierName', label: 'Supplier' },
    { key: 'total', label: 'Total', type: 'money', align: 'right' },
    { key: 'status', label: 'Status', type: 'status' },
    { key: 'createdAt', label: 'When', type: 'time' },
  ];

  private readonly catSig = signal<D.CatalogueRow[] | null>(null);
  private readonly invSig = signal<D.InvoiceListRow[] | null>(null);
  private readonly ordersSig = signal<D.OrderRow[] | null>(null);
  readonly selInvId = signal<string | null>(null);
  readonly selInv = computed(() => (this.invSig() ?? []).find((i) => i.id === this.selInvId()) ?? null);
  private readonly payMsgSig = signal('');
  catalogue() { return this.catSig(); }
  invoices() { return this.invSig(); }
  orders() { return this.ordersSig(); }
  payMsg() { return this.payMsgSig(); }

  ngOnInit() { void this.refresh(); }
  refresh() {
    void this.api.catalogue().then((c) => this.catSig.set(c));
    void this.api.invoices().then((i) => this.invSig.set(i));
    void this.api.orders().then((o) => this.ordersSig.set(o));
  }

  async placeOrder() {
    if (!this.orderItemId) return;
    const idx = this.orderItemId.indexOf(':');
    const supplierId = this.orderItemId.slice(0, idx);
    const itemId = this.orderItemId.slice(idx + 1);
    try {
      await this.api.placeOrder(supplierId, [{ catalogueItemId: itemId, quantity: 1 }]);
      this.refresh();
    } catch (e) { alert('Order failed: ' + wireMsg(e)); }
  }

  async payInvoice(route: string) {
    const inv = this.selInv();
    if (!inv) return;
    try {
      const r = await this.api.payInvoice(inv.id, route);
      this.payMsgSig.set((r as unknown as { label?: string }).label ?? 'Invoice payment simulated (one debit, one credit, invoice held then settled)');
      this.refresh();
    } catch (e) { alert('Pay failed: ' + wireMsg(e)); }
  }
}

/* =====================================================================
 * K05 inventory + alerts
 * ===================================================================== */
@Component({
  selector: 'merchant-inventory',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PageHeader, MpTable, MpEntityForm, MpCard, MpPerm],
  template: `
    <page-header title="Inventory" subtitle="K05: stock movement ledger + K06 low-stock warnings" />
    <mp-card title="Stock items" class="mt">
      <mp-table [cols]="cols" [rows]="items()" empty="No stock items - add the first one below" loading="Loading inventory..." />
    </mp-card>
    <div class="grid2 mt">
      <mp-entity-form #af [schema]="addItemSchema" submitLabel="Add item" *mpPerm="'merchant.inventory:create'" (submitted)="addItem($event)" />
      <mp-entity-form #mf [schema]="moveSchema" submitLabel="Record movement" *mpPerm="'merchant.inventory:modify'" (submitted)="move($event)" />
    </div>
  `,
  styles: [`
    .mt { margin-top: var(--sp-4); }
  `],
})
export class MerchantInventory implements OnInit {
  private readonly api = inject(MerchantService);
  readonly cols: MpColumn[] = [
    { key: 'name', label: 'Item' },
    { key: 'unit', label: 'Unit' },
    { key: 'currentStock', label: 'Stock', align: 'right' },
    { key: 'reorderLevel', label: 'Reorder at', align: 'right' },
    { key: 'state', label: 'State', type: 'status' },
  ];
  readonly addItemSchema: MpFieldSchema[] = [
    { key: 'name', label: 'Item name', kind: 'text', required: true },
    { key: 'unit', label: 'Unit (each/box/kg)', kind: 'text' },
    { key: 'reorderLevel', label: 'Reorder at (qty)', kind: 'number' },
    { key: 'openingStock', label: 'Opening stock', kind: 'number' },
  ];
  readonly moveSchema: MpFieldSchema[] = [
    { key: 'itemId', label: 'Item id (from table)', kind: 'text', required: true },
    { key: 'movementType', label: 'Movement', kind: 'select', required: true,
      options: [{ value: 'SALE', label: 'Sale' }, { value: 'DELIVERY', label: 'Delivery' },
                { value: 'ADJUSTMENT', label: 'Adjustment' }, { value: 'WASTE', label: 'Waste' }] },
    { key: 'quantity', label: 'Quantity', kind: 'number', required: true },
    { key: 'reference', label: 'Reference', kind: 'text' },
  ];

  private readonly itemsSig = signal<D.InventoryRow[] | null>(null);
  items() { return this.itemsSig(); }
  private readonly af = viewChild('af');
  private readonly mf = viewChild('mf');

  ngOnInit() { void this.refresh(); }
  refresh() { void this.api.inventory().then((i) => this.itemsSig.set(i)); }

  async addItem(v: Record<string, string>) {
    try {
      await this.api.addInventoryItem(v['name'] ?? '', v['unit'] ?? 'each', v['reorderLevel'] ?? '0', v['openingStock'] ?? '0');
      (this.af() as MpEntityForm).done('Item added');
      this.refresh();
    } catch (e) { (this.af() as MpEntityForm).failWire(e); }
  }

  async move(v: Record<string, string>) {
    try {
      await this.api.adjustStock({
        itemId: v['itemId'] ?? '', movementType: v['movementType'] ?? 'SALE',
        quantity: Number(v['quantity'] ?? 0), reference: v['reference'] || undefined,
      });
      (this.mf() as MpEntityForm).done('Stock movement recorded');
      this.refresh();
    } catch (e) { (this.mf() as MpEntityForm).failWire(e); }
  }
}

/* =====================================================================
 * M30-M35 khata (credit book)
 * ===================================================================== */
@Component({
  selector: 'merchant-khata',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpAlert, PageHeader, MpTable, MpSpinner, MpEntityForm, MpButton, MpCard, CurrencyAmount, StatusBadge, MpPerm],
  template: `
    <page-header title="Khata (credit book)" subtitle="M30: customer credit notes - NEVER in lender feeds (business rule: khata excluded)" />
    <mp-card title="Customer accounts" class="mt">
      @if (rows(); as r) {
        <mp-table [cols]="cols" [rows]="r" [selectable]="true" (rowClick)="select($event)" empty="No khata customers yet" />
        @if (sel(); as k) {
          <div class="row mt"><b>{{ k.customerName }}</b><span>outstanding</span><currency-amount [amount]="k.outstanding" /><status-badge [status]="k.status" /></div>
          @if (entries(); as e) {
            <mp-table [cols]="entryCols" [rows]="e" empty="No entries yet" />
          }
          <mp-button *mpPerm="'merchant.khata:execute'" class="mt" variant="ghost" (onClick)="remind(k.id)">Send reminder (CU02)</mp-button>
        }
      } @else { <mp-spinner label="Loading khata..." /> }
    </mp-card>
    <mp-entity-form #kf class="mt" [schema]="createSchema" submitLabel="Create khata customer" *mpPerm="'merchant.khata:create'" (submitted)="create($event)" />
    @if (sel()) {
      <mp-entity-form #ef class="mt" [schema]="entrySchema" submitLabel="Add entry" *mpPerm="'merchant.khata:create'" (submitted)="addEntry($event)" />
    }
    @if (msg(); as m) {<mp-alert kind="ok">{{ m }}</mp-alert>}
  `,
  styles: [`
    .row { display: flex; align-items: center; gap: var(--sp-3); flex-wrap: wrap; }
  `],
})
export class MerchantKhata implements OnInit {
  private readonly api = inject(MerchantService);
  readonly cols: MpColumn[] = [
    { key: 'customerName', label: 'Customer' },
    { key: 'customerPhone', label: 'Phone' },
    { key: 'outstanding', label: 'Outstanding', type: 'money', align: 'right' },
    { key: 'status', label: 'Status', type: 'status' },
  ];
  readonly entryCols: MpColumn[] = [
    { key: 'entryType', label: 'Type' },
    { key: 'amount', label: 'Amount', type: 'money', align: 'right' },
    { key: 'dueDate', label: 'Due', type: 'date' },
    { key: 'settledAt', label: 'Settled', type: 'date' },
  ];
  readonly createSchema: MpFieldSchema[] = [
    { key: 'customerName', label: 'Customer name', kind: 'text', required: true },
    { key: 'customerPhone', label: 'Customer phone', kind: 'text', required: true },
  ];
  readonly entrySchema: MpFieldSchema[] = [
    { key: 'entryType', label: 'Entry', kind: 'select', required: true,
      options: [{ value: 'CHARGE', label: 'Charge' }, { value: 'PAYMENT', label: 'Payment' }] },
    { key: 'amount', label: 'Amount (N$)', kind: 'number', required: true },
    { key: 'dueDate', label: 'Due date', kind: 'text', hint: 'YYYY-MM-DD' },
  ];

  private readonly rowsSig = signal<D.KhataRow[] | null>(null);
  private readonly selSig = signal<D.KhataRow | null>(null);
  private readonly entriesSig = signal<D.KhataEntryRow[] | null>(null);
  private readonly msgSig = signal('');
  rows() { return this.rowsSig(); }
  sel() { return this.selSig(); }
  entries() { return this.entriesSig(); }
  msg() { return this.msgSig(); }

  private readonly ef = viewChild('ef');
  private readonly kf = viewChild('kf');

  ngOnInit() { void this.refresh(); }
  refresh() {
    void this.api.khata().then((k) => this.rowsSig.set(k));
    const s = this.selSig();
    if (s) void this.api.khataEntries(s.id).then((e) => this.entriesSig.set(e));
  }

  select(id: string) {
    const row = (this.rowsSig() ?? []).find((x) => x.id === id) ?? null;
    this.selSig.set(row);
    if (row) void this.api.khataEntries(row.id).then((e) => this.entriesSig.set(e));
  }

  async create(v: Record<string, string>) {
    try {
      await this.api.createKhata(v['customerName'] ?? '', v['customerPhone'] ?? '');
      (this.kf() as MpEntityForm).done('Khata customer created');
      this.refresh();
    } catch (e) { (this.kf() as MpEntityForm).failWire(e); }
  }

  async addEntry(v: Record<string, string>) {
    const k = this.selSig();
    if (!k) return;
    try {
      await this.api.addKhataEntry(k.id, {
        entryType: v['entryType'] ?? 'CHARGE', amount: Number(v['amount'] ?? 0), dueDate: v['dueDate'] || undefined,
      } as { entryType: string; amount: number; dueDate?: string });
      (this.ef() as MpEntityForm).done('Entry recorded');
      this.refresh();
    } catch (e) { (this.ef() as MpEntityForm).failWire(e); }
  }

  async remind(id: string) {
    const res = await this.api.remindKhata(id);
    this.msgSig.set((res.channel ?? 'SMS') + ' to ' + res.to + ': ' + res.body);
  }
}

/* =====================================================================
 * M36/M37 tabs (2-tap open tab + reminders)
 * ===================================================================== */
@Component({
  selector: 'merchant-tabs',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpAlert, PageHeader, MpTable, MpSpinner, MpEntityForm, MpButton, MpCard, FormsModule, MpPerm],
  template: `
    <page-header title="Tabs" subtitle="M36: open a customer tab in two taps; M37: one tap to settle or remind (M37)" />
    <mp-card title="Open tabs" class="mt">
      @if (rows(); as r) {
        <mp-table [cols]="cols" [rows]="r" [selectable]="true" (rowClick)="select($event)" empty="No tabs yet - two taps to create one" />
        @if (selId(); as id) {
          <div class="row mt">
            <mp-button *mpPerm="'merchant.tab:execute'" (onClick)="settle(id)">Settle (M37)</mp-button>
            <mp-button *mpPerm="'merchant.tab:execute'" variant="ghost" (onClick)="remind(id)">Remind</mp-button>
          </div>
          @if (entries(); as e) {
            <mp-table [cols]="entryCols" [rows]="e" empty="No entries yet" />
          }
        }
      } @else { <mp-spinner label="Loading tabs..." /> }
    </mp-card>
    <mp-entity-form #tf class="mt" [schema]="createSchema" submitLabel="Create tab" *mpPerm="'merchant.tab:create'" (submitted)="create($event)" />
    @if (msg(); as m) {<mp-alert kind="ok">{{ m }}</mp-alert>}
  `,
  })
export class MerchantTabs implements OnInit {
  private readonly api = inject(MerchantService);
  readonly cols: MpColumn[] = [
    { key: 'customerName', label: 'Customer' },
    { key: 'totalAmount', label: 'Total', type: 'money', align: 'right' },
    { key: 'dueDate', label: 'Due', type: 'date' },
    { key: 'status', label: 'Status', type: 'status' },
  ];
  readonly entryCols: MpColumn[] = [
    { key: 'amount', label: 'Entry', type: 'money', align: 'right' },
    { key: 'description', label: 'For' },
    { key: 'createdAt', label: 'When', type: 'time' },
  ];
  readonly createSchema: MpFieldSchema[] = [
    { key: 'customerName', label: 'Customer name', kind: 'text', required: true },
    { key: 'customerPhone', label: 'Customer phone', kind: 'text' },
    { key: 'amount', label: 'Amount (N$)', kind: 'number', required: true },
    { key: 'description', label: 'For (e.g. airtime)', kind: 'text' },
    { key: 'dueDate', label: 'Due date', kind: 'text', hint: 'YYYY-MM-DD' },
  ];

  private readonly rowsSig = signal<D.TabRow[] | null>(null);
  private readonly selSig = signal<string | null>(null);
  private readonly entriesSig = signal<D.TabEntryRow[] | null>(null);
  private readonly msgSig = signal('');
  rows() { return this.rowsSig(); }
  selId() { return this.selSig(); }
  entries() { return this.entriesSig(); }
  msg() { return this.msgSig(); }

  private readonly tf = viewChild('tf');

  ngOnInit() { void this.refresh(); }
  refresh() { void this.api.tabs().then((t) => this.rowsSig.set(t)); }

  select(id: string) {
    this.selSig.set(id);
    void this.api.tabEntries(id).then((e) => this.entriesSig.set(e));
  }

  async create(v: Record<string, string>) {
    try {
      await this.api.createTab({
        customerName: v['customerName'] ?? '', customerPhone: v['customerPhone'] ?? '',
        amount: Number(v['amount'] ?? 0), description: v['description'] || undefined,
        dueDate: v['dueDate'] || undefined,
      });
      (this.tf() as MpEntityForm).done('Tab created');
      this.refresh();
    } catch (e) { (this.tf() as MpEntityForm).failWire(e); }
  }

  async settle(id: string) {
    try { await this.api.settleTab(id); this.refresh(); }
    catch (e) { this.msgSig.set('Settle failed: ' + wireMsg(e)); }
  }

  async remind(id: string) {
    const res = await this.api.remindTab(id);
    this.msgSig.set((res.channel ?? 'SMS') + ' to ' + res.to + ': ' + res.body);
  }
}

/* =====================================================================
 * M21-M25 advance: consent -> eligibility -> offer -> accept/fund -> repay
 * ===================================================================== */
@Component({
  selector: 'merchant-advance',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpAlert, PageHeader, MpTable, MpSpinner, MpButton, MpCard, CurrencyAmount, StatusBadge, FormsModule, KpiRow, SimulatedBanner, MpPerm],
  template: `
    <page-header title="Cash advance" subtitle="M21-M25: cap = lesser of N$25 000 / 20% of avg monthly net receipts / lender limit; repayment 10% of eligible daily receipts (TC-ADV)" />
    <simulated-banner class="mt-block" />

    <mp-card title="1. Grant a consent (L01 prerequisite)" class="mt">
      @if (lenders(); as ls) {
        <div class="row">
          <select class="in" [(ngModel)]="consentLenderId">
            <option value="">Choose lender...</option>
            @for (l of ls; track l.id) { <option [value]="l.id">{{ l.name }}</option> }
          </select>
          <mp-button *mpPerm="'merchant.advance:create'" (onClick)="grant()" [disabled]="!consentLenderId">Grant data consent</mp-button>
        </div>
      } @else { <mp-spinner label="Loading lenders (one call)..." /> }
      @if (consents(); as cs) {
        <mp-table [cols]="consentCols" [rows]="cs" class="mt" empty="No consents yet" />
      }
      @if (msg(); as m) {<mp-alert kind="ok">{{ m }}</mp-alert>}
    </mp-card>

    <mp-card title="2. Eligibility & request assessment" class="mt">
      @if (elig(); as e) {
        <kpi-row [tiles]="[
          { label: '90d eligible total', amount: e.eligibleTotal, sub: e.tradingDays + ' trading days' },
          { label: 'Avg monthly net', amount: e.avgMonthlyNetReceipts, sub: '20% of this drives the cap' },
        ]" />
        <p class="dim">Excluded: khata, refunds, circular/self transfers (rules applied: {{ e.rulesApplied.join(', ') }})</p>
      }
      <div class="row mt">
        <mp-button *mpPerm="'merchant.advance:submit'" (onClick)="assess()" [disabled]="!consentLenderId">Request assessment</mp-button>
      </div>
      @if (offer(); as a) {
        <div class="offer mt">
          <div class="row">
            <status-badge [status]="a.decision === 'APPROVED' ? 'OFFERED' : 'DECLINED'" />
            <span>Offer <currency-amount [amount]="a.offerPrincipal" /> (cap: lesser of 20% x avg monthly {{ 'N$ ' + (a.avgMonthlyNetReceipts * 0.2).toFixed(2) }})</span>
          </div>
          <div>Repay <currency-amount [amount]="a.offerRepayment" /> at {{ (a.repaymentRate * 100).toFixed(0) }}%/day</div>
          <div class="dim">{{ a.reasonCodes.join(' - ') }}</div>
        </div>
      }
    </mp-card>

    <mp-card title="3. Current advance" class="mt">
      @if (adv(); as v) {
        @if (v.advance; as a) {
          <div class="row">
            <status-badge [status]="a.status" [simulated]="a.status === 'FUNDED' || a.status === 'ACTIVE' ? 'lender disbursement simulated' : false" />
            <b>{{ money(a.principalAmount) }}</b> <span>repay {{ money(a.totalRepayment) }}</span>
            <span class="dim">rate: {{ rateText(a.repaymentRate) }}</span>
          </div>
          <mp-button *mpPerm="'merchant.advance:authorize'" class="mt" (onClick)="accept()" [disabled]="a.status !== 'OFFERED'">Accept offer</mp-button>
          @if (v.repayments?.length) {
            <mp-table [cols]="repayCols" [rows]="v.repayments" class="mt" empty="No collections yet" />
          }
        } @else { <p class="dim">No advance yet.</p> }
      } @else { <p class="dim">No advance yet - request an assessment above.</p> }
    </mp-card>
  `,
  styles: [`
    .row { display: flex; align-items: center; gap: var(--sp-3); flex-wrap: wrap; }
    .in { padding: 8px; border: 1px solid var(--mp-border); border-radius: var(--radius-1); font: inherit; min-width: 240px; }
    .offer { display: grid; gap: 4px; justify-items: start; }
  `],
})
export class MerchantAdvance implements OnInit {
  private readonly api = inject(MerchantService);
  consentLenderId = '';
  money(v: unknown): string { return money(Number(v ?? 0)); }

  readonly consentCols: MpColumn[] = [
    { key: 'consentId', label: 'Consent' },
    { key: 'purpose', label: 'Purpose' },
    { key: 'status', label: 'Status', type: 'status' },
    { key: 'grantedAt', label: 'Granted', type: 'time' },
  ];
  readonly repayCols: MpColumn[] = [
    { key: 'collectionDate', label: 'Day', type: 'date' },
    { key: 'amount', label: 'Collected', type: 'money', align: 'right' },
    { key: 'eligibleSalesBase', label: 'Base', type: 'money', align: 'right' },
  ];

  private readonly lendersSig = signal<D.LendersView[] | null>(null);
  private readonly consentsSig = signal<D.ConsentRow[] | null>(null);
  private readonly eligSig = signal<D.EligibilityView | null>(null);
  private readonly offerSig = signal<D.AssessmentView | null>(null);
  private readonly advSig = signal<D.AdvanceDetailView | null>(null);
  private readonly msgSig = signal('');
  lenders() { return this.lendersSig(); }
  consents() { return this.consentsSig(); }
  elig() { return this.eligSig(); }
  offer() { return this.offerSig(); }
  adv() { return this.advSig(); }
  msg() { return this.msgSig(); }

  ngOnInit() { void this.refresh(); }
  refresh() {
    void this.api.lenders().then((l) => this.lendersSig.set(l));
    void this.api.consents().then((c) => this.consentsSig.set(c));
    void this.api.eligibility().then((e) => this.eligSig.set(e)).catch(() => null);
    void this.api.currentAdvance().then((a) => this.advSig.set(a)).catch(() => this.advSig.set(null));
  }

  async grant() {
    if (!this.consentLenderId) return;
    try {
      await this.api.grantConsent(this.consentLenderId);
      this.msgSig.set('Consent granted - the lender can now view 90-day records');
      this.refresh();
    } catch (e) { this.msgSig.set('Consent failed: ' + wireMsg(e)); }
  }

  async assess() {
    if (!this.consentLenderId) { this.msgSig.set('Grant a consent first'); return; }
    try {
      const a = await this.api.requestAdvance(this.consentLenderId, 'ADVANCE');
      this.offerSig.set(a);
      this.refresh();
    } catch (e) { this.msgSig.set('Assessment failed: ' + wireMsg(e)); }
  }

  async accept() {
    const a = this.advSig()?.advance;
    if (!a) return;
    try {
      await this.api.acceptAdvance(a.id);
      this.msgSig.set('Offer accepted - awaiting SIMULATED lender funding');
      this.refresh();
    } catch (e) { this.msgSig.set('Accept failed: ' + wireMsg(e)); }
  }

  rateText(r: number): string { return Math.round(r * 100) + '% daily on eligible receipts'; }
}

/* =====================================================================
 * M27 settings/devices / M28 statements
 * ===================================================================== */
@Component({
  selector: 'merchant-settings',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PageHeader, MpTable, MpSpinner, MpCard],
  template: `
    <page-header title="Settings" subtitle="M27: MFA policy, trusted devices, receive channels (C03, X05)" />
    @if (s(); as v) {
      <div class="grid2 mt">
        <mp-card title="Receive channels">
          <div>Short code: <b>{{ v.shortCode || '-' }}</b></div>
          <div class="mono">QR: {{ v.qrPayload || '-' }}</div>
        </mp-card>
        <mp-card title="Trusted devices (last 20)">
          <mp-table [cols]="devCols" [rows]="v.devices ?? []" empty="No devices yet" />
        </mp-card>
      </div>
    } @else { <mp-spinner label="Loading settings..." /> }
  `,
  styles: [`
    .mono { font-family: monospace; font-size: var(--fs-sm); }
    .mt { margin-top: var(--sp-4); }
  `],
})
export class MerchantSettings implements OnInit {
  private readonly api = inject(MerchantService);
  readonly devCols: MpColumn[] = [
    { key: 'deviceFingerprint', label: 'Device' },
    { key: 'mfaMethod', label: 'MFA' },
    { key: 'expiresAt', label: 'Expires', type: 'time' },
  ];
  private readonly sSig = signal<D.SettingsView | null>(null);
  s() { return this.sSig(); }
  ngOnInit() { void this.api.settings().then((v) => this.sSig.set(v)); }
}

@Component({
  selector: 'merchant-statements',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PageHeader, MpTable, MpEntityForm, MpCard, MpPerm],
  template: `
    <page-header title="Statements" subtitle="M28: generate for a period; downloads valid 60 minutes (C04)" />
    <mp-entity-form #gf [schema]="genSchema" submitLabel="Generate statement" *mpPerm="'merchant.statement:execute'" (submitted)="generate($event)" class="mt" />
    <mp-card title="Available statements" class="mt">
      <mp-table [cols]="cols" [rows]="rows()" [selectable]="true" (rowClick)="download($event)" empty="No statements yet" loading="Loading statements..." />
      @if (dl(); as t) { <pre class="wire">{{ t }}</pre> }
    </mp-card>
  `,
  styles: [`
    .wire { background: var(--mp-bg); padding: var(--sp-3); border-radius: var(--radius-1); font-size: var(--fs-sm); margin-top: var(--sp-3); max-height: 320px; overflow: auto; white-space: pre; }
    .mt { margin-top: var(--sp-4); }
  `],
})
export class MerchantStatements implements OnInit {
  private readonly api = inject(MerchantService);
  readonly genSchema: MpFieldSchema[] = [
    { key: 'from', label: 'From (YYYY-MM-DD)', kind: 'text', required: true },
    { key: 'to', label: 'To (YYYY-MM-DD)', kind: 'text', required: true },
  ];
  readonly cols: MpColumn[] = [
    { key: 'periodStart', label: 'From', type: 'date' },
    { key: 'periodEnd', label: 'To', type: 'date' },
    { key: 'generatedAt', label: 'Generated', type: 'time' },
  ];
  private readonly rowsSig = signal<D.StatementRow[] | null>(null);
  private readonly dlSig = signal('');
  rows() { return this.rowsSig(); }
  dl() { return this.dlSig(); }

  private readonly gf = viewChild('gf');

  ngOnInit() { void this.refresh(); }
  refresh() { void this.api.statements().then((r) => this.rowsSig.set(r)); }

  async generate(v: Record<string, string>) {
    try {
      await this.api.generateStatement(v['from'] ?? '', v['to'] ?? '');
      (this.gf() as MpEntityForm).done('Statement generated');
      this.refresh();
    } catch (e) { (this.gf() as MpEntityForm).failWire(e); }
  }

  async download(id: string) {
    this.dlSig.set(await this.api.statementText(id));
  }
}
