import { ChangeDetectionStrategy, Component, computed, effect, signal } from '@angular/core';
import { MiPayWidget } from '../../core/mipay-widget';
import { connectServerList } from '../../core/server-list';
import { ServerQuery } from '../../../../../src/app/design-system/molecules/table-view';
import { DataCol, DataTable, MpCell } from '../../../../../src/app/design-system/organisms/data-table';
import { MpTabs, TabOption } from '../../../../../src/app/design-system/molecules/tabs';
import { StatusBadge } from '../../../../../src/app/design-system/atoms/status-badge';
import { EmptyState } from '../../../../../src/app/design-system/molecules/empty-state';
import { MpAlert } from '../../../../../src/app/design-system/molecules/alert';
import { KYC_HINTS, refusal } from '../../../kyc/kyc-api';
import type { OrgPeopleConfig } from './people.widget';
import type { Person } from './people.contract';

/** "organization_access" → "Organization access" */
export const roleLabel = (r: string) => (r.charAt(0).toUpperCase() + r.slice(1)).replace(/_/g, ' ');
const words = (s: string | null) => (s ? s.charAt(0) + s.slice(1).toLowerCase().replace(/_/g, ' ') : '—');

/** Admins / staff chips and the table; the server searches, filters, sorts and pages. */
@Component({
  selector: 'mpw-org-people',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpTabs, DataTable, MpCell, StatusBadge, EmptyState, MpAlert],
  template: `
    @if (!orgId()) {
      <empty-state title="Pick an organization" hint="Its people and their roles appear here." />
    } @else {
      <mp-tabs [options]="tabs" [value]="orgRole()" (valueChange)="setOrgRole($event)" label="Role in the organization" />
      <mp-data-table [server]="list" [rows]="list.rows()" [cols]="cols()" placeholder="Search people or roles"
                     loading="Loading people…" empty="Nobody here.">
        <ng-template mpCell="status" let-row><status-badge [status]="row.status ?? 'PENDING'" /></ng-template>
        <ng-template mpCell="roles" let-row>
          @for (r of row.roles; track r) {<span class="role" data-role>{{ label(r) }}</span>}
          @for (r of row.pendingRoles; track r) {<span class="role pending" data-role-pending>{{ label(r) }} (pending)</span>}
          @if (!row.roles.length && !row.pendingRoles.length) {<span class="none">no role yet</span>}
        </ng-template>
      </mp-data-table>
    }
    @if (error(); as e) {<mp-alert kind="bad">{{ e }}</mp-alert>}
  `,
  styles: [`
    :host { display: grid; gap: var(--sp-3, 12px); min-width: 0; }
    .role { display: inline-block; max-width: 100%; overflow-wrap: anywhere; margin: 0 6px 2px 0; padding: 1px 8px; border-radius: 999px;
            font-size: var(--fs-xs, 11.5px); font-weight: 600; border: 1px solid var(--mp-border); }
    .role.pending { border-style: dashed; color: var(--mp-text-dim); }
    .none { color: var(--mp-amber, #f7c66b); font-size: var(--fs-xs, 11.5px); }
  `],
})
export class OrgPeopleWidget extends MiPayWidget<OrgPeopleConfig> {
  readonly orgId = this.input<string>('orgId');
  readonly list = new ServerQuery<Person>(this.config.pageSize, { field: 'name', dir: 'asc' });
  readonly orgRole = signal('');
  readonly error = signal('');
  readonly label = roleLabel;

  readonly tabs: TabOption[] = [
    { value: '', label: 'Everyone' },
    { value: 'ADMIN', label: 'Admins' },
    { value: 'STAFF', label: 'Staff' },
  ];

  private readonly allCols: DataCol<Person>[] = [
    { key: 'name', label: 'Name', text: (p) => p.name ?? '—', sort: 'name' },
    { key: 'username', label: 'Username', text: (p) => p.username ?? '—', sort: 'username', cls: 'mono' },
    { key: 'orgRole', label: 'In the org', text: (p) => words(p.orgRole), sort: 'orgRole' },
    { key: 'status', label: 'Status', text: (p) => words(p.status), sort: 'status' },
    { key: 'roles', label: 'Roles', text: (p) => p.roles.join(', '), sort: 'roles' },
  ];
  /** Phone: who and their roles; the rest from tablet up. */
  readonly cols = computed(() => (this.size().tier === 'xs'
    ? this.allCols.filter((c) => c.key === 'name' || c.key === 'roles') : this.allCols));

  constructor() {
    super();
    effect(() => { if (!this.orgId()) this.state.set('empty'); });
    connectServerList(this.data, 'org.people.search', this.list, {
      // nothing is sent until an organization is picked; a new pick starts on page 1
      extra: () => (this.orgId() ? { orgId: this.orgId() } : null),
      onPage: () => { this.error.set(''); this.state.set('ready'); },
      onError: (e) => this.error.set(refusal(e, KYC_HINTS)),
    });
  }

  setOrgRole(v: string) {
    this.orgRole.set(v);
    this.list.setFilter('orgRole', v);
  }
}
