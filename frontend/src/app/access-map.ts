/**
 * Route → permission map for the SME app (RBAC grid, fapi2-design App G).
 *
 * Every shell route lists the permission ROWS that unlock it (OR lists). Row-level
 * means: any held verb on the row opens the feature area - grant "manage inventory"
 * (merchant.inventory:create) and the inventory system appears, operable to the
 * extent of the granted cells. The same map drives:
 *  - the nav sidebar (an item shows when its rows are held), and
 *  - the route guards (deep links without the permission land on /no-access).
 * Individual buttons inside screens gate on the exact cells ("row:verb") via *mpPerm.
 */
export const ROUTE_PERMS: Record<string, string[]> = {
  // ---------------- merchant
  '/merchant': ['merchant.account'],
  '/merchant/accept': ['merchant.payment'],
  '/merchant/payments': ['merchant.payment'],
  '/merchant/payments/:id': ['merchant.payment'],
  '/merchant/balance': ['merchant.account'],
  '/merchant/orders': ['merchant.order'],
  '/merchant/inventory': ['merchant.inventory'],
  '/merchant/khata': ['merchant.khata', 'merchant.tab'],
  '/merchant/tabs': ['merchant.tab'],
  '/merchant/advance': ['merchant.advance'],
  '/merchant/statements': ['merchant.statement'],
  '/merchant/settings': ['merchant.account'],

  // ---------------- supplier
  '/supplier': ['supplier.account'],
  '/supplier/catalogue': ['supplier.catalogue'],
  '/supplier/orders': ['supplier.order', 'supplier.invoice'],
  '/supplier/invoices/:id': ['supplier.invoice'],
  '/supplier/accounts': ['supplier.bank_account'],
  '/supplier/statements': ['supplier.statement'],

  // ---------------- lender
  '/lender': ['lender.advance', 'lender.assessment'],
  '/lender/portfolio': ['lender.portfolio'],
  '/lender/collections': ['lender.collection'],

  // ---------------- ops (platform staff)
  '/ops/approvals': [
    'ops.kyc_check', 'ops.merchant_approval', 'ops.supplier_approval',
    'ops.bank_account_approval', 'ops.onboarding_request',
  ],
  '/ops/recon': ['ops.recon'],
  '/ops/incidents': ['ops.incident'],
  '/ops/desk': ['ops.complaint', 'ops.dispute'],
  '/ops/reports': ['ops.report'],
  '/ops/audit': ['ops.audit'],

  // ---------------- platform admin (superset persona)
  '/admin': ['admin.access'],
  '/admin/connectors': ['admin.connector'],
  '/admin/config': ['admin.config'],
  '/admin/simulator': ['admin.simulation'],
  '/admin/access': ['admin.access'],
  '/admin/compliance': ['admin.sar', 'admin.aml_alert', 'admin.dsar'],
  '/admin/hygiene': ['admin.platform_ops'],
  '/inventory': ['admin.access'],
};
