import { PageHeader } from '../../../../../../src/app/design-system/organisms/page-header';
import { MpSpinner } from '../../../../../../src/app/design-system/atoms/mp-spinner';
import { MpAlert } from '../../../../../../src/app/design-system/molecules/alert';
import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { AdminAuthService } from '../../core/auth';
import { KycApplicationView, KycCheckRow, KycTaskRow, OnboardingRequestRow, OnboardingStatus, OrgDecisionResult, OrgView, readProblem } from '../../core/api';
import { KycView } from './kyc-view';
import { RouterLink } from '@angular/router';
import { OnboardChild } from '../../../../../shared/kyc/onboard-child';
import { Modal } from '../../../../../../src/app/design-system/organisms/modal';
import { MpTabs, TabOption } from '../../../../../../src/app/design-system/molecules/tabs';
import { DataCol, DATA_TABLE } from '../../../../../../src/app/design-system/organisms/data-table';
import { MpPerm } from '../../../../../shared/web-core/perm-directive';

/**
 * Onboarding review queue ([M10]/second-person rule): every request the
 * caller may act on - the platform sees all requests; an org ADMIN sees the
 * requests for orgs they parent. Approve (with simulated sanctions result)
 * or reject with a note; the same requester can never review ([M4]) - the
 * backend refuses with INDEPENDENCE_RULE and the portal surfaces it.
 *
 * KYC: manual checks can be recorded on any org entity (IDENTITY / SANCTIONS /
 * ADVERSE_MEDIA). The decision flow auto-records IDENTITY + SANCTIONS; this
 * panel lets ops add or override checks at any time.
 */
