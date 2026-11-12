import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import {
  RbacCellState, RbacResourceView, RbacRoleUpdate, RbacRoleView, RBAC_VERBS, RbacVerb, readProblem,
} from '../../core/api';

/**
 * Permission matrix (fapi2-design Appendix G, cut-over P5.5): the standard RBAC roles and
 * their grids, live from RBAC. Rows are the grid rows, columns the 10 fixed verbs.
 *
 * - Only the role's OWN cells are editable; what it inherits from junior roles (or ticks on
 *   all rows) is shown locked, so saving never flattens the hierarchy.
 * - A cell no endpoint uses doesn't exist and can't be ticked.
 * - RBAC applies the grid save rules (no submit + authorize on one row, no simulate in
 *   production, platform roles don't both administer access and authorize customers) and
 *   refuses with a reason.
 * - Seeded roles: an edit here lasts until the next reviewed change to grid.yaml, which is
 *   what compliance signs off. Org-defined roles are managed per org under Roles & access.
 */
@Component({
  selector: 'mpa-permission-matrix',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule],
  template: `
    <div class="head">
      <h2>Permission Matrix</h2>
      <div class="tabs" role="tablist">
        <button role="tab" [class.on]="view() === 'all'" (click)="view.set('all')">All roles</button>
        <button role="tab" [class.on]="view() === 'role'" (click)="view.set('role')">Edit one role</button>
      </div>
      <button class="ghost" (click)="reload()">Refresh</button>
      <input class="filter" name="q" [ngModel]="q()" (ngModelChange)="q.set($event)" placeholder="Filter rows (e.g. merchant.)" />
    </div>
    @if (problem(); as p) {<div class="err">{{ p }}</div>}

    @if (view() === 'all') {
      <div class="panel">
        <div class="legend small">
          <span><b class="v own">R</b> own</span>
          <span><b class="v inh">R</b> inherited</span>
          <span class="muted">R read · C create · M modify · D delete · S submit · A authorize · X execute · E export · Ad administer · Si simulate</span>
          <span class="muted">{{ rows().length }} rows × {{ roles().length }} roles · click a role to edit it</span>
        </div>
        <div class="overview-wrap">
          <table class="overview">
            <thead>
              <tr>
                <th class="resource-col">Row</th>
                @for (r of roles(); track r.name) {
                  <th class="rolehead" [title]="r.description" (click)="openRole(r.name)">
                    <span>{{ r.name }}</span><em>{{ r.kind }}</em>
                  </th>
                }
              </tr>
            </thead>
            <tbody>
              @for (row of rows(); track row.id) {
                <tr>
                  <td class="resource-cell" [title]="row.description">{{ row.id }}
                    @if (row.tenantTypes.length) {<span class="muted small">· {{ row.tenantTypes.join(', ') }}</span>}</td>
                  @for (r of roles(); track r.name) {
                    <td class="ov-cell">
                      @for (c of overviewCell(r, row); track c.v) {<b class="v {{ c.kind }}" [title]="row.id + ':' + c.v + ' (' + c.kind + ')'">{{ c.abbr }}</b>}
                    </td>
                  }
                </tr>
              }
            </tbody>
          </table>
        </div>
      </div>
    } @else {
    <div class="cols">
      <div class="panel roles">
        <div class="ptitle">Roles</div>
        @if (loading()) {<div class="muted">Loading…</div>}
        @for (r of roles(); track r.name) {
          <div class="role" [class.selected]="selectedName() === r.name" (click)="selectRole(r.name)"
               role="button" tabindex="0" (keydown.enter)="selectRole(r.name)">
            <span class="nm">{{ r.name }}</span>
            <span class="chip small">{{ r.kind }}</span>
            <span class="chip small">{{ r.permissions.length }}</span>
          </div>
        } @empty {
          @if (!loading()) {<div class="muted small">No roles found.</div>}
        }
      </div>
      <div class="panel matrix">
        @if (selected(); as r) {
          <div class="ptitle">{{ r.name }}
            <span class="muted small">{{ r.description }}</span>
          </div>
          <div class="meta small">
            <span>inherits: {{ r.inherits.length ? r.inherits.join(', ') : 'nothing' }}</span>
            <span>granting needs: {{ r.approval }}</span>
            @if (r.seeded) {<span class="chip amber">seeded · edits last until the next reviewed grid.yaml change</span>}
          </div>
          <div class="legend small">
            <span><i class="sw own"></i> own (editable)</span>
            <span><i class="sw inherited"></i> inherited (change it on the junior role)</span>
            <span><i class="sw na"></i> no endpoint uses this cell</span>
          </div>
          <div class="matrix-wrap">
            <table class="matrix-table">
              <thead>
                <tr>
                  <th class="resource-col">Row</th>
                  @for (v of verbs; track v) {<th class="verb-col" [title]="v">{{ v.slice(0, 4) }}</th>}
                </tr>
              </thead>
              <tbody>
                @for (row of rows(); track row.id) {
                  <tr>
                    <td class="resource-cell" [title]="row.description">{{ row.id }}
                      @if (row.tenantTypes.length) {<span class="muted small">· {{ row.tenantTypes.join(', ') }}</span>}</td>
                    @for (v of verbs; track v) {
                      @let st = state(row, v);
                      <td class="verb-cell">
                        <input type="checkbox" [attr.aria-label]="row.id + ':' + v"
                               [class]="st" [class.dirty]="isDirty(row.id, v)"
                               [checked]="st === 'own' || st === 'inherited'"
                               [disabled]="st === 'na' || st === 'inherited' || busy()"
                               (change)="toggle(row.id, v)" />
                      </td>
                    }
                  </tr>
                }
              </tbody>
            </table>
          </div>
          @if (saveError(); as e) {<div class="err">{{ e }}</div>}
          @if (saved()) {<div class="ok small">Saved.</div>}
          @if (dirtyCount()) {
            <div class="actions">
              <span class="muted small">{{ dirtyCount() }} change(s)</span>
              <button class="ghost" (click)="discard()" [disabled]="busy()">Discard</button>
              <button class="primary" (click)="save()" [disabled]="busy()">Save {{ r.name }}</button>
            </div>
          }
        } @else {
          <div class="muted pad">Pick a role on the left to see and edit its permissions.</div>
        }
      </div>
    </div>
    }
  `,
  styles: [`
    .head { display: flex; align-items: center; gap: 14px; margin-bottom: 14px; flex-wrap: wrap; }
    h2 { margin: 0; font-size: 20px; line-height: 1.1; }
    .filter { max-width: 260px; }
    .cols { display: grid; grid-template-columns: 300px 1fr; gap: 14px; align-items: start; }
    @media (max-width: 900px) { .cols { grid-template-columns: 1fr; } }
    .panel { padding: 12px; border: 1px solid var(--a-border); border-radius: 8px; min-width: 0; }
    .ptitle { font-size: 13px; font-weight: 600; margin-bottom: 8px; display: flex; gap: 8px; align-items: baseline; flex-wrap: wrap; }
    .roles { position: sticky; top: 8px; max-height: calc(100vh - 140px); overflow: auto; }
    .role { display: flex; align-items: center; gap: 6px; padding: 7px 10px; border-radius: 6px; cursor: pointer; border: 1px solid transparent; }
    .role:hover { background: var(--a-panel-2); }
    .role.selected { background: color-mix(in srgb, var(--a-teal) 12%, transparent); border-color: var(--a-teal); }
    .nm { font-size: 13px; font-weight: 600; flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .role .chip { flex: none; }
    .tabs { display: inline-flex; border: 1px solid var(--a-border); border-radius: 999px; overflow: hidden; }
    .tabs button { border: 0; border-radius: 0; background: transparent; padding: 6px 14px; color: var(--a-dim); }
    .tabs button.on { background: var(--a-teal); color: var(--a-accent-ink); font-weight: 700; }
    .overview-wrap { overflow-x: auto; }
    .overview { border-collapse: collapse; font-size: 11px; }
    .overview th, .overview td { padding: 3px 5px; border-bottom: 1px solid var(--a-border); vertical-align: middle; }
    .overview thead th { position: sticky; top: 0; background: var(--a-bg, #0b0b0b); z-index: 1; }
    .rolehead { cursor: pointer; text-align: left; min-width: 74px; max-width: 110px; font-weight: 600; }
    .rolehead span { display: block; white-space: normal; word-break: break-word; line-height: 1.15; }
    .rolehead em { display: block; font-style: normal; font-weight: 400; color: var(--a-dim); font-size: 10px; }
    .rolehead:hover span { color: var(--a-teal); text-decoration: underline; }
    .ov-cell { white-space: nowrap; }
    .v { display: inline-block; min-width: 13px; text-align: center; margin-right: 2px; padding: 0 2px; border-radius: 3px; font-size: 10px; }
    .v.own { background: var(--a-teal); color: var(--a-accent-ink); }
    .v.inh { background: color-mix(in srgb, var(--a-teal) 25%, transparent); color: var(--a-dim); font-weight: 500; }
    .meta, .legend { display: flex; gap: 14px; flex-wrap: wrap; color: var(--a-dim); margin-bottom: 8px; align-items: center; }
    .sw { display: inline-block; width: 10px; height: 10px; border-radius: 2px; margin-right: 4px; vertical-align: middle; }
    .sw.own { background: var(--a-teal); }
    .sw.inherited { background: color-mix(in srgb, var(--a-teal) 40%, transparent); }
    .sw.na { background: var(--a-border); }
    .matrix-wrap { overflow-x: auto; }
    .matrix-table thead th { position: sticky; top: 0; background: var(--a-bg, #0b0b0b); z-index: 1; }
    .matrix-table { border-collapse: collapse; font-size: 12px; }
    .matrix-table th, .matrix-table td { padding: 3px 6px; border-bottom: 1px solid var(--a-border); }
    .verb-col { font-weight: 500; color: var(--a-dim); text-align: center; }
    .verb-cell { text-align: center; }
    .verb-cell input { width: auto; margin: 0; }
    .verb-cell input.na { opacity: .15; }
    .verb-cell input.inherited { opacity: .55; }
    .verb-cell input.dirty { outline: 2px solid var(--a-amber, orange); outline-offset: 1px; }
    .resource-cell { font-family: var(--font-mono, monospace); white-space: nowrap; }
    .actions { display: flex; gap: 8px; align-items: center; justify-content: flex-end; margin-top: 10px; }
    .err { color: var(--a-red); font-size: var(--fs-sm); margin: 6px 0; }
    .ok { color: var(--a-green); margin-top: 6px; }
    .small { font-size: var(--fs-xs); }
    .pad { padding: 16px; }
  `],
})
export class PermissionMatrix implements OnInit {
  private readonly http = inject(HttpClient);
  readonly verbs: RbacVerb[] = RBAC_VERBS;

