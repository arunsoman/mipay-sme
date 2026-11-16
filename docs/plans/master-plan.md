# Master plan: what is left, in order

Status: **living document** · last updated 2026-11-15

afriqpay runs `329b7c7` (KYC v3 with the 5-step onboarding wizard, OA1/OA2, **RBAC enforcing** with legacy-parity roles; deployed 2026-11-12, rollback tags `:pre-kycux-20261112` (portals) / `:pre-onbkyc-20261112` / `:pre-enforce-20261112`, or `APP_RBAC_MODE=shadow`), except comms, which stays on its previous image until `BrevoEmailSenderTest` passes. Only work still to do is listed here. Finished work, delivery notes and the old audit are in git history (`git show 953c2ab:docs/plans/master-plan.md`). Detail lives in each plan. This file only says **what comes next**.

## 1. Plans with work left

| Plan | What is left |
|---|---|
| [`org-access-model.md`](org-access-model.md) | the TPTS-rooted model: OA1–OA8 |
| [`idp-rbac-cutover-plan.md`](idp-rbac-cutover-plan.md) | UAT in `dual` mode; then P4, P5, P6 (enforce), P7. Backfills (3.9–3.11) shipped: org-binding + `users.org_id NOT NULL` (V15_1), identity backfill with hash import, RBAC backfill, the access screen with link/session actions (commits `2aef8aa`, `2af6317`, `ca1e101`, `ab38504`) — reconcile with [`../../_goal.md`](../../_goal.md)'s "clean test installation, no backfills" note before the dual run |
| [`2fa-design.md`](2fa-design.md) | phases 1–5, shipped `off`, switched on after the cut-over |
| [`decision-permission-model.md`](decision-permission-model.md) | D-9 decision-effect model: frozen proposals, `return` outcome, supersession |
| [`comms-design.md`](comms-design.md) | B1 real email/SMS, B2 push and in-app, B3 policy, B4 scale |
| [`kyc-design.md`](kyc-design.md) | phase 0 (own service), 4 (cross-service go-live gate), 6 (EMI profile) |
| [`role-menu-builder.md`](role-menu-builder.md) | New role by portal: menu/submenu visibility + per-screen actions (admin first, then org User management) |
| [`admin-console.md`](admin-console.md) | Admin console (three menus; server search on every list; org features = portal menus via `organization_access@<org>`, presets, warnings). **Live on afriqpay 2026-11-16** (`636264d`, rollback tag `:pre-console-20261116`). Left: pick a preset for every org showing "Access not set"; give the admins who set features a platform role covering the Organizations/KYC rows |
| [`bookkeeping-and-charges-design.md`](bookkeeping-and-charges-design.md) | Core double-entry ledger for all money (books, head/leaf GL → ledger accounts per org wallet/loan/payable, two-phase transfers, posting rules, period close; replaces the single-sided ledgers) and one charges engine (fees, commissions, penalties, discounts, taxes; plans on the org tree; quote/explain/simulate). Phases B0–B6; decisions D-BK-1…7. Then the revenue-share plan (MiNdLABS ↔ TPTS) |
| [`payments-and-banking-design.md`](payments-and-banking-design.md) | Real money movement: payment orchestrator on the double-entry ledger, isolated bank gateway (signed instructions, HSM keys, rail adapters, SIM adapter), ISO 20022 / instant / EFT / RTGS / card rails, security controls, automatic + manual reconciliation with day certification. Phases PB0–PB7; decisions D-PAY-1…7 |
| [`inventory-manufacturing-plugin.md`](inventory-manufacturing-plugin.md), [`plugins/*`](plugins/README.md) | plugin framework with packs, then Inventory & Manufacturing phases I0–I11 (TPTS devices first at I3), then the other plugins. Decisions D-INV-1…9 open |
| [`permission-atlas.md`](permission-atlas.md) | One generated permission dictionary (backend + frontend meaning), sync of definitions and live rights to every system, one conflict engine (SoD incl. cross-row, ceilings, visibility, concurrent edits), explain. Steps 1–8; decisions 1–6 proposed; Q1–Q3 for compliance |
| [`portal-builder.md`](portal-builder.md) | Portal builder: menus + screens filled with registered, self-hiding, mobile-first widgets; FAPI by default through the BFF port. Steps 0–7 (step 0 = shared CSRF interceptor, fixes sme-ui sending no `X-CSRF`); decisions 1–11 proposed |

## 2. Rules for the order

1. Permissions before features: no plugin starts until RBAC enforces (P6).
2. Roles must be right before RBAC enforces (OA1–OA3 before P6).
3. Ship behind switches, switch on last, with a week of shadow before each switch-on (RBAC enforce was the deliberate exception).
4. Comms runs on its own track; features that need it light up when it lands.

## 3. The sequence

**Deadline:** the `core_authorized` bridge closes on afriqpay on **2026-12-31**. Step 2 must be done by then, or the date moved (`RBAC_CORE_AUTHORIZED_UNTIL`).

