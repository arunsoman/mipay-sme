import { PageHeader } from '../../../../../../src/app/design-system/organisms/page-header';
import { MpSpinner } from '../../../../../../src/app/design-system/atoms/mp-spinner';
import { MpAlert } from '../../../../../../src/app/design-system/molecules/alert';
import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { MpPerm } from '../../../../../shared/web-core/perm-directive';
import { MpTabs, TabOption } from '../../../../../../src/app/design-system/molecules/tabs';
import { TableView, TABLE_TOOLS } from '../../../../../../src/app/design-system/molecules/table-view';
import { AccessService } from '../../../../../shared/web-core/access';
import { BusinessPortal } from '../../../../../shared/web-core/portal-permission-catalogue';
import { buildTree } from '../../../../../shared/rbac/role-menu-model';
import type { PaletteRow } from '../../../../../shared/rbac/role-menu-model';
import { RoleMenuBuilder } from '../../../../../../src/app/design-system/organisms/role-menu-builder';
import { OperationsGrid } from '../../../../../../src/app/design-system/organisms/operations-grid';
import { buildOperationsTree } from '../../../../../shared/rbac/role-operations-model';
import type { RegisteredWidgetInfo } from '../../../../../shared/rbac/role-operations-model';
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

type ScopeId = BusinessPortal;
interface Scope { id: ScopeId; label: string }
interface Grant { verb: RbacVerb; label: string; kind: 'own' | 'inh'; from: string; privileged: boolean }
interface Group { area: string; label: string; rows: RbacResourceView[] }
type ChipState = 'own' | 'inh' | 'off';

/** Org portals, in the order a tie picks them (the deployed portals first). */
const ORG_PORTALS: ScopeId[] = ['SUPPLIER', 'MANUFACTURER', 'DISTRIBUTOR', 'MERCHANT', 'LENDER'];

const SCOPES: Scope[] = [
  { id: 'MERCHANT', label: 'Merchant' },
  { id: 'SUPPLIER', label: 'Supplier' },
  { id: 'LENDER', label: 'Lender' },
  { id: 'DISTRIBUTOR', label: 'Distributor' },
  { id: 'MANUFACTURER', label: 'Manufacturer' },
  { id: 'PLATFORM', label: 'Admin / platform' },
  { id: 'CUSTOMER', label: 'Customer' },
];

const VERB_LABEL: Record<RbacVerb, string> = {
  read: 'view', create: 'create', modify: 'edit', delete: 'delete', submit: 'submit',
  authorize: 'approve', execute: 'perform', export: 'export', administer: 'administer', simulate: 'simulate',
};

