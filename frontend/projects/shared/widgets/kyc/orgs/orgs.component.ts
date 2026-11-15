import { ChangeDetectionStrategy, Component, computed, signal } from '@angular/core';
import { MiPayWidget } from '../../core/mipay-widget';
import { connectServerList } from '../../core/server-list';
import { ServerQuery } from '../../../../../src/app/design-system/molecules/table-view';
import { DataCol, DataTable, MpCell } from '../../../../../src/app/design-system/organisms/data-table';
import { MpTabs, TabOption } from '../../../../../src/app/design-system/molecules/tabs';
import { StatusBadge } from '../../../../../src/app/design-system/atoms/status-badge';
import { EmptyState } from '../../../../../src/app/design-system/molecules/empty-state';
import { MpAlert } from '../../../../../src/app/design-system/molecules/alert';
import { KYC_HINTS, refusal } from '../../../kyc/kyc-api';
import type { KycOrgsConfig } from './orgs.widget';
import type { KycOrgsRow } from './orgs.contract';

type Group = 'all' | 'progress' | 'approved' | 'stopped';

/** Each chip is a server filter on status (a comma means any of). */
export const STATUS_OF: Record<Group, string> = {
  all: '',
  progress: 'DRAFT,ENTRY_CHECK,VERIFYING,IN_REVIEW',
  approved: 'APPROVED',
  stopped: 'REJECTED,BLOCKED,WITHDRAWN',
};
const words = (s: string | null) => (s ? s.charAt(0) + s.slice(1).toLowerCase().replace(/_/g, ' ') : '—');
const when = (iso: string | null) => (iso ? new Date(iso).toLocaleDateString('en-NA', { day: 'numeric', month: 'short', year: 'numeric' }) : '—');

/** Status chips and the table; the server searches, filters, sorts and pages. */
@Component({
  selector: 'mpw-kyc-orgs',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpTabs, DataTable, MpCell, StatusBadge, EmptyState, MpAlert],
  template: `
    @if (nothingAtAll()) {
      <empty-state title="No KYC applications yet" hint="Organizations you onboard appear here with their KYC status." />
    } @else {
      <mp-tabs [options]="tabs" [value]="group()" (valueChange)="setGroup($event)" label="KYC status" />
      <mp-data-table [server]="list" [rows]="list.rows()" [cols]="cols()" placeholder="Search organizations"
                     loading="Loading KYC applications…" empty="No application in this state."
                     [selectable]="true" (rowClick)="pick($event)">
        <ng-template mpCell="status" let-row><status-badge [status]="row.status ?? 'NOT_STARTED'" /></ng-template>
      </mp-data-table>
    }
    @if (error(); as e) {<mp-alert kind="bad">{{ e }}</mp-alert>}
  `,
  styles: [`
    :host { display: grid; gap: var(--sp-3, 12px); min-width: 0; }
  `],
})
export class KycOrgsWidget extends MiPayWidget<KycOrgsConfig> {
  readonly list = new ServerQuery<KycOrgsRow>(this.config.pageSize, { field: 'updatedAt', dir: 'desc' });
  readonly group = signal<Group>('all');
  readonly error = signal('');
  /** the very first answer had nothing, with no search or filter: show the empty state instead of a table */
  readonly nothingAtAll = signal(false);

  readonly tabs: TabOption[] = [
    { value: 'all', label: 'All' },
    { value: 'progress', label: 'In progress' },
    { value: 'approved', label: 'Approved' },
    { value: 'stopped', label: 'Rejected or blocked' },
  ];

  private readonly allCols: DataCol<KycOrgsRow>[] = [
    { key: 'orgName', label: 'Organization', text: (o) => o.orgName ?? '—', sort: 'orgName' },
    { key: 'orgType', label: 'Type', text: (o) => words(o.orgType), sort: 'orgType' },
    { key: 'status', label: 'KYC status', text: (o) => words(o.status), sort: 'status' },
    { key: 'updatedAt', label: 'Last update', text: (o) => when(o.updatedAt), sort: 'updatedAt' },
  ];
  /** Phone: who and their status; type and date from tablet up. */
  readonly cols = computed(() => (this.size().tier === 'xs'
    ? this.allCols.filter((c) => c.key === 'orgName' || c.key === 'status') : this.allCols));

  private first = true;

  constructor() {
    super();
    connectServerList(this.data, 'kyc.orgs.search', this.list, {
      onPage: (page) => {
        if (this.first) this.nothingAtAll.set(page.total === 0);
        this.first = false;
        this.error.set('');
        this.state.set(this.nothingAtAll() ? 'empty' : 'ready');
      },
      onError: (e) => this.error.set(refusal(e, KYC_HINTS)),
    });
  }

  setGroup(v: string) {
    this.group.set(v as Group);
    this.list.setFilter('status', STATUS_OF[v as Group]);
  }

  /** Picking an organization tells the page which one it is. */
  pick(o: KycOrgsRow) {
    this.publish('orgName', o.orgName);
    this.publish('orgId', o.orgId);
  }
}