  readonly roles = signal<RbacRoleView[]>([]);
  readonly resources = signal<RbacResourceView[]>([]);
  readonly selectedName = signal<string | null>(null);
  /** The selected role's own cells as edited; compared with the saved own cells. */
  readonly work = signal<Set<string>>(new Set());
  readonly q = signal('');
  /** 'all': every row × every role, read-only · 'role': edit one role's own cells. */
  readonly view = signal<'all' | 'role'>('all');
  private static readonly ABBR: Record<string, string> = {
    read: 'R', create: 'C', modify: 'M', delete: 'D', submit: 'S',
    authorize: 'A', execute: 'X', export: 'E', administer: 'Ad', simulate: 'Si',
  };
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly problem = signal('');
  readonly saveError = signal('');
  readonly saved = signal(false);

  readonly selected = computed(() => this.roles().find((r) => r.name === this.selectedName()) ?? null);
  readonly rows = computed(() => {
    const q = this.q().trim().toLowerCase();
    return this.resources().filter((r) => r.used.length && (!q || r.id.toLowerCase().includes(q)));
  });
  private readonly ownSaved = computed(() => new Set(this.selected()?.own ?? []));
  private readonly effective = computed(() => new Set(this.selected()?.permissions ?? []));
  readonly dirtyCount = computed(() => {
    const a = this.ownSaved(), b = this.work();
    let n = 0;
    for (const c of a) if (!b.has(c)) n++;
    for (const c of b) if (!a.has(c)) n++;
    return n;
  });

