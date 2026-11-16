# Plan: Bookkeeping (general ledger) and the charges, fees & taxes engine

Status: **draft for review** · 2026-11-15 · core (not a plugin) · comes before the revenue-share plan and before org-to-org invoicing (inventory D-INV-3)

## 0. Summary

Two core capabilities that every money flow, plugin and partner agreement will rest on:

1. **Bookkeeping**: **one double-entry ledger for all money**, from each merchant's wallet up to the company's balance sheet.
   - **Structure.** The chart of accounts is a tree of **head (header) accounts** that only aggregate and **leaf GL accounts**. Under a leaf sit the **ledger accounts** that hold individual balances: one per org wallet, loan, payable or bank account.
   - **Every money movement is a balanced transfer** between ledger accounts. Nothing is single-sided anywhere: not customer balances, not holds, not pending card funds, not bank mirrors, not loans.
   - **Several sets of books** (one per legal entity) use the same engine. Journals are never edited, periods close, and every business event posts through versioned posting rules.
   - **Today's single-sided ledgers are replaced, not wrapped** (§5.0).

> **Scope rule.** The existing code (§1) is the starting point and a source of requirements, not a limit. Where it gets in the way of a clean double-entry design, it is replaced. Clean start (`_goal.md`) means there is no historical data to carry over.
2. **Charges engine**: one engine for **fees, commissions, penalties, discounts and waivers, levies and taxes**. It is fully configurable without code: who pays, who earns, how much (flat, percentage, tiered, capped, volume-based, scheduled), when, under which conditions, with which tax, at which level of the org tree. It is deterministic and explainable, applies exactly once per event, can be simulated before it goes live, and posts to the GL.

Both are core because the books, fees and taxes must be one source of truth across all orgs and plugins, can never be switched off, and must share transactions with the money movements they describe.

---

## 1. Where the code is today (2026-11-15)

| Fact | Where | Consequence |
|---|---|---|
| Customer-funds ledgers are **single-sided** (one `CREDIT`/`DEBIT` row per owner per event), with idempotency keys | `IssuerBalanceLedgerEntry`, `MerchantSubledgerEntry`, `LedgerService` | **Replaced** by double-entry ledger accounts and transfers (§5.0). Keep their good ideas: idempotency keys, pending → confirmed, issuer reference |
| Owners are only `MERCHANT` and `SUPPLIER` (`OwnerType`), and balance accounts are keyed by them | `BalanceAccount`, `Enums.OwnerType` | Manufacturers, distributors, TPTS and MiNdLABS can't hold balances or be booked. They need org-keyed accounts (also needed for inventory D-INV-3) |
| Fees: one tariff per `code`, `PERCENTAGE` or flat, `externalCost`, `CostBasis` (contracted / provision / unquoted), effective dates, `approvedBy` | `FeeTariff` | A starting point. There are no tiers, caps, minimums, conditions, payer/beneficiary, plan levels, or tax |
| Fees applied once per `(fee_code, reference)`, reversible | `FeeService.applyOnce`, `FeeTransaction` | Keep the exactly-once rule |
| **Fees are recorded but never posted**: no `SubledgerSource.FEE` posting and no balance effect | `FeeService`, `LedgerService` | Fee revenue isn't in any ledger yet. The GL and the charges engine fix this |
| `findTopByCodeOrderByEffectiveFromDesc` picks a **future-dated** tariff before its start date | `FeeService` L44 | Bug. The new engine resolves by event time (`effective_from <= t < effective_to`) |
| Fee callers: card payment `PaymentService` L198 (reversed L281), advance `AdvanceService` L424; preview `FeeService.preview` (hard-coded labels, settings for switching/sponsor provisions) | services | The callers switch to `ChargeEngine` (§9) |
| **No tax anywhere** (VAT number only collected in KYC) | `KycMasterSeed` | Tax is new |
| Lender credit has its own ledger | `LenderCreditLedgerEntry`, `AdvanceService` | Replaced by `LOAN` ledger accounts under a loan-receivable leaf |
| Reconciliation runs, rules and breaks exist | `domain/recon`, `ReconciliationService` | External statement recon (bank, issuer, scheme, lender) reuses them |
| Currency `NAD`, amounts `numeric(18,2)` | all money tables | Multi-currency-ready model, NAD functional currency |
| `Verb.SIMULATE` exists | `Verb.java` | Used for tariff and posting-rule simulation |

---

## 2. Principles

1. **Double entry, always balanced.** Every journal balances per currency (Σ debit = Σ credit). The database enforces it with a deferred constraint, and the service enforces it too.
2. **Append-only.** Posted journals are never updated or deleted; corrections are reversals or adjusting journals. The app's DB role has no `UPDATE`/`DELETE` on posted lines.
3. **Only leaves carry entries.** A head account is never posted to. Its balance is the sum of its subtree, so restructuring the tree never touches entries.
4. **Code never names GL accounts.** Code raises **accounting events** and uses **account keys** (`FEE_INCOME_CARD`). Each book maps keys to leaves. The chart of accounts can change without a release.
5. **One ledger, three levels.** Head GL → leaf GL → ledger account. Per-customer balances are double-entry **ledger accounts** under a leaf GL (e.g. every merchant wallet sits under `2-100-110 Merchant balances`), so a leaf's balance *is* the sum of its ledger accounts. It is equal by construction, not by a reconciliation that can drift. There is never one GL leaf per merchant; dimensions carry the analysis.
6. **Same transaction for money.** A core money movement, its charges and its GL journal commit together. Plugins post asynchronously through the outbox, with idempotency.
7. **Configuration is versioned, approved and simulated.** Tariff plans, tax rates, posting rules and the chart of accounts are versioned and maker-checked, and can be replayed against history before they are published.
8. **Explainable.** Every charge and every journal line can answer *why*: which plan, rule, rate, inputs and version.

