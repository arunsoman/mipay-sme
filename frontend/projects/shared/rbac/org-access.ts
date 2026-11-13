import { MpSpinner } from '../../../src/app/design-system/atoms/mp-spinner';
import { MpAlert } from '../../../src/app/design-system/molecules/alert';
import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { refusal } from '../kyc/kyc-api';
import { Modal } from '../../../src/app/design-system/organisms/modal';
import { DataCol, DATA_TABLE } from '../../../src/app/design-system/organisms/data-table';
import { RoleCeiling } from './role-ceiling';
import {
  OrgMember, OrgRolesView, RBAC_HINTS, RbacRole, RoleAssignment, groupCells, roleLabel,
} from './rbac-api';

interface RoleDraft { editing: string | null; name: string; description: string; bases: Set<string>; cells: Set<string>; }

const emptyDraft = (): RoleDraft => ({ editing: null, name: '', description: '', bases: new Set(), cells: new Set() });

/**
 * "Roles & access" for one org (docs/plans/extended-rbac.md ER5):
 * <ul>
 *   <li>its role ceiling (read-only for the org itself; editable from an org above it);</li>
 *   <li>the roles it may use, and its own roles carved out of them (never wider than their
 *       base roles, and switched off if the ceiling narrows);</li>
 *   <li>who holds which role, giving and taking roles, and role requests waiting for a
 *       second person (four-eyes).</li>
 * </ul>
 */
