import { ChangeDetectionStrategy, Component, OnInit, inject, signal, viewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { CurrencyAmount } from '../../design-system/atoms/currency-amount';
import { StatusBadge } from '../../design-system/atoms/status-badge';
import { MpCard } from '../../design-system/atoms/mp-card';
import { MpButton } from '../../design-system/atoms/mp-button';
import { MpSpinner } from '../../design-system/atoms/mp-spinner';
import { EmptyState } from '../../design-system/molecules/empty-state';
import { PageHeader } from '../../design-system/organisms/page-header';
import { MpTable, MpColumn } from '../../design-system/organisms/mp-table';
import { MpEntityForm, MpFieldSchema } from '../../design-system/organisms/mp-entity-form';
import { MpPerm } from '../../../../projects/shared/web-core/perm-directive';
import { SimulatedBanner } from '../../design-system/molecules/simulated-banner';
import { SupplierService } from '../../core/services';
import { KpiRow } from '../../design-system/organisms/kpi-row';
import * as D from '../../core/dto';

function wireMsg(e: unknown): string {
  const err = (e as { error?: { detail?: string; title?: string } })?.error ?? {};
  return String(err['detail'] || err['title'] || 'unknown');
}

/* ================= C21 - supplier home ================= */
@Component({
  selector: 'supplier-home',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PageHeader, MpCard, MpSpinner, StatusBadge, SimulatedBanner],
  template: `
    @if (s(); as sup) {
      <page-header [title]="sup.businessName" subtitle="Your approval status, KYC and activity at a glance" />
      <div class="grid3 mt">
        <mp-card title="Approval status">
          <status-badge [status]="sup.approvalStatus" />
          <div class="dim mt6">KYC: {{ sup.kycStatus }}</div>
          <div class="dim">Owner: {{ sup.ownerName }} ({{ sup.ownerPhone }})</div>
        </mp-card>
        <mp-card title="Open orders">
          <div class="big">{{ counts()?.orders ?? '-' }}</div>
          <div class="dim">waiting on you</div>
        </mp-card>
        <mp-card title="Unpaid invoices">
          <div class="big">{{ counts()?.invoices ?? '-' }}</div>
          <div class="dim">issued to merchants</div>
        </mp-card>
      </div>
    } @else { <mp-spinner label="Loading profile..." /> }
    <simulated-banner />
  `,
  styles: [`
    .big { font-size: var(--fs-xl); font-weight: 700; }
    .mt6 { margin-top: 6px; }
    .grid3 { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--sp-4); }
  `],
})
export class SupplierHome implements OnInit {
  private readonly api = inject(SupplierService);
  private readonly sup = signal<D.MerchantApprovalRow | null>(null);
  private readonly countsSig = signal<{ orders: number; invoices: number } | null>(null);
  s() { return this.sup(); }
  counts() { return this.countsSig(); }

  ngOnInit() {
    void this.api.profile().then((p) => this.sup.set(p));
    void Promise.all([this.api.orders(), this.api.invoices()]).then(([o, i]) =>
      this.countsSig.set({ orders: o.length, invoices: i.filter((x) => x.status === 'PENDING').length }));
  }
}

/* ================= C22 - catalogue management ================= */
@Component({
  selector: 'supplier-catalogue',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PageHeader, MpCard, MpTable, MpSpinner, MpEntityForm, MpPerm],
  template: `
    <page-header title="Catalogue" subtitle="New items go live once MiPay approves them" />
    <mp-card title="My catalogue" class="mt">
      @if (rows(); as r) {
        <mp-table [cols]="cols" [rows]="r" empty="No items yet - add your first product below" />
      } @else { <mp-spinner label="Loading catalogue..." /> }
    </mp-card>
    <mp-entity-form #cf class="mt" [schema]="addSchema" submitLabel="Add catalogue item" *mpPerm="'supplier.catalogue:create'" (submitted)="add($event)" />
  `,
})
export class SupplierCatalogue implements OnInit {
  private readonly api = inject(SupplierService);
  readonly cols: MpColumn[] = [
    { key: 'productName', label: 'Product' },
    { key: 'unit', label: 'Unit' },
    { key: 'unitPrice', label: 'Unit price', type: 'money', align: 'right' },
    { key: 'status', label: 'Status', type: 'status' },
  ];
  readonly addSchema: MpFieldSchema[] = [
    { key: 'productName', label: 'Product name', kind: 'text', required: true },
    { key: 'unit', label: 'Unit (each/box/kg)', kind: 'text', required: true },
    { key: 'unitPrice', label: 'Unit price (N$)', kind: 'number', required: true },
  ];
  private readonly rowsSig = signal<D.CatalogueRow[] | null>(null);
  rows() { return this.rowsSig(); }
  private readonly cf = viewChild('cf');

  ngOnInit() { void this.refresh(); }
  refresh() { void this.api.catalogue().then((c) => this.rowsSig.set(c)); }

