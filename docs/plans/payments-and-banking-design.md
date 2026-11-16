# Plan: Payments and banking: moving real money safely

Status: **draft for review** · 2026-11-15 · core · builds on [`bookkeeping-and-charges-design.md`](bookkeeping-and-charges-design.md) (double-entry ledger, two-phase transfers, charges) and [`fapi2-design.md`](fapi2-design.md) (gateway, mTLS, FAPI 2.0)

## 0. Summary

How MiPay turns a business intent ("customer pays merchant", "merchant pays supplier", "merchant withdraws", "TPTS pays MiNdLABS its share") into **real money moving between real bank accounts**, with these guarantees:

- **Never lost:** every movement is in the double-entry ledger before any instruction leaves.
- **Never doubled:** idempotency at every hop, from the API, through the ledger and the instruction, to the bank's own end-to-end id.
- **Never unaccounted:** every bank line is reconciled to the ledger, automatically where possible and in a controlled manual workbench where not.
- **Never moved by one person or one compromised component:** instructions are signed, payouts need approvals, and bank credentials sit in a separate hardened service.

The design has four parts: the **payment orchestrator** (core), the **bank gateway** (a separate, isolated service that is the only thing that talks to banks), **rail adapters** (one per bank and protocol), and the **reconciliation engine** (automatic matching plus a manual exceptions workbench).

> **Scope rule.** As with bookkeeping: today's code is a starting point, not a limit. All money is still **simulated** today. This plan is what makes it real.

---

## 1. Where the code is today (2026-11-15)

| Fact | Where | Consequence |
|---|---|---|
| Collection channels `QR`, `SHORT_CODE`, `CARD`; payment states `INITIATED → PENDING → CONFIRMED / FAILED / REFUNDED`; idempotency key per payment | `Payment`, `Enums.PaymentChannel/PaymentStatus`, `PaymentService.accept/confirm/fail/refund` | The channels stay; the state machine grows (§5.2) |
| Bank behaviour is simulated: `BankSimulator` scenarios `MANUAL, CONFIRM, DUPLICATE, TIMEOUT, LATE_CONFIRM, FAIL` on virtual threads; `X-Sim-Scenario`; everything labelled SIMULATED | `BankSimulator`, `SimulationService` | Becomes the **SIM rail adapter** behind the same interface as real banks (§11) |
| B2B invoice payment routes `BOOK_TRANSFER`, `EFT`; states `HELD → PROCESSING → CONFIRMED / FAILED / TIMED_OUT`, with retry | `B2BPaymentService`, `InvoicePayment` | Becomes a payment order on the orchestrator |
| Withdrawals `REQUESTED → APPROVED → PAID / FAILED` with a bank reference | `WithdrawalService`, `Withdrawal` | Becomes a payout order (beneficiary checks, approvals, rail choice) |
| Connectors: types `BANK_ISSUER, LENDER, IPS, CARD, QR, KYC, NOTIFICATION`, auth `OAUTH2, API_KEY, MTLS`, `credentialsRef`, capabilities, health check, credential rotation | `Connector`, `ConnectorService` | The registry model is kept and moves into the bank gateway. `API_KEY` is not allowed for money rails |
| Merchant balance account → issuer account ref + legal holder | `AccountMapping`, `BalanceAccount` | Becomes `BANK`/`WALLET` ledger account attributes (bookkeeping §5.0) |
| Reconciliation compares issuer net vs subledger net per owner and expires stale holds; break types `MISSING_CREDIT, DUPLICATE_DEBIT, STALE_HOLD, ORPHAN_PAYMENT`; open breaks block release | `ReconciliationService`, `domain/recon`, `OpsController /recon/*` | Replaced by statement-level matching against real bank data (§9); "breaks block release" is kept |
| Settlements: one row per date/cycle/total | `Settlement` | Becomes settlement batches matched line by line |
| Provider callbacks are signature-verified and deduplicated (KYC) | `KycController` L169 | The same pattern, made stronger, for bank callbacks |
| Gateway, mTLS mesh, FAPI 2.0, DPoP, step-up, RBAC enforcing | trustplane, `fapi2-design.md` | Reused: the bank gateway is a new mesh member |

---

## 2. Principles

