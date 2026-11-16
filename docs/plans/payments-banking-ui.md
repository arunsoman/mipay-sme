# Payments, banking & reconciliation: flows and screens (UI annex)

Status: **draft for review** · 2026-11-15 · companion to [`payments-and-banking-design.md`](payments-and-banking-design.md) and [`bookkeeping-and-charges-design.md`](bookkeeping-and-charges-design.md)

This document is the **human side** of the money design: how a payment is initiated with its fees, taxes and charges, what every screen shows for it, the banking-integration screens, and the reconciliation reports and workbenches. It adds no new mechanics; every state, break type, cell and rule here is the one defined in the two parent plans. It exists so that builders and reviewers can see the whole journey without reading the engine internals.

---

## 1. Where the screens are today (2026-11-15)

| Screen | Where | What it does | Becomes |
|---|---|---|---|
| Dashboard (M04) | `frontend/src/app/features/merchant/screens.ts` | Balance, today's movement, recent payments | A2 tiles refreshed from ledger accounts |
| Accept payment (M06) | same | QR / short-code / card entry + issuer callback simulator; card posts `PENDING` until issuer confirms (TC-ACC-01) | A1–A3 with a **fee + tax quote before confirm** (new; `FeeService.preview` exists but no screen uses it yet) |
| Payments list (M11) / Payment trail (M10) | same | Every payment with channel and status; full trail with issuer events and idempotency keys (TC-ACC-05) | A4/A5 with the charge explain drawer |
| Balance & withdrawal (M05) | same | Confirmed / pending / held / owed + M26 release to issuer bank account; withdrawal history | C1 with quote, limits, value date; C4 approval queue |
| Stock orders & supplier invoices (M13/M16) | same | Order from catalogue; pay invoice from balance — funds held, then settled atomically (TC-B2B) | B1–B3 |
| Cash advance (M21–M25) | same | Cap, disbursement, 10% sweep (TC-ADV) | unchanged; charges via the charges engine |
| Statements (M28) | same | Generate for a period; download valid 60 minutes (C04) | E1 with fee + tax lines from `chg.charge_lines` |
| Ops reconciliation (T05–T08) | `frontend/src/app/features/ops/screens.ts`, `OpsController /recon/*` | Run a check, runs, breaks, resolve ("while a mismatch is open, withdrawals are paused") | G1–G8; break types grow to the §9.4 list of the parent plan |
| Ops reports / audit | same | Daily settlement and fees earned for a day; audit trail | G9 + F6 instruction log |
| Fee preview | `FeeService.preview` → `Dtos.FeePreview(amount, billed, externalCost, contribution, ipsFee, switching, sponsor, labels)` | API only — **no screen calls it yet** | replaced by `POST /api/charges/quote` + the A1 breakdown panel |

---

## 2. The payment initiation journey (with fees, taxes and charges)

```
 Payer / merchant portal                      Core (orchestrator)                     Bank gateway
 ───────────────────────                      ───────────────────                     ───────────
 1 choose method (QR / short code /
   card / B2B invoice / withdrawal)
        │
 2 QUOTE  ── POST /api/charges/quote ────────▶ resolve plan for org (platform →
        ◀── breakdown: fee lines, tax lines,    org type → subtree → org → product, §7.4)
        │    total, who pays, value date        for each charge: condition (CEL) → pricing
        │                                       → modifiers → rounding → tax → splits
        │                                       (no persistence; returns quote_hash)
 3 CONFIRM ── Idempotency-Key + quote_hash ─▶ checks: limits, funds, cut-off, step-up
        │                                       │
        │                                       ├─ RESERVED: pending transfer (DR wallet
        │                                       │  / payer, CR in-flight:rail)
        │                                       └─ instruction outbox ── signed JWS ────▶ verify signature,
        │                                                                                  approvals, limits again
 4 WAIT     status shown honestly:            ◀── InstructionStatusChanged ─────────── rail adapter
            "processing / expected by <value       (ack, final, return, UNKNOWN)
             date / returned: reason"
        │                                          UNKNOWN ⇒ enquiry (pacs.028), never resubmit
 5 SETTLE   COMPLETED ──────────────────────▶ POST_PENDING + linked charge transfers
        ◀── receipt with fee + tax breakdown      (charges engine apply(quote) → charge lines
                                                   → posting rules → GL, same transaction)
 6 STATEMENT / explain drawer: every line answers "which plan, rate, tax code, why"
```

