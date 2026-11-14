/**
 * The role builder's model (docs/plans/role-menu-builder.md): a portal's menus and
 * submenus, as its sidebar draws them, over a set of held cells.
 *
 * A submenu is visible for a role exactly when the portal shell would draw it:
 *  - its screen's rows pass `liveNavigation` (any held verb on any of its rows), AND
 *  - its route passes the route gate (menu.ts perm list, OR semantics), AND
 *  - once the role is menu-scoped for the portal (holds any menu cell of it), the
 *    screen's own menu cell `nav.<portal>_<menu>_<screen>:read` (decision 2026-11-14).
 *
 * With menu cells (`nav: true`) every submenu switches on its own: switching it on adds its
 * menu cell and the fewest permission cells that let the shell draw it; switching it off
 * takes away its menu cell and the permission cells no other switched-on submenu needs.
 * The first change to a role that has no menu cells yet first gives it the menu cell of
 * every submenu it shows today, so nothing else changes.
 *
 * Without menu cells (`nav: false`, RBAC has not registered them) visibility follows the
 * permissions alone (decision 1a) and submenus that share a permission switch together.
 *
 * Pure TypeScript, no Angular: tested with `node --experimental-strip-types`.
 */
import { LIVE_SCREEN_ROUTES, navRow, navScoped, PORTAL_PERMISSION_CATALOGUE } from '../web-core/portal-permission-catalogue';
import type { BusinessPortal, PermissionMenu, PermissionScreen } from '../web-core/portal-permission-catalogue';
import { menuPerms } from '../web-core/menu';
import type { PortalId } from '../web-core/menu';

/** A grantable row: its id, plain description and the verbs that may be given on it. */
export interface PaletteRow { id: string; description: string; verbs: string[] }

export type CellState = 'own' | 'inh' | 'off';
export interface CellChip { cell: string; row: string; verb: string; rowDescription: string; state: CellState }

export interface ScreenNode {
  /** "<portal>/<menu>/<screen>" (the LIVE_SCREEN_ROUTES key). */
  key: string;
  id: string;
  label: string;
  link: string | null;
  /** live: routed today and grantable from the palette; planned: no route yet;
   *  unavailable: routed, but none of its rows can be given from this palette. */
  status: 'live' | 'planned' | 'unavailable';
  /** The rows the screen's actions live on (its resources + rows of exact gate cells). */
  rows: string[];
  chips: CellChip[];
  visible: boolean;
  /** Visible only through inherited cells: hiding it here cannot take it away. */
  lockedVisible: boolean;
  /** Other live screens of this portal that share a row with this one ("Menu › Screen"). */
  sharesWith: string[];
}

export interface MenuNode { id: string; label: string; screens: ScreenNode[]; visible: boolean; live: boolean }

/** Options: `nav` = menu cells can be given (RBAC knows the nav.* rows). */
export interface BuildOpts { nav?: boolean }

/** Which menu.ts sidebar a business portal's routes are gated by. */
export function shellOf(portal: BusinessPortal): PortalId {
  if (portal === 'PLATFORM') return 'admin';
  if (portal === 'MANUFACTURER' || portal === 'DISTRIBUTOR' || portal === 'SUPPLIER') return 'org';
  return 'sme';
}

/** Routes the shells guard without a sidebar item of their own (kept in step with
 *  org-portal app.routes.ts NON_MENU and src/app/access-map.ts). */
const EXTRA_GATES: Record<PortalId, Record<string, string[]>> = {
  admin: {},
  org: { '/supplier': ['supplier.account'] },
  sme: { '/merchant/tabs': ['merchant.tab'], '/merchant/settings': ['merchant.account'] },
};

/** The route gate of a link in a portal's shell, or null when the shell has no such route. */
export function routeGate(portal: BusinessPortal, link: string): string[] | null {
  const shell = shellOf(portal);
  const hit = menuPerms(shell).find(([l]) => l === link);
  return hit ? hit[1] : EXTRA_GATES[shell][link] ?? null;
}

const rowOf = (p: string) => p.split(':')[0];

/** The shells' `can`: "row:verb" exact, "row" any verb; OR over the list. */
export function canWith(held: Set<string>, perm: string[]): boolean {
  const rows = new Set([...held].map(rowOf));
  return perm.some((p) => (p.includes(':') ? held.has(p) : rows.has(p)));
}

/** The menu cell of a screen key ("<portal>/<menu>/<screen>"). */
export function navCellOf(key: string): string {
  const [p, m, s] = key.split('/');
  return navRow(p as BusinessPortal, m, s) + ':read';
}

interface Ctx { portal: BusinessPortal; palette: Map<string, PaletteRow>; nav: boolean }
interface Hit { m: PermissionMenu; s: PermissionScreen; key: string }

