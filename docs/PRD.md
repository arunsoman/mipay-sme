# MiPay SME Ecosystem — Consolidated Product Requirements Document

**Version 2.0 · single source of truth.** This PRD consolidates (a) every
feature already implemented and UAT-verified in this repository, (b) the
value-chain extension requested by product notes (manufacturer / dealer /
distributor onboarding, tiered invoices), and (c) the two new payment
capabilities: **Pay-Now on every invoice** and the **invoice payment
scheduler**. Use cases are specific and traceable to the test pack.

Regulatory posture: sandbox/UAT build — every bank/lender/connector event is
**SIMULATED** and audit-labelled (`docs/UAT-BACKEND-TEST-PACK.md`).
Currency: NAD. Compliance: see `docs/KYC-COMPLIANCE-SPEC.md`.

---

## 0. Status legend

| Mark | Meaning |
|---|---|
| ✅ | Implemented in this repo; acceptance evidence in the UAT pack |
| 🆕 | Specified by this PRD (not yet built) — build order in §14 |
| ◻ | Roadmap (out of sandbox scope) |

---

## 1. Product overview

MiPay SME is a mobile-first commerce + payments + credit platform for
Namibian townships and small enterprises:

- **Merchants** accept payments (QR, short-code, card), pay suppliers for
  stock (B2B), track inventory/khata credit books/tabs, get cash advances on
  receipts, and withdraw to their bank.
- **Supply-side partners** (suppliers — extended in §7 to distributors,
  dealers, manufacturers) publish catalogues, take orders, issue invoices.
- **Lenders** underwrite cash advances against 90-day confirmed receipts and
  collect 10% of daily receipts until the advance is repaid.
- **TPTS ops** reviews onboarding/KYC, runs reconciliation, handles complaints/
  disputes/incidents.
- **MindLabs admin** owns platform configuration, connectors, the simulator,
  access revocation, compliance tooling and go-live hygiene.