Rules the screens must never break (from the parent plans):
- A fee is **quoted before** confirm (A1/C1) and applied **exactly once** per `(charge_code, source_event_id, payer)`; a reversal follows the charge type's policy (`FULL / PROPORTIONAL / NONE / NON_REFUNDABLE_AFTER`).
- Tax lines come from the determination table (D-BK-2): shown when the charge is taxable, inclusive or exclusive per charge type; a VAT-registered org's fee billing produces a **gapless tax invoice** (E2).
- Statuses are honest: nothing is "paid" before `COMPLETED`; `UNKNOWN` is shown as "checking with the bank", never success or failure.
- Refunds mirror the journey (A6) with the fee reversal policy applied visibly.

---

## 3. What each order type shows as charges

| Order type (§5.1 parent) | Charge lines shown | Tax | Payer |
|---|---|---|---|
| Collection QR / short code | `card_service`-style platform fee per plan (rate/slab/volume), free allowance progress | per determination (`VAT_STD` / exempt), inclusive or exclusive | merchant (DEDUCT) or payer (added), per plan |
| Collection card | platform fee + acquirer cost shown as **cost lines** (interchange, switching, sponsor — basis labels CONTRACTED / PROVISION / UNQUOTED) | as above | TPTS (cost, ACCRUE) |
| Internal transfer (invoice, book) | commission lines where a network split applies (§7.7) | per plan | payer org |
| External payment / EFT | rail fee + value-date notice | as above | payer org |
| Payout / withdrawal | `withdrawal_fee` (flat/percent/tiered), WHT deducted where applicable (`WHT payable`, certificate ref) | VAT where taxable; WHT is a deduction, shown as its own line | org |
| Refund | fee reversal per policy: "fee refunded in full / proportionally / retained" | mirrored | — |
| Advance | `advance_service` fee at disbursement; `late_repayment_penalty` TIME_BASED on arrears | per determination | merchant |
| Platform sweeps / revenue share | not shown to orgs; internal (F7) | — | TPTS |

---

## 4. Screens — collection and payments (A)

### A1 · Accept payment (merchant; evolves M06)
```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Accept payment                                          channel: ◉QR ○Code ○Card│
├──────────────────────────────────────────────────────────────────────────────┤
│ Amount  [ 450.00 ]  NAD      reference (optional) [ table-12 ]               │
│                                                                              │
│ ┌ QUOTE — before you confirm ─────────────────────────────────────────────┐  │
│ │ Card service fee (Merchant Standard 2027, 2.75%, first 20 this month)   │  │
│ │   12.38 + VAT 15% 1.86                                    = N$14.24     │  │
│ │ You receive                                             N$435.76        │  │
│ │ free allowance: 8 of 20 card payments left this month   ⓘ how this is   │  │
│ │                                                         calculated      │  │
│ └─────────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
│ [ Generate QR ]  (short code: *134*844*ref#   ·   card: hand terminal over)  │
├──────────────────────────────────────────────────────────────────────────────┤
│   ██████████████   QR (EMV MPM, merchant virtual account)                    │
│   ██  ▓▓▓▓▓▓  ██   450.00 NAD · table-12 · expires 14:59                     │
│   ██████████████                                                             │
└──────────────────────────────────────────────────────────────────────────────┘
```

### A2 · Customer pay screen (QR / short code deep-link)
```
┌──────────────────────────────┐
│ Pay  Kapana Grill            │
│ 450.00 NAD · table-12        │
│ ──────────────────────────── │
│ Pay from: ◉ IPP instant      │
│           ○ Card             │
│ [Confirm in bank app ▸]      │
│ ──────────────────────────── │
│ You'll confirm in your bank; │
│ MiPay shows the result here. │
└──────────────────────────────┘
```

### A3 · Card sale on the terminal (P2PE; MiPay never touches the PAN, D-PAY-4)
```
┌────────────────┐   terminal flow: amount → present/insert/tap →
│ 450.00 NAD     │   ONLINE AUTH → (pending) → capture → confirm.
│ ▊▊▊▊ present   │   MiPay sees AUTHORISED → CAPTURED → SETTLED (batch, §8).
│ [Approved ✓]   │   Screen shows only scheme refs, never PAN.
└────────────────┘
```

