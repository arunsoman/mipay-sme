/**
 * The "new role by widget" model: a portal's menus › submenus › widgets, and for each widget the
 * four operations (View, Create, Modify, Approve - core/manifest.ts OPERATIONS) a role may be
 * given on it.
 *
 * Where a widget sits: until layouts are stored (portal-builder.md phase 5) a widget is placed
 * under every submenu whose rows (PORTAL_PERMISSION_CATALOGUE) overlap the rows it requires.
 *
 * What a tick grants (the role's cells):
 *  - View:    the widget's `requires` (a bare row becomes "row:read"), plus the submenu's menu
 *             cell when RBAC knows menu cells (`nav`), so the role really sees the submenu;
 *  - Create / Modify / Approve: the cells of the widget's actions whose command performs that
 *             operation (`commandOperations`), plus View - a role cannot act on what it can't see.
 * The state is the role's set of cells, so widgets that share a cell switch together
 * (portal-builder.md decision 3), and cells from roles it builds on show ticked and locked.
 *
 * Pure TypeScript, no Angular.
 */
import { PORTAL_PERMISSION_CATALOGUE } from '../web-core/portal-permission-catalogue';
import type { BusinessPortal } from '../web-core/portal-permission-catalogue';
import { navCellOf } from './role-menu-model';

export const ROLE_OPERATIONS = ['view', 'create', 'modify', 'approve'] as const;
export type RoleOperation = (typeof ROLE_OPERATIONS)[number];
export const OPERATION_LABEL: Record<RoleOperation, string> = { view: 'View', create: 'Create', modify: 'Modify', approve: 'Approve' };

/** What the registry serves per widget (GET /api/ui/widgets): the fields this model reads. */
export interface RegisteredWidgetInfo {
  id: string;
  label: string;
  description?: string | null;
  requires: string[];
  actions: { id: string; label: string; cell: string; command?: string | null }[];
  operations?: string[] | null;
  commandOperations?: Record<string, string> | null;
}

export type OpState = 'unsupported' | 'off' | 'own' | 'inherited';

export interface GridWidget {
  id: string;
  label: string;
  description: string;
  /** The cells each supported operation grants (empty for an unsupported one). */
  cells: Record<RoleOperation, string[]>;
  supported: Record<RoleOperation, boolean>;
}
export interface GridSubmenu { key: string; id: string; label: string; widgets: GridWidget[] }
export interface GridMenu { id: string; label: string; submenus: GridSubmenu[] }

const isOp = (o: string): o is RoleOperation => (ROLE_OPERATIONS as readonly string[]).includes(o);
const asCell = (p: string) => (p.includes(':') ? p : `${p}:read`);
const rowOf = (p: string) => p.split(':')[0];

/** The cells each operation of a widget grants under one submenu. */
export function widgetCells(w: RegisteredWidgetInfo, submenuKey: string | null, nav: boolean): GridWidget {
  const supported = { view: false, create: false, modify: false, approve: false } as Record<RoleOperation, boolean>;
  for (const o of w.operations ?? []) if (isOp(o)) supported[o] = true;
  const view = [...new Set([...w.requires.map(asCell), ...(nav && submenuKey ? [navCellOf(submenuKey)] : [])])];
  const cells = { view: supported.view ? view : [], create: [], modify: [], approve: [] } as Record<RoleOperation, string[]>;
  for (const op of ['create', 'modify', 'approve'] as const) {
    if (!supported[op]) continue;
    const own = w.actions.filter((a) => a.command && w.commandOperations?.[a.command] === op).map((a) => a.cell);
    cells[op] = [...new Set([...view, ...own])];
  }
  return { id: w.id, label: w.label, description: w.description ?? '', cells, supported };
}

/** The portal's menus › submenus, each with the widgets placed on it (submenus without one are left out). */
export function buildOperationsTree(portal: BusinessPortal, widgets: readonly RegisteredWidgetInfo[],
                                    opts: { nav?: boolean } = {}): GridMenu[] {
  const out: GridMenu[] = [];
  for (const m of PORTAL_PERMISSION_CATALOGUE[portal] ?? []) {
    const submenus: GridSubmenu[] = [];
    for (const sc of m.screens) {
      const rows = new Set(sc.resources);
      const key = `${portal}/${m.id}/${sc.id}`;
      const here = widgets.filter((w) => w.requires.some((r) => rows.has(rowOf(r))))
        .map((w) => widgetCells(w, key, !!opts.nav));
      if (here.length) submenus.push({ key, id: sc.id, label: sc.label, widgets: here });
    }
    if (submenus.length) out.push({ id: m.id, label: m.label, submenus });
  }
  return out;
}

/** Only the widgets on which `held` ticks at least one operation (a person's or role's read-only view). */
export function heldOnly(menus: readonly GridMenu[], held: ReadonlySet<string>): GridMenu[] {
  const none = new Set<string>();
  const out: GridMenu[] = [];
  for (const m of menus) {
    const submenus = m.submenus
      .map((s) => ({ ...s, widgets: s.widgets.filter((w) => ROLE_OPERATIONS.some((o) => opState(w, o, held, none) === 'own')) }))
      .filter((s) => s.widgets.length);
    if (submenus.length) out.push({ ...m, submenus });
  }
  return out;
}

/** How an operation shows for a role holding `selected` (own) and `inherited` cells. */
export function opState(w: GridWidget, op: RoleOperation, selected: ReadonlySet<string>, inherited: ReadonlySet<string>): OpState {
  if (!w.supported[op]) return 'unsupported';
  const cells = w.cells[op];
  if (!cells.length) return 'off';
  if (cells.every((c) => inherited.has(c))) return 'inherited';
  return cells.every((c) => selected.has(c) || inherited.has(c)) ? 'own' : 'off';
}

/**
 * Tick or untick one operation. Ticking adds its cells (View comes along). Unticking View
 * takes the widget's every cell; unticking another operation takes only the cells that View
 * does not need. Inherited cells are never touched.
 */
export function toggleOperation(w: GridWidget, op: RoleOperation, on: boolean, selected: ReadonlySet<string>,
                                inherited: ReadonlySet<string> = new Set()): Set<string> {
  const out = new Set(selected);
  if (!w.supported[op]) return out;
  if (on) {
    for (const c of w.cells[op]) if (!inherited.has(c)) out.add(c);
    return out;
  }
  const keep = op === 'view' ? new Set<string>() : new Set(w.cells.view);
  const drop = op === 'view' ? ROLE_OPERATIONS.flatMap((o) => w.cells[o]) : w.cells[op];
  for (const c of drop) if (!keep.has(c)) out.delete(c);
  return out;
}