- **Customers** pay from their own bank account on a basic phone (customer
  simulator mirrors this in UAT; customer-side onboarding is bank's job).

Money rules (non-negotiable, NFR-1..NFR-8 in §13): three-ledger posting, no
overdraft, exactly-once effects, advance cap = min(N$25,000 · 20% avg monthly
receipts · lender limit), 10% daily-receipts sweep, unique idempotency keys,
SIMULATED labelling, machine-readable problem codes.

## 2. Actors & onboarding ladder (value chain)

```
MANUFACTURER 🆕  ──invoice──▶  DEALER/DISTRIBUTOR 🆕  ──invoice──▶
        WHOLESALER/SUPPLIER ──invoice──▶  MERCHANT  ──sells──▶  CUSTOMERS
```

| # | Actor | Onboarding | Today |
|---|---|---|---|
| A1 | Merchant (SME) | Public self-registration M02 → TPTS ops approval → KYC (identity + sanctions) → provision (balance a/c, QR, short-code) | ✅ |
| A2a | Supplier / wholesaler | Public self-registration S02 + receiving bank account → independent ops approval → KYC | ✅ |
| A2b | **Distributor / Dealer** | Same S02 flow; `supplierType` distinguishes tier; trades with merchants and buys from above tier | 🆕 |
| A2c | **Manufacturer** | Same flow; `supplierType = MANUFACTURER`; invoices the tiers below (dealer first) | 🆕 |
| A3 | Lender | Seeded/admin-provisioned institution (licence ref) with credit rules | ✅ ◻ |
| A4 | TPTS ops staff | Admin-provisioned device (password + MFA) | ✅ |
| A5 | MindLabs admin | Same | ✅ |
| A6 | **Customer (merchant's)** | Not platform-registered; pays as bank payer; merchant records name+phone for khata/tab credit books; registered-customer profiles with credit limits ◻ | ✅ ◻ |

Onboarding is one pipeline for all self-registered actors (UC-ONB-1..7):
public form → PENDING → ops review (KYC review panel) → approve/reject →
provision. Reviewer ≠ requester always; bank-account changes always need a
second person; approval records IDENTITY + SANCTIONS checks and a risk
rating with a review cycle (LOW 36m / MEDIUM 24m / HIGH 12m).

## 3. THE invoice — one object for every tier

A single invoice object is the money hub of the chain. Properties:

- `invoiceNumber` + globally `uniqueReference` (exactly-one-invoice-per-order, TC-B2B-01)
- payer (merchant/buyer), payee (supplier-tier entity), amount (NAD), status
  `ISSUED → PROCESSING → PAID` (+ `PARTIALLY_PAID` when schedules pay in
  instalments, `CANCELLED`), payment route `BOOK_TRANSFER | EFT` (IPP native
  excluded at go-live)
- **due date 🆕** (credit terms; drives scheduling and the scheduler)
- **PAY-NOW action 🆕 — every invoice, every tier, always payable in one click** (§5)

Invoice lifecycle with scheduling 🆕:

```
ISSUED ──[Pay Now]──▶ PROCESSING ──bank callback──▶ PAID
   │        \── insufficient balance ──▶ refused (hold-first intact)
   └──[Schedule]─▶ SCHEDULED(P#1) ──executor──▶ PROCESSING ──▶ PAID
         └──[Instalments]─▶ SCHEDULED(P1..Pn) ──▶ PARTIALLY_PAID … ──▶ PAID
```

## 4. Use cases — payments & receipt (existing, ✅)

| UC | Use case | Flow (essentials) | Acceptance / evidence |
|---|---|---|---|
| UC-ACC-1 | Merchant accepts customer payment | Choose channel QR / short-code / CARD; sandbox bank callback confirms; subledger credit + audit SIMULATED | Payment CONFIRMED once; pending panel; TC-ACC-01/02 |
| UC-ACC-2 | Idempotent confirmation | Duplicate callback/event absorbed with single-effect guarantee (event key + row lock + unique index) | DUPLICATE_IGNORED; TC-RACE-01; TC-ACC-03 |
| UC-ACC-3 | Card acquiring | Card entry at accept; card fee exactly once on the deployed BC p.16 tariff (2.50%) | Fee row + contribution math; TC-SET-10, TC-ACC-03 |
| UC-ACC-4 | Refund | Full/partial refund ≤ confirmed amount; three-ledger symmetric | REFUNDED; no over-refund (TC-RACE-10) |
| UC-ACC-5 | Speaker log | Merchant's day-audit event feed per confirmed payment/tap | list endpoint; TC-BAL family |
| UC-CUST-1 | Basic-phone payer simulator | `/customer` page: accept + confirm + khata reminder flows, no login | standalone route; browser-verified |

## 5. 🆕 UC-ACC-6 — Pay-Now link on every invoice

**Requirement (verbatim intent):** every invoice shall have a **Pay-Now
link**, clicking which the system initiates the payment.

| Field | Spec |
|---|---|
| Actor | Buyer (merchant, dealer, distributor — the invoice payer) |
| Where | every invoice list row (primary action), invoice detail (primary button), and a shareable in-app deep link on the invoice (`#pay/{uniqueReference}`) |
| Preconditions | Invoice `ISSUED`/`SCHEDULED`; payer portal session (deep link opens login first); payer has a balance account |
| Trigger | Click **Pay Now** |
| Main flow | 1) system resolves invoice by `uniqueReference` (one query, DTO) → 2) idempotency guard: existing HELD/PROCESSING/PAID payment for the reference is **absorbed** (no second charge; screen shows state) → 3) fee preview (card/tariff math) + balance check vs available (holds excluded) → 4) insufficient: HTTP 409 `code=INSUFFICIENT_BALANCE`, UI shows "Schedule instead?" affordance (§6) → 5) lock payer balance account → place **hold** → `PROCESSING` → 6) simulated bank call (route BOOK_TRANSFER/EFT; `X-Sim-Scenario` honoured in sandbox) → callback → confirm → **PAID**, hold → debit, supplier credited, audit SIMULATED, event trail on the payment detail |
| Outcome | Invoice PAID exactly once; buyer and payee side both see the payment; reconciliation stays clean |
| Failure modes | bank TIMEOUT → payment stays PROCESSING/PENDING + retry-timeout action exists; bank FAIL → hold released, invoice returns ISSUED (retry allowed); duplicate click → absorbed |
| Idempotency | `uniqueReference` + payment-event unique index + pessimistic re-lock (TC-RACE-01 standard) |
| Deep link | opening `#pay/ref` when logged out → login → lands back on the invoice detail with Pay-Now in context; when payer lacks access → 403 problem detail |
| Acceptance | UC-SCHED-1a/1b tests + TC-B2B pack; manual browser pass per portal |

