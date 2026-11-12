import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import {
  RbacResourceView, RbacRoleUpdate, RbacRoleView, RBAC_VERBS, RbacVerb, readProblem,
} from '../../core/api';

/**
 * Permission matrix (fapi2-design Appendix G, cut-over P5.5), live from RBAC.
 *
 * Read it org type first. Pick Merchant, Supplier, Lender, Distributor, Manufacturer or
 * TPTS / Platform, and the grid shows only the roles and rows that apply there:
 * - rows: the resources, grouped by area, in plain words (technical id underneath). A
 *   row applies to an org type when it is that type's row (tenant_types) or a generic row
 *   the type's roles use; platform rows are the ones platform roles use. "Show unused
 *   rows" adds the rest, to tick something no role holds yet.
 * - columns (overview): the roles of that org type.
 * - cell: what the role may do on the row, as words. Solid = ticked on the role itself;
 *   outlined = inherited, with the role it comes from. Only verbs the row uses exist.
 * - a ⚑ marks privileged actions (money, rights, identity, KYC decisions, controls). This
 *   is a provisional rule until risk tiers come from RBAC (org-access-model OA5).
 *
 * New role: "+ New role" creates a non-seeded ORG role (or PLATFORM role on the TPTS tab)
 * that builds on existing roles and/or starts from a copy of one's own actions, then opens
 * it in the editor. A non-seeded role can be deleted while nobody holds it.
 *
 * Editing: click a role's column header. Only the role's own cells change; inherited ones
 * are changed on the role they come from. RBAC applies the grid save rules and refuses with
 * a reason. Seeded roles are editable in dev/sandbox (until the next grid.yaml change) and
 * locked in uat/production. Org-defined roles are managed per org under Roles & access.
 */

type ScopeId = 'MERCHANT' | 'SUPPLIER' | 'LENDER' | 'DISTRIBUTOR' | 'MANUFACTURER' | 'PLATFORM';
interface Scope { id: ScopeId; label: string }
interface Grant { verb: RbacVerb; label: string; kind: 'own' | 'inh'; from: string; privileged: boolean }
interface Group { area: string; label: string; rows: RbacResourceView[] }
type ChipState = 'own' | 'inh' | 'off';

const SCOPES: Scope[] = [
  { id: 'MERCHANT', label: 'Merchant' },
  { id: 'SUPPLIER', label: 'Supplier' },
  { id: 'LENDER', label: 'Lender' },
  { id: 'DISTRIBUTOR', label: 'Distributor' },
  { id: 'MANUFACTURER', label: 'Manufacturer' },
  { id: 'PLATFORM', label: 'standard' },
];

const VERB_LABEL: Record<RbacVerb, string> = {
  read: 'view', create: 'create', modify: 'edit', delete: 'delete', submit: 'submit',
  authorize: 'approve', execute: 'perform', export: 'export', administer: 'administer', simulate: 'simulate',
};

const AREA_LABEL: Record<string, string> = {
  merchant: 'Merchant business', supplier: 'Supplier business', lender: 'Lending',
  org: 'Organisation', kyc: 'KYC', rbac: 'Access control', ops: 'Operations', admin: 'Platform administration',
};
const AREA_ORDER = ['merchant', 'supplier', 'lender', 'org', 'kyc', 'rbac', 'ops', 'admin'];

const ROLE_LABEL: Record<string, string> = {
  kyc_applicant: 'KYC applicant', org_viewer: 'Viewer', org_staff: 'Staff', org_admin: 'Admin',
  org_user_admin: 'User admin', org_onboarder: 'Onboarder', org_access_admin: 'Access admin',
  lender_officer: 'Lender officer', sandbox_tester: 'Sandbox tester', platform_mlro: 'MLRO',
};

const APPROVAL_LABEL: Record<string, string> = {
  none: 'no approval', org_admin: 'approval by another org admin', platform: 'approval by a platform user admin',
  compliance: 'approval by compliance', break_glass: 'break-glass approval',
};

/** Rows where performing / approving moves money or changes where it goes. */
const MONEY_ROWS = new Set(['merchant.withdrawal', 'merchant.payment', 'merchant.invoice', 'merchant.advance',
  'lender.advance', 'lender.collection', 'supplier.bank_account']);
/** Rows that are about who may do what, or who someone is. */
const ACCESS_ROWS = new Set(['org.user', 'org.rights', 'org.suspension', 'org.onboarding']);

