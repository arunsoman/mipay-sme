import { ChangeDetectionStrategy, Component, OnInit, inject, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { StatusBadge } from '../../design-system/atoms/status-badge';
import { MpCard } from '../../design-system/atoms/mp-card';
import { MpButton } from '../../design-system/atoms/mp-button';
import { MpSpinner } from '../../design-system/atoms/mp-spinner';
import { EmptyState } from '../../design-system/molecules/empty-state';
import { PageHeader } from '../../design-system/organisms/page-header';
import { MpTable, MpColumn } from '../../design-system/organisms/mp-table';
import { MpEntityForm, MpFieldSchema } from '../../design-system/organisms/mp-entity-form';
import { KpiRow } from '../../design-system/organisms/kpi-row';
import { MpPerm } from '../../../../projects/shared/web-core/perm-directive';
import { AdminService } from '../../core/services';
import * as D from '../../core/dto';

function wireMsg(e: unknown): string {
  const err = (e as { error?: { detail?: string; title?: string } })?.error ?? {};
  return String(err['detail'] || err['title'] || 'unknown');
}

/* ================= A01 admin overview + health ================= */
@Component({
  selector: 'admin-home',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpCard, MpSpinner, PageHeader, KpiRow],
  template: `
    <page-header title="MiPay console overview" subtitle="Environment posture, health and the seed identities used for UAT sweeps" />
    @if (h(); as v) {
      <kpi-row [tiles]="[
        { label: 'Database', amount: v.database ?? '-', money: false, sub: 'postgres (Eco prod) / h2 (demo)' },
        { label: 'Load test (X30)', amount: v.tps != null ? v.tps + ' TPS' : '-', money: false, sub: '20 TPS x 30 min gate = pass' },
        { label: 'Critical incidents open', amount: v.openCriticalIncidents ?? 0, money: false, sub: '15-min ack SLA' },
      ]" />
    } @else { <mp-spinner label="Calling /api/admin/health..." /> }
    <mp-card title="UAT seed identities" class="mt">
      <div class="ids">
        <div><b>Merchant:</b> 0811111111 (OTP flow, dev code 123456)</div>
        <div><b>Supplier:</b> 0852222222 / supplier123</div>
        <div><b>Lender:</b> 0813333333 / lender123</div>
        <div><b>Ops:</b> 0827777777 / ops123</div>
        <div><b>Admin:</b> 0819999999 / admin123</div>
      </div>
    </mp-card>
    <mp-card title="Go-live exclusions (SB p.14)" class="mt">
      <div class="dim">Native IPP B2B excluded at go-live: routes are BOOK_TRANSFER and EFT only. Every simulated bank/lender event is audit-labelled SIMULATED.</div>
    </mp-card>
  `,
  styles: [`.ids { display: grid; gap: 4px; font-size: var(--fs-md); }`],
})
export class AdminHome implements OnInit {
  private readonly api = inject(AdminService);
  private readonly hSig = signal<D.HealthView | null>(null);
  h() { return this.hSig(); }
  ngOnInit() { void this.api.health().then((v) => this.hSig.set(v)).catch(() => this.hSig.set({})); }
}

/* ================= C01-C09 connectors ================= */
@Component({
  selector: 'admin-connectors',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpTable, MpSpinner, MpButton, StatusBadge, PageHeader, MpPerm],
  template: `
    <page-header title="Connectors" subtitle="C01-C09: NDB issuer, acquirer card gateway, IPP; C08 test, C09 rotate credentials" />
    @if (rows(); as r) {
      <mp-table [cols]="cols" [rows]="r" [selectable]="true" (rowClick)="pick($event)" empty="No connectors configured" />
      <div class="row mt">
        <span class="dim">Selected: {{ sel()?.slice(0, 8) || 'none' }}</span>
        <mp-button *mpPerm="'admin.connector:execute'" [disabled]="!sel()" (onClick)="test()">Test</mp-button>
        <mp-button *mpPerm="'admin.connector:administer'" variant="ghost" [disabled]="!sel()" (onClick)="rotate()">Change secret</mp-button>
      </div>
      @if (testResult(); as t) {
        <div class="mt"><status-badge [status]="okLabel(t)" /> latency {{ t.latencyMs ?? 'n/a' }} ms {{ t.error ?? '' }}</div>
        <div class="dim">{{ t.label }}</div>
      }
    } @else { <mp-spinner label="Loading connectors..." /> }
  `,
  styles: [`
    .row { display: flex; align-items: center; gap: var(--sp-3); }
    .mt { margin-top: var(--sp-3); }
  `],
})
export class AdminConnectors implements OnInit {
  private readonly api = inject(AdminService);
  readonly cols: MpColumn[] = [
    { key: 'name', label: 'Name' },
    { key: 'type', label: 'Type' },
    { key: 'environment', label: 'Env' },
    { key: 'status', label: 'Status', type: 'status' },
    { key: 'lastHealthCheck', label: 'Last check', type: 'time' },
    { key: 'healthLatencyMs', label: 'Latency', align: 'right' },
  ];
  private readonly rowsSig = signal<D.ConnectorView[] | null>(null);
  private readonly selSig = signal<string | null>(null);
  private readonly testSig = signal<D.ConnectorTestRow | null>(null);
  rows() { return this.rowsSig(); }
  testResult() { return this.testSig(); }
  sel() { return this.selSig(); }

