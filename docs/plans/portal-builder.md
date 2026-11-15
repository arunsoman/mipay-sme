# Portal builder: menus, screens and registered widgets

Status: **in progress** · started 2026-11-15 · KYC first (decided 2026-11-15)

## Goal

A developer builds a portal's navigation (menus and submenus) in a builder. Each submenu opens as an empty screen, and the developer fills it by dragging widgets from a registry. The rules:

- **One widget works in every portal.** A widget reads the context it is placed in (portal, org, screen, page selection) and fetches the right data for it.
- **Widgets hide themselves:**
  - when the user lacks the permission;
  - when the portal has no data source for them;
  - when the server refuses (403).
- **Mobile first.** Every widget declares its grid size for each screen tier. A widget without one does not compile.
- **Testable standalone, FAPI 2.0 by default when placed.**
  - Standalone, a widget runs on fixtures with no gateway and no session.
  - In a portal, the same code goes through the gateway BFF lane (`fapi2-design.md` §4, I4). There it is compliant by construction, because that lane is its only way to reach the network.

## Where the code is today (2026-11-15)

| Fact | Where | Consequence |
|---|---|---|
| Menus, screens and per-screen resources are hand-written TS | `shared/web-core/portal-permission-catalogue.ts`, `menu.ts` | The builder's output must *become* this catalogue, not a second model |
| Self-hiding by permission exists | `AccessService.can`, `*mpPerm`, `permGuard` | Widgets reuse it unchanged |
| Browser holds no tokens; gateway is the confidential FAPI client | `session.ts`, `fapi2-design.md` I4 | A widget is FAPI-compliant if its only path is same-origin `/api` + cookie + `X-CSRF` |
| Three different HTTP setups | admin `adminAuthInterceptor`, org `portalAuthInterceptor`, sme-ui none | **sme-ui sends no `X-CSRF` on any mutation**: `core/http.ts` `HttpInterceptor` is never registered (`app.config.ts` uses plain `provideHttpClient()`) |
| No step-up handling | all portals | RFC 9470 challenges (§4.3) are not handled anywhere |
| Screens inject concrete services with fixed URLs per persona | `MerchantService`, `SupplierService`, KYC components | Screens can't adapt to their context or run standalone |
| No breakpoint system | three ad-hoc `@media (max-width: …)` queries, no tokens, no container queries | Mobile-first grid has to be introduced |
| No component tests | zero `*.spec.ts`; schematics `skipTests: true`; Playwright only in Python UAT tooling | Testing starts from zero |
| No drag-and-drop library | `@angular/cdk` not installed | Add it for the builder |

## Decisions

| # | Decision | State |
|---|---|---|
| 1 | **Layouts are build-time.** The builder writes `layouts/<portal>.json` into the repo; CI checks it and regenerates the nav catalogue. Runtime layouts (business admins editing, backend store, maker/checker publish) are phase 7. | proposed |
| 2 | **One network path.** Widgets reach data only through `WidgetDataPort`. In portals it is `GatewayDataPort`; standalone it is `FixtureDataPort`. Lint bans `HttpClient`, `fetch`, `XMLHttpRequest` and storage APIs under `shared/widgets/**`. | proposed |
| 3 | **Widget visibility follows permissions only.** There are no per-widget `nav.*` cells. Widgets that share a cell switch together, and the RBAC screen shows "also on: …" (as decision 2A of `role-menu-builder.md`). | proposed |
| 4 | **Screen ids are immutable once published.** Labels can change. Builder-made screens get a menu cell without the menu id, `nav.<portal>_<screenUid>`, so moving a screen between menus keeps its grants. The 177 existing rows stay as they are. | proposed |
| 5 | **New screens in menu-scoped portals start hidden.** The publish step warns which roles won't see them. The RBAC screen offers a one-click "show to roles that already hold its permissions"; menu cells grant no API access. | proposed |
| 6 | **Permission coherence blocks merges.** Every endpoint a widget's sources and commands call must enforce a cell the widget declares (`requires` or `actions`). Every declared cell must exist in the `@Perm` catalogue. | proposed |
| 7 | **The registry server holds manifests, never code.** Widget code ships only inside each portal's reviewed build. Remote code on the portal origin would ride the session cookie and `X-CSRF`. | proposed |
| 8 | **Mobile-first grid by container width.** Four tiers (`xs` 4 cols < 600px, `sm` 8 cols < 960px, `md` 12 cols < 1280px, `lg` 12 cols). Every widget declares `size` for every tier with `span.*`, checked by the compiler. Layouts store order + optional spans, never x/y. | proposed |
| 9 | **Required fixture scenarios:** `default`, `empty`, `error` (checked by the compiler). `longText` is also required for widgets that show user-entered text. | proposed |
| 10 | **Test runners:** Vitest (Angular 20 `@angular/build:unit-test`) for unit tests, `@playwright/test` (Node, headless only) for anything in a browser. Visual baselines are made and compared only in the CI container. | proposed |
| 11 | **`requires` takes an OR list (like `menu.ts`) or `{ all: [...] }`.** Use `all` when the widget can't work without several rows (the KYC editor needs `kyc.application` and `kyc.form`). | proposed |

