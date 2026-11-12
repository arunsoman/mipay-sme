# Plan: extended RBAC: role ceilings per org and org-defined roles

Status: **built and verified end to end on an isolated copy** (2026-11-12); not deployed · builds on cut-over P2 (the grid, grants, four-eyes) · tracked in [`master-plan.md`](master-plan.md) as items 2.5 / 3.7

## 1. What it is

When an org onboards a child org, it chooses **which roles the new org may use**. The new org's admin then gives those roles to its own users, and may build **its own roles** out of them. Whatever the new org does is bounded by what it inherited.

```
PLATFORM (no ceiling: every org role)
 └─ Namib Distribution          ceiling {kyc_applicant, org_admin, org_staff, org_viewer}
     │                          may hand down {org_staff, org_viewer}
     └─ Oka Store (merchant)    ceiling {kyc_applicant, org_admin, org_viewer}   ⊆ Namib's hand-down set + baseline
                                org-defined role "cashier@oka" = part of org_viewer's cells
```

## 2. The rules

| # | Rule |
|---|---|
| X1 | **A ceiling is per org.** It has two sets: `roles` (what the org's users may hold in it) and `delegable` (what it may hand down to orgs it onboards; `delegable ⊆ roles`). |
| X2 | **No explicit ceiling means inherit:** the org takes the nearest ancestor's `delegable` set as both sets. No explicit ceiling anywhere above means **unrestricted** (every ORG role). This keeps today's orgs and the platform root working unchanged, and is what shadow mode (P6) compares against. |
| X3 | **Baseline roles** (`kyc_applicant`, `org_admin`) are always inside every ceiling, so an onboarded org can always fill in its KYC and be administered. They come from `ceiling_baseline` in `grid.yaml`. |
| X4 | **Child ⊆ parent.** A child's `roles` ⊆ the parent's effective `delegable` ∪ baseline. Narrowing a parent clamps every explicit ceiling below it in the same transaction. |
| X5 | **Who sets it:** the onboarder (inside the onboarding request), later an admin of the parent org or a platform user admin. The org itself never edits its own ceiling. |
| X6 | **The core is the authority** (it owns orgs). Ceilings live in the core table `org_role_ceilings` and reach RBAC with the org-tree sync, exactly like holds. RBAC re-applies X2–X4 on arrival (it clamps, and logs what it clamped). |
| X7 | **Enforced twice in RBAC:** an assignment of an ORG role outside the ceiling of its org is refused (409 `ROLE_OUTSIDE_CEILING`, system writes too); and `Check` ignores any assignment whose role is no longer inside the ceiling (`role_outside_ceiling:<role>@<org>`), so narrowing takes effect on the next request without rewriting assignments. Platform assignments (scope `all` or on the PLATFORM org) are exempt (U6). |
| X8 | **Org-defined roles** are named `<slug>@<org id>`, owned by one org, and assignable only in that org (`own` or `subtree` scope on it). Each declares **base roles** (global ORG roles inside the owner's ceiling); its grid must be a subset of the union of the base roles' effective grids. |
| X9 | **Org-defined roles can't bypass separation of duty:** the base roles must not include both sides of any STATIC or DYNAMIC SoD rule; at assignment time the role counts as its base roles for static SoD; the grid save rules (no same-row submit + authorize) apply; approval is the strictest of the base roles' approvals. |
| X10 | **Bounded at decision time:** an org-defined role's mask is `own grid ∩ ⋃ mask(base)` over the base roles that are still inside the owner's ceiling. Narrowing a ceiling or a base role's grid therefore narrows every org-defined role built on it, immediately. |
| X11 | **Compliance:** org-defined roles are a subset of signed-off roles by construction. The role pack lists them in their own section (owner, base roles, cells) for the access review. |

## 3. Work items

| # | Item | Where | Status |
|---|---|---|---|
| ER1 | Role ceiling per org: RBAC `tenants.role_ceiling` / `role_delegable` (null = no explicit ceiling); effective-ceiling resolution (X2, X3); sync payload; core table `org_role_ceilings`, `OrgRoleCeilingService` (validate, set, cascade, effective view) | `rbac-service` `Ceilings`, `RbacStore.syncOrgs`; backend V16, `OrgRoleCeilingService`, `OrgTreeSyncService` | ✅ |
| ER2 | Org-defined roles: `roles.owner_tenant`, `roles.base_roles`; create / modify / delete with X8–X10; role pack section (X11); mesh endpoints for the core to act on behalf of a user | `RbacStore`, `RolesController`, `MeshController`, `RolePack` | ✅ |
| ER3 | Core and onboarding wiring: onboarding request carries `roles` / `delegableRoles`; `grantable` lists the roles the caller may hand down; ceiling GET/PUT; org roles and user-role assignment endpoints proxied to RBAC on behalf of the user | `OrgOnboardingService`, `OrgController`, new `OrgRolesController`, `RbacMeshClient` | ✅ |
| ER4 | Enforcement in RBAC: assignment refusal and `Check` refusal (X7), org-defined role masking (X10), SoD expansion (X9) | `AccessService`, `Decisions` | ✅ |
| ER5 | UI: a "Roles" step in onboarding; a ceiling editor on child orgs; "Roles & access" in the org portal (ceiling, org-defined role builder, team role assignment) | `shared/kyc/onboard-child.ts`, new `shared/rbac/*`, org portal and admin portal routes | ✅ |

## 4. As built (2026-11-12)

- **RBAC** (`trustplane/java/rbac-service`): `Ceilings` (effective ceiling, clamp after sync); `tenants.role_ceiling` / `role_delegable`, `roles.owner_tenant` / `base_roles`, `ceiling_baseline` (seeded from `grid.yaml`); `Decisions.withinCeilings` (X7, X10); `AccessService.checkCeiling` and `staticConflict` (X7, X9); `RbacStore.createOrgRole` / `updateOrgRole` / `rolesFor`; `RolesController` takes `tenant` + `base_roles`; new `OrgAccessMeshController` (`/rbac/internal/tenants/{t}/roles|assignments|approvals`, on behalf of a named actor; `core_authorized` until P6). The role pack lists ceilings and org-defined roles in their own section. `org_admin` gains `rbac.role` create/modify/delete (its own org's roles), so **the role pack for compliance must be regenerated**.
- **Core** (`backend`): V16 `org_role_ceilings`, `OrgRoleCeilingService`, `RbacMeshClient`, `OrgAccessController` (`/api/orgs/{id}/role-ceiling|roles|members|role-assignments|role-approvals`); onboarding takes `roleCeiling` / `roleDelegable`; `GET /api/orgs/me/grantable` lists the roles the caller may hand down; the org-tree sync carries ceilings (and its fingerprint includes them).
- **Portals**: a roles step in `onboard-child.ts`; `shared/rbac/role-ceiling.ts` (on "My network" for orgs below); `shared/rbac/org-access.ts` as "Roles & access" in the org portals (own org) and the admin portal (any org).
- **Tests**: RBAC 17/17 (3 new: ceilings, org-defined roles, on-behalf requests) against a scratch Postgres; backend `TUat20RoleCeilingTests` plus TUat01/02/17/18 green on H2.
- **End to end (2026-11-12)**, on an isolated copy (local RBAC and backend JVMs, own Postgres with V1–V16 applied by Flyway, own test PKI and mTLS; the running stack untouched): 22/22 checks over the portals' HTTP API: onboarding with a ceiling, the ceiling reaching RBAC through the org-tree sync, org-defined role create and refusal beyond its base, assignment (active / four-eyes pending / refused outside the ceiling), narrowing switching an org-defined role off, revoke, back to inherit. An org's own admin is refused on its own ceiling (403 `ANCESTOR_REQUIRED`). The backend registered 128 cells over 216 endpoints with RBAC, none rejected.

## 5. Not in this plan

- Making RBAC *decide* for the backend: that stays cut-over P6 (`RbacAuthz` still returns `true` until then). Until P6, the ceiling binds everything that already asks RBAC: the gateway's route checks, RBAC's own admin API, and the new role screens.
- A ceiling on platform roles: platform roles are never assignable in an org (P2).
- Org-defined roles inheriting from other org-defined roles: base roles are always global roles, so one level only.