@Component({
  selector: 'mpa-permission-matrix',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule],
  template: `
    @if (rbacMode() && rbacMode() !== 'enforce') {
      <div class="rbac-status" role="status">
        RBAC is in {{ rbacMode() }} mode: these roles do not yet decide MiPay application access; the legacy role checks do.
      </div>
    }

    <div class="head">
      <h2>Permission Matrix</h2>
      <button class="primary" (click)="startCreate()" [disabled]="busy() || loading()">+ New role</button>
      <button class="ghost" (click)="reload()" [disabled]="loading()">Refresh</button>
    </div>

    <div class="toolbar">
      <input class="filter" name="q" [ngModel]="q()" (ngModelChange)="q.set($event)" placeholder="Filter rows (e.g. withdrawal)" />
      <label class="small check"><input type="checkbox" [ngModel]="showAll()" (ngModelChange)="showAll.set($event)" /> show unused rows</label>
      <span class="legend small">
        <span><b class="chip-v own">view</b> on the role</span>
        <span><b class="chip-v inh">view</b> inherited</span>
        <span><b class="flag">⚑</b> privileged (provisional rule until risk tiers)</span>
      </span>
    </div>

    @if (problem(); as p) {<div class="err">{{ p }}</div>}
    @if (loading() && !roles().length) {<div class="muted pad">Loading…</div>}

    @if (creating()) {
      <!-- ============ create a role ============ -->
      <div class="panel create">
        <div class="ptitle">New {{ scope() === 'PLATFORM' ? 'platform' : 'org' }} role</div>
        <div class="muted small hint">
          @if (scope() === 'PLATFORM') {A platform role is held by TPTS staff and applies across all orgs.}
          @else {An org role can be given to users of any org whose ceiling allows it. Its rows only apply where they fit the org's type.}
          After creating it you pick its actions row by row.
        </div>
        <div class="form">
          <label>Name
            <input name="nr-name" [ngModel]="draftName()" (ngModelChange)="draftName.set($event)" placeholder="e.g. KYC Approver" autocomplete="off" />
            @if (draftName().trim()) {<span class="muted small">saved as <span class="mono">{{ roleId() || '—' }}</span></span>}
          </label>
          <label>What it is for
            <input name="nr-desc" [ngModel]="draftDesc()" (ngModelChange)="draftDesc.set($event)" placeholder="Takes payments and runs tabs, no withdrawals" />
          </label>
          <div>
            <div class="lbl">Builds on <span class="muted small">(it gets everything these roles have; leave empty to start from nothing)</span></div>
            <div class="picks">
              @for (x of creatableBases(); track x.name) {
                <label class="pick"><input type="checkbox" [checked]="draftBases().has(x.name)" (change)="toggleBase(x.name)" /> {{ roleLabel(x.name) }}</label>
              }
            </div>
          </div>
          <label>Start from a copy of
            <select name="nr-copy" [ngModel]="draftCopy()" (ngModelChange)="draftCopy.set($event)">
              <option value="">nothing (blank)</option>
              @for (x of creatableBases(); track x.name) {<option [value]="x.name">{{ roleLabel(x.name) }}'s own actions</option>}
            </select>
          </label>
        </div>
        @if (createError(); as e) {<div class="err">{{ e }}</div>}
        <div class="actions">
          <button class="ghost" (click)="creating.set(false)" [disabled]="busy()">Cancel</button>
          <button class="primary" (click)="create()" [disabled]="busy() || !validName()">Create role</button>
        </div>
      </div>
    } @else if (editing(); as r) {
      <!-- ============ edit one role ============ -->
      <div class="panel">
        <div class="edit-head">
          <button class="ghost" (click)="closeEditor()">← All {{ scopeLabel() }} roles</button>
          <div class="ttl">
            <span class="rname">{{ roleLabel(r.name) }}</span>
            <span class="mono muted small">{{ r.name }}</span>
          </div>
          <select class="role-pick" [ngModel]="r.name" (ngModelChange)="openRole($event)" aria-label="Role">
            @for (x of scopeRoles(); track x.name) {<option [value]="x.name">{{ roleLabel(x.name) }}</option>}
          </select>
        </div>
        <div class="meta small">
          <span>{{ r.description }}</span>
          <span>inherits: {{ r.inherits.length ? labels(r.inherits) : 'nothing' }}</span>
          <span>granting it needs {{ approvalLabel(r.approval) }}</span>
          @if (r.locked) {<span class="tag amber">seeded · read-only here</span>}
          @else if (r.seeded) {<span class="tag amber">seeded · sandbox edit, lasts until the next grid.yaml change</span>}
        </div>
        @if (r.locked) {
          <div class="seeded-warning" role="status">Seeded roles can't be changed here in this environment. Update the reviewed grid.yaml and deploy it.</div>
        }
        @if (!r.seeded) {
          <div class="danger-row"><button class="danger" (click)="deleteRole(r.name)" [disabled]="busy()">Delete this role</button>
            <span class="muted small">only while nobody holds it</span></div>
        }
        <div class="muted small hint">Click an action to give or take it. Inherited actions are changed on the role they come from.</div>

        @for (g of groups(); track g.area) {
          <div class="group">{{ g.label }}</div>
          @for (row of g.rows; track row.id) {
            <div class="erow">
              <div class="rlabel">
                <span class="rdesc">{{ row.description || row.id }}</span>
                <span class="mono muted small">{{ row.id }}</span>
              </div>
              <div class="chips">
                @for (v of row.used; track v) {
                  <button type="button" class="chip-v"
                          [class.own]="states().get(row.id + ':' + v) === 'own'"
                          [class.inh]="states().get(row.id + ':' + v) === 'inh'"
                          [class.off]="states().get(row.id + ':' + v) === 'off'"
                          [class.dirty]="isDirty(row.id, $any(v))"
                          [disabled]="r.locked || states().get(row.id + ':' + v) === 'inh' || busy()"
                          [attr.aria-pressed]="states().get(row.id + ':' + v) !== 'off'"
                          [title]="chipTitle(r, row, $any(v), states().get(row.id + ':' + v) ?? 'off')"
                          (click)="toggle(row.id, $any(v))">
                    @if (privileged(row.id, $any(v))) {<b class="flag">⚑</b>}{{ verbLabel(v) }}
                    @if (states().get(row.id + ':' + v) === 'inh') {<em> · from {{ roleLabel(sourceOf(r, row.id + ':' + v)) }}</em>}
                  </button>
                }
              </div>
            </div>
          }
        } @empty {
          <div class="muted pad">No rows match.</div>
        }

        @if (saveError(); as e) {<div class="err">{{ e }}</div>}
        @if (saved()) {<div class="ok small">Saved.</div>}
        @if (dirtyCount()) {
          <div class="actions">
            <span class="muted small">{{ dirtyCount() }} change(s)</span>
            <button class="ghost" (click)="discard()" [disabled]="busy()">Discard</button>
            <button class="primary" (click)="save()" [disabled]="busy()">Save {{ roleLabel(r.name) }}</button>
          </div>
        }
      </div>
    } @else if (roles().length) {
      <!-- ============ overview: rows × roles of this org type ============ -->
      <div class="panel">
        @if (scopeNote(); as n) {<div class="muted small note">{{ n }}</div>}
        <div class="viewbar">
          <div class="seg" role="tablist" aria-label="View">
            <button type="button" role="tab" [class.on]="view() === 'controller'" (click)="view.set('controller')">By controller</button>
            <button type="button" role="tab" [class.on]="view() === 'permission'" (click)="view.set('permission')">By permission</button>
          </div>
          @if (view() === 'controller') {
            <span class="muted small">{{ controllers().length }} controllers · {{ endpointCount() }} endpoints × {{ scopeRoles().length }} roles</span>
            <span class="grow"></span>
            <button type="button" class="ghost sm" (click)="expandAll()">Expand all</button>
            <button type="button" class="ghost sm" (click)="collapseAll()">Collapse all</button>
          } @else {
            <span class="muted small">{{ rowCount() }} rows × {{ scopeRoles().length }} roles · click a role to edit it</span>
          }
        </div>
        @if (view() === 'controller') {
        <div class="overview-wrap">
          <table class="overview ctl">
            <thead>
              <tr>
                <th class="resource-col">Endpoint</th>
                <th class="perm-col">Needs</th>
                @for (r of scopeRoles(); track r.name) {
                  <th class="rolehead" [title]="r.name + ': ' + r.description">
                    <button type="button" class="rolebtn" (click)="openRole(r.name)"><span>{{ roleLabel(r.name) }}</span></button>
                  </th>
                }
              </tr>
            </thead>
            <tbody>
              @for (c of controllers(); track c.name) {
                @let open = openCtl().has(c.name) || !!q().trim();
                <tr class="ctlhead" (click)="toggleCtl(c.name)" [attr.aria-expanded]="open">
                  <td [attr.colspan]="scopeRoles().length + 2">
                    <span class="caret">{{ open ? '▾' : '▸' }}</span>
                    <b>{{ c.name }}</b>
                    <span class="muted small"> · {{ c.endpoints.length }} endpoint{{ c.endpoints.length === 1 ? '' : 's' }}</span>
                  </td>
                </tr>
                @if (open) {
                  @for (e of c.endpoints; track e.method + e.path) {
                    <tr class="eprow">
                      <td class="resource-cell"><span class="m {{ e.method.toLowerCase() }}">{{ e.method }}</span>
                        <span class="mono">{{ e.path }}</span>
                        <span class="muted small hname">{{ e.method_name }}</span></td>
                      <td class="perm-cell"><span class="rdesc">{{ e.rowDesc }}</span>
                        <span class="chip-v own">{{ verbLabel($any(e.verb)) }}</span>
                        <span class="mono muted small">{{ e.cell }}</span></td>
                      @for (r of scopeRoles(); track r.name) {
                        <td class="ov-cell">@if (r.permissions.includes(e.cell)) {<span class="tick">✓</span>} @else {<span class="none">—</span>}</td>
                      }
                    </tr>
                  }
                }
              } @empty {
                <tr><td class="muted pad" [attr.colspan]="scopeRoles().length + 2">No endpoints match.</td></tr>
              }
            </tbody>
          </table>
        </div>
        } @else {
        <div class="overview-wrap">
          <table class="overview">
            <thead>
              <tr>
                <th class="resource-col">What</th>
                @for (r of scopeRoles(); track r.name) {
                  <th class="rolehead" [title]="r.name + ': ' + r.description">
                    <button type="button" class="rolebtn" (click)="openRole(r.name)">
                      <span>{{ roleLabel(r.name) }}</span>
                      @if (r.inherits.length) {<em>+ {{ labels(r.inherits) }}</em>}
                    </button>
                  </th>
                }
              </tr>
            </thead>
            <tbody>
              @for (g of groups(); track g.area) {
                <tr class="grow"><td [attr.colspan]="scopeRoles().length + 1">{{ g.label }}</td></tr>
                @for (row of g.rows; track row.id) {
                  <tr>
                    <td class="resource-cell">
                      <span class="rdesc">{{ row.description || row.id }}</span>
                      <span class="mono muted small">{{ row.id }}</span>
                    </td>
                    @for (r of scopeRoles(); track r.name) {
                      <td class="ov-cell">
                        @for (c of grants(r, row); track c.verb) {
                          <span class="chip-v {{ c.kind }}" [title]="c.kind === 'own' ? 'on ' + r.name : 'inherited from ' + c.from">@if (c.privileged) {<b class="flag">⚑</b>}{{ c.label }}</span>
                        } @empty {<span class="none">—</span>}
                      </td>
                    }
                  </tr>
                }
              } @empty {
                <tr><td class="muted pad" [attr.colspan]="scopeRoles().length + 1">No rows match.</td></tr>
              }
            </tbody>
          </table>
        </div>
        }
      </div>
    }
  `,
  styles: [`
    .viewbar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 10px; }
    .viewbar .grow { flex: 1; }
    .seg { display: inline-flex; border: 1px solid #2a2a2a; border-radius: 10px; padding: 3px; gap: 3px; background: #0d0d0d; }
    .seg button { border: 0; background: transparent; padding: 7px 14px; border-radius: 7px; font-size: 13.5px; color: var(--a-dim); }
    .seg button.on { background: #262626; color: var(--a-text); font-weight: 600; }
    button.sm { padding: 5px 12px; font-size: 12.5px; }
    .ctlhead td { cursor: pointer; padding: 10px 12px; background: #141414; border-top: 1px solid #222; font-size: 14px; }
    .ctlhead:hover td { background: #1a1a1a; }
    .caret { display: inline-block; width: 18px; color: var(--a-dim); }
    .eprow .resource-cell { display: table-cell; }
    .m { display: inline-block; min-width: 54px; font-size: 11px; font-weight: 700; letter-spacing: .04em; color: var(--a-dim); }
    .m.get { color: var(--a-green); } .m.post { color: var(--a-teal); } .m.put, .m.patch { color: var(--a-amber); } .m.delete { color: var(--a-red); }
    .hname { margin-left: 8px; }
    .perm-col { min-width: 220px; text-align: left; }
    .perm-cell .rdesc { margin-right: 6px; }
    .tick { color: var(--a-green); font-weight: 700; }
    .rbac-status { margin-bottom: 12px; padding: 10px 12px; border: 1px solid #d6a43a; border-radius: 6px; color: #f4d58d; background: #302713; font-size: 13px; }
    .seeded-warning { margin: 8px 0; padding: 8px 10px; border-left: 3px solid #d6a43a; color: #f4d58d; background: #302713; font-size: 12px; }
    .head { display: flex; align-items: center; gap: 14px; margin-bottom: 10px; }
    h2 { margin: 0; font-size: 24px; line-height: 1.1; flex: 1; }
    .scopes { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 10px; }
    .scopes button { border: 1px solid var(--a-border); border-radius: 999px; background: transparent; padding: 7px 16px; color: var(--a-dim); font-size: 14px; }
    .scopes button.on { background: var(--a-teal); border-color: var(--a-teal); color: var(--a-accent-ink); font-weight: 700; }
    .toolbar { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; margin-bottom: 10px; }
    .filter { max-width: 260px; }
    .check { display: inline-flex; gap: 6px; align-items: center; color: var(--a-dim); white-space: nowrap; }
    .check input { width: auto; margin: 0; }
    .legend { display: inline-flex; gap: 12px; flex-wrap: wrap; color: var(--a-dim); align-items: center; }
    .panel { padding: 12px; border: 1px solid var(--a-border); border-radius: 8px; min-width: 0; }
    .note { margin-bottom: 6px; }
    .overview-wrap { overflow: auto; max-height: calc(100vh - 240px); }
    .overview { border-collapse: separate; border-spacing: 0; font-size: 14px; }
    .overview th, .overview td { padding: 7px 10px; border-bottom: 1px solid var(--a-border); vertical-align: top; }
    .overview thead th { position: sticky; top: 0; background: var(--a-bg, #0b0b0b); z-index: 2; vertical-align: bottom; }
    .overview .resource-col, .overview .resource-cell { position: sticky; left: 0; background: var(--a-bg, #0b0b0b); z-index: 1; min-width: 260px; max-width: 320px; }
    .overview thead .resource-col { z-index: 3; text-align: left; }
    .rolehead { min-width: 140px; text-align: left; font-size: 14px; }
    .rolebtn { border: 0; background: transparent; padding: 0; text-align: left; color: inherit; cursor: pointer; font-weight: 700; }
    .rolebtn span { display: block; }
    .rolebtn em { display: block; font-style: normal; font-weight: 400; color: var(--a-dim); font-size: 12px; }
    .rolebtn:hover span { color: var(--a-teal); text-decoration: underline; }
    .grow td { font-weight: 700; color: var(--a-teal); font-size: 15px; padding-top: 14px; position: sticky; left: 0; background: var(--a-bg, #0b0b0b); }
    .resource-cell .rdesc, .rlabel .rdesc { display: block; }
    .resource-cell .mono, .rlabel .mono { display: block; }
    .ov-cell { min-width: 140px; }
    .none { color: var(--a-border); }
    .chip-v { display: inline-block; margin: 0 4px 4px 0; padding: 2px 9px; border-radius: 999px; font-size: 13px; line-height: 20px; border: 1px solid var(--a-teal); white-space: nowrap; }
    .chip-v.own { background: var(--a-teal); color: var(--a-accent-ink); font-weight: 600; }
    .chip-v.inh { background: transparent; color: var(--a-dim); border-style: dashed; }
    .chip-v.off { background: transparent; color: var(--a-dim); border-color: var(--a-border); }
    .chip-v em { font-style: normal; opacity: .8; }
    button.chip-v { cursor: pointer; font: inherit; font-size: 13px; text-transform: none; letter-spacing: 0; box-shadow: none; min-height: 0; }
    button.chip-v.own, button.chip-v.own:hover, button.chip-v.own:focus, button.chip-v.own:active { background: var(--a-teal); color: var(--a-accent-ink); border-color: var(--a-teal); }
    button.chip-v.off, button.chip-v.off:hover, button.chip-v.off:focus, button.chip-v.off:active { background: transparent; color: var(--a-dim); border-color: var(--a-border); }
    button.chip-v.off:hover { border-color: var(--a-teal); color: var(--a-text, #eee); }
    button.chip-v.inh, button.chip-v.inh:disabled { background: transparent; color: var(--a-dim); border-color: var(--a-teal); opacity: 1; }
    button.chip-v:disabled { cursor: default; }
    button.chip-v.own:disabled, button.chip-v.off:disabled { opacity: .6; }
    button.chip-v.dirty { outline: 2px solid var(--a-amber, orange); outline-offset: 1px; }
    .flag { color: #d6a43a; margin-right: 3px; font-weight: 700; }
    .chip-v.own .flag { color: #7a4b00; }
    .edit-head { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 8px; }
    .ttl { flex: 1; display: flex; gap: 8px; align-items: baseline; flex-wrap: wrap; }
    .rname { font-size: 19px; font-weight: 700; }
    .role-pick { max-width: 220px; }
    .meta { display: flex; gap: 14px; flex-wrap: wrap; color: var(--a-dim); margin-bottom: 8px; align-items: center; font-size: 13px; }
    .tag { padding: 1px 8px; border-radius: 999px; border: 1px solid #d6a43a; color: #f4d58d; }
    .hint { margin: 4px 0 6px; }
    .group { font-weight: 700; color: var(--a-teal); margin: 16px 0 4px; font-size: 16px; }
    .erow { display: grid; grid-template-columns: minmax(240px, 360px) 1fr; gap: 14px; padding: 8px 0; border-bottom: 1px solid var(--a-border); align-items: start; }
    @media (max-width: 700px) { .erow { grid-template-columns: 1fr; gap: 4px; } }
    .rlabel { font-size: 15px; }
    .chips { display: flex; flex-wrap: wrap; }
    .mono { font-family: var(--font-mono, monospace); }
    .actions { position: sticky; bottom: 0; display: flex; gap: 8px; align-items: center; justify-content: flex-end; margin-top: 10px; padding: 8px 0; background: var(--a-bg, #0b0b0b); }
    .err { color: var(--a-red); font-size: var(--fs-sm); margin: 6px 0; }
    .ok { color: var(--a-green); margin-top: 6px; }
    .muted { color: var(--a-dim); }
    .create .ptitle { font-size: 16px; font-weight: 700; margin-bottom: 4px; }
    .form { display: grid; gap: 12px; max-width: 720px; margin: 10px 0; }
    .form label, .form .lbl { display: grid; gap: 4px; font-size: 14px; }
    .picks { display: flex; flex-wrap: wrap; gap: 6px 14px; }
    .pick { display: inline-flex !important; gap: 6px; align-items: center; font-size: 14px; }
    .pick input { width: auto; margin: 0; }
    .danger-row { display: flex; gap: 10px; align-items: center; margin: 6px 0; }
    .small { font-size: 12.5px; }
    .pad { padding: 16px; }
  `],
})
export class PermissionMatrix implements OnInit {
  private readonly http = inject(HttpClient);
  readonly scopes = SCOPES;

