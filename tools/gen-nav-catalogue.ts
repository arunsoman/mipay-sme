/**
 * Writes the menu cells (one RBAC row per portal submenu, verb read) for the backend to
 * register with RBAC: backend/src/main/resources/rbac/nav-catalogue.json.
 * Source of truth: frontend/projects/shared/web-core/portal-permission-catalogue.ts.
 *
 *   node --experimental-strip-types tools/gen-nav-catalogue.ts          # write
 *   node --experimental-strip-types tools/gen-nav-catalogue.ts --check  # fail when stale
 *
 * Run it after changing the catalogue's menus or submenus (docs/plans/role-menu-builder.md).
 */
import fs from 'node:fs';
import { allNavRows } from '../frontend/projects/shared/web-core/portal-permission-catalogue.ts';

const out = new URL('../backend/src/main/resources/rbac/nav-catalogue.json', import.meta.url);
const json = JSON.stringify(allNavRows(), null, 1) + '\n';
if (process.argv.includes('--check')) {
  const now = fs.existsSync(out) ? fs.readFileSync(out, 'utf8') : '';
  if (now !== json) { console.error('nav-catalogue.json is stale: run node --experimental-strip-types tools/gen-nav-catalogue.ts'); process.exit(1); }
  console.log('nav-catalogue.json is current');
} else {
  fs.writeFileSync(out, json);
  console.log(`wrote ${allNavRows().length} menu cells to backend/src/main/resources/rbac/nav-catalogue.json`);
}
