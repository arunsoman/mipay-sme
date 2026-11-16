import { ChangeDetectionStrategy, Component, computed, signal } from '@angular/core';
import { MiPayWidget } from '../../core/mipay-widget';
import { words, when } from '../../core/text';
import { DataCol, DataTable, MpCell } from '../../../../../src/app/design-system/organisms/data-table';
import { MpTabs, TabOption } from '../../../../../src/app/design-system/molecules/tabs';
import { ApprovalActions } from '../../../../../src/app/design-system/organisms/approval-actions';
import { FormField } from '../../../../../src/app/design-system/molecules/form-field';
import { KvPair } from '../../../../../src/app/design-system/molecules/kv-pair';
import { MpAlert } from '../../../../../src/app/design-system/molecules/alert';
import { StatusBadge } from '../../../../../src/app/design-system/atoms/status-badge';
import { EmptyState } from '../../../../../src/app/design-system/molecules/empty-state';
import { KYC_HINTS, refusal } from '../../../kyc/kyc-api';
import type { OrgOnboardingQueueConfig } from './onboarding-queue.widget';
import type { OnboardingRequest, OrgDecisionResult, OrgOnboardingDecideRequest, OrgOnboardingList } from './onboarding-queue.contract';

const SANCTIONS = [
  { value: '', label: 'Not screened here' },
  { value: 'CLEAR', label: 'Clear' },
  { value: 'MATCH', label: 'Possible match' },
];

/** The onboarding queue: a status switch, the requests, and a decision strip for the picked one. */
@Component({
  selector: 'mpw-org-onboarding-queue',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DataTable, MpCell, MpTabs, ApprovalActions, FormField, KvPair, MpAlert, StatusBadge, EmptyState],
  template: `
    <mp-tabs [options]="tabs" [value]="status()" (valueChange)="setStatus($event)" label="Request status" />
    @if (rows(); as list) {
      @if (list.length) {
        <mp-data-table [cols]="cols()" [rows]="list" [pageSize]="config.pageSize" [selectable]="true"
                       placeholder="Search requests" (rowClick)="pick($event)">
          <ng-template mpCell="status" let-r><status-badge [status]="r.status ?? 'PENDING'" /></ng-template>
        </mp-data-table>
      } @else {
        <empty-state title="No requests here" hint="Organizations waiting to be let in appear here." />
      }
    } @else {
      <mp-data-table [cols]="cols()" [rows]="null" loading="Loading requests…" />
    }

    @if (selected(); as r) {
      <section class="decide" data-selected-request>
        <div class="who"><b>{{ r.orgName ?? 'Unnamed organization' }}</b>
          <span class="dim">{{ kindText(r) }} · {{ type(r.orgType) }}</span></div>
        <div class="kv">
          <kv-pair k="Requested">{{ day(r.createdAt) }}</kv-pair>
          <kv-pair k="By">{{ r.requestedBy ?? '—' }} ({{ type(r.requestedByRole) }})</kv-pair>
          <kv-pair k="Rights">{{ r.rightsDirect || '—' }}</kv-pair>
          <kv-pair k="May hand down">{{ r.rightsCeiling || '—' }}</kv-pair>
        </div>
        @if (r.note) {<p class="dim">{{ r.note }}</p>}
        @if (r.status === 'PENDING_REVIEW') {
          <mp-form-field key="note" label="Note" [value]="note()" (changed)="note.set($event)"
                         hint="Recorded with the decision." />
          <mp-form-field key="sanctions" label="Sanctions screening" kind="select" [options]="sanctionsOptions"
                         [value]="sanctions()" (changed)="sanctions.set($event)" />
          <approval-actions data-action="decide" perm="org.onboarding:authorize" [status]="r.status"
                            [note]="note()" [sanctions]="sanctions()" (decide)="decide(r, $event.approve)" />
        } @else {
          <kv-pair k="Decided">{{ type(r.decision) }} · {{ day(r.reviewedAt) }} by {{ r.reviewedBy ?? '—' }}</kv-pair>
        }
      </section>
    }
    @if (flash(); as f) {<mp-alert [kind]="f.kind">{{ f.text }}</mp-alert>}
  `,
  styles: [`
    :host { display: grid; gap: var(--sp-3, 12px); min-width: 0; }
    .decide { display: grid; gap: var(--sp-2, 8px); padding-top: var(--sp-3, 12px); border-top: 1px solid var(--a-border, #2b2b2b); }
    .who { display: flex; flex-wrap: wrap; gap: 4px 10px; align-items: baseline; overflow-wrap: anywhere; }
    .kv { display: grid; gap: var(--sp-1, 4px); }
    .dim { color: var(--a-dim, #a3a3a3); font-size: var(--fs-sm, 12.5px); margin: 0; overflow-wrap: anywhere; }
  `],
})
export class OrgOnboardingQueueWidget extends MiPayWidget<OrgOnboardingQueueConfig> {
  readonly tabs: TabOption[] = [
    { value: 'PENDING_REVIEW', label: 'Waiting' },
    { value: 'APPROVED', label: 'Approved' },
    { value: 'REJECTED', label: 'Rejected' },
    { value: '', label: 'All' },
  ];
  readonly sanctionsOptions = SANCTIONS;
  readonly status = signal('PENDING_REVIEW');
  readonly rows = signal<OrgOnboardingList | null>(null);
  readonly selected = signal<OnboardingRequest | null>(null);
  readonly note = signal('');
  readonly sanctions = signal('');
  readonly flash = signal<{ kind: 'ok' | 'bad'; text: string } | null>(null);
  readonly type = words;
  readonly day = when;

