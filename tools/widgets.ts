/**
 * MiPay widget tool (docs/plans/portal-builder.md §7, §11). Run from frontend/ via npm:
 *
 *   npm run widget:new -- <area>/<name> [--label "…"] [--category "…"] [--portals SUPPLIER,PLATFORM]
 *       scaffold a widget and print what still has to be built
 *   npm run widgets:check [-- <id>]
 *       readiness report per widget: what is done, what is still open (exit 1 when anything is)
 *   npm run widgets:gen [-- --check]
 *       write registry.generated.ts (the portals' and workbench's list) and widget-manifest.json
 *       (what the registry server receives); --check fails when either is stale
 *   npm run widgets:register -- --portal SUPPLIER --bundle supplier-portal@<sha> --url <registry>
 *       post this portal's widgets to the registry server (token: WIDGET_REGISTRY_TOKEN)
 *   npm run widgets:endpoint -- GET /api/kyc/tasks
 *       what the backend says about an endpoint: controller, @Perm cell, params, body, response
 *       type, its contract, a data-sources entry and a sample answer
 *   npm run widgets:contract -- GET /api/kyc/tasks --name kyc.tasks.list --out <widget>.contract.ts
 *       write (or refresh) the endpoint's response contract into a widget's contract file
 *   npm run widgets:contract -- POST /api/kyc/tasks/{taskId}/decision --name kyc.tasks.decide --request --out …
 *       write the request-body contract of a command (what the UI must send)
 *   npm run widgets:ui [-- --json]
 *       the atoms, molecules and organisms a widget may use (it may not define its own)
 *   npm run widgets:prompt [-- GET /api/… POST /api/… …] [--out file]
 *       a self-contained prompt for ANY LLM chat: paste it, add the UI requirement, get every file
 *   npm run widgets:apply -- <answer.md> [--no-backend]
 *       write an LLM's answer into its widget folder and run every check, the tests, the
 *       screenshots and the registry validation; on failure, a repair message to paste back
 *
 * Manifests, fixtures and data sources are pure TypeScript; Node strips the types.
 */
import { registerHooks } from 'node:module';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

// Angular-style extensionless relative imports → .ts (the manifests are shared with the app)
registerHooks({
  resolve(spec, ctx, next) {
    try { return next(spec, ctx); } catch (e) {
      if ((spec.startsWith('.') || spec.startsWith('/')) && !/\.[cm]?[jt]s$|\.json$/.test(spec)) return next(spec + '.ts', ctx);
      throw e;
    }
  },
});

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const FE = path.join(ROOT, 'frontend');
const WIDGETS = path.join(FE, 'projects/shared/widgets');
const REGISTRY_TS = path.join(WIDGETS, 'registry.generated.ts');
const MANIFEST_JSON = path.join(WIDGETS, 'widget-manifest.json');
const rel = (p: string) => path.relative(FE, p);

const { manifestProblems, requiredList, cellsOf } = await import(pathToFileURL(path.join(WIDGETS, 'core/manifest.ts')).href);
const { TIERS } = await import(pathToFileURL(path.join(WIDGETS, 'core/grid.ts')).href);
const { resolveRoute, isSafeApiPath } = await import(pathToFileURL(path.join(WIDGETS, 'core/sources.ts')).href);
const { SOURCES } = await import(pathToFileURL(path.join(WIDGETS, 'data-sources.ts')).href);
const { PERMS } = await import(pathToFileURL(path.join(FE, 'projects/shared/web-core/permissions.ts')).href);
const { allNavRows } = await import(pathToFileURL(path.join(FE, 'projects/shared/web-core/portal-permission-catalogue.ts')).href);
const shapeLib = await import(pathToFileURL(path.join(WIDGETS, 'core/shape.ts')).href);
const java = await import(pathToFileURL(path.join(ROOT, 'tools/widget-lib/java.ts')).href);
const cat = await import(pathToFileURL(path.join(ROOT, 'tools/widget-lib/catalogue.ts')).href);
const BACKEND_WEB = path.join(ROOT, 'backend/src/main/java/com/mipay/sme/web');
const BACKEND_SRC = path.join(ROOT, 'backend/src/main/java');

const PORTALS = ['PLATFORM', 'LENDER', 'MERCHANT', 'MANUFACTURER', 'DISTRIBUTOR', 'SUPPLIER', 'CUSTOMER'];
/** The truth: the backend's @Perm cells (read from its Java), the trustplane-only rbac.* rows, the menu cells. */
const BACKEND_ENDPOINTS = java.scanEndpoints(path.join(ROOT, 'backend/src/main/java/com/mipay/sme/web'));
const KNOWN_CELLS = new Set<string>([
  ...BACKEND_ENDPOINTS.filter((e: { perm: string | null }) => e.perm).map((e: { perm: string }) => e.perm),
  ...PERMS.map((p: { id: string }) => p.id).filter((id: string) => id.startsWith('rbac.')),
  ...allNavRows().map((r: { id: string }) => `${r.id}:read`),
]);
/** Cells a widget's backend stub proposes (set by widgets:apply): reported as pending, not unknown. */
const PROPOSED_CELLS = new Set<string>((process.env['WIDGET_PROPOSED_CELLS'] ?? '').split(',').filter(Boolean));
const KNOWN_ROWS = new Set<string>([...KNOWN_CELLS].map((c) => c.split(':')[0]));
const REQUIRED_SCENARIOS = ['default', 'empty', 'error'];
const CATALOGUE = cat.scanCatalogue(FE);
const KNOWN_TAGS: Set<string> = cat.knownTags(CATALOGUE);
const TODO = /TODO\(widget\)/;

// ------------------------------------------------------------------ discovery

interface Found { dir: string; name: string; widgetFile: string; fixturesFile: string; componentFile: string; specFile: string }

function findWidgets(dir = WIDGETS): Found[] {
  const out: Found[] = [];
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory() && e.name !== 'core') out.push(...findWidgets(p));
    else if (e.isFile() && e.name.endsWith('.widget.ts')) {
      const name = e.name.replace(/\.widget\.ts$/, '');
      out.push({ dir, name, widgetFile: p, fixturesFile: path.join(dir, `${name}.fixtures.ts`),
        componentFile: path.join(dir, `${name}.component.ts`), specFile: path.join(dir, `${name}.spec.ts`) });
    }
  }
  return out.sort((a, b) => a.widgetFile.localeCompare(b.widgetFile));
}

async function load(f: Found): Promise<{ def: any; fixtures: any; error?: string }> {
  try {
    const def = (await import(pathToFileURL(f.widgetFile).href)).default;
    const fixtures = fs.existsSync(f.fixturesFile) ? (await import(pathToFileURL(f.fixturesFile).href)).default : null;
    return { def, fixtures };
  } catch (e) {
    return { def: null, fixtures: null, error: String((e as Error).message ?? e) };
  }
}

/** Every (name, portal) a widget can call, resolved through data-sources.ts. */
function routesOf(def: any) {
  const out: { name: string; kind: 'source' | 'command'; portal: string; method: string; path: string }[] = [];
  const missing: string[] = [];
  for (const [kind, names] of [['source', def.sources], ['command', def.commands]] as const) {
    for (const name of names) for (const portal of def.contexts) {
      const r = resolveRoute(SOURCES, name, portal);
      if (r) out.push({ name, kind, portal, method: r.method, path: r.path });
      else missing.push(`${name} (${portal})`);
    }
  }
  return { routes: out, missing };
}

// ------------------------------------------------------------------ new (the template creator)

const pascal = (s: string) => s.split(/[-_.]/).filter(Boolean).map((w) => w[0].toUpperCase() + w.slice(1)).join('');

function arg(flag: string, args: string[]): string | undefined {
  const i = args.indexOf(flag);
  return i >= 0 ? args[i + 1] : undefined;
}

