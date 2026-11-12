# Master plan: the order everything is built in

Status: **living document**, maintained alongside every plan below · last updated 2026-11-12

This file decides **what is built in what order, and why**. It doesn't repeat the plans: each row points to the plan and phase that holds the detail. When a plan changes, its row here changes in the same commit (§7).

---

## 1. The plans

| Plan | What it delivers | State today |
|---|---|---|
| [`trustplane/PLAN.md`](../../trustplane/PLAN.md) | gateway, FAPI 2.0 IdP, RBAC service, PKI, e2e suite | ✅ built (milestones 1–6); now being reshaped by the plans below |
| Org hierarchy (V4 migrations, `OrgTreeService`, suspensions) | org tree, closure, holds, onboarding rights | ✅ built |
| [`kyc-design.md`](kyc-design.md) | KYC master, validation, workflow, providers | 🟡 **built in-process** (phases 1, 2, 3, 5, 7 in core: `KycMasterService`, `KycSecretProtector`, `KycWorkflowEngine`, built-in providers, shared editor). **Not done:** phase 0 (separate `kyc-service`), phase 4 (cross-service go-live gate), phase 6 (EMI profile; verify) |
| [`fapi2-design.md`](fapi2-design.md) | the identity, FAPI and RBAC **design** (the "why") | 📘 design, revised to the agreed model. It is built through the cut-over plan, plus its §8 steps 7–8 |
| [`idp-rbac-cutover-plan.md`](idp-rbac-cutover-plan.md) | login as a plugin, users owned by the core, RBAC decides (P0–P7) | 🟡 **P0, P1, P2 done** (P0 `1c12e52`, `eb3bd7f`; P1 `76e1e0a`; P2 `16374fa`; gateway + e2e `a6c9b84`); P3–P7 not started; decisions D1–D10 agreed |
| [`2fa-design.md`](2fa-design.md) | 2FA in the IdP, shipped `off`, factors light up with comms (phases 1–5) | 📝 planned; all items decided; break-glass names are placeholders |
| [`comms-design.md`](comms-design.md) | the comms plane: email, SMS, push, WhatsApp, in-app (P0–P4) | 🟡 **B0 done** (`c19d0c5`, wiring `a6c9b84`; sandbox provider only); B1–B4 planned; §14 decisions are assumed |
| [`inventory-plugin.md`](inventory-plugin.md) | the **plugin framework** (§1) and the Inventory plugin | 📝 planned; its prerequisite (org hierarchy) is done |
| [`plugins/*`](plugins/README.md) | 23 org plugins, one page each | 💡 ideas: one-page briefs, no detailed plans yet |

---

## 2. How the order was decided

1. **Identity before anything that checks permissions.** Every new feature would otherwise be written against `org_role` ADMIN/STAFF, `UserRole` and `platformStaff`, which the cut-over deletes. Building on them means writing it twice. So **plugins start only after RBAC enforces** (cut-over P6).
2. **The comms plane runs alongside identity, not after it.** 2FA, merchant OTP, self-service reset and all set-up links need it. It has no dependency on identity, so it runs on its own track from day one and its phases release features as they land. Nothing waits for it, and it doesn't wait for anything.
3. **Ship switched off, light up later.** 2FA (`mfa.mode`), RBAC (`app.rbac.mode`), the gateway BFF (per-host `bff`) and identity (`app.identity.mode`) are all built behind switches, so code never waits for a dependency to finish. Only *switching on* waits.
4. **The core's data rules come first.** The rules that every user belongs to an org and that the org's state governs the user (cut-over §1.5) are enforced before any new user-facing feature, so nothing new can create an org-less user.
5. **KYC moves to its own service after the identity cut-over.** The cut-over changes the same code (KYC go-live enrolment, workflow actor rules, `UserPlatformFunction`). Moving it first means changing it in two places. The split is about isolating personal data, not about load (about 80 orgs a month), so it can wait one stage.
6. **Compliance-first plugins before commercial ones.** KYC periodic review creates the `KYC_LAPSED` hold the cut-over already supports; Document Vault and Audit Viewer serve the regulator. Commercial plugins follow Inventory, which several of them build on.
7. **The highest-risk step goes last in each track,** with a week of shadow or dual-run before it: identity P7, RBAC enforce, `mfa.mode=enforce`.

---

## 3. Dependency map

