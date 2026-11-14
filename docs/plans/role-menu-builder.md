# Role builder by portal menu

Status: **in progress** · started 2026-11-14

## Goal

**New role** starts with a **portal** dropdown. It then shows that portal's menus and submenus exactly as the sidebar draws them. The person creating the role:

- switches each menu and submenu on or off (visible or hidden for this role);
- inside each visible submenu (one screen in the portal), picks the actions the role may take there.

## Decisions (2026-11-14)

| # | Decision |
|---|---|
| 1 | **Visibility follows permissions (1a).** There is no separate "hidden" flag. Showing a submenu gives the smallest set of cells that makes the sidebar draw it (view where the row has view). Hiding it takes away every cell on the screen. A screen is visible in the builder exactly when the portal shell would draw it: `liveNavigation` (screen rows) **and** the route gate (`menu.ts` perm list). |
| 2 | **Shared permissions are shown, not split (2A).** When a screen's cells also drive other screens of the same portal, the screen says *"also affects: …"*. Changing it changes every screen that shares it. Backend splits (option C) happen screen by screen, later. |
| 3 | **Planned screens are hidden behind a "Show planned" switch, off by default (3C).** Planned screens are greyed out and can't be ticked. |
| 4 | **One shared component, released in two steps (4C).** Step 1: Admin → Roles and permissions (new and edit). Step 2: User management → "Create a role", with the portal fixed to the org's type and the choices limited to what the manager holds (their ceiling). |
| 5 | **One portal per role.** The editor shows the tree for the portal picked in the dropdown. A role has no stored portal: the dropdown is the lens. The editor opens on Admin for platform roles, and for org roles on the org portal where its cells draw the most screens. |
| 6 | **Landing page (2026-11-14).** Tabs: By role, By controller, By permission. The Portal dropdown is gone from the landing page; the overview lists every role. The portal is picked in New role and in the role editor only. |
| 7 | **Menu cells: each submenu has its own switch (2026-11-14, replaces 1a).** Under 1a, submenus sharing a permission could only switch together (for example, 16 of the Admin portal's live submenus). Each submenu now has its own RBAC row `nav.<portal>_<menu>_<screen>` with verb `read`. It grants no endpoint, it only picks what the sidebar draws. A submenu shows when the role holds its menu cell **and** the screen's permissions. **Opt-in per portal:** a user who holds no menu cell of a portal keeps the permission-driven sidebar. Once any of their roles holds one, that portal shows only switched-on submenus, for **all** of their roles. Route guards and the shells' first-page redirect apply the same rule, so a switched-off screen can't be opened by URL. Switching off removes the menu cell and the permission cells no other switched-on submenu needs. The first change to a role without menu cells first gives it the menu cell of every submenu it shows today. Org managers may switch menu cells without holding them (RBAC `holdsAll` exempts `nav.*`). |

## Design

- `frontend/projects/shared/rbac/role-menu-model.ts`: pure functions, with no Angular, so they can be tested with Node:
  - `buildTree(portal, ctx)`: menus → screens, each with its cells, live/planned status, visibility, and the screens it shares cells with;
  - `showScreen` / `hideScreen` / `showMenu` / `hideMenu`, which return the new cell set.

  The route gate comes from `menu.ts` (`PLATFORM → admin`, `MANUFACTURER/DISTRIBUTOR/SUPPLIER → org`, others → `sme`), plus the org portal's `/supplier` landing gate.
- `frontend/src/app/design-system/organisms/role-menu-builder.ts`: the `mp-role-menu-builder` component (the design system owns all UI). Inputs:
  - `portal`;
  - `rows` (the cell palette: id, used verbs, description);
  - `selected` (own cells);
  - `inherited` (read-only cells from roles it builds on);
  - `disabled`.

  Output: `selectedChange`.
- Admin `permission-matrix.ts`:
  - The create panel gets a **Portal** dropdown and the builder. **Create role** sends the picked cells. `kind` = PLATFORM for Admin, ORG otherwise.
  - The editor's old "Portal menus and screens" block is replaced by the builder for the selected portal.
  - The technical row view stays in its `<details>`.
- Step 2: `user-management.ts` "Create a role" swaps the flat row picker for the builder. The portal is the org's type, and `rows` are the manager's own cells.
- Menu cells (decision 7):
  - `portal-permission-catalogue.ts` provides `navRow`, `navScoped`, `navAllows` and `allNavRows`, and `liveNavigation` applies the rule.
  - `tools/gen-nav-catalogue.ts` writes `backend/src/main/resources/rbac/nav-catalogue.json` (run it after changing the catalogue; `--check` fails when stale). `EndpointManifestPublisher` registers those rows with RBAC.
  - RBAC `registerCatalog` declares the registered verbs of rows that have no endpoint.
  - `permGuard(perm, {portal, link})` and the admin/org shells' `pageOk` add the menu check.
  - The sme-ui guards (`access-map.ts`) don't apply it yet (its sidebar does, through `liveNavigation`).

## Steps

| Step | Work | State |
|---|---|---|
| 1 | Model + Node test | done |
| 2 | Builder component (shared UI, portal-theme tokens) | done |
| 3 | Admin: create with portal + tree; editor uses the builder; By role tab; no landing portal dropdown | done |
| 4 | Org portals: User management uses the builder (fixed portal, ceiling) | done (headless, supplier portal) |
| 5 | Headless check in a browser; builds of admin-portal and org-portal | admin checked at 1280px and 390px against a mocked API; admin + supplier builds pass |
| 6 | Deploy to afriqpay | portals live 2026-11-14 (rollback tag `:pre-rolebuilder-20261114`) |
| 7 | Menu cells (decision 7): catalogue, model, guards, backend registration, RBAC declared verbs + `holdsAll` exemption; Node tests (81 live screens switch alone), RBAC tests (28/28), backend `NavCatalogueTest`, headless admin + supplier | built and tested locally |
| 8 | Deploy menu cells to afriqpay: backend + RBAC + portals together (the org portals send `nav.*` cells that RBAC must know) | live 2026-11-14: 177 menu rows registered, rollback tag `:pre-navcells-20261114` |
