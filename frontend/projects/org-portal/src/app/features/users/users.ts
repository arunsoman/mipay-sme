import { Modal } from '../../../../../shared/ui/modal';
import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { PortalAuthService } from '../../core/auth';
import {
  FlatNode, MessageView, OrgNodeRow, OrgStaffView, OrgUserRow, flatten, nodeById, refusal, statusClass,
} from '../../core/api';

const HINTS: Record<string, string> = {
  LAST_ADMIN: 'an organization must keep at least one active ADMIN',
  DUPLICATE_PHONE: 'that phone number is already registered',
  ORG_SUSPENDED: 'your organization (or one above it) is suspended',
};

/**
 * Users: the caller's own team (add STAFF / ADMIN, deactivate, resend
 * activation) and, read-mostly, the administrators of organizations below -
 * an ancestor ADMIN may deactivate them or resend their activation link.
 */
@Component({
  selector: 'mpo-users',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, Modal],
  template: `
    <div class="head">
      <h2>Users</h2>
      <select name="org" [ngModel]="orgId()" (ngModelChange)="orgId.set($event)">
        @for (n of orgs(); track n.id) {
          <option [value]="n.id">{{ '· '.repeat(n.depth) }}{{ n.name }} ({{ n.type.toLowerCase() }})</option>
        }
      </select>
      <button class="ghost" (click)="load()">Refresh</button>
    </div>
    @if (flash(); as f) {<div class="flash {{ f.kind }}">{{ f.text }}</div>}
    @if (problem(); as p) {<div class="err">{{ p }}</div>}

    <section class="panel">
      <table class="grid">
        <thead><tr><th>Name</th><th>Username</th><th>Org role</th><th>Status</th><th>Phone</th><th>Email</th><th></th></tr></thead>
        <tbody>
          @for (u of users(); track u.id) {
            <tr>
              <td>{{ u.name }} @if (u.id === auth.me()?.userId) {<span class="muted small">(you)</span>}</td>
              <td class="mono">{{ u.username ?? '—' }}</td>
              <td><span class="chip" [class.teal]="u.orgRole === 'ADMIN'">{{ u.orgRole ?? '—' }}</span></td>
              <td><span class="chip {{ cls(u.status) }}">{{ u.status }}</span></td>
              <td class="mono">{{ u.phone ?? 'hidden' }}</td>
              <td class="mono">{{ u.email ?? 'hidden' }}</td>
              <td class="acts">
                @if (u.status === 'ACTIVE' && u.id !== auth.me()?.userId) {
                  <button class="ghost" (click)="resetCredential(u)" [disabled]="busy()">Reset credential</button>
                  <button class="danger" (click)="deactivate(u)" [disabled]="busy()">Deactivate</button>
                }
                @if (u.status === 'PENDING') {
                  <button (click)="resend(u)" [disabled]="busy()">Resend activation</button>
                }
              </td>
            </tr>
          } @empty {
            <tr><td colspan="7" class="muted">No users visible for this organization.</td></tr>
          }
        </tbody>
      </table>
    </section>

    @if (isOwn()) {
      <div class="addrow"><button class="primary" (click)="openAdd()">＋ Add a team member</button></div>
    }
    <mp-modal [open]="adding()" eyebrow="Users" title="Add a team member"
              subtitle="They receive an email with their MiPay username and a link to set their own password."
              [dirty]="addDirty()" (closed)="adding.set(false)">
      @if (addError(); as e) {<div class="err">{{ e }}</div>}
      <form id="addMemberForm" class="fields" (ngSubmit)="addStaff()">
        <label>Name * <input name="n" [(ngModel)]="s.name" required /></label>
        <label>Phone * <input name="p" [(ngModel)]="s.phone" required /></label>
        <label>Email * <input name="e" type="email" [(ngModel)]="s.email" required /></label>
        <label>Username <input name="u" value="" readonly tabindex="-1" class="ro"
                               placeholder="created by MiPay · emailed to the address above" /></label>
        <label>Role
          <select name="r" [(ngModel)]="s.orgRole">
            <option value="STAFF">STAFF — day-to-day work, cannot move money</option>
            <option value="ADMIN">ADMIN — full control, can use this portal</option>
          </select></label>
      </form>
      <div modal-footer>
        <button type="button" class="ghost" (click)="adding.set(false)">Cancel</button>
        <button type="submit" form="addMemberForm" class="primary" [disabled]="busy()">Add member</button>
      </div>
    </mp-modal>
  `,
  styles: [`
    .addrow { display: flex; justify-content: flex-end; margin: -4px 0 16px; }
    .ro { opacity: .7; cursor: not-allowed; font-style: italic; }
    .head { display: flex; align-items: center; gap: 14px; margin-bottom: 14px; flex-wrap: wrap; }
    h2 { margin: 0; font-size: 30px; line-height: 1.1; }
    .head select { width: auto; max-width: 420px; flex: 1; }
    section { margin-bottom: 16px; overflow-x: auto; }
    .pad { padding: 16px; }
    .ptitle { font-size: 14px; font-weight: 600; margin-bottom: 12px; }
    .fields { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 10px; align-items: end; }
    label { display: grid; gap: 5px; font-size: var(--fs-sm); color: var(--a-dim); }
    .acts { white-space: nowrap; }
    .hint { color: var(--a-dim); font-size: var(--fs-xs); margin-top: 8px; }
    .err { color: var(--a-red); font-size: var(--fs-sm); margin: 6px 0; }
    .flash { font-size: var(--fs-sm); margin: 0 0 12px; padding: 8px 12px; border-radius: 8px; }
    .flash.ok { background: color-mix(in srgb, var(--a-green) 14%, transparent); color: var(--a-green); }
    .flash.bad { background: color-mix(in srgb, var(--a-red) 14%, transparent); color: var(--a-red); }
  `],
})
export class Users implements OnInit {
  private readonly http = inject(HttpClient);
  readonly auth = inject(PortalAuthService);
  readonly cls = statusClass;