### A4 · Payment confirmation (after COMPLETED)
```
┌──────────────────────────────────────────────────────────────────────────────┐
│ ✓ Paid · N$450.00 · card · 14:32            ref PAY-8f3a…  receipt ⎙  share ▸│
├──────────────────────────────────────────────────────────────────────────────┤
│ Gross                        450.00                                          │
│ Card service fee (2.75%)     −12.38                                          │
│ VAT 15% (VAT_STD)             −1.86      tax invoice TI-2026-004318 ⎙        │
│ Net to your wallet           435.76                                          │
│ Funds in:  issuer confirmed  ·  booked to wallet:org/7f3…  ·  ledger ✓       │
└──────────────────────────────────────────────────────────────────────────────┘
```

### A5 · Payment detail + charge explain drawer (evolves M10)
```
┌───────────────────────────────┬──────────────────────────────────────────────┐
│ PAY-8f3a…            CONFIRMED│ CHARGE EXPLAIN  charge_line c-9921           │
│ 450.00 NAD · card · 14:32     │ ──────────────────────────────────────────── │
│ ── trail ────────────────────  │ plan      Merchant Standard 2027  v4        │
│ INITIATED 14:31:58 idem:9c1f   │ assignment ORG (Acme Foods)                 │
│ PENDING    14:31:59 issuer ok  │ condition event.method=='CARD'              │
│ CONFIRMED  14:32:02 posted ✓   │ model     PERCENT 2.75% of amount           │
│ journal  J-2026-11-004318      │ modifier  allowance: 8/20 used (Nov)        │
│ ── ids ──────────────────────  │ tax       VAT_STD 15% exclusive             │
│ EndToEndId  E2E-9c1f…          │ split     TPTS 100% → revshare post-period  │
│ instruction INS-7712 ✓ signed  │ ──────────────────────────────────────────── │
│ bank ref    IPP-44190          │ [Open plan] [This month's charges]          │
└───────────────────────────────┴──────────────────────────────────────────────┘
```

### A6 · Refund
```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Refund PAY-8f3a…                                                             │
│ Original 450.00 · fee 14.24 (12.38 + 1.86 VAT)                               │
│ Amount to refund [ 450.00 ]   policy (card_service): FULL                    │
│ ┌ preview ───────────────────────────────────────────────────────────────┐   │
│ │ customer receives 450.00 · fee reversed 14.24 · wallet −464.24          │   │
│ │ accumulators restored (allowance 8/20 again)                            │   │
│ └────────────────────────────────────────────────────────────────────────┘   │
│ [Refund]  Idempotency-Key auto · step-up (T2)                                │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Screens — B2B invoice payment (B)

### B1 · Supplier invoices (evolves M16)
```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Stock orders & supplier invoices                                             │
│ ────────┬──────────────┬─────────┬──────────┬────────────┬────────────────── │
│ Invoice │ Supplier     │ Amount  │ Due      │ State      │ Action           │
│ INV-221 │ Oshana Spares│ 8,400.00│ 30 Nov   │ AWAITING   │ [Pay]            │
│ INV-218 │ Oshana Spares│ 5,120.00│ paid     │ CONFIRMED  │ view             │
├──────────────────────────────────────────────────────────────────────────────┤
│ wallet available: 21,930.00 · held for invoices: 8,400.00 (HELDOVER list ▸)  │
└──────────────────────────────────────────────────────────────────────────────┘
```

### B2 · Pay invoice (rail choice + value date; becomes a payment order)
```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Pay INV-221 · Oshana Spares · 8,400.00 NAD                                   │
│ How: ◉ Book transfer (both wallets on MiPay) — immediate, no fee             │
│      ○ EFT to their bank — arrives next business day (cut-off 15:00)         │
│      ○ Instant (IPP) — seconds, fee 1.50 + VAT                               │
│ ┌ quote ─────────────────────────────────────────────────────────────────┐   │
│ │ rail fee 0.00 (book) · tax 0.00 · total debited 8,400.00               │   │
│ │ value date: today 26 Nov   available after: 13,530.00                  │   │
│ └────────────────────────────────────────────────────────────────────────┘   │
│ funds are HELD now, released to the supplier when the transfer completes     │
│ [Pay]  Idempotency-Key auto                                                  │
└──────────────────────────────────────────────────────────────────────────────┘
```

### B3 · Invoice payment states (sub-screen of B1)
```
HELD ─▶ PROCESSING ─▶ CONFIRMED
   │        │              (supplier wallet credited; order FULFILLED)
   │        └─▶ TIMED_OUT ─▶ [Retry] → enquiry on UNKNOWN, never blind resend
   └─▶ FAILED (released back to wallet, reason shown)