UI (atomic design): `mp-button variant="primary"` + `simulated-banner`
context; invoice list uses table row-link, detail uses page CTA row; result
uses kv-pair + status-badge; never bespoke markup.

## 6. 🆕 UC-ACC-7 — Invoice payment scheduler

Because invoices carry due dates, the merchant shouldn't need to click on
the day. The scheduler executes Pay-Now semantics on a clock.

| Field | Spec |
|---|---|
| Actor | Merchant (owner), auto (executor), ops (monitor), supplier (visibility) |
| Entry points | 1) after invoice issuance: "Pay now / Schedule on due date / Pay in instalments"; 2) invoice detail: re-schedule, cancel schedule; 3) khata settle & tab settle reuse the same engine (◻ phase 2) |
| Object | `ScheduledPayment(id, invoiceRef, payerId, payeeId, amount OR instalments[n], frequency ONCE/WEEKLY/MONTHLY, firstRunDate, nextRunDate, remainingInstalments, status, idempotencyKey(run), retries, createdAt/cancelledBy)` |
| States | `SCHEDULED → EXECUTING → PAID \| (FAILED → RETRYING ≤3 → FAILED) \| DONE(instalments) \| CANCELLED`; multi-instalment: invoice `PARTIALLY_PAID` between runs |
| Execution tick | executor scans `next_run <= today` (simulated clock in UAT: admin sim-controls can advance the day); per run: lock payer balance account (pessimistic, cluster-safe), **hold-first** → same Pay-Now flow (never overdraft) → bank SIMULATED call → PAID releases hold & posts; on insufficient balance/FAIL → FAILED, retry tomorrow ×3, then ops incident |
| Idempotency | unique `idempotencyKey = sched:{id}:{runNo}`; rerun-safe (same standard as TC-RACE-03) |
| API | `POST /api/merchant/invoices/{id}/schedule` (frequency, instalments, start), `GET /api/merchant/scheduled-payments`, `POST .../{id}/cancel`, supplier mirror `GET /api/supplier/scheduled-receipts` |
| Screens | merchant **"Scheduled payments"** page (list + cancel/re-schedule + next run), supplier **"Expected receipts"** card in invoices view, ops monitor tile |
| Invariants | ≤ available balance per run; ledger mirror intact after every run (assertReconciliationClean); every run audited SIMULATED; three-ledger posting per instalment |
| Acceptance | UC-SCHED-1..6 tests (§14); manual pass via sim-controls clock |

UC-SCHED-1..6 sketch:
1. Schedule single payment on due date → executor runs → invoice PAID, ledger clean ✅🆕
2. Schedule 3 instalments → PARTIALLY_PAID ×2 → PAID; supplier credit = sum = invoice amount, exactly once
3. Insufficient balance on run → FAILED row, hold not taken, 3 retries, ops incident raised
4. Cancel scheduled (before run) → CANCELLED, nothing executes
5. Duplicate executor tick / service restart mid-run → same row executes once (idempotencyKey absorbed)
6. Timeout scenario on run date → PENDING preserved, retry honoured, late confirm absorbed

## 7. 🆕 UC-CHAIN — value-chain onboarding & tiered trade

| UC | Use case | Spec |
|---|---|---|
| UC-CHAIN-1 | Onboard a manufacturer / distributor / dealer | S02 gains **"I am a" = Manufacturer / Distributor / Dealer / Wholesaler (default)**; stored as `supplierType`; ops directory shows tier badge; KYC identical to A2a; portal subtitles/labels adapt ("Manufacturer catalogue") |
| UC-CHAIN-2 | Dealer buys from manufacturer | Generalised B2B order: buyer can be any approved supply-side entity buying from a higher tier (buyerType/buyerId on order+invoice); order → invoice flow reused verbatim |
| UC-CHAIN-3 | Tier invoice + Pay-Now | Dealer's incoming manufacturer invoice and outgoing merchant invoices all render identically (§3) with Pay-Now (§5) and schedulable payment (§6) |
| UC-CHAIN-4 | Dealer sells to merchant | Dealer appears in merchant's supplier directory (typed "DEALER"); stock purchases route as today |
| UC-CHAIN-5 | Chain visibility (ops) | Ops directory groups by tier; recon treats dealer/manufacturer balance accounts exactly like supplier ones (three-ledger) |
| UC-CHAIN-6 | Lender exposure stays receipt-based | Dealer/manufacturer receipts are visible to lenders only under consent, same exclusions (khata, self-transfer, circular, refunds) |

