import { ChangeDetectionStrategy, Component, computed, signal } from '@angular/core';
import { MiPayWidget } from '../../core/mipay-widget';
import { whenTime, words } from '../../core/text';
import { DataCol, DataTable } from '../../../../../src/app/design-system/organisms/data-table';
import { MpButton } from '../../../../../src/app/design-system/atoms/mp-button';
import type { OpsAuditConfig } from './audit.widget';
import type { AuditRow, OpsAuditPage } from './audit.contract';

/** The server pages (page, size); the table searches within the page. */
@Component({
  selector: 'mpw-ops-audit',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DataTable, MpButton],
  template: `
    <mp-data-table [cols]="cols()" [rows]="rows()" [pageSize]="config.pageSize" placeholder="Search this page"
                   loading="Loading the audit trail…" empty="Nothing recorded yet." />
    <div class="pager" data-pager>
      <mp-button variant="ghost" [disabled]="page() === 0" (onClick)="go(page() - 1)">Newer</mp-button>
      <span class="dim">Page {{ page() + 1 }} of {{ pages() }}</span>
      <mp-button variant="ghost" [disabled]="page() + 1 >= pages()" (onClick)="go(page() + 1)">Older</mp-button>
    </div>
  `,
  styles: [`
    :host { display: grid; gap: var(--sp-3, 12px); min-width: 0; }
    .pager { display: flex; flex-wrap: wrap; gap: var(--sp-2, 8px); align-items: center; justify-content: flex-end; }
    .dim { color: var(--a-dim, #a3a3a3); font-size: var(--fs-sm, 12.5px); }
  `],
})
export class OpsAuditWidget extends MiPayWidget<OpsAuditConfig> {
  readonly answer = signal<OpsAuditPage | null>(null);
  readonly page = signal(0);
  readonly rows = computed(() => (this.answer() ? this.answer()!.rows ?? [] : null));
  readonly pages = computed(() => Math.max(1, Math.ceil((this.answer()?.totalElements ?? 0) / this.config.pageSize)));

  private readonly allCols: DataCol<AuditRow>[] = [
    { key: 'createdAt', label: 'When', text: (r) => whenTime(r.createdAt) },
    { key: 'actorRole', label: 'Who', text: (r) => words(r.actorRole) },
    { key: 'action', label: 'Did', text: (r) => words(r.action) },
    { key: 'entity', label: 'To', text: (r) => `${words(r.entityType)} ${r.entityId ?? ''}`.trim(), cls: 'mono' },
    { key: 'meta', label: 'Details', text: (r) => r.meta ?? '' },
  ];
  readonly cols = computed(() => (this.size().tier === 'xs'
    ? this.allCols.filter((c) => c.key === 'createdAt' || c.key === 'action') : this.allCols));

  constructor() {
    super();
    this.go(0);
  }

  go(page: number) {
    this.page.set(page);
    this.answer.set(null);
    this.data.query<OpsAuditPage>('ops.audit.page', { page: String(page), size: String(this.config.pageSize) }).subscribe({
      next: (a) => { this.answer.set(a); if (page === 0 && !(a.rows ?? []).length) this.state.set('empty'); },
      error: () => undefined,
    });
  }
}