@Component({
  selector: 'mp-org-access',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpSpinner, MpAlert, FormsModule, Modal, RoleCeiling, ...DATA_TABLE],
  template: `
    @if (roles(); as rv) {
      @if (rv.rbac_mode && rv.rbac_mode !== 'enforce') {
        <div class="rbac-status" role="status">
          RBAC is in {{ rv.rbac_mode }} mode: these roles and ceilings do not yet decide MiPay application access; the legacy role checks do.
        </div>
      }
    }
    @if (flash(); as f) {<mp-alert [kind]="f.kind">{{ f.text }}</mp-alert>}

    <section class="panel pad">
      <mp-role-ceiling [orgId]="orgId()" [editable]="canEditCeiling()" (changed)="reload()" />
    </section>

    <section class="panel pad">
      <div class="ptitle">Roles in this organization</div>
      @if (error(); as e) {<mp-alert kind="bad">{{ e }}</mp-alert>}
      @if (roles(); as v) {
        <mp-data-table [cols]="roleCols" [rows]="roleRows()" placeholder="Search roles" empty="No roles."
                       [trackBy]="roleKey" [rowClass]="roleClass">
          <ng-template mpCell="name" let-x>
            @if (!x.own) {<b>{{ x.r.name }}</b>}
            @else {<b>{{ label(x.r.name) }}</b> <span class="chip teal">own</span>
              @if (!x.r.in_ceiling) {<span class="chip red">outside ceiling · inactive</span>}}
            @if (x.r.description) {<div class="muted small">{{ x.r.description }}</div>}
          </ng-template>
          <ng-template mpCell="act" let-x>
            @if (x.own && manage()) {
              <button class="ghost" (click)="editRole(x.r)">Edit</button>
              <button class="ghost" (click)="deleteRole(x.r)" [disabled]="busy()">Delete</button>
            }
          </ng-template>
        </mp-data-table>
        @if (manage()) {
          <div class="row"><button class="primary" (click)="newRole()">＋ New role for this organization</button></div>
          <div class="hint">An organization's own role is carved out of standard roles it may use: it can only contain their
            permissions, and it stops working if one of them leaves the ceiling.</div>
        }
      } @else if (!error()) {
        <mp-spinner label="Loading…" />
      }
    </section>

    <section class="panel pad">
      <div class="ptitle">People and their roles</div>
      @if (manage() && roles()) {
        <form class="assign" (ngSubmit)="assign()">
          <label>Person
            <select name="user" [(ngModel)]="aUser" required>
              <option value="">—</option>
              @for (m of members(); track m.id) {<option [value]="m.id">{{ m.name }} ({{ m.username }}) · {{ m.status }}</option>}
            </select></label>
          <label>Role
            <select name="role" [(ngModel)]="aRole" required>
              <option value="">—</option>
              @for (r of assignable(); track r) {<option [value]="r">{{ label(r) }}</option>}
            </select></label>
          <label>Reason <input name="reason" [(ngModel)]="aReason" placeholder="why they need it" /></label>
          <button class="primary" type="submit" [disabled]="busy() || !aUser || !aRole">Give role</button>
        </form>
      }
      <mp-data-table [cols]="currentCols" [rows]="current()" placeholder="Search people and roles" empty="No roles given yet.">
        <ng-template mpCell="person" let-a>{{ a.userName ?? a.subject }} @if (a.username) {<span class="muted small">({{ a.username }})</span>}</ng-template>
        <ng-template mpCell="role" let-a>{{ label(a.role) }} @if (a.scope === 'subtree') {<span class="chip">and orgs below</span>}</ng-template>
        <ng-template mpCell="status" let-a let-v="value"><span class="chip {{ a.status === 'ACTIVE' ? 'green' : 'amber' }}">{{ v }}</span></ng-template>
        <ng-template mpCell="act" let-a>@if (manage()) {<button class="ghost" (click)="revoke(a)" [disabled]="busy()">{{ a.status === 'ACTIVE' ? 'Take away' : 'Cancel' }}</button>}</ng-template>
      </mp-data-table>

      @if (approvals().length) {
        <div class="btitle">Waiting for your approval</div>
        <mp-data-table [cols]="approvalCols" [rows]="approvals()" placeholder="Search requests">
          <ng-template mpCell="act" let-a>
            <button class="primary" (click)="decide(a, true)" [disabled]="busy()">Approve</button>
            <button class="ghost" (click)="decide(a, false)" [disabled]="busy()">Reject</button>
          </ng-template>
        </mp-data-table>
      }
    </section>

    <mp-modal [open]="draftOpen()" size="wide" eyebrow="Organization role"
              [title]="draft.editing ? 'Edit ' + label(draft.editing) : 'New role'"
              subtitle="Pick the standard roles it is carved from, then tick only the permissions it needs."
              (closed)="draftOpen.set(false)">
      <form class="form" (ngSubmit)="saveRole()">
        <div class="fields">
          <label>Name (lower_snake_case) *
            <input name="rname" [(ngModel)]="draft.name" [readonly]="!!draft.editing" required placeholder="e.g. stock_clerk" /></label>
          <label>Description <input name="rdesc" [(ngModel)]="draft.description" /></label>
        </div>
        <fieldset>
          <legend>Built on</legend>
          <div class="bases">
            @for (r of usable(); track r.name) {
              <label class="check"><input type="checkbox" [checked]="draft.bases.has(r.name)" (change)="toggleBase(r.name)" /> {{ r.name }}</label>
            }
          </div>
        </fieldset>
        <fieldset>
          <legend>Permissions ({{ draft.cells.size }} ticked)</legend>
          @for (g of offeredCells(); track g.row) {
            <div class="cellrow">
              <span class="rowname">{{ g.row }}</span>
              @for (v of g.verbs; track v) {
                <label class="check"><input type="checkbox" [checked]="draft.cells.has(g.row + ':' + v)" (change)="toggleCell(g.row + ':' + v)" /> {{ v }}</label>
              }
            </div>
          } @empty {
            <div class="muted small">Pick at least one role to build on.</div>
          }
        </fieldset>
        @if (modalError(); as e) {<mp-alert kind="bad">{{ e }}</mp-alert>}
      </form>
      <div modal-footer>
        <button class="ghost" type="button" (click)="draftOpen.set(false)">Cancel</button>
        <button class="primary" (click)="saveRole()" [disabled]="busy() || !draft.name || !draft.cells.size">Save role</button>
      </div>
    </mp-modal>
  `,
  styles: [`
    .rbac-status { margin-bottom: 12px; padding: 10px 12px; border: 1px solid #d6a43a; border-radius: 6px; color: #f4d58d; background: #302713; font-size: 13px; }
    :host { display: grid; gap: 14px; }
    .pad { padding: 16px; display: grid; gap: 10px; }
    .row { display: flex; gap: 8px; flex-wrap: wrap; }
    .actions { display: flex; gap: 6px; justify-content: flex-end; }
    .small { font-size: var(--fs-xs); }
    tr.off td { opacity: .6; }
    .assign { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 10px; align-items: end; }
    .form { display: grid; gap: 12px; }
    .fields { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 10px; }
    label { display: grid; gap: 5px; font-size: var(--fs-sm); color: var(--a-dim); }
    label.check { display: inline-flex; gap: 6px; align-items: center; color: var(--a-text); }
    label.check input { width: auto; }
    fieldset { border: 1px solid var(--a-border); border-radius: 8px; padding: 12px; margin: 0; min-width: 0; }
    legend { color: var(--a-dim); font-size: var(--fs-sm); padding: 0 6px; }
    .bases { display: flex; gap: 14px; flex-wrap: wrap; }
    .cellrow { display: flex; gap: 12px; flex-wrap: wrap; align-items: center; padding: 4px 0;
               border-bottom: 1px solid var(--a-border); }
    .rowname { min-width: 200px; font-family: var(--font-mono, monospace); font-size: var(--fs-xs); }
  `],
})
export class OrgAccess {
  readonly orgId = input.required<string>();
  /** The caller is above this org (or MiPay staff): show the ceiling editor. */
  readonly canEditCeiling = input(false);
  /** The caller administers this org: show role and assignment controls. */
  readonly manage = input(false);