---

## 3. Books (whose bookkeeping)

A **book** is one legal entity's set of accounts. It has a functional currency, a fiscal calendar, a chart of accounts and its own journals.

| Book | Entity | Holds |
|---|---|---|
| `TPTS` | the operating company | fee and charge revenue, costs (interchange, switching, sponsor, bank fees), tax payable, receivables and payables with partners, revenue share payable to MiNdLABS, device sales/rental revenue (from inventory), customer-funds control accounts (on- or off-balance-sheet per D-BK-1) |
| `MINDLABS` | the technology partner | revenue share receivable from TPTS and income. A thin book, fed by the revenue-share settlement |
| `SAFEGUARD` *(optional, D-BK-1)* | trust / e-money safeguarding view | customer-funds liability per product vs. safeguarded cash at the issuer bank(s) |

- The model is **multi-book** from day one: `book_id` is on every GL row.
- **Orgs' own books** (a manufacturer's accounting) are **not** core. They stay in the org's accounting system, fed by the Accounting Export plugin. The engine is generic, so a later "Books" plugin could give orgs a book of their own (D-BK-6).
- **Inter-book entries** (TPTS owes MiNdLABS) are posted as mirrored journals in both books, linked by an `intercompany_ref`, and reconciled.

---

## 4. Chart of accounts: head and leaf GLs

### 4.1 Account

| Field | Meaning |
|---|---|
| `book_id`, `code` | Unique per book. Segmented, e.g. `4-100-110`. Immutable once posted to |
| `name`, `description` | Can change (history kept) |
| `kind` | `HEAD` (aggregates only) or `LEAF` (postable) |
| `parent_id` | The head above. Root heads have none. Depth is unlimited (typically 3–5) |
| `class` | `ASSET`, `LIABILITY`, `EQUITY`, `INCOME`, `EXPENSE` (+ `MEMO` for off-balance-sheet) |
| `normal_balance` | `DEBIT` / `CREDIT`, derived from the class; can be overridden for **contra** accounts (accumulated depreciation, fee waivers as contra-income) |
| `leaf_type` *(leaves)* | `STANDARD`, `CONTROL` (has ledger accounts under it; its balance is their sum; only transfers to those ledger accounts post here, never manual journals), `BANK` (reconciled to statements), `SUSPENSE`, `ROUNDING`, `RETAINED_EARNINGS`, `INTERCOMPANY` |
| `currency` | One currency, or `ANY` (multi-currency leaf; balances kept per currency) |
| `dimensions_required` | Which dimensions a line must carry (e.g. `product`, `org`) |
| `allow_manual` | Whether manual journals may post here (control accounts: no) |
| `status`, `effective_from/to` | `ACTIVE`, `BLOCKED` (no new postings), `CLOSED` (zero balance, hidden) |
| `path` | Materialised path (`ltree`), maintained with an `account_closure` table, for fast subtree roll-ups |

**Rules:**
- A `HEAD` cannot have entries. A `LEAF` cannot have children. Turning a leaf with entries into a head is refused (create a new leaf under a new head instead).
- Moving a leaf to another head is allowed. It changes reporting, not entries. Reports can be run "as of tree version N" (`coa_versions`), so prior statements reproduce.
- Every leaf's class must equal its root head's class (no income leaf under an asset head).
- Changing the chart of accounts is maker-checked (`gl.account:submit` / `authorize`).

### 4.2 Dimensions (analysis without exploding the tree)

Each journal line can carry: `org_id` (counter-party org), `org_type`, `product` (card, QR, IPS, advance, device, plugin:<key>), `channel`, `scheme`, `region`, `cost_centre`, `project`, `charge_code`, `partner`. Dimensions are typed and validated (an `org_id` must exist). Balances are kept per account **and** per configurable dimension sets, so "card fee income by distributor network this month" needs no extra leaves.

### 4.3 Starter chart for the TPTS book (illustrative; finance approves the real one)

```
1 ASSETS (head)
  1-100 Cash and bank (head)
    1-100-110 Operating account – Bank A                 LEAF BANK
    1-100-120 Fee collection account – Bank A            LEAF BANK
  1-200 Receivables (head)
    1-200-210 Fees receivable from merchants             LEAF CONTROL (ledger account per org)
    1-200-220 Due from issuer/sponsor bank               LEAF
    1-200-230 Device sales & rental receivable           LEAF CONTROL (invoices)
    1-200-240 Advances receivable                        LEAF CONTROL (ledger account per advance, if on book)
  1-300 Customer funds (head)                            (D-BK-1: on-balance-sheet only if TPTS holds them)
    1-300-310 Safeguarded funds at issuer                LEAF BANK
  1-400 Tax assets (head)
    1-400-410 VAT input                                  LEAF
2 LIABILITIES (head)
  2-100 Customer funds liability (head)
    2-100-110 Merchant balances                          LEAF CONTROL (one WALLET ledger account per merchant)
    2-100-120 Supplier / org balances                    LEAF CONTROL
    2-100-130 Funds in transit (pending confirmation)    LEAF CONTROL
  2-200 Payables (head)
    2-200-210 Revenue share payable – MiNdLABS           LEAF INTERCOMPANY
    2-200-220 Commissions payable – network partners     LEAF CONTROL
    2-200-230 Scheme / switching / sponsor costs payable LEAF
  2-300 Tax liabilities (head)
    2-300-310 VAT output                                 LEAF
    2-300-320 Withholding tax payable                    LEAF
  2-900 Suspense and clearing (head)
    2-900-910 Unallocated receipts suspense              LEAF SUSPENSE
    2-900-920 Rounding differences                       LEAF ROUNDING
3 EQUITY (head)
  3-100-110 Share capital                                LEAF
  3-900-910 Retained earnings                            LEAF RETAINED_EARNINGS
4 INCOME (head)
  4-100 Transaction fee income (head)
    4-100-110 Card service fees                          LEAF  (dims: product, scheme, org)
    4-100-120 QR / IPS fees                              LEAF
    4-100-130 Withdrawal fees                            LEAF
  4-200 Credit income (head)
    4-200-210 Advance service fees                       LEAF
  4-300 Platform income (head)
    4-300-310 Plugin subscriptions                       LEAF  (dim: plugin)
    4-300-320 Device sales                               LEAF
    4-300-330 Device rental                              LEAF
  4-900 Fee waivers and discounts (contra)               LEAF (normal DEBIT)
5 EXPENSES (head)
  5-100 Cost of revenue (head)
    5-100-110 Interchange                                LEAF
    5-100-120 Switching (provision)                      LEAF
    5-100-130 Sponsor markup (provision)                 LEAF
    5-100-140 Acquirer bundled cost                      LEAF
  5-200 Revenue share expense – MiNdLABS                 LEAF   (or contra-income, D-BK-4)
  5-300 Device cost of sales                             LEAF   (from inventory valuation journal)
  5-900 Bad debts / write-offs                           LEAF
```