## Design

### 1. The widget contract

A widget is a folder under `frontend/projects/shared/widgets/<area>/<name>/`:

```
<name>.widget.ts      manifest: pure TS, no Angular import (read by the builder, RBAC model, generator, Node tests)
<name>.component.ts   the UI, built only from the shared design-system components
<name>.fixtures.ts    scenarios for standalone use
<name>.spec.ts        unit tests (the conformance, layout and a11y tests are generated)
```

```ts
export interface WidgetDef<C = unknown> {
  id: string;                         // 'kyc.application' — stable forever
  version: string;                    // semver; layouts pin the major
  label: string; category: string;
  contexts: BusinessPortal[] | ((ctx: WidgetContext) => boolean);
  requires: string[] | { all: string[] };          // decision 11
  actions: WidgetAction[];            // named, gated buttons: { id, label, cell, command? }
  sources: string[];                  // logical read sources, never URLs
  commands?: string[];                // logical writes
  inputs?: Record<string, { from: string[] }>;     // e.g. orgId from ['selection.orgId', 'context.orgId']
  publishes?: string[];               // page-selection keys this widget writes (e.g. 'selection.orgId')
  config: ConfigSchema<C>;            // drives the builder's inspector form
  size: Record<Tier, TierSize>;       // decision 8 — required for every tier
  minHeight?: string;                 // rem, optional (charts, KPI tiles)
  hideOn?: Array<'forbidden' | 'empty'>;
  load: () => Promise<Type<unknown>>; // lazy component chunk
}
```

`defineWidget()` is the only constructor. It is typed so that a missing or out-of-range field is a compile error.

### 2. Context

The host creates each placed widget with a child injector that provides:

| Token | Contents |
|---|---|
| `WIDGET_CONTEXT` | signal of `{ portal, orgId, orgType, screenKey, routeParams, mode: 'live' \| 'design' \| 'fixture' }` |
| `PAGE_SELECTION` | page-level signal store; widgets write the keys they `publish` and read the keys their `inputs` name |
| `WIDGET_INPUTS` | the resolved `inputs` (first non-empty `from` path wins) |
| `WIDGET_CONFIG` | this instance's settings from the layout |
| `WIDGET_SIZE` | `{ tier, span, widthPx }` from a ResizeObserver on the cell |
| `WidgetDataPort` | `GatewayDataPort` (live) or `FixtureDataPort` (workbench, builder, tests) |

The builder checks wiring when a layout is saved. Any `inputs` path that nothing on the page publishes, and that the context cannot supply, is an error.

### 3. Data sources and the port

`shared/widgets/data-sources.ts` maps each logical source or command to a request, per portal:

```ts
'kyc.application.get': { '*': { method: 'GET', path: '/api/kyc/orgs/{orgId}' } },
'orders.list': {
  SUPPLIER: { method: 'GET', path: '/api/supplier/orders' },
  MERCHANT: { method: 'GET', path: '/api/merchant/orders' },
},
```

`GatewayDataPort`:
- fills path parameters from params;
- refuses anything that isn't a relative `/api/` path;
- sends through the one shared `fapiBffInterceptor` (in `shared/web-core`). That interceptor replaces the three current setups and fixes the sme-ui CSRF gap.

It maps the gateway's answers:

| Gateway answer | Port behaviour |
|---|---|
| `401 login_required` | `session.login(returnTo)` |
| `401` + `WWW-Authenticate … insufficient_user_authentication` (RFC 9470) | "Confirm it's you" → gateway step-up → retry (see open item O1) |
| `403` | widget state `forbidden` → hidden when `hideOn` has it |
| ProblemDetail with `code` | passed to the widget as `ApiProblem` (reuses `readProblem`) |

`FixtureDataPort` serves the chosen scenario and records every call.

### 4. Auto-hide (the server stays the authority)

1. **Permission:** if `requires` fails `access.can`, the widget is never created.
2. **Context:** if the widget's `contexts` exclude the portal, or a source has no mapping for it, the widget isn't in that portal's palette and doesn't render.
3. **Runtime:** on `403` (or `empty`, per `hideOn`), the cell collapses and the grid reflows (`grid-auto-flow: row dense`).

A screen's resources are the union of its widgets' `requires`, worked out automatically. So `liveNavigation`, `navAllows` and `permGuard` keep working unchanged.

### 5. Layout model and renderer

```ts
interface PortalLayout { portal: BusinessPortal; version: number; menus: LayoutMenu[] }
interface LayoutMenu   { id: string; label: string; icon: string; screens: LayoutScreen[] }
interface LayoutScreen { uid: string; id: string; label: string; slug: string; items: PlacedWidget[] }
interface PlacedWidget {
  instanceId: string; widgetId: string; major: number; config: unknown;
  order: number;                                        // phone (xs) reading order
  span?: Partial<Record<Tier, number>>;                 // within the widget's min..max
  orderOverride?: Partial<Record<'md' | 'lg', number>>;
}
```