function cmdNew(args: string[]) {
  const target = args.find((a) => !a.startsWith('--') && !args[args.indexOf(a) - 1]?.startsWith('--'));
  if (!target || !/^[a-z][a-z0-9]*\/[a-z][a-z0-9-]*$/.test(target)) {
    console.error('usage: npm run widget:new -- <area>/<name>   (lower case, e.g. kyc/application, trade/orders-table)');
    process.exit(2);
  }
  const [area, name] = target.split('/');
  const id = `${area}.${name}`;
  const dir = path.join(WIDGETS, area, name);
  if (fs.existsSync(dir) && !args.includes('--force')) { console.error(`${rel(dir)} already exists (--force to overwrite)`); process.exit(2); }
  const label = arg('--label', args) ?? pascal(name).replace(/([a-z])([A-Z])/g, '$1 $2');
  const category = arg('--category', args) ?? pascal(area);
  const portals = (arg('--portals', args) ?? 'PLATFORM').split(',').map((p) => p.trim().toUpperCase());
  const bad = portals.filter((p) => !PORTALS.includes(p));
  if (bad.length) { console.error(`unknown portal(s): ${bad.join(', ')} - one of ${PORTALS.join(', ')}`); process.exit(2); }
  const cls = `${pascal(area)}${pascal(name)}Widget`;
  const cfg = `${pascal(area)}${pascal(name)}Config`;
  const depth = '../../';

  const files: Record<string, string> = {
    [`${name}.widget.ts`]: `/**
 * ${label} (${id}). See docs/plans/portal-builder.md Appendix A for a worked example.
 * Open items: npm run widgets:check -- ${id}
 */
import { defineWidget, span } from '${depth}core/manifest';

export interface ${cfg} {
  // TODO(widget): the settings a page designer may change (number, boolean, string or a union of strings)
  pageSize: number;
}

export default defineWidget<${cfg}>({
  id: '${id}',
  version: '0.1.0',
  label: '${label}',
  description: 'TODO(widget): one sentence - what the user sees and does here.',
  category: '${category}',
  contexts: [${portals.map((p) => `'${p}'`).join(', ')}],
  // TODO(widget): the permission(s) that show this widget - rows or exact cells from
  // shared/web-core/permissions.ts. A list means ANY of them; { all: [...] } means ALL.
  requires: ['todo.replace_me:read'],
  // TODO(widget): one entry per gated button: { id, label, cell: 'row:verb', command }
  // and put data-action="<id>" on the element.
  actions: [],
  // TODO(widget): the logical reads / writes the component calls (map them in data-sources.ts)
  sources: [],
  commands: [],
  // where the org (or another entity) comes from: page selection first, then the user's own org
  inputs: { orgId: { from: ['selection.orgId', 'context.orgId'] } },
  config: {
    pageSize: { type: 'number', default: 20, min: 5, max: 100, label: 'Rows per page' },
  },
  // TODO(widget): review the span per tier, phone first (span.<tier>(min, default, max))
  size: { xs: span.xs(4), sm: span.sm(4, 8), md: span.md(6, 12), lg: span.lg(4, 6) },
  hideOn: ['forbidden'],
  load: () => import('./${name}.component').then((m) => m.${cls}),
});
`,
    [`${name}.component.ts`]: `import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MiPayWidget } from '${depth}core/mipay-widget';
import type { ${cfg} } from './${name}.widget';

/**
 * Rules (checked by widgets:check):
 *  - data only through this.data.query / this.data.command - never HttpClient, fetch or storage
 *  - build from the shared design-system components; style with @container, never @media
 *  - every gated action: *mpPerm="'row:verb'" and data-action="<action id>"
 */
@Component({
  selector: 'mpw-${area}-${name}',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [],
  template: \`
    <!-- TODO(widget): the widget's UI -->
    <div class="todo">${label}: not built yet (org {{ orgId() ?? '-' }})</div>
  \`,
  styles: [\`
    :host { display: block; min-width: 0; }
    .todo { padding: 16px; border: 1px dashed var(--a-border, #2b2b2b); border-radius: 12px; color: var(--a-dim, #a3a3a3); }
  \`],
})
export class ${cls} extends MiPayWidget<${cfg}> {
  readonly orgId = this.input<string>('orgId');
  // TODO(widget): load data, e.g.
  //   readonly rows = toSignal(this.data.query<Row[]>('${area}.list', { orgId: ... }), { initialValue: [] });
}
`,
    [`${name}.fixtures.ts`]: `/**
 * Fixture scenarios for ${id}: an answer for every source and command, per scenario.
 * Required: default, empty, error. Add longText if the widget shows user-entered text.
 */
import { defineFixtures, fail } from '${depth}core/fixtures';

export default defineFixtures({
  // TODO(widget): realistic data for every source and command in the manifest
  default: {},
  empty: {},
  error: {},
});

void fail;
`,
    [`${name}.spec.ts`]: `import { renderWidget } from '${depth}core/testing';
import def from './${name}.widget';
import fixtures from './${name}.fixtures';

describe('${id}', () => {
  // TODO(widget): replace with real tests - the right sources with the right params, the data
  // rendered, actions hidden without their cells. The generated suites cover FAPI conformance,
  // states, layout per tier and accessibility.
  it('renders in its first context', async () => {
    const w = await renderWidget(def, fixtures, { portal: def.contexts[0], orgId: 'org-1', scenario: 'default', cells: [] });
    expect(w.host).toBeTruthy();
  });
});
`,
  };

  fs.mkdirSync(dir, { recursive: true });
  for (const [f, body] of Object.entries(files)) fs.writeFileSync(path.join(dir, f), body);

  console.log(`\nCreated ${id} in ${rel(dir)}/\n`);
  console.log(`What needs to be built (\`npm run widgets:check -- ${id}\` shows what is still open):\n`);
  const steps = [
    [`Manifest           ${name}.widget.ts`, [
      'description, contexts (the portals it works in)',
      'requires: the permission(s) that show it - rows or row:verb cells from shared/web-core/permissions.ts',
      'actions: every gated button - { id, label, cell, command }',
      'sources / commands: the logical reads and writes the component calls',
      'inputs: where the org / entity comes from (selection.<key>, context.<key>)',
      'size: the span per tier, phone first',
    ]],
    ['Data sources       shared/widgets/data-sources.ts', ['map every source and command to its /api route, per portal (\'*\' = all)']],
    [`Component          ${name}.component.ts`, [
      'extends MiPayWidget; data only via this.data (never HttpClient / fetch / storage)',
      'shared design-system components only; @container, never @media',
      '*mpPerm and data-action="<id>" on every action element',
    ]],
    [`Fixtures           ${name}.fixtures.ts`, ['default, empty, error (+ longText if it shows user text)', 'an answer for every source and command']],
    [`Tests              ${name}.spec.ts`, ['the right sources with the right params; the data rendered; actions hidden without their cells']],
    ['Register & look    npm run widgets:gen  →  npm run workbench  →  http://localhost:4300/w/' + id, []],
    ['Publish            PR: widgets:check + test:widgets + test:layout; deploy: npm run widgets:register', []],
  ] as const;
  steps.forEach(([title, items], i) => {
    console.log(`  ${i + 1}. ${title}`);
    for (const it of items) console.log(`       - ${it}`);
  });
  console.log('');
}

// ------------------------------------------------------------------ check (what is still open)

type Mark = '✓' | '✗' | '!';
interface Line { mark: Mark; text: string; detail?: string[] }

function scanText(file: string): string { return fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : ''; }

/** The component file and the files it imports (relative), to the given depth. */
function importClosure(file: string, depth: number, seen = new Set<string>()): string[] {
  if (depth < 0 || seen.has(file) || !fs.existsSync(file)) return [];
  seen.add(file);
  const out = [file];
  for (const m of scanText(file).matchAll(/from\s+'(\.[^']+)'/g)) {
    const p = path.resolve(path.dirname(file), m[1]);
    const f = fs.existsSync(p + '.ts') ? p + '.ts' : p;
    out.push(...importClosure(f, depth - 1, seen));
  }
  return out;
}

function todoLines(files: string[]): string[] {
  const out: string[] = [];
  for (const f of files) scanText(f).split('\n').forEach((l, i) => { if (TODO.test(l)) out.push(`${rel(f)}:${i + 1}  ${l.trim()}`); });
  return out;
}

