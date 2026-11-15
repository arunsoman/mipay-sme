# Plan: the admin console (admin.afriqpay.com)

Status: **built and tested** (2026-11-15) · deploy to afriqpay pending · tracked in [`master-plan.md`](master-plan.md)

## 1. What it is

The admin portal's sidebar is cut down to three menus with five submenus. The aim is that an admin has fewer places to look and less to keep in mind:

| Menu | Submenu | Page (route) | Widgets |
|---|---|---|---|
| Organizations | All organizations | `/organizations` | `admin.organizations` → pick one: tabs **What it can do** (`org.access`), **People** (`org.people`), **KYC** (`kyc.application`, review mode) |
| KYC | Applications | `/kyc/applications` | `kyc.orgs` → pick one: `kyc.application` |
| | Review queue | `/kyc/reviews` | `kyc.tasks` → pick one: `kyc.application` (review mode) |
| People and access | Platform users | `/access/users` | `users.organization-users` |
| | Roles and permissions | `/rbac` | the existing role screen (New role uses the same grid as `rbac.new-role`) |

Every other admin page keeps its route and its guard. These pages are gone from the sidebar and from the PLATFORM menu catalogue, and so from the role builder's Admin tree and its menu cells (43 → 5). The admin portal now opens on Organizations.

## 2. One search contract for every list (`ListQuery`)

```
GET …?q=kalahari&sort=name,asc&status=PENDING,ACTIVE&page=0&size=20
→ {items: [...], total: 57, page: 0, size: 20}
```

- Backend: `common/ListQuery` holds the contract once. Each list declares its fields once (`text`, `field`, `searchOnly`, a default sort and a size limit). An undeclared sort or filter is a 400 (`BAD_SORT` / `BAD_FILTER`); nothing is silently ignored. Lists the database can answer use a JPA `Specification` + `Pageable`, for example KYC applications. Lists the server assembles itself are queried in memory on the server, after they are built:
  - the review queue, which the workflow engine scopes;
  - people with their RBAC roles;
  - organizations with their counts and warnings.
- Frontend: there is one server mode, in the shared table (`TableView` / `mp-data-table [server]`, sortable headers through `DataCol.sort`), with one `ServerQuery` state per list. One widget helper (`connectServerList`) sends the query: search is debounced, and a newer query cancels the one in flight. `fixturePage` answers fixtures the way the server does. Each source declares its query keys in `data-sources.ts`.

| Endpoint | Cell | Filters | Sort |
|---|---|---|---|
| `GET /api/admin/organizations/search` | `admin.org_directory:read` | type, status, kycStatus, preset, warnings | name, type, status, kycStatus, parentName, users, preset, features, warnings, createdAt |
| `GET /api/kyc/orgs/search` | `kyc.application:read` | orgType, status, isSuspended | orgName, orgType, status, isSuspended, submittedAt, decidedAt, updatedAt |
| `GET /api/kyc/tasks/search` | `kyc.task:read` | applicationStatus, kind, stageKey | orgName, kind, stageKey, applicationStatus, createdAt |
| `GET /api/orgs/{id}/people` | `org.user:read` | orgRole, status, roles, pendingRoles | name, username, orgRole, status, roles |

The old `GET /api/kyc/orgs` and `GET /api/kyc/tasks` stay for the older screens.

## 3. What an org can do: features and presets

Since 2026-11-12 there are no standard org roles. A new org's people get org-defined roles, and RBAC refuses any role that grants more than its creator holds. On that model:

- **A feature is one top-level menu of the org's portal.** For example, User management is the "Team and access" menu. Its permissions are the permission rows of the menu's screens, with every verb the code registers for them, plus the screens' menu cells. `tools/gen-nav-catalogue.ts` writes them to `rbac/feature-catalogue.json` from the portals' own menu catalogue, so features follow the portal automatically.
- **Presets:** `rbac/access-presets.json` defines three: Full access, Essentials, and KYC only.
- **Who holds what:**
  - An org's switched-on features are held by its admins through **one** org-defined RBAC role, `organization_access@<org>`. The role is kept equal to the features, trimmed to what the person switching them holds.
  - Because RBAC's "never beyond its creator" rule applies, the org's admin can never build a role beyond its features.
  - Switching a feature off also strips it from the org's other roles (clamp).
  - No new enforcement is involved.
- **Where it's stored:**
  - `org_access_settings` holds the preset, the features, who set them, when they were applied, and RBAC's last refusal (migration `V4`, entity `OrgAccessSetting`).
  - `OrgFeatureService` builds the role, clamps, assigns, and works out the warnings.
- **Onboarding** saves the Full preset, without calling RBAC: RBAC learns about the org only after the onboarding commits. **Go-live** builds the role on behalf of whoever last set the features, then gives it to the new admin (source `kyc_go_live`).
- **Who may change it:** an admin of an org above it, or platform staff (as for the role ceiling). An org never sets its own features.
- **Warnings** (badges in the organizations list, alerts on the tab; "Needs attention" filters by them):

| Warning | When |
|---|---|
| Access not set | the org has no setting yet (orgs onboarded before 2026-11-15) |
| Can't add users | Team and access is off |
| Can't complete KYC | Onboarding is off |
| Access not applied | RBAC refused the last change; the reason is shown, and **Apply again** retries |

## 4. Tests

- Backend:
  - `ListQueryTest` (6), the contract.
  - `OrgFeatureServiceTest` (8), the catalogue, presets, the KYC-only grid, clamp, trimming to what the actor holds, refusal as a warning, and go-live only.
  - `TUat21AdminConsoleTests` (7), over HTTP on H2: search, filters, sort, paging, fail-closed sort and filter, people, and features.
  - Full suite: 258 tests. The one failure, `InstallerServiceTest`, comes from before this work: the installer now requires maker and checker details.
- Widgets: 147 unit tests, including conformance for all 8 widgets, and `widgets:check` is clean. The workbench drops a scenario carried over from another widget (the Organization users `NO_FIXTURE` error).
- Headless: the real admin portal against a mocked API at 1280 px and 390 px, with 34/34 checks. These cover the three menus and five submenus, server search, filter and sort, the warning badge, pick → What it can do → save → warning cleared, People, the KYC lists, Platform users, no horizontal scroll, and no console errors.
- Builds: every portal and the workbench build.

## 5. Not yet

- A platform-wide "who holds role X" list across all orgs. People are listed per org.
- Platform roles that already pick submenus by menu cells must be given the new KYC menu cells (`nav.platform_kyc_*`) in Roles and permissions. `organization_root` holds every row, so the root admin is not affected.
- Orgs onboarded before this change show "Access not set". Pick a preset for each one on its What it can do tab.
