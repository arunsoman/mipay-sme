"""UAT module definitions: required TC IDs (from docs/UAT-BACKEND-TEST-PACK.md traceability
matrix), business-case / storyboard references, and where each module is implemented."""

B = "backend/src/main/java/com/mipay/sme/"
UI = "frontend/src/app/features/"


def rng(prefix, a, b):
    return [f"TC-{prefix}-{i:02d}" for i in range(a, b + 1)]


MODULES = [
    {
        "key": "SET", "name": "Sandbox setup & configuration",
        "bc": "BC p.15–17 (tariffs, native-IPP exclusion), p.7 (limits)", "sb": "SB p.16 Days 1–5",
        "window": "Days 1–5",
        "required": rng("SET", 1, 31),
        "where": [
            ("Connectors / capability matrix", B + "service/ConnectorService.java", "/api/admin/connectors", "admin/connectors"),
            ("Tariffs, limits, rules, settings", B + "service/AdminService.java:63–187", "/api/admin/tariffs · /limits · /rules · /settings", "admin/config"),
            ("Seeded sandbox (tariffs, lender, rules)", B + "config/DataSeeder.java:95–226", "—", "—"),
            ("Synthetic history / scene runner", B + "service/SimulationService.java:164, 316", "/api/admin/sim/* · /scenes/run", "admin/simulator"),
        ],
    },
    {
        "key": "ONB", "name": "Merchant & supplier onboarding / approval",
        "bc": "BC p.18 ‘Merchant and supplier approval’; p.9 supplier onboarding", "sb": "SB p.8 Scene 1",
        "window": "Days 6–12",
        "required": rng("ONB", 1, 4),
        "where": [
            ("Register merchant / supplier", B + "service/OnboardingService.java:57, 86", "POST /api/onboarding/merchant · /supplier", "login (register)"),
            ("TPTS approval + provisioning (balance, QR, short code)", B + "service/OnboardingService.java:122, 175", "POST /api/ops/approvals/merchants/{id}", "ops/approvals"),
            ("Supplier bank-account change, 4-eyes approval", B + "service/OnboardingService.java:235, 267", "POST /api/supplier/bank-accounts/change · /api/ops/approvals/bank-accounts/{id}", "supplier/accounts · ops/approvals"),
            ("Unapproved merchant gate", B + "service/PaymentService.java:61", "all merchant money endpoints", "—"),
        ],
    },
    {
        "key": "ACC", "name": "Payment acceptance (QR, short code, card)",
        "bc": "BC p.18 ‘Acceptance and availability’, ‘Timeout and duplicate’", "sb": "SB p.9 Scene 2",
        "window": "Days 6–12",
        "required": rng("ACC", 1, 6),
        "where": [
            ("Accept / confirm / fail / refund", B + "service/PaymentService.java:74, 161, 214, 236", "POST /api/merchant/payments · /payments/{id}/simulate · /refund", "merchant/accept · merchant/payments"),
            ("Customer QR / short-code pay", B + "web/CustomerController.java:38, 57", "GET /api/customer/lookup · POST /api/customer/pay", "customer (simulator)"),
            ("Speaker confirmation log", B + "service/PaymentService.java:293", "GET /api/merchant/speaker-log", "merchant (dashboard)"),
        ],
    },
    {
        "key": "BAL", "name": "Merchant balance (available / pending / held / owed)",
        "bc": "BC p.7 balance controls; p.8 worked journey", "sb": "SB p.7",
        "window": "Days 6–12",
        "required": rng("BAL", 1, 5),
        "where": [
            ("Issuer ledger + merchant subledger posting", B + "service/LedgerService.java:57, 90, 102", "—", "—"),
            ("Balance view (four amounts)", B + "service/LedgerService.java:210, 224", "GET /api/merchant/balance", "merchant/balance · merchant (dashboard)"),
            ("Holds: create / convert / release / expire", B + "service/LedgerService.java:266–296", "—", "merchant/balance (Held tile)"),
        ],
    },
    {
        "key": "B2B", "name": "B2B stock purchase — pay supplier from balance",
        "bc": "BC p.9; p.18 ‘Pay from balance’, ‘Insufficient balance’, ‘Concurrent orders’", "sb": "SB p.10–11 Scenes 3–4",
        "window": "Days 13–20",
        "required": rng("B2B", 1, 10),
        "where": [
            ("Order to invoice (unique ref)", B + "service/B2BPaymentService.java:73, 111", "POST /api/merchant/orders · /api/supplier/orders/{id}/invoice", "merchant/orders · supplier/orders"),
            ("Pay from balance: lock, availability check, hold, book transfer/EFT", B + "service/B2BPaymentService.java:149–201", "POST /api/merchant/invoices/{id}/pay", "merchant/orders (Pay from balance)"),
            ("Timeout retry", B + "service/B2BPaymentService.java:244", "POST /api/merchant/invoices/{id}/retry-timeout", "merchant/orders"),
            ("Withdrawal (locked, capped, recon-gated)", B + "service/WithdrawalService.java:43–84", "POST /api/merchant/withdrawals", "merchant/balance"),
        ],
    },
    {
        "key": "REC", "name": "Verified records, consent & eligibility data",
        "bc": "BC p.10; p.18 ‘Advance eligibility’, ‘Consent and Khata’", "sb": "SB p.12 Scene 5",
        "window": "Days 21–25",
        "required": rng("REC", 1, 6),
        "where": [
            ("Consent grant / withdraw (named lender, purpose, fields, retention)", B + "service/AdvanceService.java:106, 130", "POST /api/merchant/advance/consent · /consents/{id}/withdraw", "merchant/advance"),
            ("Eligibility with exclusion rules (advance, self, circular)", B + "service/AdvanceService.java:209–290", "GET /api/merchant/advance/eligibility · /api/lender/records/{id}", "merchant/advance · lender"),
        ],
    },
    {
        "key": "ADV", "name": "Merchant cash advance (offer, fund, sales-based repayment)",
        "bc": "BC p.11; p.18 ‘Advance funding’, ‘Repayment mandate’", "sb": "SB p.11, p.13 Scene 6",
        "window": "Days 21–25",
        "required": rng("ADV", 1, 6),
        "where": [
            ("Assessment & offer (lesser-of cap)", B + "service/AdvanceService.java:164", "POST /api/merchant/advance/request · /api/lender/assess", "merchant/advance · lender"),
            ("Accept to fund (issuer-confirmed credit)", B + "service/AdvanceService.java:363, 378", "POST /api/merchant/advance/{id}/accept · /api/lender/advances/{id}/fund", "merchant/advance · lender"),
            ("Daily sweep of eligible sales; arrears pause", B + "service/AdvanceService.java:440, 484", "POST /api/lender/sweep", "lender/collections · lender/portfolio"),
        ],
    },
    {
        "key": "RCN", "name": "Reconciliation, fees & statements",
        "bc": "BC p.18 ‘Reconciliation’, ‘Fees and billing’", "sb": "SB p.14 Scene 7",
        "window": "Days 26–30",
        "required": rng("RCN", 1, 4),
        "where": [
            ("Three-ledger recon run, breaks, release gate", B + "service/ReconciliationService.java:42, 139, 154", "POST /api/ops/recon/run · /recon/breaks/{id}/resolve", "ops/recon"),
            ("Fees applied once / reversal", B + "service/FeeService.java:38, 61", "GET /api/ops/reports/fees", "ops/reports"),
            ("Statements", B + "service/StatementService.java", "GET/POST /api/merchant/statements · /download", "merchant/statements"),
        ],
    },
    {
        "key": "SEC", "name": "Security & recovery",
        "bc": "BC p.18 ‘Security and recovery’; p.28", "sb": "SB p.15 Scene 8",
        "window": "Days 26–30",
        "required": rng("SEC", 1, 6),
        "where": [
            ("JWT auth, role gates, tenant scoping", B + "security/SecurityConfig.java · security/JwtAuthFilter.java", "all /api/**", "core/guards.ts (role guards)"),
            ("Audit of privileged actions", B + "service/AuditService.java", "GET /api/ops/audit", "ops/audit"),
            ("Backups / restore tests (RTO/RPO)", B + "service/AdminService.java:286, 299", "POST /api/admin/backups · /restore-test", "admin/hygiene"),
            ("Credential rotation / API-client revoke", B + "service/AdminService.java:219–247, 349", "POST /api/admin/connectors/{id}/rotate · /api-clients/{id}/revoke", "admin/connectors"),
        ],
    },
    {
        "key": "OPS", "name": "Operations, merchant tools & compliance consoles",
        "bc": "BC p.6 merchant tools; p.13 governance; p.28 risks", "sb": "SB p.9; Addendum 1",
        "window": "Days 6–30",
        "required": rng("OPS", 1, 30),
        "where": [
            ("Inventory / khata / tabs", B + "service/InventoryService.java · web/MerchantController.java:312–523", "/api/merchant/inventory · /khata · /tabs", "merchant/inventory · /khata · /tabs"),
            ("Ops desk: complaints, disputes, suspensions, incidents", B + "service/OpsService.java", "/api/ops/complaints · /disputes · /suspend · /incidents", "ops/desk · ops/incidents"),
            ("AML / SAR / DSAR", B + "service/ComplianceService.java:64–187", "/api/admin/compliance/*", "admin/compliance"),
            ("Settlement & fee reports, deployments, backups", B + "service/ReportingService.java · AdminService.java:249–330", "/api/ops/reports/* · /api/admin/deployments", "ops/reports · admin/hygiene"),
        ],
    },
    {
        "key": "PERF", "name": "Performance & service targets",
        "bc": "BC p.18 UAT exit: 20 TPS × 30 min; 99.9%; 15-min ack", "sb": "SB p.17",
        "window": "Days 26–30",
        "required": rng("PERF", 1, 3),
        "where": [
            ("Health / availability report", B + "web/AdminController.java:297", "GET /api/admin/health", "admin"),
            ("Incident SLA", B + "service/OpsService.java", "POST /api/ops/incidents/{id}/ack", "ops/incidents"),
            ("Load generator", "docs/load-test.py · TUat13PerformanceTests", "—", "—"),
        ],
    },
    {
        "key": "SCENE", "name": "Storyboard scenes 1–8 (demonstration runner)",
        "bc": "BC p.17 demonstration script", "sb": "SB p.8–15 Scenes 1–8",
        "window": "Days 26–30",
        "required": [f"Scene {i}" for i in range(1, 9)],
        "where": [
            ("Scene runner (labelled SIMULATED)", B + "service/SimulationService.java:298, 316", "GET /api/admin/scenarios · POST /api/admin/scenes/run", "admin/simulator"),
        ],
    },
    {
        "key": "RACE", "name": "Concurrency / race-condition safety (supplementary)",
        "bc": "BC p.18 ‘Concurrent orders’, ‘Timeout and duplicate’; p.28 double-spend risk", "sb": "SB p.15",
        "window": "Days 26–30",
        "required": rng("RACE", 1, 13),
        "where": [
            ("Pessimistic balance lock", B + "repository/BalanceAccountRepository.java:20", "—", "—"),
            ("Idempotent ledger posting", B + "service/LedgerService.java:57", "—", "—"),
        ],
    },
]