  ngOnInit() { void this.refresh(); }
  refresh() { void this.api.connectors().then((r) => this.rowsSig.set(r)); }

  pick(id: string) { this.selSig.set(id); }

  okLabel(t: D.ConnectorTestRow): string { return t.ok ? 'ACTIVE' : 'FAILED'; }

  async test() { if (this.sel()) { this.testSig.set(await this.api.testConnector(this.sel()!)); this.refresh(); } }
  async rotate() { if (this.sel()) { await this.api.rotateConnector(this.sel()!); this.refresh(); } }
}

/* ================= C10-C45 config ================= */
@Component({
  selector: 'admin-config',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpCard, MpTable, MpButton, PageHeader, MpEntityForm, FormsModule, MpPerm],
  template: `
    <page-header title="Configuration" subtitle="Tariffs (quoted/unquoted/provision), limits, business rules, notification templates, settings - every change audited before/after (TC-SET-21)" />

    <mp-card title="Fee tariffs (BC p.15)" class="mt">
      <mp-table [cols]="tariffCols" [rows]="tariffs() ?? []" empty="No tariffs" />
      <mp-entity-form #tf [schema]="tariffSchema" submitLabel="Upsert tariff" *mpPerm="'admin.config:modify'" (submitted)="upsertTariff($event)" class="mt" />
    </mp-card>

    <mp-card title="Limit profiles (C10)" class="mt">
      <mp-table [cols]="limitCols" [rows]="limits() ?? []" empty="No limits" />
      <mp-entity-form #lf [schema]="limitSchema" submitLabel="Upsert limit profile" *mpPerm="'admin.config:modify'" (submitted)="upsertLimit($event)" class="mt" />
    </mp-card>

    <mp-card title="Business rules (R1-R4 + exclusions)" class="mt">
      <mp-table [cols]="ruleCols" [rows]="rules() ?? []" empty="No rules" />
      <div class="row mt">
        <input class="in" [(ngModel)]="ruleCode" placeholder="R1" />
        <mp-button *mpPerm="'admin.config:modify'" (onClick)="toggleRule(true)">Enable</mp-button>
        <mp-button *mpPerm="'admin.config:modify'" variant="ghost" (onClick)="toggleRule(false)">Disable</mp-button>
      </div>
    </mp-card>

    <mp-card title="Settings" class="mt">
      <mp-table [cols]="settingCols" [rows]="settings() ?? []" empty="No settings" />
      <div class="row mt">
        <input class="in" [(ngModel)]="setKey" placeholder="mfa_policy" />
        <input class="in" [(ngModel)]="setValue" placeholder="OTP+device" />
        <mp-button *mpPerm="'admin.config:modify'" (onClick)="upsertSetting()">Save</mp-button>
      </div>
    </mp-card>

    <mp-card title="Notification templates" class="mt">
      <mp-table [cols]="tplCols" [rows]="templates() ?? []" empty="No templates" />
    </mp-card>
  `,
  styles: [`
    .row { display: flex; align-items: center; gap: var(--sp-3); }
    .in { padding: 8px; border: 1px solid var(--mp-border); border-radius: var(--radius-1); font: inherit; width: 220px; }
    .mt { margin-top: var(--sp-3); }
  `],
})
export class AdminConfig implements OnInit {
  private readonly api = inject(AdminService);
  readonly tariffCols: MpColumn[] = [
    { key: 'code', label: 'Fee code' },
    { key: 'rateType', label: 'Type' },
    { key: 'value', label: 'Value', align: 'right' },
    { key: 'externalCost', label: 'External cost', align: 'right' },
    { key: 'costBasis', label: 'Basis' },
  ];
  readonly limitCols: MpColumn[] = [
    { key: 'merchantId', label: 'Merchant' },
    { key: 'maxBalance', label: 'Max balance', type: 'money', align: 'right' },
    { key: 'dailyDebitCap', label: 'Daily debit cap', type: 'money', align: 'right' },
    { key: 'withdrawalCap', label: 'Withdrawal cap', type: 'money', align: 'right' },
    { key: 'advanceCap', label: 'Advance cap', type: 'money', align: 'right' },
    { key: 'arrearsTriggerPct', label: 'Arrears %', align: 'right' },
  ];
  readonly ruleCols: MpColumn[] = [
    { key: 'ruleCode', label: 'Code' },
    { key: 'enabled', label: 'On' },
    { key: 'definition', label: 'Definition' },
  ];
  readonly settingCols: MpColumn[] = [
    { key: 'key', label: 'Key' },
    { key: 'value', label: 'Value' },
  ];
  readonly tplCols: MpColumn[] = [
    { key: 'eventCode', label: 'Event' },
    { key: 'channel', label: 'Channel' },
    { key: 'locale', label: 'Locale' },
    { key: 'status', label: 'Status' },
    { key: 'body', label: 'Body' },
  ];

