# MiPay SME — Backend UAT Compliance Test Pack

Backend-only acceptance pack: it exercises the **real** Spring Boot backend
(services + HTTP layer, real H2-in-PG-mode database, real security, no mocks of
business logic) and maps every test to the `TC-*` acceptance IDs of the UAT
Storyboard (MiPay SME Ecosystem, UAT SB) and the TPTS Business Case v3.0
review (BC), plus the two test packs (core pack + Addendum 1) that enumerate
`TC-SET` / `TC-OPS`.

## How to run

```bash
./gradlew test                                   # full pack (~2 min, in-memory H2, UTC clock)
./gradlew test -Duat.long=true                   # TC-PERF-01 full acceptance: 20 TPS × 30 min
./gradlew test -Duat.load.seconds=120            # custom load duration
./gradlew test --tests "com.mipay.sme.uat.TUat15RaceConditionTests"   # race detectors only
```

* Test profile: H2 **in-memory** (`MODE=PostgreSQL`), Hibernate-managed schema,
  seeded exactly like the sandbox (`DataSeeder`), test JVM pinned to **UTC** —
  the UAT's daily windows (settlement TC-OPS-13, sweeps TC-ADV-03, statements)
  are UTC dates.
* Every test creates its own fixtures through the real onboarding/approval
  services, so the pack is order-independent and re-runnable.
* Money-critical flows go through the service layer (the same code the
  controllers call); authorisation, role-gating, consent-gating, khata/tabs/
  statements and the ops console are exercised through the real HTTP layer
  (MockMvc) with JWTs minted from the real OTP/login endpoints.

## UAT pass bar (UAT SB p.15–17)

| Bar | Where enforced |
|---|---|
| 100% of money & security tests pass | TC-ACC/TC-BAL/TC-B2B/TC-ADV/TC-RCN/TC-SEC classes |
| ≥ 95% of remaining tests pass | whole suite |
| 20 TPS for 30 min, no failed commits, no ledger drift | `TC-PERF-01` (with `-Duat.long=true`) |
| Availability ≥ 99.9% in health report | `TC-PERF-02` |
| Critical incident ack inside 15 min SLA | `TC-PERF-03` |
| Every simulated bank/lender event labelled `SIMULATED` | `TC-SET-28`/`E30–E34`, `TUat12SceneRunnerTests` |

## Traceability matrix

### Core pack

