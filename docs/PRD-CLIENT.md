# MiPay SME Ecosystem — Product Requirements Document

**Version 1.0 (client issue) · Requirements & use cases only.**

MiPay SME is a mobile-first commerce, payments and credit ecosystem for
Namibian small and medium enterprises. This document is the complete product
specification: actors, capabilities, business rules and use cases. All
amounts are in Namibian Dollars (NAD). The platform's banking, lender and
card interactions run through configured connectors; every non-real (simulated)
event is visibly labelled SIMULATED without exception.

---

## 1. Product vision

Give an informal trader (merchant) a phone as their point of sale, supply
chain and credit engine:

- accept customer payments in a tap, with an auditable digital ledger
- buy stock from their supply chain and pay invoices on time, automatically
- borrow against their proven sales, with safe daily repayment
- offer store credit to trusted customers, and still keep complete records

The platform serves five human roles plus customers: **Merchant**, **Supplier
chain** (manufacturer, distributor, dealer, wholesaler), **Lender**, **TPTS
operations**, **MindLabs platform administration** — and **Customers** who pay
from their personal banks on basic or smart phones.

## 2. The value chain

Goods and invoices flow down the chain; payments flow up:

```
MANUFACTURER ──invoice──▶ DISTRIBUTOR/DEALER ──invoice──▶
        WHOLESALER/SUPPLIER ──invoice──▶ MERCHANT ──sales──▶ CUSTOMERS
```

Every commercial invoice in the chain, at every tier, is a first-class,
payable object (§5–§6). Money movement for every tier posts through the same
ledger architecture; a receiving account at any tier is approved with the
same multi-person controls.

## 3. Onboarding & know-your-customer

### 3.1 Requirements

- O-1 Self-service registration shall be available to merchants and to each
  supply-chain tier from a public page: owner name, ID number, mobile number,
  business name, (regional-format) business type and optional registration
  number. Suppliers additionally provide their receiving bank account.
- O-2 Registration shall never require an app-install or account-creation
  step beyond the form; the record enters PENDING approval.
- O-3 TPTS operations shall review each pending party in a single console,
  with the party's recorded identity and sanctions checks visible per row
  before deciding.
- O-4 Approval shall record KYC checks (IDENTITY, SANCTIONS) with result,
  details, timestamp and acting officer, and shall assign the party a **risk
  rating** (LOW/MEDIUM/HIGH) driving a periodic review date
  (LOW 36 / MEDIUM 24 / HIGH 12 months).
- O-5 A failed or unverified sanctions outcome shall block approval; the
  registration stays REJECTED and the party cannot trade or sign in.
- O-6 Receiving-bank-account registration and **any later change** require
  approval by an officer **different from whoever requested it** (second
  person, evidence-audited).
- O-7 A change to a receiving account is a **trigger event** that re-opens
  sanctions screening for the party.
- O-8 Account (device-session) revocation by an administrator shall take
  effect on the very next request the revoked device makes, and shall raise a
  CRITICAL incident for the 15-minute acknowledgement SLA.
- O-9 Approval shall provision merchants with a balance account, a personal
  QR code and a pay-by-short-code; supply-side parties with an activated
  receiving account.
- O-10 Every onboarding/KYC action is audited with actor, roles, before and
  after values.

### 3.2 Onboarding use cases

