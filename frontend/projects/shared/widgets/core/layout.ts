/**
 * A portal's layout (docs/plans/portal-builder.md §5): menus → submenus (screens) → placed
 * widgets, with every visible text translatable.
 *
 * i18n rules (validateLayout):
 *  - every label is a MESSAGE KEY, generated from the ids (portal.<portal>.<menu>[.<screen>]),
 *    never free text - so translations survive relabelling and keys never collide;
 *  - `en` is the source locale and always present; every enabled locale needs every message;
 *  - placeholders ({name}) must match the source message; no markup; no stray whitespace;
 *  - long labels warn (the sidebar must fit a phone);
 *  - the export splits the layout (keys only) from one message bundle per locale.
 *
 * Screens carry an immutable `uid` (decision 4): screens seeded from today's catalogue keep their
 * existing menu cell (nav.<portal>_<menu>_<screen>); new screens get nav.<portal>_<uid>, so moving
 * a screen to another menu never changes who can see it.
 *
 * Pure TypeScript: the workbench builder, the registry tool and CI read it.
 */
import type { BusinessPortal } from '../../web-core/portal-permission-catalogue';
import { PORTAL_PERMISSION_CATALOGUE, navRow } from '../../web-core/portal-permission-catalogue';
import type { Tier } from './grid';
import { TIERS, spanFor } from './grid';
import type { WidgetDef } from './manifest';
import { requiredList } from './manifest';
import { defaultLayout } from './default-layouts';

export const SOURCE_LOCALE = 'en';
/** Locales offered in the builder (BCP 47). Namibia first; add more with "Add locale". */
export const SUGGESTED_LOCALES = ['en', 'af', 'de', 'ng', 'pt', 'fr'];
export const RTL_LOCALES = ['ar', 'he', 'fa', 'ur'];
const LOCALE = /^[a-z]{2,3}(-[A-Z]{2}|-[A-Z][a-z]{3})?$/;
const ID = /^[a-z][a-z0-9-]{0,39}$/;
const MAX_MENU_LABEL = 28;
const MAX_SCREEN_LABEL = 36;

export interface I18nText { readonly key: string; messages: Record<string, string> }

export interface PlacedWidget {
  instanceId: string;
  widgetId: string;
  major: number;
  config: Record<string, unknown>;
  span?: Partial<Record<Tier, number>>;
}

export interface LayoutScreen {
  /** Immutable once published (decision 4). */
  readonly uid: string;
  /** True when seeded from today's catalogue: keeps its legacy menu cell. */
  readonly legacy?: boolean;
  id: string;
  label: I18nText;
  items: PlacedWidget[];
}

export interface LayoutMenu { id: string; icon: string; label: I18nText; screens: LayoutScreen[] }

export interface PortalLayout {
  format: 1;
  portal: BusinessPortal;
  version: number;
  locales: string[];
  menus: LayoutMenu[];
}

export interface Finding { level: 'error' | 'warning'; where: string; text: string }

// ------------------------------------------------------------------ keys, cells, routes

export function menuKey(portal: BusinessPortal, menuId: string): string {
  return `portal.${portal.toLowerCase()}.${menuId}`;
}
export function screenKey(portal: BusinessPortal, menuId: string, screenId: string): string {
  return `portal.${portal.toLowerCase()}.${menuId}.${screenId}`;
}

/** The screen's menu cell (decision 4). */
export function screenNavCell(portal: BusinessPortal, s: LayoutScreen): string {
  if (s.legacy) {
    // frozen at seeding: "<PORTAL>/<menu>/<screen>" as the catalogue had it, whatever the ids are now
    const [, m, sc] = s.uid.split('/');
    return `${navRow(portal, m, sc)}:read`;
  }
  return `nav.${portal.toLowerCase()}_${s.uid.replace(/-/g, '_')}:read`;
}

export function screenRoute(menuId: string, screenId: string): string {
  return `/p/${menuId}/${screenId}`;
}

