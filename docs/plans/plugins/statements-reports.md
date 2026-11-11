# Plugin: Statements & Reports

Periodic statements and operational reports for an org's own data, plus roll-up reports for parents when a child shares.

## Benefits of a plugin over building it into the core
- **Read-only:** it only reads ledgers and events, so it can't corrupt money data.
- **Scheduling and exports per org:** formats, periods and recipients are configured per org instead of hard-coded.
- **Heavy queries kept apart:** report generation can later move to a read replica without changing the core.
- **Can be sold in tiers:** basic statements for everyone, advanced reports as a paid grant.