  readonly tariffSchema: MpFieldSchema[] = [
    { key: 'code', label: 'Fee code (ips_switching)', kind: 'text', required: true },
    { key: 'rateType', label: 'Type (PERCENT/FLAT)', kind: 'select', required: true,
      options: [{ value: 'PERCENT', label: 'PERCENT' }, { value: 'FLAT', label: 'FLAT' }] },
    { key: 'value', label: 'Value (0.4 = 0.40 N$ / %)', kind: 'number', required: true },
    { key: 'costBasis', label: "Basis (CONTRACTED/PROVISION/UNQUOTED)", kind: 'text', required: true },
    { key: 'externalCost', label: 'External cost (N$)', kind: 'number' },
  ];
  readonly limitSchema: MpFieldSchema[] = [
    { key: 'merchantId', label: 'Merchant id (blank=all -> default)', kind: 'text' },
    { key: 'maxBalance', label: 'Max balance', kind: 'number' },
    { key: 'dailyDebitCap', label: 'Daily debit cap', kind: 'number' },
    { key: 'withdrawalCap', label: 'Withdrawal cap', kind: 'number' },
    { key: 'advanceCap', label: 'Advance cap', kind: 'number' },
    { key: 'arrearsTriggerPct', label: 'Arrears trigger (0.05)', kind: 'number' },
  ];

  ruleCode = ''; setKey = ''; setValue = '';

  private readonly tariffsSig = signal<D.TariffRow[]>([]);
  private readonly limitsSig = signal<D.LimitRow[]>([]);
  private readonly rulesSig = signal<D.RuleRow[]>([]);
  private readonly settingsSig = signal<D.SettingRow[]>([]);
  private readonly templatesSig = signal<D.TemplateRow[]>([]);
  tariffs() { return this.tariffsSig(); }
  limits() { return this.limitsSig(); }
  rules() { return this.rulesSig(); }
  settings() { return this.settingsSig(); }
  templates() { return this.templatesSig(); }

  private readonly tf = viewChild('tf');
  private readonly lf = viewChild('lf');

  ngOnInit() { void this.refresh(); }
  refresh() {
    void this.api.tariffs().then((t) => this.tariffsSig.set(t));
    void this.api.limits().then((l) => this.limitsSig.set(l));
    void this.api.rules().then((r) => this.rulesSig.set(r));
    void this.api.settings().then((s) => this.settingsSig.set(s));
    void this.api.templates().then((t) => this.templatesSig.set(t));
  }

  async upsertTariff(v: Record<string, string>) {
    try {
      await this.api.upsertTariff({
        code: v['code'], rateType: v['rateType'], value: Number(v['value']),
        costBasis: v['costBasis'], externalCost: v['externalCost'] ? Number(v['externalCost']) : null,
      });
      (this.tf() as unknown as MpEntityForm).done('Tariff saved');
      this.refresh();
    } catch (e) { (this.tf() as unknown as MpEntityForm).failWire(e); }
  }

