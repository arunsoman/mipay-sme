"""Addendum 1 screen inventory mapped to the live app (checked against every screen on 2026-11-09).

Each screen: (id, name, status, role, route, highlight_text, what the app has / lacks)
status: A = available, P = partial, N = not built. role/route None when not built.
"""
A, P, N = "AVAILABLE", "PARTIAL", "NOT BUILT"
ADM, OPS, MER, LEN, SUP, PUB = "MINDLABS_ADMIN", "TPTS_OPS", "MERCHANT", "LENDER", "SUPPLIER", "PUBLIC"

GROUPS = [
 ("A.1", "Bank or licensed issuer connector", [
  ("E01", "Connector list", A, ADM, "/admin/connectors", "NDB Issuer", "Lists the NDB issuer and card-gateway connectors with type, environment, status, last check and latency."),
  ("E02", "Connector detail", P, ADM, "/admin/connectors", "NDB Issuer", "Name, type, environment and status only. No base URL, TLS certificate, timeout or retry settings."),
  ("E03", "Connector capability matrix", P, ADM, "/admin", "Go-live exclusions", "Only a note that native IPP B2B is excluded (book transfer and EFT only). No capability matrix."),
  ("E04", "Account mapping", N, None, None, None, "No screen maps merchant balance accounts to issuer account references."),
  ("E05", "Fund-flow boundary settings", N, None, None, None, "No screen for the legal balance holder or fund-flow boundary."),
  ("E06", "Callback / webhook configuration", P, ADM, "/admin/connectors", "Rotate secret", "‘Rotate secret’ exists. No endpoint, signing method or replay-window settings."),
  ("E07", "Test connection / health check", A, ADM, "/admin/connectors", "Test (C08)", "‘Test’ runs a health check and records latency."),
 ]),
 ("A.2", "Lender adapter", [
  ("E10", "Lender list", N, None, None, None, "The lender is seeded; there is no lender list screen. The merchant only sees it in the consent drop-down."),
  ("E11", "Lender adapter configuration", N, None, None, None, "No assessment, disbursement or repayment-notification endpoint settings."),
  ("E12", "Consent template configuration", N, None, None, None, "Consent purpose is fixed (‘Cash advance assessment’); no template screen."),
  ("E13", "Decision mapping", N, None, None, None, "No mapping of lender decision codes."),
  ("E14", "Disbursement confirmation", N, None, None, None, "No ‘Fund’ / disbursement-confirmation action for the lender or ops (see UAT 2.8)."),
  ("E15", "Repayment mandate configuration", P, ADM, "/admin/config", "Business rules", "Cap rule R3 and the limit profile are configurable; the 10% collection rate is fixed and there is no hardship setting."),
 ]),
 ("A.3", "Payment rails and other connectors", [
  ("E20", "IPS / switch connector", N, None, None, None, "Only the issuer and card-gateway connectors exist; no IPS/switch connector."),
  ("E21", "Card acquirer connector", A, ADM, "/admin/connectors", "Acquirer Card Gateway", "Card gateway connector listed; card payments stay PENDING until confirmed."),
  ("E22", "QR / short-code service configuration", P, MER, "/merchant/settings", "Receive channels", "Merchant sees its QR payload and short code. No admin configuration of QR spec or short-code range."),
  ("E23", "KYC / identity provider configuration", N, None, None, None, "KYC results are recorded at approval, but there is no provider configuration screen."),
  ("E24", "Notification gateway configuration", N, None, None, None, "No SMS/WhatsApp gateway or sender-ID settings."),
  ("E25", "Sponsor / IPSP settings", P, ADM, "/admin/config", "qr_sponsorship", "Only the sponsor fee (qr_sponsorship N$0.10, UNQUOTED) is configurable."),
  ("E26", "Vendor concentration view", N, None, None, None, "No dependency / concentration view."),
 ]),
 ("A.4", "Fee, tariff and pricing configuration", [
  ("C01", "Fee tariff list", A, ADM, "/admin/config", "Fee tariffs", "Tariffs listed with type, value, external cost and basis."),
  ("C02", "Fee tariff detail", P, ADM, "/admin/config", "card_service", "Value, external cost and basis only; no effective dates or ‘paid by’. Card fee is 1.5%, BC says 2.50%."),
  ("C03", "IPN / sponsor charge configuration", A, ADM, "/admin/config", "ips_switching", "Switching N$0.40 labelled PROVISION and sponsor N$0.10 labelled UNQUOTED."),
  ("C04", "Billing preview", N, None, None, None, "No ‘cost of a N$60 payment’ calculator."),
  ("C05", "Fee application log", A, OPS, "/ops/reports", "Fee report", "Fee report per day: billed, external cost and contribution."),
 ]),
 ("A.5", "Limits, holds and controls", [
  ("C10", "Merchant limit profile", A, ADM, "/admin/config", "Limit profiles", "Max balance, daily debit cap, withdrawal cap, advance cap and arrears % per merchant."),
  ("C11", "Hold policy configuration", N, None, None, None, "Hold duration and release rules are not configurable."),
  ("C12", "Supplier payment limits", N, None, None, None, "No per-invoice / per-day supplier limits."),
  ("C13", "Advance product configuration", A, ADM, "/admin/config", "R3", "Rule R3: cap = min(N$25,000, 20% of average monthly receipts, lender limit)."),
  ("C14", "Arrears trigger configuration", A, ADM, "/admin/config", "Limit profiles", "Arrears % (default 5) in the limit profile and rule R4 (no open arrears case)."),
 ]),
 ("A.6", "Reconciliation and data rules", [
  ("C20", "Reconciliation rule set", N, None, None, None, "Match keys and tolerances are not configurable."),
  ("C21", "Reconciliation schedule", N, None, None, None, "Reconciliation runs on demand; no schedule or cut-off screen."),
  ("C22", "Break category configuration", P, ADM, "/admin/simulator", "Inject a reconciliation break", "Two break types can be injected (missing credit, amount mismatch); categories are not configurable."),
  ("C23", "Eligible-receipt rules", A, ADM, "/admin/config", "exclude_self_transfer", "Exclusion rules for advance proceeds, self-transfers, circular B2B, refunds and Khata, each can be toggled."),
  ("C24", "Data-retention rules", P, ADM, "/admin/config", "statement_retention", "Only statement retention is shown as a setting; no consent or audit retention."),
 ]),
 ("A.7", "Approval workflow configuration", [
  ("C30", "Merchant approval workflow", P, OPS, "/ops/approvals", "Merchant approvals", "The approval queue works; the workflow itself (who approves) is not configurable."),
  ("C31", "Supplier approval workflow", P, OPS, "/ops/approvals", "Supplier approvals", "Queue works; not configurable."),
  ("C32", "Bank-account-change workflow", P, OPS, "/ops/approvals", "Bank account changes", "Rule ‘approver ≠ requester’ is enforced, but Ops currently cannot approve PENDING_APPROVAL changes (UAT 2.1)."),
  ("C33", "Suspension rights", P, OPS, "/ops/desk", "Suspensions", "Ops can suspend and lift; who may suspend is not configurable."),
 ]),
 ("A.8", "Notification and content templates", [
  ("C40", "SMS/WhatsApp templates", P, ADM, "/admin/config", "Notification templates", "Template table exists but is empty and has no form to add one."),
  ("C41", "Speaker confirmation text", N, None, None, None, "No speaker-confirmation text setting."),
  ("C42", "Consent text templates", N, None, None, None, "No consent text template screen."),
  ("C43", "Statement template", N, None, None, None, "Statements can be generated, but the template is not configurable."),
 ]),
 ("A.9", "Simulation and environment controls", [
  ("E30", "Environment selector", P, ADM, "/admin", "SANDBOX/UAT", "A global SANDBOX/UAT ‘SIMULATED’ banner is on every page; there is no environment selector."),
  ("E31", "Simulation control panel", P, ADM, "/admin/simulator", "Bank simulator controls", "Bank events (confirm, duplicate, timeout, late confirm, fail) are available; lender controls (approve, decline, fund, zero-sales) are not."),
  ("E32", "Synthetic data generator", A, ADM, "/admin/simulator", "Generate 120d history", "‘Generate 120d history’ creates synthetic trading data."),
  ("E33", "Scenario runner", P, ADM, "/admin/simulator", "Scenario library", "Three run buttons; the scenario table shows ‘No scenarios loaded’ and ‘Run advance flow’ fails (UAT 2.8)."),
  ("E34", "Label audit", A, OPS, "/ops/audit", "SIMULATED", "Every bank and lender event in the audit trail carries the SIMULATED flag."),
 ]),
 ("A.10", "Access, tenancy and roles", [
  ("E40", "Tenant setup", N, None, None, None, "No tenant setup screen."),
  ("E41", "Role and permission matrix", N, None, None, None, "Roles are enforced by route guards; there is no permission matrix screen."),
  ("E42", "Maker–checker rules", P, OPS, "/ops/approvals", "DIFFERENT approver", "Independence is enforced for bank changes; not configurable."),
  ("E43", "Device and session policy", P, ADM, "/admin/access", "Access console", "Sessions per user with revoke; MFA policy shown as a setting; no session-expiry or device-binding settings."),
  ("E44", "Security settings", P, ADM, "/admin/config", "mfa_policy", "MFA policy setting only; privileged actions are logged in the audit trail."),
  ("E45", "Recovery configuration", P, ADM, "/admin/hygiene", "Backups & restore tests", "Backups can be taken; no restore-test action or RTO/RPO settings."),
 ]),
 ("B.1", "Merchant tools", [
  ("M30", "Inventory list", A, MER, "/merchant/inventory", "Stock items", "Items with unit, stock, reorder level and state; add items."),
  ("M31", "Inventory item detail", P, MER, "/merchant/inventory", "Record movement", "Movements (sale, delivery, adjustment, waste) are recorded on the list page; no per-item detail page."),
  ("M32", "Stock alerts", P, MER, "/merchant/inventory", "REORDER AT", "Low-stock state shown in the list; no separate alerts screen or reorder prompt."),
  ("M33", "Reorder rules", P, MER, "/merchant/inventory", "Reorder at", "Reorder level only; no preferred supplier or order quantity."),
  ("M34", "Khata list", A, MER, "/merchant/khata", "Customer accounts", "Customer credit accounts with outstanding amounts; create customer. (Finding #3: a Khata API call returned 500 in testing.)"),
  ("M35", "Khata detail", N, None, None, None, "No Khata entry / detail page."),
  ("M36", "Tab creation", A, MER, "/merchant/tabs", "Create tab", "Create a tab with customer, amount, purpose and due date."),
  ("M37", "Tab reminder", P, MER, "/merchant/tabs", "Open tabs", "The page says ‘one tap to settle or remind’; the action only appears once a tab exists (not seen in this capture)."),
  ("M38", "Sales reports", P, MER, "/merchant", "SALES TODAY", "Today's sales tile only; no daily/weekly/monthly report."),
  ("M39", "Profit view", P, MER, "/merchant", "profit", "Today's profit figure on the dashboard only."),
  ("M40", "Receipt printing", N, None, None, None, "No print/share receipt."),
  ("M41", "Speaker confirmation log", N, None, None, None, "No speaker confirmation log."),
  ("M42", "QR code management", P, MER, "/merchant/settings", "QR:", "QR payload shown; no generate/rotate."),
  ("M43", "Short-code management", P, MER, "/merchant/settings", "Short code:", "Short code shown; no test dial (a customer can pay it on /customer)."),
  ("M44", "Card terminal setup", N, MER, "/merchant/accept", "Card terminal", "Accept page shows ‘Card terminal: -’; there is no pairing screen."),
  ("M45", "Device management", A, MER, "/merchant/settings", "Trusted devices", "Trusted devices with MFA method and expiry."),
  ("M46", "Offline mode", N, None, None, None, "No offline queue."),
  ("M47", "Settings", P, MER, "/merchant/settings", "Settings", "Receive channels and devices; no business profile, notification or consent settings."),
 ]),
 ("B.2", "TPTS operations console", [
  ("T10", "Complaint management", A, OPS, "/ops/desk", "Complaints", "Log, assign to me, resolve."),
  ("T11", "Dispute / chargeback", A, OPS, "/ops/desk", "Disputes", "Open a dispute against a payment; resolve for or against."),
  ("T12", "Settlement reports", A, OPS, "/ops/reports", "Settlement cycle", "Daily settlement cycle of record."),
  ("T13", "Fee reports", A, OPS, "/ops/reports", "Fee report", "Fees billed, external cost and contribution."),
  ("T14", "Statement generation", P, MER, "/merchant/statements", "Generate statement", "Merchants generate their own statements; Ops has no statement screen."),
  ("T15", "Suspension management", A, OPS, "/ops/desk", "Suspensions", "Suspend a merchant or supplier with a reason; lift."),
  ("T16", "Incident management", A, OPS, "/ops/incidents", "Open + historical incidents", "Log, acknowledge (15-min late flag), resolve."),
  ("T17", "Merchant support", P, OPS, "/ops/approvals", "Merchant approvals", "Ops can review a merchant's KYC from the approval queue; no general merchant support view."),
  ("T18", "Supplier support", P, OPS, "/ops/approvals", "Supplier approvals", "Approval queue only; no supplier support view."),
  ("T19", "Audit trail viewer", A, OPS, "/ops/audit", "Audit trail", "Searchable audit with actor, time and SIMULATED flag."),
  ("T20", "Reporting dashboard", N, None, None, None, "No active-merchant / B2B usage / retention dashboard."),
 ]),
 ("B.3", "Lender portal", [
  ("L10", "Portfolio dashboard", A, LEN, "/lender/portfolio", "OUTSTANDING", "Outstanding, owed and arrears tiles plus the advances table."),
  ("L11", "Arrears management", A, LEN, "/lender/portfolio", "Arrears", "Arrears cases with days past due and status."),
  ("L12", "Vintage analysis", P, LEN, "/lender/portfolio", "COHORT", "Cohort column and portfolio snapshot; no loss-by-cohort analysis."),
  ("L13", "Disbursement history", P, LEN, "/lender/portfolio", "FUNDED", "Funded date column in the advances table; no separate history."),
  ("L14", "Repayment history", A, LEN, "/lender/collections", "Collections", "Collections per day with the eligible-sales base; run the daily sweep."),
  ("L15", "Stress test view", N, None, None, None, "No stress-test view."),
 ]),
 ("B.4", "Mindlabs admin console", [
  ("X10", "API management", A, ADM, "/admin/hygiene", "API clients", "Create API clients with rate limits; revoke."),
  ("X11", "Environment management", P, ADM, "/admin", "MiPay console overview", "Environment posture on the overview; deployments target UAT or PROD. No environment management."),
  ("X12", "Deployment management", A, ADM, "/admin/hygiene", "Deployments", "Record deployments and roll back."),
  ("X13", "Monitoring dashboard", P, ADM, "/admin", "LOAD TEST", "Database, load-test TPS and open critical incidents; no availability or latency."),
  ("X14", "Backup and restore", P, ADM, "/admin/hygiene", "Backup now", "Backups work; no button to run a restore test."),
  ("X15", "Test data management", P, ADM, "/admin/simulator", "Generate 120d history", "Generate synthetic history; no data-set list or reset."),
  ("X16", "Sandbox management", P, ADM, "/admin/simulator", "Bank simulator controls", "Default bank-simulator scenario; no sandbox reset."),
  ("X17", "Documentation", P, None, None, None, "API docs are served by the backend at http://localhost:8080/swagger-ui/index.html; not linked from the console."),
 ]),
 ("B.5", "Customer-facing touchpoints", [
  ("CU01", "Payment receipt (SMS/WhatsApp)", P, PUB, "/customer", "Customer payment simulator", "The customer simulator shows the payment result; no SMS/WhatsApp receipt."),
  ("CU02", "Tab reminder", N, None, None, None, "No customer-side tab reminder."),
  ("CU03", "Advance notification", N, None, None, None, "No advance notification."),
 ]),
 ("B.6", "Compliance and data protection", [
  ("K01", "KYC / AML checks", P, OPS, "/ops/approvals", "KYC", "KYC status per merchant and sanctions result on approval; no separate KYC screen."),
  ("K02", "Transaction monitoring", A, ADM, "/admin/compliance", "AML monitoring alerts", "Velocity alerts raised automatically."),
  ("K03", "Suspicious activity report (SAR)", A, ADM, "/admin/compliance", "Suspicious activity reports", "Draft a SAR from an alert and submit it."),
  ("K04", "Data subject access request (DSAR)", A, ADM, "/admin/compliance", "Data subject access requests", "Create a DSAR; completed ones export the merchant's data."),
  ("K05", "Consent withdrawal", N, MER, "/merchant/advance", "Grant a consent", "Consent can be granted; there is no withdraw button on screen."),
  ("K06", "Data retention", P, ADM, "/admin/config", "statement_retention", "Statement retention setting only."),
  ("K07", "Audit access", P, OPS, "/ops/audit", "Audit trail", "Ops can read the audit trail; no regulator access role."),
 ]),
]


def stats(screens):
    a = sum(1 for s in screens if s[2] == A)
    p = sum(1 for s in screens if s[2] == P)
    return a, p, len(screens) - a - p