const AREA_LABEL: Record<string, string> = {
  merchant: 'Merchant business', supplier: 'Supplier business', lender: 'Lending',
  org: 'Organisation', kyc: 'KYC', rbac: 'Access control', ops: 'Operations', admin: 'Platform administration',
  nav: 'Menus (what the sidebar shows)',
};
const AREA_ORDER = ['merchant', 'supplier', 'lender', 'org', 'kyc', 'rbac', 'ops', 'admin', 'nav'];

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
  imports: [PageHeader, MpSpinner, MpAlert, MpTabs, FormsModule, MpPerm, ...TABLE_TOOLS, RoleMenuBuilder, OperationsGrid],
  template: `
    @if (rbacMode() && rbacMode() !== 'enforce') {
      <div class="rbac-status" role="status">
        RBAC is in {{ rbacMode() }} mode: these roles do not yet decide MiPay application access; the legacy role checks do.
      </div>
    }

    <page-header title="Roles and permissions">
      <div actions>
        <button *mpPerm="'rbac.role:create'" class="primary" (click)="startCreate()" [disabled]="busy() || loading()">+ New role</button>
        <button class="ghost" (click)="reload()" [disabled]="loading()">Refresh</button>
      </div>
    </page-header>

    <div class="toolbar">
      <input class="filter" name="q" [ngModel]="q()" (ngModelChange)="q.set($event); roleView.go(0); ctlView.go(0); rowView.go(0)" placeholder="Filter (e.g. cashier, withdrawal)" />
      @if (view() !== 'role' || editing() || creating()) {
        <label class="small check"><input type="checkbox" [ngModel]="showAll()" (ngModelChange)="showAll.set($event)" /> show unused rows</label>
        <span class="legend small">
          <span><b class="chip-v own">view</b> on the role</span>
          <span><b class="chip-v inh">view</b> inherited</span>
          <span><b class="flag">⚑</b> sensitive (money or access)</span>
        </span>
      }
    </div>

    @if (problem(); as p) {<mp-alert kind="bad">{{ p }}</mp-alert>}
    @if (loading() && !roles().length) {<mp-spinner label="Loading…" />}

    @if (creating()) {
      <!-- ============ create a role ============ -->
      <div class="panel create">
        <div class="ptitle">New role</div>
        <div class="muted small hint">
          @if (scope() === 'PLATFORM') {A platform role is held by TPTS staff and applies across all orgs.}
          @else {An org role can be given to users of any org whose ceiling allows it. Its rows only apply where they fit the org's type.}
          Pick the portal, then tick what the role may do on each widget, menu by menu: View, Create, Modify or Approve.
          Only the operations a widget supports can be ticked.
        </div>
        <div class="form">
          <label>Portal
            <select name="nr-portal" [ngModel]="scope()" (ngModelChange)="setDraftPortal($event)">
              @for (s of scopes; track s.id) {<option [value]="s.id">{{ s.label }}</option>}
            </select>
          </label>
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
            <select name="nr-copy" [ngModel]="draftCopy()" (ngModelChange)="setDraftCopy($event)">
              <option value="">nothing (blank)</option>
              @for (x of creatableBases(); track x.name) {<option [value]="x.name">{{ roleLabel(x.name) }}'s own actions</option>}
            </select>
          </label>
        </div>
        <div class="ptitle">{{ scopeLabel() }} portal: widgets and what the role may do on them</div>
        <mp-operations-grid [menus]="draftGrid()" [selected]="draftCells()" [inherited]="draftInherited()" [disabled]="busy()"
                            [empty]="draftWidgets() === null ? 'Loading widgets…' : 'No widgets are registered for this portal yet.'"
                            (selectedChange)="draftCells.set($event)" />
        @if (createError(); as e) {<mp-alert kind="bad">{{ e }}</mp-alert>}
        <div class="actions">
          <span class="muted small">{{ draftCells().size }} action(s) picked</span>
          <button class="ghost" (click)="creating.set(false)" [disabled]="busy()">Cancel</button>
          <button class="primary" (click)="create()" [disabled]="busy() || !validName()">Create role</button>
        </div>
      </div>
    } @else if (editing(); as r) {
      <!-- ============ edit one role ============ -->
      <div class="panel">
        <div class="edit-head">
          <button class="ghost" (click)="closeEditor()">← All roles</button>
          <div class="ttl">
            <span class="rname">{{ roleLabel(r.name) }}</span>
            <span class="mono muted small">{{ r.name }}</span>
          </div>
          <select class="role-pick" [ngModel]="r.name" (ngModelChange)="openRole($event)" aria-label="Role">
            @for (x of allRoles(); track x.name) {<option [value]="x.name">{{ roleLabel(x.name) }}</option>}
          </select>
        </div>
        <div class="meta small">
          <span>{{ r.description }}</span>
          <span>builds on: {{ r.inherits.length ? labels(r.inherits) : 'nothing' }}</span>
          <span>granting it needs {{ approvalLabel(r.approval) }}</span>
          @if (r.locked) {<span class="tag amber">built-in role · read-only</span>}
          @else if (r.seeded) {<span class="tag amber">built-in role · changes here last until the next release</span>}
        </div>
        @if (r.locked) {
          <div class="seeded-warning" role="status">Built-in roles can't be changed here. They change with a reviewed release.</div>
        }
        @if (!r.seeded) {
          <div class="danger-row"><button *mpPerm="'rbac.role:delete'" class="danger" (click)="deleteRole(r.name)" [disabled]="busy()">Delete this role</button>
            <span class="muted small">only while nobody holds it</span></div>
        }
        <div class="muted small hint">Switch menus and submenus on or off, then pick the actions on each screen. A switch gives the screen's view action; switching it off takes every action on that screen away. Actions shared with other screens say so. Inherited actions are changed on the role they come from.</div>

        <div class="business-tree">
          <div class="ptitle">
            @if (lensScopes(r).length > 1) {
              <label class="small lens">Menus of the
                <select name="ed-portal" [ngModel]="scope()" (ngModelChange)="scope.set($event)">
                  @for (s of lensScopes(r); track s.id) {<option [value]="s.id">{{ s.label }}</option>}
                </select> portal</label>
            } @else { {{ scopeLabel() }} portal: menus and screens }
          </div>
          <mp-role-menu-builder [portal]="scope()" [palette]="palette()" [selected]="work()"
                                [inherited]="inheritedCells()" [disabled]="!canEditTree()" [nav]="navEnabled()" (selectedChange)="setWork($event)" />
        </div>

        <details><summary>All backend permissions (technical view)</summary>

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
                          [disabled]="r.locked || !access.can('rbac.role:modify') || states().get(row.id + ':' + v) === 'inh' || busy()"
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
        </details>

        @if (saveError(); as e) {<mp-alert kind="bad">{{ e }}</mp-alert>}
        @if (saved()) {<mp-alert kind="ok">Saved.</mp-alert>}
        @if (dirtyCount()) {
          <div class="actions">
            <span class="muted small">{{ dirtyCount() }} change(s)</span>
            <button class="ghost" (click)="discard()" [disabled]="busy()">Discard</button>
            <button *mpPerm="'rbac.role:modify'" class="primary" (click)="save()" [disabled]="busy()">Save {{ roleLabel(r.name) }}</button>
          </div>
        }
      </div>
    } @else if (roles().length) {
      <!-- ============ overview: rows × roles of this org type ============ -->
      <div class="panel">
        <div class="viewbar">
          <mp-tabs [options]="viewOpts" [value]="view()" (valueChange)="view.set($event)" label="View" />
          @if (view() === 'role') {
            <span class="muted small">{{ roleRows().length }} roles · click a role to edit it</span>
          } @else if (view() === 'controller') {
            <span class="muted small">{{ controllers().length }} controllers · {{ endpointCount() }} endpoints × {{ allRoles().length }} roles</span>
            <span class="grow"></span>
            <button type="button" class="ghost sm" (click)="expandAll()">Expand all</button>
            <button type="button" class="ghost sm" (click)="collapseAll()">Collapse all</button>
          } @else {
            <span class="muted small">{{ rowCount() }} rows × {{ allRoles().length }} roles · click a role to edit it</span>
          }
        </div>
        @if (view() === 'role') {
        <div class="overview-wrap">
          <table class="overview roles">
            <thead>
              <tr><th class="resource-col">Role</th><th>Kind</th><th>Builds on</th><th>Actions</th><th>Shows</th></tr>
            </thead>
            <tbody>
              @for (x of roleView.rows(); track x.role.name) {
                <tr class="rrow" (click)="openRole(x.role.name)">
                  <td class="resource-cell">
                    <button type="button" class="rolebtn"><span>{{ roleLabel(x.role.name) }}</span></button>
                    <span class="muted small">{{ x.role.description }}</span>
                    <span class="mono muted small">{{ x.role.name }}</span>
                  </td>
                  <td>{{ x.role.kind === 'PLATFORM' ? 'Platform' : 'Organization' }}
                    @if (x.role.locked) {<span class="tag amber">built-in · read-only</span>}
                    @else if (x.role.seeded) {<span class="tag amber">built-in</span>}</td>
                  <td>{{ x.role.inherits.length ? labels(x.role.inherits) : '—' }}</td>
                  <td>{{ x.role.own.length }} own@if (x.role.permissions.length > x.role.own.length) {<span class="muted"> · {{ x.role.permissions.length }} in all</span>}</td>
                  <td>@if (x.screens) { {{ x.portalLabel }} portal · {{ x.menus }} menu{{ x.menus === 1 ? '' : 's' }}, {{ x.screens }} screen{{ x.screens === 1 ? '' : 's' }} }
                      @else {<span class="none">no menus</span>}</td>
                </tr>
              } @empty {
                <tr><td class="muted pad" colspan="5">No roles match.</td></tr>
              }
            </tbody>
          </table>
        </div>
        <mp-pager class="pg" [view]="roleView" />
        } @else if (view() === 'controller') {
        <div class="overview-wrap">
          <table class="overview ctl">
            <thead>
              <tr>
                <th class="resource-col">Screen action (API)</th>
                <th class="perm-col">Needs permission</th>
                @for (r of allRoles(); track r.name) {
                  <th class="rolehead" [title]="r.name + ': ' + r.description">
                    <button type="button" class="rolebtn" (click)="openRole(r.name)"><span>{{ roleLabel(r.name) }}</span></button>
                  </th>
                }
              </tr>
            </thead>
            <tbody>
              @for (c of ctlView.rows(); track c.name) {
                @let open = openCtl().has(c.name) || !!q().trim();
                <tr class="ctlhead" (click)="toggleCtl(c.name)" [attr.aria-expanded]="open">
                  <td [attr.colspan]="allRoles().length + 2">
                    <span class="caret">{{ open ? '▾' : '▸' }}</span>
                    <b>{{ ctlLabel(c.name) }}</b>
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
                      @for (r of allRoles(); track r.name) {
                        <td class="ov-cell">@if (r.permissions.includes(e.cell)) {<span class="tick">✓</span>} @else {<span class="none">—</span>}</td>
                      }
                    </tr>
                  }
                }
              } @empty {
                <tr><td class="muted pad" [attr.colspan]="allRoles().length + 2">No endpoints match.</td></tr>
              }
            </tbody>
          </table>
        </div>
        <mp-pager class="pg" [view]="ctlView" />
        } @else {
        <div class="overview-wrap">
          <table class="overview">
            <thead>
              <tr>
                <th class="resource-col">What</th>
                @for (r of allRoles(); track r.name) {
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
              @for (x of rowView.rows(); track x.row.id; let i = $index) {
                @let row = x.row;
                @if (x.first || i === 0) {
                  <tr class="grow"><td [attr.colspan]="allRoles().length + 1">{{ x.label }}@if (!x.first) {<span class="muted small"> (continued)</span>}</td></tr>
                }
                  <tr>
                    <td class="resource-cell">
                      <span class="rdesc">{{ row.description || row.id }}</span>
                      <span class="mono muted small">{{ row.id }}</span>
                    </td>
                    @for (r of allRoles(); track r.name) {
                      <td class="ov-cell">
                        @for (c of grants(r, row); track c.verb) {
                          <span class="chip-v {{ c.kind }}" [title]="c.kind === 'own' ? 'on ' + r.name : 'inherited from ' + c.from">@if (c.privileged) {<b class="flag">⚑</b>}{{ c.label }}</span>
                        } @empty {<span class="none">—</span>}
                      </td>
                    }
                  </tr>
              } @empty {
                <tr><td class="muted pad" [attr.colspan]="allRoles().length + 1">No rows match.</td></tr>
              }
            </tbody>
          </table>
        </div>
        <mp-pager class="pg" [view]="rowView" />
        }
      </div>
    }
  `,
  styles: [`
    .viewbar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 10px; }
    .viewbar .grow { flex: 1; }
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
    .pg { padding: 0 12px 12px; }
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
    .lens { display: inline-flex; gap: 6px; align-items: center; font-size: 14px; font-weight: 600; }
    .lens select { width: auto; }
    .overview.roles td { white-space: normal; }
    .overview.roles th { text-align: left; }
    .overview.roles .resource-cell span { display: block; }
    .rrow { cursor: pointer; }
    .rrow:hover td { background: var(--a-panel-2); }
    .business-tree { display: grid; gap: 10px; padding: 16px; }
    .business-tree h3 { margin: 14px 0 2px; }
    .menu-node { border: 1px solid var(--a-border); border-radius: 8px; overflow: hidden; }
    .menu-head, .screen-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; padding: 9px 12px; }
    .menu-head { background: var(--a-panel-2); }
    .menu-head b { margin-right: auto; }
    .screen-node { padding-left: 16px; border-top: 1px solid var(--a-border); }
    .screen-head span:first-child { margin-right: auto; }
    .screen-node .chips { padding: 0 12px 10px; }
    details { padding: 12px 16px; border-top: 1px solid var(--a-border); }
    summary { cursor: pointer; font-weight: 700; }
    .meta { display: flex; gap: 14px; flex-wrap: wrap; color: var(--a-dim); margin-bottom: 8px; align-items: center; font-size: 13px; }
    .tag { padding: 1px 8px; border-radius: 999px; border: 1px solid #d6a43a; color: #f4d58d; }
    .group { font-weight: 700; color: var(--a-teal); margin: 16px 0 4px; font-size: 16px; }
    .erow { display: grid; grid-template-columns: minmax(240px, 360px) 1fr; gap: 14px; padding: 8px 0; border-bottom: 1px solid var(--a-border); align-items: start; }
    @media (max-width: 700px) { .erow { grid-template-columns: 1fr; gap: 4px; } }
    .rlabel { font-size: 15px; }
    .chips { display: flex; flex-wrap: wrap; }
    .mono { font-family: var(--font-mono, monospace); }
    .actions { position: sticky; bottom: 0; display: flex; gap: 8px; align-items: center; justify-content: flex-end; margin-top: 10px; padding: 8px 0; background: var(--a-bg, #0b0b0b); }
    .muted { color: var(--a-dim); }
    .create .ptitle { font-size: 16px; font-weight: 700; margin-bottom: 4px; }
    .form { display: grid; gap: 12px; max-width: 720px; margin: 10px 0; }
    .form label, .form .lbl { display: grid; gap: 4px; font-size: 14px; }
    .picks { display: flex; flex-wrap: wrap; gap: 6px 14px; }
    .pick { display: inline-flex !important; gap: 6px; align-items: center; font-size: 14px; }
    .pick input { width: auto; margin: 0; }
    .danger-row { display: flex; gap: 10px; align-items: center; margin: 6px 0; }
    .small { font-size: 12.5px; }
  `],
})
export class PermissionMatrix implements OnInit {
  private readonly http = inject(HttpClient);
  readonly scopes = SCOPES;
  readonly roles = signal<RbacRoleView[]>([]);
  readonly resources = signal<RbacResourceView[]>([]);
  /** No org-type tabs: one matrix of every standard role over every row from the code. */
  readonly scope = signal<ScopeId>('PLATFORM');
  /** Actions hide themselves without their permission (*mpPerm). */
  readonly access = inject(AccessService);
  readonly q = signal('');
  readonly showAll = signal(false);
  /** Overview: grouped by controller (each expandable) or the permission grid. */
  readonly view = signal<'role' | 'controller' | 'permission'>('role');
  readonly viewOpts: TabOption<'role' | 'controller' | 'permission'>[] = [
    { value: 'role', label: 'By role' }, { value: 'controller', label: 'By controller' }, { value: 'permission', label: 'By permission' }];
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
  /** "OrgAuthAccessController" -> "Org auth access" */
  ctlLabel(name: string) {
    const t = name.replace(/Controller$/, '').replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase();
    return t.charAt(0).toUpperCase() + t.slice(1);
  }
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
  /** The new role's own cells, picked in the builder. */
  readonly draftCells = signal<Set<string>>(new Set());
  readonly createError = signal('');
  /** The widgets the selected portal's build registered (GET /api/ui/widgets), for the new-role grid. */
  readonly draftWidgets = signal<RegisteredWidgetInfo[] | null>(null);
  /** Menus › submenus › widgets of the portal, each with View / Create / Modify / Approve. */
  readonly draftGrid = computed(() => buildOperationsTree(this.scope(), this.draftWidgets() ?? [], { nav: this.navEnabled() }));
  /** What the new role gets from the roles it builds on. */
  readonly draftInherited = computed(() => {
    const out = new Set<string>();
    for (const n of this.draftBases()) for (const c of this.byName().get(n)?.permissions ?? []) out.add(c);
    return out;
  });
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
  /** The rows the builder may give: every row some endpoint uses, with its verbs. */
  /** RBAC knows the menu cells (nav.* rows): each submenu then switches on its own. */
  readonly navEnabled = computed(() => this.resources().some((r) => r.id.startsWith('nav.') && r.used.length));
  readonly palette = computed<PaletteRow[]>(() => this.resources().filter((r) => r.used.length)
    .map((r) => ({ id: r.id, description: r.description || r.id, verbs: r.used })));
  canEditTree(): boolean { return !!this.editing() && !this.editing()!.locked && this.access.can('rbac.role:modify') && !this.busy(); }
  /** The edited role's inherited cells (shown in the builder, changed on their source role). */
  readonly inheritedCells = computed(() => new Set([...this.states()].filter(([, st]) => st === 'inh').map(([c]) => c)));
  setWork(next: Set<string>) {
    if (!this.canEditTree()) return;
    this.work.set(next);
    this.saved.set(false);
  }