  async upsertLimit(v: Record<string, string>) {
    try {
      await this.api.upsertLimit({
        merchantId: v['merchantId'] || '', maxBalance: numOrNull(v['maxBalance']),
        dailyDebitCap: numOrNull(v['dailyDebitCap']), withdrawalCap: numOrNull(v['withdrawalCap']),
        advanceCap: numOrNull(v['advanceCap']), arrearsTriggerPct: numOrNull(v['arrearsTriggerPct']),
      });
      (this.lf() as unknown as MpEntityForm).done('Limit profile saved');
      this.refresh();
    } catch (e) { (this.lf() as unknown as MpEntityForm).failWire(e); }
  }

  async toggleRule(enabled: boolean) {
    if (!this.ruleCode) { alert('Enter a rule code, e.g. R1'); return; }
    try { await this.api.toggleRule(this.ruleCode, enabled); this.refresh(); }
    catch (e) { alert(wireMsg(e)); }
  }

  async upsertSetting() {
    if (!this.setKey) { alert('Enter a setting key'); return; }
    try { await this.api.upsertSetting(this.setKey, this.setValue); this.refresh(); }
    catch (e) { alert(wireMsg(e)); }
  }
}

function numOrNull(v: string): number | null { return v === '' || v == null ? null : Number(v); }

/* ================= E31/E46 simulator + scenes ================= */
@Component({
  selector: 'admin-simulator',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpCard, MpTable, MpButton, StatusBadge, PageHeader, MpEntityForm, FormsModule, MpSpinner, MpPerm],
  template: `
    <page-header title="Simulator" subtitle="E31: replay bank-side events; C31-C33 scenario library; bank-sim controls set the default X-Sim-Scenario applied to every bank call" />

    <mp-card title="Bank simulator controls" class="mt">
      @if (simCtl(); as c) {
        <div class="row">
          <span class="dim">Default scenario:</span>
          <select class="sel" [(ngModel)]="chosenScenario">
            @for (s of SCENARIOS; track s) { <option [value]="s">{{ s }}</option> }
          </select>
          <mp-button *mpPerm="'admin.simulation:simulate'" (onClick)="saveControls()">Apply</mp-button>
          <span class="dim">simulatorEnabled: <b>{{ c.simulatorEnabled }}</b></span>
        </div>
        <div class="dim mt">Applies to every simulated bank call unless a merchant payment carries a per-request X-Sim-Scenario header. MANUAL preserves inline behavior; async scenes park the payment in HOLDING/PENDING until the virtual-thread bank callback lands. SIMULATED-labelled throughout.</div>
      } @else { <mp-spinner label="Loading sim controls..." /> }
    </mp-card>

    <mp-card title="Scenario library (C31)" class="mt">
      <mp-table [cols]="sceneCols" [rows]="scenes() ?? []" empty="No scenarios loaded" />
      <div class="row mt">
        <mp-button *mpPerm="'admin.simulation:simulate'" (onClick)="runScene('happy_path')">Run happy path</mp-button>
        <mp-button *mpPerm="'admin.simulation:simulate'" (onClick)="runScene('advance_flow')">Run advance flow</mp-button>
        <mp-button *mpPerm="'admin.simulation:simulate'" variant="ghost" (onClick)="runScene('recon_break')">Run recon break scene</mp-button>
      </div>
      @if (runResult(); as r) {
        <div class="steps mt">
          @for (st of r.steps; track st.step) {
            <div class="step"><status-badge [status]="st.ok ? 'CONFIRMED' : 'FAILED'" /> {{ st.step }} {{ st.detail ?? '' }}</div>
          }
        </div>
      }
    </mp-card>

    <div class="grid2 mt">
      <mp-card title="Inject a reconciliation break">
        <mp-entity-form #bf [schema]="breakSchema" submitLabel="Inject" *mpPerm="'admin.simulation:simulate'" (submitted)="injectBreak($event)" />
      </mp-card>
      <mp-card title="Quick actions">
        <div class="row">
          <mp-button (onClick)="clearBreaks()">Clear all breaks</mp-button>
          <mp-button (onClick)="synthetic()">Generate 120d history</mp-button>
        </div>
        @if (lastMsg(); as m) { <div class="dim mt">{{ m }}</div> }
      </mp-card>
    </div>
  `,
  styles: [`
    .row { display: flex; align-items: center; gap: var(--sp-3); flex-wrap: wrap; }
    .sel { padding: 8px; border: 1px solid var(--mp-border); border-radius: var(--radius-1); font: inherit; }
    .step { display: flex; align-items: center; gap: 10px; padding: 3px 0; font-size: var(--fs-md); }
    .grid2 { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--sp-4); align-items: start; }
    @media (max-width: 900px) { .grid2 { grid-template-columns: minmax(0, 1fr); } }
    .mt { margin-top: var(--sp-3); }
  `],
})
export class AdminSimulator implements OnInit {
  private readonly api = inject(AdminService);
  readonly sceneCols: MpColumn[] = [
    { key: 'name', label: 'Name' },
    { key: 'description', label: 'Description' },
    { key: 'action', label: 'Action' },
    { key: 'isLabelled', label: 'SIMULATED' },
    { key: 'enabled', label: 'Enabled' },
  ];
  readonly SCENARIOS = ['MANUAL', 'CONFIRM', 'DUPLICATE', 'TIMEOUT', 'LATE_CONFIRM', 'FAIL'];
  readonly breakSchema: MpFieldSchema[] = [
    { key: 'breakType', label: 'Break type', kind: 'select', required: true,
      options: [{ value: 'MISSING_CREDIT', label: 'Missing credit' }, { value: 'AMOUNT_MISMATCH', label: 'Amount mismatch' }] },
    { key: 'amount', label: 'Amount (N$)', kind: 'number' },
    { key: 'reference', label: 'Reference', kind: 'text' },
  ];