function ctxOf(portal: BusinessPortal, palette: PaletteRow[], opts?: BuildOpts): Ctx {
  return { portal, palette: new Map(palette.map((r) => [r.id, r])), nav: !!opts?.nav };
}

function screens(portal: BusinessPortal): Hit[] {
  return PORTAL_PERMISSION_CATALOGUE[portal].flatMap((m) => m.screens.map((s) => ({ m, s, key: `${portal}/${m.id}/${s.id}` })));
}

function find(portal: BusinessPortal, key: string): Hit | null {
  return screens(portal).find((h) => h.key === key) ?? null;
}

function linkOf(ctx: Ctx, h: Hit): string | null { return LIVE_SCREEN_ROUTES[h.key] ?? null; }

function gateOf(ctx: Ctx, h: Hit): string[] {
  const link = linkOf(ctx, h);
  return link ? routeGate(ctx.portal, link) ?? [] : [];
}

function rowsOfScreen(ctx: Ctx, h: Hit): string[] {
  const exact = gateOf(ctx, h).filter((g) => g.includes(':')).map(rowOf);
  return [...new Set([...h.s.resources, ...exact])];
}

function isLive(ctx: Ctx, h: Hit): boolean {
  const link = linkOf(ctx, h);
  return !!link && h.s.resources.length > 0 && routeGate(ctx.portal, link) !== null;
}

/** Visible by permissions alone (decision 1a). */
function permVisible(ctx: Ctx, h: Hit, held: Set<string>): boolean {
  return isLive(ctx, h) && canWith(held, h.s.resources) && canWith(held, gateOf(ctx, h));
}

function scopedFor(ctx: Ctx, held: Set<string>): boolean {
  return navScoped(ctx.portal, (rows) => canWith(held, rows));
}

/** Visible as the shell decides it: permissions, and the menu cell once menu-scoped. */
function shellVisible(ctx: Ctx, h: Hit, held: Set<string>): boolean {
  return permVisible(ctx, h, held) && (!scopedFor(ctx, held) || held.has(navCellOf(h.key)));
}

/** "view" when the row has it, else the first grantable verb. */
function firstCell(row: PaletteRow): string | null {
  if (!row.verbs.length) return null;
  return `${row.id}:${row.verbs.includes('read') ? 'read' : row.verbs[0]}`;
}

/** The menus and submenus of one portal over own (`selected`) and `inherited` cells. */
export function buildTree(portal: BusinessPortal, palette: PaletteRow[], selected: Set<string>,
                          inherited: Set<string> = new Set(), opts?: BuildOpts): MenuNode[] {
  const ctx = ctxOf(portal, palette, opts);
  const held = new Set([...selected, ...inherited]);
  const all = screens(portal);

  // live screens and their rows, for "also affects"
  const liveRows = all.filter((h) => isLive(ctx, h))
    .map((h) => ({ key: h.key, label: `${h.m.label} › ${h.s.label}`, rows: new Set(rowsOfScreen(ctx, h)) }));

  return PORTAL_PERMISSION_CATALOGUE[portal].map((m) => {
    const nodes = m.screens.map((s): ScreenNode => {
      const h: Hit = { m, s, key: `${portal}/${m.id}/${s.id}` };
      const rows = rowsOfScreen(ctx, h);
      const chips: CellChip[] = [];
      for (const r of rows) {
        const p = ctx.palette.get(r);
        for (const v of p?.verbs ?? []) {
          const cell = `${r}:${v}`;
          chips.push({ cell, row: r, verb: v, rowDescription: p!.description || r,
            state: selected.has(cell) ? 'own' : inherited.has(cell) ? 'inh' : 'off' });
        }
      }
      const live = isLive(ctx, h);
      const status = !live ? 'planned' : chips.length ? 'live' : 'unavailable';
      const visible = shellVisible(ctx, h, held);
      const lockedVisible = visible && (ctx.nav
        ? inherited.has(navCellOf(h.key)) && permVisible(ctx, h, inherited)
        : permVisible(ctx, h, inherited));
      const mine = new Set(rows);
      const sharesWith = status === 'live'
        ? liveRows.filter((o) => o.key !== h.key && [...o.rows].some((r) => mine.has(r))).map((o) => o.label)
        : [];
      return { key: h.key, id: s.id, label: s.label, link: linkOf(ctx, h), status, rows, chips, visible,
        lockedVisible, sharesWith };
    });
    return { id: m.id, label: m.label, screens: nodes, visible: nodes.some((s) => s.visible),
      live: nodes.some((s) => s.status === 'live') };
  });
}

/**
 * A role that holds no menu cell of the portal yet switches to menu cells on its first
 * change: it gets the menu cell of every submenu it shows today (nothing else changes).
 */