async function checkOne(f: Found, generatedCurrent: boolean): Promise<{ id: string; lines: Line[] }> {
  const lines: Line[] = [];
  const add = (ok: boolean | 'warn', text: string, detail?: string[]) =>
    lines.push({ mark: ok === 'warn' ? '!' : ok ? '✓' : '✗', text, detail: detail?.length ? detail : undefined });
  const { def, fixtures, error } = await load(f);
  if (!def) { add(false, 'manifest loads', [error ?? 'no default export']); return { id: path.relative(WIDGETS, f.widgetFile), lines }; }

  const probs = manifestProblems(def);
  add(!probs.length, 'manifest is consistent', probs);
  add(path.relative(WIDGETS, f.dir).split(path.sep).join('.') === def.id, `id matches its folder (${def.id})`);
  add(TIERS.every((t: string) => def.size?.[t]), 'size declared for every tier (xs sm md lg)');

  const unknown = [...requiredList(def), ...def.actions.map((a: { cell: string }) => a.cell)]
    .filter((c: string) => (c.includes(':') ? !KNOWN_CELLS.has(c) : !KNOWN_ROWS.has(c)));
  const proposed = unknown.filter((c: string) => PROPOSED_CELLS.has(c) || [...PROPOSED_CELLS].some((p) => p.startsWith(c + ':')));
  const missing = unknown.filter((c: string) => !proposed.includes(c));
  add(!missing.length, 'every permission is enforced by the backend (@Perm)', missing);
  if (proposed.length) add('warn', 'new permissions proposed by the backend stub (pending implementation)', proposed);
  add(def.sources.length + def.commands.length > 0, 'declares what it reads / writes (sources, commands)');

  const { routes, missing: unmapped } = routesOf(def);
  add(!unmapped.length, 'every source and command is mapped for every context (data-sources.ts)', unmapped);
  const unsafe = routes.filter((r) => !isSafeApiPath(r.path)).map((r) => `${r.name}: ${r.path}`);
  add(!unsafe.length, 'every route is a relative /api/ path', unsafe);

  // component rules
  const own = fs.readdirSync(f.dir).filter((n) => n.endsWith('.ts') && !n.endsWith('.spec.ts') && !n.endsWith('.fixtures.ts'))
    .map((n) => path.join(f.dir, n));
  const comp = scanText(f.componentFile);
  add(!!comp, `component file exists (${f.name}.component.ts)`);
  add(/extends\s+MiPayWidget\b/.test(comp), 'component extends MiPayWidget');
  const scope = [...new Set([...own, ...importClosure(f.componentFile, 1).filter((p) => !p.includes(`${path.sep}web-core${path.sep}`))])];
  const banned: string[] = [];
  for (const file of scope) {
    const t = scanText(file).replace(/\/\*[\s\S]*?\*\/|\/\/.*$/gm, '');
    for (const [re, what] of [[/\bHttpClient\b/, 'HttpClient'], [/\bfetch\s*\(/, 'fetch()'], [/\bXMLHttpRequest\b/, 'XMLHttpRequest'],
      [/\b(localStorage|sessionStorage|indexedDB)\b/, 'browser storage'], [/@media\b/, '@media (use @container)']] as const) {
      if (re.test(t)) banned.push(`${rel(file)}: ${what}`);
    }
  }
  add(!banned.length, 'no HttpClient / fetch / storage / @media in the widget or what it imports', banned);
  const closure = importClosure(f.componentFile, 2).map(scanText).join('\n');
  const noMarker = def.actions.filter((a: { id: string }) => !closure.includes(`data-action="${a.id}"`)).map((a: { id: string }) => a.id);
  add(!noMarker.length, 'every action has a data-action="<id>" element', noMarker);

  // fixtures
  if (!fixtures) add(false, `fixtures file exists (${f.name}.fixtures.ts)`);
  else {
    const sc = fixtures.scenarios ?? {};
    const missingSc = REQUIRED_SCENARIOS.filter((s) => !(s in sc));
    add(!missingSc.length, 'required scenarios: default, empty, error', missingSc);
    const unanswered = [...def.sources, ...def.commands].filter((n: string) => !(n in (sc.default ?? {})));
    add(!unanswered.length, 'the default scenario answers every source and command', unanswered);
    add('longText' in sc ? true : 'warn', 'longText scenario (required when the widget shows user-entered text)');
  }

  // contracts: what the server must send (self-diagnosis in the harness)
  const contracts = def.contracts ?? {};
  const noContract = def.sources.filter((n: string) => !contracts[n]);
  add(!noContract.length, 'every source has a response contract (npm run widgets:contract)', noContract);
  const cmdNoContract = def.commands.filter((n: string) => !contracts[n]);
  add(cmdNoContract.length ? 'warn' : true, 'every command has a response contract', cmdNoContract);
  // what the UI sends: every command with a body has a request contract
  const requests = def.requests ?? {};
  const bodyCommands = def.commands.filter((n: string) => routes.some((r) => r.name === n && ['POST', 'PUT', 'PATCH'].includes(r.method)));
  const noRequest = bodyCommands.filter((n: string) => !requests[n]);
  add(!noRequest.length, 'every command that sends a body has a request contract (widgets:contract --request)', noRequest);
  if (fixtures) {
    const drift: string[] = [];
    let skipped = 0;
    for (const [scName, sc] of Object.entries(fixtures.scenarios ?? {}) as [string, Record<string, unknown>][]) {
      for (const [n, v] of Object.entries(sc)) {
        const shape = contracts[n];
        if (!shape || (v && typeof v === 'object' && '__fail' in (v as object))) continue;
        if (typeof v === 'function') { skipped++; continue; }
        for (const i of shapeLib.check(shape, v).filter((x: { level: string }) => x.level === 'error').slice(0, 5)) {
          drift.push(`${scName} › ${n}: ${i.path} expected ${i.expected}, got ${i.got}`);
        }
      }
    }
    add(!drift.length, `fixtures match the contracts${skipped ? ` (${skipped} computed answer(s) checked at runtime only)` : ''}`, drift);
  }

  // reuse, never invent: only catalogue components, no raw controls
  const ownSrc = own.map((file) => ({ file, text: scanText(file) }));
  const comps = ownSrc.flatMap((x) => [...x.text.matchAll(/@(Component|Directive|Pipe)\s*\(/g)].map((m) => `${rel(x.file)}: @${m[1]}`));
  add(comps.length === 1 && comps[0].endsWith('@Component'), 'defines no components of its own (reuse npm run widgets:ui)',
    comps.length === 1 ? [] : comps);
  const ownTag = /selector:\s*'([^']+)'/.exec(comp)?.[1] ?? '';
  const templates = ownSrc.flatMap((x) => [...x.text.matchAll(/template:\s*`([\s\S]*?)`/g)].map((m) => ({ file: x.file, t: m[1] })));
  const unknownTags: string[] = [];
  const rawControls: string[] = [];
  const RAW: Record<string, string> = { table: 'mp-data-table', button: 'mp-button', select: 'mp-select or mp-form-field',
    input: 'mp-input or mp-form-field', textarea: 'mp-form-field' };
  for (const { file, t } of templates) {
    for (const m of t.matchAll(/<([a-z][a-z0-9]*-[a-z0-9-]+)/g)) {
      if (!KNOWN_TAGS.has(m[1]) && m[1] !== ownTag && !m[1].startsWith('ng-')) unknownTags.push(`${rel(file)}: <${m[1]}> is not in the catalogue`);
    }
    for (const m of t.matchAll(/<(table|button|select|input|textarea)\b/g)) rawControls.push(`${rel(file)}: <${m[1]}> - use ${RAW[m[1]]}`);
  }
  add(!unknownTags.length, 'uses only catalogue components (atoms, molecules, organisms, shared)', [...new Set(unknownTags)]);
  add(!rawControls.length, 'no raw <table> / <button> / <input> / <select> (the design system has them)', [...new Set(rawControls)]);

  // tests
  const spec = scanText(f.specFile);
  const its = (spec.match(/\bit\(/g) ?? []).length;
  add(its > 1, `unit tests written (${its} in ${f.name}.spec.ts)`);

  const todos = todoLines([f.widgetFile, f.componentFile, f.fixturesFile, f.specFile]);
  add(!todos.length, 'no TODO(widget) left', todos);
  add(generatedCurrent, 'registry is generated and current (npm run widgets:gen)');
  return { id: def.id, lines };
}

async function cmdCheck(args: string[]) {
  const only = args.find((a) => !a.startsWith('--'));
  const found = findWidgets();
  const current = (await generate()).current;
  let failed = 0;
  for (const f of found) {
    const r = await checkOne(f, current);
    if (only && r.id !== only) continue;
    const open = r.lines.filter((l) => l.mark === '✗').length;
    failed += open ? 1 : 0;
    console.log(`\n${r.id}  ${open ? `- ${open} open item(s)` : '- ready'}`);
    for (const l of r.lines) {
      console.log(`  ${l.mark} ${l.text}`);
      for (const d of l.detail ?? []) console.log(`      ${d}`);
    }
  }
  if (!found.length) console.log('no widgets yet: npm run widget:new -- <area>/<name>');
  console.log('');
  process.exit(failed ? 1 : 0);
}

// ------------------------------------------------------------------ gen

async function generate() {
  const found = findWidgets();
  const entries: any[] = [];
  const imports: string[] = [];
  const list: string[] = [];
  for (const [i, f] of found.entries()) {
    const { def, fixtures, error } = await load(f);
    if (!def) { console.error(`skipped ${rel(f.widgetFile)}: ${error}`); continue; }
    const from = './' + path.relative(WIDGETS, f.widgetFile).split(path.sep).join('/').replace(/\.ts$/, '');
    const fx = './' + path.relative(WIDGETS, f.fixturesFile).split(path.sep).join('/').replace(/\.ts$/, '');
    imports.push(`import w${i} from '${from}';`);
    list.push(`  { def: w${i}, fixtures: () => import('${fx}').then((m) => m.default) },`);
    entries.push({
      id: def.id, version: def.version, label: def.label, description: def.description, category: def.category,
      contexts: def.contexts, requires: requiredList(def), requiresMode: Array.isArray(def.requires) ? 'any' : 'all',
      cells: cellsOf(def), actions: def.actions, sources: def.sources, commands: def.commands,
      inputs: def.inputs ?? {}, publishes: def.publishes ?? [], config: def.config, size: def.size,
      minHeight: def.minHeight ?? null, hideOn: def.hideOn ?? ['forbidden'],
      scenarios: fixtures?.expect ?? {}, routes: routesOf(def).routes,
    });
  }
  const ts = `// GENERATED by tools/widgets.ts gen - do not edit. Source: every *.widget.ts under shared/widgets.
import type { WidgetDef } from './core/manifest';
import type { FixtureSet } from './core/fixtures';
${imports.join('\n')}

export interface RegisteredWidget { readonly def: WidgetDef<any>; readonly fixtures: () => Promise<FixtureSet> }

export const WIDGETS: readonly RegisteredWidget[] = [
${list.join('\n')}
];
`;
  const json = JSON.stringify({ format: 1, widgets: entries }, null, 1) + '\n';
  const current = scanText(REGISTRY_TS) === ts && scanText(MANIFEST_JSON) === json;
  return { ts, json, current, count: entries.length };
}

async function cmdGen(args: string[]) {
  const g = await generate();
  if (args.includes('--check')) {
    if (!g.current) { console.error('the widget registry is stale: run npm run widgets:gen'); process.exit(1); }
    console.log('widget registry is current');
    return;
  }
  fs.writeFileSync(REGISTRY_TS, g.ts);
  fs.writeFileSync(MANIFEST_JSON, g.json);
  console.log(`wrote ${g.count} widget(s): ${rel(REGISTRY_TS)}, ${rel(MANIFEST_JSON)}`);
}

// ------------------------------------------------------------------ register

async function cmdRegister(args: string[]) {
  const portal = arg('--portal', args)?.toUpperCase();
  const bundle = arg('--bundle', args);
  const url = arg('--url', args) ?? process.env['WIDGET_REGISTRY_URL'];
  const token = process.env['WIDGET_REGISTRY_TOKEN'];
  if (!portal || !PORTALS.includes(portal) || !bundle || !url) {
    console.error('usage: WIDGET_REGISTRY_TOKEN=… npm run widgets:register -- --portal SUPPLIER --bundle supplier-portal@<sha> --url http://backend:8080/internal/ui/widgets/register');
    process.exit(2);
  }
  if (!token) { console.error('WIDGET_REGISTRY_TOKEN is not set'); process.exit(2); }
  const manifest = JSON.parse(scanText(MANIFEST_JSON) || '{"widgets":[]}');
  const widgets = manifest.widgets.filter((w: any) => w.contexts.includes(portal))
    .map((w: any) => ({ ...w, routes: w.routes.filter((r: any) => r.portal === portal) }));
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Registry-Token': token },
    body: JSON.stringify({ bundle, portal, widgets }),
  });
  const body = await res.text();
  console.log(`${res.status} ${res.statusText}\n${body}`);
  process.exit(res.ok ? 0 : 1);
}