- A generic route `/p/:menu/:screen` renders a `PortalPage`, guarded by `permGuard(screen.resources, { portal, link })`.
- `tools/gen-nav-catalogue.ts` reads the layouts as well as the hand-written catalogue.
- Existing hand-built screens are registered as full-width page widgets, so migration happens one screen at a time.

### 6. Mobile-first grid (decision 8)

- `portal-theme.scss` gets breakpoint tokens. The page grid is a container (`container-type: inline-size`): 4 columns, then `@container (min-width: 600px)` → 8, `960px` → 12.
- Each cell is also a container. Widgets style themselves with `@container`, never `@media` (lint rule).
- The shared components adapt once for every widget: `mp-data-table` gets a compact card mode below a set width, and `kpi-row` wraps.

Size is checked by the compiler:

```ts
type Nums<N extends number, A extends unknown[] = [unknown]> =
  A['length'] extends N ? N : A['length'] | Nums<N, [...A, unknown]>;           // 1..N
type Between<Lo extends number, Hi extends number> = Exclude<Nums<Hi>, Exclude<Nums<Lo>, Lo>>;
export const COLS = { xs: 4, sm: 8, md: 12, lg: 12 } as const;
function tier<C extends number>(cols: C) {
  return <Min extends Nums<C>, Def extends Between<Min, C> = Min, Max extends Between<Def, C> = C>
    (min: Min, def?: Def, max?: Max): Span => ({ kind: 'span', min, default: def ?? min, max: max ?? cols });
}
export const span = { xs: tier(COLS.xs), sm: tier(COLS.sm), md: tier(COLS.md), lg: tier(COLS.lg),
                      hidden: (reason: string): Hidden => ({ kind: 'hidden', reason }) };
// WidgetDef.size: Record<Tier, Span | Hidden>  — every tier required
```

Each of these is a build error:
- a missing `size` or a missing tier;
- `span.xs(6)` (wider than the phone grid);
- a default below its min, or a max below its default.

`span.hidden(reason)` is allowed but needs a reason. It is a layout choice, not a permission.

### 7. Registry

| Half | Lives in | Read by |
|---|---|---|
| Manifest | `*.widget.ts` → `npm run widgets:gen` → `registry.generated.ts` + `widget-manifest.json` (CI `--check`) → registry server | builder palette, RBAC screen, CI, layout publish |
| Code | lazy chunk inside each portal build whose `contexts` include it | the portal at runtime |

**Registry server** (phase 6, modelled on `EndpointManifestPublisher`):
- On deploy, each portal build posts its manifest over the internal network to `POST /internal/ui/widgets/register` with `{ bundle: "<portal>@<sha>", portal, widgets[] }`.
- The server validates the manifest against the live endpoint manifest (decision 6).
- It records which build carries which widget version, and marks widgets missing from the new build as retired, listing the layouts and roles that still use them.
- Read API: `GET /api/ui/widgets?portal=` (RBAC-gated, browser lane).

Until phase 6, `widget-manifest.json` stands in for the server.

**Versioning:**
- Layouts pin a major version. A new major ships with `migrate(oldConfig)`.
- A widget can't be removed from a build while a published layout uses it.

### 8. Effect on the RBAC screen

- `role-menu-model.ts` takes the catalogue as a parameter instead of importing the constant. It is pure, so this is a small refactor. `isLive` becomes "published with at least one widget". `EXTRA_GATES` and most of `LIVE_SCREEN_ROUTES` go away as screens migrate.
- `mp-role-menu-builder` gets a third level: **menu → submenu → widget → named actions** ("Issue invoice: allow") instead of raw verb chips per row.
- The RBAC role editor gets **"Preview as this role"**: the real page on fixtures, with `AccessService` replaced in the preview injector by the role's cells.
- Unchanged: ceilings in User management, inherited cells and `lockedVisible`, one portal per role, the `holdsAll` exemption for `nav.*`.
- The Node tests move to fixture catalogues. The "every live screen switches alone" rule becomes a property test over any layout.

### 9. The builder

It is a project in the workbench (decision 1), using `@angular/cdk/drag-drop`, with three panes:
- **Menu tree:** add, rename, reorder.
- **Canvas:** the empty screen grid. It opens on **Phone**, with a tier switcher. Dragging reorders; resizing snaps to columns within the widget's min/max.
- **Inspector:** a form from `config`, the computed required permissions, and the roles that would see the screen.

The canvas always runs in `design` mode on fixtures. "Save" writes `layouts/<portal>.json`.

### 10. Testing