/** The permissions that show a screen: the union of its widgets' `requires` (§4). */
export function screenResources(s: LayoutScreen, defs: ReadonlyMap<string, WidgetDef<any>>): string[] {
  const out = new Set<string>();
  for (const it of s.items) { const d = defs.get(it.widgetId); if (d) requiredList(d).forEach((r) => out.add(r)); }
  return [...out];
}

export function newUid(): string {
  const r = (globalThis.crypto?.randomUUID?.() ?? Math.random().toString(36).slice(2) + Date.now().toString(36));
  return 's' + r.replace(/-/g, '').slice(0, 10);
}

/** Re-key every label from the current ids (keys are derived, never typed). */
export function rekey(layout: PortalLayout): PortalLayout {
  return {
    ...layout,
    menus: layout.menus.map((m) => ({
      ...m,
      label: { key: menuKey(layout.portal, m.id), messages: m.label.messages },
      screens: m.screens.map((s) => ({ ...s, label: { key: screenKey(layout.portal, m.id, s.id), messages: s.label.messages } })),
    })),
  };
}

// ------------------------------------------------------------------ seed

/** Start from the built-in layout (default-layouts.ts); for a portal without one, today's menus (PORTAL_PERMISSION_CATALOGUE), English only, no widgets placed. */
export function seedLayout(portal: BusinessPortal): PortalLayout {
  // the built-in layout when the portal has one (it replaced the coded menus); else today's catalogue
  const built = defaultLayout(portal);
  if (built) return built;
  return {
    format: 1, portal, version: 1, locales: [SOURCE_LOCALE],
    menus: PORTAL_PERMISSION_CATALOGUE[portal].map((m) => ({
      id: m.id, icon: '',
      label: { key: menuKey(portal, m.id), messages: { [SOURCE_LOCALE]: m.label } },
      screens: m.screens.map((s) => ({
        uid: `${portal}/${m.id}/${s.id}`, legacy: true, id: s.id,
        label: { key: screenKey(portal, m.id, s.id), messages: { [SOURCE_LOCALE]: s.label } },
        items: [],
      })),
    })),
  };
}

// ------------------------------------------------------------------ validation

