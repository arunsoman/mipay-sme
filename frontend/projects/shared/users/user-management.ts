import { PageHeader } from '../../../src/app/design-system/organisms/page-header';
import { MpSpinner } from '../../../src/app/design-system/atoms/mp-spinner';
import { MpAlert } from '../../../src/app/design-system/molecules/alert';
import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { Modal } from '../../../src/app/design-system/organisms/modal';
import { MpTabs, TabOption } from '../../../src/app/design-system/molecules/tabs';
import { DataCol, DATA_TABLE } from '../../../src/app/design-system/organisms/data-table';
import { MpPerm } from '../web-core/perm-directive';
import { AccessService } from '../web-core/access';
import { readProblem } from '../kyc/kyc-api';
import { OrgRolesView, RbacRole, RBAC_HINTS, RoleAssignment } from '../rbac/rbac-api';

/** GET /api/orgs/me/access */
export interface MyAccess {
  orgId: string; orgName: string; orgType: string;
  permissions: { id: string; row: string; verb: string; rowDescription: string }[];
}
interface Person { id: string; name: string; username: string; status: string; phone: string; email: string; }

/** The permission that shows User management (RBAC or extended RBAC). */
export const USER_ADMIN_CELL = 'org.user:administer';

const VERB_WORD: Record<string, string> = {
  read: 'view', create: 'create', modify: 'change', delete: 'delete', submit: 'submit', authorize: 'approve',
  execute: 'perform', export: 'export', administer: 'manage', simulate: 'simulate',
};
const VERB_ORDER = ['read', 'create', 'modify', 'delete', 'submit', 'authorize', 'execute', 'export', 'administer', 'simulate'];

/**
 * User management, the same in every portal, for the signed-in user's OWN organization:
 * its people, adding a person, and then giving them a role: an existing one, or a new one
 * built from permissions the manager holds (RBAC or extended RBAC; never more), then assigned.
 * Shown only to holders of org.user:administer.
 */
