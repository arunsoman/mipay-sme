import { ChangeDetectionStrategy, Component, computed, effect, signal, untracked } from '@angular/core';
import { MiPayWidget } from '../../core/mipay-widget';
import { roleName, words } from '../../core/text';
import { DataCol, DataTable } from '../../../../../src/app/design-system/organisms/data-table';
import { Modal } from '../../../../../src/app/design-system/organisms/modal';
import { MpButton } from '../../../../../src/app/design-system/atoms/mp-button';
import { MpCheckbox } from '../../../../../src/app/design-system/atoms/mp-checkbox';
import { FormField } from '../../../../../src/app/design-system/molecules/form-field';
import { KvPair } from '../../../../../src/app/design-system/molecules/kv-pair';
import { MpAlert } from '../../../../../src/app/design-system/molecules/alert';
import { EmptyState } from '../../../../../src/app/design-system/molecules/empty-state';
import { MpPerm } from '../../../web-core/perm-directive';
import { RBAC_HINTS, groupCells, roleLabel } from '../../../rbac/rbac-api';
import { refusal } from '../../../kyc/kyc-api';
import type { RbacRolesConfig } from './roles.widget';
import type {
  OrgRole, OrgRolesView, PlatformRole, RbacRoleUpdateOrgRequest, RbacRoleUpdatePlatformRequest,
} from './roles.contract';

/** One role as the widget shows it, whichever portal it came from. */
export interface RoleRow {
  name: string;
  description: string;
  permissions: string[];
  baseRoles: string[];
  /** the org's own role (org portals) */
  own: boolean;
  /** may be changed here */
  editable: boolean;
  /** may be deleted here */
  deletable: boolean;
  approval: string | null;
}

