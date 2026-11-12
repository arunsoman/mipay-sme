import { Modal } from '../../../../../shared/ui/modal';
import { RoleCeiling } from '../../../../../shared/rbac/role-ceiling';
import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { PortalAuthService } from '../../core/auth';
import {
  FlatNode, OrgNodeRow, OrgRightsView, SuspensionRow, flatten, nodeById, refusal, splitList, statusClass,
} from '../../core/api';

const HINTS: Record<string, string> = {
  ANCESTOR_REQUIRED: 'only an organization above it may do this',
  RIGHTS_NARROW_ONLY: 'rights can only be narrowed',
  ORG_SUSPENDED: 'your organization (or one above it) is suspended',
};

/**
 * "My network": the caller's whole subtree down to the leaves, with derived
 * status. For any organization BELOW the caller's own: suspend it (which cuts
 * off its entire sub-network), lift holds this org placed, and narrow its
 * onboarding rights. The caller's own org is shown read-only.
 */
@Component({
  selector: 'mpo-network',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Modal, FormsModule, DatePipe, RoleCeiling],
  template: `
    <div class="head">
      <h2>My network</h2>
      @if (tree(); as t) {
        <span class="muted small">{{ t.subtree?.descendants ?? 0 }} organizations below you ·
          {{ t.subtree?.activeDescendants ?? 0 }} active</span>
      }
      <button class="ghost" (click)="load()">Refresh</button>
    </div>
    @if (flash(); as f) {<div class="flash {{ f.kind }}">{{ f.text }}</div>}
    @if (problem(); as p) {<div class="err">{{ p }}</div>}

    <div class="cols">
      <div class="panel tree">
        <input class="filter" name="q" [(ngModel)]="q" placeholder="Filter by name or type" />
        @for (n of visible(); track n.id) {
          <div class="node" [class.selected]="n.id === selectedId()" (click)="pick(n.id)"
               role="button" tabindex="0" (keydown.enter)="pick(n.id)">
            <span class="indent" [style.width.px]="n.depth * 14"></span>
            <span class="nm" [class.me]="n.depth === 0">{{ n.name }}</span>
            <span class="chip">{{ n.type }}</span>
            <span class="chip {{ cls(n.effectiveStatus) }}">{{ n.effectiveStatus }}</span>
          </div>
        } @empty {
          <div class="muted">{{ loading() ? 'Loading…' : 'Nothing matches.' }}</div>
        }
      </div>

      <div class="panel details">
        @if (selected(); as o) {
          <div class="ptitle">{{ o.name }} <span class="chip">{{ o.type }}</span>
            <span class="chip {{ cls(o.effectiveStatus) }}">{{ o.effectiveStatus }}</span></div>
          @if (path().length > 1) {
            <div class="path muted small">{{ path().join(' › ') }}</div>
          }

          <div class="stats">
            <div><b>{{ o.userCount }}</b><span>users</span></div>
            <div><b>{{ o.subtree?.descendants ?? 0 }}</b><span>orgs below</span></div>
            <div><b>{{ o.subtree?.users ?? 0 }}</b><span>users incl. sub-network</span></div>
            <div><b>{{ o.onboardedAt ? (o.onboardedAt | date:'MMM d, y') : '—' }}</b><span>onboarded</span></div>
          </div>

          @if (isOwn()) {
            <div class="muted small">This is your own organization. Suspension and rights for it are managed by the organization above you.</div>
          }

          <!-- holds -->
          <div class="block">
            <div class="btitle">Suspension</div>
            @for (h of activeHolds(); track h.id) {
              <div class="hold">
                <span class="chip bad">HOLD</span>
                <span>{{ h.reason }}</span>
                <span class="muted small">since {{ h.createdAt | date:'MMM d, HH:mm' }}</span>
                @if (!isOwn()) {<button class="ghost" (click)="lift(h.id)" [disabled]="busy()">Lift</button>}
              </div>
            } @empty {
              <div class="muted small">{{ o.effectiveStatus === 'SUSPENDED_BY_ANCESTOR'
                ? 'Suspended because an organization above it is suspended.' : 'No holds on this organization.' }}</div>
            }
            @if (!isOwn() && o.lifecycle === 'ACTIVE') {
              <div class="row"><button class="danger" (click)="openSuspend()">Suspend…</button></div>
              <mp-modal [open]="suspending()" size="narrow" eyebrow="Suspend" [title]="o.name"
                        [dirty]="!!reason.trim()" (closed)="suspending.set(false)">
                <div class="warnbox">Suspending {{ o.name }} also blocks the {{ o.subtree?.descendants ?? 0 }} organizations
                  below it and all {{ o.subtree?.users ?? o.userCount }} users in its sub-network, and signs them out immediately.</div>
                <label class="fl">Reason for suspension *
                  <input name="reason" [(ngModel)]="reason" placeholder="e.g. compliance hold pending review" /></label>
                @if (modalError(); as e) {<div class="err">{{ e }}</div>}
                <div modal-footer>
                  <button class="ghost" type="button" (click)="suspending.set(false)">Cancel</button>
                  <button class="danger" (click)="suspend(o)" [disabled]="busy() || !reason.trim()">Suspend {{ o.name }}</button>
                </div>
              </mp-modal>
            }
          </div>

          <!-- role ceiling (extended-rbac): editable for organizations below the caller's own -->
          <mp-role-ceiling [orgId]="o.id" [editable]="!isOwn()" />

          <!-- rights -->
          @if (o.type !== 'MERCHANT' && o.type !== 'LENDER') {
            <div class="block">
              <div class="btitle">Onboarding rights</div>
              @if (rights(); as r) {
                <table class="grid">
                  <thead><tr><th>Type</th><th>Directly</th><th>Anywhere below it</th><th>In effect now</th></tr></thead>
                  <tbody>
                    @for (t of rightTypes(); track t) {
                      <tr>
                        <td>{{ t }}</td>
                        <td><input type="checkbox" [checked]="savedHas('direct', t)" disabled /></td>
                        <td><input type="checkbox" [checked]="savedHas('ceiling', t)" disabled /></td>
                        <td>{{ splitList(r.effDirect).includes(t) ? 'can onboard' : '—' }}</td>
                      </tr>
                    } @empty {
                      <tr><td colspan="4" class="muted">No onboarding rights.</td></tr>
                    }
                  </tbody>
                </table>
                @if (!isOwn() && rightTypes().length) {
                  <div class="row"><button class="primary" (click)="openRights()">Edit rights</button></div>
                  <mp-modal [open]="editingRights()" eyebrow="Onboarding rights" [title]="o.name"
                            subtitle="Untick to take a right away; it applies to the whole sub-network at once. Rights cannot be added back here."
                            [dirty]="dirty()" discardText="Discard the narrowing? Nothing has been saved."
                            (closed)="closeRights()">
                    <table class="grid">
                      <thead><tr><th>Type</th><th>Directly</th><th>Anywhere below it</th><th>In effect now</th></tr></thead>
                      <tbody>
                        @for (t of rightTypes(); track t) {
                          <tr>
                            <td>{{ t }}</td>
                            <td><input type="checkbox" [checked]="wDirect().has(t)" [disabled]="!wDirect().has(t)"
                                       (change)="drop('direct', t)" /></td>
                            <td><input type="checkbox" [checked]="wCeiling().has(t)" [disabled]="!wCeiling().has(t)"
                                       (change)="drop('ceiling', t)" /></td>
                            <td>{{ splitList(r.effDirect).includes(t) ? 'can onboard' : '—' }}</td>
                          </tr>
                        } @empty {
                          <tr><td colspan="4" class="muted">No onboarding rights.</td></tr>
                        }
                      </tbody>
                    </table>
                    @if (modalError(); as e) {<div class="err">{{ e }}</div>}
                    <div modal-footer>
                      <button class="ghost" type="button" (click)="closeRights()">Cancel</button>
                      <button class="primary" (click)="saveRights()" [disabled]="busy() || !dirty()">Save narrowing</button>
                    </div>
                  </mp-modal>
                }
              } @else {
                <div class="muted small">Loading…</div>
              }
            </div>
          }
        } @else {
          <div class="muted">Pick an organization.</div>
        }
      </div>
    </div>
  `,
  styles: [`
    .warnbox { font-size: var(--fs-sm); padding: 10px 12px; border-radius: 10px; color: var(--a-red);
               background: color-mix(in srgb, var(--a-red) 10%, transparent); }
    .fl { display: grid; gap: 5px; font-size: var(--fs-sm); color: var(--a-dim); }
    .head { display: flex; align-items: center; gap: 14px; margin-bottom: 14px; flex-wrap: wrap; }
    h2 { margin: 0; font-size: 30px; line-height: 1.1; }
    .head button { margin-left: auto; }
    .cols { display: grid; grid-template-columns: minmax(280px, 380px) 1fr; gap: 14px; align-items: start; }
    @media (max-width: 800px) { .cols { grid-template-columns: 1fr; } }
    .panel { padding: 12px; }
    .tree { max-height: 70vh; overflow: auto; }
    .filter { margin-bottom: 8px; }
    .node { display: flex; align-items: center; gap: 6px; padding: 6px 8px; border-radius: 8px; cursor: pointer; }
    .node:hover { background: var(--a-panel-2); }
    .node.selected { background: color-mix(in srgb, var(--a-teal) 14%, transparent); }
    .indent { flex: none; border-bottom: 1px dotted var(--a-border); align-self: center; }
    .nm { font-size: 13px; flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .nm.me { font-weight: 700; }
    .details { display: grid; gap: 14px; }
    .ptitle { font-size: 15px; font-weight: 600; display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(110px, 1fr)); gap: 8px; }
    .stats div { background: var(--a-panel-2); border-radius: 8px; padding: 8px 10px; display: grid; }
    .stats span { color: var(--a-dim); font-size: var(--fs-xs); }
    .block { border: 1px solid var(--a-border); border-radius: 8px; padding: 12px; display: grid; gap: 8px; }
    .btitle { font-weight: 600; font-size: 13px; }
    .hold { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .suspend { display: flex; gap: 8px; }
    .grid input { width: auto; }
    .row { display: flex; gap: 8px; }
    .hint { color: var(--a-dim); font-size: var(--fs-xs); }
    .err { color: var(--a-red); font-size: var(--fs-sm); margin: 6px 0; }
    .flash { font-size: var(--fs-sm); margin: 0 0 12px; padding: 8px 12px; border-radius: 8px; }
    .flash.ok { background: color-mix(in srgb, var(--a-green) 14%, transparent); color: var(--a-green); }
    .flash.bad { background: color-mix(in srgb, var(--a-red) 14%, transparent); color: var(--a-red); }
  `],
})
export class Network implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(PortalAuthService);
  readonly cls = statusClass;
  readonly splitList = splitList;

  readonly tree = signal<OrgNodeRow | null>(null);
  readonly loading = signal(false);
  readonly problem = signal('');
  readonly selectedId = signal<string | null>(null);
  readonly busy = signal(false);
  readonly flash = signal<{ kind: 'ok' | 'bad'; text: string } | null>(null);
  q = '';
  reason = '';

  readonly flat = computed<FlatNode[]>(() => (this.tree() ? flatten(this.tree()!) : []));
  readonly selected = computed(() => {
    const t = this.tree(); const id = this.selectedId();
    return t && id ? nodeById(t, id) : null;
  });
  readonly isOwn = computed(() => this.selected()?.id === this.auth.orgId());
  readonly activeHolds = computed(() => (this.selected()?.holds ?? []).filter((h) => !h.releasedAt));
  readonly path = computed(() => {
    const id = this.selectedId(); const t = this.tree();
    if (!id || !t) return [];
    const trail: string[] = [];
    const walk = (n: OrgNodeRow): boolean => {
      trail.push(n.name);
      if (n.id === id) return true;
      for (const c of n.children ?? []) if (walk(c)) return true;
      trail.pop();
      return false;
    };
    walk(t);
    return trail;
  });

  readonly rights = signal<OrgRightsView | null>(null);
  readonly wDirect = signal<Set<string>>(new Set());
  readonly wCeiling = signal<Set<string>>(new Set());
  readonly rightTypes = computed(() => splitList(this.rights()?.ceiling));
  readonly dirty = computed(() => {
    const r = this.rights();
    return !!r && (splitList(r.direct).length !== this.wDirect().size
      || splitList(r.ceiling).length !== this.wCeiling().size);
  });

  visible(): FlatNode[] {
    const q = this.q.trim().toLowerCase();
    return q ? this.flat().filter((n) => n.name.toLowerCase().includes(q) || n.type.toLowerCase().includes(q))
      : this.flat();
  }

  async ngOnInit() { await this.load(); }

  async load() {
    this.loading.set(true);
    this.problem.set('');
    try {
      const t = await firstValueFrom(this.http.get<OrgNodeRow>('/api/orgs/me/tree'));
      this.tree.set(t);
      const id = this.selectedId() && nodeById(t, this.selectedId()!) ? this.selectedId()! : t.id;
      this.pick(id);
    } catch (e) {
      this.problem.set(refusal(e, HINTS));
    } finally {
      this.loading.set(false);
    }
  }

  pick(id: string) {
    const changed = id !== this.selectedId();
    this.selectedId.set(id);
    if (changed) this.reason = '';
    void this.loadRights(id);
  }

  private async loadRights(id: string) {
    const n = this.tree() ? nodeById(this.tree()!, id) : null;
    if (!n || n.type === 'MERCHANT' || n.type === 'LENDER') { this.rights.set(null); return; }
    try {
      const r = await firstValueFrom(this.http.get<OrgRightsView>(`/api/orgs/${id}/rights`));
      if (this.selectedId() !== id) return;
      this.rights.set(r);
      this.resetRights();
    } catch (e) {
      this.rights.set(null);
      this.flash.set({ kind: 'bad', text: refusal(e, HINTS) });
    }
  }

  drop(kind: 'direct' | 'ceiling', t: string) {
    const d = new Set(this.wDirect()); const c = new Set(this.wCeiling());
    d.delete(t);
    if (kind === 'ceiling') c.delete(t);
    this.wDirect.set(d); this.wCeiling.set(c);
  }

  readonly suspending = signal(false);
  readonly editingRights = signal(false);
  readonly modalError = signal('');

  savedHas(kind: 'direct' | 'ceiling', t: string) {
    return splitList(kind === 'direct' ? this.rights()?.direct : this.rights()?.ceiling).includes(t);
  }

  openSuspend() { this.reason = ''; this.modalError.set(''); this.suspending.set(true); }
  openRights() { this.resetRights(); this.modalError.set(''); this.editingRights.set(true); }
  closeRights() { this.resetRights(); this.editingRights.set(false); }

  resetRights() {
    const r = this.rights();
    this.wDirect.set(new Set(splitList(r?.direct)));
    this.wCeiling.set(new Set(splitList(r?.ceiling)));
  }

  async saveRights() {
    const id = this.selectedId();
    if (!id) return;
    this.busy.set(true);
    try {
      const r = await firstValueFrom(this.http.put<OrgRightsView>(`/api/orgs/${id}/rights`,
        { rightsDirect: [...this.wDirect()], rightsCeiling: [...this.wCeiling()] }));
      this.rights.set(r);
      this.resetRights();
      this.editingRights.set(false);
      this.flash.set({ kind: 'ok', text: 'Rights narrowed for this organization and everything below it.' });
    } catch (e) {
      this.modalError.set(refusal(e, HINTS));
    } finally {
      this.busy.set(false);
    }
  }

  async suspend(o: OrgNodeRow) {
    this.busy.set(true);
    try {
      await firstValueFrom(this.http.post<SuspensionRow>(`/api/orgs/${o.id}/suspend`, { reason: this.reason.trim() }));
      this.flash.set({ kind: 'ok', text: `${o.name} suspended with its sub-network.` });
      this.reason = '';
      this.suspending.set(false);
      await this.load();
    } catch (e) {
      this.modalError.set(refusal(e, HINTS));
    } finally {
      this.busy.set(false);
    }
  }

  async lift(holdId: string) {
    this.busy.set(true);
    try {
      await firstValueFrom(this.http.post<SuspensionRow[]>(`/api/orgs/suspensions/${holdId}/lift`, {}));
      this.flash.set({ kind: 'ok', text: 'Hold lifted. Other holds, if any, stay in force.' });
      await this.load();
    } catch (e) {
      this.flash.set({ kind: 'bad', text: refusal(e, HINTS) });
    } finally {
      this.busy.set(false);
    }
  }
}