  private readonly scenesSig = signal<D.ScenarioRow[] | null>(null);
  private readonly runResSig = signal<D.SceneRunResult | null>(null);
  private readonly lastMsgSig = signal('');
  private readonly simCtlSig = signal<D.SimControlsView | null>(null);
  scenes() { return this.scenesSig(); }
  runResult() { return this.runResSig(); }
  lastMsg() { return this.lastMsgSig(); }
  simCtl() { return this.simCtlSig(); }
  chosenScenario = '';

  private readonly bf = viewChild('bf');

  ngOnInit() {
    void this.api.scenarios().then((s) => this.scenesSig.set(s));
    void this.loadControls();
  }

  async loadControls() {
    try {
      const c = await this.api.simControls();
      this.simCtlSig.set(c);
      this.chosenScenario = c.defaultScenario;
    } catch (_e) { /* controls unavailable */ }
  }

  async saveControls() {
    try {
      const c = await this.api.setSimControls(this.chosenScenario);
      this.simCtlSig.set(c);
      this.lastMsgSig.set(`Bank-sim default scenario set to ${c.defaultScenario} (SIMULATED audit entry)`);
    } catch (e) { this.lastMsgSig.set(wireMsg(e)); }
  }

  async runScene(scene: string) {
    try {
      const res = await this.api.runScene(scene);
      this.runResSig.set(res);
    } catch (e) { alert(wireMsg(e)); }
  }

  async injectBreak(v: Record<string, string>) {
    try {
      await this.api.injectBreak({
        breakType: v['breakType'], amount: v['amount'] ? Number(v['amount']) : undefined,
        reference: v['reference'] || undefined,
      });
      (this.bf() as unknown as MpEntityForm).done('Break injected - next recon run will find it');
      this.lastMsgSig.set('Break injected');
    } catch (e) { (this.bf() as unknown as MpEntityForm).failWire(e); }
  }

  async clearBreaks() {
    const r = await this.api.clearBreaks();
    this.lastMsgSig.set(JSON.stringify(r));
  }

  async synthetic() {
    const r = await this.api.syntheticHistory();
    this.lastMsgSig.set(JSON.stringify(r));
  }
}