```
DONE: trustplane · org hierarchy · KYC v2 (in-process) · @Perm catalogue + org-tree sync · A0 · A1 · A2 · B0
   │
   ├──────────────── Track A: identity & access (idp-rbac-cutover-plan) ────────────────────────────┐
   │  A0 edge ─┬─ A1 IdP credential plugin ─┬─ A3 core links/lifecycle/org rules ─ A4 gateway sessions ─ A5 portals ─┐
   │           └─ A2 RBAC rebuild ──────────┘                                     │                                 ├─ A7 CUT-OVER
   │                                                    A6 RBAC shadow → enforce ─┴─────────────────────────────────┘
   │
   ├──────────────── Track B: comms plane (comms-design) ───────────────────────────────────────────┐
   │  B0 foundations + sandbox ─ B1 real SMS/email ─ B2 push + in-app ─ B3 policy ─ B4 scale/engagement
   │      │ (CommsClient API for A1/A3)  │ (lights up SMS 2FA, merchant OTP, reset)   │ (+ mobile app → push)
   │
   ├──────────────── Track C: 2FA (2fa-design), ships mfa.mode=off ─────────────────────────────────┐
   │  C1 foundations (TOTP, passkey, codes, PIN) [after A1] ─ C2 step-up [with A4/A6] ─ C3 recovery [after A3]
   │  C4 SMS + merchant OTP [code now, live at B1] ─ C5 push in the MiPay app [live at B2 + app release]
   │  switch-on: off → shadow → enforce [after A7]
   │
   ├──────────────── Track D: KYC service split (kyc-design phases 0, 4, 6) [after A7] ─────────────┘
   │
   └──────────────── Track E: platform and plugins [after A6 enforce] ───────────────────────────────
      E1 plugin framework + Inventory ─ E2 compliance plugins (KYC review, Document Vault, Audit Viewer)
      E3 Notifications plugin [after B1] ─ E4 commercial/ops/finance plugins ─ E5 partner API lane + FAPI conformance [after A7]
```

---

## 4. The sequence

Stages are ordered. Work inside a stage runs in parallel where the tracks allow. **Gate** = what must be true before the next stage's dependent work switches on.

### Stage 0: Decide and prepare (now, about 1 week, mostly people work)

| # | Item | Plan | Status |
|---|---|---|---|
| 0.1 | Review and commit the four plan docs (`idp-rbac-cutover-plan`, `2fa-design`, `fapi2-design` revision, `comms-design`) and this file | all | ✅ 2026-11-11, commit `1c12e52` (review continues in the docs) |
| 0.2 | Compliance starts reviewing the role pack (draft from cut-over P2.5 / `fapi2-design` G.6). The sign-off is needed only at Stage 3 | cut-over D9 | ⬜ |
| 0.3 | Confirm the comms §14 assumptions (SMS aggregator, email provider, WhatsApp) and start provider contracts; the long lead time is why this starts now | comms §14 | ⬜ |
| 0.4 | Mobile app: confirm the roadmap date (it gates push, C5) | 2fa D4 | ⬜ |
| 0.5 | Fill in the break-glass officers (`<BG_…>` placeholders), needed before A7 | 2fa §9.6 | ⬜ |

**Gate 0:** plans committed; provider procurement started.

### Stage 1: Foundations (about 2 weeks)

| # | Item | Plan | Status |
|---|---|---|---|
| 1.1 | A0: edge hardening, switches, drop unused `Tenant` | cut-over P0 | ✅ code done 2026-11-11, commits `1c12e52` (0.4, 0.5) and `eb3bd7f` (0.1–0.3): 0.1 FAPI TLS in `nginx-mipay-sme.conf` (`nginx -t` ok); 0.2 `externalHost()` (DPoP `htu`, BFF `redirect_uri`, upstream `X-Forwarded-Host` from a trusted proxy only; tests); 0.3 `IDP_ENVIRONMENT` (no seed users and a public https issuer outside dev/sandbox; tests); 0.4 switches; 0.5 `Tenant` removed, V14 verified on Postgres. **Waiting on ops (⛔ for production only):** copy the nginx file to the host and reload; DNS + certificate for `auth.afriqpay.com` before `IDP_ENVIRONMENT=production` |
| 1.2 | A1: IdP credential accounts, enrolment API, events, `uid` claim, `CommsClient` (sandbox) | cut-over P1 | ✅ 2026-11-12, commit `76e1e0a` (IdP) + `a6c9b84` (gateway: `uid` as RBAC subject and in the context, back-channel logout endpoint, BFF fixes). As built and decisions: cut-over plan P1 "Status". Events to the core wait in the IdP's queue until P3.6 sets `IDP_CORE_EVENTS_URL` |
| 1.3 | A2: delete the demo RBAC; build the grid, roles, SoD, four-eyes approvals | cut-over P2 | ✅ 2026-11-12, commit `16374fa` (RBAC service, `grid.yaml`, core org-tree sync with holds) + `a6c9b84` (routes). Role pack for compliance: [`docs/rbac/role-pack-v1.md`](../rbac/role-pack-v1.md). As built: cut-over plan P2 "Status" |
| 1.4 | B0: comms plane skeleton, Postgres queue, sandbox provider, `POST /messages`, `GET /capabilities` | comms P0 | ✅ 2026-11-12, commit `c19d0c5` (service) + `a6c9b84` (compose). As built: comms-design §12 "P0 as built" |