  private readonly allCols: DataCol<OnboardingRequest>[] = [
    { key: 'orgName', label: 'Organization', text: (r) => r.orgName ?? '—' },
    { key: 'orgType', label: 'Type', text: (r) => words(r.orgType) },
    { key: 'kind', label: 'Request', text: (r) => this.kindText(r) },
    { key: 'createdAt', label: 'Requested', text: (r) => when(r.createdAt) },
    { key: 'status', label: 'Status', text: (r) => words(r.status) },
  ];
  /** Phone: who and where it stands. */
  readonly cols = computed(() => (this.size().tier === 'xs'
    ? this.allCols.filter((c) => c.key === 'orgName' || c.key === 'status') : this.allCols));

  constructor() {
    super();
    this.load();
  }

  kindText(r: OnboardingRequest) {
    return r.kind === 'ADMIN_RECOVERY' ? 'New administrator' : 'New organization';
  }

  setStatus(v: string) {
    this.status.set(v);
    this.selected.set(null);
    this.load();
  }

  private load() {
    this.rows.set(null);
    const params = this.status() ? { status: this.status() } : {};
    this.data.query<OrgOnboardingList>('org.onboarding.list', params).subscribe({
      next: (list) => { this.rows.set(list); this.state.set(list.length ? 'ready' : 'empty'); },
      error: () => this.rows.set([]),
    });
  }

  pick(r: OnboardingRequest) {
    this.selected.set(r);
    this.note.set('');
    this.sanctions.set('');
    this.flash.set(null);
    this.publish('orgName', r.orgName);
    this.publish('orgId', r.orgId);
  }

  decide(r: OnboardingRequest, approve: boolean) {
    const body: OrgOnboardingDecideRequest = { approve, note: this.note() || null, sanctionsResult: this.sanctions() || null };
    this.data.command<OrgDecisionResult>('org.onboarding.decide', { id: r.id, body }).subscribe({
      next: (res) => {
        this.flash.set({ kind: 'ok', text: res.message ?? `${approve ? 'Approved' : 'Rejected'}: ${r.orgName ?? 'the request'} is now ${words(res.status)}.` });
        this.selected.set(null);
        this.load();
      },
      error: (e: unknown) => this.flash.set({ kind: 'bad', text: refusal(e, KYC_HINTS) }),
    });
  }
}