| Layer | What | Tool |
|---|---|---|
| Workbench | All four tiers side by side. Context / cells / scenario / theme switches. Overlays: column grid, overflow, spacing, tap targets. Network panel with source → resolved endpoint, params, status, payload. State badge with received vs rendered counts. Every view has its own URL. | `ng serve widget-workbench` (port 4300) |
| Unit | `renderWidget(def, { portal, tier, scenario, cells, selection })` harness: port calls, state, DOM, gated actions | Vitest |
| Generated per widget | FAPI conformance (relative `/api` only, `X-CSRF` on mutations, never `Authorization`, step-up handled, 403 hides); every scenario reaches its state; geometry per tier at min and default span (no x-overflow, nothing outside its cell, edges on column lines ±1px, no clipped text, tap ≥ 44px on `xs`); axe-core; screenshot per tier × scenario × theme | Vitest + `@playwright/test` headless |
| Page | Layouts render in the workbench (`/layout/<portal>/<menu>/<screen>`): no overlapping cells, phone order matches `order`, hidden widgets leave no gaps | Playwright |
| Live smoke | Isolated compose project (never the live stack): seeded users, real gateway, real data, a 403 for a missing cell, step-up | Playwright, before deploy |

### 11. Developer commands

```bash
npm run widget:new   -- <area>/<name>      # scaffold
npm run workbench                         # see it, all tiers
npm run fixtures:capture -- <source> …    # record real responses from the isolated stack, PII scrubbed
npm run test:widget  -- <id>              # unit + generated conformance
npm run test:layout  -- <id>              # headless tiers × scenarios × themes; HTML report
npm run widgets:gen                       # registry.generated.ts + widget-manifest.json
npm run widgets:check                     # coherence (decision 6), contracts, version rules
```

## Steps

| Step | Work | State |
|---|---|---|
| 0 | Shared `fapiBffInterceptor` in `shared/web-core`; register it in sme-ui, admin and org portals (fixes the sme-ui `X-CSRF` gap) | admin + org portals use it; **sme-ui still sends no `X-CSRF`** |
| 1 | Widget contract, `defineWidget`, `span.*` types, `WidgetDataPort` (gateway + fixture), data-source map, lint rules, `widgets:gen` / `widgets:check` | done (checks live in `widgets:check`, not ESLint) |
| 2 | Workbench project + unit harness + generated conformance/layout/a11y suites; Vitest + Playwright set up | done except axe (a11y) and screenshot baselines |
| 3 | Breakpoint tokens + container grid in `portal-theme.scss`; compact modes in `mp-data-table`, `kpi-row`; mobile check of the portal shells' sidebars | not started |
| 4 | First widgets: **KYC application** (Appendix A), **KYC review queue** (`kyc.tasks`, built with the skill) | done; KPI row, orders table, org tree deferred (KYC first) |
| 5 | Layout model, `PortalPage` + `/p/:menu/:screen`, `gen-nav-catalogue` reads layouts; `role-menu-model` takes the catalogue as input; RBAC builder third level + preview-as-role | portals draw a published layout (2026-11-16): `shared/web-core/portal-layout.ts` (`PortalLayoutService`, `layoutNavigation`, `LayoutPage` at `/p/:menu/:screen` with `layoutScreenGuard`) in the admin and org shells. Widget screens show when one widget's `requires` is met; empty seeded screens keep today's coded page and rule; no layout = coded menus. Not yet: menu cells for new screens (`gen-nav-catalogue` does not read layouts), RBAC third level |
| 6 | Builder UI (menu tree, canvas, inspector); registry server endpoints + deploy-time registration | builder UI with i18n menus done (workbench `/builder`); registry server done (`/internal/ui/widgets/register`, `/api/ui/widgets`); deploy-time registration not wired |
| 7 | (optional) Runtime layouts: backend store, maker/checker publish, live `nav.*` registration | built (2026-11-16): `V5 ui_layouts`, `PortalLayoutService` (DRAFT → PENDING → PUBLISHED → RETIRED, four-eyes by builder user, unpublish), `/internal/ui/builder/**` (builder key + nginx basic-auth user), `GET /api/ui/layouts/{portal}`; the builder's **Publish** view; hosted at portalbuilder.afriqpay.com (`Dockerfile.builder`, `deploy/afriqpay/nginx-portalbuilder.conf`). Live `nav.*` registration not done |

## Built so far (2026-11-15)