/* ================= X04 access console ================= */
@Component({
  selector: 'admin-access',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpCard, MpTable, MpButton, StatusBadge, PageHeader, MpSpinner],
  template: `
    <page-header title="Access console (X04)"
      subtitle="Device sessions per user; revoking kills all live tokens immediately (server-side jti check) and raises a CRITICAL incident with 15-min ack SLA" />
    @if (rows(); as r) {
      <mp-card>
        <mp-table [cols]="cols" [rows]="r" [selectable]="true" (rowClick)="sel.set($event)" empty="No users" />
        <div class="row mt">
          <mp-button variant="danger" [disabled]="!sel()" (onClick)="revoke()">Revoke access</mp-button>
          <span class="dim">Selected: {{ sel() || 'none' }}</span>
        </div>
      </mp-card>
    } @else { <mp-spinner label="Loading access directory..." /> }
    @if (result(); as v) {
      <mp-card title="Revoked" class="mt">
        <div class="row"><status-badge [status]="'CRITICAL'" /> {{ v.revoked }} device session(s) killed - incident {{ v.incidentId?.slice(0, 8) }} raised</div>
        <div class="dim mt">The user cannot sign in with an existing token: every request is checked against the session store (SIMULATED audit entry).</div>
      </mp-card>
    }
  `,
  styles: [`
    .row { display: flex; align-items: center; gap: var(--sp-3); }
    .mt { margin-top: var(--sp-3); }
  `],
})
export class AdminAccess implements OnInit {
  private readonly api = inject(AdminService);
  readonly cols: MpColumn[] = [
    { key: 'userId', label: 'User id' },
    { key: 'phone', label: 'Phone' },
    { key: 'role', label: 'Role' },
    { key: 'status', label: 'Status', type: 'status' },
    { key: 'activeSessions', label: 'Live sessions', align: 'right' },
  ];

  private readonly rowsSig = signal<D.AccessUserRow[] | null>(null);
  private readonly resultSig = signal<D.AccessRevokeResult | null>(null);
  readonly sel = signal<string | null>(null);
  rows() { return this.rowsSig(); }
  result() { return this.resultSig(); }

  ngOnInit() { void this.refresh(); }
  refresh() { void this.api.accessUsers().then((r) => this.rowsSig.set(r)); }

  async revoke() {
    if (!this.sel()) { return; }
    try {
      this.resultSig.set(await this.api.revokeUserAccess(this.sel()!));
      this.refresh();
    } catch (e) { alert(wireMsg(e)); }
  }
}

/* ================= C40-C45 compliance ================= */
@Component({
  selector: 'admin-compliance',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpCard, MpTable, MpButton, StatusBadge, PageHeader, MpEntityForm, MpPerm],
  template: `
    <page-header title="Compliance" subtitle="Money-laundering alerts, suspicious activity reports and personal data requests" />

    <mp-card title="Money-laundering alerts" class="mt">
      <mp-table [cols]="amlCols" [rows]="aml() ?? []" empty="No AML alerts (all flows healthy)" />
    </mp-card>

    <mp-card title="Suspicious activity reports" class="mt">
      <mp-table [cols]="sarCols" [rows]="sars() ?? []" [selectable]="true" (rowClick)="selSar.set($event)" empty="No reports yet" />
      <div class="row mt">
        <mp-button (onClick)="submitSar()" [disabled]="!selSar()">Submit report</mp-button>
        <span class="dim">Selected: {{ selSar()?.slice(0, 8) || 'none' }}</span>
      </div>
      <mp-entity-form #sf [schema]="sarCreateSchema" submitLabel="Draft a report from this alert" *mpPerm="'admin.sar:create'" (submitted)="createSar($event)" class="mt" />
    </mp-card>

    <mp-card title="Personal data requests" class="mt">
      <mp-table [cols]="dsarCols" [rows]="dsars() ?? []" empty="No data requests yet" />
      <mp-entity-form #df [schema]="dsarCreateSchema" submitLabel="Create data request" *mpPerm="'admin.dsar:create'" (submitted)="createDsar($event)" class="mt" />
      <div class="dim mt">When a request is completed, the merchant gets a full copy of their data: balance, payments, invoices, consents and statements.</div>
    </mp-card>
  `,
  styles: [`
    .row { display: flex; align-items: center; gap: var(--sp-3); }
    .mt { margin-top: var(--sp-3); }
  `],
})
export class AdminCompliance implements OnInit {
  private readonly api = inject(AdminService);
  readonly amlCols: MpColumn[] = [
    { key: 'createdAt', label: 'When', type: 'time' },
    { key: 'alertType', label: 'Type' },
    { key: 'entityType', label: 'Account' },
    { key: 'details', label: 'Details' },
    { key: 'status', label: 'Status', type: 'status' },
  ];
  readonly sarCols: MpColumn[] = [
    { key: 'createdAt', label: 'Drafted', type: 'time' },
    { key: 'alertId', label: 'Alert' },
    { key: 'reference', label: 'Reference' },
    { key: 'status', label: 'Status', type: 'status' },
    { key: 'submittedAt', label: 'Submitted', type: 'time' },
  ];
  readonly dsarCols: MpColumn[] = [
    { key: 'requestedAt', label: 'Requested', type: 'time' },
    { key: 'merchantId', label: 'Merchant' },
    { key: 'status', label: 'Status', type: 'status' },
    { key: 'completedAt', label: 'Completed', type: 'time' },
  ];
  readonly sarCreateSchema: MpFieldSchema[] = [
    { key: 'alertId', label: 'Alert', kind: 'text', required: true },
    { key: 'details', label: 'Details', kind: 'text' },
  ];
  readonly dsarCreateSchema: MpFieldSchema[] = [
    { key: 'merchantId', label: 'Merchant ID', kind: 'text', required: true },
  ];