### 4.4 Account keys

`gl.account_keys(book_id, key, leaf_account_id, dimension_defaults, effective_from/to)`. Code and posting rules only use keys (`FEE_INCOME_CARD`, `MERCHANT_FUNDS_CONTROL`, `VAT_OUTPUT`, `INTERCHANGE_COST`, `ROUNDING`, …). A key can also resolve **by dimension** (e.g. `FEE_INCOME` → `4-100-110` when `product=card`, `4-100-120` when `product=qr`). A key without a mapping in a book fails posting rule validation at publish time, never at run time.

---

## 5. The double-entry ledger: accounts, transfers, journals, balances

### 5.0 Three levels of accounts

```
HEAD GL   2 LIABILITIES › 2-100 Customer funds liability          aggregates only, never posted
  LEAF GL   2-100-110 Merchant balances                            GL leaf; balance = Σ its ledger accounts
    LEDGER  wallet:org/7f3…  (Merchant "Kapana Grill", NAD)        holds one balance; target of postings
    LEDGER  wallet:org/a91…  (Merchant "Oshana Spares", NAD)
  LEAF GL   2-100-130 Funds in transit
    LEDGER  pending:org/7f3…                                       card funds awaiting issuer confirmation
LEAF GL   1-300-310 Safeguarded funds – Bank A
    LEDGER  bank:BANK_A/1234567                                    mirror of the real bank account
LEAF GL   4-100-110 Card service fees                              leaf with no sub-accounts: posted directly
```

- **Ledger account** (`gl.ledger_accounts`): `id, book_id, gl_leaf_id, owner_org_id?, purpose` (`WALLET`, `PENDING`, `HOLD`, `LOAN`, `FEES_RECEIVABLE`, `PAYABLE`, `BANK`, `SUSPENSE`…), `currency`, `normal_balance`, `status` (`ACTIVE`, `FROZEN`, `CLOSED`), `limits` (min balance, overdraft), `version`. It replaces `BalanceAccount` and generalises `OwnerType` to **any org** (manufacturers, distributors, TPTS and MiNdLABS included).
- A leaf GL either has ledger accounts (then it is a **control leaf**, and only postings to its ledger accounts are allowed) or has none (then it is posted directly). The engine treats both the same way: an **entry always targets one ledger account**, and directly posted leaves get one implicit ledger account.
- Opening an org (go-live) opens its ledger accounts from an **account template** per org type (wallet, pending, hold, fees receivable…). The template is configuration, not code.

### 5.0.1 Transfers (the only way money moves)

A **transfer** is a balanced set of entries across ledger accounts, applied atomically:

```
Transfer {
  book_id, idempotency_key, event {type, ref}, posting_date, value_date,
  entries: [ { ledger_account_id, side: DEBIT|CREDIT, amount, currency, dims } … ]   Σ DR = Σ CR per currency
  phase: SINGLE | PENDING | POST_PENDING(ref) | VOID_PENDING(ref)
}
```

- **Two-phase transfers** replace the separate pending/hold mechanisms (`PENDING_CONFIRMATION` sub-ledger rows, `BalanceHold`):
  - `PENDING` reserves the amounts (the debit account's *available* balance drops, its *posted* balance doesn't).
  - `POST_PENDING` posts them, fully or partially (e.g. on issuer confirmation).
  - `VOID_PENDING` releases them (failure, expiry, `expires_at` swept by a job).
  - So a hold, an in-flight card payment and an invoice "funds held" are the same mechanism.
- **Balances per ledger account**: `posted_dr, posted_cr, pending_dr, pending_cr`.
  - `available = posted − pending outflows`, for the account's normal side.
  - A ledger account's **limits** are checked in the same transaction: no overdraft unless allowed, plus `LimitProfile` caps (max balance, daily debit), which become account limits.
- **Linked transfers**: several transfers that must succeed or fail together (payment + fee + tax + commission split) are applied as one unit.
- **Exactly once**: `UNIQUE(book_id, idempotency_key)`; a replay returns the original transfer.
- **Concurrency**: entries are locked in ledger-account-id order. Hot accounts (TPTS fee income, a big merchant) use balance buckets (§8).
- A transfer **is** a journal (§5.1). The journal header is the transfer, the journal lines are its entries with their GL leaf, and there is no second copy.

### 5.0.2 What a card payment looks like

| Step | DR | CR |
|---|---|---|
| Card payment authorised (pending) | `bank-clearing:card` | `pending:merchant` |
| Issuer confirms (post pending) | `pending:merchant` | `wallet:merchant` |
| Card service fee 2.75% + VAT (linked) | `wallet:merchant` | `income:card_fees` / `vat:output` |
| Interchange cost accrued | `expense:interchange` | `payable:scheme` |
| Withdrawal to bank (pending → post) | `wallet:merchant` | `bank:BANK_A` |
| Refund | mirror entries, fee per reversal policy | |