// ------------------------------------------------------------------ endpoint / contract / ui

const constName = (name: string) => name.toUpperCase().replace(/[^A-Z0-9]+/g, '_');
const typeName = (name: string) => pascal(name.replace(/[^a-zA-Z0-9]+/g, '-'));

function resolveEndpoint(args: string[]) {
  const [method, p] = args.filter((a, i) => !a.startsWith('--') && !args[i - 1]?.startsWith('--'));
  if (!method || !p || !/^(GET|POST|PUT|PATCH|DELETE)$/i.test(method) || !p.startsWith('/api/')) {
    console.error('usage: … <GET|POST|PUT|PATCH|DELETE> /api/<path>   (path variables as {name})');
    process.exit(2);
  }
  const all = java.scanEndpoints(BACKEND_WEB);
  const e = java.findEndpoint(all, method, p);
  if (!e) {
    const near = all.filter((x: { path: string }) => x.path.split('/').slice(0, 4).join('/') === p.split('/').slice(0, 4).join('/'))
      .map((x: { method: string; path: string }) => `  ${x.method} ${x.path}`);
    console.error(`no backend handler serves ${method.toUpperCase()} ${p}${near.length ? '\nsimilar:\n' + near.join('\n') : ''}`);
    process.exit(1);
  }
  const idx = java.indexTypes(BACKEND_SRC);
  const built = java.contractFor(e.returnType, idx);
  const body = e.body ? java.contractFor(e.body.type, idx) : null;
  return { e, built, body, frontendPath: p };
}

function cmdEndpoint(args: string[]) {
  const { e, built, body, frontendPath } = resolveEndpoint(args);
  const shape = new Function('s', `return ${built.code}`)(shapeLib.s);
  console.log(`\n${e.method} ${e.path}`);
  console.log(`  handler     ${e.handler}  (${path.relative(ROOT, e.file)}:${e.line})`);
  console.log(`  permission  ${e.perm ?? (e.publicReason ? `public - ${e.publicReason}` : 'NONE (unannotated)')}`);
  console.log(`  path vars   ${e.pathVars.map((x: { name: string }) => x.name).join(', ') || '-'}`);
  console.log(`  query       ${e.query.map((x: { name: string; required: boolean }) => x.name + (x.required ? '' : '?')).join(', ') || '-'}`);
  console.log(`  body        ${e.body ? `${e.body.type}${e.body.required ? '' : ' (optional)'}` : '-'}`);
  console.log(`  returns     ${e.returnType}`);
  console.log(`\ndata-sources.ts entry (frontend path params must keep the backend's order):`);
  const q = e.query.map((x: { name: string }) => `'${x.name}'`);
  console.log(`  '<area.name>': { '*': { method: '${e.method}', path: '${frontendPath}'${q.length ? `, query: [${q.join(', ')}]` : ''} } },`);
  console.log(`\nresponse contract:\n${built.code}`);
  if (built.untyped.length) console.log(`\n! untyped on the server (Map / JsonNode / Object) - tighten these by hand from the frontend type:\n  ${built.untyped.join('\n  ')}`);
  if (body) console.log(`\nrequest body (${e.body.type}):\n${body.code}`);
  console.log(`\nsample answer (shape only - make fixtures realistic):\n${JSON.stringify(shapeLib.sample(shape), null, 2)}\n`);
}

function cmdContract(args: string[]) {
  const name = arg('--name', args);
  const out = arg('--out', args);
  if (!name || !out) { console.error('usage: npm run widgets:contract -- GET /api/… --name <source> --out <widget>.contract.ts'); process.exit(2); }
  const { e, built: response, body } = resolveEndpoint(args);
  const isRequest = args.includes('--request');
  if (isRequest && !body) { console.error(`${e.method} ${e.path} takes no request body (${e.handler})`); process.exit(1); }
  const built = isRequest ? body! : response;
  const file = path.resolve(process.cwd(), out);
  const C = constName(name) + (isRequest ? '_REQUEST' : '');
  const marker = `${isRequest ? 'request' : 'contract'} ${name}`;
  const block = `// <${marker}>
/** ${e.method} ${e.path} ${isRequest ? `sends ${e.body.type}` : `→ ${e.returnType}`} (${e.handler}, ${e.perm ?? 'public'}). Generated by widgets:contract. */
export const ${C} = ${built.code};
export type ${typeName(name)}${isRequest ? 'Request' : ''} = Infer<typeof ${C}>;
// </${marker}>`;
  let text = fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : '';
  if (!text) {
    const shapeRel = path.relative(path.dirname(file), path.join(WIDGETS, 'core/shape')).split(path.sep).join('/');
    text = `/**\n * Response contracts: what the server must send. The port checks every answer against these and\n * the harness shows any mismatch. Regenerate after a backend change (widgets:contract).\n */\nimport { s, type Infer } from '${shapeRel.startsWith('.') ? shapeRel : './' + shapeRel}';\n`;
  }
  const esc = marker.replace(/[.]/g, '\\.');
  const re = new RegExp(`// <${esc}>[\\s\\S]*?// </${esc}>`);
  text = re.test(text) ? text.replace(re, block) : `${text.trimEnd()}\n\n${block}\n`;
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, text);
  console.log(`wrote ${C} (${e.method} ${e.path} ${isRequest ? `sends ${e.body.type}` : `→ ${e.returnType}`}) to ${path.relative(FE, file)}`);
  if (built.untyped.length) console.log(`! untyped on the server - tighten by hand:\n  ${built.untyped.join('\n  ')}`);
}

function cmdUi(args: string[]) {
  if (args.includes('--json')) { console.log(JSON.stringify(CATALOGUE, null, 1)); return; }
  console.log(`${CATALOGUE.length} reusable UI elements. A widget imports these; it never defines its own.\n`);
  console.log(cat.catalogueMarkdown(CATALOGUE));
}

// ------------------------------------------------------------------ prompt (portable: any LLM)

const fence = (lang: string, code: string) => '```' + lang + '\n' + code.trimEnd() + '\n```';
const read = (p: string) => fs.readFileSync(path.join(FE, p), 'utf8');

function endpointSection(method: string, p: string): string {
  const { e, built, body, frontendPath } = resolveEndpoint([method, p]);
  const shape = new Function('s', `return ${built.code}`)(shapeLib.s);
  const q = e.query.map((x: { name: string; required: boolean }) => x.name + (x.required ? '' : '?'));
  return [
    `### ${e.method} ${frontendPath}`,
    `- handler: \`${e.handler}\``,
    `- permission cell: \`${e.perm ?? (e.publicReason ? 'public' : 'NONE')}\``,
    `- path variables: ${e.pathVars.map((x: { name: string }) => '`' + x.name + '`').join(', ') || 'none'} (use these names in data-sources and in the params the component passes)`,
    `- query parameters: ${q.map((x: string) => '`' + x + '`').join(', ') || 'none'}`,
    `- request body: ${e.body ? '`' + e.body.type + '`' + (e.body.required ? '' : ' (optional)') : 'none'}`,
    `- response: \`${e.returnType}\``,
    '', 'Response contract (put it in the contract file between `// <contract NAME>` markers, NAME = your source/command name):',
    fence('ts', built.code),
    ...(built.untyped.length ? ['', 'Untyped on the server - ask the user for these shapes (or use the frontend type): ' + built.untyped.map((x: string) => '`' + x + '`').join(', ')] : []),
    ...(body ? ['', 'Request contract (between `// <request NAME>` markers):', fence('ts', body.code),
      ...(body.untyped.length ? ['Untyped request parts - ask the user: ' + body.untyped.join(', ')] : [])] : []),
    '', 'Sample answer (shape only):', fence('json', JSON.stringify(shapeLib.sample(shape), null, 2)),
    '', 'data-sources entry:', fence('ts', `'<area.thing.verb>': { '*': { method: '${e.method}', path: '${frontendPath}'${e.query.length ? `, query: [${e.query.map((x: { name: string }) => `'${x.name}'`).join(', ')}]` : ''} } },`),
  ].join('\n');
}