  private readonly amlSig = signal<D.AmlRow[] | null>(null);
  private readonly sarsSig = signal<D.SarRow[] | null>(null);
  private readonly dsarsSig = signal<D.DsarRow[] | null>(null);
  readonly selSar = signal<string | null>(null);
  aml() { return this.amlSig(); }
  sars() { return this.sarsSig(); }
  dsars() { return this.dsarsSig(); }

  private readonly sf = viewChild('sf');
  private readonly df = viewChild('df');

  ngOnInit() { void this.refresh(); }
  refresh() {
    void this.api.amlAlerts().then((a) => this.amlSig.set(a));
    void this.api.sars().then((s) => this.sarsSig.set(s));
    void this.api.dsars().then((d) => this.dsarsSig.set(d));
  }

  async createSar(v: Record<string, string>) {
    try {
      await this.api.createSar(v['alertId'], v['details'] ?? '');
      (this.sf() as unknown as MpEntityForm).done('SAR drafted');
      this.refresh();
    } catch (e) { (this.sf() as unknown as MpEntityForm).failWire(e); }
  }
  async submitSar() { if (this.selSar()) { await this.api.submitSar(this.selSar()!); this.refresh(); } }
  async createDsar(v: Record<string, string>) {
    try {
      await this.api.createDsar(v['merchantId'] ?? '');
      (this.df() as unknown as MpEntityForm).done('DSAR created - export runs to completion');
      this.refresh();
    } catch (e) { (this.df() as unknown as MpEntityForm).failWire(e); }
  }
}