**Gate 1:** e2e suite green with the new IdP and RBAC checks; the comms sandbox passes the activation and OTP flows.
**✅ Met 2026-11-12:** the e2e suite (`a6c9b84`) passed **137/137** three times against an isolated copy of the trust plane (own images, volumes and networks; the running stack untouched), the last run on fresh volumes. It includes activation through a set-up link sent by comms (S21) and merchant OTP through comms (S24). The real backend (`16374fa`) registered its catalogue with the new RBAC (125 cells, 204 endpoints, none rejected) and a core suspension reached RBAC as a hold in about 6 s. Integration tests: IdP 13, comms 8, RBAC 14, backend 159 (0 failures). **Not yet deployed** to the running local stack or afriqpay (§6).

### Stage 2: Dual-stack (about 2 weeks)

| # | Item | Plan | Status |
|---|---|---|---|
| 2.1 | A3: auth contract, `identity_links`, `UserLifecycleService`, org-binding rules U1–U7, reconcile, resolver, backfills | cut-over P3 | ⬜ |
| 2.2 | A4: gateway `auth: session`, CSRF, context v2 with `uid`, RBAC on `uid`; `admin.` on BFF in staging | cut-over P4 | ⬜ (already done in `a6c9b84`: RBAC on `uid`, `uid` in the context, BFF login works end to end and dies on back-channel logout. An out-of-order attempt at the rest is parked in `git stash`, see §8) |
| 2.3 | C1: 2FA foundations in the IdP (TOTP, passkeys, recovery codes, PIN, policy, readiness page), shipped `mfa.mode=off` | 2fa phase 1 | ⬜ |
| 2.4 | B1 starts: real SMS and email providers (the first slice that matters: set-up links and OTP) | comms P1 | ⬜ |

**Gate 2:** backend in `dual` mode with all 20 UAT suites green both ways; both backfills clean on a copy of production; the org cascade tests green.

### Stage 3: Portals and RBAC shadow (about 2 weeks, plus a 1-week shadow)

| # | Item | Plan | Status |
|---|---|---|---|
| 3.1 | A5: portals on `/auth/*`, one at a time (admin → org portals → merchant) | cut-over P5 | ⬜ (an out-of-order attempt is parked in `git stash`, see §8) |
| 3.2 | A6: `RbacAuthz` shadow mode; replace hard-coded checks; authorization matrix in CI | cut-over P6 | ⬜ |
| 3.3 | C2: step-up rules on `@Perm` cells, RFC 9470 + RAR, `step_up_acr` (fresh password while 2FA is off) | 2fa phase 2 | ⬜ |
| 3.4 | C3: recovery (core `ACCOUNT_RECOVERY`, cooling-off, contact changes, break-glass command) | 2fa phase 3 | ⬜ |
| 3.5 | C4: SMS factor and merchant phone-OTP login, code-complete and tested in the sandbox | 2fa phase 4 | ⬜ |
| 3.6 | B1 completes: failover, receipts, suppression, retry queue | comms P1 | ⬜ |

**Gate 3:** 7 days of shadow with zero unexplained mismatches; **compliance signs off the role pack**; every portal on the BFF in staging.

### Stage 4: Cut-over (about 1 week + 1 week of watching)