/** The full text of `export interface Name …{…}` (brace-balanced) or `export type Name = …;`. */
function typeDecl(src: string, name: string): string | null {
  const m = new RegExp(`export (interface|type) ${name}\\b`).exec(src);
  if (!m) return null;
  let depth = 0;
  for (let i = m.index; i < src.length; i++) {
    const ch = src[i];
    if (ch === '{' || ch === '<' || ch === '(') depth++;
    else if (ch === '}' || ch === '>' || ch === ')') {
      depth--;
      if (m[1] === 'interface' && ch === '}' && depth === 0) return src.slice(m.index, i + 1);
    } else if (m[1] === 'type' && ch === ';' && depth === 0) return src.slice(m.index, i + 1);
  }
  return null;
}

/** Every exported interface / type that a catalogue element's inputs name, from its own file. */
function inputTypes(): string {
  const out: string[] = [];
  const seen = new Set<string>();
  for (const e of CATALOGUE as { importPath: string; inputs: string[] }[]) {
    const src = fs.readFileSync(path.join(FE, e.importPath + '.ts'), 'utf8');
    for (const name of new Set(e.inputs.join(' ').match(/\b[A-Z][A-Za-z0-9]+\b/g) ?? [])) {
      if (seen.has(name)) continue;
      const decl = typeDecl(src, name);
      if (decl) { seen.add(name); out.push(`// ${e.importPath}\n${decl}`); }
    }
  }
  return out.join('\n\n');
}

/**
 * Every endpoint the backend serves, grouped by permission row: method, path, cell, what it does,
 * which org it acts on, params, body and response - with every record defined once below.
 */
function apiCatalogue(): string {
  const idx = java.indexTypes(BACKEND_SRC);
  const defs = new Map<string, string>();
  const byRow = new Map<string, any[]>();
  for (const e of BACKEND_ENDPOINTS as any[]) {
    const row = e.perm ? e.perm.split(':')[0] : '(public)';
    (byRow.get(row) ?? byRow.set(row, []).get(row)!).push(e);
  }
  const ORG: Record<string, string> = { PATH: 'org in path', SELF: 'own org', LOOKUP: 'org of the object', NONE: 'platform' };
  const rows = [...byRow.keys()].filter((r) => r !== '(public)').sort();
  const out: string[] = [];
  for (const row of rows) {
    out.push(`#### ${row}`, '', '| endpoint | cell | what it does | acts on | params | body → returns |', '|---|---|---|---|---|---|');
    for (const e of byRow.get(row)!.sort((a, b) => a.path.localeCompare(b.path) || a.method.localeCompare(b.method))) {
      const params = [...e.pathVars.map((p: any) => `{${p.name}}`), ...e.query.map((q: any) => `${q.name}${q.required ? '' : '?'}`)].join(' ') || '-';
      const body = e.body ? notation(e.body.type) + (e.body.required ? '' : '?') : '-';
      out.push(`| \`${e.method} ${e.path}\` | \`${e.perm.split(':')[1]}\` | ${e.description.replace(/\|/g, '/')} | ${ORG[e.tenantSource] ?? e.tenantSource} | ${params} | ${body} → ${notation(e.returnType)} |`);
    }
    out.push('');
  }
  function notation(t: string) { return java.notation(t, idx, defs, false).replace(/\|/g, '\\|'); }
  const types = [...defs.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([n, d]) => `${n} = ${d}`);
  return [
    `${BACKEND_ENDPOINTS.length} endpoints, ${rows.length} permission rows. A row's cells are \`<row>:<verb>\`. Types: \`string|null\` may be null; ` +
    '`integer`, `number`, `boolean`, `date-time` (ISO string), `date` (yyyy-mm-dd), `X[]` list, `map<string, X>`, `any` (free-form JSON: ask the user for its shape).',
    '', ...out, '#### Types (every record and enum used above)', '', fence('ts', types.join('\n')),
  ].join('\n');
}

let INCLUDE_API = true;