| UC | Use case | Actor | Flow & acceptance |
|---|---|---|---|
| UC-ONB-01 | Merchant self-registers | Merchant | Phone/name/ID/registration submitted → PENDING; duplicate phone refused; confirmation received on screen. |
| UC-ONB-02 | Supply-side party self-registers (any tier) | Manufacturer/Distributor/Dealer/Wholesaler | Form + tier selection + bank account → PENDING_APPROVAL receiving account. |
| UC-ONB-03 | Ops approves a merchant | TPTS Ops | Reviews identity + sanctions checks → approve → party VERIFIED, risk-rated, provisioned (balance, QR, short-code). Reject → REJECTED with note. |
| UC-ONB-04 | Sanctions outcome blocks onboarding | TPTS Ops | FAIL/unverified → BLOCKED; KYC REJECTED; approval attempt recorded; no provisioning. Acceptance: no trading and no sign-in possible afterwards. |
| UC-ONB-05 | Bank receiving-account second-person approval | TPTS Ops (2) | Requester ≠ approver enforced; approval activates the account and revokes the superseded one. |
| UC-ONB-06 | Bank change triggers re-screening | System | Approved change records a fresh SANCTIONS check; audit trail shows trigger reason. |
| UC-ONB-07 | KYC review panel | TPTS Ops | Selecting any directory row shows its checks (type, result, details, time) newest-first. Role-scoped to ops. |
| UC-ONB-08 | Risk rating & review cycle | System | Rules: cash-heavy or sanctions-review trade → HIGH (12m); supplier default MEDIUM (24m); standard trade LOW (36m); rating + next-review date stored and audited. |
| UC-ONB-09 | Access revocation | MINDLABS Admin | Select user → revoke → all live sessions die immediately; CRITICAL incident raised; result message names incident reference. |
| UC-ONB-10 | Customer record capture | Merchant | Merchant captures customer name + mobile for credit books (khata/tabs); no customer app onboarding required. |

## 4. Customer payments to merchants

### 4.1 Requirements

- P-1 A customer shall pay a merchant via personal QR, merchant short-code or
  card at a terminal, entering only phone/amount or card details on a basic
  phone; the payer's bank confirms via the bank connector.
- P-2 Payment acceptance shall show **pending until the payer's bank
  confirms**; the subledger must reflect the pending state for card, and the
  money must not be spendable until confirmed.
- P-3 Confirmation shall be **exactly-once**: replayed callbacks and repeated
  events are absorbed and visibly labelled as ignored duplicates; no double
  credit is possible under any concurrency.
- P-4 Each accepted payment shall carry the channel, payer reference, fee
  breakdown and SIMULATED labels where applicable, and shall appear in the
  merchant's day-audit log (speaker log).
- P-5 Fees: card acquiring billed at the contracted 2.50% (external acquirer
  cost 2.20%, contracted basis); switching 0.40 provision, QR sponsorship
  0.10 unquoted — collected exactly once per payment.
- P-6 A confirmed payment may be **refunded** in full or part; refunds never
  exceed confirmed amount and are exactly-once.

### 4.2 Use cases

| UC | Use case | Actor | Flow & acceptance |
|---|---|---|---|
| UC-PAY-01 | Accept QR/short-code payment | Customer/Merchant | Amount entered → INITIATED → bank callback → CONFIRMED, audited, ledger credited; pending until confirmation. |
| UC-PAY-02 | Card payment with fee | Merchant | Card terminal entry; single card_service fee applied at 2.50% with recorded cost basis. |
| UC-PAY-03 | Duplicate callback absorbed | System | Second identical callback ignored, event labelled DUPLICATE_IGNORED, single ledger credit. |
| UC-PAY-04 | Concurrent duplicate race absorbed | System | N simultaneous identical callbacks: exactly one credit; errors free. |
| UC-PAY-05 | Refund | Merchant/Ops | Full/partial refund processed once; amount flows back symmetrically in the ledger. |
| UC-PAY-06 | Speaker-log review | Merchant | Per-event day log with amounts, channel, SIMULATED labels. |
| UC-PAY-07 | Basic-phone payer simulator | Customer | A standalone payer view mirrors the bank confirm journey without login. |

## 5. THE INVOICE (every tier) — and the Pay-Now link

### 5.1 Requirements

- I-1 Every commercial invoice, at **every tier of the chain** (manufacturer
  → distributor/dealer, dealer → merchant, supplier → merchant), shall carry:
  unique invoice number, globally unique payment reference, payer, payee,
  amount, currency (NAD), payment route (BOOK_TRANSFER or EFT; IPP-native is
  excluded at go-live), issue timestamp and **due date** (credit terms).