  /** Rows that exist at all (some endpoint uses a cell on them). */
  private readonly liveRows = computed(() => this.resources().filter((r) => r.used.length));

  /** Does a row belong to this org type at all (its own type's row, or a generic row)? */
  private fitsType(row: RbacResourceView, scope: ScopeId) {
    return !row.tenantTypes.length || (scope !== 'PLATFORM' && row.tenantTypes.includes(scope));
  }

  /** Roles of the selected portal: platform roles for admin; org roles that fit the org type. */
  readonly scopeRoles = computed(() => {
    const s = this.scope(), rows = this.rowById();
    return this.roles()
      .filter((r) => s === 'PLATFORM' ? r.kind === 'PLATFORM' : r.kind === 'ORG')
      .filter((r) => s === 'PLATFORM' || !r.permissions.length || r.permissions.some((c) => {
        const row = rows.get(c.split(':')[0]);
        return !row || this.fitsType(row, s);
      }))
      .sort((a, b) => this.rank(a) - this.rank(b) || a.name.localeCompare(b.name));
  });

  /** The landing page shows every role (the portal is chosen per role, in its editor). */
  readonly allRoles = computed(() => [...this.roles()].sort((a, b) =>
    (a.kind === 'PLATFORM' ? 0 : 1) - (b.kind === 'PLATFORM' ? 0 : 1) || this.rank(a) - this.rank(b) || a.name.localeCompare(b.name)));