### 5.1 Journal

```
gl.journals:      id, book_id, journal_no (gapless per book+year), type AUTO|MANUAL|REVERSAL|CLOSING|REVALUATION,
                  posting_date, value_date, period_id, source {event_type, ref, rule_id, rule_version},
                  idempotency_key UNIQUE(book_id, key), status DRAFT|SUBMITTED|POSTED|REVERSED,
                  reverses_id, narration, created_by, approved_by, posted_at
gl.journal_lines: id, journal_id, line_no, account_id (LEAF), debit, credit (one non-zero, ≥0),
                  currency, amount_fc, fx_rate, amount_book (functional currency),
                  dimensions jsonb (validated) + indexed columns for the common ones,
                  ledger_account_id, charge_id?, tax_line_id?
```

- **AUTO** journals come from posting rules and post immediately (status `POSTED`).
- **MANUAL** journals: `DRAFT → SUBMITTED → POSTED` by a different person (4-eyes; T3 above a threshold under OA5). They can't touch `CONTROL` leaves.
- **Reversal**: a full mirror journal with `reverses_id`, dated today or in the original period if that period is still open.
- `gl.journal_lines` is partitioned by `posting_date` (monthly).

### 5.2 Balances

- `gl.balances(book, account, period, currency, dim_set_hash)`: opening, debits, credits, closing. Updated in the posting transaction (row lock in a fixed order, as in the inventory stock engine).
- Head balances are **computed** at read time from leaf balances through `account_closure` (cheap: a few thousand accounts).
- A nightly `GlIntegrityJob` rebuilds balances from lines and checks that every journal balances, every period's trial balance nets to zero, every control leaf equals the sum of its ledger accounts, and every ledger account's balance equals its entries. Any difference is a reconciliation break.

### 5.3 Periods and close

- A fiscal calendar per book (default: calendar months; fiscal year start configurable, D-BK-3).
- Period states: `OPEN` → `SOFT_CLOSED` (only `gl.period:authorize` holders may post adjustments) → `CLOSED` (no posting; late items go to the next open period with a reference).
- **Close checklist** (blocks the close): no open recon breaks, no unposted accounting events, no expired pending transfers left unswept, suspense within tolerance, FX revaluation run, tax period computed, revenue-share accrual posted.
- **Year end**: income and expense close to retained earnings with a `CLOSING` journal. Reopening a year is T4.

### 5.4 Multi-currency
Functional currency NAD. Lines keep the transaction currency and amount plus the book amount at the rate on the posting date (`gl.fx_rates`, source and timestamp recorded). Month-end **revaluation** of monetary foreign-currency balances goes to unrealised FX gain/loss. ZAR (1:1 peg) is treated as a currency with a rate, never assumed equal.

---

## 6. Posting rules: from business events to journals

### 6.1 Accounting events

Core services (and plugins through a port) raise typed events. Each event carries amounts, currency, the parties (payer, payee, org ids), product/channel attributes, the charge breakdown and references.

| Event | From |
|---|---|
| `PaymentAuthorised`, `PaymentCaptured`, `PaymentConfirmed`, `PaymentReversed`, `RefundConfirmed` | `PaymentService` |
| `InvoicePaid`, `InvoicePaymentHeld/Released` | `B2BPaymentService` |
| `WithdrawalRequested/Executed/Failed` | `WithdrawalService` |
| `AdvanceDisbursed`, `RepaymentCollected`, `AdvanceWrittenOff` | `AdvanceService` |
| `ChargeApplied`, `ChargeReversed`, `ChargeWaived` | charges engine (§7) |
| `TaxAssessed` | charges engine |
| `SettlementPosted`, `BankStatementLineMatched`, `BankFeeCharged` | settlement / connectors |
| `RevenueRecognised` (via `RevenuePort`, e.g. device sale/rental, plugin subscription) | plugins |
| `InventoryValuation` (COGS for device sales on the TPTS book) | inventory plugin (`costing` pack) |
| `RevenueShareAccrued`, `RevenueShareSettled` | revenue-share plan (next) |

### 6.2 Rule

```yaml
rule: card_payment_confirmed
version: 3
event: PaymentConfirmed
when: "event.method == 'CARD'"               # CEL, see §7.6
book: TPTS
lines:
  - dr: ISSUER_DUE            amount: event.amount                    dims: {product: card}
  - cr: MERCHANT_FUNDS_CONTROL amount: event.amount                   dims: {org: event.merchantOrgId}
  - for_each: event.charges   # charge breakdown from §7
    dr: MERCHANT_FUNDS_CONTROL amount: charge.amount                  dims: {org: event.merchantOrgId}
    cr: charge.incomeKey       amount: charge.net                     dims: {charge_code: charge.code}
    cr: VAT_OUTPUT             amount: charge.tax                     dims: {tax_code: charge.taxCode}
  - for_each: event.costs
    dr: cost.expenseKey        amount: cost.amount
    cr: COSTS_PAYABLE          amount: cost.amount
```

- Rules are data (stored in `gl.posting_rules`, versioned and effective-dated), authored as YAML in the repo and published through the admin API with maker-checker. **Simulation** (`gl.posting_rule:simulate`) replays a window of historical events through a draft rule and shows the journals it *would* produce and the balance deltas.
- At publish time the engine validates: every key resolves in the book, every line set balances for any input (checked symbolically and by fuzzing fixtures), required dimensions are present, and no line hits a `CONTROL` leaf it doesn't own.
- There is exactly one active rule per `(event, when-branch, book)`. Overlap is a publish error. An event with no matching rule goes to **suspense** and raises a break; it is never silently dropped.

### 6.3 What used to be sub-ledgers is now ledger accounts

