import { ChangeDetectionStrategy, Component, computed, signal } from '@angular/core';
import { MiPayWidget } from '../../core/mipay-widget';
import { words } from '../../core/text';
import { DataCol, DataTable } from '../../../../../src/app/design-system/organisms/data-table';
import type { RbacResourcesConfig } from './resources.widget';
import type { RbacResource, RbacResourcesList } from './resources.contract';

type Endpoint = NonNullable<RbacResource['endpoints']>[number];

@Component({
  selector: 'mpw-rbac-resources',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DataTable],
  template: `
    <mp-data-table [cols]="cols()" [rows]="rows()" [pageSize]="config.pageSize" [selectable]="true"
                   placeholder="Search rows, verbs or endpoints" loading="Loading the catalogue…" empty="No permission rows."
                   (rowClick)="selected.set($event)" />
    @if (selected(); as r) {
      <section class="detail" data-selected-row>
        <div><b class="mono">{{ r.id }}</b> <span class="dim">{{ r.description }}</span></div>
        <mp-data-table [cols]="endpointCols" [rows]="r.endpoints ?? []" [searchable]="false" [pageSize]="20"
                       empty="No endpoint uses this row." />
      </section>
    }
  `,
  styles: [`
    :host { display: grid; gap: var(--sp-3, 12px); min-width: 0; }
    .detail { display: grid; gap: var(--sp-2, 8px); padding-top: var(--sp-3, 12px); border-top: 1px solid var(--a-border, #2b2b2b); overflow-wrap: anywhere; }
    .mono { font-family: var(--font-mono, monospace); }
    .dim { color: var(--a-dim, #a3a3a3); font-size: var(--fs-sm, 12.5px); }
  `],
})
export class RbacResourcesWidget extends MiPayWidget<RbacResourcesConfig> {
  readonly rows = signal<RbacResourcesList | null>(null);
  readonly selected = signal<RbacResource | null>(null);

  private readonly allCols: DataCol<RbacResource>[] = [
    { key: 'id', label: 'Row', text: (r) => r.id ?? '—', cls: 'mono',
      search: (r) => [r.id, r.description, ...(r.endpoints ?? []).map((e) => e.path)] },
    { key: 'used', label: 'Verbs', text: (r) => (r.used ?? []).join(', ') },
    { key: 'types', label: 'Applies to', text: (r) => (r.tenantTypes ?? []).map(words).join(', ') || 'all' },
    { key: 'service', label: 'Service', text: (r) => r.service ?? '—' },
    { key: 'n', label: 'Endpoints', text: (r) => (r.endpoints ?? []).length },
  ];
  readonly cols = computed(() => (this.size().tier === 'xs'
    ? this.allCols.filter((c) => c.key === 'id' || c.key === 'used') : this.allCols));
  readonly endpointCols: DataCol<Endpoint>[] = [
    { key: 'verb', label: 'Verb', text: (e) => e.verb ?? '—' },
    { key: 'method', label: 'Method', text: (e) => e.method ?? '—' },
    { key: 'path', label: 'Path', text: (e) => e.path ?? '—', cls: 'mono' },
  ];

  constructor() {
    super();
    this.data.query<RbacResourcesList>('rbac.resources.list').subscribe({
      next: (r) => { this.rows.set(r); if (!r.length) this.state.set('empty'); },
      error: () => undefined,
    });
  }
}