# BC p.18 acceptance tests -> TC IDs that evidence them
BC_ACCEPTANCE = [
    ("Merchant and supplier approval", "Unapproved parties blocked; verified receiving account changes require independent approval.",
     ["TC-ONB-01", "TC-ONB-02", "TC-ONB-03", "TC-ONB-04", "TC-OPS-26", "TC-SET-16"]),
    ("Acceptance and availability", "Payment confirmed once; pending card proceeds never increase spendable funds prematurely.",
     ["TC-ACC-01", "TC-ACC-02", "TC-ACC-03", "TC-BAL-01", "TC-BAL-02"]),
    ("Pay from balance", "MSME authorises invoice in TPTS; atomic debit, supplier credit and invoice match use one transaction reference.",
     ["TC-B2B-01", "TC-B2B-02", "Scene 3", "TC-RACE-07"]),
    ("Insufficient balance", "Purchase refused or separately approved/funded advance offered; no hidden overdraft.",
     ["TC-B2B-05", "TC-B2B-06", "Scene 5", "TC-RACE-04"]),
    ("Concurrent orders", "Two purchases cannot both spend the same available amount. Holds and their release reconcile.",
     ["TC-B2B-08", "TC-BAL-03", "TC-ACC-06", "TC-RACE-02", "TC-RACE-12"]),
    ("Timeout and duplicate", "Pending preserved; no duplicate debit, supplier credit or stock adjustment after retries.",
     ["TC-ACC-04", "TC-ACC-05", "TC-B2B-03", "TC-B2B-04", "TC-B2B-07", "TC-RACE-01", "Scene 2", "Scene 4"]),
    ("Advance eligibility", "Loan proceeds, own transfers and circular B2B activity excluded from verified sales.",
     ["TC-REC-05", "TC-ADV-06", "TC-SET-27"]),
    ("Advance funding", "Approval alone adds no funds; actual lender transfer and issuer confirmation required.",
     ["TC-ADV-01", "TC-ADV-02", "TC-RACE-03", "TC-RACE-08", "TC-RACE-09"]),
    ("Repayment mandate", "Only eligible receipts and remaining debt used; refund, zero-sales and maturity cases handled correctly.",
     ["TC-ADV-03", "TC-ADV-04", "TC-ADV-05", "TC-SET-25", "TC-RACE-05"]),
    ("Consent and Khata", "Data shared only for agreed purpose; customer-level Khata lending feed excluded by default.",
     ["TC-REC-02", "TC-REC-03", "TC-REC-04", "TC-SET-07", "TC-OPS-29", "TC-OPS-30"]),
    ("Reconciliation", "Issuer assets/liabilities, merchant subledger, B2B transfer and credit ledger match with no unexplained differences.",
     ["TC-RCN-01", "TC-RCN-02", "TC-BAL-05", "TC-SET-23", "TC-OPS-13", "TC-RACE-06", "TC-RACE-11", "Scene 7"]),
    ("Fees and billing", "Apply the agreed tariff once; reconcile operator, interchange and sponsor charges; no unauthorised merchant debit.",
     ["TC-RCN-03", "TC-SET-10", "TC-SET-11", "TC-SET-12", "TC-SET-13", "TC-SET-14", "TC-SET-15", "TC-OPS-14"]),
    ("Security and recovery", "Cross-merchant access prevented; privileged actions logged; restore and ledger recovery tested.",
     ["TC-SEC-01", "TC-SEC-02", "TC-SEC-03", "TC-SEC-04", "TC-SEC-06", "TC-OPS-21", "TC-OPS-22", "TC-OPS-24"]),
]