  private readonly http = inject(HttpClient);
  readonly roles = signal<OrgRolesView | null>(null);
  readonly members = signal<OrgMember[]>([]);
  readonly assignments = signal<RoleAssignment[]>([]);
  readonly approvals = signal<RoleAssignment[]>([]);
  readonly error = signal('');
  readonly modalError = signal('');
  readonly busy = signal(false);
  readonly flash = signal<{ kind: 'ok' | 'bad'; text: string } | null>(null);
  readonly draftOpen = signal(false);
  readonly draftTick = signal(0);
  draft: RoleDraft = emptyDraft();
  aUser = '';
  aRole = '';
  aReason = '';

  readonly label = roleLabel;

  /** Standard roles inside the ceiling. */
  readonly usable = computed(() => (this.roles()?.roles ?? []).filter((r) => r.in_ceiling));
  /** What can be given: usable standard roles and the org's own roles still inside the ceiling. */
  readonly assignable = computed(() => [
    ...this.usable().map((r) => r.name),
    ...(this.roles()?.org_roles ?? []).filter((r) => r.in_ceiling).map((r) => r.name),
  ]);
  readonly current = computed(() => this.assignments().filter((a) => a.status === 'ACTIVE' || a.status === 'PENDING_APPROVAL'));
  readonly currentCols: DataCol<RoleAssignment>[] = [
    { key: 'person', label: 'Person', search: (a) => [a.userName, a.subject, a.username] },
    { key: 'role', label: 'Role', search: (a) => this.label(a.role) },
    { key: 'status', label: 'Status', text: (a) => (a.status === 'PENDING_APPROVAL' ? 'waiting for approval' : a.status) },
    { key: 'since', label: 'Since', cls: 'small', text: (a) => (a.requested_at || '').slice(0, 10) },
    { key: 'act', label: '' },
  ];
  readonly approvalCols: DataCol<RoleAssignment>[] = [
    { key: 'person', label: 'Person', text: (a) => this.nameOf(a.subject) },
    { key: 'role', label: 'Role', text: (a) => this.label(a.role) },
    { key: 'by', label: 'Asked by', cls: 'small', text: (a) => this.nameOf(a.requested_by) },
    { key: 'act', label: '', cls: 'actions' },
  ];
  /** standard roles it may use, then its own roles: one searchable, paged list */
  readonly roleRows = computed(() => [
    ...this.usable().map((r) => ({ own: false, r })),
    ...(this.roles()?.org_roles ?? []).map((r) => ({ own: true, r })),
  ]);
  readonly roleCols: DataCol<{ own: boolean; r: RbacRole }>[] = [
    { key: 'name', label: 'Role', search: (x) => [x.r.name, this.label(x.r.name), x.r.description, x.own ? 'own' : ''] },
    { key: 'base', label: 'Built on', cls: 'small', text: (x) => (x.own ? x.r.base_roles.join(', ') : 'standard role') },
    { key: 'perms', label: 'Permissions', text: (x) => x.r.permissions.length },
    { key: 'appr', label: 'Granting needs', cls: 'small', text: (x) => this.approvalText(x.r.approval) },
    { key: 'act', label: '', cls: 'actions' },
  ];
  readonly roleKey = (x: { own: boolean; r: RbacRole }) => (x.own ? 'own:' : 'std:') + x.r.name;
  readonly roleClass = (x: { own: boolean; r: RbacRole }) => (x.own && !x.r.in_ceiling ? 'off' : '');
  /** The cells the draft may tick: the union of its base roles' effective cells. */
  readonly offeredCells = computed(() => {
    this.draftTick();
    const byName = new Map((this.roles()?.roles ?? []).map((r) => [r.name, r]));
    const cells = new Set<string>();
    for (const b of this.draft.bases) for (const c of byName.get(b)?.permissions ?? []) cells.add(c);
    return groupCells(cells);
  });

  constructor() {
    effect(() => { const id = this.orgId(); if (id) void this.reload(); });
  }

  approvalText(a: string) {
    return ({ none: 'an administrator', org_admin: 'a second administrator', platform: 'MiPay', compliance: 'MiPay compliance' } as Record<string, string>)[a] ?? a;
  }

  nameOf(userId: string) {
    return this.members().find((m) => m.id === userId)?.name ?? userId;
  }

