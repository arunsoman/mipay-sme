import { ChangeDetectionStrategy, Component, computed, effect, signal, untracked } from '@angular/core';
import { MiPayWidget } from '../../core/mipay-widget';
import { roleName, words } from '../../core/text';
import { DataCol, DataTable } from '../../../../../src/app/design-system/organisms/data-table';
import { KpiRow } from '../../../../../src/app/design-system/organisms/kpi-row';
import { MpAlert } from '../../../../../src/app/design-system/molecules/alert';
import { EmptyState } from '../../../../../src/app/design-system/molecules/empty-state';
import { roleLabel } from '../../../rbac/rbac-api';
import type { RbacReconciliationConfig } from './reconciliation.widget';
import type { Mismatch, RbacModeGet, RbacReconGet } from './reconciliation.contract';

@Component({
  selector: 'mpw-rbac-reconciliation',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DataTable, KpiRow, MpAlert, EmptyState],
  template: `
    @if (mode(); as m) {
      <mp-alert [kind]="m.mode === 'enforce' ? 'ok' : 'info'" data-mode>{{ modeText(m.mode) }}</mp-alert>
    }
    @if (!orgId()) {
      <empty-state title="Pick an organization" hint="Where its roles disagree appears here." />
    } @else {
      @if (report(); as r) {
        <kpi-row [tiles]="tiles()" />
      }
      <mp-data-table [cols]="cols()" [rows]="rows()" [searchable]="false" loading="Comparing…"
                     empty="Nothing disagrees: every person’s roles match." />
    }
  `,
  styles: [`:host { display: grid; gap: var(--sp-3, 12px); min-width: 0; }`],
})
export class RbacReconciliationWidget extends MiPayWidget<RbacReconciliationConfig> {
  readonly orgId = this.input<string>('orgId');
  readonly mode = signal<RbacModeGet | null>(null);
  readonly report = signal<RbacReconGet | null>(null);
  readonly rows = computed(() => (this.report() ? this.report()!.rows ?? [] : null));
  readonly tiles = computed(() => {
    const r = this.report()!;
    return [
      { label: 'People', amount: r.users, money: false },
      { label: 'Active core roles', amount: r.activeCoreRoleAssignments, money: false },
      { label: 'Disagreements', amount: r.mismatches, money: false },
    ];
  });

  private readonly allCols: DataCol<Mismatch>[] = [
    { key: 'who', label: 'Person', text: (m) => m.username ?? m.userId ?? '—' },
    { key: 'core', label: 'Core role', text: (m) => words(m.coreRole) },
    { key: 'expected', label: 'Expected in RBAC', text: (m) => roleName(m.expectedRole) },
    { key: 'held', label: 'Held in RBAC', text: (m) => (m.activeRbacRoles ?? []).map((r) => roleName(roleLabel(r))).join(', ') || 'none' },
    { key: 'reason', label: 'Why', text: (m) => m.reason ?? '' },
  ];
  readonly cols = computed(() => (this.size().tier === 'xs'
    ? this.allCols.filter((c) => c.key === 'who' || c.key === 'reason') : this.allCols));

  constructor() {
    super();
    this.data.query<RbacModeGet>('rbac.mode.get').subscribe({ next: (m) => this.mode.set(m), error: () => undefined });
    effect(() => {
      const id = this.orgId();
      untracked(() => {
        this.report.set(null);
        if (!id) { this.state.set('empty'); return; }
        this.data.query<RbacReconGet>('rbac.recon.get', { orgId: id }).subscribe({ next: (r) => this.report.set(r), error: () => undefined });
      });
    });
  }

  modeText(m: string) {
    return ({
      enforce: 'RBAC decides access.',
      shadow: 'RBAC runs in shadow: the core still decides; differences are logged.',
      off: 'RBAC is off: the core decides access.',
    } as Record<string, string>)[m] ?? `RBAC mode: ${m}`;
  }
}