| # | Item | Plan | Status |
|---|---|---|---|
| 4.1 | `app.rbac.mode=enforce` | cut-over P6 | ⬜ |
| 4.2 | A7: `app.identity.mode=gateway`; delete JWT, passwords, activation, `devices_sessions`, `UserRole`, `OrgMemberRole`, `OrgRightKind` | cut-over P7 | ⬜ |
| 4.3 | Comms B1 live in production: SMS 2FA, merchant OTP login and Forgot password **light up on their own** | comms P1 · 2fa §4.2 | ⬜ |
| 4.4 | 2FA `mfa.mode=shadow` for a week | 2fa §4.4 | ⬜ |

**Gate 4:** one week clean after cut-over; break-glass placeholders replaced; readiness page shows usable methods for every profile.

### Stage 5: 2FA on, KYC isolated, plugin framework (about 3–4 weeks)

| # | Item | Plan | Status |
|---|---|---|---|
| 5.1 | `mfa.mode=enforce` (staff and admins on TOTP or passkey, SMS fallback for org users) | 2fa §4.4 | ⬜ |
| 5.2 | Track D: KYC service split, cross-service go-live gate, EMI profile (kyc-design phases 0, 4, 6) | kyc §15–16 | ⬜ |
| 5.3 | E1: plugin framework (registry, grants, `PluginAccess`, ArchUnit) on the RBAC grid, then Inventory core | inventory §10 steps 2–4 | ⬜ |
| 5.4 | B2: push and in-app (FCM, APNs, device registry, inbox) | comms P2 | ⬜ |

**Gate 5:** 2FA enforced for a week with no lock-outs; `kyc-service` holds all KYC personal data; the plugin boundary test is green.

### Stage 6: Compliance plugins, push, partner lane (about 4 weeks)

| # | Item | Plan | Status |
|---|---|---|---|
| 6.1 | C5: push approval in the MiPay app (live when B2 and the app release are both done) | 2fa phase 5 | ⬜ |
| 6.2 | E2: KYC Periodic Review (feeds the `KYC_LAPSED` hold), Document Vault, Audit Viewer | plugins | ⬜ |
| 6.3 | Inventory POS use case (serialized units, custody, terminals) | inventory §10 step 5 | ⬜ |
| 6.4 | E5: partner API lane (`api.afriqpay.com`, partner clients as service users of their org) | fapi2 §8 step 7, cut-over §7 | ⬜ |
| 6.5 | B3: comms policy (preferences, consent, quiet hours, per-tenant providers) | comms P3 | ⬜ |

### Stage 7: Breadth (ongoing)

| # | Item | Plan | Status |
|---|---|---|---|
| 7.1 | FAPI 2.0 conformance suite in CI; optional certification | fapi2 §8 step 8 | ⬜ |
| 7.2 | E3: Notifications plugin over comms, then Khata (needs it) | plugins | ⬜ |
| 7.3 | Inventory remainder (lots, reservations, shipments) | inventory §10 steps 6–7 | ⬜ |
| 7.4 | E4: plugins that build on Inventory (Field Service, Asset Lifecycle, Procurement, Delivery & Routes, Catalogue Syndication) | plugins | ⬜ |
| 7.5 | E4: commercial and finance plugins (Tabs, Pricing & Promotions, Loyalty, Statements & Reports, Commissions, Expenses, Recurring Billing, Payroll, Accounting Export) | plugins | ⬜ |
| 7.6 | Platform plugins (Partner Webhooks, White-label, Analytics); comms B4 (broadcasts, WhatsApp, scale) | plugins, comms P4 | ⬜ |

Each plugin needs its own detailed plan, like `inventory-plugin.md`, before it starts. The one-page briefs are not enough. Their internal order in Stage 7 is decided by business priority when each plan is written.

---

## 5. Timeline at a glance (estimates)

| Stage | Weeks | Cumulative |
|---|---|---|
| 0 Decide and prepare | 1 | 1 |
| 1 Foundations | 2 | 3 |
| 2 Dual-stack | 2 | 5 |
| 3 Portals + RBAC shadow | 3 | 8 |
| 4 Cut-over | 2 | 10 |
| 5 2FA on, KYC split, plugin framework | 3–4 | ~14 |
| 6 Compliance plugins, push, partner lane | 4 | ~18 |
| 7 Breadth | ongoing | — |

These assume one developer on Track A and a second on Tracks B and C. The comms estimates are rough until §13 of `comms-design.md` has real numbers. A provider contract that slips moves only the *light-up* items (4.3, 6.1), not the cut-over.

