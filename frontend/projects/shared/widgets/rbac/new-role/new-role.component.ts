import { ChangeDetectionStrategy, Component, computed, effect, signal, untracked } from '@angular/core';
import { MiPayWidget } from '../../core/mipay-widget';
import { FormField } from '../../../../../src/app/design-system/molecules/form-field';
import { MpAlert } from '../../../../../src/app/design-system/molecules/alert';
import { MpButton } from '../../../../../src/app/design-system/atoms/mp-button';
import { MpCheckbox } from '../../../../../src/app/design-system/atoms/mp-checkbox';
import { OperationsGrid } from '../../../../../src/app/design-system/organisms/operations-grid';
import { MpPerm } from '../../../web-core/perm-directive';
import { refusal } from '../../../kyc/kyc-api';
import { RBAC_HINTS } from '../../../rbac/rbac-api';
import { buildOperationsTree } from '../../../rbac/role-operations-model';
import type { BusinessPortal } from '../../../web-core/portal-permission-catalogue';
import type { RbacNewRoleConfig } from './new-role.widget';
import type {
  RbacNewRoleOrgCreateRequest, RbacNewRoleOrgRoles, RbacNewRolePlatformCreateRequest, RbacNewRolePlatformRoles,
  RbacNewRoleRoles, RbacNewRoleWidgets,
} from './new-role.contract';

interface Base { name: string; label: string; permissions: string[] }

const PORTALS: { value: BusinessPortal; label: string }[] = [
  { value: 'PLATFORM', label: 'Admin (platform role)' }, { value: 'SUPPLIER', label: 'Supplier' },
  { value: 'MANUFACTURER', label: 'Manufacturer' }, { value: 'DISTRIBUTOR', label: 'Distributor' },
  { value: 'MERCHANT', label: 'Merchant' }, { value: 'LENDER', label: 'Lender' },
];

/** "kyc_approver" → "Kyc approver" */
const roleLabel = (n: string) => (n ? n.charAt(0).toUpperCase() + n.slice(1).replace(/_/g, ' ') : n);

/** Any name ("KYC Approver") becomes the role id RBAC needs ("kyc_approver"), as the admin screen does. */
export function roleIdOf(name: string): string {
  let id = name.trim().toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '')
    .replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '');
  if (id && !/^[a-z]/.test(id)) id = 'r_' + id;
  return id.slice(0, 64);
}

/**
 * The role builder by widget. One method per operation of the sheet:
 *  1. loadWidgets  (View)   the portal's widgets → menu › submenu › widget grid
 *  2. loadRoles    (View)   the roles it may build on
 *  3. create       (Create) the new role with its ticked cells
 */
@Component({
  selector: 'mpw-rbac-new-role',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormField, MpAlert, MpButton, MpCheckbox, OperationsGrid, MpPerm],
  template: `
    <div class="form">
      @if (platform()) {
        <mp-form-field data-field="portal" key="portal" label="Portal" kind="select" [options]="portals" [value]="target()"
                       (changed)="setTarget($event)" hint="A platform role is held by staff; any other portal makes an org role." />
      }
      <mp-form-field data-field="name" key="name" label="Name" [value]="name()" (changed)="name.set($event)" placeholder="e.g. KYC Approver"
                     [hint]="roleId() ? 'saved as ' + roleId() : ''" [error]="badName() ? 'At least 3 letters or digits, starting with a letter' : ''" />
      <mp-form-field data-field="description" key="description" label="What it is for" [value]="description()" (changed)="description.set($event)"
                     placeholder="Reviews KYC applications, no payments" />
      @if (bases().length) {
        <div class="bases" data-bases>
          <span class="lbl">Builds on
            <span class="dim">{{ platform() ? '(it gets everything these roles have)' : '(its actions must come from these roles)' }}</span></span>
          <div class="picks">
            @for (b of bases(); track b.name) {
              <mp-checkbox [checked]="picked().has(b.name)" [label]="b.label" (checkedChange)="toggleBase(b.name, $event)" />
            }
          </div>
        </div>
      }
    </div>

    <mp-operations-grid [menus]="tree()" [selected]="cells()" [inherited]="inherited()" [disabled]="busy()"
                        [empty]="widgets() === null ? 'Loading widgets…' : 'No widgets on this portal yet.'"
                        (selectedChange)="cells.set($event)" />

    @if (flash(); as f) {<mp-alert [kind]="f.kind">{{ f.text }}</mp-alert>}
    <div class="actions">
      <span class="dim">{{ cells().size }} permission(s) picked</span>
      <mp-button *mpPerm="'rbac.role:create'" data-action="create" [disabled]="busy() || badName()" [loading]="busy()"
                 (onClick)="create()">Create role</mp-button>
    </div>
  `,
  styles: [`
    :host { display: grid; gap: var(--sp-3, 12px); min-width: 0; container-type: inline-size; }
    .form { display: grid; gap: var(--sp-3, 12px); grid-template-columns: 1fr; }
    .bases { display: grid; gap: 6px; }
    .lbl { font-size: var(--fs-sm, 13px); font-weight: 600; }
    .picks { display: flex; flex-wrap: wrap; gap: 6px 16px; }
    .dim { color: var(--a-dim, #a3a3a3); font-size: var(--fs-sm, 12.5px); font-weight: 400; }
    .actions { display: flex; gap: var(--sp-2, 8px); align-items: center; justify-content: flex-end; flex-wrap: wrap; }
    @container (min-width: 720px) { .form { grid-template-columns: 1fr 1fr; } .bases { grid-column: 1 / -1; } }
  `],
})
export class RbacNewRoleWidget extends MiPayWidget<RbacNewRoleConfig> {
  readonly orgId = this.input<string>('orgId');
  readonly portals = PORTALS;
  /** The widget runs in PLATFORM (standard roles) or an org portal (the org's own roles). */
  readonly platform = computed(() => this.context().portal === 'PLATFORM');
  /** The portal the role is built for: picked in PLATFORM, the context portal elsewhere. */
  private readonly picking = signal<BusinessPortal>('PLATFORM');
  readonly target = computed<BusinessPortal>(() => (this.platform() ? this.picking() : this.context().portal));