```

---

## 6. Screens — withdrawals and payouts (C)

### C1 · Balance & withdraw (evolves M05)
```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Balance & withdrawal            wallet:org/7f3… · NAD                        │
│ ┌───────┬─────────┬────────┬────────┐                                        │
│ │ 21,930│ pending │  held  │  owed  │   (posted / pending / holds / advances)│
│ └───────┴─────────┴────────┴────────┘                                        │
│ Withdraw to: Bank Windhoek ·•••7781 (verified ✓, added 12 Aug)               │
│ Amount [ 10,000.00 ]                                                         │
│ ┌ quote ─────────────────────────────────────────────────────────────────┐   │
│ │ withdrawal fee (2 free used; 3rd+) 5.00 + VAT 0.75        = 5.75       │   │
│ │ WHT: none (VAT-registered, certificate exempt)                          │   │
│ │ value date: today (EFT cut-off 15:00 · it is 14:12 ✓)                   │   │
│ │ arrives: 10,000.00 at the bank · daily limit left 40,000.00             │   │
│ └────────────────────────────────────────────────────────────────────────┘   │
│ [Request withdrawal]   > 5,000 ⇒ approval queue (C4) · step-up (RFC 9470)    │
├──────────────────────────────────────────────────────────────────────────────┤
│ History: WR-118 PAID 12 Nov · WR-122 REQUESTED · WR-119 FAILED (reason ▸)    │
│ auto-payout: off (settings ▸ — daily sweep above N$x, D-PAY-7)               │
└──────────────────────────────────────────────────────────────────────────────┘
```

### C2 · Beneficiaries (own accounts and third parties)
```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Beneficiaries                                        [+ Add beneficiary]     │
│ ────────┬───────────────────┬──────────────┬───────────┬──────────────────── │
│ •••7781 │ own · Bank Windhoek│ verified ✓  │ usable    │                    │
│ •••3320 │ MiNdLABS (payout)  │ verified ✓  │ 4-eyes    │ revenue share      │
│ •••9904 │ new supplier       │ penny test… │ cooling   │ eligible 24 Nov    │
├──────────────────────────────────────────────────────────────────────────────┤
│ rules: creator ≠ approver · new/changed ⇒ cooling-off + first-payout low cap │
└──────────────────────────────────────────────────────────────────────────────┘
```

### C3 · Approval queue (4-eyes / OA6; approvals bound to payload hash)
```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Payout approvals                                    band: > N$5,000          │
│ ────────┬──────────┬─────────┬────────────────┬──────────────┬────────────── │
│ WR-124  │ 12,000.00│ •••7781 │ requested by…  │ awaiting 2nd │ [Approve]     │
│         │          │         │ step-up ✓      │ approver     │ [Decline]     │
├──────────────────────────────────────────────────────────────────────────────┤
│ approving signs the payload hash: any change after approval voids it         │
│ I created this request ⇒ I cannot approve it (SoD)                           │
└──────────────────────────────────────────────────────────────────────────────┘
```

### C4 · Payout status (honest states)
```
┌──────────────────────────────────────────────────────────────────────────────┐
│ WR-124 · 12,000.00 · EFT · •••7781                                           │
│ SUBMITTED 14:31 → ACCEPTED 14:33 → ██████████░░ processing                   │
│ expected by: today 17:00 (EFT)                                               │
│ if the bank returns it: "RETURNED — account closed" → funds back to wallet,  │
│ you are notified; the fee is reversed per policy                             │
│ [Recall request] (camt.056 — may be refused)                                 │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## 7. Screens — charges and pricing admin (D; TPTS pricing roles)