@Component({
  selector: 'mp-user-management',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PageHeader, MpSpinner, MpAlert, MpTabs, FormsModule, Modal, MpPerm, ...DATA_TABLE],
  template: `
    <page-header title="User management">
      <div lead>@if (access(); as a) {<span class="org">{{ a.orgName }}</span>}</div>
      <div actions>
        @if (canManage()) {<button *mpPerm="'org.user:create'" class="primary" type="button" (click)="startAdd()">＋ Add person</button>}
      </div>
    </page-header>

    @if (loadError(); as e) {<mp-alert kind="bad">{{ e }}</mp-alert>}
    @if (access() && !canManage()) {
      <div class="panel pad muted">User management needs the permission to manage users ({{ cell }}) in your organization.</div>
    } @else if (access()) {
      @if (flash(); as f) {<mp-alert [kind]="f.kind">{{ f.text }}</mp-alert>}
      <section class="panel tblpanel">
        <mp-data-table [cols]="peopleCols" [rows]="people()" placeholder="Search people, contacts, roles"
                       empty="No people in this organization yet." noMatch="Nobody matches your search.">
          <ng-template mpCell="name" let-p><div class="nm">{{ p.name }}</div><div class="sub mono">{{ p.username }}</div></ng-template>
          <ng-template mpCell="contact" let-p><div>{{ p.phone }}</div><div class="sub">{{ p.email }}</div></ng-template>
          <ng-template mpCell="roles" let-p>
            @for (r of rolesOf(p.id); track r.id) {<span class="chip" [class.pend]="r.status !== 'ACTIVE'">{{ label(r.role) }}</span>}
            @empty {<span class="sub">no role</span>}
          </ng-template>
          <ng-template mpCell="status" let-p><span class="st {{ p.status.toLowerCase() }}">{{ p.status.toLowerCase() }}</span></ng-template>
          <ng-template mpCell="act" let-p>
            @if (p.status === 'PENDING') {
              <button *mpPerm="'org.user:administer'" type="button" class="ghost sm" (click)="setupLink(p)" [disabled]="busy()">Set-up link</button>
            }
            <button *mpPerm="'rbac.assignment:create'" type="button" class="ghost sm" (click)="startRole(p)">Give a role</button>
          </ng-template>
        </mp-data-table>
      </section>
    } @else if (!loadError()) {
      <mp-spinner label="Loading…" />
    }

    <!-- a pending person's set-up link: emailed, and shown here to copy -->
    <mp-modal [open]="!!link()" eyebrow="Set-up link" [title]="link()?.name ?? ''" (closed)="link.set(null)">
      @if (link(); as l) {
        <p class="hint">A new link was issued and emailed to {{ l.email || 'them' }}. Earlier links stop working.
          It works once and expires in 72 hours. Anyone with it can set this person's password: share it only with them.</p>
        <div class="linkrow"><input class="inp mono" readonly [value]="l.url" (focus)="$any($event.target).select()" />
          <button type="button" class="primary" (click)="copy(l.url)">{{ copied() ? 'Copied' : 'Copy' }}</button></div>
      }
    </mp-modal>

    <!-- 1. add a person -->
    <mp-modal [open]="adding()" eyebrow="User management" title="Add a person" [dirty]="!!(np.name || np.phone || np.email)"
              discardText="Discard this person? Nothing has been created yet." (closed)="adding.set(false)">
      <form #f="ngForm" class="form" [class.tried]="f.submitted" (ngSubmit)="f.valid && addPerson()" novalidate>
        <label class="fl"><span class="lb">Full name</span><input class="inp" name="name" [(ngModel)]="np.name" required /></label>
        <div class="two">
          <label class="fl"><span class="lb">Mobile number</span>
            <input class="inp" name="phone" type="tel" [(ngModel)]="np.phone" required placeholder="08x xxx xxxx" /></label>
          <label class="fl"><span class="lb">Email</span>
            <input class="inp" name="email" type="email" [(ngModel)]="np.email" required email /></label>
        </div>
        <p class="hint">They get their username and a set-up link by email. Next you give them a role.</p>
        @if (modalError(); as e) {<mp-alert kind="bad">{{ e }}</mp-alert>}
        <div class="foot">
          <button type="button" class="ghost" (click)="adding.set(false)">Cancel</button>
          <span class="grow"></span>
          <button class="primary" type="submit" [disabled]="busy()">{{ busy() ? 'Adding…' : 'Add and continue' }}</button>
        </div>
      </form>
    </mp-modal>

    <!-- 2. give the person a role: existing, or built from the manager's permissions -->
    <mp-modal [open]="!!roleFor()" size="wide" eyebrow="Give a role" [title]="roleFor()?.name ?? ''"
              [dirty]="mode() === 'new' && (picked().size > 0 || !!newRole.name)"
              discardText="Discard this role? Nothing has been created yet." (closed)="roleFor.set(null)">
      <mp-tabs [options]="modeOpts()" [value]="mode()" (valueChange)="mode.set($event)" label="Role" />

      @if (mode() === 'existing') {
        <div class="roles">
          @for (r of orgRoles(); track r.name) {
            <label class="rolecard" [class.on]="existing === r.name">
              <input type="radio" name="er" [value]="r.name" [(ngModel)]="existing" />
              <span><b>{{ label(r.name) }}</b>
                <span class="sub">{{ r.description || (r.permissions.length + ' permissions') }}</span></span>
            </label>
          }
        </div>
      } @else {
        <div class="two">
          <label class="fl"><span class="lb">Role name</span>
            <input class="inp" [(ngModel)]="newRole.name" placeholder="e.g. Cashier" maxlength="40" /></label>
          <label class="fl"><span class="lb">What it is for <em>optional</em></span>
            <input class="inp" [(ngModel)]="newRole.description" placeholder="e.g. Takes payments at the till" /></label>
        </div>
        <div class="pickhead">
          <span class="lb">Permissions <span class="sub">— from what you hold yourself · {{ picked().size }} picked</span></span>
          <input class="inp filter" [(ngModel)]="filterText" placeholder="Filter" />
        </div>
        <div class="groups">
          @for (g of groups(); track g.row) {
            <div class="grp">
              <div class="gname"><b>{{ g.desc }}</b><span class="sub mono">{{ g.row }}</span></div>
              <div class="chips">
                @for (v of g.verbs; track v) {
                  <button type="button" class="chipbtn" [class.on]="picked().has(g.row + ':' + v)"
                          [attr.aria-pressed]="picked().has(g.row + ':' + v)" (click)="toggle(g.row + ':' + v)">
                    {{ picked().has(g.row + ':' + v) ? '✓ ' : '' }}{{ word(v) }}</button>
                }
              </div>
            </div>
          } @empty {<div class="sub">No permission matches.</div>}
        </div>
      }
      @if (modalError(); as e) {<mp-alert kind="bad">{{ e }}</mp-alert>}
      <div modal-footer>
        <button type="button" class="ghost" (click)="roleFor.set(null)">Later</button>
        <button type="button" class="primary" (click)="giveRole()"
                [disabled]="busy() || (mode() === 'existing' ? !existing : (!newRole.name.trim() || !picked().size))">
          {{ busy() ? 'Saving…' : mode() === 'existing' ? 'Give this role' : 'Create role and give it' }}</button>
      </div>
    </mp-modal>
  `,
  styles: [`
    h2 { margin: 0; font-size: 28px; }
    .org { color: var(--a-dim); font-size: 14px; }
    .muted, .sub { color: var(--a-dim); }
    .sub { font-size: 12px; display: block; }
    .mono { font-family: ui-monospace, "SF Mono", Menlo, Consolas, monospace; }
    .panel { overflow-x: auto; }
    .tblpanel { padding: 12px 14px; }
    .nm { font-weight: 600; }
    .chip { display: inline-block; margin: 0 6px 6px 0; padding: 3px 10px; border-radius: 999px; font-size: 12px;
            background: color-mix(in srgb, var(--a-teal) 14%, transparent); color: var(--a-teal); }
    .chip.pend { background: #222; color: var(--a-amber); }
    .st { font-size: 12px; } .st.active { color: var(--a-green); } .st.pending { color: var(--a-amber); }
    .act { text-align: right; white-space: nowrap; }
    button.sm { padding: 6px 12px; font-size: 12.5px; }
    .form { display: grid; gap: 16px; }
    .two { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
    @media (max-width: 640px) { .two { grid-template-columns: 1fr; } }
    .fl { display: grid; gap: 7px; min-width: 0; }
    .lb { font-size: 13px; font-weight: 550; color: #d9d9d9; }
    .lb em { font-style: normal; font-weight: 400; color: var(--a-dim); font-size: 11.5px; margin-left: 6px; }
    .inp { box-sizing: border-box; height: 44px; width: 100%; font-size: 14.5px; border-radius: 10px;
           background: #0d0d0d; border-color: #2a2a2a; padding: 0 14px; }
    .tried .inp.ng-invalid { border-color: var(--a-red); }
    .linkrow { display: flex; gap: 8px; margin-top: 12px; }
    .linkrow .inp { font-size: 12.5px; }
    .act button + button { margin-left: 6px; }
    .foot { display: flex; gap: 10px; align-items: center; }
    .foot button { min-height: 42px; padding: 0 20px; }
    .roles { display: grid; gap: 8px; }
    .rolecard { display: flex; gap: 12px; align-items: flex-start; padding: 12px 14px; border: 1px solid #262626; border-radius: 12px; cursor: pointer; }
    .rolecard.on { border-color: var(--a-teal); }
    .rolecard input { width: auto; margin-top: 3px; }
    .pickhead { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
    .filter { max-width: 220px; height: 36px; }
    .groups { display: grid; gap: 4px; max-height: 46vh; overflow: auto; padding-right: 4px; }
    .grp { display: grid; grid-template-columns: minmax(180px, 280px) 1fr; gap: 12px; padding: 10px 0; border-bottom: 1px solid #1d1d1d; align-items: center; }
    @media (max-width: 640px) { .grp { grid-template-columns: 1fr; } }
    .gname b { font-size: 13.5px; font-weight: 600; }
    .chips { display: flex; flex-wrap: wrap; gap: 6px; }
    .chipbtn { padding: 6px 12px; font-size: 12.5px; font-weight: 600; border-radius: 999px; border: 1px solid #3a3a3a; background: #151515; color: var(--a-text); }
    .chipbtn.on { background: color-mix(in srgb, var(--a-teal) 18%, transparent); border-color: var(--a-teal); color: var(--a-teal); }
  `],
})
export class UserManagement implements OnInit {
  private readonly http = inject(HttpClient);
  /** Every action here hides itself without its permission (*mpPerm). */
  readonly accessSvc = inject(AccessService);
  readonly cell = USER_ADMIN_CELL;