  readonly tree = signal<OrgNodeRow | null>(null);
  readonly orgId = signal<string | null>(null);
  readonly ownUsers = signal<OrgUserRow[]>([]);
  readonly problem = signal('');
  readonly busy = signal(false);
  readonly flash = signal<{ kind: 'ok' | 'bad'; text: string } | null>(null);
  s = { name: '', phone: '', email: '', orgRole: 'STAFF' };
  readonly adding = signal(false);
  readonly addError = signal('');
  addDirty() { return !!(this.s.name || this.s.phone || this.s.email); }
  openAdd() {
    this.s = { name: '', phone: '', email: '', orgRole: 'STAFF' };
    this.addError.set('');
    this.adding.set(true);
  }

  readonly orgs = computed<FlatNode[]>(() => (this.tree() ? flatten(this.tree()!) : []));
  readonly isOwn = computed(() => this.orgId() === this.auth.orgId());
  readonly users = computed<OrgUserRow[]>(() => {
    if (this.isOwn()) return this.ownUsers();
    const t = this.tree(); const id = this.orgId();
    return (t && id ? nodeById(t, id)?.users : null) ?? [];
  });

  async ngOnInit() {
    this.orgId.set(this.auth.orgId());
    await this.load();
  }

  async load() {
    this.problem.set('');
    try {
      const [t, own] = await Promise.all([
        firstValueFrom(this.http.get<OrgNodeRow>('/api/orgs/me/tree')),
        firstValueFrom(this.http.get<OrgUserRow[]>('/api/orgs/me/users')),
      ]);
      this.tree.set(t);
      this.ownUsers.set(own);
      if (!this.orgId() || !nodeById(t, this.orgId()!)) this.orgId.set(t.id);
    } catch (e) {
      this.problem.set(refusal(e, HINTS));
    }
  }

  private async act(fn: () => Promise<string>) {
    this.busy.set(true);
    try {
      this.flash.set({ kind: 'ok', text: await fn() });
      await this.load();
    } catch (e) {
      this.flash.set({ kind: 'bad', text: refusal(e, HINTS) });
    } finally {
      this.busy.set(false);
    }
  }

  deactivate(u: OrgUserRow) {
    if (!confirm(`Deactivate ${u.name}? They are signed out immediately.`)) return;
    void this.act(async () => (await firstValueFrom(
      this.http.post<MessageView>(`/api/orgs/users/${u.id}/deactivate`, {}))).message);
  }

  resend(u: OrgUserRow) {
    void this.act(async () => (await firstValueFrom(
      this.http.post<MessageView>(`/api/orgs/users/${u.id}/resend-activation`, {}))).message);
  }

  resetCredential(u: OrgUserRow) {
    const orgId = this.orgId();
    if (!orgId || !confirm(`Send ${u.name} a credential reset link? Their current password and sessions will stop working after the reset is processed.`)) return;
    void this.act(async () => {
      await firstValueFrom(this.http.post<{ enqueued: boolean }>(
        `/api/orgs/${encodeURIComponent(orgId)}/auth/users/${encodeURIComponent(u.id)}/reset-credential`, {}));
      return `Credential reset link queued for ${u.name}.`;
    });
  }

  async addStaff() {
    const s = this.s;
    this.busy.set(true);
    this.addError.set('');
    try {
      // the username is created by the backend and emailed with the activation link
      const v = await firstValueFrom(this.http.post<OrgStaffView>('/api/orgs/me/users', {
        name: s.name.trim(), phone: s.phone.trim(), email: s.email.trim(),
        username: null, orgRole: s.orgRole, password: null,
      }));
      this.adding.set(false);
      this.flash.set({ kind: 'ok', text: `${v.name} added (${v.status})${v.username ? ' as ' + v.username : ''}; their username and activation link have been emailed.` });
      await this.load();
    } catch (e) {
      this.addError.set(refusal(e, HINTS));
    } finally {
      this.busy.set(false);
    }
  }
}
