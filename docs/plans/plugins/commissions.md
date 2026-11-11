# Plugin: Commissions & Incentives

Distributor commission on its merchants' sales, and agent incentives (e.g. per POS terminal deployed).

## Benefits of a plugin over building it into the core
- **Uses the tree for attribution:** "who sold under whom" comes from the org tree instead of a separate mapping.
- **Pays out only through the money interface:** idempotency, no-overdraft and audit rules are enforced by the core.
- **Rules per network:** each manufacturer sets its own commission scheme.
- **Risk contained:** commission bugs can't reach payment processing.