function buildPrompt(endpoints: [string, string][]): string {
  const ref = 'projects/shared/widgets/kyc/tasks';
  const dsRef = /\/\/ review queue[\s\S]*?\n(?=\}\);)/.exec(read('projects/shared/widgets/data-sources.ts'))?.[0] ?? '';
  const perms = (PERMS as { id: string; area: string }[]).reduce((acc: Record<string, string[]>, p) => ((acc[p.area] ??= []).push(p.id), acc), {});
  const testingApi = /export interface RenderOpts[\s\S]*?\n}\n\nexport interface RenderedWidget[\s\S]*?\n}\n/.exec(read('projects/shared/widgets/core/testing.ts'))?.[0] ?? '';
  return [
`# MiPay widget builder

**How to use this:** paste this whole document into any AI chat (ChatGPT, Claude, Gemini, …), then write your UI requirement at the end
under "My requirement". The answer is a complete widget. Save the answer as a file and run, in \`frontend/\`:

    npm run widgets:apply -- answer.md

That writes the files, runs every check and test, takes screenshots at every screen size and validates the widget against the
backend's permissions - ready to register. If anything fails it prints a repair message: paste it back into the same chat.

---

## Instructions for the AI

You are building ONE MiPay UI widget: an Angular 20 standalone component plus its manifest, contracts, fixtures and tests, that
plugs into MiPay's widget registry. MiPay is a FAPI 2.0 payment platform: the browser never holds tokens; a gateway (BFF) does
FAPI and the widget reaches the API ONLY through \`this.data\` (the widget port). A local tool applies your answer and checks it
strictly - follow the rules and the output format exactly.

### Step 1 - the operations sheet
List every operation the UI performs (each read and each write: load, save draft, submit, approve, …). For each:
endpoint (METHOD /api/path), permission cell, the shape it RECEIVES (response), the shape it SENDS (request body / params),
and what the UI does after success, a refusal (422 / 409) and 403.
- Use the "Endpoints for this widget" section when present: it is read from the real backend.
- If an endpoint, a permission or a shape you need is missing or untyped, ASK the user once, showing your drafted sheet, and wait.
  Never invent an endpoint or a permission. (The user can generate a prompt with the endpoints resolved:
  \`npm run widgets:prompt -- GET /api/... POST /api/...\`.)
- **Reuse first.** Look for each operation in the "API catalogue" (every endpoint the backend serves, with its permission and
  shapes). Mark it EXISTING and use that endpoint, its cell and its exact shapes when one fits.
- **New when nothing fits.** Mark it NEW and propose: an endpoint (\`/api/<area>/…\`), a permission cell - reuse an existing
  row and verb when the meaning matches, else a new row \`<area>.<resource>\` with one of the ten verbs below - and the request
  and response shapes this UI needs. You then also deliver a backend controller stub (see "Backend stub").
- Complex UIs (draft → submit, approve / reject…) are several operations: each has its own command, request contract and
  response contract; chained writes (save then submit) run in order and a later failure says the earlier step succeeded.

### Step 2 - the files
When the sheet is complete, print it briefly, then output the files in the Output format, then the self-check list. Nothing else.

### Hard rules (the checker enforces every one)
1. Five files in \`frontend/projects/shared/widgets/<area>/<name>/\`: \`<name>.widget.ts\`, \`<name>.contract.ts\`,
   \`<name>.component.ts\`, \`<name>.fixtures.ts\`, \`<name>.spec.ts\` - plus one DATA-SOURCES block. \`<area>\`/\`<name>\` are
   lower case (name may contain dashes); the widget id is \`<area>.<name>\`.
2. Reuse, never invent: only the UI elements listed below (import them by the paths shown, relative from the widget folder:
   \`frontend/\` is five levels up, e.g. \`../../../../../src/app/design-system/organisms/data-table\`). Never define a component,
   directive or pipe other than the widget itself. Never use raw \`<table>\`, \`<button>\`, \`<input>\`, \`<select>\`, \`<textarea>\`.
3. One network path: never HttpClient, fetch, XMLHttpRequest, localStorage/sessionStorage/indexedDB. Only \`this.data.query(name, params)\`
   and \`this.data.command(name, { ...pathParams, ...queryParams, body })\`.
4. Every source and command has a response contract; every command that sends a body (POST/PUT/PATCH) has a request contract.
   Contracts use the \`s.*\` builders; export the type with \`Infer\`. Build request bodies typed by their request type.
5. Every gated action: \`*mpPerm="'row:verb'"\` (or an organism's \`perm\` input) AND \`data-action="<action id>"\` on the element.
6. Mobile first: \`size\` for xs, sm, md, lg with \`span.*\` (xs max 4 columns, sm 8, md/lg 12). Style with \`@container\`, never \`@media\`.
   For structural phone changes (fewer table columns) use \`this.size().tier === 'xs'\`.
7. Fixtures: scenarios \`default\`, \`empty\`, \`error\`, plus \`longText\` when the widget shows user text, \`forbidden\` (fail(403)) when it
   hides on 403, and one per refusal you handle. Every value must match the response contract exactly (types, nulls, every field present).
   \`default\` answers every source and command. Realistic Namibian data (organisations, people, NAD amounts, ISO dates).
8. Tests (Vitest globals \`describe\`/\`it\`/\`expect\`) with \`renderWidget\`: at least 4: the right sources and params, the data rendered,
   actions hidden without their cell and shown after \`await w.grant(cell)\`, each write's params/body (\`w.lastCommand()\`), no
   \`[data-diagnostics]\` with the fixtures, and a drifted answer diagnosed in \`[data-diagnostics]\`.
9. TypeScript strict, Angular strict templates: no \`any\` leaks in templates, signals for state, \`ChangeDetectionStrategy.OnPush\`.
10. No \`TODO\` left anywhere.

### Verbs (the only ten) and tenant sources

| verb | meaning |  | tenantSource | the org the call acts on |
|---|---|---|---|---|
| read | view / list |  | PATH | named by a path variable: \`tenant = "#id"\` |
| create | create / add |  | SELF | the caller's own org: \`tenant = "null"\` |
| modify | change / update |  | LOOKUP | derived from an object (task, request): \`lookup = "<resolver>"\` |
| delete | delete / retire |  | NONE | platform-wide (admin / ops): \`tenant = "null"\` |
| submit | submit (maker) |  | | |
| authorize | approve (checker) - never in the same role as submit on the same row |  | | |
| execute | run / act with effect |  | | |
| export | download / export |  | | |
| administer | administer / control |  | | |
| simulate | simulate (sandbox only) |  | | |

### Backend stub (only for NEW operations)

One controller per widget, in package \`com.mipay.sme.web\`, file \`backend/src/main/java/com/mipay/sme/web/<Area><Thing>Controller.java\`
(a NEW file - never edit an existing controller). Request and response types are records nested in the controller, named
exactly as in your contracts. Every handler has \`@Perm\` (never \`@PublicEndpoint\`) and answers 501 until implemented:

    package com.mipay.sme.web;

    import com.mipay.sme.security.rbac.Perm;
    import com.mipay.sme.security.rbac.TenantSource;
    import com.mipay.sme.security.rbac.Verb;
    import jakarta.validation.Valid;
    import jakarta.validation.constraints.NotBlank;
    import java.time.Instant;
    import java.util.List;
    import org.springframework.http.HttpStatus;
    import org.springframework.web.bind.annotation.*;
    import org.springframework.web.server.ResponseStatusException;

    /** <What these endpoints are for>. STUB generated for widget <area.name>: implement the service calls. */
    @RestController
    public class KycNotesController {

        public record KycNoteRow(String id, String orgId, String text, String author, Instant createdAt) { }
        public record KycNoteRequest(@NotBlank String text) { }

        @Perm(resource = "kyc.note", verb = Verb.READ, tenantSource = TenantSource.PATH, tenant = "#orgId",
              description = "List the review notes of an organization's KYC application")
        @GetMapping("/api/kyc/orgs/{orgId}/notes")
        public List<KycNoteRow> list(@PathVariable String orgId) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "kyc notes: not implemented yet");
        }

        @Perm(resource = "kyc.note", verb = Verb.CREATE, tenantSource = TenantSource.PATH, tenant = "#orgId",
              description = "Add a review note to an organization's KYC application")
        @PostMapping("/api/kyc/orgs/{orgId}/notes")
        public KycNoteRow add(@PathVariable String orgId, @Valid @RequestBody KycNoteRequest body) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "kyc notes: not implemented yet");
        }
    }

Rules for stubs: \`@NotBlank\`/\`@NotNull\` on required request fields (the contract generator reads them); primitives
(\`int\`, \`long\`, \`boolean\`) for never-null numbers and flags, boxed or \`String\`/\`Instant\` otherwise (those may be null);
the widget's contracts must describe exactly these records (the checker compares them). Add a BACKEND-NOTES block saying
what each stub must do (service, rules, which existing data), so a backend developer can implement it.

### Output format (machine-applied - exactly this, paths from the repository root)

    === FILE: frontend/projects/shared/widgets/<area>/<name>/<name>.widget.ts ===
    …the whole file…
    === END FILE ===
    (the same for .contract.ts, .component.ts, .fixtures.ts, .spec.ts)
    === DATA-SOURCES ===
      '<area.thing.verb>': { '*': { method: 'GET', path: '/api/…', query: ['…'] } },
    === END DATA-SOURCES ===
    (only when an operation is NEW:)
    === BACKEND: backend/src/main/java/com/mipay/sme/web/<Area><Thing>Controller.java ===
    …the whole stub controller…
    === END BACKEND ===
    === BACKEND-NOTES ===
    …what each stub endpoint must do…
    === END BACKEND-NOTES ===

When answering a REPAIR REPORT, output only the changed files (and the DATA-SOURCES block if it changed), same format.

### Self-check (print it, ticked, after the files)
- [ ] every operation is marked EXISTING (endpoint from the catalogue) or NEW (stub provided, same shapes as the contracts)
- [ ] every operation in the sheet has a source/command, a response contract, a request contract (writes) and a fixture answer
- [ ] only listed UI elements; no raw controls; no own components
- [ ] every action: exact cell + data-action
- [ ] fixtures match the contracts field by field
- [ ] size for all four tiers; no @media
- [ ] tests as in rule 8`,
    '', '---', '', '## Endpoints for this widget (read from the MiPay backend)', '',
    endpoints.length ? endpoints.map(([m, p]) => endpointSection(m, p)).join('\n\n')
      : '_None resolved for this prompt._ Ask the user for each endpoint (METHOD /api/path), its permission cell, an example response and an example request body - or ask them to run `npm run widgets:prompt -- GET /api/... POST /api/...` and paste the new prompt.',
    ...(INCLUDE_API ? ['', '## API catalogue (every endpoint the backend serves - reuse these first)', '', apiCatalogue()] : []),
    '', '## The widget API (exact source)', '',
    '### core/manifest.ts', fence('ts', read('projects/shared/widgets/core/manifest.ts')),
    '### core/grid.ts', fence('ts', read('projects/shared/widgets/core/grid.ts')),
    '### core/shape.ts', fence('ts', read('projects/shared/widgets/core/shape.ts')),
    '### core/fixtures.ts', fence('ts', read('projects/shared/widgets/core/fixtures.ts')),
    '### core/mipay-widget.ts', fence('ts', read('projects/shared/widgets/core/mipay-widget.ts')),
    '### core/testing.ts (the test harness API; import `renderWidget` from `../../core/testing`)', fence('ts', testingApi),
    '', '## UI elements you may use (the only ones)', '', cat.catalogueMarkdown(CATALOGUE), '',
    'Types the elements\' inputs use (exact source):', fence('ts', inputTypes()),
    'Shared helpers: `refusal(e, KYC_HINTS)` / `readProblem(e)` from `../../../kyc/kyc-api` turn a refused call into a message; `MpPerm` (`*mpPerm`) from `../../../web-core/perm-directive`.',
    '', '## Permission cells (choose only from these)', '',
    Object.entries(perms).map(([a, ids]) => `- **${a}**: ${ids.map((i) => '`' + i + '`').join(', ')}`).join('\n'),
    '', '## Reference widget: kyc.tasks (complete, passing every check - imitate its structure)', '',
    ...['widget', 'contract', 'component', 'fixtures', 'spec'].map((k) =>
      `=== FILE: frontend/${ref}/tasks.${k}.ts ===\n${read(`${ref}/tasks.${k}.ts`).trimEnd()}\n=== END FILE ===`),
    `=== DATA-SOURCES ===\n${dsRef.trimEnd()}\n=== END DATA-SOURCES ===`,
    '', '---', '', '## My requirement', '', '(write what the widget should show and let the user do, and the endpoints if you know them)', '',
  ].join('\n');
}

function cmdPrompt(args: string[]) {
  INCLUDE_API = !args.includes('--no-api');
  const out = arg('--out', args);
  const rest = args.filter((a, i) => !a.startsWith('--') && !args[i - 1]?.startsWith('--'));
  const pairs: [string, string][] = [];
  for (let i = 0; i + 1 < rest.length; i += 2) pairs.push([rest[i], rest[i + 1]]);
  const text = buildPrompt(pairs);
  if (out) {
    const f = path.resolve(process.cwd(), out);
    fs.mkdirSync(path.dirname(f), { recursive: true });
    fs.writeFileSync(f, text);
    console.log(`wrote ${path.relative(process.cwd(), f)} (${Math.round(text.length / 1024)} kB, ${pairs.length} endpoint(s) resolved) - paste it into any AI chat, add the requirement at the end`);
  } else process.stdout.write(text);
}

// ------------------------------------------------------------------ apply (an LLM's answer → a verified widget)