| Today (single-sided) | Double-entry replacement | Reconciliation that remains |
|---|---|---|
| `MerchantSubledgerEntry` CONFIRMED | `WALLET` ledger accounts under `2-100-110` | none needed internally (equal by construction); the integrity job re-proves it |
| `MerchantSubledgerEntry` PENDING_CONFIRMATION | two-phase transfers on `PENDING` accounts under `2-100-130` | pending older than the SLA → break |
| `BalanceHold` | two-phase transfers (`PENDING` with `expires_at`) | expired holds swept |
| `IssuerBalanceLedgerEntry` | `BANK` ledger accounts (mirror of each real account) under `1-300-310` / `1-200-220` | **external**: the bank-mirror account vs the bank statement (existing recon, now line by line) |
| `LenderCreditLedgerEntry` | `LOAN` ledger accounts per advance under `1-200-240` (or `MEMO` if off-book) | vs the lender's statement |
| `FeeTransaction` | charge lines + their transfers into income/receivable accounts | none internally |
| Revenue share (next plan) | `PAYABLE` account to MiNdLABS under `2-200-210` | vs the settlement statement |

Internal reconciliation disappears as a *control*, because the GL can't disagree with itself. What remains is **external** reconciliation (bank, issuer, scheme, lender statements vs the mirror accounts) and the integrity job.

### 6.4 Reports
Trial balance (by head roll-up, any tree version), balance sheet, P&L, cash-flow (indirect, from account classes and tags), GL detail per account with drill-down to the source event, dimension pivots, ledger account statements (any org wallet, loan or payable), external statement recon, journal register, audit trail of configuration changes. Every report exports (`gl.report:export`).

---

## 7. Charges engine: fees, commissions, penalties, discounts, taxes

### 7.1 Concepts

