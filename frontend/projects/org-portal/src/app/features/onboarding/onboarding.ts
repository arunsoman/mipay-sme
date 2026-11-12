import { MpPerm } from '../../../../../shared/web-core/perm-directive';
import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { PortalAuthService } from '../../core/auth';
import { RouterLink } from '@angular/router';
import { OnboardingRequestRow, OnboardingStatus, OrgDecisionResult, readProblem, refusal, statusClass } from '../../core/api';
import { OnboardChild } from '../../../../../shared/kyc/onboard-child';
import { Modal } from '../../../../../shared/ui/modal';

const HINTS: Record<string, string> = {
  ONBOARDING_NOT_ALLOWED: 'your organization is not allowed to onboard this type',
  RIGHTS_EXCEED_CEILING: 'you can only pass on rights your organization holds',
  RIGHTS_INVALID_FOR_TYPE: 'that type can only onboard types below its own rank',
  RANK_RULE: 'that type cannot sit under your organization',
  ORG_SUSPENDED: 'your organization (or one above it) is suspended',
  DUPLICATE_PHONE: 'that phone number is already registered',
  KYC_INCOMPLETE: 'the KYC application is incomplete — fix it or untick it and add it later',
  KYC_INVALID: 'the KYC application has invalid values',
  INDEPENDENCE_RULE: 'a second ADMIN of your organization must review requests you submitted',
  DECIDE_AUTHORITY: 'only the parent organization’s ADMIN or platform staff decide',
  RIGHTS_STALE: 'your organization’s rights were narrowed after the request was submitted',
  ALREADY_DECIDED: 'someone else decided this request first',
};

/**
 * Onboarding for an org ADMIN:
 *  - "Onboard an organization" (shared mp-onboard-child): only the child
 *    types this org's effective DIRECT rights allow, then straight into the
 *    new org's KYC application.
 *  - "Requests": requests raised under this org. New organizations go live
 *    through the KYC workflow (open their KYC); admin-recovery requests are
 *    still decided here.
 */
@Component({
  selector: 'mpo-onboarding',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpPerm, FormsModule, DatePipe, RouterLink, OnboardChild, Modal],
  template: `
    <div class="head">
      <h2>Onboarding</h2>
      <button class="ghost" (click)="reload()">Refresh</button>
      <!-- new org (shared, rights-based, continues into KYC) -->
      <mp-onboard-child *mpPerm="'org.onboarding:create'" [compact]="true" (onboarded)="onOnboarded()" />
    </div>
    @if (flash(); as f) {<div class="flash {{ f.kind }}">{{ f.text }}</div>}

    <!-- ---------------- requests ---------------- -->
    <section class="panel pad">
      <div class="qhead">
        <div class="ptitle">Requests</div>
        <div class="tabs">
          @for (t of tabs; track t) {
            <button [class.tab-active]="tab() === t" (click)="setTab(t)">{{ tabLabel(t) }}</button>
          }
        </div>
      </div>
      @if (queueError(); as e) {<div class="err">{{ e }}</div>}

      @if (selected(); as r) {
        <mp-modal [open]="true" size="narrow" eyebrow="Review request" [title]="r.orgName ?? 'Organization'"
                  [subtitle]="title(r.orgType)" (closed)="selected.set(null)">
          <div class="fields">
            <label>Note <input name="note" [(ngModel)]="note" placeholder="optional" /></label>
            @if (auth.me()?.simulated) {
              <label>Sanctions screening (simulated)
                <select name="sanc" [(ngModel)]="sanctions">
                  <option value="CLEAR">CLEAR</option>
                  <option value="FAIL">FAIL (blocks the org)</option>
                </select></label>
            }
          </div>
          <div modal-footer>
            <button class="ghost" (click)="selected.set(null)">Cancel</button>
            <button class="danger" (click)="decide(false)" [disabled]="busy()">Reject</button>
            <button class="primary" (click)="decide(true)" [disabled]="busy()">Approve</button>
          </div>
        </mp-modal>
      }

      <table class="grid">
        <thead><tr><th>Organization</th><th>May onboard</th><th>Submitted</th><th>Status</th><th></th></tr></thead>
        <tbody>
          @for (r of rows(); track r.id) {
            <tr>
              <td><div class="nm">{{ r.orgName }}</div>
                <div class="muted small">{{ title(r.orgType) }}@if (r.kind === 'ADMIN_RECOVERY') { · admin recovery}</div></td>
              <td class="small">{{ words(r.rightsDirect) }}</td>
              <td>{{ r.createdAt | date:'MMM d, HH:mm' }}
                @if (mine(r)) {<div class="muted small">by you</div>}</td>
              <td><span class="chip {{ cls(r.status) }}">{{ tabLabel(r.status) }}</span>
                @if (r.reviewedAt) {<div class="muted small">decided {{ r.reviewedAt | date:'MMM d' }}</div>}</td>
              <td>
                @if (r.kind === 'ORG_ONBOARDING') {
                  <a *mpPerm="'kyc.application:read'" class="btn ghost" routerLink="/kyc" [queryParams]="{ org: r.orgId }">View KYC</a>
                } @else if (r.status === 'PENDING_REVIEW') {
                  @if (mine(r)) {
                    <span class="muted small" title="Second-person rule">awaiting another admin</span>
                  } @else {
                    <button *mpPerm="'org.onboarding:authorize'" (click)="pick(r)">Review</button>
                  }
                }
              </td>
            </tr>
          } @empty {
            <tr><td colspan="5" class="muted">No {{ tabLabel(tab()).toLowerCase() }} requests.</td></tr>
          }
        </tbody>
      </table>
    </section>
  `,
  styles: [`
    .head { display: flex; align-items: center; gap: 10px 14px; margin-bottom: 14px; flex-wrap: wrap; }
    .nm { font-weight: 600; }
    td { vertical-align: middle; }
    a.btn { display: inline-flex; padding: 6px 14px; border-radius: 999px; border: 1px solid var(--a-border); color: var(--a-text);
            text-decoration: none; font-size: 13px; white-space: nowrap; }
    section { overflow-x: auto; }
    h2 { margin: 0; font-size: 30px; line-height: 1.1; flex: 1; }
    section { margin-bottom: 16px; }
    .pad { padding: 16px; }
    .ptitle { font-size: 14px; font-weight: 600; margin-bottom: 12px; }
    .types { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 14px; }
    .types button.sel { border-color: var(--a-teal); color: var(--a-teal); }
    .form { display: grid; gap: 12px; }
    fieldset { border: 1px solid var(--a-border); border-radius: 8px; padding: 12px; margin: 0; }
    legend { color: var(--a-dim); font-size: var(--fs-sm); padding: 0 6px; }
    .fields { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 10px; }
    label { display: grid; gap: 5px; font-size: var(--fs-sm); color: var(--a-dim); }
    .rights { max-width: 520px; }
    .rights input, .toggle input { width: auto; }
    .toggle { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
    .row { display: flex; gap: 8px; flex-wrap: wrap; }
    .hint { color: var(--a-dim); font-size: var(--fs-xs); margin-top: 8px; }
    .qhead { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; }
    .qhead .ptitle { margin: 0; }
    .tabs { display: flex; gap: 4px; flex-wrap: wrap; }
    .tabs button { border-color: transparent; background: transparent; color: var(--a-dim); }
    .tabs button.tab-active { color: var(--a-teal); background: var(--a-panel-2); border-color: var(--a-border); }
    .decide { border: 1px solid var(--a-border); border-radius: 8px; padding: 12px; margin: 12px 0; display: grid; gap: 10px; }
    .dtitle { font-size: 13.5px; }
    table.grid { margin-top: 10px; }
    .btnlink { color: var(--a-teal); font-size: var(--fs-sm); }
    .err { color: var(--a-red); font-size: var(--fs-sm); margin: 6px 0; }
    .flash { font-size: var(--fs-sm); margin: 0 0 12px; padding: 8px 12px; border-radius: 8px; }
    .flash.ok { background: color-mix(in srgb, var(--a-green) 14%, transparent); color: var(--a-green); }
    .flash.bad { background: color-mix(in srgb, var(--a-red) 14%, transparent); color: var(--a-red); }
    @media (max-width: 700px) { table.grid { display: block; overflow-x: auto; } }
  `],
})
export class Onboarding implements OnInit {
  private readonly http = inject(HttpClient);
  readonly auth = inject(PortalAuthService);
  readonly cls = statusClass;