const placeholders = (s: string) => [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort().join(',');

function checkText(f: Finding[], where: string, t: I18nText, locales: string[], max: number) {
  const src = t.messages[SOURCE_LOCALE] ?? '';
  for (const loc of locales) {
    const v = t.messages[loc];
    if (v === undefined || v.trim() === '') {
      f.push({ level: 'error', where, text: `${loc}: translation missing for ${t.key}` });
      continue;
    }
    if (v !== v.trim() || /\s{2,}/.test(v)) f.push({ level: 'error', where, text: `${loc}: stray whitespace in "${v}"` });
    if (/[<>]/.test(v)) f.push({ level: 'error', where, text: `${loc}: no markup in labels ("${v}")` });
    if (loc !== SOURCE_LOCALE && src && placeholders(v) !== placeholders(src)) {
      f.push({ level: 'error', where, text: `${loc}: placeholders differ from en ("${v}" vs "${src}")` });
    }
    if (v.length > max) f.push({ level: 'warning', where, text: `${loc}: "${v}" is ${v.length} characters; over ${max} wraps on a phone sidebar` });
  }
}

/** Everything wrong with a layout; errors block export. */
export function validateLayout(layout: PortalLayout, defs: ReadonlyMap<string, WidgetDef<any>>): Finding[] {
  const f: Finding[] = [];
  if (!layout.locales.includes(SOURCE_LOCALE)) f.push({ level: 'error', where: 'locales', text: 'en (the source locale) must be enabled' });
  for (const l of layout.locales) if (!LOCALE.test(l)) f.push({ level: 'error', where: 'locales', text: `"${l}" is not a BCP 47 locale (en, af, pt-BR)` });
  if (!layout.menus.length) f.push({ level: 'warning', where: 'menus', text: 'the portal has no menus' });

  const keys = new Set<string>();
  const menuIds = new Set<string>();
  const uids = new Set<string>();
  for (const m of layout.menus) {
    const mw = `menu ${m.id}`;
    if (!ID.test(m.id)) f.push({ level: 'error', where: mw, text: `id "${m.id}" must be lower case letters, digits and dashes` });
    if (menuIds.has(m.id)) f.push({ level: 'error', where: mw, text: `menu id "${m.id}" is used twice` });
    menuIds.add(m.id);
    if (keys.has(m.label.key)) f.push({ level: 'error', where: mw, text: `message key ${m.label.key} is used twice` });
    keys.add(m.label.key);
    checkText(f, mw, m.label, layout.locales, MAX_MENU_LABEL);
    if (!m.screens.length) f.push({ level: 'warning', where: mw, text: 'no submenus: the menu will not show' });

    const screenIds = new Set<string>();
    for (const s of m.screens) {
      const sw = `${m.id} › ${s.id}`;
      if (!ID.test(s.id)) f.push({ level: 'error', where: sw, text: `id "${s.id}" must be lower case letters, digits and dashes` });
      if (screenIds.has(s.id)) f.push({ level: 'error', where: sw, text: `submenu id "${s.id}" is used twice in ${m.id}` });
      screenIds.add(s.id);
      if (uids.has(s.uid)) f.push({ level: 'error', where: sw, text: `uid ${s.uid} is used twice` });
      uids.add(s.uid);
      if (keys.has(s.label.key)) f.push({ level: 'error', where: sw, text: `message key ${s.label.key} is used twice` });
      keys.add(s.label.key);
      checkText(f, sw, s.label, layout.locales, MAX_SCREEN_LABEL);
      if (!s.items.length) f.push({ level: 'warning', where: sw, text: 'empty screen: place at least one widget' });
      for (const it of s.items) {
        const d = defs.get(it.widgetId);
        if (!d) { f.push({ level: 'error', where: sw, text: `widget ${it.widgetId} is not in the registry` }); continue; }
        if (!d.contexts.includes(layout.portal)) f.push({ level: 'error', where: sw, text: `${d.id} does not work in the ${layout.portal} portal` });
        if (Number(d.version.split('.')[0]) !== it.major) f.push({ level: 'warning', where: sw, text: `${d.id} is placed at major ${it.major}, the registry has ${d.version}` });
        for (const t of TIERS) {
          const want = it.span?.[t];
          const z = d.size[t];
          if (want === undefined || z.kind === 'hidden') continue;
          if (want < z.min || want > z.max) f.push({ level: 'error', where: sw, text: `${d.id} @${t}: span ${want} is outside ${z.min}..${z.max}` });
        }
      }
    }
  }
  return f;
}

// ------------------------------------------------------------------ export

export interface LayoutExport {
  /** layouts/<portal>.json - keys only, no texts. */
  layout: unknown;
  /** i18n/<portal>.<locale>.json - flat key → text, one per locale. */
  bundles: Record<string, Record<string, string>>;
}

export function exportLayout(layout: PortalLayout): LayoutExport {
  const bundles: Record<string, Record<string, string>> = {};
  for (const l of layout.locales) bundles[l] = {};
  const put = (t: I18nText) => { for (const l of layout.locales) bundles[l][t.key] = t.messages[l] ?? ''; };
  const menus = layout.menus.map((m) => {
    put(m.label);
    return {
      id: m.id, icon: m.icon, label: m.label.key,
      screens: m.screens.map((s) => {
        put(s.label);
        return {
          uid: s.uid, id: s.id, label: s.label.key, route: screenRoute(m.id, s.id), navCell: screenNavCell(layout.portal, s),
          items: s.items.map((it, order) => ({ ...it, order })),
        };
      }),
    };
  });
  return { layout: { format: layout.format, portal: layout.portal, version: layout.version, locales: layout.locales, menus }, bundles };
}

/** The span a placed widget gets at a tier (layout override within the widget's bounds). */
export function placedSpan(def: WidgetDef<any>, it: PlacedWidget, tier: Tier): number | null {
  return spanFor(def.size[tier], it.span?.[tier]);
}
