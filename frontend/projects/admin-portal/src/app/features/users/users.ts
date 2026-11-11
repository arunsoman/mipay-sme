import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { AdminAuthService } from '../../core/auth';
import { MessageView, OrgNodeRow, OrgStaffView, OrgUserRow, OutboxRow, readProblem } from '../../core/api';
import { problemText } from '../onboarding/onboarding';
import { Modal } from '../../../../../shared/ui/modal';

/**
 * User management over the org tree ([C3/H9/M10]): pick an org from the
 * caller's visible tree, see its members, deactivate (last-ADMIN guard
 * enforced by the backend), re-issue activation links, and - for org ADMINs -
 * add staff that inherit the org's own role. MINDLABS_ADMIN additionally
 * reads the sandbox activation outbox ([M8], SIMULATED).
 */
@Component({
  selector: 'mpa-users',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, DatePipe, Modal],
  template: `
    <div class="head">
      <h2>User management</h2>
      <button class="ghost" (click)="reload()">Refresh</button>
    </div>

    <div class="cols">
      <!-- ---------------- org tree picker ---------------- -->
      <div class="panel tree">
        <div class="ptitle">Organizations</div>
        @if (problem(); as p) {<div class="err">{{ p }}</div>}
        @if (loading()) {<div class="muted">Loading tree…</div>}
        @if (tree(); as root) {
          @for (n of flatten(root); track n.id) {
            <div class="node" [class.selected]="n.id === selectedId()"
                 [class.depth0]="n.depth === 0" (click)="pick(n)" role="button" tabindex="0"
                 (keydown.enter)="pick(n)">
              <span class="indent">{{ indent(n.depth) }}</span>
              <span class="nm">{{ n.name }}</span>
              <span class="chip small">{{ n.type }}</span>
              <span class="chip" [class.ok]="n.effectiveStatus === 'ACTIVE'"
                    [class.bad]="n.effectiveStatus.startsWith('SUSPENDED')"
                    [class.warn]="n.effectiveStatus === 'PENDING'">{{ n.effectiveStatus }}</span>
              <span class="muted small">{{ n.userCount }} user(s)</span>
            </div>
          }
        }
      </div>

      <!-- ---------------- selected org ---------------- -->
      <div class="panel details">
        @if (selected(); as org) {
          <div class="ptitle">{{ org.name }} <span class="chip">{{ org.type }}</span>
            <span class="chip" [class.ok]="org.effectiveStatus === 'ACTIVE'"
                  [class.bad]="org.effectiveStatus.startsWith('SUSPENDED')"
                  [class.warn]="org.effectiveStatus === 'PENDING'">{{ org.effectiveStatus }}</span>
            <div class="mono small muted">{{ org.id }}</div></div>

          <table class="grid">
            <thead><tr><th>Name</th><th>Username</th><th>Role</th><th>Org role</th>
              <th>Status</th><th>Phone</th><th>Email</th><th></th></tr></thead>
            <tbody>
              @for (u of org.users; track u.id) {
                <tr>
                  <td>{{ u.name }}</td>
                  <td class="mono small">{{ u.username ?? '—' }}</td>
                  <td><span class="chip small">{{ u.role }}</span></td>
                  <td><span class="chip small" [class.teal]="u.orgRole === 'ADMIN'">{{ u.orgRole ?? '—' }}</span></td>
                  <td><span class="chip small" [class.ok]="u.status === 'ACTIVE'"
                            [class.warn]="u.status === 'PENDING'"
                            [class.bad]="u.status === 'SUSPENDED'">{{ u.status }}</span></td>
                  <td class="mono small">{{ u.phone ?? 'masked' }}</td>
                  <td class="mono small">{{ u.email ?? 'masked' }}</td>
                  <td class="acts">
                    @if (u.status === 'ACTIVE') {
                      <button class="danger" (click)="deactivate(u)">Deactivate</button>
                    }
                    @if (u.status === 'PENDING') {
                      <button (click)="resend(u)">Resend activation</button>
                    }
                  </td>
                </tr>
              } @empty {
                <tr><td colspan="8" class="muted">No members visible for this org.</td></tr>
              }
            </tbody>
          </table>

          @if (canAddStaff()) {
            <div class="addrow"><button class="primary" (click)="openAddStaff()">＋ Add staff</button></div>
          }
          <mp-modal [open]="addingStaff()" eyebrow="User management" [title]="'Add staff to ' + org.name"
                    subtitle="Staff inherit the org's role. Their MiPay username and an activation link are emailed to them."
                    [dirty]="staffDirty()" (closed)="addingStaff.set(false)">
            @if (staffError(); as e) {<div class="err">{{ e }}</div>}
            <form id="addStaffForm" class="mform" (ngSubmit)="addStaff()">
              <label>Name * <input name="st_name" [(ngModel)]="stName" required /></label>
              <label>Phone * <input name="st_phone" [(ngModel)]="stPhone" placeholder="0xx…" required /></label>
              <label>Username <input name="st_user" value="" readonly tabindex="-1" class="ro"
                                     placeholder="created by MiPay · emailed to the address" /></label>
              <label>Email * <input name="st_mail" type="email" [(ngModel)]="stEmail" required /></label>
              <label>Org role
                <select name="st_role" [(ngModel)]="stOrgRole">
                  <option value="STAFF">STAFF</option>
                  <option value="ADMIN">ADMIN</option>
                </select>
              </label>
            </form>
            <div modal-footer>
              <button type="button" class="ghost" (click)="addingStaff.set(false)">Cancel</button>
              <button type="submit" form="addStaffForm" class="primary" [disabled]="busy()">Add staff member</button>
            </div>
          </mp-modal>
        } @else {
          <div class="muted pad">Pick an organization on the left.</div>
        }
      </div>
    </div>

    <!-- ---------------- activation outbox (MINDLABS_ADMIN, sandbox only) ---------------- -->
    @if (auth.role() === 'MINDLABS_ADMIN') {
      <div class="panel outbox">
        <div class="ptitle">Activation outbox <span class="chip warn">SIMULATED · sandbox only</span></div>
        @if (outboxError(); as e) {<div class="err">{{ e }}</div>}
        <table class="grid">
          <thead><tr><th>Event</th><th>Channel</th><th>Recipient</th><th>Status</th>
            <th>Created</th><th>Activation link</th></tr></thead>
          <tbody>
            @for (o of outbox(); track o.id) {
              <tr>
                <td><span class="chip small">{{ o.eventCode }}</span></td>
                <td>{{ o.channel }}</td>
                <td class="mono small">{{ o.recipient }}</td>
                <td><span class="chip small" [class.ok]="o.status === 'SENT'">{{ o.status }}</span></td>
                <td>{{ o.createdAt | date:'MMM d, HH:mm' }}</td>
                <td class="mono small wrap">{{ o.activationLink }}</td>
              </tr>
            } @empty {
              <tr><td colspan="6" class="muted">No activation messages yet.</td></tr>
            }
          </tbody>
        </table>
      </div>
    }

    @if (flash(); as f) {<div class="flash {{ f.kind }}">{{ f.text }}</div>}
  `,
  styles: [`
    .ro { opacity: .7; cursor: not-allowed; font-style: italic; }
    .addrow { display: flex; justify-content: flex-end; margin-top: 12px; }
    .mform { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 12px; }
    .mform label { display: grid; gap: 5px; font-size: var(--fs-sm); color: var(--a-dim); }
    .head { display: flex; align-items: center; gap: 14px; margin-bottom: 14px; }
    h2 { margin: 0; font-size: 30px; line-height: 1.1; }
    .cols { display: grid; grid-template-columns: 360px 1fr; gap: 14px; align-items: start; }
    .panel { padding: 12px; }
    .ptitle { font-size: 13.5px; font-weight: 600; margin-bottom: 10px; display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .ptitle .mono { width: 100%; }
    .tree { max-height: 60vh; overflow: auto; }
    .node { display: flex; align-items: center; gap: 6px; padding: 6px 8px; border-radius: 8px; cursor: pointer; }
    .node:hover { background: var(--a-panel-2); }
    .node.selected { background: color-mix(in srgb, var(--a-teal) 14%, transparent); outline: 1px solid color-mix(in srgb, var(--a-teal) 40%, transparent); }
    .node.depth0 .nm { font-weight: 700; }
    .nm { font-size: 13px; }
    .indent { white-space: pre; color: var(--a-dim); }
    .small { font-size: 10.5px; padding: 0 7px; }
    .acts { white-space: nowrap; }
    .acts button { margin-right: 6px; }
    .addstaff { margin-top: 16px; border-top: 1px solid var(--a-border); padding-top: 12px; }
    .grid4 { display: grid; grid-template-columns: repeat(5, 1fr) auto; gap: 10px; align-items: end; }
    label { display: grid; gap: 4px; font-size: var(--fs-sm); color: var(--a-dim); }
    .cell { padding-bottom: 1px; }
    .hint { color: var(--a-dim); font-size: var(--fs-xs); margin-top: 8px; }
    .err { color: var(--a-red); font-size: var(--fs-sm); margin: 6px 0; }
    .muted { color: var(--a-dim); }
    .pad { padding: 20px; }
    .outbox { margin-top: 14px; }
    .wrap { word-break: break-all; }
    .flash { font-size: var(--fs-sm); margin-top: 10px; padding: 8px 12px; border-radius: 8px; }
    .flash.ok { background: color-mix(in srgb, var(--a-green) 14%, transparent); color: var(--a-green); }
    .flash.bad { background: color-mix(in srgb, var(--a-red) 14%, transparent); color: var(--a-red); }
  `],
})
export class Users implements OnInit {
  private readonly http = inject(HttpClient);
  readonly auth = inject(AdminAuthService);

