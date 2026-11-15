/**
 * Writes, from the one menu catalogue, what the backend needs to know about portal menus:
 *
 *  - backend/src/main/resources/rbac/nav-catalogue.json: the menu cells (one RBAC row per
 *    portal submenu, verb read) the backend registers with RBAC (role-menu-builder.md);
 *  - backend/src/main/resources/rbac/feature-catalogue.json: every org portal's top-level menus
 *    with their screens' permission rows and menu cells. Each menu is one **feature** an org can
 *    be given (admin-console.md): switching it on gives the org's admin those rows and cells.
 *
 * Source of truth: frontend/projects/shared/web-core/portal-permission-catalogue.ts.
 *
 *   node --experimental-strip-types tools/gen-nav-catalogue.ts          # write
 *   node --experimental-strip-types tools/gen-nav-catalogue.ts --check  # fail when stale
 *
 * Run it after changing the catalogue's menus or submenus.
 */
import fs from 'node:fs';
import {
  allNavRows, navRow, PORTAL_PERMISSION_CATALOGUE, type BusinessPortal,
} from '../frontend/projects/shared/web-core/portal-permission-catalogue.ts';

/** The org types an org can have, each drawn by the portal of the same name. */
const ORG_PORTALS: BusinessPortal[] = ['MANUFACTURER', 'DISTRIBUTOR', 'SUPPLIER', 'MERCHANT', 'LENDER'];

function features() {
  const out: Record<string, unknown[]> = {};
  for (const p of ORG_PORTALS) {
    out[p] = PORTAL_PERMISSION_CATALOGUE[p].map((m) => ({
      id: m.id,
      label: m.label,
      screens: m.screens.map((s) => ({ id: s.id, label: s.label, resources: s.resources, nav: navRow(p, m.id, s.id) })),
    }));
  }
  return out;
}

const outputs: [URL, string][] = [
  [new URL('../backend/src/main/resources/rbac/nav-catalogue.json', import.meta.url), JSON.stringify(allNavRows(), null, 1) + '\n'],
  [new URL('../backend/src/main/resources/rbac/feature-catalogue.json', import.meta.url), JSON.stringify(features(), null, 1) + '\n'],
];

if (process.argv.includes('--check')) {
  let stale = false;
  for (const [file, json] of outputs) {
    const now = fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : '';
    if (now !== json) { console.error(`${file.pathname.split('/').pop()} is stale: run node --experimental-strip-types tools/gen-nav-catalogue.ts`); stale = true; }
  }
  if (stale) process.exit(1);
  console.log('nav-catalogue.json and feature-catalogue.json are current');
} else {
  for (const [file, json] of outputs) fs.writeFileSync(file, json);
  console.log(`wrote ${allNavRows().length} menu cells and the org portals' features to backend/src/main/resources/rbac/`);
}