@Component({
  selector: 'mpw-rbac-roles',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DataTable, Modal, MpButton, MpCheckbox, FormField, KvPair, MpAlert, EmptyState, MpPerm],
  template: `
    @if (needsOrg()) {
      <empty-state title="Pick an organization" hint="Its roles appear here." />
    } @else {
      <mp-data-table [cols]="cols()" [rows]="rows()" [pageSize]="config.pageSize" [selectable]="true"
                     placeholder="Search roles or permissions" loading="Loading roles…" empty="No roles yet."
                     (rowClick)="pick($event)" />
    }

    @if (selected(); as r) {
      <section class="detail" data-selected-role>
        <div class="who"><b>{{ label(r.name) }}</b> <span class="dim">{{ r.own ? 'your own role' : 'standard role' }}</span></div>
        @if (r.description) {<p class="dim">{{ r.description }}</p>}
        @if (r.baseRoles.length) {<kv-pair k="Built on">{{ r.baseRoles.map(label).join(', ') }}</kv-pair>}
        @if (r.approval) {<kv-pair k="Giving it needs">{{ approvalText(r.approval) }}</kv-pair>}
        <div class="cells" data-cells>
          @for (g of grouped(); track g.row) {
            <div class="row"><span class="mono">{{ g.row }}</span>
              @for (v of g.verbs; track v) {<span class="chip">{{ v }}</span>}</div>
          } @empty {<span class="dim">No permissions.</span>}
        </div>
        <div class="acts">
          @if (r.editable) {
            <mp-button *mpPerm="'rbac.role:modify'" data-action="edit" variant="ghost" (onClick)="openEdit(r)">Change</mp-button>
          }
          @if (r.deletable) {
            <mp-button *mpPerm="'rbac.role:delete'" data-action="delete" variant="danger" (onClick)="confirmDelete.set(true)">Delete</mp-button>
          }
        </div>
      </section>
    }
    @if (flash(); as f) {<mp-alert [kind]="f.kind" data-flash>{{ f.text }}</mp-alert>}

    <mp-modal [open]="!!editing()" eyebrow="Change role" [title]="editing() ? label(editing()!.name) : ''" size="wide"
              (closed)="editing.set(null)">
      @if (editing(); as e) {
        <div class="edit" data-edit>
          @if (e.own) {
            <mp-form-field key="description" label="Description" [value]="desc()" (changed)="desc.set($event)" />
            <div class="group"><b>Built on</b>
              @for (b of bases(); track b) {
                <mp-checkbox [label]="label(b)" [checked]="draftBases().has(b)" (checkedChange)="toggleBase(b, $event)" />
              }
            </div>
          }
          <div class="group"><b>Permissions</b>
            @for (g of offered(); track g.row) {
              <div class="row"><span class="mono">{{ g.row }}</span>
                @for (v of g.verbs; track v) {
                  <mp-checkbox [label]="v" [checked]="draftCells().has(g.row + ':' + v)" (checkedChange)="toggleCell(g.row + ':' + v, $event)" />
                }
              </div>
            }
          </div>
          @if (editError(); as m) {<mp-alert kind="bad">{{ m }}</mp-alert>}
        </div>
      }
      <div modal-footer>
        <mp-button variant="ghost" (onClick)="editing.set(null)">Cancel</mp-button>
        <mp-button data-action="edit" (onClick)="save()">Save role</mp-button>
      </div>
    </mp-modal>

    <mp-modal [open]="confirmDelete()" eyebrow="Delete role" [title]="selected() ? label(selected()!.name) : ''" size="narrow"
              (closed)="confirmDelete.set(false)">
      <p>Delete this role? People who hold it must have it taken away first.</p>
      <div modal-footer>
        <mp-button variant="ghost" (onClick)="confirmDelete.set(false)">Keep it</mp-button>
        <mp-button data-action="delete" variant="danger" (onClick)="remove()">Delete role</mp-button>
      </div>
    </mp-modal>
  `,
  styles: [`
    :host { display: grid; gap: var(--sp-3, 12px); min-width: 0; }
    .detail { display: grid; gap: var(--sp-2, 8px); padding-top: var(--sp-3, 12px); border-top: 1px solid var(--a-border, #2b2b2b); }
    .who { display: flex; flex-wrap: wrap; gap: 4px 10px; align-items: baseline; overflow-wrap: anywhere; }
    .cells, .edit, .group { display: grid; gap: var(--sp-1, 4px); }
    .edit { gap: var(--sp-3, 12px); }
    .row { display: flex; flex-wrap: wrap; gap: 4px 10px; align-items: center; }
    .mono { font-family: var(--font-mono, monospace); font-size: var(--fs-sm, 12.5px); min-width: 12em; overflow-wrap: anywhere; }
    .chip { padding: 1px 8px; border-radius: 999px; border: 1px solid var(--mp-border); font-size: var(--fs-xs, 11.5px); }
    .acts { display: flex; flex-wrap: wrap; gap: var(--sp-2, 8px); }
    .dim { color: var(--a-dim, #a3a3a3); font-size: var(--fs-sm, 12.5px); margin: 0; overflow-wrap: anywhere; }
  `],
})
export class RbacRolesWidget extends MiPayWidget<RbacRolesConfig> {
  readonly orgId = this.input<string>('orgId');
  readonly platform = computed(() => this.context().portal === 'PLATFORM');
  readonly needsOrg = computed(() => !this.platform() && !this.orgId());
  readonly rows = signal<RoleRow[] | null>(null);
  readonly selected = signal<RoleRow | null>(null);
  readonly grouped = computed(() => groupCells(this.selected()?.permissions ?? []));
  readonly flash = signal<{ kind: 'ok' | 'bad'; text: string } | null>(null);
  readonly label = roleLabel;
  /** the standard roles an own role may build on (org portals) */
  readonly bases = signal<string[]>([]);
  private standard = new Map<string, string[]>();

  readonly editing = signal<RoleRow | null>(null);
  readonly desc = signal('');
  readonly draftBases = signal(new Set<string>());
  readonly draftCells = signal(new Set<string>());
  readonly editError = signal('');
  readonly confirmDelete = signal(false);

  /** What the role may be given: its base roles' cells (own roles) or every cell any role uses (PLATFORM). */
  readonly offered = computed(() => {
    const e = this.editing();
    if (!e) return [];
    const cells = new Set<string>(this.draftCells());
    if (e.own) for (const b of this.draftBases()) for (const c of this.standard.get(b) ?? []) cells.add(c);
    else for (const r of this.rows() ?? []) for (const c of r.permissions) cells.add(c);
    return groupCells(cells);
  });

  private readonly allCols: DataCol<RoleRow>[] = [
    { key: 'name', label: 'Role', text: (r) => roleName(roleLabel(r.name)), search: (r) => [r.name, r.description, ...r.permissions] },
    { key: 'kind', label: 'Kind', text: (r) => (r.own ? 'Own' : 'Standard') },
    { key: 'perms', label: 'Permissions', text: (r) => r.permissions.length },
    { key: 'approval', label: 'Giving it needs', text: (r) => this.approvalText(r.approval) },
  ];
  readonly cols = computed(() => (this.size().tier === 'xs'
    ? this.allCols.filter((c) => c.key === 'name' || c.key === 'perms') : this.allCols));

