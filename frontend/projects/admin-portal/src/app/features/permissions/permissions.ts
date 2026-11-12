import { MpPerm } from '../../../../../shared/web-core/perm-directive';
import { Modal } from '../../../../../shared/ui/modal';
import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { AdminAuthService } from '../../core/auth';
import { GrantableView, OrgNodeRow, OrgRightsView, readProblem } from '../../core/api';
import { problemText } from '../onboarding/onboarding';

/**
 * Delegated rights console ([D1/D2/H7]): view an org's explicit DIRECT and
 * CEILING rows plus the derived effective sets, and narrow them. Narrowing is
 * the only write ([D1]) - checkboxes only ever REMOVE a type and the backend
 * refuses additions with RIGHTS_NARROW_ONLY. The onboarder's own grantable
 * view (what child types may be proposed) is shown for the caller's org.
 */
@Component({
  selector: 'mpa-permissions',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, Modal, MpPerm],
  template: `
    <div class="head">
      <h2>Onboarding rights</h2>
      <button class="ghost" (click)="reload()">Refresh</button>
    </div>

    <div class="cols">
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
              <span class="ty">{{ word(n.type) }}</span>
            </div>
          }
        }
      </div>

      <div class="panel details">
        @if (selected(); as org) {
          <div class="dhead">
            <div><h3>{{ org.name }}</h3><div class="muted small">{{ word(org.type) }} · what it may onboard</div></div>
            <button *mpPerm="'org.rights:administer'" class="primary" (click)="openEdit()" [disabled]="!rights()">Edit rights</button>
          </div>

          @if (rightsError(); as e) {<div class="err">{{ e }}</div>}
          @if (rights(); as r) {
            <div class="cards">
              <div class="card">
                <div class="ctitle">Onboards directly</div>
                <div class="tags">@for (t of list(r.effDirect); track t) {<span class="tag">{{ word(t) }}</span>} @empty {<span class="muted">Nothing</span>}</div>
              </div>
              <div class="card">
                <div class="ctitle">Anywhere below it</div>
                <div class="tags">@for (t of list(r.effCeiling); track t) {<span class="tag">{{ word(t) }}</span>} @empty {<span class="muted">Nothing</span>}</div>
              </div>
            </div>
            @if (!editing() && flash(); as f) {<div class="flash {{ f.kind }}">{{ f.text }}</div>}
            <div class="hint">You can only narrow these here. Wider rights are given by the organization above it, when it is onboarded.</div>

            <mp-modal [open]="editing()" eyebrow="Onboarding rights" [title]="'Rights of ' + org.name"
                      subtitle="Untick to narrow. Rights can't be widened here."
                      [dirty]="dirty()" discardText="Discard the narrowing? Nothing has been saved."
                      (closed)="closeEdit()">
            <div class="blocks">
                <div class="block">
                  <div class="btitle">Onboards directly <span class="muted small">— types it may onboard itself</span></div>
                  <div class="opts">
                    @for (t of allTypes; track t) {
                      <label class="opt" [class.locked]="!holds('direct', t)">
                        <input type="checkbox" name="dir_{{t}}"
                               [ngModel]="holds('direct', t) ? true : false"
                               [disabled]="!holds('direct', t) || busy()"
                               (ngModelChange)="toggle('direct', t, $event)" />
                        <span>{{ word(t) }}</span>
                      </label>
                    }
                  </div>

                </div>

                <div class="block">
                  <div class="btitle">Anywhere below it <span class="muted small">— the most its whole network may onboard</span></div>
                  <div class="opts">
                    @for (t of allTypes; track t) {
                      <label class="opt" [class.locked]="!holds('ceiling', t)">
                        <input type="checkbox" name="ceil_{{t}}"
                               [ngModel]="holds('ceiling', t) ? true : false"
                               [disabled]="!holds('ceiling', t) || busy()"
                               (ngModelChange)="toggle('ceiling', t, $event)" />
                        <span>{{ word(t) }}</span>
                      </label>
                    }
                  </div>

                </div>

              </div>
              @if (flash(); as f) {<div class="flash {{ f.kind }}">{{ f.text }}</div>}
              <div modal-footer>
                <button class="ghost" type="button" (click)="closeEdit()">Cancel</button>
                <button class="primary" [disabled]="busy() || !dirty()" (click)="save()">Save</button>
              </div>
            </mp-modal>
          }

          @if (grantable(); as g) {
            <div class="grantable">
              <div class="btitle">What your organization may onboard</div>
              <table class="grid">
                <thead><tr><th>Type</th><th>It may let them onboard</th><th>Ticked by default</th></tr></thead>
                <tbody>
                  @for (c of g.children; track c.type) {
                    <tr>
                      <td>{{ word(c.type) }}</td>
                      <td class="small">{{ words(c.allowedCeiling) }}</td>
                      <td class="small">{{ words(c.defaultDirect) }}</td>
                    </tr>
                  } @empty {
                    <tr><td colspan="3" class="muted">Your organization may not onboard anything.</td></tr>
                  }
                </tbody>
              </table>
            </div>
          }
        } @else {
          <div class="muted pad">Pick an organization on the left.</div>
        }
      </div>
    </div>
  `,
  styles: [`
    .dhead { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
    .dhead h3 { margin: 0; font-size: 19px; color: var(--a-text); }
    .cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 12px; }
    .card { border: 1px solid #222; background: #0f0f0f; border-radius: 12px; padding: 14px 16px; display: grid; gap: 10px; }
    .ctitle { font-size: 12px; letter-spacing: .05em; text-transform: uppercase; color: var(--a-dim); font-weight: 700; }
    .tags { display: flex; flex-wrap: wrap; gap: 6px; }
    .tag { padding: 4px 11px; border-radius: 999px; font-size: 13px; background: color-mix(in srgb, var(--a-teal) 14%, transparent); color: var(--a-teal); }
    .ty { margin-left: auto; font-size: 11.5px; color: var(--a-dim); }
    .summary { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 6px 18px; margin: 6px 0 12px; }
    .head { display: flex; align-items: center; gap: 10px 14px; margin-bottom: 14px; flex-wrap: wrap; }
    .grantable table td { vertical-align: middle; }
    .grantable { overflow-x: auto; }
    @media (max-width: 760px) { .cols { grid-template-columns: minmax(0, 1fr) !important; } .dhead { flex-wrap: wrap; } }
    h2 { margin: 0; font-size: 30px; line-height: 1.1; }
    .cols { display: grid; grid-template-columns: 360px 1fr; gap: 14px; align-items: start; }
    .panel { padding: 12px; }
    .ptitle { font-size: 13.5px; font-weight: 600; margin-bottom: 10px; display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .tree { max-height: 60vh; overflow: auto; }
    .node { display: flex; align-items: center; gap: 6px; padding: 6px 8px; border-radius: 8px; cursor: pointer; }
    .node:hover { background: var(--a-panel-2); }
    .node.selected { background: color-mix(in srgb, var(--a-teal) 14%, transparent); outline: 1px solid color-mix(in srgb, var(--a-teal) 40%, transparent); }
    .node.depth0 .nm { font-weight: 700; }
    .nm { font-size: 13px; }
    .indent { white-space: pre; color: var(--a-dim); }
    .small { font-size: 10.5px; padding: 0 7px; }
    .blocks { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
    .block { border: 1px solid var(--a-border); border-radius: 8px; padding: 12px; }
    .derived { grid-column: 1 / -1; }
    .btitle { font-size: 13px; font-weight: 600; margin-bottom: 8px; }
    .opts { display: flex; flex-wrap: wrap; gap: 6px 14px; margin-bottom: 8px; }
    .opt { display: inline-flex; gap: 6px; align-items: center; font-size: var(--fs-sm);
           border: 1px solid var(--a-border); padding: 4px 10px; border-radius: 999px; }
    .opt input { width: auto; }
    .opt.locked { opacity: .45; }
    .kv { display: flex; justify-content: space-between; gap: 10px; padding: 4px 0; font-size: var(--fs-sm); }
    .kv span:first-child { color: var(--a-dim); }
    .row { display: flex; gap: 8px; margin-top: 14px; }
    .hint { color: var(--a-dim); font-size: var(--fs-xs); margin-top: 10px; }
    .grantable { margin-top: 16px; border-top: 1px solid var(--a-border); padding-top: 12px; }
    .err { color: var(--a-red); font-size: var(--fs-sm); margin: 6px 0; }
    .muted { color: var(--a-dim); }
    .pad { padding: 20px; }
    .flash { font-size: var(--fs-sm); margin-top: 10px; padding: 8px 12px; border-radius: 8px; }
    .flash.ok { background: color-mix(in srgb, var(--a-green) 14%, transparent); color: var(--a-green); }
    .flash.bad { background: color-mix(in srgb, var(--a-red) 14%, transparent); color: var(--a-red); }
  `],
})
export class Permissions implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AdminAuthService);

  readonly allTypes = ['MANUFACTURER', 'DISTRIBUTOR', 'SUPPLIER', 'MERCHANT', 'LENDER'] as const;

  readonly tree = signal<OrgNodeRow | null>(null);
  readonly loading = signal(false);
  readonly problem = signal('');
  readonly selectedId = signal<string | null>(null);

  readonly rights = signal<OrgRightsView | null>(null);
  readonly rightsError = signal('');
  readonly busy = signal(false);
  readonly flash = signal<{ kind: string; text: string } | null>(null);
  readonly grantable = signal<GrantableView | null>(null);

  private base: { direct: Set<string>; ceiling: Set<string> } = { direct: new Set(), ceiling: new Set() };
  private work: { direct: Set<string>; ceiling: Set<string> } = { direct: new Set(), ceiling: new Set() };

  readonly dirty = computed(() =>
    setDiff(this.work.direct, this.base.direct) || setDiff(this.work.ceiling, this.base.ceiling));

  readonly selected = computed(() => {
    const id = this.selectedId();
    const root = this.tree();
    return id && root ? nodeById(root, id) : null;
  });

  async ngOnInit() { await this.reload(); }

  /** "MERCHANT" -> "Merchant" */
  word(t: string | null) { return t ? t.charAt(0) + t.slice(1).toLowerCase() : ''; }
  list(csv: string | null) { return (csv ?? '').split(',').map((x) => x.trim()).filter(Boolean); }
  words(xs: string[]) { return xs.length ? xs.map((x) => this.word(x)).join(', ') : '—'; }

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

  pick(n: OrgNodeRow) { this.selectedId.set(n.id); void this.loadRights(n.id); }

  holds(kind: 'direct' | 'ceiling', t: string) {
    return this.work[kind].has(t);
  }

  toggle(kind: 'direct' | 'ceiling', t: string, on: boolean) {
    // [D1]: nothing here can ADD a right; only removals mutate the working set
    if (on) return;
    this.work[kind].delete(t);
    this.refreshRightsSignal();
    this.flash.set(null);
  }

  readonly editing = signal(false);

  openEdit() {
    this.reset();
    this.editing.set(true);
  }

  closeEdit() {
    this.reset();
    this.editing.set(false);
  }

  reset() {
    this.work = { direct: new Set(this.base.direct), ceiling: new Set(this.base.ceiling) };
    this.refreshRightsSignal();
    this.flash.set(null);
  }

  async reload() {
    this.flash.set(null);
    await Promise.all([this.loadTree(),
      this.selectedId() ? this.loadRights(this.selectedId()!) : Promise.resolve()]);
  }

  private async loadTree() {
    this.loading.set(true);
    this.problem.set('');
    try {
      const t = await firstValueFrom(this.http.get<OrgNodeRow>('/api/orgs/me/tree'));
      this.tree.set(t);
      if (!this.selectedId() && t.id) {
        this.selectedId.set(t.id);
        await this.loadRights(t.id);
      }
    } catch (e) {
      this.problem.set(problemText(e));
    } finally {
      this.loading.set(false);
    }
  }

  private async loadRights(orgId: string) {
    this.rightsError.set('');
    try {
      const r = await firstValueFrom(this.http.get<OrgRightsView>(`/api/orgs/${orgId}/rights`));
      this.base = {
        direct: new Set(splitList(r.direct)),
        ceiling: new Set(splitList(r.ceiling)),
      };
      this.reset();
      this.rights.set(r);
      this.grantable.set(orgId === this.auth.orgId()
        ? await firstValueFrom(this.http.get<GrantableView>('/api/orgs/me/grantable'))
        : null);
    } catch (e) {
      this.rights.set(null);
      const p = readProblem(e);
      this.rightsError.set(`Rights unavailable (${p.code ?? p.status}): ${p.detail}`);
    }
  }

  private refreshRightsSignal() {
    const r = this.rights();
    if (!r) return;
    this.rights.set({
      direct: joinSet(this.work.direct),
      ceiling: joinSet(this.work.ceiling),
      effDirect: r.effDirect,
      effCeiling: r.effCeiling,
    });
  }

  async save() {
    const orgId = this.selectedId();
    if (!orgId) return;
    this.busy.set(true);
    this.flash.set(null);
    try {
      const updated = await firstValueFrom(this.http.put<OrgRightsView>(`/api/orgs/${orgId}/rights`, {
        rightsDirect: [...this.work.direct],
        rightsCeiling: [...this.work.ceiling],
      }));
      this.base = {
        direct: new Set(splitList(updated.direct)),
        ceiling: new Set(splitList(updated.ceiling)),
      };
      this.reset();
      this.rights.set(updated);
      this.editing.set(false);
      this.flash.set({ kind: 'ok', text: 'Rights narrowed and saved.' });
    } catch (e) {
      const p = readProblem(e);
      this.flash.set({ kind: 'bad',
        text: `Refused (${p.code ?? p.status}): ${p.detail}${p.code === 'RIGHTS_NARROW_ONLY' ? ' — rights may only be narrowed' : ''}` });
    } finally {
      this.busy.set(false);
    }
  }
}

function splitList(s: string | null): string[] {
  return s ? s.split(',').map((x) => x.trim()).filter(Boolean) : [];
}

function joinSet(s: Set<string>): string {
  return [...s].sort().join(',');
}

function setDiff(a: Set<string>, b: Set<string>): boolean {
  if (a.size !== b.size) return true;
  for (const x of a) if (!b.has(x)) return true;
  return false;
}

function nodeById(root: OrgNodeRow, id: string): OrgNodeRow | null {
  if (root.id === id) return root;
  for (const c of root.children ?? []) {
    const hit = nodeById(c, id);
    if (hit) return hit;
  }
  return null;
}
