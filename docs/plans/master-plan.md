# Master plan: what is left, in order

Status: **living document** · last updated 2026-11-12

Only work still to do is listed here. Finished work, delivery notes and the old audit are in git history (`git show 953c2ab:docs/plans/master-plan.md`). Detail lives in each plan. This file only says **what comes next**.

## 1. Plans with work left

| Plan | What is left |
|---|---|
| [`org-access-model.md`](org-access-model.md) | the TPTS-rooted model: OA1–OA8 |
| [`idp-rbac-cutover-plan.md`](idp-rbac-cutover-plan.md) | P3 remainder, P4, P5, P6 (enforce), P7 |
| [`2fa-design.md`](2fa-design.md) | phases 1–5, shipped `off`, switched on after the cut-over |
| [`comms-design.md`](comms-design.md) | B1 real email/SMS, B2 push and in-app, B3 policy, B4 scale |
| [`kyc-design.md`](kyc-design.md) | phase 0 (own service), 4 (cross-service go-live gate), 6 (EMI profile) |
| [`inventory-plugin.md`](inventory-plugin.md), [`plugins/*`](plugins/README.md) | plugin framework, Inventory, then the plugins |

## 2. Rules for the order

1. Permissions before features: no plugin starts until RBAC enforces (P6).
2. Roles must be right before RBAC enforces (OA1–OA3 before P6).
3. Ship behind switches, switch on last, with a week of shadow before each switch-on.
4. Comms runs on its own track; features that need it light up when it lands.

## 3. The sequence

**Deadline:** the `core_authorized` bridge closes on afriqpay on **2026-12-31**. Step 2 must be done by then, or the date moved (`RBAC_CORE_AUTHORIZED_UNTIL`).

| Step | Work | Plan |
|---|---|---|
| **1. Now** | Commit and push the review fixes; deploy to afriqpay (trustplane first, then `deploy/afriqpay/preflight.sh`, then mipay) | — |
| | P3 remainder: org-binding backfill + `users.org_id NOT NULL`, identity backfill, RBAC backfill, admin access screen; UAT in `dual` mode | cut-over 3.9–3.11 |
| | OA1 TPTS root bootstrap · OA2 capability roles · OA3 per-type onboarding rights in the UI | org-access |
| | Comms B1 starts (real email/SMS providers) | comms P1 |
| **2. Before 2026-12-31** | P4 gateway sessions · P5 portals on the BFF, with OA7 org-centred navigation | cut-over, org-access |
| | P6: RBAC shadow for 7 days, then enforce (OA8) | cut-over P6 |
| | Compliance signs off the role pack | cut-over D9 |
| **3. Then** | OA5 risk tiers · OA6 approval chains (4-eyes, 6-eyes for EMI) | org-access |
| | P7: identity `gateway` only; delete legacy JWT, passwords, `UserRole` | cut-over P7 |
| | 2FA phases 1–4, then `mfa.mode` shadow → enforce · OA4 activation emails live with B1 | 2fa, comms |
| **4. After** | KYC service split · plugin framework + Inventory · compliance plugins · push (B2) · partner API lane | kyc, inventory, plugins, comms |
| **5. Ongoing** | FAPI conformance · remaining plugins · comms B3/B4 | — |

## 4. Open decisions and dependencies

| Item | Owner | Needed by |
|---|---|---|
| Decisions D-1 … D-8 (MiNdLABS access, tiers, thresholds, first-user 4-eyes, portal merge, baseline roles, EMI scope, tier sign-off) | you, compliance | step 1 (D-1, D-6), step 3 (rest) |
| Role pack review and sign-off ([`role-pack-v1.md`](../rbac/role-pack-v1.md)); regenerate it when `grid.yaml` or `@Perm` changes | compliance | step 2 |
| Comms provider contracts (email, SMS, WhatsApp) | business | step 1 |
| Break-glass officers (placeholders in 2fa §9.6) | management | step 3 |
| Mobile app release date (gates push 2FA) | product | step 4 |
| DNS + certificate for `auth.afriqpay.com`, then `IDP_ENVIRONMENT=production` | ops | before P4 goes live |
| Deploy the local dev stack (dump `rbac`, create `comms` and `gateway` DBs, rebuild) | you | step 1 |
| Rotate `ops-admin-ops` on afriqpay (likely still the seed password) | you | now |
| Disk on the dev host is about 93% full | you | before more image builds |

## 5. Keeping this file current

- Finish something: **delete its row** (git keeps the history).
- Add a plan: add it to §1 and slot it into §3 using §2.
- Take a decision: remove it from §4 and record it in its plan.