- I-2 **Every invoice shall have a Pay-Now link.** Clicking it shall
  initiate the payment for that invoice immediately:
  - the system resolves the invoice by its unique reference,
  - validates that an identical initiated payment is not already in flight
    (a repeat click shall visibly show the in-flight/paid state, never a
    second charge),
  - previews the fee, then validates the payer's **available** balance
    (excludes held/committed funds),
  - insufficient balance shall be refused with a machine-readable reason
    (`INSUFFICIENT_BALANCE`) and shall offer scheduling instead, without any
    state change,
  - on success the system **places a hold** on the amount, marks the
    invoice PROCESSING, instructs the bank connector, and completes the
    invoice as PAID on the bank callback — hold released into a posted
    debit, payee credited, all parties notified, single effect guaranteed.
- I-3 The Pay-Now link shall be reachable from the invoice list row, the
  invoice detail, and a shareable reference link; opening the shareable link
  unauthenticated lands on the payer's sign-in and returns to the invoice.
- I-4 A payment that the bank does not resolve in time (TIMEOUT) shall keep
  money **held** (never spendable by the payer) and expose an explicit
  retry action; a late bank confirmation of an already-failed payment shall
  be refused rather than mutate state.
- I-5 An invoice paid once shall be PAID once: double-pay attempts, double
  callbacks and concurrent clicks all resolve to the single payment.
- I-6 Invoices paid by instalment shall surface `PARTIALLY_PAID` between
  payments and PAID only when the sum of paid instalments equals the invoice.

### 5.2 Use cases

| UC | Use case | Actor | Flow & acceptance |
|---|---|---|---|
| UC-INV-01 | Issue invoice for an order | Supply-side party | Order SUBMITTED → issue → exactly one invoice per order (globally unique reference); concurrent issuance attempts yield one invoice. |
| UC-INV-02 | **Pay-Now click** | Invoice payer (any tier) | Click Pay-Now → resolve → fee preview → balance check → hold → bank → PAID; buyer debited once, payee credited once; all three-ledger entries mirror. |
| UC-INV-03 | Pay-Now repeated click | Payer | Second click shows existing payment state; no second charge; single-effect intact. |
| UC-INV-04 | Pay-Now with insufficient balance | Payer | 409 INSUFFICIENT_BALANCE; nothing charged; scheduling offered. |
| UC-INV-05 | Pay-Now shareable link | Payer | Not signed in → sign-in → returns to the invoice; wrong role → refusal with problem code. |
| UC-INV-06 | Bank timeout | System | Payment stays pending/processing; money held; retry action available; late confirm of failed payment refused. |
| UC-INV-07 | Concurrent buy race | System | Multiple concurrent invoice payments: exactly as many succeed as the balance affords; remainder refused; available = 0. |
| UC-INV-08 | Invoice states | System | ISSUED → PROCESSING → PAID; PARTIALLY_PAID under instalments; CANCELLED available; states machine-visible to both parties. |

## 6. Payment scheduler (invoices paid on time, automatically)

### 6.1 Requirements

- S-1 A payer shall be able to **schedule** any invoice payment for its due
  date (single future payment) or **spread it in instalments** (equal parts,
  e.g. 3 × monthly).
- S-2 The scheduler shall present, for each invoice: Pay now · Schedule on
  due date · Pay in instalments; and allow the payer to cancel or re-schedule
  any pending schedule before it runs.
- S-3 Each run shall behave exactly like a Pay-Now payment: validate
  available balance, hold first (never overdraft), instruct the bank
  connector, confirm on callback; each run shall carry a **unique
  idempotency key** so an executor restart or a repeated tick can never
  double-execute.
- S-4 A run whose funds are unavailable shall fail without taking a hold,
  retry up to three further attempts (next attempt no earlier than the next
  day), and raise an ops incident after the final failure.
- S-5 Part-payment via instalments shall keep remaining schedules visible to
  both payer and payee (`PARTIALLY_PAID`), with a cancel that stops future
  runs.
- S-6 The payee shall see **expected receipts** (upcoming scheduled payments)
  for their invoices; ops shall monitor scheduled activity and failures.
- S-7 Every scheduled execution is audited and SIMULATED-labelled like all
  connector events; the daily reconciliation account must remain clean after
  every run.