| Piece | Where | Notes |
|---|---|---|
| Response + request contracts | `shared/widgets/core/shape.ts`, `<widget>.contract.ts` | generated from the backend's Java records (`npm run widgets:contract`, `--request` for bodies; `@NotNull/@NotBlank` → required); hand-written below the markers where the server is untyped |
| Self-diagnosis | `ScopedDataPort`, `WidgetHost` | every answer and every sent body checked; findings in the widget's cell in the harness (`WIDGET_DIAGNOSTICS = 'panel'`), console in portals |
| Endpoint resolver | `tools/widget-lib/java.ts`, `npm run widgets:endpoint` | controller, `@Perm` cell, params, body, response from the Java sources |
| UI catalogue + reuse rules | `tools/widget-lib/catalogue.ts`, `npm run widgets:ui` | `widgets:check` refuses own components, unknown tags, raw `<table>/<button>/<input>/<select>` |
| Live harness | `widget-workbench/api-services.json`, `proxy.conf.mjs`, `connection.ts` | `WB_SERVICE=<id> npm run workbench:live` on the portal host; one sign-in through the gateway BFF (FAPI 2.0 stays in the gateway); real permissions and org |
| API catalogue + backend stubs | `widgets:prompt` (≈240 endpoints, cells, shapes), `widgets:apply` (`BACKEND` blocks, `stubcheck`, `--with-backend`) | the AI reuses an endpoint or proposes a new cell + endpoint + shapes + `@Perm` stub; proven with a test widget (two NEW endpoints, stub compiled and validated; removed after the test) |
| Portable prompt (any LLM) | `docs/widget-prompt/`, `npm run widgets:prompt` / `widgets:apply` | prompt with rules, API, catalogue (inputs + defaults + types), cells, reference widget, endpoints from the backend; apply writes one widget folder and runs checks, tests, layout, screenshots, registry validation; REPAIR.md loop. Proven with `kyc.orgs` (one repair round) |
| Skill | `.claude/skills/mipay-aruns-ui/SKILL.md` | requirement → operations sheet (endpoint, request shape, response shape per operation, confirmed with the user) → finished widget |

## Open items

| # | Item |
|---|---|
| O1 | **Step-up replay for XHR.** §4.3 says the gateway replays the original request, but a full-page step-up redirect loses in-memory state. Choose between a step-up popup window and the user retrying (the widget declares which). Depends on P4. |
| O2 | Should `lg` get 16 columns for very wide screens, or stay at 12? |
| O3 | Do the portal shells turn the sidebar into a drawer below `sm`? Not checked yet. |
| O4 | The live harness has been tested against a simulated gateway only; the local gateway was crash-looping on 2026-11-15. Run `workbench:live` against the real stack once it is healthy. |
| O5 | Builder exports are not consumed yet: no runtime `/p/:menu/:screen` renderer (step 5). |

---

## Appendix A. Worked example: the KYC application editor as a widget

This walks the real KYC editor (`shared/kyc/kyc-application.ts` + `kyc-schema-editor.ts`) through build, test and publish. It is the first widget of step 4.

### A.1 What the existing component does

| Concern | Today | Problem for a widget |
|---|---|---|
| Which org | `orgId` input, set by the host page (admin: picked in the directory; org portal: own org) | Must come from context or page selection |
| Reads | `GET /api/orgs/{id}/kyc` (`kyc.application:read`), `GET /api/kyc/schema/{orgId}` (`kyc.form:read`), `GET /api/kyc/lists/{code}[?parent=]` (`kyc.form:read`) | Direct `HttpClient` calls |
| Writes | `PUT /api/kyc/orgs/{id}?expectedVersion=` (`modify`), `POST …/submit` (`submit`), `POST …/withdraw` (`delete`) | Direct `HttpClient` calls |
| Gating | `*mpPerm` on Submit and Withdraw; server-side `canEdit` / `masked` | Fine; becomes named actions |
| Schema failure | `catchError(() => of(null))`: a 403 on `kyc.form:read` gives a form with no schema | Should be the `forbidden` state |
| Layout | `@media (max-width: 640px) { .f { grid-template-columns: 1fr } }` (desktop-first, viewport) | Becomes a mobile-first `@container` |
| Server problems | `KYC_INVALID` / `KYC_INCOMPLETE` problems are listed under the field they name; other codes use `KYC_HINTS` | Keep as is |

### A.2 Scaffold

```bash
npm run widget:new -- kyc/application
```
```
frontend/projects/shared/widgets/kyc/application/
  kyc-application.widget.ts
  kyc-application.component.ts
  kyc-application.fixtures.ts
  kyc-application.spec.ts
```

### A.3 Data sources (one entry each, shared by every portal)

The endpoint is the same in every portal. Context only changes **which org**, not the URL. Compare `orders.list`, where the URL itself changes per portal.

```ts
// shared/widgets/data-sources.ts
'kyc.application.get':      { '*': { method: 'GET',  path: '/api/kyc/orgs/{orgId}' } },
'kyc.schema.get':           { '*': { method: 'GET',  path: '/api/kyc/schema/{orgId}' } },
'kyc.list.get':             { '*': { method: 'GET',  path: '/api/kyc/lists/{code}', query: ['parent'] } },
'kyc.application.save':     { '*': { method: 'PUT',  path: '/api/kyc/orgs/{orgId}', query: ['expectedVersion'] } },
'kyc.application.submit':   { '*': { method: 'POST', path: '/api/kyc/orgs/{orgId}/submit' } },
'kyc.application.withdraw': { '*': { method: 'POST', path: '/api/kyc/orgs/{orgId}/withdraw' } },
```

