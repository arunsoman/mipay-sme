import { ChangeDetectionStrategy, Component, computed, signal } from '@angular/core';
import { MiPayWidget } from '../../core/mipay-widget';
import { KpiRow } from '../../../../../src/app/design-system/organisms/kpi-row';
import { DataCol, DataTable, MpCell } from '../../../../../src/app/design-system/organisms/data-table';
import { MpTabs, TabOption } from '../../../../../src/app/design-system/molecules/tabs';
import { StatusBadge } from '../../../../../src/app/design-system/atoms/status-badge';
import { EmptyState } from '../../../../../src/app/design-system/molecules/empty-state';
import { MpAlert } from '../../../../../src/app/design-system/molecules/alert';
import { KYC_HINTS, refusal } from '../../../kyc/kyc-api';
import type { KycOrgsConfig } from './orgs.widget';
import type { KycOrgsList } from './orgs.contract';

type Org = KycOrgsList[number];
type Filter = 'all' | 'progress' | 'approved' | 'stopped';

const IN_PROGRESS = ['DRAFT', 'ENTRY_CHECK', 'VERIFYING', 'IN_REVIEW'];
const STOPPED = ['REJECTED', 'BLOCKED', 'WITHDRAWN'];
const groupOf = (o: Org): Filter =>
  o.status === 'APPROVED' ? 'approved' : STOPPED.includes(o.status ?? '') ? 'stopped' : IN_PROGRESS.includes(o.status ?? '') ? 'progress' : 'all';
const words = (s: string | null) => (s ? s.charAt(0) + s.slice(1).toLowerCase().replace(/_/g, ' ') : '—');
const when = (iso: string | null) => (iso ? new Date(iso).toLocaleDateString('en-NA', { day: 'numeric', month: 'short', year: 'numeric' }) : '—');

/** The board: counts, a status filter and the table; the server decides which organizations are visible. */
@Component({
  selector: 'mpw-kyc-orgs',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [KpiRow, MpTabs, DataTable, MpCell, StatusBadge, EmptyState, MpAlert],
  template: `
    @if (rows(); as all) {
      @if (all.length) {
        <kpi-row [tiles]="tiles()" />
        <mp-tabs [options]="tabs()" [value]="filter()" (valueChange)="setFilter($event)" label="KYC status" />
        <mp-data-table [cols]="cols()" [rows]="shown()" [pageSize]="config.pageSize" placeholder="Search organizations"
                       empty="No organization in this state." (rowClick)="pick($event)">
          <ng-template mpCell="status" let-row><status-badge [status]="row.status ?? 'NOT_STARTED'" /></ng-template>
        </mp-data-table>
      } @else {
        <empty-state title="No organizations yet" hint="Organizations you onboard appear here with their KYC status." />
      }
    } @else if (!error()) {
      <mp-data-table [cols]="cols()" [rows]="null" loading="Loading organizations…" />
    }
    @if (error(); as e) {<mp-alert kind="bad">{{ e }}</mp-alert>}
  `,
  styles: [`
    :host { display: grid; gap: var(--sp-3, 12px); min-width: 0; }
  `],
})
export class KycOrgsWidget extends MiPayWidget<KycOrgsConfig> {
  readonly rows = signal<KycOrgsList | null>(null);
  readonly filter = signal<Filter>('all');
  readonly error = signal('');

  private readonly allCols: DataCol<Org>[] = [
    { key: 'orgName', label: 'Organization', text: (o) => o.orgName ?? '—' },
    { key: 'orgType', label: 'Type', text: (o) => words(o.orgType) },
    { key: 'status', label: 'KYC status', text: (o) => words(o.status) },
    { key: 'updatedAt', label: 'Last update', text: (o) => when(o.updatedAt), search: (o) => o.updatedAt ?? '' },
  ];
  /** Phone: who and their status; type and date from tablet up. */
  readonly cols = computed(() => (this.size().tier === 'xs'
    ? this.allCols.filter((c) => c.key === 'orgName' || c.key === 'status') : this.allCols));

  private readonly count = (f: Filter) => (this.rows() ?? []).filter((o) => f === 'all' || groupOf(o) === f).length;
  readonly tiles = computed(() => [
    // counts, not money: kpi-row renders N$ unless money is false
    { label: 'Organizations', amount: this.count('all'), money: false },
    { label: 'In progress', amount: this.count('progress'), sub: 'draft, checking or in review', money: false },
    { label: 'Approved', amount: this.count('approved'), money: false },
    { label: 'Rejected or blocked', amount: this.count('stopped'), money: false },
  ]);
  readonly tabs = computed<TabOption[]>(() => [
    { value: 'all', label: `All (${this.count('all')})` },
    { value: 'progress', label: `In progress (${this.count('progress')})` },
    { value: 'approved', label: `Approved (${this.count('approved')})` },
    { value: 'stopped', label: `Rejected or blocked (${this.count('stopped')})` },
  ]);
  readonly shown = computed(() => (this.rows() ?? []).filter((o) => this.filter() === 'all' || groupOf(o) === this.filter()));

  constructor() {
    super();
    this.data.query<KycOrgsList>('kyc.orgs.list').subscribe({
      next: (rows) => {
        this.rows.set(rows);
        this.state.set(rows.length ? 'ready' : 'empty');
      },
      error: (e: unknown) => this.error.set(refusal(e, KYC_HINTS)),
    });
  }

  setFilter(v: string) { this.filter.set(v as Filter); }

  /** Picking an organization tells the page which one it is. */
  pick(o: Org) { this.publish('orgId', o.orgId); }
}