  readonly name = signal('');
  readonly description = signal('');
  readonly roleId = computed(() => roleIdOf(this.name()));
  readonly validName = computed(() => /^[a-z][a-z0-9_]{2,63}$/.test(this.roleId()));
  /** Typed but unusable. An empty name goes to the server, which owns that rule (ROLE_NAME_REQUIRED). */
  readonly badName = computed(() => !!this.name().trim() && !this.validName());

  readonly widgets = signal<RbacNewRoleWidgets | null>(null);
  readonly roles = signal<RbacNewRoleRoles | null>(null);
  readonly picked = signal<Set<string>>(new Set());
  /** The role's own cells, ticked in the grid. */
  readonly cells = signal<Set<string>>(new Set());
  readonly busy = signal(false);
  readonly flash = signal<{ kind: 'ok' | 'bad'; text: string } | null>(null);

  readonly tree = computed(() => buildOperationsTree(this.target(), this.widgets() ?? [], { nav: this.config.menuCells }));

  /** Roles it may build on: platform roles of the same kind, or the org's usable roles. */
  readonly bases = computed<Base[]>(() => {
    const r = this.roles();
    if (!r) return [];
    if (Array.isArray(r)) {
      const kind = this.target() === 'PLATFORM' ? 'PLATFORM' : 'ORG';
      return (r as RbacNewRolePlatformRoles).filter((x) => x.kind === kind && x.name)
        .map((x) => ({ name: x.name!, label: roleLabel(x.name!), permissions: x.permissions ?? [] }));
    }
    return (r as RbacNewRoleOrgRoles).roles.map((x) => ({ name: x.name, label: roleLabel(x.name), permissions: x.permissions }));
  });

  /** Platform roles inherit their bases' cells (shown ticked and locked); org roles are carved out of them. */
  readonly inherited = computed(() => {
    if (!this.platform()) return new Set<string>();
    const out = new Set<string>();
    for (const b of this.bases()) if (this.picked().has(b.name)) b.permissions.forEach((c) => out.add(c));
    return out;
  });

  constructor() {
    super();
    effect(() => {
      const portal = this.target();
      const org = this.orgId();
      untracked(() => this.load(portal, org));
    });
  }

  setTarget(p: string) {
    this.picking.set(p as BusinessPortal);
    this.picked.set(new Set());
    this.cells.set(new Set());
  }

  toggleBase(name: string, on: boolean) {
    const s = new Set(this.picked());
    if (on) s.add(name); else s.delete(name);
    this.picked.set(s);
  }

  private load(portal: BusinessPortal, org: string | null) {
    if (!this.platform() && !org) { this.state.set('empty'); return; }
    this.state.set('loading');
    this.widgets.set(null);
    this.loadWidgets(portal, org);
    this.loadRoles(org);
  }

  /** 1. The portal's widgets (View). */
  loadWidgets(portal: BusinessPortal, org: string | null) {
    const params = this.platform() ? { portal } : { id: org, portal };
    this.data.query<RbacNewRoleWidgets>('rbac.new-role.widgets', params).subscribe({
      next: (w) => { this.widgets.set(w); this.state.set(w.length ? 'ready' : 'empty'); },
      error: (e: unknown) => { this.widgets.set([]); this.flash.set({ kind: 'bad', text: refusal(e, RBAC_HINTS) }); },
    });
  }

  /** 2. The roles it may build on (View). */
  loadRoles(org: string | null) {
    this.data.query<RbacNewRoleRoles>('rbac.new-role.roles', this.platform() ? {} : { id: org }).subscribe({
      next: (r) => this.roles.set(r),
      error: (e: unknown) => this.flash.set({ kind: 'bad', text: refusal(e, RBAC_HINTS) }),
    });
  }

  /** 3. Create the role (Create). */
  create() {
    if (this.badName()) return;
    const permissions = [...this.cells()].sort();
    const bases = [...this.picked()];
    const base = { name: this.roleId(), description: this.description().trim() };
    let params: Record<string, unknown>;
    if (this.platform()) {
      const body: RbacNewRolePlatformCreateRequest = {
        ...base, kind: this.target() === 'PLATFORM' ? 'PLATFORM' : 'ORG', inherits: bases, permissions };
      params = { body };
    } else {
      const body: RbacNewRoleOrgCreateRequest = { ...base, baseRoles: bases, permissions };
      params = { id: this.orgId(), body };
    }
    this.busy.set(true);
    this.flash.set(null);
    this.data.command<{ name: string | null }>('rbac.new-role.create', params).subscribe({
      next: (r) => {
        this.busy.set(false);
        this.flash.set({ kind: 'ok', text: `Role ${roleLabel(r.name ?? base.name)} created with ${permissions.length} permission(s).` });
        this.name.set('');
        this.description.set('');
        this.cells.set(new Set());
        this.picked.set(new Set());
      },
      error: (e: unknown) => { this.busy.set(false); this.flash.set({ kind: 'bad', text: refusal(e, RBAC_HINTS) }); },
    });
  }
}
