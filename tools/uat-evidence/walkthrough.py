"""Manual live-page walkthrough for each Business Case UAT item.

Each step: (role, route, what to do, what you should see to PASS)."""

BASE = "http://localhost:4200"

LOGINS = [
    ("Merchant (Ndapewa's shop)", "MERCHANT", "0811111111", "OTP 123456 (sandbox shows it on screen)", "Merchant OTP login (default tab)"),
    ("Supplier (Kamati Wholesale)", "SUPPLIER", "0852222222", "supplier123", "Staff login"),
    ("Lender", "LENDER", "0813333333", "lender123", "Staff login"),
    ("TPTS Operations", "TPTS_OPS", "0827777777", "ops123", "Staff login"),
    ("Mindlabs Admin", "MINDLABS_ADMIN", "0819999999", "admin123", "Staff login"),
]

ROLE_SHORT = {"MERCHANT": "Merchant", "SUPPLIER": "Supplier", "LENDER": "Lender",
              "TPTS_OPS": "TPTS Ops", "MINDLABS_ADMIN": "Admin", "PUBLIC": "No login"}

# BC p.18 acceptance tests, in the same order as BC_ACCEPTANCE in modules.py
BC_STEPS = {
    "Merchant and supplier approval": [
        ("TPTS_OPS", "/ops/approvals",
         "Open the Merchant, Supplier and Bank-account-change queues. Approve one pending merchant.",
         "Pending parties are listed and cannot trade until approved. The approved merchant moves out of the queue."),
        ("SUPPLIER", "/supplier/accounts",
         "Submit a receiving-account change.",
         "The change shows PENDING. In /ops/approvals a different person (Ops) can approve it. "
         "Current build: Ops sees ‘already PENDING_APPROVAL’ and no Approve button (see the recording)."),
    ],
    "Acceptance and availability": [
        ("MERCHANT", "/merchant/accept",
         "Create a N$60 QR payment, then press CONFIRM in the callback simulator.",
         "Status CONFIRMED once. On /merchant/balance, Available goes up by N$60."),
        ("MERCHANT", "/merchant/accept",
         "Create a N$120 CARD payment. Do not confirm it yet. Open /merchant/balance.",
         "The Pending tile shows N$120 and Available is unchanged. Available rises only after CONFIRM. "
         "Known gap: QR payments do not show in Pending before confirmation."),
        ("PUBLIC", "/customer",
         "Pay the merchant's short code (from /merchant/accept) as a customer.",
         "The payment succeeds and appears once in /merchant/payments."),
    ],
    "Pay from balance": [
        ("MERCHANT", "/merchant/orders", "Order 1 unit from the Kamati catalogue.", "The order is listed as SUBMITTED."),
        ("SUPPLIER", "/supplier/orders", "Press ‘Issue invoice’ on that order.", "One invoice with a unique reference is issued."),
        ("MERCHANT", "/merchant/orders", "Select the invoice and press ‘Pay via EFT’ or ‘Pay via book transfer’.",
         "Invoice PAID. On /merchant/balance there is one debit under Wallet movement, and the supplier's invoice detail shows one payment."),
    ],
    "Insufficient balance": [
        ("MERCHANT", "/merchant/orders",
         "Try to pay an invoice larger than Available. If needed, first withdraw on /merchant/balance to bring Available down.",
         "Refused with a shortfall message. Available does not go negative, and no hold or debit is created."),
    ],
    "Concurrent orders": [
        ("MERCHANT", "/merchant/balance",
         "Start an invoice payment and watch the Held tile.",
         "Held rises and Available falls by the same amount. The hold converts to a debit, or is released if the payment fails. "
         "True simultaneous spending cannot be checked by eye: rely on TC-RACE-02, which currently FAILS."),
    ],
    "Timeout and duplicate": [
        ("MERCHANT", "/merchant/accept",
         "Create a payment and press TIMEOUT, then CONFIRM, then DUPLICATE.",
         "After TIMEOUT it stays PENDING. CONFIRM completes it. DUPLICATE is ignored, and Available rises only once."),
        ("MERCHANT", "/merchant/payments", "Open that payment.", "The event trail shows a single confirmation and the duplicate marked as ignored."),
    ],
    "Advance eligibility": [
        ("MERCHANT", "/merchant/advance",
         "Read the ‘2. Eligibility’ card.",
         "‘Rules applied’ lists exclude_advance, exclude_self_transfer and exclude_circular. The eligible total excludes them."),
        ("MINDLABS_ADMIN", "/admin/config", "Under Business rules, view the exclusion rules.", "The exclusion rules are listed and can be toggled. Each change is audited."),
    ],
    "Advance funding": [
        ("MERCHANT", "/merchant/advance", "Grant consent, request an assessment, then press ‘Accept offer’.",
         "Status ACCEPTED. Available on /merchant/balance does NOT change."),
        ("MINDLABS_ADMIN", "/admin/simulator", "Press ‘Run advance flow’.",
         "Every step is green: consent, assess, offer, fund (issuer confirms), sweep. Funds become available only at the fund step. "
         "UI gap: there is no ‘Fund’ button for the lender or merchant, and in the current build this button fails with "
         "‘Unknown scene advance_flow’ (see the recording), so funding cannot currently be shown in the UI."),
    ],
    "Repayment mandate": [
        ("LENDER", "/lender/collections", "Press ‘Run daily sweep’.",
         "It collects 10% of that day's eligible sales. A zero-sales day collects nothing, and running it again the same day collects nothing more."),
        ("MERCHANT", "/merchant/advance", "View ‘3. Current advance’.", "The repayments table matches the sweep, and the amount owed falls by the collected amount."),
    ],
    "Consent and Khata": [
        ("MERCHANT", "/merchant/advance", "Grant a data consent.", "The named lender, purpose, data fields and retention are shown. It can be withdrawn."),
        ("LENDER", "/lender", "Open the merchant's records.",
         "Records are visible only after consent and contain no customer-level Khata. After consent is withdrawn they are refused."),
    ],
    "Reconciliation": [
        ("TPTS_OPS", "/ops/recon", "Press ‘Run reconciliation’.", "A clean run: issuer, subledger and credit-ledger totals match, with 0 breaks."),
        ("MINDLABS_ADMIN", "/admin/simulator", "Inject a reconciliation break, then re-run reconciliation on /ops/recon.",
         "The break is flagged, and a withdrawal on /merchant/balance is BLOCKED until it is resolved."),
    ],
    "Fees and billing": [
        ("TPTS_OPS", "/ops/reports", "Load the fee report for today.",
         "Each fee appears once per event, with external cost and contribution shown separately."),
        ("MINDLABS_ADMIN", "/admin/config", "Review the Fee tariffs.",
         "Switching N$0.40 and sponsor N$0.10 are labelled as provisions, and IPS P2M is N$0. "
         "Check the card fee: it is seeded at 1.50%, while BC p.16 says 2.50%."),
    ],
    "Security and recovery": [
        ("MERCHANT", "/ops/approvals", "While logged in as the merchant, open this ops link.",
         "You are redirected to /merchant. Merchants cannot reach ops pages."),
        ("TPTS_OPS", "/ops/audit", "Tick ‘Simulated events only’, then clear it.", "Privileged actions are listed with actor, time and before/after values."),
        ("MINDLABS_ADMIN", "/admin/hygiene", "Press ‘Backup now’, then run a restore test.", "The restore test is recorded with RTO under 2 h and RPO = 0."),
    ],
}

DEMO_STEPS = [
    [("TPTS_OPS", "/ops/approvals"), ("SUPPLIER", "/supplier/catalogue")],
    [("MERCHANT", "/merchant/accept"), ("MERCHANT", "/merchant/balance")],
    [("MERCHANT", "/merchant/orders"), ("SUPPLIER", "/supplier/orders")],
    [("MINDLABS_ADMIN", "/admin/simulator"), ("MERCHANT", "/merchant/advance"), ("LENDER", "/lender")],
    [("MINDLABS_ADMIN", "/admin/simulator"), ("LENDER", "/lender/collections")],
]

EXIT_STEPS = [
    ("Service targets: health and load", "MINDLABS_ADMIN", "/admin",
     "The health tiles show the database up, the load-test TPS (gate: 20 TPS) and 0 open critical incidents. "
     "The 99.9% availability figure is only in the health API (TC-PERF-02), not on this page."),
    ("Critical incident acknowledged within 15 minutes", "TPTS_OPS", "/ops/incidents",
     "Log an incident and acknowledge it. It is not flagged late."),
    ("Every bank and lender event labelled SIMULATED", "TPTS_OPS", "/ops/audit",
     "The sandbox banner is visible and simulated events carry the SIMULATED flag."),
]