  readonly roles = signal<RbacRoleView[]>([]);
  readonly resources = signal<RbacResourceView[]>([]);
  /** No org-type tabs: one matrix of every standard role over every row from the code. */
  readonly scope = signal<ScopeId>('PLATFORM');
  readonly q = signal('');
  readonly showAll = signal(false);
  /** Overview: grouped by controller (each expandable) or the permission grid. */
  readonly view = signal<'controller' | 'permission'>('controller');
  readonly openCtl = signal<Set<string>>(new Set());
  readonly controllers = computed(() => {
    const q = this.q().trim().toLowerCase();
    const by = new Map<string, { name: string; endpoints: { method: string; path: string; verb: string; cell: string;
      rowDesc: string; method_name: string }[] }>();
    for (const row of this.resources()) {
      for (const e of row.endpoints ?? []) {
        const [ctl, fn] = (e.handler || 'Other#').split('#');
        const cell = row.id + ':' + e.verb;
        const ep = { method: e.method, path: e.path, verb: e.verb, cell, rowDesc: row.description || row.id, method_name: fn ?? '' };
        if (q && ![ctl, e.path, cell, ep.rowDesc].some((x) => x.toLowerCase().includes(q))) continue;
        const g = by.get(ctl) ?? { name: ctl, endpoints: [] };
        g.endpoints.push(ep);
        by.set(ctl, g);
      }
    }
    for (const g of by.values()) g.endpoints.sort((a, b) => a.path.localeCompare(b.path) || a.method.localeCompare(b.method));
    return [...by.values()].sort((a, b) => a.name.localeCompare(b.name));
  });
  readonly endpointCount = computed(() => this.controllers().reduce((n, c) => n + c.endpoints.length, 0));
  toggleCtl(name: string) {
    const s = new Set(this.openCtl());
    if (s.has(name)) s.delete(name); else s.add(name);
    this.openCtl.set(s);
  }
  expandAll() { this.openCtl.set(new Set(this.controllers().map((c) => c.name))); }
  collapseAll() { this.openCtl.set(new Set()); }
  readonly editingName = signal<string | null>(null);
  /** The edited role's own cells (all rows, not only the visible ones). */
  readonly work = signal<Set<string>>(new Set());
  readonly loading = signal(false);
  /** app.rbac.mode on the backend; the warning shows only while RBAC does not decide. */
  readonly rbacMode = signal('');
  readonly busy = signal(false);
  readonly problem = signal('');
  readonly saveError = signal('');
  readonly saved = signal(false);
  // ---- new role draft ----
  readonly creating = signal(false);
  readonly draftName = signal('');
  readonly draftDesc = signal('');
  readonly draftBases = signal<Set<string>>(new Set());
  readonly draftCopy = signal('');
  readonly createError = signal('');
  /** Any name the admin types ("KYC Approver") becomes the role id RBAC needs ("kyc_approver"). */
  readonly roleId = computed(() => {
    let id = this.draftName().trim().toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '')
      .replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '');
    if (id && !/^[a-z]/.test(id)) id = 'r_' + id;
    return id.slice(0, 64);
  });
  readonly validName = computed(() => /^[a-z][a-z0-9_]{2,63}$/.test(this.roleId()));
  /** Roles a new role of this scope may build on: same kind, standard (org-defined roles live per org). */
  readonly creatableBases = computed(() => this.scopeRoles().filter((r) => r.name !== 'kyc_applicant'));

  private readonly byName = computed(() => new Map(this.roles().map((r) => [r.name, r])));
  readonly editing = computed(() => this.byName().get(this.editingName() ?? '') ?? null);
  readonly scopeLabel = computed(() => SCOPES.find((s) => s.id === this.scope())!.label);
  private readonly rowById = computed(() => new Map(this.resources().map((r) => [r.id, r])));

  /** Rows that exist at all (some endpoint uses a cell on them). */
  private readonly liveRows = computed(() => this.resources().filter((r) => r.used.length));

  /** Does a row belong to this org type at all (its own type's row, or a generic row)? */
  private fitsType(row: RbacResourceView, scope: ScopeId) {
    return !row.tenantTypes.length || (scope !== 'PLATFORM' && row.tenantTypes.includes(scope));
  }

  /** Roles of the org type: platform roles for TPTS; org roles holding anything that applies there. */
  readonly scopeRoles = computed(() => {
    const s = this.scope(), rows = this.rowById();
    return this.roles()
      .filter((r) => s === 'PLATFORM' || r.kind === 'ORG')
      .filter((r) => s === 'PLATFORM' || !r.permissions.length || r.permissions.some((c) => {
        const row = rows.get(c.split(':')[0]);
        return !row || this.fitsType(row, s);
      }))
      .sort((a, b) => this.rank(a) - this.rank(b) || a.name.localeCompare(b.name));
  });

  /** Row ids some role of the org type holds. */
  private readonly heldRows = computed(() => {
    const out = new Set<string>();
    for (const r of this.scopeRoles()) for (const c of r.permissions) out.add(c.split(':')[0]);
    return out;
  });

  private readonly visibleRows = computed(() => {
    const s = this.scope(), q = this.q().trim().toLowerCase(), held = this.heldRows(), all = this.showAll();
    const ed = this.editing();
    const own = ed ? new Set([...this.work()].map((c) => c.split(':')[0])) : new Set<string>();
    return this.liveRows().filter((row) => {
      if (s === 'PLATFORM') {
        if (!all && !held.has(row.id) && !own.has(row.id)) return false;
      } else {
        if (!this.fitsType(row, s)) return false;
        const typeRow = row.tenantTypes.includes(s);
        if (!all && !typeRow && !held.has(row.id) && !own.has(row.id)) return false;
      }
      return !q || row.id.toLowerCase().includes(q) || row.description.toLowerCase().includes(q);
    });
  });

  readonly groups = computed<Group[]>(() => {
    const m = new Map<string, RbacResourceView[]>();
    for (const row of this.visibleRows()) {
      const area = row.id.split('.')[0];
      if (!m.has(area)) m.set(area, []);
      m.get(area)!.push(row);
    }
    const order = (a: string) => { const i = AREA_ORDER.indexOf(a); return i < 0 ? 99 : i; };
    return [...m.entries()].sort((a, b) => order(a[0]) - order(b[0]) || a[0].localeCompare(b[0]))
      .map(([area, rows]) => ({ area, label: AREA_LABEL[area] ?? area, rows: rows.sort((x, y) => x.id.localeCompare(y.id)) }));
  });

  readonly rowCount = computed(() => this.visibleRows().length);

  readonly scopeNote = computed(() => {
    const s = this.scope();
    if (s === 'PLATFORM') return '';
    const own = this.liveRows().some((r) => r.tenantTypes.includes(s));
    return own ? '' : `${this.scopeLabel()} orgs have no rows of their own yet: only the rows every org has (organisation, KYC, access).`;
  });

  private readonly ownSaved = computed(() => new Set(this.editing()?.own ?? []));
  readonly dirtyCount = computed(() => {
    const a = this.ownSaved(), b = this.work();
    let n = 0;
    for (const c of a) if (!b.has(c)) n++;
    for (const c of b) if (!a.has(c)) n++;
    return n;
  });

  async ngOnInit() {
    firstValueFrom(this.http.get<{ mode: string }>('/api/admin/rbac/mode'))
      .then((m) => this.rbacMode.set(m.mode)).catch(() => this.rbacMode.set('unknown'));
    await this.reload();
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
      const ed = this.editingName();
      if (ed) {
        const r = roles.find((x) => x.name === ed);
        if (r) this.work.set(new Set(r.own)); else this.editingName.set(null);
      }
    } catch (e) {
      this.problem.set(readProblem(e).detail || 'Failed to load roles from RBAC');
    } finally {
      this.loading.set(false);
    }
  }

  setScope(s: ScopeId) {
    if (this.dirtyCount() && !this.confirmDiscard()) return;
    this.scope.set(s);
    this.editingName.set(null);
    this.creating.set(false);
  }

  openRole(name: string) {
    if (this.editingName() === name) return;
    if (this.dirtyCount() && !this.confirmDiscard()) return;
    const r = this.byName().get(name);
    if (!r) return;
    this.creating.set(false);
    this.editingName.set(name);
    this.work.set(new Set(r.own));
    this.saveError.set('');
    this.saved.set(false);
  }

  closeEditor() {
    if (this.dirtyCount() && !this.confirmDiscard()) return;
    this.editingName.set(null);
  }

  private confirmDiscard() {
    return confirm('Discard your unsaved changes to this role?');
  }

  // ---- creating and deleting roles ----------------------------------------

  startCreate() {
    if (this.dirtyCount() && !this.confirmDiscard()) return;
    this.editingName.set(null);
    this.draftName.set('');
    this.draftDesc.set('');
    this.draftBases.set(new Set());
    this.draftCopy.set('');
    this.createError.set('');
    this.creating.set(true);
  }

  toggleBase(name: string) {
    const b = new Set(this.draftBases());
    if (b.has(name)) b.delete(name); else b.add(name);
    this.draftBases.set(b);
  }

  async create() {
    const name = this.roleId();
    if (!this.validName()) { this.createError.set('Give the role a name of at least 3 letters.'); return; }
    if (this.byName().has(name)) { this.createError.set(`A role called ${name} already exists.`); return; }
    const copy = this.byName().get(this.draftCopy());
    const kind = this.scope() === 'PLATFORM' ? 'PLATFORM' : 'ORG';
    this.busy.set(true);
    this.createError.set('');
    try {
      await firstValueFrom(this.http.post<RbacRoleView>('/api/admin/rbac/roles', {
        name, description: this.draftDesc().trim(), kind,
        inherits: [...this.draftBases()], permissions: copy ? [...copy.own] : [],
      }));
      await this.reload();
      this.creating.set(false);
      this.openRole(name);
    } catch (e) {
      const p = readProblem(e);
      this.createError.set(`Refused (${p.code ?? p.status}): ${p.detail}`);
    } finally {
      this.busy.set(false);
    }
  }

  async deleteRole(name: string) {
    if (!confirm(`Delete the role ${name}? This only works while nobody holds it.`)) return;
    this.busy.set(true);
    this.saveError.set('');
    try {
      await firstValueFrom(this.http.delete(`/api/admin/rbac/roles/${encodeURIComponent(name)}`));
      this.editingName.set(null);
      await this.reload();
    } catch (e) {
      const p = readProblem(e);
      this.saveError.set(`Refused (${p.code ?? p.status}): ${p.detail}`);
    } finally {
      this.busy.set(false);
    }
  }

  // ---- reading the grid --------------------------------------------------

  /** What a role may do on a row, own first-class and inherited with its source. */
  grants(r: RbacRoleView, row: RbacResourceView): Grant[] {
    const own = new Set(r.own), eff = new Set(r.permissions);
    const out: Grant[] = [];
    for (const v of RBAC_VERBS) {
      if (!row.used.includes(v)) continue;
      const c = `${row.id}:${v}`;
      const isOwn = own.has(c);
      if (!isOwn && !eff.has(c)) continue;
      out.push({ verb: v, label: VERB_LABEL[v], kind: isOwn ? 'own' : 'inh',
        from: isOwn ? r.name : this.sourceOf(r, c), privileged: this.privileged(row.id, v) });
    }
    return out;
  }

  /** The junior role an inherited cell comes from (nearest first), or "all rows". */
  sourceOf(r: RbacRoleView, cell: string): string {
    const seen = new Set<string>([r.name]);
    let level = [...r.inherits];
    while (level.length) {
      const next: string[] = [];
      for (const n of level) {
        if (seen.has(n)) continue;
        seen.add(n);
        const j = this.byName().get(n);
        if (!j) continue;
        if (j.own.includes(cell)) return n;
        next.push(...j.inherits);
      }
      level = next;
    }
    const verb = cell.split(':')[1];
    if (r.allRows.includes(verb)) return 'all rows';
    for (const n of seen) if (this.byName().get(n)?.allRows.includes(verb)) return n;
    return 'a junior role';
  }

  /** Every cell of the edited role: own (as edited), inherited, or off. */
  readonly states = computed(() => {
    const r = this.editing(), w = this.work(), saved = this.ownSaved();
    const out = new Map<string, ChipState>();
    if (!r) return out;
    const eff = new Set(r.permissions);
    for (const row of this.resources()) {
      for (const v of row.used) {
        const c = `${row.id}:${v}`;
        out.set(c, w.has(c) ? 'own' : eff.has(c) && !saved.has(c) ? 'inh' : 'off');
      }
    }
    return out;
  });

  chipTitle(r: RbacRoleView, row: RbacResourceView, v: RbacVerb, st: ChipState) {
    const c = `${row.id}:${v}`;
    if (st === 'inh') return `${c}: inherited from ${this.sourceOf(r, c)}; change it there`;
    return `${c}: ${st === 'own' ? 'click to take away' : 'click to give'}`;
  }

  /**
   * Provisional privileged rule (until RBAC serves risk tiers, OA5): approving, administering,
   * moving money, changing users/rights, KYC decisions, and anything on access control,
   * operations decisions or platform controls that isn't a plain view.
   */
  privileged(rowId: string, v: string): boolean {
    if (v === 'read') return false;
    if (v === 'authorize' || v === 'administer') return true;
    if (MONEY_ROWS.has(rowId) && ['execute', 'modify', 'delete'].includes(v)) return true;
    if (ACCESS_ROWS.has(rowId) && ['create', 'delete'].includes(v)) return true;
    const area = rowId.split('.')[0];
    if (area === 'rbac' && v !== 'read') return true;
    if (area === 'admin' && ['modify', 'execute', 'export', 'simulate'].includes(v)) return true;
    if (rowId === 'kyc.application' && v === 'delete') return true;
    return false;
  }

  // ---- labels ------------------------------------------------------------

  verbLabel(v: string) { return VERB_LABEL[v as RbacVerb] ?? v; }

  roleLabel(name: string): string {
    if (!name) return '';
    if (ROLE_LABEL[name]) return ROLE_LABEL[name];
    const base = name.replace(/^platform_/, '').replace(/_/g, ' ');
    return base.charAt(0).toUpperCase() + base.slice(1);
  }

  labels(names: string[]) { return names.map((n) => this.roleLabel(n)).join(', '); }

  approvalLabel(a: string) { return APPROVAL_LABEL[a] ?? a; }

  /** Overview column order: the applicant and junior roles first, admin roles last. */
  private rank(r: RbacRoleView): number {
    const order = ['kyc_applicant', 'org_viewer', 'org_staff', 'lender_officer', 'org_user_admin', 'org_onboarder', 'org_access_admin', 'org_admin'];
    const i = order.indexOf(r.name);
    return i < 0 ? 50 + this.closure(r).size : i;
  }

  private closure(r: RbacRoleView): Set<string> {
    const out = new Set<string>();
    const stack = [...r.inherits];
    while (stack.length) {
      const n = stack.pop()!;
      if (out.has(n)) continue;
      out.add(n);
      stack.push(...(this.byName().get(n)?.inherits ?? []));
    }
    return out;
  }

  // ---- editing -----------------------------------------------------------

  isDirty(row: string, v: RbacVerb) {
    const c = `${row}:${v}`;
    return this.ownSaved().has(c) !== this.work().has(c);
  }

  toggle(row: string, v: RbacVerb) {
    const r = this.editing();
    if (!r || r.locked) return;
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
    const r = this.editing();
    if (!r || r.locked) return;
    this.busy.set(true);
    this.saveError.set('');
    try {
      const update: RbacRoleUpdate = { permissions: [...this.work()] };
      const updated = await firstValueFrom(this.http.put<RbacRoleView>(`/api/admin/rbac/roles/${encodeURIComponent(r.name)}`, update));
      // the hierarchy passes the change on to senior roles: reload them all
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