  readonly tree = signal<OrgNodeRow | null>(null);
  readonly loading = signal(false);
  readonly problem = signal('');
  readonly selectedId = signal<string | null>(null);
  readonly flash = signal<{ kind: string; text: string } | null>(null);

  readonly addingStaff = signal(false);
  readonly staffError = signal('');
  readonly busy = signal(false);
  staffDirty() { return !!(this.stName || this.stPhone || this.stEmail); }
  openAddStaff() {
    this.stName = this.stPhone = this.stUsername = this.stEmail = '';
    this.stOrgRole = 'STAFF';
    this.staffError.set('');
    this.addingStaff.set(true);
  }
  stName = '';
  stPhone = '';
  stUsername = '';
  stEmail = '';
  stOrgRole = 'STAFF';

  readonly selected = computed(() => {
    const id = this.selectedId();
    const root = this.tree();
    return id && root ? nodeById(root, id) : null;
  });

  /** Only callers bound to a non-platform org with ADMIN add staff via /me/users. */
  canAddStaff() {
    return this.auth.isOrgAdmin() && !this.auth.isPlatformStaff()
      && !!this.selected() && this.selected()!.id === this.auth.orgId();
  }

  readonly outbox = signal<OutboxRow[]>([]);
  readonly outboxError = signal('');

