# Plan: the org-centred access model

Status: **draft for review** · 2026-11-12

The target model, and what is still missing to get there. It builds on extended RBAC
([`extended-rbac.md`](extended-rbac.md)) and the identity cut-over
([`idp-rbac-cutover-plan.md`](idp-rbac-cutover-plan.md)).

## 1. The model

1. **mindlabs-admin** (the system operator) creates the root org **TPTS** and its first admin. After that, MiNdLABS has no standing access to TPTS business data (only break-glass, D-1).
2. The **TPTS admin** creates TPTS users and gives them roles. TPTS's ceiling is every role in the system.
3. A user with an **onboarding right for a type** (Manufacturer, Distributor, Supplier, Merchant, Lender) onboards an org of that type and picks its role ceiling (extended RBAC). Org types are fixed.
4. When the new org is created, its applicant gets a **unique username** and a set-up link at the KYC contact email. They fill in KYC as `kyc_applicant`. When the org goes **active** they become `org_admin` and get a "you are live" email.
5. That first user is the org's **privileged user**: they hold everything in the org's ceiling.
6. If the ceiling includes **user management**, they can create, edit and remove users. If it includes **org management**, they can hand on onboarding rights for the types they hold themselves.
7. **Privileged actions need a second person** (4-eyes). EMI actions also need a final approval at TPTS (6-eyes). The same rules apply inside TPTS.

## 2. Gaps and work items

| # | Item | What to build | Depends on |
|---|---|---|---|
| OA1 | **TPTS root bootstrap** | Split today's shared root: a `MINDLABS` operator org (platform engineer, break-glass, bootstrap only) and `TPTS` as the operating root (ceiling = all roles). Migration moves today's TPTS ops users to TPTS. A one-time bootstrap screen or command lets mindlabs-admin create TPTS and its first admin (enrolled, set-up link). The bootstrap refuses if TPTS already exists | D-1 |
| OA2 | **Standard capability roles** | Split `org_admin` into standard roles an org can be given or withheld: `org_user_admin` (user create/edit/remove, role assignment inside the ceiling), `org_onboarder` (onboard child orgs, per-type grant), `org_access_admin` (org-defined roles). `org_admin` stays as their union. Add them to `grid.yaml`, regenerate the role pack | D-6 |
| OA3 | **Onboarding rights per type in the UI** | Already modelled as `org.onboarding:create` plus a grant per child type. The user screen shows them as "Onboard Merchant / Distributor / …" checkboxes. An org may hand on only types it holds itself, and only types the fixed type rules allow below it | OA2 |
| OA4 | **Activation emails reach a real inbox** | Comms B1 (real email and SMS provider). Username generated at submission, unique, sent with the set-up link; "you are live" email at go-live | comms B1 |
| OA5 | **Risk tiers per cell** | Each grid cell gets a tier: T0 read · T1 routine · T2 step-up · T3 4-eyes · T4 6-eyes with TPTS final. It is declared in `@Perm` (`tier`, `stepUp`) and in `grid.yaml`. RBAC refuses at registration a tier below the rule-derived floor (money, rights, identity, KYC, controls, bulk data, irreversible). An unclassified cell counts as T4. Context can only raise a tier (amount over a threshold, acting on another org, new payee, EMI) | D-2, D-3 |
| OA6 | **Approval chains** | `Check` on a T3/T4 cell returns "approval required" with the chain. Approvals are bound to a payload hash, have a step-up per approval, and need a different person at each stage. Stages are `own`, `parent` or `platform`, and EMI adds the TPTS final stage. **Small-org fallback:** an org with fewer than two eligible approvers escalates to its parent, then to TPTS. Changing a tier is itself T4 | OA5, P6, D-4 |
| OA7 | **Org-centred navigation** | One shell, the same for every org including TPTS: **My org** (profile, KYC, users, roles & access, approvals inbox) · **My network** (children, onboard by type, each child's status, ceiling and users) · business screens · **Platform** tabs for TPTS only. Drop screens the new model doesn't need | D-5 |
| OA8 | **RBAC enforces** | Cut-over P6: shadow for 7 days with no unexplained mismatches, then `app.rbac.mode=enforce`. Must happen before the `core_authorized` bridge closes (2026-12-31 on afriqpay), or that date moves | cut-over P3–P5 |

**Order:** OA1, OA2 and OA3 come first: the roles must be right before RBAC enforces. Then OA8. OA5 and OA6 follow after enforcement. OA7 runs alongside P5 (portals), and OA4 lights up when comms B1 is live.

## 3. Decisions needed

| # | Question |
|---|---|
| D-1 | MiNdLABS after bootstrap: no standing access (break-glass only), or a read-only support role? |
| D-2 | Which actions are T3 and which are T4. The proposed rubric is in OA5; compliance confirms it in the role pack |
| D-3 | Payout threshold for T3 → T4 (per currency), and whether acting on another org always adds a stage |
| D-4 | The first privileged user: are they exempt from 4-eyes inside their own org (your model), or does 4-eyes always apply, with the small-org fallback to the parent? (Recommended: always apply, with the fallback) |
| D-5 | Merge the admin portal and the org portals into one shell (OA7), or keep them separate with the same layout? |
| D-6 | Is `org_user_admin` in the baseline (always inside a ceiling) or chosen by the onboarder? |
| D-7 | EMI scope: which orgs and which actions get the TPTS final stage |
| D-8 | Who signs off tier changes: compliance alone, or compliance and the MLRO for money? |