const ANSI = /\x1b\[[0-9;]*[A-Za-z]/g;
/** The useful end of a tool's output: no colours, no progress lines, no stack frames into node_modules. */
const tail = (t: string, n = 120) => t.replace(ANSI, '').split('\n')
  .filter((l) => l.trim() && !/^\s*\[\d+\/\d+\]/.test(l) && !/node_modules|zone\.js|^\s+\d+\|/.test(l) && !/^\s*❯ (fulfilled|_Zone|Zone|drain|invoke)/.test(l))
  .slice(-n).join('\n');

function run(cmd: string, argv: string[], cwd: string, env: Record<string, string> = {}, timeoutMs = 900_000) {
  const r = spawnSync(cmd, argv, { cwd, encoding: 'utf8', env: { ...process.env, ...env }, timeout: timeoutMs, maxBuffer: 64 * 1024 * 1024 });
  return { ok: r.status === 0, out: `${r.stdout ?? ''}${r.stderr ?? ''}${r.error ? String(r.error) : ''}` };
}

function cmdApply(args: string[]) {
  const file = args.find((a) => !a.startsWith('--'));
  if (!file || !fs.existsSync(file)) { console.error('usage: npm run widgets:apply -- <answer.md> [--no-backend]'); process.exit(2); }
  const text = fs.readFileSync(file, 'utf8').replace(/\r\n/g, '\n');
  const blocks = [...text.matchAll(/^=== FILE: (\S+) ===\n([\s\S]*?)^=== END FILE ===/gm)].map((m) => ({ path: m[1], body: m[2] }));
  const ds = /^=== DATA-SOURCES ===\n([\s\S]*?)^=== END DATA-SOURCES ===/m.exec(text)?.[1];
  const stubs = [...text.matchAll(/^=== BACKEND: (\S+) ===\n([\s\S]*?)^=== END BACKEND ===/gm)].map((m) => ({ path: m[1], body: m[2] }));
  const notes = /^=== BACKEND-NOTES ===\n([\s\S]*?)^=== END BACKEND-NOTES ===/m.exec(text)?.[1];
  const withBackend = args.includes('--with-backend');
  const unfence = (b: string) => b.replace(/^\s*```[a-z]*\n/, '').replace(/\n```\s*$/, '\n');
  const PATH = /^frontend\/projects\/shared\/widgets\/([a-z][a-z0-9]*)\/([a-z][a-z0-9-]*)\/([a-z][a-z0-9-]*)\.(widget|contract|component|fixtures|spec)\.ts$/;
  const bad = blocks.filter((b) => { const m = PATH.exec(b.path); return !m || m[2] !== m[3]; }).map((b) => b.path);
  if (!blocks.length) { console.error('no "=== FILE: … ===" blocks found in the answer'); process.exit(1); }
  if (bad.length) { console.error(`refused: files outside one widget folder or misnamed:\n  ${bad.join('\n  ')}`); process.exit(1); }
  const keys = new Set(blocks.map((b) => { const m = PATH.exec(b.path)!; return `${m[1]}/${m[2]}`; }));
  if (keys.size !== 1) { console.error(`refused: one widget per answer (found ${[...keys].join(', ')})`); process.exit(1); }
  const [area, name] = [...keys][0].split('/');
  const id = `${area}.${name}`;
  const dir = path.join(WIDGETS, area, name);
  const isNew = !fs.existsSync(dir);
  const kinds = new Set(blocks.map((b) => PATH.exec(b.path)![4]));
  const missingKinds = ['widget', 'contract', 'component', 'fixtures', 'spec'].filter((k) => !kinds.has(k));
  if (isNew && missingKinds.length) { console.error(`refused: a new widget needs all five files; missing ${missingKinds.join(', ')}`); process.exit(1); }

  // backend stubs: only NEW controllers in com.mipay.sme.web, every handler @Perm, nothing public
  const STUB = /^backend\/src\/main\/java\/com\/mipay\/sme\/web\/([A-Z][A-Za-z0-9]*Controller)\.java$/;
  const MARK = `// Generated by widgets:apply for ${id} - a STUB: implement it before release.`;
  for (const st of stubs) {
    const m = STUB.exec(st.path);
    const code = unfence(st.body);
    const target = path.join(ROOT, st.path);
    const problems = [
      !m && 'must be backend/src/main/java/com/mipay/sme/web/<Name>Controller.java',
      m && !new RegExp(`\\bclass\\s+${m[1]}\\b`).test(code) && `must declare class ${m?.[1]}`,
      !/^\s*package\s+com\.mipay\.sme\.web\s*;/m.test(code) && 'must be in package com.mipay.sme.web',
      !/@RestController/.test(code) && 'must be a @RestController',
      /@(?:[\w.]+\.)?PublicEndpoint/.test(code) && 'may not declare public endpoints (every handler needs @Perm)',
      fs.existsSync(target) && !fs.readFileSync(target, 'utf8').startsWith(MARK) && 'would overwrite existing backend code',
    ].filter(Boolean);
    if (problems.length) { console.error(`refused backend stub ${st.path}:\n  ${problems.join('\n  ')}`); process.exit(1); }
  }

  fs.mkdirSync(dir, { recursive: true });
  for (const b of blocks) fs.writeFileSync(path.join(ROOT, b.path), unfence(b.body).trimEnd() + '\n');
  if (ds !== undefined) {
    const lines = unfence(ds).split('\n').map((l) => l.trimEnd()).filter((l) => l.trim());
    if (lines.some((l) => /^\s*(import|export|\}\))/.test(l))) { console.error('refused: the DATA-SOURCES block may only hold source entries'); process.exit(1); }
    const dsFile = path.join(WIDGETS, 'data-sources.ts');
    let src = fs.readFileSync(dsFile, 'utf8');
    const block = `  // <widget ${id}>\n${lines.map((l) => '  ' + l.trim()).join('\n')}\n  // </widget ${id}>\n`;
    const re = new RegExp(`  // <widget ${id.replace(/[.-]/g, '\\$&')}>[\\s\\S]*?  // </widget ${id.replace(/[.-]/g, '\\$&')}>\\n`);
    src = re.test(src) ? src.replace(re, block) : src.replace(/\n\}\);\s*$/, `\n${block}});\n`);
    fs.writeFileSync(dsFile, src);
  }
  console.log(`applied ${blocks.length} file(s)${ds !== undefined ? ' + data sources' : ''} to ${rel(dir)}/ (${isNew ? 'new widget' : 'update'})\n`);

  const OUT = path.join(FE, 'widget-out', id);
  fs.rmSync(OUT, { recursive: true, force: true });
  fs.mkdirSync(OUT, { recursive: true });

  // the stubs as a proposal (always) and, with --with-backend, in the backend source
  const stubDir = path.join(OUT, 'backend');
  let stubEndpoints: any[] = [];
  if (stubs.length) {
    fs.mkdirSync(stubDir, { recursive: true });
    for (const st of stubs) fs.writeFileSync(path.join(stubDir, path.basename(st.path)), `${MARK}\n${unfence(st.body).trimEnd()}\n`);
    if (notes !== undefined) fs.writeFileSync(path.join(OUT, 'BACKEND-NOTES.md'), `# Backend work for ${id}\n\n${unfence(notes).trim()}\n`);
    stubEndpoints = java.scanEndpoints(stubDir);
    const unguarded = stubEndpoints.filter((e) => !e.perm).map((e) => `${e.method} ${e.path}`);
    const outside = stubEndpoints.filter((e) => !e.path.startsWith('/api/')).map((e) => `${e.method} ${e.path}`);
    const norm = (p: string) => p.replace(/\{[^}]+\}/g, '{}');
    // an endpoint already served by real backend code (not by this widget's earlier stub) is taken
    const taken = stubEndpoints.filter((e) => (BACKEND_ENDPOINTS as any[]).some((b) => b.method === e.method
      && norm(b.path) === norm(e.path) && !fs.readFileSync(b.file, 'utf8').startsWith(MARK)))
      .map((e) => `${e.method} ${e.path}`);
    if (unguarded.length || outside.length || taken.length) {
      console.error(`refused backend stub:${unguarded.length ? `\n  without @Perm: ${unguarded.join(', ')}` : ''}${outside.length ? `\n  not under /api/: ${outside.join(', ')}` : ''}${taken.length ? `\n  already served by existing code: ${taken.join(', ')}` : ''}`);
      process.exit(1);
    }
    if (withBackend) for (const st of stubs) fs.copyFileSync(path.join(stubDir, path.basename(st.path)), path.join(ROOT, st.path));
    console.log(`backend stub: ${stubEndpoints.length} endpoint(s), new cell(s) ${[...new Set(stubEndpoints.map((e) => e.perm))].join(', ')}`
      + (withBackend ? ' - installed in the backend source' : ` - proposal in ${rel(stubDir)}/ (install with --with-backend)`) + '\n');
  }
  const proposedCells = withBackend ? [] : [...new Set(stubEndpoints.map((e) => e.perm as string))];
  const steps: { name: string; ok: boolean; out: string; skipped?: boolean }[] = [];
  const tool = ['--disable-warning=MODULE_TYPELESS_PACKAGE_JSON', path.join(ROOT, 'tools/widgets.ts')];
  const step = (name: string, fn: () => { ok: boolean; out: string }) => {
    const stop = steps.some((s) => !s.ok && ['register in the build', 'checks'].includes(s.name));
    if (stop) { steps.push({ name, ok: false, out: '', skipped: true }); console.log(`  - ${name}: skipped`); return; }
    const r = fn();
    steps.push({ name, ...r });
    console.log(`  ${r.ok ? '✓' : '✗'} ${name}`);
  };

  step('register in the build', () => run('node', [...tool, 'gen'], FE));
  step('checks', () => run('node', [...tool, 'check', id], FE, { WIDGET_PROPOSED_CELLS: proposedCells.join(',') }));
  if (stubs.length) step('contracts describe the backend stub exactly', () => run('node', [...tool, 'stubcheck', id, stubDir], FE));
  if (stubs.length && withBackend) step('backend stub compiles', () => run('./gradlew', ['compileJava', '--offline', '-q'], path.join(ROOT, 'backend')));
  step('unit + FAPI conformance tests', () => run('npx', ['ng', 'test', 'widget-workbench', '--watch=false',
    '--include', `shared/widgets/${area}/${name}/${name}.spec.ts`, '--include', 'shared/widgets/conformance.spec.ts'], FE));
  step('layout at every tier × scenario', () => run('npx', ['playwright', 'test', '-c', 'e2e/playwright.config.ts', '-g', `${id} @`, '--reporter=line'], FE));
  step('screenshots', () => run('npx', ['playwright', 'test', '-c', 'e2e/shots.config.ts', '--reporter=line'], FE, { SHOT_ID: id, SHOT_OUT: OUT }));
  if (stubs.length && !withBackend) {
    steps.push({ name: 'registry server would accept it', ok: true, out: '', skipped: true });
    console.log(`  … registry server would accept it: pending - the backend stub is a proposal (apply with --with-backend to compile and validate it)`);
  } else if (!args.includes('--no-backend')) {
    step('registry server would accept it (real @Perm endpoints)', () => {
      const r = run('./gradlew', ['test', '--offline', '-q', '--rerun-tasks', '--tests',
        'com.mipay.sme.ui.WidgetRegistryServiceTest.theGeneratedFrontendManifestPassesAgainstTheRealEndpointsInEveryPortal'],
        path.join(ROOT, 'backend'), { WIDGET_ONLY: id });
      const xml = path.join(ROOT, 'backend/build/test-results/test/TEST-com.mipay.sme.ui.WidgetRegistryServiceTest.xml');
      const why = fs.existsSync(xml) ? [...fs.readFileSync(xml, 'utf8').matchAll(/<failure message="([^"]*)"/g)].map((m) => m[1].replace(/&quot;/g, '"').replace(/&lt;/g, '<').replace(/&gt;/g, '>')).join('\n') : '';
      return { ok: r.ok, out: why || r.out };
    });
  }

  // registration payloads: what `widgets:register` sends for each portal
  const manifest = JSON.parse(scanText(MANIFEST_JSON) || '{"widgets":[]}');
  const entry = manifest.widgets.find((w: { id: string }) => w.id === id);
  const regs: string[] = [];
  if (entry) {
    for (const portal of entry.contexts) {
      const f = path.join(OUT, `registration.${portal}.json`);
      fs.writeFileSync(f, JSON.stringify({ bundle: `${portal.toLowerCase()}-portal@<build>`, portal,
        widgets: [{ ...entry, routes: entry.routes.filter((r: { portal: string }) => r.portal === portal) }] }, null, 2) + '\n');
      regs.push(rel(f));
    }
  }
  const shots = fs.readdirSync(OUT).filter((f) => f.endsWith('.png')).map((f) => rel(path.join(OUT, f)));
  const failed = steps.filter((s) => !s.ok);
  const pending = steps.some((s) => s.skipped && s.ok);
  const report = [
    `# ${id} - ${failed.length ? `${failed.length} step(s) failed` : pending ? 'built and tested; waits for the backend' : 'ready to register'}`, '',
    ...steps.map((s) => `- ${s.skipped ? '–' : s.ok ? '✓' : '✗'} ${s.name}${s.skipped && s.ok ? ' (pending: backend stub not installed)' : ''}`), '',
    ...(stubs.length ? [`Backend: ${stubEndpoints.length} new endpoint(s) on ${[...new Set(stubEndpoints.map((e) => e.perm))].join(', ')} - `
      + (withBackend ? 'stub installed in the backend source; it answers 501 until implemented.' : `proposal in \`${rel(stubDir)}/\`; install with \`--with-backend\`.`)
      + (notes !== undefined ? ` What it must do: \`${rel(path.join(OUT, 'BACKEND-NOTES.md'))}\`.` : ''), ''] : []),
    `Harness: \`npm run workbench\` → http://localhost:4300/w/${id}`,
    `Screenshots: ${shots.length ? shots.map((x) => '`' + x + '`').join(', ') : 'none'}`,
    `Registration payloads: ${regs.map((x) => '`' + x + '`').join(', ') || 'none'}`,
    'Register at deploy: `WIDGET_REGISTRY_TOKEN=… npm run widgets:register -- --portal <P> --bundle <portal>@<sha> --url <backend>/internal/ui/widgets/register`',
    '', 'Note: the answer\'s code ran in your local tests - read an AI answer before applying it.',
  ].join('\n');
  fs.writeFileSync(path.join(OUT, 'REPORT.md'), report + '\n');
  if (failed.length) {
    const repair = [
      `REPAIR REPORT for widget ${id}. Fix these and answer with only the changed files (and the DATA-SOURCES block if it changed), in the same "=== FILE: … ===" format.`, '',
      ...failed.filter((s) => !s.skipped).map((s) => `## ${s.name}\n\n${fence('', tail(s.out))}`),
    ].join('\n');
    fs.writeFileSync(path.join(OUT, 'REPAIR.md'), repair + '\n');
    console.log(`\n✗ ${failed.length} step(s) failed. Paste ${rel(path.join(OUT, 'REPAIR.md'))} back into the chat.`);
    console.log(`  report: ${rel(path.join(OUT, 'REPORT.md'))}`);
    process.exit(1);
  }
  console.log(`\n✓ ${id} ${pending ? 'is built and tested on fixtures; registration waits for the backend stub' : 'is ready to register'}.\n  report: ${rel(path.join(OUT, 'REPORT.md'))}\n  screenshots: ${shots.join(', ')}\n  registration: ${regs.join(', ')}`);
}