### D1 · Tariff plan editor with simulator (`chg.plan:*`, T2–T3, maker-checked)
```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Plan: Merchant Standard 2027   v4 DRAFT        [Submit] [Diff v3] [Simulate] │
├──────────────────────────────────────────────────────────────────────────────┤
│ card_service   FEE  payer: merchant                                          │
│   model: PERCENT  rate 2.75%  base: amount                                   │
│   modifiers: free allowance 20/month · min 1.00 · cap 250.00                 │
│   tax: VAT_STD (exclusive)   reversal: FULL   rounding: HALF_UP 0.01         │
│ withdrawal_fee FEE  model: TIERED_SLAB  0–3 free, then 5.00 flat             │
│ distributor_commission COMMISSION  split: 10 bps of net → payable (2-200-220)│
├──────────────────────────────────────────────────────────────────────────────┤
│ SIMULATOR  last 30 days replayed ────────────────┐  revenue +2.1%             │
│ orgs affected 1,204 · >10% move: 6 (list ▸)      │  floor breach: none        │
│ [Publish effective 01 Dec]  notice 30 days ✓ (T3)│  tax impact +N$1,844       │
└──────────────────────────────────────────────────────────────────────────────┘
```

### D2 · Plan assignment on the org tree ("what does org X pay and why")
```
PLATFORM default ──────────────────────────── card 2.75% · wd 5.00
  └ MERCHANT (org type) ────────────────────── (inherit)
      └ Acme Manufacturing (SUBTREE) ──────── card 2.40% (negotiated)
          └ Acme Dist (ORG) ───────────────── card INHERIT · wd EXCLUDE
              └ Kapana Grill (ORG+product:qr) card 2.10% (promo → 31 Dec)
  selected org: Kapana Grill ⇒ card 2.10% (promotion), wd 5.00 (platform)
  [Assign plan to subtree…]  manufacturer may set only its own charges (D-BK-5)
```