---

## 6. Open items across all plans

| Item | Needed by | Owner | Plan |
|---|---|---|---|
| Compliance sign-off of the role pack | Gate 3 | compliance | cut-over D9 / P2.5a |
| Comms provider choices and contracts (§14 assumptions) | B1 (Stage 2) | business | comms §14 |
| Mobile app release date | C5 (Stage 6) | product | 2fa D4 |
| Break-glass officers and deputies (placeholders now) | Gate 4 | management | 2fa §9.6 |
| Current branch-code lists from each bank | before production KYC for new banks | ops | kyc §18 |
| Whether a parent org's admin should ever be a KYC approval stage | Track D | compliance | kyc §18 |
| Inventory assumptions (its "please confirm" list) | E1 (Stage 5) | product | inventory, Assumptions |
| Deploy the hardened `nginx-mipay-sme.conf` to the afriqpay host and reload nginx | production edge (P0.1) | ops | cut-over P0, `eb3bd7f` |
| DNS + certificate for `auth.afriqpay.com`, then `IDP_ENVIRONMENT=production` with that issuer | production IdP (P0.3); before A4 goes live | ops | cut-over P0, `eb3bd7f` |
| **Deploy Stage 1** to the local stack and afriqpay: take a dump of the `rbac` database (cut-over §4.3), then rebuild and recreate `idp`, `rbac`, `gateway` and the new `comms` (`docker compose up -d --build`). The new RBAC drops the demo schema at first boot. Until then the running containers keep their old images **but bind-mount `trustplane/config` from the checkout**: a restart of the old gateway loads the new `routes.yaml` (its RBAC admin routes then fail against the old RBAC), and the old IdP the new `clients.yaml` (harmless). The old RBAC survives a restart because the grid seed has a new name (`grid.yaml`) | before Stage 2 integration work against live services | you / ops | `a6c9b84` |
| Compliance review of the generated role pack [`docs/rbac/role-pack-v1.md`](../rbac/role-pack-v1.md) (starts 0.2; sign-off needed at Gate 3) | Gate 3 | compliance | cut-over P2.5a |
| Set `IDP_CORE_EVENTS_URL` when the core's `POST /internal/auth/events` exists | P3.6 | dev | cut-over P1 status |
| The admin portal's permission screens and the backend's `RbacClientService` still call the removed RBAC endpoints (they already failed before: no mTLS, no gateway context) | P5.5 Roles & access | dev | cut-over §4.1 |
| Disk space on the dev host: about 2.7 GB free on a 97 GB disk; a full disk crashed the Java services once (§8.1) | any further image builds | you | — |

Resolved elsewhere, kept here for traceability: who holds compliance roles in production is now RBAC assignments (cut-over D9/D10), and comms owns the SMS/email provider choice (2fa §17).

---

## 7. How this file is maintained

- **One source of order.** Plans describe *what* and *how*; this file alone says *when*. A plan doesn't set its own start date.
- **Update in the same commit:**
  - **a phase finishes:** mark its row ✅ with the date and the commit, and check whether a gate is now met;
  - **a phase starts:** mark it 🟡;
  - **a plan gains or loses a dependency:** update §3 and the affected rows, and recheck the order against the rules in §2;
  - **a new plan is written:** add it to §1 and slot it into a stage using the rules in §2;
  - **a decision is taken:** remove it from §6 and record it in its own plan.
- **Status legend:** ✅ done · 🟡 in progress · ⬜ not started · ⛔ blocked (say on what).
- **Before starting work,** read §4 for the first ⬜ item whose stage gate is met.

---

## 8. Audit of the working tree (2026-11-11)

Checked against the code, not the plan. **No cut-over phase is done** (P0 is now in progress; see the resolution below). P1, P2, P3, P6 and P7 have not been started: there is no IdP `accounts` table or `uid` claim, the demo RBAC seed and supplier controllers are still there, there is no `identity_links` or lifecycle service, `RbacAuthz.check` still returns `true`, and `JwtService` and `AuthController` still exist.

Uncommitted work found (author unknown; `tools/dev-review-extension.py` suggests an automated implement-and-review loop). It has these problems:

| Where | Problem | Effect |
|---|---|---|
| `V14__drop_tenants.sql` | The `Tenant` entity and `TenantRepository` are still in the code | with `ddl-auto: validate` (Postgres profile) the backend **fails to start** (missing table `tenants`) |
| `V14__drop_tenants.sql` | `drop trigger … on tenants` runs after the table is dropped | the migration may fail on that line, and the trigger never existed anyway |
| `V14__drop_tenants.sql` | inserts a `CORE` v14 `PUBLISHED` row into `kyc_schema_versions` | a fake KYC schema that KYC schema resolution and lists may pick up |
| `AppProperties` | `identityMode`, `idpSync`, `rbacSync` added, but nothing reads them; `idpSync` is the superseded name (the plan now uses `app.auth.provider`) | no effect yet |
| `gateway/config.go` + `routes.yaml` | `session` passes validation, but `server.go` enforces only `token`; `mipay /api/**` is now labelled `auth: session` | the label suggests session protection that does not exist (traffic passes through as before) |
| `frontend/src/app/app.routes.ts` | uses `orgTypeGuard` without importing it | **compile error** |
| `frontend/src/app/app.routes.ts` | `orgTypeGuard('TPTS_OPS')` and `('ADMIN')`: these are not org types | ops and admin routes would be unreachable |
| `frontend/src/app/core/auth.service.ts` | `requestOtp`, `verifyOtp`, `loginWithPassword` removed, but `features/auth/login.ts` still calls them | **compile error**; the merchant app loses login while the backend is still `legacy` |
| `frontend/src/app/core/http.ts` / `app.config.ts` | `authInterceptor` renamed to `sessionInterceptor`, but `app.config.ts` still imports `authInterceptor` | **compile error** |
| `frontend/src/app/core/http.ts` | keeps a session id in `localStorage` and sends `X-Session-ID` | contradicts the design: an HttpOnly `__Host-mp_sess` cookie, nothing in `localStorage`, and `X-CSRF` on writes |
| Order | P4/P5 work started before P1–P3 | the frontend can't work until the IdP, the BFF session enforcement and `GatewayIdentityFilter` exist |

**Resolution (2026-11-11):**
- **P0 kept and fixed:**
  - `Tenant`, `TenantRepository` and the two enums deleted;
  - `V14` is now a plain `drop table if exists tenants` (no trigger line, no fake KYC schema row);
  - switches renamed to the plan's names (`authProvider` replaces `idpSync`).

  The backend compiles and its test suite passes (159 tests, 0 failures, 3 skipped). `V14` also verified on Postgres 16: a fresh database migrates V1–V14 and the backend starts with `ddl-auto: validate`.
- **P4/P5 attempt parked**, not deleted: `git stash` entry *"parked: out-of-order cut-over P4/P5 attempt …"* holds the gateway `routes.yaml` / `config.go` and the merchant-app `app.routes.ts`, `auth.service.ts`, `guards.ts`, `http.ts`, `nav-shell.ts` changes. The merchant app type-checks again without them.
  - **When P4/P5 start,** reuse what fits from the stash (the `orgType` guards, the gateway `session` value), but follow the design: an HttpOnly cookie, `X-CSRF` on writes, nothing in `localStorage`, and platform users routed by permission, not by a fake org type.

### 8.1 Stage 1 delivery notes (2026-11-12)

- **Bugs found and fixed while proving Gate 1:**
  - **IdP token binding:** behind the gateway, the IdP took the gateway's own mesh certificate as the client's, so a token request with no DPoP proof and no client certificate was "bound" to the gateway instead of refused (FAPI 2.0 requires refusal). Fixed in `76e1e0a`, with a regression test.
  - **BFF login never completed:** the token exchange signed DPoP for the mesh URL, not the issuer's token URL. Fixed in `a6c9b84`.
  - **Old e2e S14** could only pass because of the binding bug; rewritten to send real proofs.
  - **First RBAC decision after a cold start** exceeded the gateway's 400 ms budget; the gateway now warms the channel and allows 1.5 s (still fails closed).
- **Incident:** during the isolated test builds the disk filled up (95 of 97 GB were already used). The running `sme-backend-1` and `trustplane-rbac-1` JVMs crashed (exit 139). The backend was restarted with your OK. The RBAC restarted by itself but then loaded the new grid seed through the config bind mount and crash-looped, so the grid seed was renamed to `grid.yaml`, letting the old RBAC start on its own database as before. Only the test stack's images, volumes and the dangling build cache were removed to free space. Neither live database logged a write failure.

