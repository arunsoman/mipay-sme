# Plugin: Payroll

Pay staff from the org balance, with approvals and limits.

## Benefits of a plugin over building it into the core
- **High-risk code in its own box:** payroll runs only through the money interface, with second-person approval and limits.
- **Turned on only for orgs vetted for it:** the platform controls exactly who gets payroll.
- **Payroll and tax rules change often:** they can be versioned and released apart from the payment core.
- **Clear audit trail:** every payroll run belongs to one plugin and one org.
