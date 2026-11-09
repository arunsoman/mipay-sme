# KYC & Compliance — PRD section

> Status: **sandbox implementation** (this repo) + specification for production.
> Every screening provider is **SIMULATED** and audit-labelled. Regulatory basis:
> Namibian FIC Act 29 of 1996 (AMLF), PEP/sanctions obligations, AML/CFT
> directives; legal wording to be confirmed with counsel before go-live.
> This section was added after a PRD gap review concluded that KYC was treated
> as a one-line prerequisite ("identity and ownership are verified", UAT SB
> p.8) rather than a specified capability.

## 1. Scope

| Capability | Sandbox status | Where |
|---|---|---|
| Merchant KYC (identity + ownership) | **implemented** | `OnboardingService.approveMerchant` |
| Supplier KYC (owner + bank account) | **implemented** | `approveSupplier` + independent bank approval |
| Sanctions screening (simulated provider) | **implemented** | `ComplianceService.recordCheck(SANCTIONS)` |
| Screening blocks onboarding (sanctions FAIL) | **implemented** | BLOCKED + REJECTED audit (`TC-KYC-03`) |
| Risk rating + review cycle | **implemented** | `applyRiskRating`, `kyc_risk_rating` / `kyc_next_review_date` |
| Trigger-event re-screening (bank change) | **implemented** | `kyc_rescreened` audit on account activation (`TC-KYC-10`) |
| Ops KYC review panel (read model) | **implemented** | `GET /api/ops/kyc/{type}/{id}` + ops UI panel (`TC-KYC-13`) |
| KYC audit trail (actor, before/after) | **implemented** (audit_log, SIMULATED-labelled) | `kyc_risk_rated`, `merchant_approval_blocked` |
| Beneficial ownership (UBO) registry | **not implemented** — see §9 roadmap | — |
| Document upload / liveness | **not implemented** (basic-phone onboarding collects ID number only) | — |
| Periodic-review enforcement (restrict on overdue) | **data model only** — restriction scheduler planned | — |
| EDD cases, adverse-media provider | **planned** (enum `ADVERSE_MEDIA` exists) | — |
| Data sharing with lender beyond BC p.12 consent | consent covers financial records only | — |
| SAR / DSAR / velocity monitoring | **implemented** (out of KYC-scoping: K01–K07 family) | `TUat10ComplianceDataProtectionTests` |

## 2. Workflow state machine (as implemented)

```
register (public M02/S02)            approveMerchant(approve=true, sanctions)
  user + merchant PENDING  ──────▶  IDENTITY check  + SANCTIONS check
                                        │                     │
                                        │        FAIL ────┐   │
                                        ▼                 ▼   ▼
                                  VERIFIED + APPROVED   REJECTED + BLOCKED audit
                                  risk rating + provision   (no provision, no sign-in)
```

- **Reviewer ≠ requester** is structural: public self-registration is
  anonymous; approval is a TPTS_OPS action (`TC-SET-17`).
- **Bank receiving accounts** are a separate approval with an explicit
  second-person rule: `requestedBy != approverId` → else
  `INDEPENDENCE_RULE` conflict (`TC-KYC-08`, enforced by UAT).
- Each account activation is a **trigger event** that re-opens sanctions
  screening (TC-KYC-10).

## 3. Risk model (sandbox rules)

At approval time (`KYC spec §5` equivalent):

| Input | Rule | Rating | Review cycle |
|---|---|---|---|
| business type contains cash/gambling/forex | cash-heavy | HIGH | 12 months |
| sanctions screening = MANUAL_REVIEW | enhanced diligence | HIGH | 12 months |
| business type blank | data gap | MEDIUM | 24 months |
| default retail/service | standard | LOW | 36 months |
| supplier (any) | bank-exposure default | MEDIUM | 24 months |

Rating + `next_review_date` are stored on `merchants` / `suppliers`
(`kyc_risk_rating`, `kyc_next_review_date`; migration `V3__kyc_risk_cycle.sql`
for Postgres), surfaced in the ops directory DTOs, and audited
(`kyc_risk_rated` with `review cycle Xm`).

**Enforcement of overdue reviews (restriction/suspension) is data-model only in
sandbox** — a daily job would flag `kyc_next_review_date < today` and restrict
new credit/B2B. Documented as roadmap §9.

## 4. Data model (as implemented vs spec)