  async add(v: Record<string, string>) {
    try {
      await this.api.addToCatalogue({ productName: v['productName'] ?? '', unit: v['unit'] ?? 'each', unitPrice: Number(v['unitPrice'] ?? 0) });
      (this.cf() as unknown as MpEntityForm).done('Item added');
      this.refresh();
    } catch (e) { (this.cf() as unknown as MpEntityForm).failWire(e); }
  }
}

/* ================= C24/C25/C26 - orders + issue invoice ================= */
@Component({
  selector: 'supplier-orders',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PageHeader, MpTable, MpSpinner, MpCard, MpButton, CurrencyAmount, StatusBadge, MpPerm],
  template: `
    <page-header title="Orders & invoices" subtitle="Accept orders and send invoices" />
    <mp-card title="Orders" class="mt">
      @if (orders(); as o) {
        <mp-table [cols]="orderCols" [rows]="o" [selectable]="true" (rowClick)="select($event)" empty="No orders yet" />
      } @else { <mp-spinner label="Loading orders..." /> }
    </mp-card>
    @if (sel(); as order) {
      <mp-card [title]="'Order ' + order.id?.slice(0, 8)" class="mt">
        <div class="row">
          <span class="dim">Merchant:</span><b>{{ order.merchantName }}</b>
          <span class="dim">Total:</span><currency-amount [amount]="order.total" />
          <status-badge [status]="order.status" />
        </div>
        @if (order.items?.length) {
          <mp-table [cols]="itemCols" [rows]="order.items" class="mt" />
        }
        <div class="row mt">
          <mp-button *mpPerm="'supplier.invoice:create'" (onClick)="issue(order.id!)">Send invoice</mp-button>
        </div>
        @if (issued(); as inv) {
          <div class="ok mt">Invoice issued: <b>{{ inv.uniqueReference }}</b> for <currency-amount [amount]="inv.amount" /></div>
        }
      </mp-card>
    }
    <mp-card title="Invoices issued" class="mt">
      @if (invoices(); as i) {
        <mp-table [cols]="invCols" [rows]="i" empty="No invoices yet - accept an order and issue one" />
      } @else { <mp-spinner label="Loading invoices..." /> }
    </mp-card>
  `,
  styles: [`.row { display: flex; gap: var(--sp-3); align-items: center; flex-wrap: wrap; } .ok { color: #16a34a; }`],
})
export class SupplierOrders implements OnInit {
  private readonly api = inject(SupplierService);
  readonly orderCols: MpColumn[] = [
    { key: 'id', label: 'Order' },
    { key: 'merchantName', label: 'Merchant' },
    { key: 'total', label: 'Total', type: 'money', align: 'right' },
    { key: 'status', label: 'Status', type: 'status' },
    { key: 'createdAt', label: 'When', type: 'time' },
  ];
  readonly itemCols: MpColumn[] = [
    { key: 'productName', label: 'Item' },
    { key: 'quantity', label: 'Qty', align: 'right' },
    { key: 'unitPrice', label: 'Unit price', type: 'money', align: 'right' },
    { key: 'lineTotal', label: 'Line total', type: 'money', align: 'right' },
  ];
  readonly invCols: MpColumn[] = [
    { key: 'uniqueReference', label: 'Ref' },
    { key: 'counterpartyName', label: 'Merchant' },
    { key: 'amount', label: 'Amount', type: 'money', align: 'right' },
    { key: 'status', label: 'Status', type: 'status' },
  ];

  private readonly ordersSig = signal<D.OrderRow[] | null>(null);
  private readonly selSig = signal<D.OrderDetailRow | null>(null);
  private readonly invSig = signal<D.InvoiceListRow[] | null>(null);
  private readonly issuedSig = signal<D.InvoiceDetailRow | null>(null);
  orders() { return this.ordersSig(); }
  sel() { return this.selSig(); }
  invoices() { return this.invSig(); }
  issued() { return this.issuedSig(); }

  ngOnInit() { void this.refresh(); }
  refresh() {
    void this.api.orders().then((o) => this.ordersSig.set(o));
    void this.api.invoices().then((i) => this.invSig.set(i));
  }

  async select(id: string) {
    try { this.selSig.set(await this.api.orderDetail(id)); }
    catch (e) { alert('Load failed: ' + wireMsg(e)); }
  }

  async issue(orderId: string) {
    try {
      this.issuedSig.set(await this.api.issueInvoice(orderId));
      this.refresh();
    } catch (e) { alert('Issue failed: ' + wireMsg(e)); }
  }
}