1. **Ledger first.** No instruction leaves for a bank unless the ledger has already reserved the money (a pending transfer). The bank's answer posts or voids that reservation. The ledger is the record of *intent and state*; the bank statement is the record of *external truth*. Reconciliation proves the two agree.
2. **Exactly once, end to end.** The API idempotency key, the ledger transfer key, the instruction id and the bank's end-to-end id (`EndToEndId` / `UETR`) are all bound together. An `UNKNOWN` outcome is **never retried blindly**; it is resolved by a status enquiry with the same id.
3. **No single point of trust.** The orchestrator signs instructions; the bank gateway verifies the signature and the approval evidence before sending anything. A compromised orchestrator alone can't pay out, and a compromised gateway can't invent instructions.
4. **Isolation of bank access.** Only the bank gateway holds bank credentials and keys, reaches bank networks, and sits in PCI/bank-facing scope. Core never talks to a bank directly.
5. **Fail closed.** Unknown bank response → `UNKNOWN` plus enquiry, never success. Recon break → payouts from the affected account pause. A rail outage → its kill switch, never a silent fallback to another rail.
6. **Limits everywhere.** Per org, per beneficiary, per rail, per day, platform-wide, and a **pre-funding check** against the source bank account's real available balance.
7. **Everything is reconciled at three levels:** instruction ↔ bank status, ledger mirror ↔ bank statement line, balances ↔ balances.

---

## 3. Parties and where the money sits

```
 Customer's bank ──(instant / EFT / card scheme)──▶ Sponsor (issuer) bank
                                                    ├─ Collection / safeguarding account(s)   (customer funds)
                                                    │     └─ per-org virtual accounts or internal wallets (D-PAY-1)
                                                    ├─ TPTS operating account                 (fee income, costs)
                                                    └─ Settlement accounts per rail
 Card acquirer / processor ──(net settlement T+n)──▶ Collection account
 Sponsor bank ──(EFT / instant / RTGS)──▶ Merchant's, supplier's and MiNdLABS's own bank accounts (payouts)
```

| Party | Role |
|---|---|
| **Sponsor / issuer bank** | Holds the regulated accounts. Gives us APIs or host-to-host channels, statements and access to the national rails |
| **TPTS** | Operates the platform; owns the fee income; instructs the bank |
| **MiNdLABS** | Technology partner; paid its revenue share by payout like any other beneficiary |
| **Card acquirer / processor** | Acquires card payments from terminals; settles net to the collection account |
| **National rails** (through the sponsor bank) | Instant payments (the IPP), EFT clearing, RTGS for high value. Exact access is per the sponsor bank's membership (D-PAY-2) |

**D-PAY-1 (with bookkeeping D-BK-1):**
- **Model A, virtual accounts:** each org has a virtual account number at the sponsor bank, so incoming payments identify the org by account.
- **Model B, pooled account:** one pooled safeguarding account, and orgs are internal wallets identified by payment reference.

Recommendation: **A where the bank supports it** (cleaner matching, cleaner safeguarding evidence), with B as a fallback per rail. The ledger supports both: a `WALLET` ledger account optionally carries a `virtual_account_no`.

---

## 4. Architecture

```
 Portals / partner APIs ──▶ trustplane gateway (FAPI 2.0, RBAC, step-up)
                                   │
                                   ▼
 ┌──────────────────────────── MiPay core ─────────────────────────────────────────┐
 │ Payment API ─▶ Payment Orchestrator (state machines per order type)             │
 │                  ├─ Risk & limits (velocity, sanctions, new-beneficiary rules)  │
 │                  ├─ Approvals (4-eyes / OA6 chain, step-up evidence)            │
 │                  ├─ Charges engine (quote → apply)                              │
 │                  ├─ Ledger (two-phase transfers)                                │
 │                  └─ Instruction outbox  ── signed instruction (JWS) ──┐         │
 │ Reconciliation engine ◀── statements / reports / statuses ───────────┼───┐     │
 └───────────────────────────────────────────────────────────────────────┼───┼─────┘
                                       mTLS mesh, private network      ▼   │
 ┌──────────────────────── bank gateway (new trustplane service) ───────────┴─────┐
 │ verify instruction signature + approvals + limits again ─▶ rail router       │
 │ rail adapters: SPONSOR_API (ISO 20022 / REST) · H2H_SFTP (files) · ACQUIRER  │
 │                · SIM (sandbox)                                                │
 │ inbound: webhooks (signed), file pickup, status enquiry poller,               │
 │          statement ingestion → normalised events back to core                 │
 │ HSM/KMS for keys · vault for credentials · egress allow-list · own DB         │
 └──────────────────────────────────┬─────────────────────────────────────────────┘
                                    ▼  mTLS / VPN / SFTP
                     Sponsor bank · acquirer · (later) other banks
```

**The bank gateway** is a separate deployable in trustplane (Go, like the gateway, or Java like `comms-service`; D-PAY-3):
- **Network:** its own network zone, the only service with egress to bank endpoints (allow-list by IP and SNI).
- **Data:** its own database, holding the instruction log, raw bank messages and file archive. It holds **no balances**; balances live in core's ledger.
- **Keys:** its own HSM/KMS keys.
- **API to core:** small and mTLS-only: `SubmitInstruction`, `EnquireStatus`, `Recall`, `VerifyAccount`, plus event push back (`InstructionStatusChanged`, `StatementReceived`, `IncomingCreditNotified`, `SettlementReportReceived`).