| Step | Work | Plan |
|---|---|---|
| **1. Now** | P3: backfills + not-null org are done (V15_1, identity/RBAC backfill tools, access screen); UAT in `dual` mode remains | cut-over 3.9–3.11 |
| | OA3 per-type onboarding rights in the UI ( OA2 roles live in `grid.yaml` + role pack regenerated; OA1 bootstrap built — `V18` + `POST /api/admin/bootstrap/tpts`) | org-access |
| | Role builder by portal menu (admin Roles and permissions, then org User management) | role-menu-builder |
| | Admin console: set the existing orgs' presets ("Needs attention" lists them) | admin-console |
| | Portal builder step 0: one shared `fapiBffInterceptor`; sme-ui sends no `X-CSRF` today | portal-builder |
| | Permission atlas steps 1 and 3: `perms:drift` + generated atlas (frontend list has drifted); browsers re-check rights on focus / 403 | permission-atlas |
| | Comms B1: Brevo email adapter done and tested (live send waits on the Brevo **API** key — the held `brevo` credential is SMTP-relay); SMS provider choice still open | comms P1 |
| **2. Before 2026-12-31** | P4 gateway sessions · P5 portals on the BFF, with OA7 org-centred navigation | cut-over, org-access |
| | P6/OA8 enforce is **on** (skipped the shadow week by decision, 2026-11-12). Left: shrink the `legacy_*` parity roles to the D9 split in the matrix, replace the hard-coded legacy checks (6.3), gateway enforcement (6.6), matrix test (6.7) | cut-over P6 |
| | Compliance signs off the role pack | cut-over D9 |
| **3. Then** | Permission atlas steps 2, 4, 5: declared cross-row SoD, versioned role edits, conflict engine + explain (after compliance answers Q1–Q2) | permission-atlas |
| | OA5 risk tiers · OA6 approval chains (4-eyes, 6-eyes for EMI) | org-access |
| | P7: identity `gateway` only; delete legacy JWT, passwords, `UserRole` | cut-over P7 |
| | 2FA phases 1–4, then `mfa.mode` shadow → enforce · OA4 activation emails live with B1 | 2fa, comms |
| | Portal builder steps 1–5: widget contract + port, workbench + tests, mobile-first grid, first widgets (KYC application), layouts + RBAC screen third level (after P5: widgets rely on the BFF lane) | portal-builder |
| | Onboarding / Users / Access widget set (2026-11-16): 18 widgets (onboarding queue, onboard child, KYC status, KYC checks, network, roles, assignments, role approvals, role ceiling, rights, my access, permission catalogue, reconciliation, platform assignments, sign-in, new administrator, platform access, audit). Portals now draw a built-in layout (`widgets/core/default-layouts.ts`) with only these three menus; coded menus are off. Next: republish (or unpublish) any layout already published on afriqpay, which still wins over the built-in one; then add the other menus back as widgets | portal-builder |
| **4. After** | Portal builder step 6 (builder UI, registry server); step 7 runtime layouts only if business admins need them | portal-builder |
| | Bookkeeping + charges B1–B4 · payments & banking PB1–PB2 (SIM rail + reconciliation), PB3 real rail once the sponsor bank is chosen · revenue share (B6, PB7) · KYC service split · plugin framework + Inventory & Manufacturing (I1–I3 first; I10 invoicing after B4) · compliance plugins · push (B2) · partner API lane | kyc, inventory, plugins, comms |
| **5. Ongoing** | FAPI conformance · remaining plugins · comms B3/B4 | — |

## 4. Open decisions and dependencies

| Item | Owner | Needed by |
|---|---|---|
| Decisions D-2 … D-8 ( tiers, thresholds, first-user 4-eyes, portal merge, baseline roles, EMI scope, tier sign-off — **D-1 MiNdLABS split and D-6 capability roles were decided 2026-11-12**) | you, compliance | step 3 (rest) |
| Role pack review and sign-off ([`role-pack-v1.md`](../rbac/role-pack-v1.md)); regenerate it when `grid.yaml` or `@Perm` changes | compliance | step 2 |
| Comms provider contracts (email: Brevo — API key still missing; SMS, WhatsApp) | business | step 1 |
| Break-glass officers (placeholders in 2fa §9.6) | management | step 3 |
| Mobile app release date (gates push 2FA) | product | step 4 |
| DNS + certificate for `auth.afriqpay.com`, then `IDP_ENVIRONMENT=production` | ops | before P4 goes live |
| Deploy the local dev stack (dump `rbac`, create `comms` and `gateway` DBs, rebuild) | you | step 1 |
| Rotate `ops-admin-ops` on afriqpay (likely still the seed password) | you | now |
| Disk on the dev host is about 93% full | you | before more image builds |
| Permission atlas Q1–Q3: the cross-row maker/checker pairs, STATIC vs DYNAMIC, grantability of api-only cells | compliance, you | before permission-atlas step 2 |
| Bookkeeping decisions D-BK-1…7 (customer funds on/off TPTS balance sheet, VAT per charge type, fiscal year, revenue-share treatment) | you, compliance, finance, tax adviser | before B1 |
| Payments & banking decisions D-PAY-1…7 (sponsor bank and rails, virtual accounts, card acceptance, regulatory list) | business, compliance | before PB3 (PB0 now: bank specs and test access take time) |
| Inventory & Manufacturing decisions D-INV-1…9 (grant model, network share, org-to-org invoicing, cost method) | you, compliance | before inventory I1 |
| Portal builder decisions 1–11 (build-time layouts, one network path, widget visibility, screen ids, mobile grid, test runners) | you | before portal-builder step 1 |

## 5. Keeping this file current

- Finish something: **delete its row** (git keeps the history).
- Add a plan: add it to §1 and slot it into §3 using §2.
- Take a decision: remove it from §4 and record it in its plan.
