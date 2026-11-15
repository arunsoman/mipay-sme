import { ChangeDetectionStrategy, Component, computed, signal } from '@angular/core';
import { MiPayWidget } from '../../core/mipay-widget';
import { DataTable, DataCol } from '../../../../../src/app/design-system/organisms/data-table';
import { ApprovalActions } from '../../../../../src/app/design-system/organisms/approval-actions';
import { FormField } from '../../../../../src/app/design-system/molecules/form-field';
import { MpAlert } from '../../../../../src/app/design-system/molecules/alert';
import { EmptyState } from '../../../../../src/app/design-system/molecules/empty-state';
import { KYC_HINTS, refusal } from '../../../kyc/kyc-api';
import type { KycTasksConfig } from './tasks.widget';
import type { KycTasksDecide, KycTasksDecideRequest, KycTasksList } from './tasks.contract';

type Task = KycTasksList[number];

const when = (iso: string | null) => (iso ? new Date(iso).toLocaleDateString('en-NA', { day: 'numeric', month: 'short', year: 'numeric' }) : '—');
const words = (s: string | null) => (s ? s.charAt(0) + s.slice(1).toLowerCase().replace(/_/g, ' ') : '—');

/**
 * The review queue. Rows come from the caller's inbox (the server decides which tasks the caller
 * may see); the decision strip shows only with kyc.task:authorize (approval-actions' perm).
 */
@Component({
  selector: 'mpw-kyc-tasks',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DataTable, ApprovalActions, FormField, MpAlert, EmptyState],
  template: `
    @if (tasks(); as rows) {
      @if (rows.length) {
        <mp-data-table [cols]="cols()" [rows]="rows" [pageSize]="config.pageSize" placeholder="Search reviews"
                       (rowClick)="pick($event)" />
      } @else {
        <empty-state title="No open reviews" hint="KYC applications waiting for you appear here." />
      }
    } @else {
      <mp-data-table [cols]="cols()" [rows]="null" loading="Loading reviews…" />
    }

    @if (selected(); as t) {
      <section class="decide" data-selected-task>
        <div class="who"><b>{{ t.orgName }}</b> <span class="dim">{{ stage(t) }} · opened {{ opened(t) }}</span></div>
        @if (t.openNote) {<p class="dim">{{ t.openNote }}</p>}
        <mp-form-field key="note" label="Note to the applicant" [value]="note()" (changed)="note.set($event)"
                       hint="Recorded with the decision; required by policy when you reject." />
        <approval-actions data-action="decide" perm="kyc.task:authorize" [status]="t.applicationStatus ?? 'PENDING'"
                          approveLabel="Approve" rejectLabel="Reject" [note]="note()" (decide)="decide(t, $event.approve)" />
      </section>
    }
    @if (flash(); as f) {<mp-alert [kind]="f.kind">{{ f.text }}</mp-alert>}
  `,
  styles: [`
    :host { display: grid; gap: var(--sp-3, 12px); min-width: 0; }
    .decide { display: grid; gap: var(--sp-2, 8px); padding-top: var(--sp-3, 12px); border-top: 1px solid var(--a-border, #2b2b2b); }
    .who { display: flex; flex-wrap: wrap; gap: 4px 10px; align-items: baseline; }
    .dim { color: var(--a-dim, #a3a3a3); font-size: var(--fs-sm, 12.5px); margin: 0; }
  `],
})
export class KycTasksWidget extends MiPayWidget<KycTasksConfig> {
  readonly tasks = signal<KycTasksList | null>(null);
  readonly selected = signal<Task | null>(null);
  readonly note = signal('');
  readonly flash = signal<{ kind: 'ok' | 'bad'; text: string } | null>(null);

  private readonly allCols: DataCol<Task>[] = [
    { key: 'orgName', label: 'Organization', text: (t) => t.orgName ?? '—' },
    { key: 'stage', label: 'Stage', text: (t) => this.stage(t) },
    { key: 'applicationStatus', label: 'Status', text: (t) => words(t.applicationStatus) },
    { key: 'createdAt', label: 'Opened', text: (t) => when(t.createdAt), search: (t) => t.createdAt ?? '' },
  ];
  /** Mobile first: a phone shows who and where it stands; the stage and date appear from tablet up. */
  readonly cols = computed(() => (this.size().tier === 'xs'
    ? this.allCols.filter((c) => c.key === 'orgName' || c.key === 'applicationStatus')
    : this.allCols));

  constructor() {
    super();
    this.load();
  }

  stage(t: Task) { return `${words(t.stageKey)} (step ${t.stageSeq})`; }
  opened(t: Task) { return when(t.createdAt); }

  load() {
    this.data.query<KycTasksList>('kyc.tasks.list').subscribe({
      next: (rows) => {
        this.tasks.set(rows);
        this.state.set(rows.length ? 'ready' : 'empty');
      },
      error: (e: unknown) => this.flash.set({ kind: 'bad', text: refusal(e, KYC_HINTS) }),
    });
  }

  /** Picking a task shows its decision strip and tells the page which org it is. */
  pick(t: Task) {
    this.selected.set(t);
    this.note.set('');
    this.flash.set(null);
    this.publish('orgId', t.orgId);
  }

  decide(t: Task, approve: boolean) {
    // the body is typed by the request contract (KycDecisionRequestV2); the port re-checks it at runtime
    const body: KycTasksDecideRequest = { outcome: approve ? 'APPROVE' : 'REJECT', note: this.note() || null };
    this.data.command<KycTasksDecide>('kyc.tasks.decide', { taskId: t.id, body }).subscribe({
      next: (v) => {
        this.flash.set({ kind: 'ok', text: `${approve ? 'Approved' : 'Rejected'}: ${v.orgName ?? t.orgName} is now ${words(v.status)}.` });
        this.selected.set(null);
        this.load();
      },
      error: (e: unknown) => this.flash.set({ kind: 'bad', text: refusal(e, KYC_HINTS) }),
    });
  }
}