  async ngOnInit() { await this.reload(); }

  indent(d: number) { return d <= 0 ? '' : '  '.repeat(d - 1) + '└ '; }

  flatten(root: OrgNodeRow) {
    const out: (OrgNodeRow & { depth: number })[] = [];
    const walk = (n: OrgNodeRow, d: number) => {
      out.push({ ...n, depth: d });
      (n.children ?? []).forEach((c) => walk(c, d + 1));
    };
    walk(root, 0);
    return out;
  }

  pick(n: OrgNodeRow) { this.selectedId.set(n.id); }

  async reload() {
    if (this.flash()) this.flash.set(null);
    await Promise.all([this.loadTree(), this.loadOutbox()]);
  }

  private async loadTree() {
    this.loading.set(true);
    this.problem.set('');
    try {
      const t = await firstValueFrom(this.http.get<OrgNodeRow>('/api/orgs/me/tree'));
      this.tree.set(t);
    } catch (e) {
      this.problem.set(problemText(e));
    } finally {
      this.loading.set(false);
    }
  }

  private async loadOutbox() {
    this.outboxError.set('');
    if (this.auth.role() !== 'MINDLABS_ADMIN') return;
    try {
      this.outbox.set(await firstValueFrom(this.http.get<OutboxRow[]>('/api/admin/sim/outbox')));
    } catch (e) {
      this.outboxError.set(`Outbox unavailable: ${problemText(e)}`);
    }
  }

  async deactivate(u: OrgUserRow) {
    if (!confirm(`Deactivate ${u.name}? Sessions end immediately and STAFF/ADMIN access is revoked.`)) return;
    try {
      const msg = await firstValueFrom(this.http.post<MessageView>(
        `/api/orgs/users/${u.id}/deactivate`, {}));
      this.flash.set({ kind: 'ok', text: msg.message });
      await this.loadTree();
    } catch (e) {
      const p = readProblem(e);
      this.flash.set({ kind: 'bad', text: `Refused (${p.code ?? p.status}): ${p.detail}` });
    }
  }

  async resend(u: OrgUserRow) {
    try {
      const msg = await firstValueFrom(this.http.post<MessageView>(
        `/api/orgs/users/${u.id}/resend-activation`, {}));
      this.flash.set({ kind: 'ok', text: `${msg.message}` });
      await Promise.all([this.loadTree(), this.loadOutbox()]);
    } catch (e) {
      const p = readProblem(e);
      this.flash.set({ kind: 'bad', text: `Refused (${p.code ?? p.status}): ${p.detail}` });
    }
  }

  async addStaff() {
    const org = this.selected();
    if (!org) return;
    this.busy.set(true);
    this.staffError.set('');
    try {
      const view = await firstValueFrom(this.http.post<OrgStaffView>(
        '/api/orgs/me/users', {
          name: this.stName, phone: this.stPhone,
          username: null, email: this.stEmail,   // generated by the backend and emailed
          orgRole: this.stOrgRole, password: null,
        }));
      this.flash.set({ kind: 'ok', text: `Staff added — PENDING until activation (${view.status ?? 'queued outbox'}).` });
      this.stName = this.stPhone = this.stUsername = this.stEmail = '';
      this.addingStaff.set(false);
      await this.loadTree();
    } catch (e) {
      const p = readProblem(e);
      this.staffError.set(`Refused (${p.code ?? p.status}): ${p.detail}`);
    } finally {
      this.busy.set(false);
    }
  }
}

function nodeById(root: OrgNodeRow, id: string): OrgNodeRow | null {
  if (root.id === id) return root;
  for (const c of root.children ?? []) {
    const hit = nodeById(c, id);
    if (hit) return hit;
  }
  return null;
}