# BC p.17 demonstration script
DEMO = [
    ("Onboard Ndapewa’s shop, Selma’s café and synthetic Kamati Wholesale (labelled fictional)", ["TC-ONB-01", "TC-ONB-02", "Scene 1", "TC-SET-31"]),
    ("Accept a verified customer payment; show available and pending separately", ["TC-ACC-01", "TC-ACC-03", "TC-BAL-01", "TC-BAL-02"]),
    ("Pay a N$6,000 supplier invoice from balance incl. timeout and duplicate callback", ["TC-B2B-02", "TC-B2B-03", "TC-B2B-04", "Scene 3", "Scene 2", "Scene 4"]),
    ("Show 90–120 days synthetic data, eligibility calc, consent and lender review", ["TC-SET-30", "TC-SET-20", "TC-REC-01", "TC-REC-02", "TC-SET-24"]),
    ("Fund a N$10,000 advance, spend it on stock, collect only the authorised share", ["TC-ADV-02", "TC-ADV-03", "TC-B2B-06", "Scene 6"]),
]

# BC p.17 delivery windows -> modules
WINDOWS = [
    ("Days 1–5", "Agreed bank/issuer route, journeys, balance fields, advance model, gap register", ["SET"]),
    ("Days 6–12", "Merchant onboarding, QR/card simulator, available/pending balance and reconciled ledger", ["ONB", "ACC", "BAL"]),
    ("Days 13–20", "Supplier invoice, pay-from-balance, holds, duplicate controls and withdrawal", ["B2B"]),
    ("Days 21–25", "Verified-data summary, consent, lender decision, funded advance, repayment simulation", ["REC", "ADV"]),
    ("Days 26–30", "Negative cases, reconciliation, fixes, acceptance evidence, demonstration", ["RCN", "SEC", "PERF", "SCENE", "RACE"]),
]

# Money & security critical modules (BC p.18: must pass 100%)
CRITICAL = {"ACC", "BAL", "B2B", "ADV", "RCN", "SEC", "RACE"}