### A.4 Manifest

```ts
// kyc-application.widget.ts
import { defineWidget, span } from '../../core';

export interface KycApplicationConfig { mode: 'page' | 'wizard'; startStep: number; showProgress: boolean }

export default defineWidget<KycApplicationConfig>({
  id: 'kyc.application',
  version: '1.0.0',
  label: 'KYC application',
  category: 'Onboarding',
  contexts: ['PLATFORM', 'MANUFACTURER', 'DISTRIBUTOR', 'SUPPLIER'],
  requires: { all: ['kyc.application:read', 'kyc.form:read'] },   // useless without the schema
  actions: [
    { id: 'save',     label: 'Save draft',        cell: 'kyc.application:modify', command: 'kyc.application.save' },
    { id: 'submit',   label: 'Submit for review', cell: 'kyc.application:submit', command: 'kyc.application.submit' },
    { id: 'withdraw', label: 'Withdraw',          cell: 'kyc.application:delete', command: 'kyc.application.withdraw' },
  ],
  sources: ['kyc.application.get', 'kyc.schema.get', 'kyc.list.get'],
  commands: ['kyc.application.save', 'kyc.application.submit', 'kyc.application.withdraw'],
  // admin: the org picked by a directory/tree widget on the page; org portals: the user's own org
  inputs: { orgId: { from: ['selection.orgId', 'context.orgId'] } },
  config: {
    mode:         { type: 'enum', options: ['page', 'wizard'], default: 'page' },
    startStep:    { type: 'number', default: 0, min: 0 },
    showProgress: { type: 'boolean', default: true },
  },
  size: {
    xs: span.xs(4),            // a form: always full width on phones
    sm: span.sm(8),
    md: span.md(8, 12),        // never narrower than 8/12: two-column fields need it
    lg: span.lg(8, 12),
  },
  hideOn: ['forbidden'],
  load: () => import('./kyc-application.component').then((m) => m.KycApplicationWidget),
});
```

What the compiler and `widgets:check` would catch here:

```text
kyc-application.widget.ts:31:22 - error TS2345: Argument of type '6' is not assignable to parameter of type '8 | 9 | 10 | 11 | 12'.
    md: span.md(8, 6),

widgets:check ✗ kyc.application
  source kyc.schema.get → GET /api/kyc/schema/{orgId} enforces kyc.form:read (KycController#schemaFor),
  which is not in requires or actions                        ← if requires were ['kyc.application:read'] only
```

### A.5 Component

**Refactor the existing components; don't copy them.** `KycApplication` and `KycSchemaEditor` swap `inject(HttpClient)` for `inject(WidgetDataPort)`. The current hosts (`kyc-workspace.ts`, `onboard-child.ts`, the admin and org KYC pages) get `GatewayDataPort` from `provideWidgetHost({ mode: 'live' })` in each app config, so one component serves both the old pages and the widget.

```ts
// kyc-application.ts — before
firstValueFrom(this.http.get<KycApplicationView>(`/api/orgs/${orgId}/kyc`)),
firstValueFrom(this.http.get<SchemaResponse>(`/api/kyc/schema/${orgId}`).pipe(catchError(() => of(null)))),
// after
firstValueFrom(this.data.query<KycApplicationView>('kyc.application.get', { orgId })),
firstValueFrom(this.data.query<SchemaResponse>('kyc.schema.get', { orgId })),   // a 403 now becomes 'forbidden'

// put()
return firstValueFrom(this.data.command<KycApplicationView>('kyc.application.save',
  { orgId: v.orgId, expectedVersion: v.id ? v.version : undefined, body: this.doc() ?? {} }));

// kyc-schema-editor.ts — list lookups
this.data.query<{ items: ListItem[] }>('kyc.list.get', { code, parent })
```

```scss
/* kyc-schema-editor.ts — before (desktop-first, viewport) */
.f { grid-template-columns: 1fr 1fr; }
@media (max-width: 640px) { .f { grid-template-columns: 1fr; } }
/* after (mobile-first, its own cell's width) */
:host { container-type: inline-size; }
.f { grid-template-columns: 1fr; }
@container (min-width: 560px) { .f { grid-template-columns: 1fr 1fr; } }
```

The widget component is a thin wrapper:

```ts
// kyc-application.component.ts
@Component({
  selector: 'mpw-kyc-application',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [KycApplication, MpEmptyState],
  template: `
    @if (orgId(); as id) {
      <mp-kyc-application [orgId]="id" [wizard]="config.mode === 'wizard'"
                          (changed)="state.ready()" (loadFailed)="state.fromProblem($event)" />
    } @else {
      <mp-empty-state title="Pick an organization" text="Choose one from the directory to see its KYC application." />
    }`,
})
export class KycApplicationWidget {
  readonly config = inject(WIDGET_CONFIG<KycApplicationConfig>);
  readonly state  = inject(WIDGET_STATE);                 // loading | ready | empty | error | forbidden
  readonly orgId  = inject(WIDGET_INPUTS).orgId;          // signal: selection.orgId ?? context.orgId
}
```