### D3 · Tax codes and determination (`chg.tax:*`)
```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Tax codes   VAT_STD 15% · VAT_ZERO · VAT_EXEMPT · WHT_SERVICES               │
│ ── determination (category × buyer × place × date) ────────────────────────  │
│ FINANCIAL_SERVICE  + any buyer   NA   → VAT_EXEMPT   (D-BK-2, adviser)       │
│ SOFTWARE_SUBSCRIPTION + VAT-reg  NA   → VAT_STD 15%                          │
│ GOODS             + any buyer    NA   → VAT_STD 15%                          │
│ effective from 01 Jan 2027 · maker-checked · [Simulate on history]           │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## 8. Screens — statements and tax documents (E)

### E1 · Merchant statement (evolves M28; lines from `chg.charge_lines`)
```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Statements   period: Nov 2026                    [Generate]  [⇩ PDF] [⇩ CSV] │
│ ───────┬──────────────────────────┬──────────┬─────────┬────────────────────  │
│ 26 Nov │ Card payment PAY-8f3a…   │  450.00  │         │                      │
│ 26 Nov │   card service fee 2.75% │          │   12.38 │ plan v4, allowance   │
│ 26 Nov │   VAT 15% (VAT_STD)      │          │    1.86 │ TI-2026-004318       │
│ 26 Nov │ Withdrawal WR-118        │          │ 5,005.75│ •••7781              │
├────────┴──────────────────────────┴──────────┴─────────┴────────────────────── │
│ closing wallet 21,930.00 · downloads valid 60 minutes (C04)                   │
└───────────────────────────────────────────────────────────────────────────────┘
```

### E2 · Tax invoice (gapless per book; VAT-registered orgs)
```
┌────────────────────────────────────────────┐
│ TAX INVOICE  TI-2026-004318   26 Nov 2026  │
│ TPTS (Pty) Ltd · VAT NA-1234567-01         │
│ Billed to: Acme Foods · TIN … · VAT …      │
│ card service fee 12.38 · VAT 15% 1.86      │
│ total 14.24 NAD                            │
└────────────────────────────────────────────┘
```

---

## 9. Screens — banking integration (F; the bank gateway is the only bank-facing part)

### F1 · Connectors and rails (`bank.connector:*`)
```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Banking › Connectors & rails                                                 │
│ ───────────┬──────────────┬──────────────┬────────────┬───────────────────── │
│ Connector  │ Type / rail  │ Environment  │ Health     │ Actions            │
│ Sponsor-A  │ SPONSOR_API  │ SANDBOX ✓cert│ ● ok 84ms  │ [Rotate creds]      │
│ NamClear   │ H2H_SFTP     │ PRODUCTION   │ ● ok       │ [Test link] [Conform]│
│ Acquirer-X │ ACQUIRER     │ PRODUCTION   │ ● degraded │ [Health] [Details]  │
│ SIM        │ SIM adapter  │ SANDBOX only │ ● ok       │ (refused in PROD)   │
├──────────────────────────────────────────────────────────────────────────────┤
│ capabilities per rail: instant · batch · status enquiry · recall · rtp ·     │
│ acct-verification · statements(camt.052/053/054, MT940) · cut-offs           │
│ auth: mTLS + OAuth private_key_jwt · API_KEY is refused for money rails      │
└──────────────────────────────────────────────────────────────────────────────┘
```

### F2 · Controls: kill switches, limits, calendar
```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Banking › Controls                                     payouts paused: OFF   │
│ kill switches: IPP [RUN] · EFT [RUN] · RTGS [RUN] · Acquirer-X [RUN]         │
│   (flip = T4, audited; in-flight unaffected, new orders queue)               │
│ limits: platform daily payout cap 2,000,000 · per-org 40,000 · per-beneficiary│
│ cut-offs & calendar: EFT 15:00 · IPP 24/7 · RTGS 09:00–16:00 · holidays (NA) │
│ recon gate: payouts from BANK-A paused — BALANCE_BREAK open (G6 ▸)           │
└──────────────────────────────────────────────────────────────────────────────┘
```

### F3 · Instruction log (every signed instruction)
```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Banking › Instructions            filter: rail ▾ state ▾ account ▾           │
│ ─────────┬───────────┬─────────┬──────────┬──────────────┬─────────────────── │
│ INS-7712 │ EFT 12,000│ •••7781 │ COMPLETED│ E2E-9c1f…    │ signed ✓ apprv'd ✓ │
│ INS-7713 │ EFT 3,200 │ •••9904 │ UNKNOWN ⏳│ E2E-33aa…    │ enquiry #2, SLA 12m│
│ INS-7709 │ EFT 900   │ •••3320 │ RETURNED │ pacs.004 AC06│ funds back ✓       │
├──────────────────────────────────────────────────────────────────────────────┤
│ row ▸ raw messages (WORM) · status timeline · recall · investigation         │
└──────────────────────────────────────────────────────────────────────────────┘
```

### F4 · Pre-funding and balances
```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Banking › Accounts                (camt.052 intraday / balance API)          │
│ Collection acct …1234  bank 1,204,330.00 │ ledger mirror ✓ 1,204,330.00      │
│   in-flight payouts −45,200.00           headroom 1,159,130.00  [banner if <]│
│ Payout acct   …7781  bank    302,110.00 │ ledger mirror ✓   302,110.00       │
└──────────────────────────────────────────────────────────────────────────────┘
```

### F5 · Gateway monitor (ops)
```
queues: submit 0 · enquiry 1 · statements 0 | webhook: last 14:33:02 ✓ sig valid
files: NamClear inbox seq 4471 ✓ pgp ✓ | acquirer settlement T+1 due 15:40
alerts: UNKNOWN rate 0.4% (< 1% ok) · IPP p95 1.9s · no signature failures 24h
```

### F6 · Platform sweeps (internal; TPTS)
```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Banking › Sweeps & revenue share                                             │
│ Day close 25 Nov: certified ✓ (G7) ⇒ sweep fee income 18,442.00 → operating  │
│ MiNdLABS revshare Nov: accrual 21,300.00 → [Payout] (4-eyes, after close)    │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## 10. Screens — reconciliation (G; engine per parent §9)

### G1 · Reconciliation dashboard (evolves T05)
```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Reconciliation                                          [Run checks now]     │
│ L1 instructions  98.2% final · 3 UNKNOWN (SLA ok)                            │
│ L2 statements    BANK-A ✓ day 25 · NamClear ✓ · Acquirer ⏳ due 15:40        │
│ L3 settlements   batch 4471: 99/100 matched (fee diff 1 ▸)                   │
│ L4 balances      BANK-A ✓ · BANK-B ✗ BALANCE_BREAK (critical, payouts paused)│
│ L5 safeguarding  wallets 4,120,900 = cash at bank 4,120,900 ✓ (report ▸)     │
│ L6 GL integrity  nightly ✓ no breaks                                         │
├──────────────────────────────────────────────────────────────────────────────┤
│ open breaks 14 (2 critical pinned) · auto-match rate 30d: 94.1%              │
│ while a critical break is open, payouts from that account pause              │
└──────────────────────────────────────────────────────────────────────────────┘
```