  readonly tabs: OnboardingStatus[] = ['PENDING_REVIEW', 'APPROVED', 'REJECTED', 'BLOCKED'];

  readonly tab = signal<OnboardingStatus>('PENDING_REVIEW');
  readonly rows = signal<OnboardingRequestRow[]>([]);
  readonly queueError = signal('');
  readonly selected = signal<OnboardingRequestRow | null>(null);
  note = '';
  sanctions = 'CLEAR';

  readonly busy = signal(false);
  readonly flash = signal<{ kind: 'ok' | 'bad'; text: string } | null>(null);

  async ngOnInit() { await this.reload(); }

  async reload() {
    await this.loadQueue();
  }

  async onOnboarded() {
    this.tab.set('PENDING_REVIEW');
    await this.loadQueue();
  }

  title(t: string | null) { return t ? t.charAt(0) + t.slice(1).toLowerCase() : '—'; }
  tabLabel(s: string) { return s === 'PENDING_REVIEW' ? 'Pending' : this.title(s); }
  words(csv: string | null) { const xs = (csv ?? '').split(',').map((x) => x.trim()).filter(Boolean); return xs.length ? xs.map((x) => this.title(x)).join(', ') : '—'; }
  mine(r: OnboardingRequestRow) { return r.requestedBy === this.auth.me()?.userId; }

  setTab(t: OnboardingStatus) {
    this.tab.set(t);
    this.selected.set(null);
    void this.loadQueue();
  }

  private async loadQueue() {
    this.queueError.set('');
    try {
      this.rows.set(await firstValueFrom(this.http.get<OnboardingRequestRow[]>(
        '/api/orgs/onboarding', { params: { status: this.tab() } })));
    } catch (e) {
      this.queueError.set(refusal(e, HINTS));
    }
  }

  pick(r: OnboardingRequestRow) {
    this.selected.set(r);
    this.note = '';
    this.sanctions = 'CLEAR';
  }

  async decide(approve: boolean) {
    const r = this.selected();
    if (!r) return;
    this.busy.set(true);
    try {
      const res = await firstValueFrom(this.http.post<OrgDecisionResult>(
        `/api/orgs/onboarding/${r.id}/decide`,
        { approve, note: this.note || null, sanctionsResult: approve ? this.sanctions : null }));
      this.flash.set({ kind: 'ok', text: res.status === 'ACTIVE'
        ? `${r.orgName} approved — the administrator has been sent an activation link.`
        : `${r.orgName}: ${res.status.toLowerCase()}.` });
      this.selected.set(null);
      await this.loadQueue();
    } catch (e) {
      const p = readProblem(e);
      this.flash.set({ kind: 'bad', text: refusal(e, HINTS) });
      if (p.code === 'ALREADY_DECIDED') { this.selected.set(null); await this.loadQueue(); }
    } finally {
      this.busy.set(false);
    }
  }
}
