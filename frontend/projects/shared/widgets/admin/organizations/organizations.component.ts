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
import type { AdminOrganizationsConfig } from './organizations.widget';
import type { OrgRow } from './organizations.contract';

type Chip = 'all' | 'attention' | 'pending' | 'active';

/** Each chip is one server filter. "Needs attention" = any warning the server raises. */
export const CHIP_FILTER: Record<Chip, [string, string]> = {
  all: ['', ''],
  attention: ['warnings', "Access not set,Can't add users,Can't complete KYC,Access not applied"],
  pending: ['status', 'PENDING'],
  active: ['status', 'ACTIVE'],
};
const words = (s: string | null) => (s ? s.charAt(0) + s.slice(1).toLowerCase().replace(/_/g, ' ') : '—');

/** Quick filters and the table; the server searches, filters, sorts and pages. */
@Component({
  selector: 'mpw-admin-organizations',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpTabs, DataTable, MpCell, StatusBadge, EmptyState, MpAlert],
  template: `
    @if (nothingAtAll()) {
      <empty-state title="No organizations yet" hint="Organizations appear here once they are onboarded." />
    } @else {
      <mp-tabs [options]="tabs" [value]="chip()" (valueChange)="setChip($event)" label="Show" />
      <mp-data-table [server]="list" [rows]="list.rows()" [cols]="cols()" placeholder="Search organizations"
                     loading="Loading organizations…" empty="No organization here." [selectable]="true"
                     [rowClass]="rowClass" (rowClick)="pick($event)">
        <ng-template mpCell="kycStatus" let-row><status-badge [status]="row.kycStatus ?? 'NOT_STARTED'" /></ng-template>
        <ng-template mpCell="warnings" let-row>
          @for (w of warningsOf(row); track w) {<span class="warn" data-warning>⚠ {{ w }}</span>}
          @empty {<span class="ok">✓</span>}
        </ng-template>
      </mp-data-table>
    }
    @if (error(); as e) {<mp-alert kind="bad">{{ e }}</mp-alert>}
  `,
  styles: [`
    :host { display: grid; gap: var(--sp-3, 12px); min-width: 0; }
    .warn { display: inline-block; max-width: 100%; overflow-wrap: anywhere; margin: 0 6px 2px 0; padding: 1px 8px; border-radius: 999px;
            font-size: var(--fs-xs, 11.5px); font-weight: 700;
            color: var(--mp-amber, #f7c66b); border: 1px solid color-mix(in srgb, var(--mp-amber, #f7c66b) 50%, transparent); }
    .ok { color: var(--mp-text-dim); }
  `],
})
export class AdminOrganizationsWidget extends MiPayWidget<AdminOrganizationsConfig> {
  readonly list = new ServerQuery<OrgRow>(this.config.pageSize, { field: 'name', dir: 'asc' });
  readonly chip = signal<Chip>('all');
  readonly error = signal('');
  readonly nothingAtAll = signal(false);
  private first = true;

  readonly tabs: TabOption[] = [
    { value: 'all', label: 'All' },
    { value: 'attention', label: 'Needs attention' },
    { value: 'pending', label: 'Pending' },
    { value: 'active', label: 'Active' },
  ];

  private readonly allCols: DataCol<OrgRow>[] = [
    { key: 'name', label: 'Organization', text: (o) => o.name ?? '—', sort: 'name' },
    { key: 'type', label: 'Type', text: (o) => words(o.type), sort: 'type' },
    { key: 'kycStatus', label: 'KYC', text: (o) => words(o.kycStatus), sort: 'kycStatus' },
    { key: 'users', label: 'People', text: (o) => o.users, sort: 'users', align: 'right' },
    { key: 'preset', label: 'Access', text: (o) => (o.preset ? words(o.preset) : o.features ? `${o.features} features` : '—'), sort: 'preset' },
    // defensive: a malformed answer is reported by the self-check, it must not break the table
    { key: 'warnings', label: 'Warnings', text: (o) => (Array.isArray(o.warnings) ? o.warnings.join(', ') : ''), sort: 'warnings' },
  ];
  /** Phone: who and what needs doing; the rest from tablet up. */
  readonly cols = computed(() => (this.size().tier === 'xs'
    ? this.allCols.filter((c) => c.key === 'name' || c.key === 'warnings') : this.allCols));

  warningsOf(o: OrgRow): string[] { return Array.isArray(o.warnings) ? o.warnings : []; }

  readonly rowClass = (o: OrgRow) => (o.status === 'BLOCKED' || o.status === 'REJECTED' ? 'off' : '');

  constructor() {
    super();
    connectServerList(this.data, 'admin.organizations.search', this.list, {
      onPage: (page) => {
        if (this.first) this.nothingAtAll.set(page.total === 0);
        this.first = false;
        this.error.set('');
        this.state.set(this.nothingAtAll() ? 'empty' : 'ready');
      },
      onError: (e) => this.error.set(refusal(e, KYC_HINTS)),
    });
  }

  /** One chip at a time: every chip's filter is cleared, then the picked one is set. */
  setChip(v: string) {
    this.chip.set(v as Chip);
    const cleared = Object.fromEntries(Object.values(CHIP_FILTER).filter(([k]) => k).map(([k]) => [k, '']));
    const [key, value] = CHIP_FILTER[v as Chip];
    this.list.filters.update((f) => ({ ...f, ...cleared, ...(key ? { [key]: value } : {}) }));
    this.list.view.go(0);
  }

  /** Picking an organization tells the page which one it is. */
  pick(o: OrgRow) {
    this.publish('orgName', o.name);
    this.publish('orgId', o.id);
  }
}