  /** The portal a role is meant for: Admin for platform roles; for org roles the org
   *  portal where its cells draw the most screens (one portal per role). */
  portalFor(r: RbacRoleView): ScopeId {
    if (r.kind === 'PLATFORM') return 'PLATFORM';
    let best: ScopeId = 'SUPPLIER', most = -1;
    for (const p of ORG_PORTALS) {
      const n = this.shownScreens(p, r);
      if (n > most) { best = p; most = n; }
    }
    return best;
  }
  private shownScreens(p: ScopeId, r: RbacRoleView): number {
    return buildTree(p, this.palette(), new Set(r.permissions), undefined, { nav: this.navEnabled() }).reduce((n, m) => n + m.screens.filter((s) => s.visible).length, 0);
  }
  /** Portals the editor may draw a role in: Admin for platform roles, the org portals otherwise. */
  lensScopes(r: RbacRoleView): Scope[] {
    return SCOPES.filter((s) => (r.kind === 'PLATFORM') === (s.id === 'PLATFORM'));
  }

  /** "By role" rows: each role, its portal and how much of that portal it shows. */
  readonly roleRows = computed(() => {
    const q = this.q().trim().toLowerCase();
    return this.allRoles()
      .filter((r) => !q || [r.name, r.description ?? '', this.roleLabel(r.name)].some((x) => x.toLowerCase().includes(q)))
      .map((role) => {
        const portal = this.portalFor(role);
        const tree = buildTree(portal, this.palette(), new Set(role.permissions), undefined, { nav: this.navEnabled() });
        return { role, portalLabel: SCOPES.find((s) => s.id === portal)!.label,
          menus: tree.filter((m) => m.visible).length,
          screens: tree.reduce((n, m) => n + m.screens.filter((s) => s.visible).length, 0) };
      });
  });