/* ================= C30/E30 hygiene: api clients, deployments, backups ================= */
@Component({
  selector: 'admin-hygiene',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpCard, MpTable, MpButton, PageHeader, MpEntityForm, FormsModule, MpPerm],
  template: `
    <page-header title="Ops hygiene" subtitle="API clients, deployments/rollback, backups with restore tests proving RTO 2h / RPO 0" />

    <mp-card title="API clients" class="mt">
      <mp-table [cols]="clientCols" [rows]="clients() ?? []" [selectable]="true" (rowClick)="selClient.set($event)" empty="No clients" />
      <div class="row mt">
        <mp-button variant="ghost" (onClick)="revokeClient()" [disabled]="!selClient()">Revoke selected</mp-button>
      </div>
      <mp-entity-form #cf [schema]="clientSchema" submitLabel="Create API client" *mpPerm="'admin.api_client:create'" (submitted)="createClient($event)" class="mt" />
    </mp-card>

    <mp-card title="Deployments" class="mt">
      <mp-table [cols]="depCols" [rows]="deployments() ?? []" empty="No deployments (Eco release candidate RC1)" />
      <mp-entity-form #ddf [schema]="depSchema" submitLabel="Record deployment" *mpPerm="'admin.platform_ops:execute'" (submitted)="deploy($event)" class="mt" />
      <div class="row mt">
        <input class="in" [(ngModel)]="rollbackVersion" placeholder="version to roll back" />
        <mp-button variant="danger" (onClick)="rollback()">Roll back</mp-button>
      </div>
    </mp-card>

    <mp-card title="Backups & restore tests (C33)" class="mt">
      <div class="row">
        <mp-button (onClick)="backupNow()">Backup now</mp-button>
      </div>
      <mp-table [cols]="backupCols" [rows]="backups() ?? []" class="mt" empty="No backups" />
      <mp-table [cols]="rtCols" [rows]="restoreTests() ?? []" class="mt" empty="No restore tests yet" />
    </mp-card>
  `,
  styles: [`
    .row { display: flex; align-items: center; gap: var(--sp-3); }
    .in { padding: 8px; border: 1px solid var(--mp-border); border-radius: var(--radius-1); font: inherit; width: 220px; }
    .mt { margin-top: var(--sp-3); }
  `],
})
export class AdminHygiene implements OnInit {
  private readonly api = inject(AdminService);
  readonly clientCols: MpColumn[] = [
    { key: 'clientName', label: 'Client' },
    { key: 'clientId', label: 'Client id' },
    { key: 'scopes', label: 'Scopes' },
    { key: 'rateLimitTps', label: 'TPS' },
    { key: 'status', label: 'Status', type: 'status' },
  ];
  readonly depCols: MpColumn[] = [
    { key: 'deployedAt', label: 'When', type: 'time' },
    { key: 'version', label: 'Version' },
    { key: 'environment', label: 'Environment' },
    { key: 'status', label: 'Status', type: 'status' },
    { key: 'notes', label: 'Notes' },
  ];
  readonly backupCols: MpColumn[] = [
    { key: 'startedAt', label: 'Started', type: 'time' },
    { key: 'backupType', label: 'Type' },
    { key: 'status', label: 'Status', type: 'status' },
    { key: 'completedAt', label: 'Completed', type: 'time' },
  ];
  readonly rtCols: MpColumn[] = [
    { key: 'testedAt', label: 'Tested', type: 'time' },
    { key: 'backupId', label: 'Backup' },
    { key: 'rtoAchievedMinutes', label: 'RTO min', align: 'right' },
    { key: 'rpoAchievedMinutes', label: 'RPO min', align: 'right' },
    { key: 'ok', label: 'OK' },
  ];
  readonly clientSchema: MpFieldSchema[] = [
    { key: 'clientName', label: 'Client name', kind: 'text', required: true },
    { key: 'rateLimitTps', label: 'Rate limit (TPS)', kind: 'number' },
  ];
  readonly depSchema: MpFieldSchema[] = [
    { key: 'version', label: 'Version (v1.0.0)', kind: 'text', required: true },
    { key: 'environment', label: 'Environment', kind: 'select', required: true,
      options: [{ value: 'UAT', label: 'UAT' }, { value: 'PROD', label: 'PROD' }] },
    { key: 'notes', label: 'Notes', kind: 'text' },
  ];

  rollbackVersion = '';
  readonly selClient = signal<string | null>(null);
  private readonly clientsSig = signal<D.ApiClientRow[] | null>(null);
  private readonly depsSig = signal<D.DeploymentRow[] | null>(null);
  private readonly backupsSig = signal<D.BackupRow[] | null>(null);
  private readonly rtSig = signal<D.RestoreTestRow[] | null>(null);
  clients() { return this.clientsSig(); }
  deployments() { return this.depsSig(); }
  backups() { return this.backupsSig(); }
  restoreTests() { return this.rtSig(); }

  private readonly cf = viewChild('cf');
  private readonly ddf = viewChild('ddf');

  ngOnInit() { void this.refresh(); }
  refresh() {
    void this.api.apiClients().then((c) => this.clientsSig.set(c));
    void this.api.deployments().then((d) => this.depsSig.set(d));
    void this.api.backups().then((b) => this.backupsSig.set(b));
    void this.api.restoreTests().then((r) => this.rtSig.set(r));
  }

  async createClient(v: Record<string, string>) {
    try {
      await this.api.createClient({ clientName: v['clientName'], rateLimitTps: v['rateLimitTps'] ? Number(v['rateLimitTps']) : undefined });
      (this.cf() as unknown as MpEntityForm).done('API client created');
      this.refresh();
    } catch (e) { (this.cf() as unknown as MpEntityForm).failWire(e); }
  }
  async revokeClient() { if (this.selClient()) { await this.api.revokeClient(this.selClient()!); this.refresh(); } }
  async deploy(v: Record<string, string>) {
    try {
      await this.api.deploy({ version: v['version'], environment: v['environment'], notes: v['notes'] || undefined });
      (this.ddf() as unknown as MpEntityForm).done('Deployment recorded');
      this.refresh();
    } catch (e) { (this.ddf() as unknown as MpEntityForm).failWire(e); }
  }
  async rollback() {
    if (!this.rollbackVersion) { alert('Enter the version to roll back to'); return; }
    try { await this.api.rollback(this.rollbackVersion); this.refresh(); }
    catch (e) { alert(wireMsg(e)); }
  }
  async backupNow() { await this.api.backupNow(); this.refresh(); }
}