export function ensureScoped(portal: BusinessPortal, palette: PaletteRow[], selected: Set<string>,
                             inherited: Set<string>, opts?: BuildOpts): Set<string> {
  const ctx = ctxOf(portal, palette, opts);
  const held = new Set([...selected, ...inherited]);
  if (!ctx.nav || scopedFor(ctx, held)) return selected;
  const out = new Set(selected);
  for (const h of screens(portal)) if (permVisible(ctx, h, held)) out.add(navCellOf(h.key));
  return out;
}

/**
 * Show a submenu: its menu cell (with menu cells), and the fewest permission cells (view
 * first) that make the shell draw it. The route gate is met first (preferring a gate entry
 * on the screen's own rows), then the screen's rows. Unchanged when it cannot be shown.
 */
export function showScreen(portal: BusinessPortal, palette: PaletteRow[], selected: Set<string>,
                           inherited: Set<string>, key: string, opts?: BuildOpts): Set<string> {
  const ctx = ctxOf(portal, palette, opts);
  const hit = find(portal, key);
  if (!hit || !isLive(ctx, hit)) return selected;
  const out = new Set(ensureScoped(portal, palette, selected, inherited, opts));
  const held = () => new Set([...out, ...inherited]);
  const gate = gateOf(ctx, hit);
  const cellFor = (p: string): string | null => {
    if (p.includes(':')) return ctx.palette.get(rowOf(p))?.verbs.includes(p.split(':')[1]) ? p : null;
    const row = ctx.palette.get(p);
    return row ? firstCell(row) : null;
  };
  if (!canWith(held(), gate)) {
    const own = new Set(hit.s.resources);
    const ordered = [...gate.filter((g) => own.has(rowOf(g))), ...gate.filter((g) => !own.has(rowOf(g)))];
    for (const g of ordered) { const c = cellFor(g); if (c) { out.add(c); break; } }
  }
  if (!canWith(held(), hit.s.resources)) {
    for (const r of hit.s.resources) { const c = cellFor(r); if (c) { out.add(c); break; } }
  }
  if (ctx.nav) out.add(navCellOf(key));
  return shellVisible(ctx, hit, held()) ? out : selected;
}

/**
 * Hide a submenu. With menu cells: take its menu cell away, and the own permission cells on
 * its rows and gate rows that no other switched-on submenu of the portal needs. Without:
 * take away every own cell on its rows (submenus sharing them hide too).
 */
export function hideScreen(portal: BusinessPortal, palette: PaletteRow[], selected: Set<string>, key: string,
                           inherited: Set<string> = new Set(), opts?: BuildOpts): Set<string> {
  const ctx = ctxOf(portal, palette, opts);
  const hit = find(portal, key);
  if (!hit) return selected;
  if (!ctx.nav) {
    const rows = new Set(rowsOfScreen(ctx, hit));
    return new Set([...selected].filter((c) => !rows.has(rowOf(c))));
  }
  const out = new Set(ensureScoped(portal, palette, selected, inherited, opts));
  out.delete(navCellOf(key));
  const held = new Set([...out, ...inherited]);
  const needed = new Set<string>();
  for (const h of screens(portal)) {
    if (h.key === key || !held.has(navCellOf(h.key))) continue;
    rowsOfScreen(ctx, h).forEach((r) => needed.add(r));
    gateOf(ctx, h).forEach((g) => needed.add(rowOf(g)));
  }
  const mine = new Set([...rowsOfScreen(ctx, hit), ...gateOf(ctx, hit).map(rowOf)]);
  for (const c of [...out]) if (mine.has(rowOf(c)) && !needed.has(rowOf(c)) && !c.startsWith('nav.')) out.delete(c);
  return out;
}

/** Show a menu: show each of its live submenus. */
export function showMenu(portal: BusinessPortal, palette: PaletteRow[], selected: Set<string>,
                         inherited: Set<string>, menuId: string, opts?: BuildOpts): Set<string> {
  let out = selected;
  const m = PORTAL_PERMISSION_CATALOGUE[portal].find((x) => x.id === menuId);
  for (const s of m?.screens ?? []) out = showScreen(portal, palette, out, inherited, `${portal}/${menuId}/${s.id}`, opts);
  return out;
}

/** Hide a menu: hide every submenu in it. */
export function hideMenu(portal: BusinessPortal, palette: PaletteRow[], selected: Set<string>, menuId: string,
                         inherited: Set<string> = new Set(), opts?: BuildOpts): Set<string> {
  let out = selected;
  const m = PORTAL_PERMISSION_CATALOGUE[portal].find((x) => x.id === menuId);
  for (const s of m?.screens ?? []) out = hideScreen(portal, palette, out, `${portal}/${menuId}/${s.id}`, inherited, opts);
  return out;
}
