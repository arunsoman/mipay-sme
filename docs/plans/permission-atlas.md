# Permission atlas: one dictionary, kept in sync everywhere, with conflict checks

Status: **proposed** · started 2026-11-15

## Goal

1. **Sync.** Every system that names a permission reads the same definitions, and every system learns about a rights change. The systems are:
   - the backend (`@Perm`);
   - trustplane RBAC;
   - the gateway;
   - the admin portal's **Roles and permissions** screen;
   - User management in the org portals;
   - sme-ui;
   - the widget registry and the portal builder.

   Nothing is hand-copied, nothing goes stale, and a browser sees a revoked right without signing in again.
2. **Conflicts.** Every way two permissions, roles or edits can clash is detected in one place. Each conflict gets a severity and a fix, and is shown while a role is being designed, not after it breaks something.
3. **Meaning.** For any cell (`row:verb`), anyone can see:
   - what it lets the holder do **in the backend**: which endpoints, on which org;
   - what it shows **in the frontend**: which portal, menu, screen, widget or button;
   - who holds it;
   - what it conflicts with.

## Where permission data lives today (studied 2026-11-15)

| # | Copy | Source | How it stays in sync | Status |
|---|---|---|---|---|
| 1 | `@Perm(resource, verb, description)` on 236 handlers (251 endpoints) | backend controllers | **the truth** | complete: 0 unannotated, every `@Perm` has a description |
| 2 | RBAC `rbac_resources`, `rbac_cell_endpoints` | `EndpointManifestPublisher` → `POST /rbac/internal/permissions/register` at backend start | automatic | keeps one description per **cell** (the first endpoint's, `putIfAbsent`); row text is derived from its id ("Kyc application") |
| 3 | RBAC menu rows `nav.*` | `portal-permission-catalogue.ts` → `tools/gen-nav-catalogue.ts` → `nav-catalogue.json` → backend registration | generated, CI `--check` | fine |
| 4 | `shared/web-core/permissions.ts` (`PERMS`, "136 cells, generated against the backend") | **hand-written** | none | **drifted**: missing `admin.org_directory:read`, which the backend enforces and `menu.ts` gates on; labels are generic verbs ("view / list"), not what the cell does |
| 5 | `shared/web-core/menu.ts` (`MENU_ITEMS[].perm`) | hand-written | none (it matches today) | 0 unknown permissions |
| 6 | `portal-permission-catalogue.ts` (screen `resources`) | hand-written | none (it matches today) | 0 unknown rows; **34 rows drive more than one screen** in the same portal |
| 7 | `*mpPerm`, `access.can(...)`, `permGuard`, `perm=` in templates | hand-written | none (it matches today) | 0 unknown cells; 4 backend cells have no UI at all (`admin.bootstrap:administer`, `kyc.form:read`, `ops.monitoring:execute`, `rbac.user:read`) |
| 8 | `src/app/access-map.ts` + sme-ui role guards | hand-written | none | legacy persona gates until cut-over P6 |
| 9 | Widget manifests (`requires`, `actions`) → `widget-manifest.json` → registry server | generated, checked against `@Perm` at registration | automatic | fine (portal-builder decision 6) |
| 10 | RBAC roles, grids, inheritance, ceilings, assignments, grants | Roles and permissions screen, User management | live (RBAC is the database) | role edits have **no version check**: two admins → the last save wins silently |
| 11 | Gateway decision cache | RBAC `WatchHub` → gRPC Watch | push | fine |
| 12 | Browser rights (`AccessService`, `GET /api/orgs/me/access`) | loaded once per session | **none**: only the empty shell's "Reload my rights" | a revoked right stays visible, and a granted one invisible, until reload |
| 13 | `sod_rules` (STATIC / DYNAMIC / OBJECT, by role or by cell pair) | table read by the decision point | **nothing creates a rule** | cross-row maker/checker pairs are not enforced by RBAC (e.g. `kyc.application:submit` vs `kyc.task:authorize`); only same-row pairs are (`SOD_CONFLICT`) |

Measured by a read-only probe that scans the backend's Java and the frontend's TypeScript. Phase 1 makes it the official `perms:drift` command.

## Gaps

| # | Gap | Effect |
|---|---|---|
| G1 | Hand-written frontend permission list (#4) | Drift: a cell the backend enforces is missing from the inventory screen and from any picker built on `PERMS` |
| G2 | One description per cell in RBAC (#2) | The Roles and permissions screen can't say what a cell does when it covers several endpoints |
| G3 | No record of what a cell does in the UI | No one can answer "if I tick this, which screens and buttons appear, in which portals?" except by reading code |
| G4 | No live rights in browsers (#12) | Revocation looks slow and grants look broken; buttons the server refuses (403) stay on screen |
| G5 | No version check on role edits (#10) | Lost updates between admins |
| G6 | No cross-row separation of duty (#13) | A role or user can make and approve the same thing when the two halves sit on different rows |
| G7 | Conflict rules are scattered: RBAC refusals at save, UI hints, business-code refusals | Designers learn about a conflict only when a save or an action fails |
| G8 | No "explain" | "Why can this user do X?" / "why was this refused?" needs a developer |
| G9 | Nothing checks that a deployed frontend and a deployed backend agree on the catalogue | Version skew between portal builds and the backend goes unnoticed |

## Design

### 1. The Permission atlas: one record per cell

The atlas is **generated, never hand-written**. It joins the facts each system already owns:

| Field | Example: `kyc.application:submit` | Comes from |
|---|---|---|
| `cell`, `row`, `verb` | `kyc.application`, `submit` | `@Perm` |
| `rowLabel`, `rowDescription` | "KYC application", "An organization's KYC application" | new `@PermRow` row metadata (one per row, in the backend) |
| `verbMeaning` | "submit (maker): send for review" | `Verb` enum (one place) |
| `backend[]` | `POST /api/kyc/orgs/{id}/submit` · `KycController#submit` · "Submit a KYC application for review" · tenant: the org in the path, within the holder's scope | `@Perm` per endpoint (all of them, not the first) |
| `frontend[]` | admin › Organizations › KYC review: "Submit for review" (widget `kyc.application`, action `submit`); supplier › Onboarding › KYC and documents: same; `shared/kyc/kyc-application.ts:79` | build-time scan of the portals (see §5) |
| `risk` | `maker` · `pii` | new `@Perm(risk = …)`: `money`, `pii`, `admin`, `maker`, `checker` |
| `checks` / `checkedBy` | checked by `kyc.task:authorize` | new `@Perm(checks = "kyc.application:submit")` on the checker endpoint |
| `roles[]` | the roles holding it, own or inherited | RBAC, live |
| `conflicts[]` | `SOD:kyc-maker-checker` with `kyc.task:authorize` | the conflict engine (§3) |
| `status` | `live` · `api-only` (no UI) · `ui-only` (gated in UI, not served) · `retired` | the join |

Where each part lives:
- **Backend:** `EndpointManifestPublisher` already registers every endpoint. It adds per-endpoint descriptions, `@PermRow` metadata, `risk` and `checks`, and registers them with RBAC (new columns on `rbac_cell_endpoints` / `rbac_resources`).
- **Frontend usage:** a build tool writes `permission-usage.json` (§5). Each portal build registers it with the registry server at deploy, next to its widget manifest.
- **Atlas API:** `GET /api/admin/rbac/atlas[?cell=]` in the backend joins RBAC's catalogue and roles, the registry's frontend usage, and the conflict engine. It needs `admin.access:read`, like the permission inventory.
- **Frontend copy:** `permissions.ts` is **replaced** by `permission-atlas.generated.ts`, generated from the backend's Java by the same reader the widget tools use (`tools/widget-lib/java.ts`), with CI `--check`. That closes G1, and no running backend is needed at build time.

### 2. Sync: three planes, each with one direction of flow

```
DEFINITION plane (build + deploy, CI-gated)                  GRANT plane (runtime, push)
  backend @Perm ──register──▶ RBAC catalogue                   Roles screen / User management
  portal catalogue ─gen─▶ nav-catalogue.json ─▶ RBAC             │ edits (versioned, §2.3)
  widgets ─gen─▶ widget-manifest.json ─register─▶ registry       ▼
  portals ─scan─▶ permission-usage.json ─register─▶ registry   RBAC ──Watch──▶ gateway cache
  backend Java ─gen─▶ permission-atlas.generated.ts (FE)            └──Watch──▶ backend ──SSE──▶ browsers (§2.2)
  CI: perms:drift fails on any disagreement
```

#### 2.1 Definition plane: every copy is generated or checked

| Producer | Artifact | Consumers | Gate |
|---|---|---|---|
| backend `@Perm` (+ `@PermRow`, `risk`, `checks`) | RBAC registration at start (exists, extended) | RBAC, gateway routes, atlas | startup refuses unannotated handlers (`app.rbac.require-annotations=true` in UAT/prod) |
| backend Java (read at build) | `permission-atlas.generated.ts` | portals, builder, inventory screen | `--check` in CI |
| portal catalogue | `nav-catalogue.json` (exists) | backend → RBAC | `--check` (exists) |
| widgets | `widget-manifest.json` (exists) | registry server | `widgets:check` + registry validation (exists) |
| portal builds | `permission-usage.json` (new) | registry server → atlas | `perms:drift` |

`npm run perms:drift` makes the probe that produced the table above official. It fails CI when:
- a frontend gate names a cell or row the backend doesn't serve;
- a backend cell is missing from the generated atlas;
- a catalogue row or menu permission is unknown;
- a widget cell is unknown.

It **reports** backend cells with no UI as `api-only`; that's sometimes intended (`ops.monitoring:execute`).

**Catalogue hash (G9).** The backend registration and every portal build carry a hash of the cell set they were built against. The atlas API lists deployed builds whose hash differs: "supplier-portal@a1b2 was built against an older catalogue".

#### 2.2 Grant plane: live rights in every browser (G4)

RBAC already versions every tenant (`tenants.version`, returned with every decision) and publishes changes on the Watch stream.

- **Phase 1 (pull, small).**
  - `GET /api/orgs/me/access` returns `version` and an `ETag`.
  - `AccessService` re-checks with `If-None-Match` (a 304 costs nothing) on window focus, every 60 s while visible, and **after any 403**. A 403 is the strongest hint that the rights changed.
  - `*mpPerm`, `permGuard` and widget hosts are already reactive to `AccessService`, so the UI updates in place: a revoked button disappears and a granted menu appears.
- **Phase 2 (push).**
  - The backend subscribes to RBAC's Watch stream (it's already a mesh client) and serves `GET /api/orgs/me/access/events` (SSE) through the gateway.
  - The event carries no rights, only "your tenant's version moved". The browser then re-reads `/api/orgs/me/access` as in phase 1.
  - Phase 1's pull stays as the fallback when SSE is cut.
- **The Roles and permissions screen** listens to the same signal. When another admin changes a role you have open, the screen says so (see §2.3).

#### 2.3 Role edits are versioned (G5)

- `roles.version` (bigint, +1 per change). `GET` returns it as an `ETag`, and `PUT` / `DELETE` require `If-Match`.
- A stale write gets `409 ROLE_CHANGED` with the current role and who changed it. The role builder shows **their change vs. yours**, cell by cell, and lets the admin re-apply theirs on top. It never overwrites silently.
- Assignments get the same treatment for approve and revoke races.

### 3. Conflict management: one engine, one vocabulary (G6, G7)

All conflict rules live in **RBAC** (where the decision point is), behind one API:

```
POST /rbac/roles/{name}/lint     { cells, inherits, kind, ownerTenant }          → conflicts[] (dry run, no save)
POST /rbac/subjects/{id}/lint    { addRole, tenant, scope }                      → conflicts[] (before assigning)
GET  /rbac/conflicts             ?scope=platform|tenant                          → every live conflict (review queue)
```

The backend proxies these as `/api/admin/rbac/...` and `/api/orgs/{id}/roles/...` with the same permissions as the screens that use them. The **same function** runs on save and assign, so the editor never shows something the server would treat differently.

| Class | Example | Detected | Severity | What the UI offers |
|---|---|---|---|---|
| **SoD, same row** | role ticks `org.onboarding:create`… `submit` and `authorize` | role lint, save, assign (exists: `SOD_CONFLICT`) | block | untick one; split into maker and checker roles |
| **SoD, cross row** | `kyc.application:submit` with `kyc.task:authorize` | role lint, assign, and DYNAMIC at decision time | block (STATIC) or "activate one at a time" (DYNAMIC) | as above; or mark the role DYNAMIC (a session uses one side) |
| **SoD, object** | the maker of *this* application decides it | business code (exists: `MAKER_CANNOT_ACT`) + RBAC OBJECT rule for the audit | block at action | none at design time; shown in "explain" |
| **Ceiling** | org role with a cell outside the org's ceiling | lint, save (exists: `ROLE_OUTSIDE_CEILING`) | block | remove, or raise the ceiling (from the org above) |
| **Platform-only row in an org role** | `admin.*` in an ORG role | lint, save (exists) | block | none |
| **Entitlement without grant** | `org.onboarding:create` held, no `child_type` grant on the org | lint (role + tenant) | warn | "will be refused until the org is granted …" |
| **Visibility without authority** | menu cell on, the screen's permissions off | lint (exists in the builder's model) | warn | "the menu won't show: add view on …" |
| **Authority without visibility** | `rbac.user:read` held, no screen shows it | lint, from the atlas `status = api-only` | info | "usable through the API only" (often intended) |
| **Unreachable action** | a widget action's cell held without the widget's `requires` | lint, from widget manifests | warn | "add …:read so the button can appear" |
| **Shared meaning** | `supplier.order` drives Orders and Order detail | lint, from the atlas `frontend[]` | info | "also affects: …" (exists in the builder) |
| **Redundant** | a cell held both directly and through inheritance | lint | info | "already given by …" |
| **Risky combination** | `money` + `admin` in one role; a checker cell without its maker in the same org | lint, from `risk` / `checks` | warn → require 4-eyes approval (role `approval = platform`) | an approval request instead of a direct save |
| **Concurrent edit** | two admins edit one role | save (§2.3) | block | diff and re-apply |
| **Definition drift** | a UI gate on a cell the backend doesn't serve | CI `perms:drift`, atlas `status = ui-only` | block (CI) | fix the code |

How the rules are sourced, so nobody keeps a second list:
- **Cross-row SoD pairs** come from `@Perm(checks = "row:verb")` on the checker endpoint. The backend registers them, and RBAC turns each pair into a STATIC `sod_rules` row (cell_a / cell_b) at registration. The rules are code-reviewed with the endpoints they protect. Compliance can still add rules by hand in RBAC: the table keeps a `source` (`declared` | `manual`).
- **Risk classes** come from `@Perm(risk = …)`. Policy (which combinations need 4-eyes) lives in RBAC config, reviewed with the role pack.

**Precedence (there are no deny rules, so no allow/deny conflicts).** RBAC is allow-only. The decision order is fixed (`Decisions.java` steps 1–10): cell exists → tenant known → row applies to the org type → usable assignments → tenant holds → dynamic SoD → mask → entitlement grant → ceilings. The **explain** API reports which step decided (G8):

```
POST /rbac/explain { subject, tenant, operation } → { allowed, decidedAt: "5 tenant hold", roles: [...], via: "inherited from …", conflicts: [...] }
```

The RBAC screen shows it as "Why can … / why can't … ?" for a user and a cell.

### 4. The Roles and permissions screen, joined up

- **By permission** (the tab exists) opens the atlas record. It shows:
  - what it does in the backend (endpoints and descriptions);
  - what it shows in each portal (menus, screens, widgets, buttons, with their labels);
  - its risk and the cell it checks or is checked by;
  - who holds it;
  - its conflicts.
- **The role builder** calls `lint` on every change, debounced, and shows conflicts inline at the chip that causes them (block / warn / info). Its third level (widget → named actions, portal-builder §8) uses the atlas `frontend[]`.
- **Saving** sends `If-Match`. A 409 opens the diff (§2.3).
- **User management** (org portals): the same lint when creating a role within the ceiling, and `subjects/lint` before assigning.
- **A conflicts queue:** `GET /rbac/conflicts` lists what is live now: roles or users in conflict after a rule was added, and drift. It sits next to the access reviews.

### 5. Finding what each permission means in the frontend

**Static, at build, from the code**, so it never depends on someone updating a document. `tools/perm-usage.ts` scans every portal and writes `permission-usage.json` with one entry per gate:

| Gate | Where found | Label recorded |
|---|---|---|
| menu point | `menu.ts` `MENU_ITEMS` | the item label + portal |
| screen | `portal-permission-catalogue.ts` | portal › menu › screen labels |
| route guard | `permGuard([...])` in route tables | the route path + the screen label |
| widget | `widget-manifest.json` (`requires`, `actions`) | widget label; each action label |
| element gate | `*mpPerm="'cell'"` / organism `perm=` in templates | `data-action` id, else the element's text (`Submit for review`) or `aria-label`, plus file:line |
| code gate | `access.can('cell')` | file:line (and a warning: prefer `*mpPerm` or a widget action, which carry labels) |

Rules the scan enforces, so every gate has a meaning:
- Every element gate must have a label: `data-action`, visible text or `aria-label`. `perms:drift` fails otherwise.
- A widget action's label **is** its meaning. That's why widget actions are named in the manifest.

**At runtime, in the backend:** the atlas joins `@Perm` per endpoint with the tenant source (`PATH #id` = "the org in the URL", `SELF` = "the user's own org", `NONE` = "platform-wide"). It says *on what* the right applies, not only *what*.

## Decisions (proposed)

| # | Decision |
|---|---|
| 1 | The backend's annotations are the only place a permission is **defined**. Everything else is generated from them or checked against them; `permissions.ts` is deleted for a generated file. |
| 2 | Cross-row separation of duty is **declared on the checker endpoint** (`@Perm(checks = …)`) and becomes RBAC rules at registration; manual rules stay possible and are labelled. |
| 3 | Conflict rules run in RBAC behind one `lint` function used by the editor, save, assign and the conflicts queue. |
| 4 | Browsers learn about rights changes: pull on focus, every 60 s and after a 403 (phase 1); SSE push (phase 2). Events carry versions, never rights. |
| 5 | Role and assignment writes are versioned (`If-Match`); a stale write is a 409 with a diff, never a silent overwrite. |
| 6 | Every UI gate carries a label (`data-action`, text or `aria-label`), so the atlas can say what it shows. |

## Steps

| Step | Work | State |
|---|---|---|
| 1 | `perms:drift` (the probe, official, in CI); `permission-atlas.generated.ts` replaces `permissions.ts` (fixes the missing `admin.org_directory:read`); inventory screen reads it | not started |
| 2 | Backend: per-endpoint descriptions to RBAC; `@PermRow`; `@Perm(risk, checks)` on the known pairs (KYC submit/decide, merchant/lender advance, approvals); RBAC turns `checks` into `sod_rules` | not started |
| 3 | Live rights phase 1: `version` + ETag on `/api/orgs/me/access`; `AccessService` re-check on focus, timer, 403 | not started |
| 4 | Role versioning: `roles.version`, `If-Match`, `409 ROLE_CHANGED`; builder diff | not started |
| 5 | Conflict engine: `lint` (roles, subjects), `conflicts` queue, `explain`; role builder inline conflicts; User management | not started |
| 6 | `permission-usage.json` scan + registration; atlas API; "By permission" atlas panel | not started |
| 7 | Live rights phase 2: Watch → backend → SSE; Roles screen "changed by someone else" | not started |
| 8 | Catalogue hash on registration and builds; skew report | not started |

Order rule: steps 1 and 3 first (small, they fix live defects); 2 before 5 (the engine needs the declared pairs); 6 after 5.

## Open questions

| # | Question |
|---|---|
| Q1 | The known cross-row pairs. Proposed: `kyc.application:submit` ↔ `kyc.task:authorize`; `merchant.advance:submit` ↔ `lender.advance:authorize`; `org.onboarding:create` ↔ `ops.onboarding_request:authorize`; merchant / supplier / bank-account submissions ↔ the `ops.*_approval:authorize` cells. Compliance to confirm. |
| Q2 | Cross-row SoD: STATIC (a role or user may never hold both) or DYNAMIC (may hold both, uses one per session)? Proposed STATIC for platform roles and DYNAMIC for small orgs with one admin; the entry-checker logic already falls back there. |
| Q3 | Should `api-only` cells (no UI) be grantable in the role builder at all, or only in the technical row view? |