Non-goals: procurement tendering, multi-level pricing matrices, PO matching —
kept in the existing simple catalogue/order/invoice model.

## 8. Use cases — merchant stock & supply (existing ✅)

| UC | Use case | Flow | Evidence |
|---|---|---|---|
| UC-B2B-1 | Browse supplier catalogue → order | merchant picks items+qty → order SUBMITTED | TC-B2B-01 |
| UC-B2B-2 | Supplier issues invoice | one order → one invoice (unique reference, row lock) | TC-RACE-07 |
| UC-B2B-3 | Merchant pays supplier invoice | route BOOK_TRANSFER/EFT; hold → bank → confirm; supplier credited (three-ledger) | TC-B2B-05 |
| UC-B2B-4 | Spend race safety | concurrent invoice payments: exactly as many as affordable; available 0 after | TC-RACE-02 |
| UC-B2B-5 | Timeout & recovery | TIMEOUT keeps PENDING; retry-timeout re-triggers; late confirm absorbed | TC-B2B-06; sim TIMEOUT |
| UC-B2B-6 | Async bank scenarios | `X-Sim-Scenario` parks payment; manual sim confirm; duplicate absorbed | verified live (DUPLICATE run) |
| UC-INV-1 | Inventory add / adjust / alerts | movements, reorder alerts + reorder rules | TC-INV pack |
| UC-INV-2 | Low-stock reorder to supplier | one-click reorder creates order draft | TC-INV-xx |

## 9. Use cases — merchant money management (existing ✅)

| UC | Use case | Essentials | Evidence |
|---|---|---|---|
| UC-BAL-1 | Balance & three-ledger mirror | issuer ledger = subledger, holds visible | TC-BAL-02 |
| UC-BAL-2 | Withdrawal ≤ cap (hold-aware) | cap = limit profile; no overdraft (account lock) | TC-BAL-05, TC-RACE-04 |
| UC-BAL-3 | Held funds not spendable | hold 400 → spendable drops; hold intact under race | TC-BAL-04, TC-RACE-12 |
| UC-BAL-4 | Statements | generate 60-min window statement; download + ops generate | TC-STMT-xx |
| UC-ADV-1 | Consent to lender | merchant grants/withdraws; consent logged; lender sees only consented records | TC-ADV, TC-LEND |
| UC-ADV-2 | Advance assessment & offer | cap rule R3; offer/decline/fund via lender | TC-ADV-02..04 |
| UC-ADV-3 | Advance fund replay-safe | idempotency key; replay absorbed | TC-RACE-03 |
| UC-ADV-4 | 10% daily-receipts sweep | sweep once/day ≤ balance; arrears trigger | TC-ADV-04, TC-RACE-05 |
| UC-ADV-5 | Accept-vs-fund race serialised | both-ACCEPT or first-FUNDED; credit ⟺ FUNDED | TC-RACE-09 |
| UC-KHATA-1..2 | Khata credit book | customer name+phone, CHARGE/PAYMENT, settle, WhatsApp/SMS-style reminder (SIMULATED) | TC-KHATA |
| UC-TAB-1..2 | Tabs (table credit) | OPEN→PAID/OVERDUE; settle + reminder | UC-TAB |
| UC-SET-1 | Merchant settings/devices | profile, MFA devices, short-code/QR | TC-SET-xx |
| 🆕 UC-ACC-8 | Pay-invoice via Pay-Now + schedule | (see §5/§6 — replaces manual-only pay) | UC-SCHED tests |

## 10. Use cases — lender, ops, admin portals (existing ✅)

**Lender (L01–L15):** consents & records view · assess (rule R2) → offer ·
decline · fund (idempotent) · portfolio + snapshots · advances · collections
(10% sweep view) — TC-ADV/TC-LEND families.

**TPTS ops (T01–T20):** approvals directory (merchant/supplier/bank; approve,
reject; independence rule TC-SET-17; KYC review panel per row) ·
reconciliation (run, breaks, resolve; RUN_LOCK serialisation TC-RACE-11) ·
incidents (CRITICAL ack SLA 15-min) · service desk (complaints/disputes
lifecycle) · suspensions (payment/B2B blocked TC-OPS-11) · audit console
(simulated filter) · reports (settlement, fee revenue) · ops payment feed.