@Component({
  selector: 'mpa-onboarding',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PageHeader, MpSpinner, MpAlert, MpTabs, FormsModule, DatePipe, KycView, RouterLink, OnboardChild, Modal, MpPerm, ...DATA_TABLE],
  template: `
    <page-header title="Onboarding">
      <div actions>
        <button class="ghost" (click)="load()">Refresh</button>
        <!-- onboard under the platform root, within the root's rights, then straight into KYC -->
        <mp-onboard-child *mpPerm="'org.onboarding:create'" [compact]="true" (onboarded)="load()" (kycChanged)="loadKycTasks()" />
      </div>
    </page-header>
    <mp-tabs [options]="tabOpts()" [value]="tab()" (valueChange)="setTab($event)" label="Request status" />

    @if (problem(); as p) {<mp-alert kind="bad">{{ p }}</mp-alert>}
    @if (flash(); as f) {<mp-alert [kind]="f.kind">{{ f.text }}</mp-alert>}
    @if (loading()) {<mp-spinner label="Loading…" />}

    @if (selected(); as row) {
      <mp-modal [open]="true" size="wide"
                [eyebrow]="(row.status === 'PENDING_REVIEW' ? 'Review request' : 'Request') + ' · ' + label(row.kind)"
                [title]="row.orgName ?? 'Organization'" [subtitle]="row.orgType + ' · request ' + row.id"
                (closed)="close()">
        @if (flash(); as f) {<mp-alert [kind]="f.kind">{{ f.text }}</mp-alert>}
        @if (row.status === 'PENDING_REVIEW') {
        @if (row.kind === 'ORG_ONBOARDING') {
          <!-- [D8] ORG_ONBOARDING is decided by the KYC workflow's ops-review task -->
          <div class="muted small workflownote">
            This organization is approved through its KYC: once the KYC review below is approved, the
            organization goes live and its administrator gets their set-up link.
          </div>
        } @else {
        <div class="grid2">
          <label>Note
            <input name="note" [(ngModel)]="note" placeholder="review note (optional)" />
          </label>
          <label>Sanctions screening (simulated)
            <select name="sanctions" [(ngModel)]="sanctions">
              <option value="CLEAR">CLEAR</option>
              <option value="FAIL">FAIL (blocks the org)</option>
            </select>
          </label>
        </div>
        <div class="row" *mpPerm="'org.onboarding:authorize'">
          <button class="primary" (click)="decide(true)">Approve</button>
          <button class="danger" (click)="decide(false)">Reject</button>
        </div>
        }
        }
        <!-- KYC application document (JSON + tree details + isSuspended) -->
        <div class="kyc-section">
          @if (kycApp(); as k) {
            <div class="dtitle">KYC application
              <span class="chip small" [class.ok]="k.status === 'APPROVED'" [class.warn]="isLiveStatus(k.status)"
                    [class.bad]="k.status === 'REJECTED' || k.status === 'BLOCKED' || k.status === 'WITHDRAWN'">{{ k.status.replace('_', ' ') }}</span>
              @if (k.isSuspended) {<span class="chip bad small">SUSPENDED</span>}
              <span class="muted small">{{ pathText(k) }} · depth {{ k.depth }}</span></div>
            @if (k.submittedApplication ?? k.application; as doc) {
              <mpa-kyc-view [doc]="doc" />
            } @else {
              <div class="muted small">The organization has not started its KYC application.</div>
            }
            @if (k.status === 'REJECTED' && k.decisionNote) {<div class="muted small">Rejected: {{ k.decisionNote }}</div>}
            @if (kycTasksFor(k.orgId).length; as n) {
              <div class="dtitle sub">Open review tasks for this application</div>
              @for (t of kycTasksFor(k.orgId); track t.id) {
                <div class="taskrow" *mpPerm="'kyc.task:authorize'">
                  <span class="chip small warn">{{ t.stageKey === 'ops-review' ? 'MiPay review' : t.stageKey }}</span>
                  <span class="muted small mono">{{ t.openNote ?? '' }}</span>
                  <input class="tsknote" name="note-{{t.id}}" [(ngModel)]="taskNotes[t.id]"
                         placeholder="decision note (returned/rejected to the org)" />
                  <button class="primary" (click)="decideTask(t.id, 'APPROVE')">Approve</button>
                  <button (click)="decideTask(t.id, 'RETURN')">Return</button>
                  <button class="danger" (click)="decideTask(t.id, 'REJECT')">Reject</button>
                  <button class="danger" (click)="decideTask(t.id, 'BLOCK')">Block</button>
                </div>
              }
            } @else if (k.status !== 'NOT_STARTED' && isLiveStatus(k.status)) {
              <div class="muted small">Nothing to review right now — the application is waiting on another step.</div>
            }
          }
        </div>

        <div class="kyc-section">
          <div class="dtitle">Screening checks for {{ row.orgName }}</div>
          <mp-data-table [cols]="checkCols" [rows]="kycChecks()" placeholder="Search checks" empty="No KYC checks recorded yet.">
            <ng-template mpCell="chip" let-c let-v="value"><span class="chip small" [class.ok]="c.result === 'PASS'" [class.bad]="c.result === 'FAIL'">{{ v }}</span></ng-template>
            <ng-template mpCell="checkedAt" let-v="value">{{ v | date:'MMM d, HH:mm' }}</ng-template>
          </mp-data-table>
          <div class="grid3" *mpPerm="'ops.kyc_check:create'">
            <label>Check type
              <select name="kycType" [(ngModel)]="kycType">
                <option value="IDENTITY">IDENTITY</option>
                <option value="SANCTIONS">SANCTIONS</option>
                <option value="ADVERSE_MEDIA">ADVERSE MEDIA</option>
              </select>
            </label>
            <label>Result
              <select name="kycResult" [(ngModel)]="kycResult">
                <option value="PASS">PASS</option>
                <option value="FAIL">FAIL</option>
                <option value="MANUAL_REVIEW">MANUAL REVIEW</option>
              </select>
            </label>
            <label>Details
              <input name="kycDetails" [(ngModel)]="kycDetails" placeholder="simulated provider note" />
            </label>
            <div class="cell">
              <button class="primary" (click)="addKycCheck()">Add KYC check</button>
            </div>
          </div>
        </div>
        <div modal-footer>
          <a class="btnlink" routerLink="/kyc" [queryParams]="{ org: row.orgId }">Open full KYC ›</a>
          <button type="button" (click)="close()">Close</button>
        </div>
      </mp-modal>
    }

    <div class="panel tblpanel">
      <mp-data-table [cols]="rowCols" [rows]="rows()" placeholder="Search organizations"
                     [empty]="'No ' + label(tab()).toLowerCase() + ' requests.'" noMatch="No request matches your search.">
        <ng-template mpCell="org" let-r>
          <div class="nm">{{ r.orgName }}</div>
          <div class="sub">{{ typeLabel(r.orgType) }}@if (r.kind === 'ADMIN_RECOVERY') { · <span class="warnt">admin recovery</span>}</div>
          @if (r.note && r.kind === 'ADMIN_RECOVERY') {<div class="muted small clip" [title]="r.note">{{ r.note }}</div>}
        </ng-template>
        <ng-template mpCell="createdAt" let-v="value">{{ v | date:'MMM d, HH:mm' }}</ng-template>
        <ng-template mpCell="status" let-r>
          <span class="chip" [class.warn]="r.status === 'PENDING_REVIEW'" [class.ok]="r.status === 'APPROVED'"
                [class.bad]="r.status === 'BLOCKED' || r.status === 'REJECTED'">{{ label(r.status) }}</span>
          @if (r.reviewedAt) {<div class="muted small">decided {{ r.reviewedAt | date:'MMM d' }}</div>}
        </ng-template>
        <ng-template mpCell="act" let-r>
          <div class="acts">
            @if (r.status === 'PENDING_REVIEW' && r.kind === 'ADMIN_RECOVERY') {
              <button *mpPerm="'org.onboarding:authorize'" (click)="pick(r)">Decide</button>
            }
            @if (r.orgId) {
              <button *mpPerm="'kyc.application:read'" class="ghost" (click)="pickKyc(r)">Review</button>
              <a *mpPerm="'kyc.application:modify'" class="btn ghost" routerLink="/kyc" [queryParams]="{ org: r.orgId }">Edit KYC</a>
            }
          </div>
        </ng-template>
      </mp-data-table>
    </div>
  `,
  styles: [`
    h2 { margin: 0; font-size: 30px; line-height: 1.1; }
    .sub { color: var(--a-dim); font-size: 12.5px; margin-top: 3px; }
    .warnt { color: var(--a-amber); }
    .empty { padding: 28px 14px !important; text-align: center; }
    a.btn { display: inline-flex; align-items: center; padding: 7px 14px; border-radius: 999px; border: 1px solid var(--a-border);
            color: var(--a-text); text-decoration: none; font-size: 13px; }
    a.btn:hover { border-color: #5a5a5a; }
    .panel { padding: 4px 0; margin-top: 10px; }
    .decide { padding: 14px; }
    .dtitle { font-size: 13.5px; margin-bottom: 10px; }
    .grid2 { display: grid; grid-template-columns: 2fr 1fr; gap: 12px; margin-bottom: 12px; }
    .grid3 { display: grid; grid-template-columns: 1fr 1fr 2fr auto; gap: 10px; align-items: end; }
    label { display: grid; gap: 5px; font-size: var(--fs-sm); color: var(--a-dim); }
    .row { display: flex; gap: 8px; }
    .cell { padding-bottom: 1px; }
    .btnlink { color: var(--a-teal); font-size: var(--fs-sm); white-space: nowrap; }
    .tblpanel { padding: 12px 14px; }
    .org .nm { font-weight: 600; }
    .sub { display: flex; flex-wrap: wrap; gap: 4px 6px; align-items: center; margin-top: 4px; font-size: var(--fs-xs); }
    .clip { max-width: 280px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .rights { min-width: 140px; }
    .nowrap { white-space: nowrap; }
    th.act, td.act { width: 1%; }
    .acts { display: flex; align-items: center; justify-content: flex-end; gap: 6px; flex-wrap: nowrap; }
    .acts button, .acts a { white-space: nowrap; padding: 6px 14px; font-size: 13px; }
    td { vertical-align: middle; }
    .acts .btnlink { text-align: center; }
    .kyc-section { margin-top: 16px; border-top: 1px solid var(--a-border); padding-top: 12px; }
    .kyc-table { font-size: var(--fs-sm); }
    .kyc-table td { font-size: var(--fs-sm); }
    .chip.small { font-size: 10.5px; padding: 0 7px; }
    .close { float: right; padding: 4px 10px; }
    .kycdec { margin-top: 12px; align-items: end; }
    .dtitle.sub { margin: 10px 0 6px; }
    .taskrow { display: flex; align-items: center; gap: 8px; margin: 6px 0; flex-wrap: wrap; }
    .taskrow .tsknote { flex: 1 1 220px; min-width: 160px; }
    .workflownote { margin-bottom: 10px; }
  `],
})
export class Onboarding implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AdminAuthService);

  readonly tabs: OnboardingStatus[] = ['PENDING_REVIEW', 'APPROVED', 'REJECTED', 'BLOCKED'];
  tabOpts(): TabOption<OnboardingStatus>[] { return this.tabs.map((t) => ({ value: t, label: this.label(t) })); }
  readonly tab = signal<OnboardingStatus>('PENDING_REVIEW');
  readonly rows = signal<OnboardingRequestRow[]>([]);
  readonly loading = signal(false);
  readonly problem = signal('');
  readonly flash = signal<{ kind: string; text: string } | null>(null);
  readonly selected = signal<OnboardingRequestRow | null>(null);
  readonly kycSelected = signal<OnboardingRequestRow | null>(null);

  readonly kycChecks = signal<KycCheckRow[]>([]);
  readonly checkCols: DataCol<KycCheckRow>[] = [
    { key: 'checkType', label: 'Check type', type: 'chip' },
    { key: 'result', label: 'Result', type: 'chip' },
    { key: 'details', label: 'Details', cls: 'small muted' },
    { key: 'checkedAt', label: 'Checked', cls: 'mono small' },
  ];
  readonly rowCols: DataCol<OnboardingRequestRow>[] = [
    { key: 'org', label: 'Organization',
      search: (r) => [r.orgName, this.typeLabel(r.orgType), r.kind === 'ADMIN_RECOVERY' ? 'admin recovery' : '', r.note] },
    { key: 'rights', label: 'May onboard', cls: 'small', text: (r) => this.pretty(r.rightsDirect) },
    { key: 'createdAt', label: 'Requested', cls: 'nowrap small' },
    { key: 'status', label: 'Status', search: (r) => this.label(r.status) },
    { key: 'act', label: '', cls: 'act' },
  ];
  kycLoading = signal(false);

  note = '';
  sanctions = 'CLEAR';
  kycType = 'IDENTITY';
  kycResult = 'PASS';
  kycDetails = 'simulated provider';
  readonly kycApp = signal<KycApplicationView | null>(null);
  readonly kycTasks = signal<KycTaskRow[]>([]);
  taskNotes: Record<string, string | null> = {};
  /** KYC checks are keyed by the business entity id (merchant/supplier) where one exists. */
  private kycEntityId: string | null = null;

  async ngOnInit() {
    await this.load();
    await this.loadKycTasks();
  }

  async loadKycTasks() {
    try {
      const rows = await firstValueFrom(this.http.get<KycTaskRow[]>('/api/kyc/tasks'));
      this.kycTasks.set(rows);
    } catch {
      this.kycTasks.set([]);
    }
  }

  kycTasksFor(orgId: string): KycTaskRow[] {
    return this.kycTasks().filter((t) => t.orgId === orgId);
  }

  isLiveStatus(st: string) {
    return ['DRAFT', 'ENTRY_CHECK', 'VERIFYING', 'IN_REVIEW'].includes(st);
  }

  async decideTask(taskId: string, outcome: string) {
    const note = this.taskNotes[taskId] || null;
    try {
      const v = await firstValueFrom(this.http.post<KycApplicationView>(`/api/kyc/tasks/${taskId}/decision`,
        { outcome, note }));
      this.kycApp.set(v);
      const done = outcome === 'APPROVE' ? 'approved' : outcome === 'RETURN' ? 'returned to the org' : `${outcome.toLowerCase()}d`;
      this.flash.set({ kind: outcome === 'APPROVE' ? 'ok' : 'bad',
        text: `Review task ${done} · application now ${v.status}` });
      this.taskNotes = { ...this.taskNotes, [taskId]: null };
      await this.loadKycTasks();
      const r = this.selected();
      if (r) void this.loadKyc(r);
      if (outcome !== 'RETURN') { await this.load(); }  // decided requests leave the pending queue
    } catch (e) {
      const p = readProblem(e);
      this.flash.set({ kind: 'bad', text: `Task decision refused (${p.code ?? p.status}): ${p.detail}` });
    }
  }

  setTab(t: OnboardingStatus) {
    this.tab.set(t);
    this.selected.set(null);
    this.kycSelected.set(null);
    this.kycChecks.set([]);
    void this.load();
  }

  label(s: string) {
    switch (s) {
      case 'PENDING_REVIEW': return 'Pending review';
      case 'ORG_ONBOARDING': return 'Org onboarding';
      case 'ADMIN_RECOVERY': return 'Admin recovery';
      default: return s ? s.charAt(0) + s.slice(1).toLowerCase().replace(/_/g, ' ') : s;
    }
  }

  /** "MERCHANT,SUPPLIER" → "Merchant, Supplier"; empty → "—". */
  pretty(list: string | null) {
    return list ? list.split(',').map((t) => this.label(t.trim())).join(', ') : '—';
  }

  typeLabel(t: string | null) { return t ? t.charAt(0) + t.slice(1).toLowerCase() : ''; }

  short(id: string | null) {
    return id ? (id.length > 14 ? id.slice(0, 8) + '…' : id) : '—';
  }

  async load() {
    this.loading.set(true);
    this.problem.set('');
    try {
      const rows = await firstValueFrom(this.http.get<OnboardingRequestRow[]>(
        '/api/orgs/onboarding', { params: { status: this.tab() } }));
      this.rows.set(rows);
    } catch (e) {
      this.problem.set(problemText(e));
    } finally {
      this.loading.set(false);
    }
  }

  pick(r: OnboardingRequestRow) {
    this.note = '';
    this.sanctions = 'CLEAR';
    this.pickKyc(r);
  }

  pickKyc(r: OnboardingRequestRow) {
    this.selected.set(r);
    this.kycSelected.set(r);
    this.kycChecks.set([]);
    this.kycApp.set(null);
    this.kycEntityId = null;
    void this.loadKyc(r);
    void this.loadKycTasks();
  }

  close() {
    this.selected.set(null);
    this.kycSelected.set(null);
  }

  pathText(k: KycApplicationView) {
    return k.treePath.map((p) => p.name).join(' › ');
  }

  private async loadKyc(r: OnboardingRequestRow) {
    if (!r.orgId || !r.orgType) return;
    this.kycLoading.set(true);
    try {
      const [org, app] = await Promise.all([
        firstValueFrom(this.http.get<OrgView>(`/api/orgs/${r.orgId}`)),
        firstValueFrom(this.http.get<KycApplicationView>(`/api/orgs/${r.orgId}/kyc`)),
      ]);
      if (this.kycSelected()?.id !== r.id) return;
      this.kycApp.set(app);
      this.kycEntityId = org.entityId ?? r.orgId;
      this.kycChecks.set(await firstValueFrom(this.http.get<KycCheckRow[]>(
        `/api/ops/kyc/${r.orgType.toLowerCase()}/${this.kycEntityId}`)));
    } catch (e) {
      const p = readProblem(e);
      this.flash.set({ kind: 'bad', text: p.status === 401 || p.status === 403
        ? 'Screening checks are only available to TPTS ops accounts (ops-admin-ops).'
        : `KYC unavailable: ${problemText(e)}` });
    } finally {
      this.kycLoading.set(false);
    }
  }

  async decide(approve: boolean) {
    const row = this.selected();
    if (!row) return;
    try {
      const res = await firstValueFrom(this.http.post<OrgDecisionResult>(
        `/api/orgs/onboarding/${row.id}/decide`,
        { approve, note: this.note || null, sanctionsResult: approve ? this.sanctions : null }));
      const verdict = res.status === 'ACTIVE'
        ? 'approved · org ACTIVE · activation link queued'
        : res.status === 'PENDING'
          ? 'approved · replacement admin activation queued'
          : `${res.status.toLowerCase()}`;
      this.flash.set({ kind: 'ok', text: `Decision recorded: ${verdict}` });
      this.close();
      // decided requests leave the pending tab; go to the matching one
      this.tab.set(res.status === 'ACTIVE' || res.status === 'APPROVED' ? 'APPROVED'
        : res.status === 'PENDING' ? 'PENDING_REVIEW' : (res.status as OnboardingStatus));
      await this.load();
    } catch (e) {
      const p = readProblem(e);
      const explain = p.code === 'INDEPENDENCE_RULE'
        ? ' — the requester cannot review their own request (second-person rule)'
        : p.code === 'DECIDE_AUTHORITY'
          ? ' — only platform staff or the parent org’s ADMIN decide'
          : p.code === 'RIGHTS_STALE'
            ? ' — the requester’s rights were narrowed since submission'
            : '';
      this.flash.set({ kind: 'bad', text: `Decision refused (${p.code ?? p.status}): ${p.detail}${explain}` });
    }
  }

  async addKycCheck() {
    const row = this.kycSelected() ?? this.selected();
    if (!row || !row.orgType || !row.orgId) return;
    const entityId = this.kycEntityId ?? row.orgId;
    this.kycLoading.set(true);
    try {
      const created = await firstValueFrom(this.http.post<KycCheckRow>(
        `/api/ops/kyc/${row.orgType.toLowerCase()}/${entityId}`,
        { checkType: this.kycType, result: this.kycResult, details: this.kycDetails || null }));
      this.flash.set({ kind: 'ok', text: `KYC check recorded: ${created.checkType} ${created.result}` });
      const all = [...this.kycChecks(), created];
      this.kycChecks.set(all);
      this.kycDetails = 'simulated provider';
    } catch (e) {
      const p = readProblem(e);
      this.flash.set({ kind: 'bad', text: `KYC check refused (${p.code ?? p.status}): ${p.detail}` });
    } finally {
      this.kycLoading.set(false);
    }
  }
}

export function problemText(e: unknown) {
  const p = readProblem(e);
  return `${p.title}${p.detail ? ` — ${p.detail}` : ''}`;
}