  async ngOnInit() { await this.reload(); }

  /** The verbs a role holds on a row, in grid order: own or inherited. */
  overviewCell(r: RbacRoleView, row: RbacResourceView): { v: string; abbr: string; kind: 'own' | 'inh' }[] {
    const own = new Set(r.own), eff = new Set(r.permissions);
    const out: { v: string; abbr: string; kind: 'own' | 'inh' }[] = [];
    for (const v of this.verbs) {
      if (!row.used.includes(v)) continue;
      const c = `${row.id}:${v}`;
      if (own.has(c)) out.push({ v, abbr: PermissionMatrix.ABBR[v], kind: 'own' });
      else if (eff.has(c)) out.push({ v, abbr: PermissionMatrix.ABBR[v], kind: 'inh' });
    }
    return out;
  }

  openRole(name: string) {
    this.view.set('role');
    this.selectRole(name);
  }

  async reload() {
    this.loading.set(true);
    this.problem.set('');
    try {
      const [roles, resources] = await Promise.all([
        firstValueFrom(this.http.get<RbacRoleView[]>('/api/admin/rbac/roles')),
        firstValueFrom(this.http.get<RbacResourceView[]>('/api/admin/rbac/resources')),
      ]);
      this.roles.set(roles);
      this.resources.set(resources);
      const keep = this.selectedName();
      if (keep && roles.some((r) => r.name === keep)) this.work.set(new Set(roles.find((r) => r.name === keep)!.own));
    } catch (e) {
      this.problem.set(readProblem(e).detail || 'Failed to load roles from RBAC');
    } finally {
      this.loading.set(false);
    }
  }