/* ================= C27 - invoice detail ================= */
@Component({
  selector: 'supplier-invoice-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CommonModule, PageHeader, MpCard, KpiRow, MpTable, MpSpinner, CurrencyAmount],
  template: `
    @if (inv(); as i) {
      <page-header [title]="'Invoice ' + i.uniqueReference" subtitle="Payments received against this invoice" />
      <kpi-row [tiles]="[
        { label: 'Amount', amount: i.amount, sub: i.currency },
        { label: 'Status', amount: i.status, money: false, sub: 'single source of truth' },
      ]" />
      <mp-card title="Payments received against this invoice" class="mt">
        <mp-table [cols]="payCols" [rows]="i.payments ?? []" empty="No payments yet" />
      </mp-card>
    } @else { <mp-spinner label="Loading invoice..." /> }
  `,
})
export class SupplierInvoiceDetail implements OnInit {
  private readonly api = inject(SupplierService);
  private readonly route = inject(ActivatedRoute);
  readonly payCols: MpColumn[] = [
    { key: 'createdAt', label: 'When', type: 'time' },
    { key: 'amount', label: 'Amount', type: 'money', align: 'right' },
    { key: 'route', label: 'Route' },
    { key: 'bankReference', label: 'Bank ref' },
    { key: 'status', label: 'Status', type: 'status' },
  ];
  private readonly invSig = signal<D.InvoiceDetailRow | null>(null);
  inv() { return this.invSig(); }

  ngOnInit() {
    const id = this.route.snapshot.paramMap.get('id')!;
    void this.api.invoiceDetail(id).then((x) => this.invSig.set(x));
  }
}

/* ================= C10/C14 - bank accounts (different-approver rule) ================= */
@Component({
  selector: 'supplier-accounts',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PageHeader, MpCard, MpTable, MpSpinner, MpEntityForm, MpPerm],
  template: `
    <page-header title="Receiving accounts" subtitle="Where your payments are paid. A change is approved by someone other than the person who asks for it" />
    <mp-card title="My bank accounts" class="mt">
      @if (rows(); as r) {
        <mp-table [cols]="cols" [rows]="r" empty="No accounts yet - submit one below" />
      } @else { <mp-spinner label="Loading accounts..." /> }
    </mp-card>
    <mp-entity-form #bf class="mt" [schema]="addSchema" submitLabel="Submit account change" *mpPerm="'supplier.bank_account:modify'" (submitted)="change($event)" />
  `,
})
export class SupplierAccounts implements OnInit {
  private readonly api = inject(SupplierService);
  readonly cols: MpColumn[] = [
    { key: 'bankName', label: 'Bank' },
    { key: 'accountNumber', label: 'Account' },
    { key: 'accountHolder', label: 'Holder' },
    { key: 'status', label: 'Status', type: 'status' },
  ];
  readonly addSchema: MpFieldSchema[] = [
    { key: 'bankName', label: 'Bank name', kind: 'select', required: true,
      options: [{ value: 'FNB Namibia', label: 'FNB Namibia' }, { value: 'Bank Windhoek', label: 'Bank Windhoek' },
                { value: 'Standard Bank', label: 'Standard Bank' }, { value: 'Nedbank', label: 'Nedbank' }] },
    { key: 'accountNumber', label: 'Account number', kind: 'text', required: true },
    { key: 'accountHolder', label: 'Account holder', kind: 'text' },
    { key: 'note', label: 'Note to approver', kind: 'text' },
  ];
  private readonly rowsSig = signal<D.BankApprovalRow[] | null>(null);
  rows() { return this.rowsSig(); }
  private readonly bf = viewChild('bf');

  ngOnInit() { void this.refresh(); }
  refresh() { void this.api.bankAccounts().then((r) => this.rowsSig.set(r)); }

  async change(v: Record<string, string>) {
    try {
      await this.api.changeBankAccount({
        bankName: v['bankName'] ?? '',
        accountNumber: v['accountNumber'] ?? '',
        accountHolder: v['accountHolder'] ?? '',
        note: v['note'] || undefined,
      });
      (this.bf() as unknown as MpEntityForm).done('Change submitted - a DIFFERENT ops approver must approve it');
      this.refresh();
    } catch (e) { (this.bf() as unknown as MpEntityForm).failWire(e); }
  }
}

/* ================= C25 - statements ================= */
@Component({
  selector: 'supplier-statements',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PageHeader, MpTable, MpSpinner, MpCard],
  template: `
    <page-header title="Statements" subtitle="Your statements, ready to download" />
    <mp-card title="Available statements" class="mt">
      @if (rows(); as r) {
        <mp-table [cols]="cols" [rows]="r" empty="No statements yet" />
      } @else { <mp-spinner label="Loading statements..." /> }
    </mp-card>
  `,
})
export class SupplierStatements implements OnInit {
  private readonly api = inject(SupplierService);
  readonly cols: MpColumn[] = [
    { key: 'periodStart', label: 'From', type: 'date' },
    { key: 'periodEnd', label: 'To', type: 'date' },
    { key: 'generatedAt', label: 'Generated', type: 'time' },
  ];
  private readonly rowsSig = signal<D.StatementRow[] | null>(null);
  rows() { return this.rowsSig(); }

  ngOnInit() { void this.api.statements().then((r) => this.rowsSig.set(r)); }
}
