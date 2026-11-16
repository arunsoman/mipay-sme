import { ChangeDetectionStrategy, Component, computed, signal } from '@angular/core';
import { MiPayWidget } from '../../core/mipay-widget';
import { roleName, words } from '../../core/text';
import { DataCol, DataTable } from '../../../../../src/app/design-system/organisms/data-table';
import { KvPair } from '../../../../../src/app/design-system/molecules/kv-pair';
import { roleLabel } from '../../../rbac/rbac-api';
import type { RbacMyAccessConfig } from './my-access.widget';
import type { RbacMyAccessGet } from './my-access.contract';

type Held = NonNullable<RbacMyAccessGet['permissions']>[number];

@Component({
  selector: 'mpw-rbac-my-access',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DataTable, KvPair],
  template: `
    @if (view(); as v) {
      <div class="kv">
        <kv-pair k="Organization">{{ v.orgName ?? '—' }} ({{ type(v.orgType) }})</kv-pair>
        <kv-pair k="Roles"><span data-roles>{{ roles() }}</span></kv-pair>
      </div>
    }
    <mp-data-table [cols]="cols()" [rows]="rows()" [pageSize]="config.pageSize" placeholder="Search permissions"
                   loading="Loading your access…" empty="You hold no permissions." />
  `,
  styles: [`
    :host { display: grid; gap: var(--sp-3, 12px); min-width: 0; }
    .kv { display: grid; gap: var(--sp-1, 4px); overflow-wrap: anywhere; }
  `],
})
export class RbacMyAccessWidget extends MiPayWidget<RbacMyAccessConfig> {
  readonly view = signal<RbacMyAccessGet | null>(null);
  readonly rows = computed(() => (this.view() ? [...(this.view()!.permissions ?? [])]
    .sort((a, b) => (a.row ?? '').localeCompare(b.row ?? '')) : null));
  readonly roles = computed(() => (this.view()?.roles ?? []).map((r) => roleName(roleLabel(r))).join(', ') || 'none');
  readonly type = words;

  private readonly allCols: DataCol<Held>[] = [
    { key: 'row', label: 'Area', text: (p) => p.row ?? '—', cls: 'mono' },
    { key: 'verb', label: 'May', text: (p) => p.verb ?? '—' },
    { key: 'desc', label: 'What it is', text: (p) => p.rowDescription ?? '' },
  ];
  readonly cols = computed(() => (this.size().tier === 'xs' ? this.allCols.slice(0, 2) : this.allCols));

  constructor() {
    super();
    this.data.query<RbacMyAccessGet>('rbac.my-access.get').subscribe({
      next: (v) => { this.view.set(v); if (!(v.permissions ?? []).length) this.state.set('empty'); },
      error: () => undefined,
    });
  }
}
