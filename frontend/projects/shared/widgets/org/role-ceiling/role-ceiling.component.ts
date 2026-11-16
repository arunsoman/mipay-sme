import { ChangeDetectionStrategy, Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { MiPayWidget } from '../../core/mipay-widget';
import { roleName } from '../../core/text';
import { DataCol, DataTable, MpCell } from '../../../../../src/app/design-system/organisms/data-table';
import { MpCheckbox } from '../../../../../src/app/design-system/atoms/mp-checkbox';
import { MpButton } from '../../../../../src/app/design-system/atoms/mp-button';
import { MpAlert } from '../../../../../src/app/design-system/molecules/alert';
import { EmptyState } from '../../../../../src/app/design-system/molecules/empty-state';
import { MpPerm } from '../../../web-core/perm-directive';
import { AccessService } from '../../../web-core/access';
import { RBAC_HINTS } from '../../../rbac/rbac-api';
import { refusal } from '../../../kyc/kyc-api';
import type { OrgRoleCeilingConfig } from './role-ceiling.widget';
import type { OrgCeilingSetRequest, RoleCeilingView } from './role-ceiling.contract';

interface Row { name: string; description: string | null; baseline: boolean; offered: boolean }

@Component({
  selector: 'mpw-org-role-ceiling',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DataTable, MpCell, MpCheckbox, MpButton, MpAlert, EmptyState, MpPerm],
  template: `
    @if (!orgId()) {
      <empty-state title="Pick an organization" hint="The roles it may use appear here." />
    } @else if (view(); as v) {
      <p class="dim" data-source>{{ source(v) }}</p>
      <mp-data-table [cols]="cols" [rows]="rows()" [searchable]="false" [pageSize]="50">
        <ng-template mpCell="use" let-r>
          <mp-checkbox [ariaLabel]="'May use ' + r.name" [checked]="use().has(r.name)" [locked]="r.baseline"
                       [disabled]="!editable() || !r.offered" (checkedChange)="toggle(use, r.name, $event)" />
        </ng-template>
        <ng-template mpCell="pass" let-r>
          <mp-checkbox [ariaLabel]="'May hand down ' + r.name" [checked]="pass().has(r.name)"
                       [disabled]="!editable() || !use().has(r.name)" (checkedChange)="toggle(pass, r.name, $event)" />
        </ng-template>
      </mp-data-table>
      <div class="acts" *mpPerm="'org.rights:administer'">
        <mp-button data-action="save" (onClick)="save(false)">Save the ceiling</mp-button>
        @if (v.explicit) {<mp-button data-action="save" variant="ghost" (onClick)="save(true)">Follow the parent’s ceiling</mp-button>}
      </div>
    } @else {
      <mp-data-table [cols]="cols" [rows]="null" loading="Loading the role ceiling…" />
    }
    @if (flash(); as f) {<mp-alert [kind]="f.kind" data-flash>{{ f.text }}</mp-alert>}
  `,
  styles: [`
    :host { display: grid; gap: var(--sp-3, 12px); min-width: 0; }
    .acts { display: flex; flex-wrap: wrap; gap: var(--sp-2, 8px); }
    .dim { color: var(--a-dim, #a3a3a3); font-size: var(--fs-sm, 12.5px); margin: 0; }
  `],
})
export class OrgRoleCeilingWidget extends MiPayWidget<OrgRoleCeilingConfig> {
  private readonly access = inject(AccessService);
  readonly orgId = this.input<string>('orgId');
  readonly view = signal<RoleCeilingView | null>(null);
  readonly use = signal(new Set<string>());
  readonly pass = signal(new Set<string>());
  readonly flash = signal<{ kind: 'ok' | 'bad'; text: string } | null>(null);
  readonly editable = computed(() => this.access.can('org.rights:administer'));

  readonly rows = computed<Row[] | null>(() => {
    const v = this.view();
    if (!v) return null;
    const offered = new Set(v.parentHandDown);
    return v.catalogue.map((c) => ({ name: c.name, description: c.description, baseline: v.baselineRoles.includes(c.name),
      offered: offered.has(c.name) }));
  });
  readonly cols: DataCol<Row>[] = [
    { key: 'name', label: 'Role', text: (r) => roleName(r.name) },
    { key: 'use', label: 'May use' },
    { key: 'pass', label: 'May hand down' },
  ];

  constructor() {
    super();
    effect(() => {
      const id = this.orgId();
      untracked(() => (id ? this.load(id) : this.state.set('empty')));
    });
  }

  source(v: RoleCeilingView) {
    if (v.unrestricted) return 'No ceiling: this organization may use every role.';
    return v.explicit ? 'Set for this organization.' : `Follows the ceiling of ${v.inheritedFrom ?? 'the organization above it'}.`;
  }

  private show(v: RoleCeilingView) {
    this.view.set(v);
    this.use.set(new Set(v.roles));
    this.pass.set(new Set(v.delegableRoles));
  }

  private load(id: string) {
    this.view.set(null);
    this.data.query<RoleCeilingView>('org.ceiling.get', { id }).subscribe({ next: (v) => this.show(v), error: () => undefined });
  }

  toggle(which: typeof this.use, name: string, on: boolean) {
    const s = new Set(which());
    if (on) s.add(name); else s.delete(name);
    which.set(s);
    // a role it may not use can't be handed down either
    if (which === this.use && !on) { const p = new Set(this.pass()); p.delete(name); this.pass.set(p); }
  }

  save(inherit: boolean) {
    const body: OrgCeilingSetRequest = inherit
      ? { roles: null, delegableRoles: null, inherit: true }
      : { roles: [...this.use()].sort(), delegableRoles: [...this.pass()].sort(), inherit: false };
    this.data.command<RoleCeilingView>('org.ceiling.set', { id: this.orgId(), body }).subscribe({
      next: (v) => { this.show(v); this.flash.set({ kind: 'ok', text: inherit ? 'It now follows its parent’s ceiling.' : 'The role ceiling is saved.' }); },
      error: (e: unknown) => this.flash.set({ kind: 'bad', text: refusal(e, RBAC_HINTS) }),
    });
  }
}