**MindLabs admin (X, C, E families):** overview + health · connectors
(test/rotate) · config (tariffs incl. BC p.16 values, limits, business rules
R1–R4 + exclusions, settings, templates) · simulator (scene catalogue E31–46,
bank-sim default scenario controls, inject/clear breaks, synthetic 120-day
history) · **access console X04** (revoke all device sessions → CRITICAL
incident; jti server-side check) · compliance (AML alerts, SAR life-cycle,
DSAR export) · ops hygiene (API clients, deployments + rollback, backups +
restore tests RTO 2h / RPO 0).

## 11. Use cases — onboarding & KYC (✅ per KYC spec)

| UC | Use case | Evidence |
|---|---|---|
| UC-ONB-1 | Public merchant self-registration | M02; phone unique; PENDING |
| UC-ONB-2 | Public supplier self-registration + bank account | S02; PENDING_APPROVAL account |
| UC-ONB-3 | Ops approval records IDENTITY + SANCTIONS + rates risk | TC-KYC-01 |
| UC-ONB-4 | Sanctions FAIL blocks | TC-KYC-03 |
| UC-ONB-5 | Bank account change needs second approver | TC-SET-17, INDEPENDENCE_RULE |
| UC-ONB-6 | Bank change triggers sanctions re-screening | TC-KYC-10 |
| UC-ONB-7 | Ops KYC review panel per entity | TC-KYC-13; browser-verified |
| UC-ONB-8 | Access revocation kills sessions + CRITICAL incident | X04 browser-verified |
| 🆕 UC-ONB-9 | Tiered onboarding (manufacturer/dealer type) | UC-CHAIN-1 |

## 12. Cross-cutting product rules

1. **SIMULATED labelling** on every non-real connector event (banner, audit, chips).
2. **Problem-detail errors** with machine-readable `code` (e.g. `INSUFFICIENT_BALANCE`, `INDEPENDENCE_RULE`, `ADVANCE_STATE`).
3. **DTO-only API** (constructor projections, `open-in-view: false`, no entity leakage; fetch-joins where lazy).
4. **Pessimistic DB locks** on every contested money path + unique constraints as backstop (BUG-R1..R13 closed).
5. **Virtual threads** for bank-sim concurrency; MANUAL scenario preserves inline behaviour.
6. **Audit everything** before/after, actor + role; simulated entries labelled.
7. Portals are role-gated (JWT + jti session check; revocable instantly).

## 13. Non-functional requirements

- NFR-1 Three-ledger rule: issuer net = subledger net; never negative; holds always honoured.
- NFR-2 Exactly-once payments/invoices/refunds/repayments (idempotency + locks).
- NFR-3 Advance cap formula (R3) and 10% sweep; khata excluded from lender feed.
- NFR-4 Machine-readable codes; wire failures never mutate state.
- NFR-5 Recon gate: daily run persists breaks, gaps never vanish (TC-RCN-01).
- NFR-6 Performance: X30 load gate = 20 TPS × 30 min (docs/load-test.py adapted).
- NFR-7 Recovery: backups + restore tests prove RTO 2h / RPO 0.
- NFR-8 Isolation/safety: per-class UAT reset; 141-test pack green (3 skips-by-design).

## 14. Build order for the 🆕 capabilities

1. **UC-ACC-6 Pay-Now** (smallest, unblocks everything): invoice detail + list actions, absorb-duplicate guard, 409 insufficient + schedule affordance.
2. **UC-ACC-7 Scheduler core**: due date on invoice, `scheduled_payments` (Flyway V4), single-run executor + admin clock control, UC-SCHED-1/5/4.
3. **UC-CHAIN-1 tier flag + labels** (supplierType, S02 field, ops badge).
4. **UC-CHAIN-2/4 tiered buyers** (buyerType on order/invoice; dealer as merchant-grade buyer).
5. **UC-SCHED instalments + supplier expected-receipts view** (+ khata/tab engine reuse ◻).
6. Registered-customer profiles ◻ (beyond khata capture).

## 15. Traceability

| Source | Doc |
|---|---|
| Compliance/KYC detail | `docs/KYC-COMPLIANCE-SPEC.md` |
| Test pack & findings | `docs/UAT-BACKEND-TEST-PACK.md` |
| Reference-implementation merges | `docs/MERGE-NOTES.md` |
| Load harness | `docs/load-test.py` |
| UAT test classes | `TUat01..TUat16` (141 tests) |