- S-8 The platform clock control shall be administrable for scenario testing
  (the operator may advance the payment day).

### 6.2 Use cases

| UC | Use case | Actor | Flow & acceptance |
|---|---|---|---|
| UC-SCH-01 | Schedule single payment | Merchant | Invoice → Schedule on due date → status SCHEDULED; visible on the schedule list with next run date. |
| UC-SCH-02 | Scheduler executes on due date | System | Executor tick → hold → bank → PAID; hold released into debit; payee credited; invoice PAID exactly once. |
| UC-SCH-03 | Instalment plan | Merchant | e.g. 3 monthly parts → PARTIALLY_PAID after 2 runs; invoice PAID when third completes; sum of credits equals invoice amount, each instalment posted once. |
| UC-SCH-04 | Failed run retries then escalates | System/Ops | Insufficient funds at run → FAILED (no hold taken); retry next day ×3; final failure raises ops incident; invoice still outstanding. |
| UC-SCH-05 | Cancel / re-schedule | Merchant | Cancel before run → CANCELLED, nothing executes; re-schedule updates next run. |
| UC-SCH-06 | Executor restart safety | System | Same run processed by two ticks or across a restart: idempotency key yields exactly one payment. |
| UC-SCH-07 | Payee visibility | Supply-side party | Expected-receipts list shows upcoming scheduled amounts and dates for their invoices. |
| UC-SCH-08 | Reuse for customer credit books | Merchant | (Phase 2) khata/tab due-date settlement driven by the same scheduler engine. |

## 7. Supply-chain commerce (catalogue → order → invoice → payment)

| UC | Use case | Actor | Flow & acceptance |
|---|---|---|---|
| UC-SUP-01 | Publish catalogue | Supply-side party | Add product/unit/price; retire item; prices visible to buyers. |
| UC-SUP-02 | Place order to a supply-side party | Buyer (merchant or dealer tier) | Select items/qty → order SUBMITTED; history and detail. |
| UC-SUP-03 | Receive orders, issue invoice | Supply-side party | Order → invoice (UC-INV-01); buyer side payable via Pay-Now/UC-SCH. |
| UC-SUP-04 | Tiered buying | Dealer/Distributor | Dealer buys from manufacturer (buyerType at dealer tier): identical order/invoice/payment flow; payments route up the chain. |
| UC-SUP-05 | Receiving accounts | Supply-side party | Register/change/revoke; second-person approval; re-screening on change. |
| UC-SUP-06 | Statements | Supply-side party | Period statements of orders, invoices, payments received. |
| UC-SUP-07 | Directory by tier | TPTS Ops | Approvals and directory grouped/labelled by tier with KYC review panel. |

## 8. Merchant money management

### 8.1 Requirements

- M-1 All merchant money shall be mirrored across three records (issuer
  balance ledger, merchant subledger, holds) that must always agree; holds
  shall never be spendable; balances shall never go negative.
- M-2 Withdrawals shall be limited by a merchant limit profile (max balance,
  daily debit, withdrawal cap) and shall never overdraw.
- M-3 Confirmed receipts, fees, holds, withdrawals, refunds, invoice payments
  and scheduled runs shall all appear in one transaction list with running
  context.
- M-4 Statements shall be generated on request (downloadable within the
  retention window) and by ops on behalf of a party.

### 8.2 Use cases

| UC | Use case | Actor | Flow & acceptance |
|---|---|---|---|
| UC-BAL-01 | View balance & mirror integrity | Merchant | Balance = available + held; ledger mirror verified. |
| UC-BAL-02 | Withdraw to bank | Merchant | Amount ≤ cap and available → withdrawal row; account locked during movement (no overdraft). |
| UC-BAL-03 | Held funds protection | System | Holds excluded from available balance always, including under concurrency. |
| UC-BAL-04 | Concurrent withdrawals | System | Parallel withdrawals never overdraw; total debits ≤ available. |
| UC-BAL-05 | Statements | Merchant/Ops | Generate/select/download. |

## 9. Inventory, khata & tabs (merchant tools)