The action buttons inside `KycApplication` keep `*mpPerm`. The manifest's `actions` only name them for the RBAC screen and the builder.

### A.6 Fixtures

Record real shapes once from the **isolated** stack; the PII is scrubbed:

```bash
npm run fixtures:capture -- kyc.schema.get kyc.application.get kyc.list.get \
  --stack sme-test --as supplier-admin --org <supplier org id> --out kyc/application/captured/
```

```ts
// kyc-application.fixtures.ts
import schema from './captured/schema.supplier.json';
import draft from './captured/application.draft.json';
import banks from './captured/list.BANK.json';
import branches from './captured/list.BANK_BRANCH.fnb.json';

const lists = { 'kyc.list.get': route({ BANK: banks, 'BANK_BRANCH?parent=FNB': branches }) };

export default fixtures<KycApplicationData>({
  // required (decision 9)
  default: { ...lists, 'kyc.schema.get': schema, 'kyc.application.get': draft },
  empty:   { ...lists, 'kyc.schema.get': schema,
             'kyc.application.get': { ...draft, id: null, status: 'NOT_STARTED', application: null } },
  error:   { 'kyc.application.get': fail(500), 'kyc.schema.get': schema },
  longText: { ...lists, 'kyc.schema.get': schema,
              'kyc.application.get': withLongNames(draft, 'Namib Wholesale & Distribution (Proprietary) Limited — Walvis Bay Branch') },
  // KYC-specific
  inReview:  { ...lists, 'kyc.schema.get': schema, 'kyc.application.get': { ...draft, status: 'IN_REVIEW', canEdit: false } },
  masked:    { ...lists, 'kyc.schema.get': schema, 'kyc.application.get': { ...draft, masked: true, canEdit: false } },
  invalid:   { ...lists, 'kyc.schema.get': schema, 'kyc.application.get': draft,
               'kyc.application.save': fail(422, { code: 'KYC_INVALID',
                 detail: 'ubos[0].dateOfBirth: required; bank.accountNumber: must be 8–11 digits' }) },
  conflict:  { ...lists, 'kyc.schema.get': schema, 'kyc.application.get': draft,
               'kyc.application.save': fail(409, { code: 'KYC_VERSION_CONFLICT' }) },
});
```

The contract test fails when a captured fixture no longer matches `KycApplicationView` or `SchemaResponse`. Re-capture to update it.

### A.7 Look at it in the workbench

```bash
npm run workbench
# http://localhost:4300/w/kyc.application?portal=SUPPLIER&scenario=default
```

Go through this checklist:

| Check | Where to look | Expected |
|---|---|---|
| Fetching | Network panel | `kyc.application.get → GET /api/kyc/orgs/org-sup-1 200`, `kyc.schema.get → GET /api/kyc/schema/org-sup-1 200`, then `kyc.list.get BANK` |
| Chained lists | Pick bank "FNB" | A new call `kyc.list.get BANK_BRANCH?parent=FNB`; the branch dropdown fills |
| Rendering | State badge | `ready`; sections rendered = schema sections visible for the step |
| Context | Switch portal to PLATFORM | "Pick an organization" (nothing publishes `selection.orgId`); set the selection picker → loads that org |
| Permissions | Untick `kyc.application:submit` | Submit button gone, Save stays |
| | Untick `kyc.form:read` | Whole widget hidden (`requires.all`) |
| Server rules | Scenario `inReview` / `masked` | Read-only form; masked note shown |
| Field problems | Scenario `invalid`, press Save | "Not saved: 2 problem(s)"; messages under date of birth and account number |
| Layout | Tier frames, grid overlay on | Phone: one column of fields, stepper fits in 360px, Yes/No buttons don't overflow; Laptop: two columns at 8/12 |
| Long text | Scenario `longText` | Org name and tree path wrap; nothing outside the cell |
| Themes | Theme switch | Status chips readable in both themes |

### A.8 Unit tests