### G2 · Statements (ingestion: automatic + manual upload)
```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Recon › Statements                    [⇩ template] [Upload camt.053/MT940/CSV]│
│ ─────────┬──────────┬──────────┬────────────────────────────────────────────  │
│ BANK-A   │ camt.053 │ 25 Nov   │ ingested ✓ 412 lines · seq 4471 contiguous  │
│ BANK-B   │ MT940    │ 25 Nov   │ GAP — opening ≠ prior closing → break B-991 │
├──────────────────────────────────────────────────────────────────────────────┤
│ upload preview: file → normalise (ISO 20022) → continuity checks →           │
│ [Commit] (recon.statement:create) · raw file archived; no double ingest      │
└──────────────────────────────────────────────────────────────────────────────┘
```

### G3 · Matching rules (versioned, maker-checked, simulated)
```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Recon › Rules                          v7 DRAFT     [Simulate on history]    │
│ 1 exact id  EndToEndId/UETR = instruction id                    conf 1.00    │
│ 2 virtual account + amount + value date                         conf 0.98    │
│ 3 unique reference (invoice/QR/short code) + amount             conf 0.95    │
│ 4 batch  1 bank line = Σ N ledger entries                       conf 0.95    │
│ 5 split  N bank lines = 1 ledger entry                          conf 0.90    │
│ 6 fuzzy  ± amount tol, ± k days, name similarity                0.50–0.90    │
│ auto-match ≥ 0.95 commits · below → suggestion queue (D-PAY-6)               │
│ bank-charge lines → expense by rule (only posting allowed automatically)     │
└──────────────────────────────────────────────────────────────────────────────┘
```

### G4 · Suggestions review (auto-match below threshold)
```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Recon › Suggestions (23)                                                     │
│ bank line −12,000.00 •••7781 26 Nov ACME PAYROLL                             │
│   ↳ suggestion 0.91: WR-124 (12,000.00, rule 6 fuzzy name)  [Match] [Reject] │
└──────────────────────────────────────────────────────────────────────────────┘
```

### G5 · Manual workbench (side-by-side; 1:1 · 1:N · N:1 · split)
```
┌───────────────────────────────┬──────────────────────────────────────────────┐
│ BANK LINE (left)              │ LEDGER CANDIDATES (right, ranked)            │
│ credit +4,500.00 26 Nov       │ ▸ 0.98 UNMATCHED-CREDIT → wallet:org/a91…    │
│ ref "INV 77" (unknown ref)    │   0.55 invoice INV-77 of another org         │
│ [Allocate ▸] [Bank error]     │ actions: match · split · suspense            │
├───────────────────────────────┴──────────────────────────────────────────────┤
│ allocate to org a91… ⇒ draft journal DJ-88 (suspense → wallet)               │
│ [Submit] ⇒ a DIFFERENT person approves (recon.adjustment:authorize)          │
│ every action commented + audited; break history immutable                    │
└──────────────────────────────────────────────────────────────────────────────┘
```

### G6 · Breaks queue and detail
```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Recon › Breaks (14)   critical first: BALANCE_BREAK B-991 (BANK-B, day 25)   │
│ ────────┬─────────────────────┬──────────┬───────┬──────────────────────────  │
│ B-991   │ BALANCE_BREAK       │ BANK-B   │ 2 days│ → blocks payouts+close    │
│ B-988   │ UNMATCHED_BANK_CREDIT│ BANK-A  │ 1 day │ suspense 4,500.00 (G5)    │
│ B-984   │ MISSING_AT_BANK     │ INS-7710 │ 3 days│ enquiry escalated         │
│ B-979   │ SETTLEMENT_FEE_DIFF │ batch    │ 5 days│ finance review            │
├────────┴─────────────────────┴──────────┴───────┴──────────────────────────── │
│ detail: evidence ▸ · investigation case (camt.027/029) · resolve with reason │
└───────────────────────────────────────────────────────────────────────────────┘
```