| Concept | What it is |
|---|---|
| **Charge type** | A reusable definition: `code` (`card_service`, `withdrawal_fee`, `late_repayment_penalty`, `device_rental`, `plugin_inventory_subscription`, `distributor_commission`, `vat_standard`), `kind` (`FEE`, `COMMISSION`, `PENALTY`, `DISCOUNT`, `LEVY`, `TAX`, `COST`), `basis` (`CONTRACTED`, `PROVISION`, `UNQUOTED`; carries today's `CostBasis` labels), **payer role**, **beneficiary role(s)**, account keys, tax category, collection method, reversal policy, rounding |
| **Trigger** | When it is evaluated: on an **event** (`PaymentConfirmed`, `WithdrawalExecuted`, `RepaymentOverdue`…), on a **schedule** (monthly, per period, per unit per day), or **manual** (ad-hoc charge, maker-checked) |
| **Pricing model** | How much (§7.2) |
| **Condition** | Whether it applies (§7.3) |
| **Tariff plan** | A named, versioned bundle of `charge type + condition + pricing model` lines (e.g. "Merchant Standard 2027", "Manufacturer X network – negotiated") |
| **Plan assignment** | Which orgs a plan applies to, at which level of the tree, from when (§7.4) |
| **Charge line** | One computed result: amount, tax, payer, beneficiaries, explanation, status |

### 7.2 Pricing models (structured; no free code)

| Model | Parameters | Example |
|---|---|---|
| `FLAT` | amount | N$5.00 per withdrawal |
| `PERCENT` | rate, base field (`amount`, `net`, `principal`, `outstanding`) | 2.75% of card amount |
| `PERCENT_PLUS_FLAT` | rate, flat | 1.5% + N$0.50 |
| `TIERED_SLAB` | bands `[from, to, rate|flat]`; each band charges its own slice | 0–1 000: 3%, 1 000–10 000: 2% … |
| `TIERED_VOLUME` (all-units) | bands; the band reached sets the rate for the whole base | monthly volume > N$500k → 1.8% on everything |
| `PER_UNIT` | price per unit, unit field | N$150 per terminal per month |
| `TIME_BASED` | rate per day/month, on outstanding | penalty 0.05%/day on arrears |
| `LOOKUP` | a matrix keyed by attributes (scheme × card type × domestic/foreign) | interchange cost table |
| `FORMULA` *(restricted)* | a CEL arithmetic expression over whitelisted inputs, for the rare case none fits; needs an extra approval | |

Modifiers on any model: **minimum**, **maximum (cap)**, **per-period cap** (max N$x per org per month, tracked in `chg.accumulators`), **free allowance** (first n withdrawals free per month), **rounding** (mode and increment per charge; residue to `ROUNDING`), **proration** (scheduled charges for partial periods).

Volume tiers and caps read **accumulators** (`chg.accumulators(org, charge_code, period, metric)` → count, sum), which are updated in the same transaction as the charge, so concurrent payments can't both slip under a cap.

### 7.3 Conditions

A charge line applies when its condition is true. Conditions are CEL expressions over a fixed, typed **context**:
`event.*` (amount, currency, method, scheme, card type, domestic, mcc, channel, time), `payer.*` / `payee.*` (org type, org id, tier, risk rating, country, VAT-registered, tags, days since go-live), `network.*` (manufacturer root, distributor), `accumulator(code, metric)`, `calendar.*` (weekday, holiday).

Examples:
- `event.method == 'CARD' && !event.domestic`
- `payee.orgType == 'MERCHANT' && payee.daysLive < 90` (fee holiday)
- `accumulator('withdrawal_fee','count') >= 3`

### 7.4 Plan assignment and precedence (fits the org tree)

Plans are assigned at levels, most specific wins:

```
  PLATFORM default
    └─ ORG TYPE (all MERCHANTs)
        └─ SUBTREE (Manufacturer X and everything below it: its negotiated network pricing)
            └─ ORG (one org's contract)
                └─ ORG + PRODUCT/CHANNEL
```

- Resolution, per charge code: walk from the most specific assignment up. The first plan that has a line for this charge code **and** whose condition matches wins. A plan line may say `INHERIT` (use the parent's) or `EXCLUDE` (charge not applicable here).
- Subtree assignment uses `org_closure`, so orgs onboarded later inherit network pricing automatically (the same pattern as plugin grants).
- **Promotions and waivers** are short-lived assignments with an expiry, a reason and approval. They sit above the org level, and they are reported as contra-income (`4-900`), not as lower fees, so the cost of discounts is visible.
- **Floors and ceilings**: a platform-level guard per charge code (e.g. card fee never below cost + x bps). A negotiated plan breaking it is refused at approval.
- Who may assign: TPTS pricing roles for everything. A manufacturer may set **its network's** pricing for charges it is the beneficiary of (e.g. its distributor commission). It can never change TPTS fees (D-BK-5).

### 7.5 Taxes

| Concept | Detail |
|---|---|
| **Tax code** | `VAT_STD` (15%), `VAT_ZERO`, `VAT_EXEMPT`, `WHT_SERVICES`, … with jurisdiction (`NA` first), rates effective-dated, account keys (output, input, payable) |
| **Tax category on the charge type** | Each charge type declares its category (e.g. `FINANCIAL_SERVICE`, `SOFTWARE_SUBSCRIPTION`, `GOODS`, `RENTAL`). A **tax determination table** maps `(supplier entity registration, category, buyer status, place of supply, date)` → tax code. Whether a payment fee is exempt financial service or taxable is a **compliance/tax adviser decision per charge type** (D-BK-2), configured, not coded. |
| **Inclusive / exclusive** | Per charge type: tax added on top, or extracted from the price |
| **Ordering** | Tax on tax and levy-before-VAT orders are explicit (`compound_order`) |
| **Withholding** | Deducted from a payout and booked to `WHT payable` with a certificate reference |
| **Documents** | Tax invoices and credit notes for charges billed to VAT-registered orgs, gapless numbering per book, required fields (TIN, VAT no. from KYC) |
| **Returns** | A per-period VAT report (output, input, adjustments) from GL tax lines, reconciled to the tax control leaves |

Tax is computed by the same engine, as charge lines of `kind=TAX` linked to the charge they tax (`taxes_charge_id`), so explanation, reversal and posting work identically.

### 7.6 Calculation pipeline (deterministic, explainable)

```
event ─▶ build context ─▶ resolve plans (§7.4) ─▶ for each applicable charge type:
          condition (CEL) ─▶ pricing model ─▶ modifiers (min/max/caps/allowances, accumulators)
          ─▶ rounding ─▶ taxes (§7.5) ─▶ beneficiary splits ─▶ charge lines + explanation
       ─▶ collection: DEDUCT (net from the payee's settlement) | DEBIT (separate debit of balance)
                      | INVOICE (accrue to receivable, bill per period) | ACCRUE (cost to us)
       ─▶ ChargeApplied events ─▶ posting rules (§6) ─▶ GL
```

- **Expression language**: CEL (`dev.cel:cel` / cel-java). It isn't Turing-complete, it is side-effect free, its cost can be bounded and its types checked at publish time. Conditions and the restricted `FORMULA` model are the only places expressions appear.
- **Precision**: compute in `BigDecimal` at scale 8, round once per charge line to the currency's minor unit, using the charge's rounding mode.
- **Exactly once**: `UNIQUE(charge_code, source_event_id, payer)` (the current `(fee_code, reference)` rule, generalised).
- **Quote = apply**: `POST /api/charges/quote` runs the identical pipeline without persisting it, and returns a `quote_hash`. Apply with that hash refuses if the plan version changed (`QUOTE_STALE`). This replaces today's `FeeService.preview`.
- **Explanation** stored with every line: plan id and version, assignment level, condition text, model, inputs, accumulator values, rounding, tax determination row. `GET /api/charges/{id}/explain`.
- **Reversals**: by policy per charge type: `FULL` (refund reverses the fee), `PROPORTIONAL` (partial refund), `NONE` (fee kept), `NON_REFUNDABLE_AFTER(days)`. Accumulators are decremented on reversal.

### 7.7 Splits (where revenue share and commissions plug in)

A charge can have several **beneficiaries** with split rules (percentage of net, flat, remainder): e.g. card fee → TPTS 100% *gross*, and then the revenue-share agreement (next plan) splits TPTS's net income with MiNdLABS, and a network commission rule gives the distributor 10 bps. Splits produce linked charge lines and post to payables (`2-200-220`, `2-200-210`), so commission and revenue-share balances are ordinary ledger accounts in the same double-entry ledger.

### 7.8 Governance
- Tariff plans, tax codes and rates, determination tables, posting rules and the chart of accounts are all **versioned documents**: `DRAFT → SUBMITTED → APPROVED → PUBLISHED (effective_from)`, with a different approver (T3; T4 for platform-wide tariffs and tax rates). A published version is never edited.
- **Simulation before publish** (`chg.plan:simulate`): replay N days of real events through the draft. It shows the per-org revenue delta, the orgs whose charges move by more than x%, floor breaches and tax impact.
- **Effective dating** by event time, never by "latest row" (fixes today's bug).
- **Org notice**: a pricing change affecting an org can require a notice period (`notice_days`). Publish refuses an effective date sooner than that, and comms sends the notice.

---

## 8. Architecture

- **Where**: in core, as two packages, `com.mipay.sme.books` (GL) and `com.mipay.sme.charges`, with their own schemas **`gl`** and **`chg`** on the core DataSource. They are separate schemas for clarity, grants and backup, but use the **same transaction manager**, because money, charges and journals must commit together (unlike plugins, which deliberately don't).
- **Boundary** (ArchUnit): only `books.api` (`PostingService`, `AccountingEventPublisher`, read APIs) and `charges.api` (`ChargeEngine.quote/apply/reverse`, `ChargeQuery`) are visible to other core packages. Plugins reach them only through `PlatformContext` ports (`RevenuePort`, `ChargeQuotePort`), asynchronously via the outbox, with idempotency.
- **Throughput**: postings are small (2–10 lines). The target is p95 < 20 ms added to a payment. Balance hot-spots (one control account touched by every payment) use **balance buckets**: N sub-rows per hot account picked by hash, summed on read, so concurrent payments don't serialise on one row.
- **Audit**: every configuration change and manual journal goes to `AuditService`. The journals themselves are the audit trail of money.

### 8.1 RBAC cells (fixed verbs)

| Resource | Verbs |
|---|---|
| `gl.book`, `gl.account` | read, create, modify, submit, authorize |
| `gl.journal` | read, create, submit, authorize, export |
| `gl.posting_rule` | read, create, modify, submit, authorize, simulate |
| `gl.period` | read, execute (soft close), authorize (close / reopen) |
| `gl.report` | read, export |
| `chg.charge_type`, `chg.plan`, `chg.assignment` | read, create, modify, submit, authorize, simulate |
| `chg.tax` (codes, rates, determination) | read, create, modify, submit, authorize |
| `chg.charge` (lines, explain, manual charge, waiver) | read, create, submit, authorize, export |

Roles: `finance_viewer`, `accountant` (manual journals, recon), `finance_controller` (approve journals, close periods), `pricing_analyst` (draft and simulate plans), `pricing_approver`, `tax_officer`. SoD: create/submit and authorize are never in one role for journals, plans, tax and periods.

### 8.2 UI (registered widgets)
Chart-of-accounts tree editor (drag a leaf between heads, live trial balance per node) · journal entry with balance indicator · trial balance / P&L / balance sheet with drill-down · control-account recon board · period close checklist · tariff plan editor with a **simulator panel** · plan assignment view on the org tree ("what does org X pay and why") · tax code admin · charge explain drawer (opens from any payment, invoice or statement line). These screens, and the whole payment / banking / reconciliation UI (quote-before-confirm, statements, tax invoices, connectors, recon workbench and reports), are drawn in [`payments-banking-ui.md`](payments-banking-ui.md).

---

## 9. Integration with existing code

| Existing | Change |
|---|---|
| `FeeTariff`, `FeeTransaction`, `FeeTariffRepository`, `FeeTransactionRepository` | Replaced by `chg.charge_types`, `chg.plans/plan_lines/assignments` and `chg.charge_lines`. Clean start: the two tariffs (`card_service`, `advance_service`) and the switching/sponsor provisions (from `SecuritySetting` keys `switching_fee_provision`, `sponsor_markup_unquoted`) are reseeded as a "Platform default" plan with `basis` labels and cost lines |
| `FeeService` (`applyOnce`, `reverse`, `preview`) | Deleted. Callers use `ChargeEngine` |
| `PaymentService` L198 / L281 | `chargeEngine.apply(PaymentConfirmed)` / `reverse(refund or failure)`, in the same transaction, then `accountingEvents.publish(...)` |
| `AdvanceService` L424 | `chargeEngine.apply(AdvanceDisbursed)` |
| `LedgerService` (`account`, `postConfirmed`, `postPending`, `confirmPending`, `reversePending`, `postIssuerOnly`, `createHold`/`convertHold`/`releaseHold`/`expireHold`, balance views) | **Rewritten** on the transfer API: `postConfirmed` → SINGLE transfer; `postPending`/`confirmPending`/`reversePending` → PENDING / POST_PENDING / VOID_PENDING; holds → PENDING with expiry; `availableFor` / `balanceView` → ledger account balances. Callers (`PaymentService`, `B2BPaymentService`, `WithdrawalService`, `AdvanceService`) switch to accounting events + posting rules |
| `IssuerBalanceLedgerEntry`, `MerchantSubledgerEntry`, `BalanceHold`, `BalanceAccount`, `AccountMapping`, `LenderCreditLedgerEntry` and their repositories; enums `OwnerType`, `LedgerEntryType`, `SubledgerSource`, `SubledgerStatus`, `HoldStatus` | **Removed.** Replaced by `gl.ledger_accounts`, `gl.journals` / `gl.journal_lines` (transfers) and two-phase state. `AccountMapping` (wallet → issuer account ref, legal holder) becomes attributes of the `BANK` ledger account and the wallet's `safeguarded_at` link |
| `LimitProfile` | Becomes ledger account limits (max balance, daily debit, withdrawal cap), checked inside the transfer; advance cap and arrears trigger stay with credit |
| `ReconciliationService` | Internal control-vs-sub-ledger rules are no longer needed (equal by construction). Rule types: `EXTERNAL_STATEMENT` (bank / issuer / scheme / lender vs mirror accounts), `GL_INTEGRITY`, `PENDING_AGEING`, `SUSPENSE_AGEING`, `TAX_CONTROL`. They reuse runs and breaks; `assertNoOpenBreaks` gates period close |
| `StatementService` / merchant statements | Fee lines come from `chg.charge_lines` with tax and explanation; tax invoices for VAT-registered orgs |
| `Dtos.FeePreview` / fee preview screen | Served by `/api/charges/quote` with the same labels (contracted / provision / unquoted) |
| Inventory plugin (`inventory-manufacturing-plugin.md`) | Its `valuation_entries` and device sale/rental revenue reach the TPTS book via `RevenuePort` + `InventoryValuation` events and posting rules. D-INV-3 (org-to-org invoices) uses the charges engine for invoice taxes |
| Accounting Export plugin | Exports `gl` journals (TPTS book) and orgs' charge/tax documents to external accounting systems |
| Revenue-share plan (next) | Built on §7.7 splits, account keys `REVSHARE_*`, the intercompany journals (§3) and period close |

---

## 10. Data model

- **`gl`**: `books, fiscal_calendars, periods, accounts, account_closure, coa_versions, account_keys, dimensions, dimension_values, journals, journal_lines (partitioned), balances (+ buckets), fx_rates, posting_rules, posting_rule_versions, intercompany_links, recon_links`
- **`chg`**: `charge_types, pricing_models, plans, plan_versions, plan_lines, assignments, promotions, floors, tax_codes, tax_rates, tax_determinations, accumulators, charge_lines, charge_line_splits, charge_explanations, quotes, tax_documents, tax_document_lines, notices`
- **`gl` (ledger level)**: `ledger_accounts, account_templates, ledger_balances (+ buckets), pending_transfers, linked_transfer_groups`
- **Removed from `db/fresh`**: `issuer_balance_ledger_entries`, `merchant_subledger_entries`, `balance_holds`, `balance_accounts`, `account_mappings`, `lender_credit_ledger_entries`, `fee_tariffs`, `fee_transactions` (limit columns move into ledger accounts)

---

## 11. Invariants (each tested)

1. Every posted journal balances per currency; the trial balance of every book and period nets to zero.
2. No posting to a head account, a blocked or closed leaf, a closed period, or (manually) a control account.
3. Posted lines are immutable; corrections are journals.
4. Every money movement in the system is a balanced transfer between ledger accounts. No table outside `gl` holds a balance. A leaf GL balance equals Σ its ledger accounts (structural, re-proved nightly). A ledger account never goes below its limit, including pending outflows.
5. Every accounting event produces exactly one journal per book (idempotency), or a suspense entry plus a break; it is never dropped.
6. Every charge applies exactly once per (charge, event, payer); a reversal restores accumulators.
7. Charge resolution uses only configuration effective at the event time; a future-dated tariff never applies early.
8. A quote and the apply that follows produce identical lines, or the apply is refused.
9. Configuration changes are maker-checked by different people; published versions are immutable.
10. Discounts and waivers are booked as contra-income, never by lowering the recorded fee.
11. A negotiated plan never breaks a platform floor.

## 12. Tests

| Suite | Covers |
|---|---|
| `TUat22GeneralLedgerTests` (TC-GL) | CoA tree rules (head/leaf, class consistency, move a leaf and reproduce old reports by tree version), balanced journals, manual journal 4-eyes, reversal, period soft/hard close, year-end closing, FX revaluation, gapless numbering, integrity job detects a tampered balance |
| `TUat23PostingRulesTests` (TC-PR) | every core event posts through its rule; missing key fails publish; no-match → suspense + break; rule simulation over fixtures; every leaf = Σ its ledger accounts and every transfer balances after a mixed day (payments, refunds, withdrawals, advances) |
| `TUat24ChargesTests` (TC-CHG) | every pricing model incl. slab vs all-units tiers, min/max, monthly cap under concurrency (accumulator), free allowance, plan precedence platform → type → subtree → org → product, later-onboarded org inherits subtree pricing, promotions as contra-income, floors refuse a plan, quote = apply and `QUOTE_STALE`, explanation complete, reversal policies, future-dated tariff not applied early (regression for today's bug) |
| `TUat25TaxTests` (TC-TAX) | inclusive/exclusive, exempt vs standard by determination table, tax on fee posted to VAT output, withholding on payout, tax invoice numbering, VAT report = tax control leaves |
| Regression | TC-RCN-03 (no double fee), TC-ACC-04, card/advance UAT, fee preview labels |
| Load | 200 payments/s on one merchant-funds control account (balance buckets), p95 posting < 20 ms |

## 13. Phases

| Phase | Work | Exit |
|---|---|---|
| B0 | Decisions D-BK-1…7; finance approves the TPTS chart of accounts; tax adviser classifies each charge type | signed |
| B1 | GL core: books, CoA head/leaf + versions, account keys, journals, balances, periods, manual journals, reports, integrity job | TC-GL |
| B2 | Ledger accounts + two-phase transfers; account templates per org type; rewrite `LedgerService` on transfers; remove the single-sided ledger tables; accounting events + posting rules for all core flows; external statement recon | TC-PR, TC-DE (double entry: payment/refund/withdrawal/advance/hold life-cycles, concurrent debits on one wallet, overdraft refused, pending expiry), full regression |
| B3 | Charges engine: charge types, pricing models, plans and assignments on the org tree, accumulators, quote/apply/explain/reverse; replace `FeeService` | TC-CHG, TC-RCN-03 |
| B4 | Taxes: codes, rates, determination, tax documents, VAT report | TC-TAX |
| B5 | Governance UI: CoA editor, plan editor + simulator, close checklist, explain drawer (widgets) | widget tests |
| B6 | Splits + intercompany → **revenue-share plan** builds here; then RevenuePort for plugins (inventory devices, plugin subscriptions) | revenue-share plan |

## 14. Decisions needed

| # | Question | Recommendation |
|---|---|---|
| D-BK-1 | Are customer funds on TPTS's balance sheet (TPTS is the e-money issuer) or held by the sponsor bank (TPTS as agent, funds as memo)? | Ask compliance. Model both: control accounts on-book, or under `MEMO` class |
| D-BK-2 | VAT treatment of each charge type (exempt financial service vs taxable), and whether TPTS is VAT-registered | Tax adviser per charge type; configured in the determination table |
| D-BK-3 | Fiscal year start for each book | Configurable; finance decides |
| D-BK-4 | Revenue share in TPTS's books: an expense or contra-income? | Expense (5-200), so gross revenue stays visible; finance confirms |
| D-BK-5 | May a manufacturer set pricing in its network, and for which charges? | Only charges where it is the beneficiary (commissions, its own service fees); never TPTS fees |
| D-BK-6 | Do orgs get their own books (a "Books" plugin) later? | Not now; Accounting Export first |
| D-BK-7 | Expression language for conditions | CEL (cel-java), typed context, bounded cost |