This replaces calling `BankSimulator` from services, and the in-core `ConnectorService` role for money rails.

---

## 5. Payment flows

### 5.1 Order types

| Order type | From → to | Rails | Today |
|---|---|---|---|
| **Collection** (customer → merchant) | customer's bank → merchant's wallet / virtual account | instant credit transfer initiated by the payer (QR / short code / reference), request-to-pay, card via acquirer | `Payment` (QR / SHORT_CODE / CARD) |
| **Internal transfer** (org → org, both on MiPay) | wallet → wallet | **book transfer** in the ledger only; no bank message needed when both wallets sit in the same safeguarding pool or the same bank (virtual accounts: an internal bank transfer) | `InvoicePayment` BOOK_TRANSFER |
| **External payment** (org → outside bank account) | wallet → beneficiary bank | instant, EFT, RTGS above threshold | `InvoicePayment` EFT |
| **Payout / withdrawal** | wallet → the org's own verified bank account | instant, EFT | `Withdrawal` |
| **Refund** | merchant wallet → original payer | same rail back (instant / EFT), card refund via acquirer | `PaymentService.refund` |
| **Advance disbursement / collection** | lender ↔ merchant wallet, 10% sweep | internal transfers + lender settlement payouts | `AdvanceService` |
| **Platform sweeps** | collection account → TPTS operating; TPTS → MiNdLABS revenue share; TPTS → costs (scheme, sponsor) | internal bank transfer, payout | new |
| **Scheduled / instalment payments** | as above, triggered on date | as above | PRD §6 |

### 5.2 One state machine for outbound money

```
 CREATED ─▶ VALIDATED ─▶ (APPROVAL_PENDING ─▶ APPROVED) ─▶ RESERVED ─▶ SUBMITTED ─▶ ACCEPTED ─▶ COMPLETED
                │                 │                          │            │            │
                ▼                 ▼                          ▼            ▼            ▼
             REJECTED          DECLINED                  INSUFFICIENT  UNKNOWN ──▶ (status enquiry) ─▶ ACCEPTED | REJECTED
                                                                         │
                                                       COMPLETED ─▶ RETURNED (bank return later: pacs.004 / EFT unpaid)
                                                       SUBMITTED ─▶ RECALL_REQUESTED ─▶ RECALLED | RECALL_REFUSED
```

| Transition | Ledger effect (double entry) |
|---|---|
| `RESERVED` | PENDING transfer: DR payer wallet, CR `in-flight:rail` (available balance drops) |
| `COMPLETED` | POST_PENDING (+ linked charge and tax transfers) |
| `REJECTED` / `DECLINED` / `RECALLED` | VOID_PENDING (charges reversed per policy) |
| `RETURNED` | new transfer DR `in-flight:rail`, CR payer wallet, plus a return reason |
| `UNKNOWN` | nothing; the money stays reserved until resolved. It is **never** voided or reposted on a guess |

Inbound collections run the mirror: `NOTIFIED` (bank credit notification) → `MATCHED` to an org (virtual account / reference) → `CREDITED` (DR bank mirror, CR org wallet), or `UNMATCHED` → suspense plus a manual allocation (§9.4). Card collections: `AUTHORISED` (pending) → `CAPTURED` → `SETTLED` (acquirer batch) → `CHARGEBACK` (dispute lifecycle).

### 5.3 Safety mechanics