  readonly access = signal<MyAccess | null>(null);
  readonly people = signal<Person[]>([]);
  readonly peopleCols: DataCol<Person>[] = [
    { key: 'name', label: 'Person', search: (p) => [p.name, p.username] },
    { key: 'contact', label: 'Contact', search: (p) => [p.phone, p.email] },
    { key: 'roles', label: 'Roles', search: (p) => this.rolesOf(p.id).map((r) => this.label(r.role)) },
    { key: 'status', label: 'Status' },
    { key: 'act', label: '', cls: 'act' },
  ];
  readonly assignments = signal<RoleAssignment[]>([]);
  readonly orgRoles = signal<RbacRole[]>([]);
  readonly loadError = signal('');
  readonly flash = signal<{ kind: 'ok' | 'bad'; text: string } | null>(null);
  readonly modalError = signal('');
  readonly busy = signal(false);

  readonly adding = signal(false);
  readonly link = signal<{ name: string; email: string; url: string } | null>(null);
  readonly copied = signal(false);

  /** Issues a fresh set-up link (emailed through comms) and shows it to copy. */
  async setupLink(p: Person) {
    this.busy.set(true);
    this.flash.set(null);
    try {
      const r = await firstValueFrom(this.http.post<{ message: string; label: string | null }>(
        `/api/orgs/users/${p.id}/resend-activation`, {}));
      this.copied.set(false);
      if (r.label) this.link.set({ name: p.name, email: p.email, url: r.label });
      else this.flash.set({ kind: 'ok', text: `A new set-up link was emailed to ${p.name}.` });
    } catch (e) {
      this.flash.set({ kind: 'bad', text: this.refusal(e) });
    } finally {
      this.busy.set(false);
    }
  }