```ts
// kyc-application.spec.ts
describe('kyc.application', () => {
  it('loads application and schema for the own org in an org portal', async () => {
    const w = await renderWidget(KycApplicationDef, { portal: 'SUPPLIER', orgId: 'org-sup-1',
      scenario: 'default', cells: ['kyc.application:read', 'kyc.form:read'] });
    expect(w.port.calls.map((c) => [c.source, c.params])).toEqual([
      ['kyc.application.get', { orgId: 'org-sup-1' }],
      ['kyc.schema.get',      { orgId: 'org-sup-1' }],
      ['kyc.list.get',        { code: 'BANK' }],
    ]);
    expect(w.state()).toBe('ready');
  });

  it('prefers the page selection over the own org (admin directory)', async () => {
    const w = await renderWidget(KycApplicationDef, { portal: 'PLATFORM', orgId: 'org-root',
      selection: { orgId: 'org-dist-7' }, scenario: 'default', cells: ALL_KYC });
    expect(w.port.calls[0].params).toEqual({ orgId: 'org-dist-7' });
  });

  it('saves with the expected version and shows field problems on KYC_INVALID', async () => {
    const w = await renderWidget(KycApplicationDef, { portal: 'SUPPLIER', orgId: 'org-sup-1',
      scenario: 'invalid', cells: ALL_KYC });
    await w.click('[data-action=save]');
    expect(w.port.lastCommand()).toEqual(expect.objectContaining({
      command: 'kyc.application.save', params: { orgId: 'org-sup-1', expectedVersion: 3 } }));
    expect(w.text('.flash')).toContain('2 problem(s)');
    expect(w.problemsUnder('ubos[0].dateOfBirth')).toEqual(['required']);
  });

  it('hides Submit and Withdraw without their cells; read-only when the server says canEdit=false', async () => {
    const w = await renderWidget(KycApplicationDef, { portal: 'SUPPLIER', orgId: 'org-sup-1',
      scenario: 'inReview', cells: ['kyc.application:read', 'kyc.form:read'] });
    expect(w.query('[data-action=submit]')).toBeNull();
    expect(w.query('[data-action=withdraw]')).toBeNull();
    expect(w.query('fieldset.sec')!.hasAttribute('disabled')).toBe(true);
  });

  it('is hidden when the schema read is forbidden', async () => {
    const w = await renderWidget(KycApplicationDef, { portal: 'SUPPLIER', orgId: 'org-sup-1',
      scenario: 'default', override: { 'kyc.schema.get': fail(403) }, cells: ALL_KYC });
    expect(w.state()).toBe('forbidden');
    expect(w.hostHidden()).toBe(true);
  });
});
```

```bash
npm run test:widget -- kyc.application
```

### A.9 The generated suites

`npm run test:layout -- kyc.application` runs, headless, with no code to write:
- **FAPI conformance:** 6 routes; every call is relative `/api/kyc/…`, the PUT and both POSTs carry `X-CSRF`, there is no `Authorization` header, a 401 step-up on submit opens the confirm flow, and a 403 hides the widget.
- **States:** 8 scenarios, each reaching its expected state.
- **Geometry:** 4 tiers × min/default span × 8 scenarios.
- **Accessibility:** axe on every render.
- **Visual:** 4 tiers × 8 scenarios × 2 themes = 64 screenshots.

A typical first failure, and its fix:

```text
✗ kyc.application @xs longText — geometry
  overflowX: .ptitle .chip (SUSPENDED) right edge 371px > cell 344px
  reproduce: http://localhost:4300/w/kyc.application?tier=xs&scenario=longText&portal=SUPPLIER
fix: .ptitle { flex-wrap: wrap; }  → re-run → ✓
```

The HTML report (`frontend/test-results/layout/index.html`) shows a grid of all the screenshots for review.

### A.10 Publish

1. **Generate and check:**
   ```bash
   npm run widgets:gen      # registry.generated.ts + widget-manifest.json now list kyc.application@1
   npm run widgets:check    # ✓ coherence: 6 routes, cells covered; ✓ contracts: 4 portals × 3 sources
   ```
2. **Place it on a screen.** In the builder (`/builder`): choose the Supplier portal, open the menu *Onboarding › KYC and documents* (screen key `SUPPLIER/onboarding/documents`), drop **KYC application**, and check the Phone preview, then Laptop. Save. This writes `layouts/SUPPLIER.json`. Do the same for Manufacturer and Distributor. For Admin, place an **Org directory** widget (publishes `selection.orgId`) next to it.
3. **Effect on navigation and RBAC:**
   - `gen-nav-catalogue` regenerates.
   - The screen key is unchanged, so the menu cell `nav.supplier_onboarding_documents` is unchanged and **no role loses or gains the screen**.
   - The route moves from `/kyc` to `/p/onboarding/documents`.
   - In the RBAC screen, that submenu now lists *KYC application: view · Save draft · Submit for review · Withdraw*.
4. **Pull request.** CI runs lint, unit, generated suites, `widgets:check` and layout checks. The 64 baseline PNGs and the layout JSON diff are reviewed in the PR.
5. **Live smoke on the isolated compose stack:**
   - supplier admin edits and submits their own KYC;
   - the parent distributor admin sees it masked and read-only;
   - a platform reviewer picks the org in the directory and sees it;
   - a user without `kyc.form:read` doesn't see the widget, and `GET /api/kyc/schema/…` returns 403.
6. **Deploy** the portals (admin + three org portals) with a rollback tag, as for other portal deploys. In phase 6 and later, each portal registers its manifest on start: `POST /internal/ui/widgets/register { bundle: "supplier-portal@<sha>", widgets: [kyc.application@1.0.0, …] }` → validated against the live endpoint manifest.
7. **Roll back:** revert the layout JSON (the screen goes back to its previous widgets or route), or redeploy the previous tag.