| UC | Use case | Actor | Flow & acceptance |
|---|---|---|---|
| UC-TOOL-01 | Inventory tracking | Merchant | Items, units, movements (in/out/adjust), stock levels. |
| UC-TOOL-02 | Reorder alerts & rules | Merchant | Reorder level triggers alert; reorder rule → draft stock order to supply side. |
| UC-TOOL-03 | Khata (credit book) | Merchant | Customer name+phone; CHARGE/PAYMENT entries with optional due date; balances; settle; customer reminder message (SIMULATED send). |
| UC-TOOL-04 | Tabs | Merchant | Named customer open tab, entries, due dates, PAID/OVERDUE, settle + reminder. |
| UC-TOOL-05 | Credit-book money rules | System | Khata balances excluded from lender records and from any platform exposure; reminders never send real messages from sandbox. |

## 10. Credit (cash advance on receipts)

### 10.1 Requirements

- C-1 The merchant shall grant/withdraw consent to a lender; only consented
  records are viewable; withdrawal stops future sharing.
- C-2 Lender shall assess eligibility on 90-day verified receipts; business
  rules: active+approved, trading days ≥ 30, and the advance cap shall be
  **min(N$25,000 · 20% of average monthly receipts · lender limit)**.
- C-3 Offer lifecycle: lender issues offer → merchant accepts → lender funds;
  acceptance and funding must each happen exactly once regardless of retries
  or races; funding without acceptance is refused; repayment credits must
  exist exactly when an advance is funded.
- C-4 Repayment via **daily sweep of 10% of confirmed net receipts**
  (excluding advance proceeds, self-transfers, circular supplier payments and
  refunds), executed once per day; arrears trigger at profile threshold.
- C-5 Khata (basic-phone credit) is never included in the lender feed.

### 10.2 Use cases

| UC | Use case | Actor | Acceptance |
|---|---|---|---|
| UC-CRD-01 | Consent grant/withdraw | Merchant/Lender | Recorded; lender view scoped to consent. |
| UC-CRD-02 | Assess & offer | Lender | Rules applied; deterministic cap; merchant sees offer; decline path. |
| UC-CRD-03 | Accept exactly-once | Merchant | Concurrent/repeated accepts → single ACCEPTED; terminal states honoured. |
| UC-CRD-04 | Fund exactly-once | Lender | Idempotent funding; replay absorbed; credit exists ⟺ FUNDED. |
| UC-CRD-05 | Daily sweep | System | Once per day; correct 10% math; exclusions honoured; arrears flagged. |
| UC-CRD-06 | Portfolio view | Lender | Advances, repayments, collections visible through their ledger. |

## 11. Operations console (TPTS)

### 11.1 Requirements

- T-1 The operator shall manage onboarding queues and the full directory
  (tier-labelled), with KYC review per party (§3).
- T-2 The operator shall run **daily reconciliation** between the issuer
  ledger and platform subledgers; every mismatch shall surface as a break
  (type, amount, reference); concurrent runs must not lose or duplicate
  breaks; breaks resolve with audit.
- T-3 Incidents with severity (CRITICAL ack SLA 15 min), complaints and
  disputes with assignment and resolution.
- T-4 Suspension of a merchant or supplier shall immediately block payments
  and B2B movements; lift restores.
- T-5 Full audit console filterable to simulated events; settlement and fee
  revenue reports per day.

### 11.2 Use cases

| UC | Use case | Acceptance |
|---|---|---|
| UC-OPS-01 Approve/reject registrations & bank changes | second-person rule, KYC panel (UC-ONB-03..07) |
| UC-OPS-02 Recon run with injected gap | break persisted per gap, deduplicated, run locked; resolve audited |
| UC-OPS-03 Incident life-cycle | create/acknowledge/resolve; CRITICAL surfaced from revocation & scheduler failures |
| UC-OPS-04 Complaints & disputes | record, assign, resolve (chargeback won/lost) |
| UC-OPS-05 Suspend / lift | suspension immediately blocks transacting; audit |
| UC-OPS-06 Audit & reports | simulated-only filter; settlement & fee reports |

## 12. Platform administration (MindLabs)