  async copy(text: string) {
    try { await navigator.clipboard.writeText(text); this.copied.set(true); } catch { this.copied.set(false); }
  }
  np = { name: '', phone: '', email: '' };

  readonly roleFor = signal<Person | null>(null);
  readonly mode = signal<'new' | 'existing'>('new');
  modeOpts(): TabOption<'new' | 'existing'>[] {
    return [
      { value: 'new', label: 'Create a role', hidden: !this.accessSvc.can('rbac.role:create') },
      { value: 'existing', label: `Use an existing role (${this.orgRoles().length})`, disabled: !this.orgRoles().length },
    ];
  }
  existing = '';
  newRole = { name: '', description: '' };
  filterText = '';
  readonly picked = signal<Set<string>>(new Set());

  readonly canManage = computed(() => !!this.access()?.permissions.some((p) => p.id === USER_ADMIN_CELL));

  /** The manager's own permissions, by row: the only ones a new role may contain. */
  groups() {
    const f = this.filterText.trim().toLowerCase();
    const by = new Map<string, { row: string; desc: string; verbs: string[] }>();
    for (const p of this.access()?.permissions ?? []) {
      if (f && !(`${p.row} ${p.rowDescription}`.toLowerCase().includes(f))) continue;
      const g = by.get(p.row) ?? { row: p.row, desc: p.rowDescription || p.row, verbs: [] };
      g.verbs.push(p.verb);
      by.set(p.row, g);
    }
    for (const g of by.values()) g.verbs.sort((a, b) => VERB_ORDER.indexOf(a) - VERB_ORDER.indexOf(b));
    return [...by.values()].sort((a, b) => a.row.localeCompare(b.row));
  }

  word(v: string) { return VERB_WORD[v] ?? v; }
  /** "cashier@org_x" → "Cashier"; "organization_root" → "Organization root" */
  label(role: string) {
    const t = role.split('@')[0].replace(/_/g, ' ');
    return t.charAt(0).toUpperCase() + t.slice(1);
  }
  rolesOf(userId: string) {
    return this.assignments().filter((a) => a.subject === userId && ['ACTIVE', 'PENDING_APPROVAL'].includes(a.status));
  }