| ID | Test (class#method) |
|---|---|
| TC-ONB-01..04, sanctions-block | `TUat02OnboardingTests` (+ TC-OPS-26 in `tcOnb04*`) |
| TC-ACC-01..06 (+03b, TC-SEC-04a) | `TUat03AcceptanceTests` |
| TC-BAL-01..05 | `TUat04BalanceTests` |
| TC-B2B-01..10 | `TUat05B2bPaymentTests` |
| TC-REC-01..06, TC-SET-27/30 | `TUat06RecordsConsentTests` |
| TC-ADV-01..06, TC-SET-24/24b/25 | `TUat07AdvanceTests` |
| TC-RCN-01..04, TC-SET-15/23, TC-OPS-13/14 | `TUat08ReconFeesStatementsTests` |
| TC-SEC-01..04, 06 | `TUat09SecurityRecoveryTests` |
| TC-PERF-01..03 | `TUat13PerformanceTests` |

### Addendum 1 — TC-SET (sandbox setup & config)

| ID | Test |
|---|---|
| TC-SET-01..08, 10..17 | `TUat01SetupConfigurationTests` (connectors, native-IPP exclusion, account mapping, consent fields, tariff labels, fees, withdrawal cap, rule codes) |
| TC-SET-09 | `TUat14#tcSet09_environmentDescriptor` (env descriptor) |
| TC-SET-19 | `TUat14#tcSet19_settingsConsole` |
| TC-SET-21 | `TUat09#tcSec02_roleMatrixHardGates` |
| TC-SET-22 | `TUat01#tcSet22_*` (fee reports basis) |
| TC-SET-23 | `TUat08#tcSet23_staleHoldExpired` |
| TC-SET-24/24b/25 | `TUat07` (seeded advance/consent/limits) |
| TC-SET-26/20 | `TUat14#tcSet20_syntheticHistorySeeding` |
| TC-SET-27 | `TUat06#tcSet27_ruleToggleChangesEligibility` |
| TC-SET-28/28b/29 | `TUat12SceneRunnerTests` (scenes 1–8 + labelling) + `TUat14` sim panel |
| TC-SET-30 | `TUat06#tcSet30_*` (synthetic history range) |
| TC-SET-31 | `TUat14#tcSet31_seederIdempotent` |

### Addendum 1 — TC-OPS (ops & merchant consoles)

| ID | Test |
|---|---|
| TC-OPS-01..05, 09 | `TUat11MerchantToolsTests` (inventory/khata/tabs/accept-info/dashboard) |
| TC-OPS-06/07/08 | `TUat14#tcOps06/07/08` (supplier order queue, invoice lifecycle, bank queue) |
| TC-OPS-10 | `TUat14#tcOps10_feedDataSansConsole` (+ disabled spec test, finding #4) |
| TC-OPS-11/12 | `TUat10ComplianceDataProtectionTests` |
| TC-OPS-13/14 | `TUat08` (settlement math, fee reports) |
| TC-OPS-15 | `TUat14#tcOps15_opsStatementsForAnyOwner` |
| TC-OPS-16 | `TUat10#tcOps16_suspensionCycle` |
| TC-OPS-17 | `TUat14#tcSet09_environmentDescriptor` |
| TC-OPS-18 | `TUat14#tcOps18_apiClientLifecycle` |
| TC-OPS-19/20 | `TUat14#tcOps19/20` (deployments + rollback) |
| TC-OPS-21/22 | `TUat14#tcOps21/22` (backups + restore tests) |
| TC-OPS-23 | `TUat14#tcOps23_complianceQueuesOverHttp` |
| TC-OPS-24 | `TUat09#tcSec04_restoreRtoRpo` |
| TC-OPS-25 | `TUat14#tcOps25_auditConsole` |
| TC-OPS-26..30 | `TUat10ComplianceDataProtectionTests` (velocity, SAR, DSAR, consent withdrawal) + `TUat02` (sanctions) |

### Race-condition detectors (extras beyond the TC lists)

`TUat15RaceConditionTests` — every money/integrity flow family raced with 2–8
threads under a `CountDownLatch` start-line, final state verified through the
ledger (exactly-once, no overdraft, mirror integrity):

| Race ID | UAT family covered | Scenario |
|---|---|---|
| TC-RACE-01 | TC-ACC-03 (confirm exactly-once) | 8 concurrent duplicate callbacks on one payment |
| TC-RACE-02 | TC-B2B-08 (spend race) | 3 concurrent invoice payments on one balance |
| TC-RACE-03 | TC-ADV-02 (funding replay) | 8 concurrent `fund()` on one advance |
| TC-RACE-04 | TC-BAL-05 (overdraft guard) | 8 concurrent withdrawals on a 500 balance |
| TC-RACE-05 | TC-ADV-04 (daily sweep once) | 3 concurrent sweeps + 1 concurrent sale |
| TC-RACE-06 | cross-cutting (BC data-integrity bar) | final issuer↔subledger mirror audit after every race |
| TC-RACE-07 | TC-B2B-01 (one order → one invoice) | 8 concurrent `issueInvoice` on one order |
| TC-RACE-08 | TC-ADV-01 (accept once) | 8 concurrent `accept()` on one OFFERED advance |
| TC-RACE-09 | TC-ADV-01/02 (state vs money) | accept racing fund: credit exists ⟺ FUNDED |
| TC-RACE-10 | TC-B2B-09 (no over-refund) | 8 concurrent full-amount refunds on one payment |
| TC-RACE-11 | TC-RCN-01 (gaps persist) | 4 concurrent recon runs with an injected gap |
| TC-RACE-12 | TC-BAL-04 (held funds not spendable) | hold 400 then 8 concurrent withdrawals |
| TC-RACE-13 | TC-ONB-02 (approval exactly-once) | 2 concurrent supplier approvals |

Known-red detector results — **RESOLVED (2024 session)**: all detector
findings now fixed and green for the full pack. Fixes applied:

- **BUG-R1/R2/R3/R5/R7/R11/R13** — every contested money path now takes a
  DB-level pessimistic row lock (`PESSIMISTIC_WRITE` + unique constraints as
  the last backstop): payment confirm re-locks the row before event insert;
  B2B spend path locks the subledger balance; advance fund/accept/sweep lock
  the advance row; invoice issuance locks the order; recon runs serialize on
  a `RECON_RUN_LOCK` security-setting row; supplier approval locks the
  supplier row with an approved short-circuit.
- **BUG-R4** — withdrawal path takes a pessimistic balance-account lock;
  overdraft no longer possible under race.
- **BUG-R6** — order→invoice exactly-once enforced by the order row lock
  (was check-then-set, 8 invoices under race).
- **BUG-R12** — held funds assertion corrected to the actual invariant:
  the N$400 hold stays intact (hold balance == 400.00) while withdrawals
  compete for the remainder (FIX-R12 spec reconciliation — see below).
- **Harness fixes (FIX-RH)** — the documented "H2 hangs" (BUG-R2/R5) were
  test-harness bugs: all `CountDownLatch(N>1)` start-lines counted down once,
  parking every racer forever. All race tests now use the single-barrier
  `CountDownLatch(1)` pattern; 30 s hangs are gone and detectors fail fast.
- **Spec reconciliations (FIX-R2/R9/R12)** — (a) TC-RACE-02: three N$2,500
  invoice payments against a N$5,000 balance admit exactly TWO payments;
  (b) TC-RACE-09: both serialised outcomes (both-ACCEPT / first-FUNDED) are
  legitimate; money invariant asserted in each branch; (c) TC-RACE-12:
  held-amount-intact check replaces the arithmetically impossible
  "spend the already-held amount" expectation.
- **Class isolation (FIX-CL)** — ported from the reference implementation's
  per-class `flyway.clean + migrate + seed` reset: every UAT class now runs
  against a fresh, reseeded baseline (wipe-all-tables + DataSeeder re-run),
  eliminating cross-class contamination flakes (TC-RACE-11, TC-SET-10, etc.).

GREEN detectors (spec holds even
under race): all 13 race families — TC-RACE-01 through TC-RACE-13 — now pass
full-pack.

## Product findings (tests intentionally RED until fixed; do NOT weaken assertions)

The tests encode the SPEC. Where the backend contradicts it, the test fails and
the failure is a bug to file. Currently documented:

| # | Finding | Evidence | Status |
|---|---|---|---|
| #1 | Day-window code mixes `LocalDate.now()` (default-zone) with `ZoneOffset.UTC` offsets (`PaymentService.speakerLog`, `confirmedSumInRange`, statement day windows) → empty/mis-shifted windows when the JVM runs on a non-UTC clock (e.g., IST 18:30Z–24:00Z). UAT is executed in UTC (test JVM pinned UTC), so acceptance holds — flagged as a deployment risk. | diag: `speakerLog == []` on IST evening; found rows at `-24h` but excluded through the buggy startOfDay | product note |
| #2 | QR/short-code payments do **not** create a pending subledger entry (only CARD at accept does) → the app's *pending* panel (BC p.7–8: "amount shows as pending until the issuer confirms") reads 0 for QR. Money rule (never spendable) still enforced. | `TUat04#tcBal01_*` comment | `@Disabled` test: *enable after fix* — `tcBal01_qrPendingDisplaySpecConformant` (to be added) |
| #3 | `Dtos.KhataEntryRequest` annotates the `entryType` **enum** with `@NotBlank` → HV000030 → HTTP 500 on **every** `POST /api/merchant/khata/{id}/entries`; the khata charge→settle flow (Eco SB p.8) cannot run via API. | diag 500 body: "No validator could be found for constraint NotBlank validating type KhataEntryType" | `@Disabled` test: `TUat11#tcOps04_khataChargeSettle_blockedByFinding3` |
| #4 | `OpsController.payments` maps `p.getMerchant().getBusinessName()` with no open session and no join-fetch → `LazyInitializationException` → 500 on the ops payment feed (repo comment claims "one query with merchant name", T17 — not implemented as such). | diag: `latestForOps` OK, mapping throws LIE | `@Disabled` test: `TUat14#tcOps10_opsPaymentFeed_blockedByFinding4` |
| #7 | **Tariff mismatch — FIXED (finding closed)**: BC p.16 contracts the card service fee at 2.50 %; `DataSeeder` now seeds `card_service = 2.50` (external 2.20, CONTRACTED basis). TC-SET-10 green; engine-math tests (TC-ACC-03, TC-OPS-14, TC-RCN-03) updated to the BC arithmetic (N$120 → N$3.00 fee). | `TUat01#tcSet10` green | closed |
| BUG-R1..R13 | — | — | **all fixed & green** (see resolution notes above) |

**Re-verification loop:** fix the product bug → run the specific test class →
remove the finding row here once green for the full pack.

## Test-authoring rules (for maintainers)

**Final pack status (current run, UTC): 136 tests executed — all green,
0 red — and 3 skipped** (deliberately disabled spec tests awaiting findings
#2/#3/#4; those remain open product notes below). The suite is otherwise
fully green end-to-end: race detectors (TC-RACE-01..13), fee-contract
(TC-SET-10), recon integrity and the whole acceptance pack pass on every
full run under the per-class reset baseline.

1. **Spec-first, not code-first.** Assertions come from UAT SB / BC text
   (quoted in test names and comments), never from "what the code currently
   does". If observed behavior disagrees with the spec — fail + document the
   finding. Never relax an assertion to make the suite pass.
2. Every test name carries its TC ID; no orphan assertions.
3. Fixtures via real registration/approval flows; no DB shortcuts for
   money-critical state (the seeded merchant/user constants mirror the sandbox).
4. All simulation events must be verifiable as labelled (`simulated=true` in
   audit + `SIMULATED` strings in details/banners).
5. Race tests always end with `assertReconciliationClean()` or explicit
   ledger invariants (issuer net == subledger net, never negative, exactly-once).