// ------------------------------------------------------------------ stubcheck (contracts vs. a backend stub)

/** Each NEW endpoint's records must be exactly what the widget's contracts say (names aside). */
async function cmdStubCheck(args: string[]) {
  const [id, stubDir] = args;
  const f = findWidgets().find((w) => path.relative(WIDGETS, w.dir).split(path.sep).join('.') === id);
  if (!f) { console.error(`no widget ${id}`); process.exit(2); }
  const { def, error } = await load(f);
  if (!def) { console.error(error); process.exit(1); }
  const idx = java.indexTypes(BACKEND_SRC);
  const local = java.indexTypes(stubDir);
  for (const [k, v] of local.records) idx.records.set(k, v);
  for (const [k, v] of local.enums) idx.enums.set(k, v);
  const norm = (p: string) => p.replace(/\{[^}]+\}/g, '{}');
  const strip = (x: unknown) => JSON.stringify(x, (k, v) => (k === 'name' ? undefined : v));
  const shape = (code: string) => new Function('s', `return ${code}`)(shapeLib.s);
  const problems: string[] = [];
  for (const e of java.scanEndpoints(stubDir)) {
    const names = [...def.sources, ...def.commands].filter((n: string) => def.contexts.some((pt: string) => {
      const r = resolveRoute(SOURCES, n, pt);
      return r && r.method === e.method && norm(r.path) === norm(e.path);
    }));
    if (!names.length) { problems.push(`${e.method} ${e.path}: no source or command of ${id} maps to it (data-sources)`); continue; }
    for (const n of names) {
      const want = strip(shape(java.contractFor(e.returnType, idx).code));
      if (!def.contracts?.[n]) problems.push(`${n}: no response contract`);
      else if (strip(def.contracts[n]) !== want) problems.push(`${n}: response contract differs from ${e.returnType} in the stub:\n    stub:   ${want}\n    widget: ${strip(def.contracts[n])}`);
      if (e.body) {
        const wantReq = strip(shape(java.contractFor(e.body.type, idx).code));
        if (!def.requests?.[n]) problems.push(`${n}: no request contract for body ${e.body.type}`);
        else if (strip(def.requests[n]) !== wantReq) problems.push(`${n}: request contract differs from ${e.body.type} in the stub:\n    stub:   ${wantReq}\n    widget: ${strip(def.requests[n])}`);
      }
    }
  }
  if (problems.length) { console.error(problems.join('\n')); process.exit(1); }
  console.log('every stub endpoint matches the widget contracts');
}

// ------------------------------------------------------------------ main

const [cmd, ...rest] = process.argv.slice(2);
const commands: Record<string, (a: string[]) => unknown> = {
  new: cmdNew, check: cmdCheck, gen: cmdGen, register: cmdRegister, endpoint: cmdEndpoint, contract: cmdContract, ui: cmdUi,
  prompt: cmdPrompt, apply: cmdApply, stubcheck: cmdStubCheck,
};
if (!cmd || !commands[cmd]) {
  console.error('usage: node tools/widgets.ts <new|check|gen|register|endpoint|contract|ui|prompt|apply> …');
  process.exit(2);
}
await commands[cmd](rest);