Implemented today (subset of the spec tables):

| Spec table | Implementation |
|---|---|
| kyc_cases | merchant/supplier row itself: `kyc_status` + `kyc_risk_rating` + `kyc_next_review_date` + `approved_by/at` |
| screening_checks | `kyc_checks(id, entity_type, entity_id, check_type, result, details, checked_at)` |
| kyc_audit | `audit_log` (before/after hash, actor, SIMULATED labels) |
| kyc_documents, beneficial_owners, periodic_reviews, edd_cases | **not implemented** — roadmap §9 |

Read model: `Dtos.KycCheckRow` (DTO-only, one indexed query, newest first).

## 5. Screens as implemented (public + ops)

| Spec screen (provisional IDs) | Delivered as |
|---|---|
| KYC-01..02 merchant registration | `/onboarding/merchant` (M02, `mp-entity-form`) |
| KYC-12 supplier registration | `/onboarding/supplier` (S02, `mp-entity-form`) |
| KYC-08 review status | supplier home "KYC: status" + merchant/ops directory columns |
| KYC-16 approval / rejection + maker-checker | `OpsApprovals` (approve/reject per row; bank second-person rule) |
| KYC-20 KYC audit trail / screening result | **KYC review panel**: selecting a directory row in `OpsApprovals` shows recorded IDENTITY/SANCTIONS checks (type, result chip, details, timestamp) |
| Rest (KYC-03..07, 09..11, 13..15, 17..19) | not implemented — roadmap |

## 6. API surface

- `POST /api/onboarding/merchant` / `POST /api/onboarding/supplier` (public, permitAll)
- `POST /api/ops/approvals/merchants/{id}` (body: approve + sanctionsResult; CLEAR/FAIL/MANUAL_REVIEW)
- `POST /api/ops/approvals/suppliers/{id}`, `POST /api/ops/approvals/bank-accounts/{id}`
- `GET /api/ops/kyc/{merchant|supplier}/{entityId}` → `List<KycCheckRow>` (TPTS_OPS role only; unknown type → 404)

## 7. Acceptance criteria (test pack)

`TUat16KycTests` (all green in the full pack):

| TC | Scenario | Assertion focus |
|---|---|---|
| TC-KYC-01 | approval records IDENTITY + SANCTIONS | checks PASS + "simulated" details; VERIFIED; LOW rating; ~36m review; audited |
| TC-KYC-03 | sanctions FAIL | BLOCKED result; KYC REJECTED; not APPROVED; FAIL row visible to panel |
| TC-KYC-20 | risk rules | cash-heavy → HIGH + ≤13m review date |
| TC-KYC-10 | bank change re-screen | +1 SANCTIONS row; `kyc_rescreened` audited per activation |
| TC-KYC-13 | ops read model | HTTP read model OK for ops, 403 for merchant role |

Related pre-existing coverage: TC-SET-17 (independent bank approval),
TC-ONB-02/03, TC-RACE-13 (approval exactly-once), TC-OPS-26 (velocity AML),
TC-OPS-28..30 (SAR/DSAR), K-family in `TUat10`.

## 8. Roles

| Role | KYC duty |
|---|---|
| TPTS_OPS | performs registration review; approves/rejects; sanctions result is recorded per approval; second approver for bank changes |
| MINDLABS_ADMIN | access console (X04) can revoke a device after a KYC concern; compliance screen (SAR/DSAR) |
| MERCHANT/SUPPLIER | submit self-registration; supplier requests bank changes |
| LENDER | consented financial-records view only (BC p.12); no KYC document access |

## 9. Roadmap to production KYC (explicitly out of sandbox scope)

1. Beneficial-ownership registry (≥25% UBOs + PEP flags) — new `beneficial_owners` table.
2. Document capture + verification provider (OCR/liveness), document expiry sweep.
3. Screening provider integration (sanctions/PEP/adverse media) with match triage + false-positive workflow.
4. Periodic-review scheduler + overdue restriction on new credit / B2B spend.
5. EDD cases with senior-approval gate and source-of-funds capture.
6. Risk-scoring engine (transaction analytics feeding rating recalcs, TC-KYC-20 rule upgrade).
7. Bank account ownership verification (name-match proof) before activation.

Every provider integration stays behind `Connector` + SIMULATED labelling so
UAT flows are unchanged between sandbox and live providers.