  selectRole(name: string) {
    if (this.selectedName() === name) return;
    this.selectedName.set(name);
    this.saveError.set('');
    this.saved.set(false);
    this.work.set(new Set(this.selected()?.own ?? []));
  }

  state(row: RbacResourceView, v: RbacVerb): RbacCellState {
    const cell = `${row.id}:${v}`;
    if (!row.used.includes(v)) return 'na';
    if (this.work().has(cell)) return 'own';
    // effective minus own (as saved) = inherited from a junior role or an all-rows tick
    if (this.effective().has(cell) && !this.ownSaved().has(cell)) return 'inherited';
    return 'off';
  }

  isDirty(row: string, v: RbacVerb) {
    const c = `${row}:${v}`;
    return this.ownSaved().has(c) !== this.work().has(c);
  }

  toggle(row: string, v: RbacVerb) {
    const c = `${row}:${v}`;
    const w = new Set(this.work());
    if (w.has(c)) w.delete(c); else w.add(c);
    this.work.set(w);
    this.saved.set(false);
  }

  discard() {
    this.work.set(new Set(this.ownSaved()));
    this.saveError.set('');
  }

  async save() {
    const r = this.selected();
    if (!r) return;
    this.busy.set(true);
    this.saveError.set('');
    try {
      const update: RbacRoleUpdate = { permissions: [...this.work()] };
      const updated = await firstValueFrom(this.http.put<RbacRoleView>(`/api/admin/rbac/roles/${encodeURIComponent(r.name)}`, update));
      // the hierarchy may pass the change on to senior roles: reload them all
      await this.reload();
      this.work.set(new Set(updated.own));
      this.saved.set(true);
    } catch (e) {
      const p = readProblem(e);
      this.saveError.set(`Refused (${p.code ?? p.status}): ${p.detail}`);
    } finally {
      this.busy.set(false);
    }
  }
}
