# MiPay plugins

Each plugin is a self-contained module. The platform turns it on per org with a grant:
- `SELF` applies to that org only.
- `SUBTREE` applies to that org and every org below it, including orgs onboarded later.

The framework is defined in [../inventory-plugin.md](../inventory-plugin.md) §1. It depends on the org hierarchy work.

## Why plugins instead of building features into the core
- **Turned on per org:** a feature only reaches the orgs that need it, or that pay for it.
- **Inherited down the tree:** one `SUBTREE` grant gives a manufacturer's whole network the feature, including orgs onboarded later.
- **Isolated code:** a plugin can't break payments or the ledgers. A boundary test enforces this.
- **Isolated data:** each plugin has its own tables, migrations and API path, so it can be removed, versioned and tested on its own.
- **Safe to switch off:** revoking a grant makes the data read-only and deletes nothing.
- **One permission model:** org roles, suspension and the second-person rule apply the same way in every plugin.

## Plugins
| Plugin | Group |
|---|---|
| [Inventory](../inventory-plugin.md) | Operations |
| [Notifications](notifications.md) | Platform |
| [Khata (customer credit book)](khata.md) | Commercial |
| [Tabs](tabs.md) | Commercial |
| [Statements & Reports](statements-reports.md) | Finance |
| [Field Service](field-service.md) | Operations |
| [Asset Lifecycle](asset-lifecycle.md) | Operations |
| [Delivery & Routes](delivery-routes.md) | Operations |
| [Procurement](procurement.md) | Operations |
| [Pricing & Promotions](pricing-promotions.md) | Commercial |
| [Loyalty](loyalty.md) | Commercial |
| [Catalogue Syndication](catalogue-syndication.md) | Commercial |
| [Commissions & Incentives](commissions.md) | Finance |
| [Expenses & Petty Cash](expenses.md) | Finance |
| [Payroll](payroll.md) | Finance |
| [Recurring Billing](recurring-billing.md) | Finance |
| [Accounting Export](accounting-export.md) | Finance |
| [KYC Periodic Review](kyc-review.md) | Compliance |
| [Document Vault](document-vault.md) | Compliance |
| [Audit Viewer](audit-viewer.md) | Compliance |
| [Partner Webhooks & API](partner-webhooks.md) | Platform |
| [White-label Branding](white-label.md) | Platform |
| [Analytics](analytics.md) | Platform |