  /** Row ids some role holds (in the editor: some role of the portal). */
  private readonly heldRows = computed(() => {
    const out = new Set<string>();
    for (const r of this.editing() ? this.scopeRoles() : this.allRoles()) for (const c of r.permissions) out.add(c.split(':')[0]);
    return out;
  });

  private readonly visibleRows = computed(() => {
    const ed = this.editing();
    const s = ed ? this.scope() : 'PLATFORM', q = this.q().trim().toLowerCase(), held = this.heldRows(), all = this.showAll();
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
  /** the overviews page through their own filtered lists (the filter box above does the searching) */
  readonly roleView = new TableView(() => this.roleRows(), undefined, 25);
  readonly ctlView = new TableView(() => this.controllers(), undefined, 25);
  readonly rowView = new TableView(
    () => this.groups().flatMap((g) => g.rows.map((row, k) => ({ label: g.label, row, first: k === 0 }))), undefined, 25);

  readonly rowCount = computed(() => this.visibleRows().length);

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

  openRole(name: string) {
    if (this.editingName() === name) return;
    if (this.dirtyCount() && !this.confirmDiscard()) return;
    const r = this.byName().get(name);
    if (!r) return;
    this.creating.set(false);
    this.scope.set(this.portalFor(r));
    this.editingName.set(name);
    this.work.set(new Set(r.own));
    this.saveError.set('');
    this.saved.set(false);
  }

  closeEditor() {
    if (this.dirtyCount() && !this.confirmDiscard()) return;
    this.editingName.set(null);
    // no role open: nothing is pending (else the next role opened reads as "unsaved")
    this.work.set(new Set());
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
    this.draftCells.set(new Set());
    this.createError.set('');
    this.creating.set(true);
    void this.loadDraftWidgets();
  }

  private async loadDraftWidgets() {
    const portal = this.scope();
    this.draftWidgets.set(null);
    try {
      const w = await firstValueFrom(this.http.get<RegisteredWidgetInfo[]>('/api/ui/widgets', { params: { portal } }));
      if (this.scope() === portal) this.draftWidgets.set(w);
    } catch {
      this.draftWidgets.set([]);
      this.createError.set('The widget registry could not be read; no widgets to pick from.');
    }
  }

  /** The new role's portal is the page's portal: switching it re-scopes the roles to build on. */
  setDraftPortal(s: ScopeId) {
    if (s === this.scope()) return;
    if (this.draftCells().size && !confirm('Switching the portal clears the menus and actions picked so far. Continue?')) return;
    this.scope.set(s);
    this.draftBases.set(new Set());
    this.draftCopy.set('');
    this.draftCells.set(new Set());
    if (this.creating()) void this.loadDraftWidgets();
  }

  /** Starting from a copy fills the builder with that role's own actions (still editable). */
  setDraftCopy(name: string) {
    this.draftCopy.set(name);
    this.draftCells.set(new Set(this.byName().get(name)?.own ?? []));
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
    const kind = this.scope() === 'PLATFORM' ? 'PLATFORM' : 'ORG';
    this.busy.set(true);
    this.createError.set('');
    try {
      await firstValueFrom(this.http.post<RbacRoleView>('/api/admin/rbac/roles', {
        name, description: this.draftDesc().trim(), kind,
        inherits: [...this.draftBases()], permissions: [...this.draftCells()],
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