  constructor() {
    super();
    effect(() => {
      const org = this.orgId();
      const platform = this.platform();
      untracked(() => (platform || org ? this.load() : this.state.set('empty')));
    });
  }

  approvalText(a: string | null) {
    if (!a) return '—';
    return ({ none: 'an administrator', org_admin: 'a second administrator', platform: 'MiPay', compliance: 'MiPay compliance' } as
      Record<string, string>)[a.toLowerCase()] ?? words(a);
  }

  private load(keep?: string) {
    this.rows.set(null);
    const params = this.platform() ? {} : { id: this.orgId() };
    this.data.query<PlatformRole[] | OrgRolesView>('rbac.roles.list', params).subscribe({
      next: (a) => {
        const rows = Array.isArray(a) ? a.map((r) => this.fromPlatform(r)) : this.fromOrg(a);
        this.rows.set(rows);
        this.selected.set(keep ? rows.find((r) => r.name === keep) ?? null : null);
        this.state.set(rows.length ? 'ready' : 'empty');
      },
      error: () => undefined,
    });
  }

  private fromPlatform(r: PlatformRole): RoleRow {
    return { name: r.name ?? '', description: r.description ?? '', permissions: r.permissions ?? [], baseRoles: r.inherits ?? [],
      own: false, editable: !r.locked, deletable: !r.locked && !r.seeded, approval: r.approval };
  }

  private fromOrg(v: OrgRolesView): RoleRow[] {
    this.standard = new Map(v.roles.map((r) => [r.name, r.permissions]));
    this.bases.set(v.roles.map((r) => r.name));
    const row = (r: OrgRole, own: boolean): RoleRow => ({ name: r.name, description: r.description ?? '', permissions: r.permissions,
      baseRoles: r.base_roles ?? [], own, editable: own, deletable: own, approval: null });
    return [...v.roles.map((r) => row(r, false)), ...v.org_roles.map((r) => row(r, true))];
  }

  pick(r: RoleRow) {
    this.selected.set(r);
    this.flash.set(null);
    this.publish('roleName', r.name);
  }

  openEdit(r: RoleRow) {
    this.desc.set(r.description);
    this.draftBases.set(new Set(r.baseRoles));
    this.draftCells.set(new Set(r.permissions));
    this.editError.set('');
    this.editing.set(r);
  }

  toggleBase(b: string, on: boolean) {
    const s = new Set(this.draftBases());
    if (on) s.add(b); else s.delete(b);
    this.draftBases.set(s);
  }

  toggleCell(c: string, on: boolean) {
    const s = new Set(this.draftCells());
    if (on) s.add(c); else s.delete(c);
    this.draftCells.set(s);
  }

  save() {
    const e = this.editing();
    if (!e) return;
    const permissions = [...this.draftCells()].sort();
    let params: Record<string, unknown>;
    if (this.platform()) {
      const body: RbacRoleUpdatePlatformRequest = { permissions };
      params = { name: e.name, body };
    } else {
      const body: RbacRoleUpdateOrgRequest = { name: e.name, description: this.desc() || null, baseRoles: [...this.draftBases()], permissions };
      params = { id: this.orgId(), name: e.name, body };
    }
    this.data.command('rbac.roles.update', params).subscribe({
      next: () => {
        this.editing.set(null);
        this.flash.set({ kind: 'ok', text: `${roleName(roleLabel(e.name))} is changed.` });
        this.load(e.name);
      },
      error: (err: unknown) => this.editError.set(refusal(err, RBAC_HINTS)),
    });
  }

  remove() {
    const r = this.selected();
    if (!r) return;
    const params = this.platform() ? { name: r.name } : { id: this.orgId(), name: r.name };
    this.data.command('rbac.roles.delete', params).subscribe({
      next: () => {
        this.confirmDelete.set(false);
        this.flash.set({ kind: 'ok', text: `${roleName(roleLabel(r.name))} is deleted.` });
        this.load();
      },
      error: (err: unknown) => { this.confirmDelete.set(false); this.flash.set({ kind: 'bad', text: refusal(err, RBAC_HINTS) }); },
    });
  }
}