### G7 · Day close certification (per bank account)
```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Recon › Day close · BANK-A · 25 Nov                                          │
│ [x] statements complete (L2)      [x] L4 balances agree                      │
│ [x] no critical breaks            [ ] suspense 4,500.00 > tolerance 0.00     │
│ ⇒ not certifiable until B-988 allocated (G5)                                 │
│ certified days allow: platform sweep (F6) + auto-payouts for that account    │
└──────────────────────────────────────────────────────────────────────────────┘
```

### G8 · Recon reports (all exportable, `gl.report:export` / `recon.*:read`)
```
· Break ageing (by type/account/owner)         · Auto-match rate trend (30d)
· Suspense ageing + balance vs tolerance       · Safeguarding report L5 (daily,
· Day-close status calendar (per account)        regulatory evidence, D-BK-1)
· Settlement fee diff log (acquirer vs charges)· VAT report (from tax control
· Instruction UNKNOWN/RETURN rate per rail       leaves, chg/gl §7.5)
```

---

## 11. Cells behind the screens (fixed verbs; parent §12.1 and bookkeeping §8.1)

| Screen | Cells |
|---|---|
| A1–A6 | `pay.order` (read, create, submit) · `pay.order:authorize` (refunds over band) · `chg.charge:read` (explain) |
| B1–B3 | `pay.order` (read, create, submit, execute) |
| C1–C4 | `pay.order` + `pay.beneficiary` (read, create, modify, authorize) · step-up for submit/approve |
| D1–D3 | `chg.plan`, `chg.charge_type`, `chg.assignment`, `chg.tax` (read, create, modify, submit, authorize, simulate) |
| E1–E2 | `gl.report`/`chg.charge` read + export · tax documents for VAT-registered orgs |
| F1–F6 | `bank.connector` (read, create, modify, authorize, execute, simulate) · `pay.limit`, `pay.rail` (modify, authorize) · platform staff only |
| G1–G8 | `recon.statement` (read, create, export) · `recon.match` (read, create, delete, authorize) · `recon.adjustment` (create, submit, authorize) · `recon.break` (read, modify, execute) · `recon.rule` (read, create, modify, authorize, simulate) |

SoD on every screen: preparer ≠ approver (journals, adjustments, payouts, beneficiaries, plans, rules); the creator of a break resolution can't close it alone above tolerance.

---

## 12. Gating and states on screens
- **Step-up** (RFC 9470) before: refund above band, payout submit/approve, beneficiary create/change, kill-switch flip (T4).
- **READ_ONLY**: a suspended org sees money screens read-only; no new orders (same rule as plugin gates).
- **Honest states**: every list maps state-machine states to plain words; `UNKNOWN` shows as "checking with the bank" with the enquiry count and SLA.
- **Simulator labelling**: in sandbox, SIM-adapter results carry a SIMULATED banner (M06's callback simulator moves behind the SIM adapter, PB1).

---

## 13. Phases (screens ride the parent phases)

| Screens | Phase |
|---|---|
| A1–A6, B1–B3 (quote → apply, new orchestrator states) | PB1 |
| G1–G8 (engine, ingestion, workbench, day close) | PB2 |
| F1–F6 (real connector: health, instructions, pre-funding, monitor) | PB3/PB4 |
| C1–C4 (beneficiary controls, approvals, limits UI) | PB5 |
| F6 sweeps + revenue share | PB7 |
| D1–D3, E1–E2 (charges governance, tax documents) | B3/B4/B5 (bookkeeping plan) |

Regression screens today (M06 simulator, T05 recon, M05) are replaced, not kept: the UAT ids (TC-ACC, TC-B2B, TC-SET, TC-RCN) keep their intent on the new screens.

---

## 14. Amendments made when accepting this document

1. `payments-and-banking-design.md` §9.5: workbench/widgets reference points here for the full screen set (G1–G8).
2. `bookkeeping-and-charges-design.md` §8.2 UI: the listed widgets are drawn here (A1/A4/A5, D1–D3, E1–E2, G-series); the plan editor includes the simulator panel shown in D1.
3. Accept-payment (M06) gains the **pre-confirm quote panel** (A1): `FeeService.preview` is currently an API with no caller — the quote becomes mandatory UI, served by `POST /api/charges/quote` with `quote_hash`.
4. Withdrawal (M05) and B2B pay (M16) gain the rail choice, value-date notice and limits display (B2, C1) before their state machines grow in PB1.