  async reload() {
    const id = this.orgId();
    this.error.set('');
    try {
      const [roles, members, assignments, approvals] = await Promise.all([
        firstValueFrom(this.http.get<OrgRolesView>(`/api/orgs/${id}/roles`)),
        firstValueFrom(this.http.get<OrgMember[]>(`/api/orgs/${id}/members`)),
        firstValueFrom(this.http.get<RoleAssignment[]>(`/api/orgs/${id}/role-assignments`)),
        firstValueFrom(this.http.get<RoleAssignment[]>(`/api/orgs/${id}/role-approvals`)),
      ]);
      this.roles.set(roles);
      this.members.set(members);
      this.assignments.set(assignments);
      this.approvals.set(approvals);
    } catch (e) {
      this.error.set(refusal(e, RBAC_HINTS));
    }
  }

  // ---- org-defined roles ----

  newRole() {
    this.draft = emptyDraft();
    this.modalError.set('');
    this.draftTick.update((n) => n + 1);
    this.draftOpen.set(true);
  }

  editRole(r: RbacRole) {
    this.draft = { editing: r.name, name: roleLabel(r.name), description: r.description,
      bases: new Set(r.base_roles), cells: new Set(r.permissions) };
    this.modalError.set('');
    this.draftTick.update((n) => n + 1);
    this.draftOpen.set(true);
  }

  toggleBase(b: string) {
    if (this.draft.bases.has(b)) this.draft.bases.delete(b); else this.draft.bases.add(b);
    this.draftTick.update((n) => n + 1);
    // drop ticked cells no base offers any more
    const offered = new Set(this.offeredCells().flatMap((g) => g.verbs.map((v) => g.row + ':' + v)));
    for (const c of [...this.draft.cells]) if (!offered.has(c)) this.draft.cells.delete(c);
  }

  toggleCell(c: string) {
    if (this.draft.cells.has(c)) this.draft.cells.delete(c); else this.draft.cells.add(c);
    this.draftTick.update((n) => n + 1);
  }

  async saveRole() {
    const d = this.draft;
    const body = { name: d.name.trim(), description: d.description.trim(), baseRoles: [...d.bases], permissions: [...d.cells] };
    this.busy.set(true);
    this.modalError.set('');
    try {
      if (d.editing) await firstValueFrom(this.http.put(`/api/orgs/${this.orgId()}/roles/${encodeURIComponent(d.editing)}`, body));
      else await firstValueFrom(this.http.post(`/api/orgs/${this.orgId()}/roles`, body));
      this.draftOpen.set(false);
      this.flash.set({ kind: 'ok', text: `Role ${body.name} saved.` });
      await this.reload();
    } catch (e) {
      this.modalError.set(refusal(e, RBAC_HINTS));
    } finally {
      this.busy.set(false);
    }
  }

  async deleteRole(r: RbacRole) {
    await this.act(() => firstValueFrom(this.http.delete(`/api/orgs/${this.orgId()}/roles/${encodeURIComponent(r.name)}`)),
      `Role ${roleLabel(r.name)} deleted.`);
  }

  // ---- assignments ----

  async assign() {
    const body = { userId: this.aUser, role: this.aRole, scope: 'own', reason: this.aReason.trim() };
    await this.act(async () => {
      const a = await firstValueFrom(this.http.post<RoleAssignment>(`/api/orgs/${this.orgId()}/role-assignments`, body));
      this.aUser = ''; this.aRole = ''; this.aReason = '';
      return a;
    }, (a) => (a as RoleAssignment).status === 'PENDING_APPROVAL'
      ? 'Requested. A second person must approve it before it takes effect.' : 'Role given.');
  }

  async revoke(a: RoleAssignment) {
    await this.act(() => firstValueFrom(this.http.post(`/api/orgs/${this.orgId()}/role-assignments/${a.id}/revoke`, { reason: 'removed in Roles & access' })),
      'Role taken away.');
  }

  async decide(a: RoleAssignment, approve: boolean) {
    await this.act(() => firstValueFrom(this.http.post(`/api/orgs/${this.orgId()}/role-approvals/${a.id}`, { approve, reason: '' })),
      approve ? 'Approved.' : 'Rejected.');
  }

  private async act<T>(fn: () => Promise<T>, ok: string | ((r: T) => string)) {
    this.busy.set(true);
    this.flash.set(null);
    try {
      const r = await fn();
      this.flash.set({ kind: 'ok', text: typeof ok === 'string' ? ok : ok(r) });
      await this.reload();
    } catch (e) {
      this.flash.set({ kind: 'bad', text: refusal(e, RBAC_HINTS) });
    } finally {
      this.busy.set(false);
    }
  }
}