  async ngOnInit() { await this.load(); }

  async load() {
    this.loadError.set('');
    try {
      const a = await firstValueFrom(this.http.get<MyAccess>('/api/orgs/me/access'));
      this.access.set(a);
      if (!this.canManage()) return;
      const [people, asg, roles] = await Promise.all([
        firstValueFrom(this.http.get<Person[]>('/api/orgs/me/users')),
        firstValueFrom(this.http.get<RoleAssignment[]>(`/api/orgs/${a.orgId}/role-assignments`)).catch(() => []),
        firstValueFrom(this.http.get<OrgRolesView>(`/api/orgs/${a.orgId}/roles`)).catch(() => null),
      ]);
      this.people.set(people);
      this.assignments.set(asg);
      // the standard org-level roles it may use (e.g. admin_role, reseller_role), then its own roles
      this.orgRoles.set([
        ...(roles?.roles ?? []).filter((r) => r.kind === 'ORG' && r.in_ceiling !== false),
        ...(roles?.org_roles ?? []),
      ]);
    } catch (e) {
      this.loadError.set(this.refusal(e));
    }
  }

  startAdd() {
    this.np = { name: '', phone: '', email: '' };
    this.modalError.set('');
    this.adding.set(true);
  }

  async addPerson() {
    this.busy.set(true);
    this.modalError.set('');
    try {
      const p = await firstValueFrom(this.http.post<Person>('/api/orgs/me/users', {
        name: this.np.name.trim(), phone: this.np.phone.trim(), email: this.np.email.trim(),
      }));
      this.adding.set(false);
      await this.load();
      this.flash.set({ kind: 'ok', text: `${p.name} added — username ${p.username}. Now give them a role.` });
      this.startRole(p);
    } catch (e) {
      this.modalError.set(this.refusal(e));
    } finally {
      this.busy.set(false);
    }
  }

  startRole(p: Person) {
    this.modalError.set('');
    this.newRole = { name: '', description: '' };
    this.filterText = '';
    this.picked.set(new Set());
    this.existing = '';
    // without rbac.role:create a manager can only give the org's existing roles
    this.mode.set(this.accessSvc.can('rbac.role:create') ? 'new' : 'existing');
    this.roleFor.set(p);
  }

  toggle(cell: string) {
    const s = new Set(this.picked());
    if (s.has(cell)) s.delete(cell); else s.add(cell);
    this.picked.set(s);
  }

  /** "Till cashier" → "till_cashier" (RBAC: lower_snake_case, 3-41 characters). */
  private slug(name: string) {
    let s = name.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[^a-z0-9]+/g, '_')
      .replace(/^_+|_+$/g, '');
    if (!/^[a-z]/.test(s)) s = 'r_' + s;
    return s.slice(0, 41).padEnd(3, 'x');
  }

  async giveRole() {
    const p = this.roleFor();
    const a = this.access();
    if (!p || !a) return;
    this.busy.set(true);
    this.modalError.set('');
    try {
      let role = this.existing;
      if (this.mode() === 'new') {
        const created = await firstValueFrom(this.http.post<{ name: string }>(`/api/orgs/${a.orgId}/roles`, {
          name: this.slug(this.newRole.name), description: this.newRole.description.trim() || this.newRole.name.trim(),
          baseRoles: [], permissions: [...this.picked()],
        }));
        role = created.name;
      }
      await firstValueFrom(this.http.post(`/api/orgs/${a.orgId}/role-assignments`, { userId: p.id, role, scope: 'own' }));
      this.roleFor.set(null);
      await this.load();
      this.flash.set({ kind: 'ok', text: `${p.name} now has the role ${this.label(role)}.` });
    } catch (e) {
      this.modalError.set(this.refusal(e));
    } finally {
      this.busy.set(false);
    }
  }

  private refusal(e: unknown) {
    const p = readProblem(e);
    return (p.code && RBAC_HINTS[p.code] ? RBAC_HINTS[p.code] + ' — ' : '') + (p.detail || p.title || 'Something went wrong');
  }
}