| Mechanism | Detail |
|---|---|
| **Idempotency** | `Idempotency-Key` required on every money API. One key = one order. A replay returns the original result; a same key with a different body → 409 `IDEMPOTENCY_MISMATCH` |
| **Bound ids** | `order_id` = ledger transfer key = `instruction_id` = ISO 20022 `EndToEndId` (and `UETR` where supported). Banks deduplicate on it, and so do we |
| **Outbox** | The instruction is written to the outbox in the same transaction as the RESERVED ledger transfer. The gateway submit is at-least-once, and the bank dedupes on `EndToEndId` |
| **Timeouts** | Every submit has a deadline. Past it → `UNKNOWN` → status enquiry (pacs.028 or the bank API), backing off, escalating to the exceptions desk after N tries. No resubmit while `UNKNOWN` |
| **Callback dedupe** | Bank notifications are deduped by `(bank, message id)` and applied by order id; out-of-order and late messages are handled by the state machine (a late success after a return is flagged) |
| **Pre-funding** | Before submitting a batch, the gateway checks the source account's bank-side available balance (intraday camt.052 or the balance API) minus in-flight. If it falls short, it holds the batch and alerts |
| **Cut-offs and calendar** | Rail cut-off times and the Namibian business-day calendar per rail. EFT after cut-off → next value date shown to the user *before* confirming |
| **Beneficiary controls** | Payout accounts are verified (account/name verification where the bank offers it, else a penny test). A new or changed beneficiary has a cooling-off period and 4-eyes (today's rule kept); first payouts to a new beneficiary have a lower limit |
| **Limits** | Per order, per day, per beneficiary, per org (ledger account limits), per rail and platform-wide caps; any breach → decline or approval |
| **Approvals** | Payouts and external payments over the band need approvals (OA6 chain), bound to the **payload hash** with a step-up per approver. Platform sweeps and revenue-share payouts are always 4-eyes (T3/T4) |
| **Kill switches** | Per rail, per bank, per org and global "payouts paused" (T4 to flip, audited). In-flight orders are unaffected; new ones queue |
| **Recon gate** | An open critical break on an account pauses payouts from that account (today's "release blocked until resolved" rule, generalised) |
| **Rail failover** | Never automatic for the same order. A failed instant payment may be **re-offered** as EFT as a new order with user consent |

---

## 6. Supported money-transfer protocols

### 6.1 Message standards

| Purpose | ISO 20022 message | Legacy / alternative |
|---|---|---|
| Initiate credit transfers (single / bulk) | `pain.001` | ACB/NamClear EFT file format via the bank (if ISO isn't offered); bank REST API |
| Status of initiation | `pain.002` | bank API status, file response reports |
| Interbank credit transfer (instant / RTGS, by the bank) | `pacs.008` (we see it reflected in statuses and statements) | n/a |
| Payment status | `pacs.002` | n/a |
| Return | `pacs.004` | EFT unpaid / return codes |
| Status enquiry | `pacs.028` | bank API GET status |
| Recall / cancellation and its answer | `camt.056` / `camt.029` | bank API recall |
| Request to pay | `pain.013` / `pain.014` | QR with reference (payer-initiated) |
| Account / name verification | `acmt.023` / `acmt.024` | bank account-verification API, penny test |
| Intraday report / end-of-day statement / debit-credit notification | `camt.052` / `camt.053` / `camt.054` | SWIFT `MT940` / `MT942`, bank CSV (manual upload) |
| Investigation | `camt.027` / `camt.029` | bank service desk ticket ref |
| Card settlement | acquirer settlement report (file / API) | n/a |

ISO 20022 is the **canonical internal model**: every adapter maps its bank's format to and from normalised ISO 20022 shapes, so the orchestrator and reconciliation see one vocabulary.

### 6.2 Rails (through the sponsor bank; exact list confirmed in D-PAY-2)

| Rail | Use | Speed | Notes |
|---|---|---|---|
| **Instant payments (Namibia IPP)** | collections by QR / short code / reference, small payouts, refunds | seconds, 24/7 | Preferred default where available; limits per the scheme rules |
| **EFT (NamClear clearing)** | payouts, B2B to external banks, bulk | same/next business day, cut-offs | Batch; returns arrive later as unpaids |
| **RTGS (NISS) via the bank** | high-value / urgent | same day, within business hours | Above a threshold, by approval only |
| **Book / internal transfer** | wallet ↔ wallet, platform sweeps | immediate | Ledger only (pooled) or an internal bank transfer (virtual accounts) |
| **Card (acquirer)** | card-present terminals (TPTS devices), card-not-present | auth real-time, settle T+n | We never handle PAN (§7.4) |
| **EMV QR (merchant-presented)** | QR collections | via the instant rail | EMVCo MPM payload carries the merchant virtual account / reference |
| *Later:* mobile money, cross-border (SWIFT `pacs.008` / gpi via the bank) | | | out of scope for v1 |

### 6.3 Transport channels

| Channel | Used for | Protection |
|---|---|---|
| **Bank REST/JSON APIs** (open-banking style) | single instructions, status, balances, account verification, webhooks | mTLS + OAuth 2 (`private_key_jwt`, certificate-bound tokens), FAPI 2.0 Security Profile where the bank supports it; JWS-signed request and response bodies |
| **Host-to-host SFTP** | bulk pain.001 / EFT files, camt.053 / MT940 statements, card settlement files | SSH key auth + pinned host key, files **PGP-signed and encrypted**, file sequence numbers, checksums, duplicate-file detection |
| **Webhooks from the bank** | instant payment notifications, status updates | mTLS where offered; **HTTP Message Signatures (RFC 9421)** or bank JWS; timestamp window + nonce replay cache; IP allow-list |
| **Private connectivity** | all of the above | IPsec VPN or private link to the bank where required; no bank traffic over shared egress |

### 6.4 Adapter contract (SPI)

Each rail adapter declares its **capabilities**: instant/batch, status enquiry, recall, request-to-pay, account verification, currencies, limits, cut-offs, statement formats, signature scheme. It implements:
`submit(instruction)`, `enquire(id)`, `recall(id)`, `verifyAccount(acct)`, `fetchStatements(since)`, `parseInbound(message)`, `health()`.

Every adapter is certified against the bank's test environment with a scripted conformance pack (§13) before its connector can be set to `PRODUCTION`. The `SIM` adapter implements the same SPI.

---

## 7. Security protocols and controls

### 7.1 Transport and identity
- **TLS 1.3** everywhere (TLS 1.2 only with FAPI-approved cipher suites where a bank requires it); HSTS at the edge.
- **mTLS** inside the mesh (already) and to every bank endpoint, with **certificate pinning** per counter-party and a short-lived workload certificate for the bank gateway.
- **OAuth 2.0 / FAPI 2.0** to bank APIs: PAR, `private_key_jwt`, sender-constrained tokens (mTLS or DPoP), no shared secrets. **`API_KEY` auth is refused for money connectors.**
- Partner and client access to MiPay payment APIs: the FAPI 2.0 lane already designed (mTLS + DPoP), with step-up (RFC 9470) for payouts and beneficiary changes.

### 7.2 Message integrity and non-repudiation
- **Instruction signing**: the orchestrator signs each instruction (JWS, ES256/PS256, key in KMS/HSM) over the canonical payload, which includes the amount, currency, debtor, creditor, `EndToEndId` and approval references. The bank gateway verifies it, re-checks limits and approval evidence, and refuses any instruction it can't verify.
- **Approval binding**: each approval (OA6) signs the payload hash; the gateway verifies that the chain is complete.
- **Bank-facing signatures**: JWS / JAdES on API bodies, PGP on files, as each bank requires; the bank's responses are verified the same way.
- **Raw message archive**: every message in and out is stored unchanged (WORM storage, hash-chained) for disputes and audit.

### 7.3 Keys and secrets
- Signing and encryption keys in an **HSM or cloud KMS** (FIPS 140-2/3 Level 3), non-exportable. Bank credentials in a vault, referenced by `credentialsRef` (today's model), never in env vars, images or the database.
- Rotation on a schedule and on demand (`rotateCredentials` exists). Key ceremonies under **dual control**; separate keys per environment; sandbox keys can never sign production instructions (environment in the JWS header, checked).

### 7.4 Card data
- MiPay **never stores, processes or transmits PAN**: terminals are EMV L1/L2 certified with **P2PE** to the acquirer (or a certified SoftPOS/MPoC app); card-not-present uses the acquirer's hosted fields / **3-D Secure 2**; MiPay holds acquirer tokens only. This keeps PCI DSS v4.0 scope to the smallest SAQ level the acquirer allows (D-PAY-4).
- TPTS's device inventory (inventory plugin `devices` pack) records the terminals' P2PE / key-injection status.

### 7.5 Data protection
- Encryption at rest (AES-256), plus **field-level encryption** for bank account numbers and national IDs; masked in logs and UIs (last 4); access to unmasked values is a separate RBAC cell and audited.
- Log hygiene: no secrets, tokens, full account numbers or card data. Logging middleware tests enforce it.

### 7.6 People and process controls
- No person can move money directly: there is no SQL write access to production ledgers and no "manual payment" outside the order workflow. Manual adjustments are maker-checked journals (bookkeeping §5.1).
- Separation of duties: whoever creates a beneficiary can't approve it; whoever submits a payout can't approve it; recon preparers can't approve their own adjustment journals.
- Break-glass access is time-boxed, needs two people, and is alerted and reviewed.

### 7.7 Fraud and financial crime
- **Sanctions screening** of beneficiaries and payers on creation and on payout (KYC sanctions check reused), with list updates.
- **Transaction monitoring**: velocity, unusual amount, new beneficiary + large payout, structuring, round-tripping between related orgs. Alerts open cases; suspicious transaction reports go to the Financial Intelligence Centre in its required format (compliance confirms the reporting channel and thresholds; D-PAY-5).
- Device and session signals from the IdP feed risk scoring for step-up decisions.

### 7.8 Platform security
- A hash-chained audit log for every money action and configuration change; SIEM alerts on payout spikes, kill-switch flips, signature failures, recon breaks.
- DR: the ledger database uses synchronous replication (RPO 0); the gateway's instruction log is replicated; a documented, rehearsed failover that keeps idempotency (no replays after failover create new instructions).
- Regular penetration tests (OWASP API Top 10 matrix from `fapi2-design.md` Appendix E, extended to payment endpoints and the bank gateway); dependency and image scanning.
- Compliance targets: PCI DSS v4.0 (minimised scope), ISO 27001-aligned ISMS, the applicable Bank of Namibia payment-system determinations, and the sponsor bank's security schedule (D-PAY-5 lists them).

---

## 8. Settlement

- **Card**: the acquirer settles net (gross − MDR − scheme fees) T+n into the collection account. The settlement report lists the transactions. The engine matches batch ↔ captured payments ↔ the bank credit; fee differences against our charges engine are flagged.
- **Instant / EFT collections**: gross per transaction; matched individually.
- **Merchant settlement**: merchants hold wallet balances; money reaches their own bank on withdrawal (manual or scheduled auto-payout, e.g. daily above N$x).
- **Platform sweeps**: daily, fee income moves from the collection or safeguarding account to the TPTS operating account (only after the day reconciles); monthly revenue-share payout to MiNdLABS after the period closes.
- The `Settlement` table becomes **settlement batches** with lines and a match status.

---

## 9. Reconciliation: automatic and manual

### 9.1 What is reconciled

| Level | Left | Right | When |
|---|---|---|---|
| L1 Instruction | every outbound order | the bank's status (ack, final, return) | continuously; `UNKNOWN` > SLA → exception |
| L2 Transactions | ledger entries on each `BANK` mirror account | bank statement lines (camt.053/054, MT940) | intraday (camt.052/054) + end of day |
| L3 Settlements | captured card payments − fees | acquirer settlement report and the bank credit | per batch |
| L4 Balances | the mirror account's closing balance | the bank statement's closing balance | end of day, per account |
| L5 Safeguarding | Σ customer wallet balances | safeguarded cash at the bank(s) | daily (regulatory evidence) |
| L6 Internal integrity | GL integrity job (bookkeeping §5.2) | | nightly |

### 9.2 Statement ingestion
- **Automatic**: via SFTP pickup or the bank API on a schedule; each file is verified (signature, checksum), parsed and normalised to ISO 20022, and the raw file archived.
- **Manual upload** (fallback or banks without H2H): camt.053, MT940 or a CSV template, uploaded by `recon.statement:create`, with a preview before commit.
- **Continuity checks**: statement id + sequence dedupe; opening balance = previous closing balance; gaps or overlaps raise a `STATEMENT_GAP` break; a statement can't be ingested twice.

### 9.3 Automatic matching engine
Rules run in priority order. Each produces a match with a confidence score:

1. **Exact id**: `EndToEndId` / `UETR` / bank reference = our instruction id → confidence 1.0
2. **Virtual account + amount + value date** → 0.98
3. **Unique payment reference** (invoice `uniqueReference`, QR reference, short code + reference) + amount → 0.95
4. **Batch**: one bank line = Σ of N ledger entries (settlement batch id, EFT batch) → 0.95
5. **Split**: N bank lines = one ledger entry (partial credits) → 0.9
6. **Fuzzy**: amount ± tolerance, date ± k days, counter-party name similarity → score 0.5–0.9

- Rules are configuration (versioned, maker-checked, simulated on past statements before publishing).
- At or above the **auto-match threshold** (default 0.95) the match commits automatically. Below it, it becomes a **suggestion** in the manual queue.
- Auto-matching never posts money on its own except for known, rule-defined cases (e.g. bank charges lines → `bank fees` expense, allowed by a rule). Everything else that needs a posting goes through the workbench.

### 9.4 Exceptions (break types)

| Break | Typical cause | Default handling |
|---|---|---|
| `UNMATCHED_BANK_CREDIT` | payer used a wrong reference; unknown receipt | Post to **suspense**; the manual allocation to an org needs evidence + 4-eyes |
| `UNMATCHED_BANK_DEBIT` | bank charge, unknown debit | Rule-based expense, or investigate with the bank |
| `MISSING_AT_BANK` | we recorded a completed order the bank didn't book | L1 enquiry; investigation; pause payouts on that account |
| `AMOUNT_MISMATCH` | FX, partial, fee netting | Adjust within tolerance by rule; else manual |
| `DUPLICATE_AT_BANK` | the bank booked twice | Recall (camt.056) / claim with the bank |
| `RETURNED` | beneficiary account closed or invalid | Order → `RETURNED`; credit the wallet back; notify |
| `STALE_UNKNOWN` / `STALE_PENDING` | no final status past SLA | Enquiry escalation; ops |
| `BALANCE_BREAK` | L4 closing balances differ | **Critical**: blocks payouts from the account and the day close |
| `SAFEGUARDING_SHORTFALL` | L5 cash < liabilities | **Critical**: immediate top-up process and compliance alert |
| `SETTLEMENT_FEE_DIFF` | acquirer fee ≠ our charge | Finance review; tariff check |
| `STATEMENT_GAP` | missing or duplicate statement | Request from the bank; blocks the close |

These replace today's `MISSING_CREDIT, DUPLICATE_DEBIT, STALE_HOLD, ORPHAN_PAYMENT`.

### 9.5 Manual reconciliation workbench (widgets)

Every screen in this plan — payment initiation with its fee/tax quote, payouts and approvals, connector and kill-switch admin, the reconciliation workbench, day close and the recon reports — is drawn in [`payments-banking-ui.md`](payments-banking-ui.md) (§4–§10, G1–G8).

- **Queues** by account, break type, age and amount, with SLAs and owner assignment. Critical items are pinned at the top.
- **Side-by-side matching**: the bank line on the left; candidate ledger entries on the right, ranked by the engine's score with the rule that found each. Actions: match 1:1, 1:N, N:1, split, unmatch (with reason), mark as bank error.
- **Postings from the workbench** (allocate an unknown receipt to an org, book a bank charge, correct an amount) create a **draft journal** that a second person approves (`recon.adjustment:authorize`). The preparer can never approve their own.
- **Investigations**: open a case with the bank (camt.027 or a ticket ref), attach evidence, track the response (camt.029); the break stays open until resolved.
- **Evidence and audit**: comments, attachments, every click recorded; break history immutable.
- **Bulk actions** limited to non-posting actions (assign, comment), never bulk money postings.

### 9.6 Day and period close
- **Day close per bank account**: all statements in, L2/L4 complete, no critical breaks → the day is **certified**. The platform sweeps and auto-payouts for that account run only after certification.
- **Period close** (bookkeeping §5.3) requires every day in the period certified and the suspense balance within tolerance.
- **Reports**: the recon status dashboard, ageing of breaks, auto-match rate, suspense ageing, the safeguarding report (L5) for compliance.

---

## 10. Operations
- **Monitoring**: per rail success rate, latency, `UNKNOWN` rate, queue depth, pre-funding headroom, cut-off countdowns, statement arrival; alerts route to on-call.
- **Runbooks**: bank outage, stuck batch, duplicate payment, wrong beneficiary, key compromise, safeguarding shortfall.
- **Business-day calendar** per rail (Namibian public holidays, bank cut-offs), maintained as configuration.
- **Customer communication**: statuses shown honestly (`processing`, `expected by <value date>`, `returned: reason`), never "paid" before `COMPLETED`.

---

## 11. Sandbox: the simulator becomes a rail

`BankSimulator` and the simulation endpoints become the **`SIM` rail adapter** inside the bank gateway, behind the same SPI, so UAT runs the real orchestrator, ledger, signing and reconciliation code.
- Existing scenarios kept: `CONFIRM, DUPLICATE, TIMEOUT, LATE_CONFIRM, FAIL, MANUAL`.
- New scenarios: `RETURN_LATER`, `UNKNOWN_THEN_ENQUIRY`, `PARTIAL_SETTLEMENT`, `STATEMENT_WITH_UNKNOWN_CREDIT`, `STATEMENT_GAP`, `BALANCE_BREAK`, `ACQUIRER_FEE_DIFF`, `SIGNATURE_INVALID`.
- It generates real camt.053 / MT940 / acquirer files so ingestion and matching are exercised.
- SIMULATED labelling stays on everything it produces. The `SIM` adapter is **refused in `PRODUCTION`**: the gateway won't load it and the connector environment check blocks it.

---

## 12. Integration with existing code

| Existing | Change |
|---|---|
| `PaymentService` (`accept`, `confirm`, `fail`, `refund`, `simulate`) | Becomes the collection side of the orchestrator; confirm/fail come from gateway events, not direct calls; `simulate` goes via the SIM adapter |
| `B2BPaymentService` (`payInvoice`, `initiateInvoicePaymentAsync`, `retryTimedOut`) | Internal / external payment orders; `retryTimedOut` replaced by status enquiry on `UNKNOWN` |
| `WithdrawalService` | Payout orders with beneficiary verification, limits, approvals |
| `AdvanceService` disbursement and sweep | Internal transfers + lender payouts through the orchestrator |
| `BankSimulator`, `SimulationService` payment/invoice parts | Move into the bank gateway as the SIM adapter |
| `Connector`, `ConnectorService` | The money-rail connectors move to the bank gateway's registry (KYC, notification and lender-scoring connectors stay where they are); `API_KEY` disallowed for rails |
| `ReconciliationService`, `ReconciliationRun/Rule/Break`, `EligibilityRule`, `OpsController /recon/*` | Replaced by the reconciliation engine (§9); the API grows (statements, matches, workbench); "open breaks block release" kept |
| `Settlement` | Settlement batches + lines |
| `Payment`, `InvoicePayment`, `Withdrawal`, `Refund` | Become typed views over a common `payment_orders` table (order type, state machine, ids, rail, instruction refs) |
| Ledger calls | Two-phase transfers (bookkeeping plan) |
| Fees | Charges engine quote → apply on order completion |

### 12.1 RBAC cells (fixed verbs)

| Resource | Verbs |
|---|---|
| `pay.order` | read, create, submit, authorize (approval), execute (release), export |
| `pay.beneficiary` | read, create, modify, authorize |
| `pay.limit`, `pay.rail` (kill switch, routing, cut-offs) | read, modify, authorize |
| `bank.connector` | read, create, modify, authorize, execute (rotate), simulate |
| `recon.statement` | read, create (manual upload), export |
| `recon.match` | read, create, delete (unmatch), authorize |
| `recon.adjustment` | create, submit, authorize |
| `recon.break` | read, modify, execute (close) |
| `recon.rule` | read, create, modify, authorize, simulate |

---

## 13. Tests

| Suite | Covers |
|---|---|
| TC-PAY (orchestrator) | every state transition and its ledger effect; idempotency replay and mismatch; `UNKNOWN` → enquiry → both outcomes; late success after return; concurrent payouts on one wallet (no overdraft); cut-off value dating; limits; approvals bound to payload hash (a changed amount after approval is refused) |
| TC-GW (bank gateway) | an unsigned or tampered instruction is refused; missing approval refused; SIM refused in PRODUCTION; `API_KEY` money connector refused; webhook signature, replay window, duplicate; file PGP verification; pre-funding hold |
| TC-RAIL (per adapter, conformance) | submit / status / return / recall / statements against the bank test environment; required before `PRODUCTION` |
| TC-REC (reconciliation) | each matching rule; auto-match threshold; every break type produced by a SIM scenario; manual allocation with 4-eyes; statement gap and duplicate; balance break blocks payouts and the day close; safeguarding report |
| Regression | TC-ACC-01..04, TC-B2B-03/05/08, TC-BAL-02, TC-SET-23, TC-RACE-11 (rewritten on the new engine, same intent) |
| Security | pen test of payment endpoints and the gateway; key-rotation drill; DR failover with no duplicate instructions |

## 14. Phases

| Phase | Work | Depends on |
|---|---|---|
| PB0 | Decisions D-PAY-1…7; choose the sponsor bank and acquirer; get their API / H2H specs and test access | business |
| PB1 | Payment orders + state machine on the double-entry ledger; SIM adapter inside a first bank-gateway build; instruction signing; outbox | bookkeeping B2 |
| PB2 | Reconciliation engine: statement ingestion (auto + manual), matching rules, break types, workbench widgets, day certification | PB1 |
| PB3 | First real rail: sponsor bank API (instant + EFT + statements + account verification) in the bank test environment; conformance pack | PB0, PB1 |
| PB4 | Card acquiring integration (terminals via inventory devices, settlement files) | PB3, inventory I3 |
| PB5 | Fraud/AML monitoring, beneficiary controls, kill switches, limits UI | PB1 |
| PB6 | Production readiness: pen test, DR drill, PCI attestation, the bank's security sign-off; go-live per rail behind its switch, shadow first (mirror real statements against the SIM-driven ledger) | all |
| PB7 | Platform sweeps + revenue-share payouts | PB3, revenue-share plan |

## 15. Decisions needed

| # | Question | Recommendation |
|---|---|---|
| D-PAY-1 | Virtual accounts per org, or a pooled account with references? | Virtual accounts where the bank supports them |
| D-PAY-2 | Which sponsor bank, and which rails it gives us (instant, EFT, RTGS, account verification, request-to-pay)? | Business choice; it drives PB3 |
| D-PAY-3 | Bank gateway language: Go (like the gateway) or Java (like comms/rbac)? | Go: small, isolated, same ops as the gateway |
| D-PAY-4 | Card acceptance: physical P2PE terminals only, or also SoftPOS; CNP with 3DS2? | P2PE terminals first (they ride the TPTS devices flow) |
| D-PAY-5 | Regulatory list: BoN determinations that apply, safeguarding method, FIC reporting channel and thresholds | Compliance |
| D-PAY-6 | Auto-match threshold and tolerances; the payout approval bands | Finance + compliance; defaults 0.95 / N$0.00 / OA6 bands |
| D-PAY-7 | Auto-payout to merchants' banks (daily sweep above a threshold) on by default? | Off by default; merchant opt-in |
