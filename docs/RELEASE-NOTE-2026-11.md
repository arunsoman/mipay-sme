# Release note — MiPay SME: IdP cut-over, RBAC enforcement, KYC v3, and the menu registry

**Branch:** `kyc-v2-portals-afriqpay-deploy` · **Range:** `c19d0c5..db1fe11` (69 commits) · **Dates:** 12–13 Nov 2026 · **All five portal builds green at `db1fe11`**

## Highlights

1. **RBAC is now the permission source for MiPay.** The identity-provider cut-over is done (Stage 1 / Gate 1): the IdP became a credential plugin (P1), the RBAC service was rebuilt as the MiPay permission grid (P2), every backend endpoint registers its permission via `@Perm`, and the gateway enforces (OA8) — nobody loses access through legacy-parity roles.
2. **Every portal is permission-driven, end to end.** Menus, pages, buttons and route guards all read one access engine (`/api/orgs/me/access`), and as of `db1fe11` **one canonical menu registry** (`frontend/projects/shared/web-core/menu.ts`) drives all five portals (sme-ui, admin-portal, manufacturer/distributor/supplier portals) — one label and one permission set per menu point, impossible to drift.
3. **KYC is a product now: one calm 5-step onboarding wizard** with the merchant data-capture form (KYC v3), read-only review with Approve/Reject (reason), and RBAC deciding who reviews.
4. **Afriqpay deploy hardening**: signed release images, digest-pinned Compose, nginx TLS, no demo seeds, root-admin recovery without an IdP credential provider.

## By area

### Identity & lifecycle (P3)
- Auth plugin contracts + Trustplane mTLS identity provider; gateway context and resolver (`a09d12e`).
- `UserLifecycleService` is the **only** writer path for users; every user writer swept through it; IdP event ingest verified against the real IdP (`80c9bdb`, `d5ccb12`).
- Durable provider-command **outbox** with real-Postgres and real-IdP integration tests (`fb3c60d`).
- Backfills complete: identity (credential import, `2af6317`), org-binding (V15_1 `org NOT NULL`, `2aef8aa`), RBAC (org/platform roles, onboarding grants, `ca1e101`) — P3 done.
- `auth-sync` reconcile: org cascades, KYC lapse holds, go-live swap, audit identity (`cbebab3`); lifecycle recovery restores org role definitions (`0b000d6`).

### Comms plane
- B0: queue, state machine, contacts, capabilities, sandbox provider (`c19d0c5`); B1: Brevo transactional-email provider behind an `EmailSender` seam (`1f2c81c`); CommsWorker startup fix (`1a8e4d4`).
- Onboarding set-up links are emailed through the comms plane, copyable in User management (`114cc50`); resend is RBAC-scoped within the caller's subtree; root-admin recovery issues activation links when no credential provider exists (`fd93763`).

### RBAC & access model
- Org-centred model (OA1–OA8): platform root split into MiNdLABS operator + TPTS operating root (`d2568b1`); capability roles and role pack regenerated from live dev (`e9bca0f`); grid registered from code, not yaml ticks.
- Extension: **role ceilings** per org and **org-defined roles** (bounded by base roles, retired with the org; four-eyes approvals) (`7b9bb95`, `13b7405`).
- D-9 decision-effect model: frozen proposals, return, supersession (`135515d`).
- Trustplane seed hardening: starter roles per org kind (admin/manufacturer/supplier/reseller — onboard organizations + manage users, nothing else) (`f149d80`); seed holds placeholder rows for not-yet-registered ticks instead of dropping them; seeded roles read-only pending review workflow (`8d04591`); incomplete org snapshots rejected (`7214e7a`); role reconciliation exposed per organization (`a147655`).

### Admin tooling
- **Permission Matrix**: every standard role over every row from the code, no org-type tabs (`f29bb4b`); **by-controller view** (controller → endpoints → permission → holders) (`21f0ebc`); role create/delete by any name.
- Access screen shows set-up links and live sessions with per-link disable and provider revoke (`ab38504`).

### Portals & UX
- Shared access engine: buttons and pages hide themselves without permission (`*mpPerm`, route guards, empty shell for zero rights) (`0fb5736`); plain words; supplier commerce screens appear in org portals only for holders of `supplier.*` rows.
- KYC: one calm 5-step wizard (`329b7c7`), org type + business profile first (`432ed6e`), the form is exactly the merchant data capture specification (KYC v3, `d9bf909`); applications pinned to superseded form versions open again (`d9f3017`); KYC review is a read-only form with Approve / Reject (reason); menus and landing page follow the user's permissions (`8492855`).
- Admin portal: operations and compliance pages (approvals, reconciliation, incidents, complaints & disputes, reports, audit trail, AML/SAR/data requests), each shown only with its permission (`5ef44a8`); layouts collapse on phones.
- **Menu registry** (`db1fe11`): duplicate menu names unified (Service desk → Complaints & disputes, Audit → Audit trail, Perms inventory → All permissions, Compliance → AML, SAR and data requests, Access → Access control), permission drift removed between portals (approvals/desk carry the union their panels need, inventory gated by the exact `admin.access:read` cell), unmapped paths deny, fail-closed nav with no ungated fallback.

### Design system
- The whole UI now lives in one design system at `frontend/src/app/design-system/`, layered **atoms → molecules → organisms**: `shared/ui` folded in as molecules (alert, tabs, table-view) and organisms (data-table, modal); status maps moved out of `core/`; no rogue UI imports anywhere; screen polish (spinner states inside tables via the `loading` text, wrapping page-header action row) (`db1fe11`, `f149d80`).

### Ops, security & CI
- RBAC decisions retained **7 years** (`bfb9a97`); enforcement status disclosed and test DSN required (`c278572`); temporary core authorization bridge **expired** — RBAC enforces for real (`60d184d`).
- Backend trustplane key volume restricted (`7da9ae9`); demo seeding disabled on the Afriqpay host (`b5d7125`); root-admin recovery settings from `.env` (`120bf2e`); no IdP demo seed users (`c183622`).
- CI: RBAC Postgres tests required, skips rejected (`de5790f`, `7153032`); signed release image workflow + digest-pinned Compose overrides (`bbfe16a`); deploy preflight and review fixes (go-live past the bridge, terminal orgs, subtree revoke, sync bad rows) (`d03a838`).
- RBAC Matrix backed by RBAC on the live grid (`5489faa`); Flyway out-of-order so V15_1 applies after the already-applied V16 (`a1ee936`).

### Docs
- FAPI 2.0 compliance spec pack (final profile + normative references) with PDF and regeneration script (`df05818`, `d19cef2`); role pack regenerated after each grid change; master plan updated to remaining work (org-centred access model OA1–OA8, `4b39a64`).

## Verification
- Gate 1 e2e for cut-over P1/P2 and comms B0 green; RBAC test suite 18/18 incl. role ceilings and org-defined roles; backend UAT pack green on H2; five Angular portal builds (sme-ui, admin-portal, manufacturer-, distributor-, supplier-portal) green on `db1fe11`.