### 12.1 Requirements

- X-1 Connector registry (issuer, card acquirer, B2B) with health test and
  credential rotation.
- X-2 Configuration: fee tariffs (basis: quoted / unquoted / provision with
  external cost), merchant limit profiles, business rules (enable/disable),
  platform settings, notification templates — every change audited before/after.
- X-3 A simulation layer listing the labelled scenarios (happy path, duplicate
  callback, timeout pending preserved, insufficient-funds refusal, recon
  break, advance fund+sweep, refund) plus **bank-sim controls** (default bank
  outcome per event) and the ability to inject/clear reconciliation test
  breaks and generate synthetic 120-day history — all disabled in production
  mode.
- X-4 Access console X04 (UC-ONB-09).
- X-5 Compliance tooling: AML monitoring alerts (velocity, self-transfer,
  circular patterns), SAR draft/submit, DSAR data export.
- X-6 Go-live hygiene: API client registry with rate limits, deployment
  records with rollback, backups and restore tests proving **RTO 2 h,
  RPO 0**.

### 12.2 Use cases

| UC | Use case | Acceptance |
|---|---|---|
| UC-ADM-01 Connector test & rotate | health + rotated secret audited |
| UC-ADM-02 Tariff/limit/rule change | audited change; fee math follows contract values |
| UC-ADM-03 Scenario library & sim controls | scenes run end-to-end; breaks injectable/clearable; default bank outcome selectable |
| UC-ADM-04 AML alert → SAR → DSAR | monitoring → SAR draft/submit; DSAR export minimised to the subject's data |
| UC-ADM-05 Deploy/rollback & backup/restore | records audited; restore tests captured with RTO/RPO achieved |

## 13. Non-functional requirements (client commitments)

- N-1 **Money integrity**: three-ledger mirror; holds honoured; no overdraft
  (N$0 tolerance); exactly-once effects under any concurrency.
- N-2 **Deterministic business rules**: advance cap formula (§10), fee
  schedule (§4), suspension gating — applied identically however flows are
  triggered.
- N-3 **Fail-safe external calls**: no external timeout/failure ever mutates a
  ledger; unresolved payments stay held and recoverable; late/duplicate
  confirmations refused or absorbed.
- N-4 **Traceability**: every state-changing action audited (actor, roles,
  before/after, simulated labels); reconciliation must never silently lose a
  mismatch.
- N-5 **Security & access**: role-scoped APIs, device sessions server-side
  revocable, secret rotation, second-person controls on sensitive flows.
- N-6 **Performance & scale**: platform sustains the contracted load profile
  (20 TPS sustained, 30 minutes, error-free).
- N-7 **Resilience**: RTO 2 h / RPO 0 with regularly rehearsed restore tests.
- N-8 **Clarity of errors**: machine-readable problem codes on every refusal
  (e.g. INSUFFICIENT_BALANCE, INDEPENDENCE_RULE, ADVANCE_STATE).

## 14. Glossary

| Term | Meaning |
|---|---|
| Pay-Now link | One-click payment initiation present on **every invoice, every tier** (§5) |
| Payment scheduler | Due-date and instalment execution engine for invoices (§6) |
| Hold | Reserved-but-not-posted funds; excluded from available balance until resolved |
| Three-ledger | Issuer bank mirror ↔ platform subledger ↔ merchant/party ledger, always reconciled |
| Khata / Tab | Merchant's customer credit book / running table credit with due dates |
| Sweep | Daily automatic repayment collection (10% of confirmed net receipts) |
| Second-person rule | Approver must differ from requester on sensitive flows |
| Tier | Supply-chain level: manufacturer / distributor / dealer / wholesaler |
| SIMULATED label | Visible marking of every connector event not produced by a live provider |

## 15. In scope vs excluded at go-live

- **In:** everything in §1–§13 (B2B payment routes BOOK_TRANSFER and EFT).
- **Out at go-live:** IPP-native (book-transfer-instant) route; agent/cash
  point onboarding; customer-side bank KYC (payer's bank owns it); lending
  beyond the receipts-based advance (no collateralised lending).
