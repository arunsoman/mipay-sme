/** Business navigation for role design. A screen is grantable only when its
 * backend RBAC resources are registered; planned screens remain visible here
 * so product scope is explicit without issuing permissions that do not exist. */
export type BusinessPortal = 'PLATFORM' | 'LENDER' | 'MERCHANT' | 'MANUFACTURER' | 'DISTRIBUTOR' | 'SUPPLIER' | 'CUSTOMER';
export interface PermissionScreen { id: string; label: string; resources: string[] }
export interface PermissionMenu { id: string; label: string; screens: PermissionScreen[] }

const screen = (id: string, label: string, ...resources: string[]): PermissionScreen => ({ id, label, resources });
const menu = (id: string, label: string, ...screens: PermissionScreen[]): PermissionMenu => ({ id, label, screens });
const shared = [
  menu('onboarding', 'Onboarding',
    screen('application', 'Organization application', 'org.onboarding', 'kyc.application'),
    screen('documents', 'KYC and documents', 'kyc.application', 'kyc.task'),
    screen('status', 'Verification status', 'kyc.application'),
    screen('first-admin', 'First administrator setup')),
    menu('team', 'Team and access',
    screen('people', 'People and invitations', 'org.user'),
    screen('roles', 'Roles and assignments', 'rbac.role', 'rbac.assignment'),
      screen('security', 'Security settings')),
  menu('help', 'Help and support', screen('cases', 'Support cases', 'ops.complaint')),
];

export const PORTAL_PERMISSION_CATALOGUE: Record<BusinessPortal, PermissionMenu[]> = {
  // The admin console (docs/plans/admin-console.md, 2026-11-15): three menus, nothing else.
  // Kept ids (organizations/directory, access/users, access/roles) keep their menu cells.
  PLATFORM: [
    menu('organizations', 'Organizations', screen('directory', 'All organizations', 'admin.org_directory')),
    menu('kyc', 'KYC', screen('applications', 'Applications', 'kyc.application'),
      screen('reviews', 'Review queue', 'kyc.task')),
    menu('access', 'People and access', screen('users', 'Platform users', 'org.user'),
      screen('roles', 'Roles and permissions', 'rbac.role')),
  ],
  LENDER: [
    menu('overview', 'Overview', screen('dashboard', 'Lender dashboard', 'lender.portfolio')),
    menu('programmes', 'Funding programmes', screen('products', 'Products and eligibility'),
      screen('limits', 'Funding limits')),
    menu('applications', 'Applications', screen('pipeline', 'Application pipeline', 'lender.advance'),
      screen('borrower', 'Borrower onboarding and consent', 'lender.assessment'),
      screen('documents', 'Application documents')),
    menu('underwriting', 'Underwriting', screen('assessment', 'Assessments', 'lender.assessment'),
      screen('decisions', 'Credit decisions', 'lender.assessment')),
    menu('offers', 'Offers and funding', screen('offers', 'Offers', 'lender.advance'),
      screen('disbursement', 'Disbursements', 'lender.advance')),
    menu('portfolio', 'Portfolio', screen('accounts', 'Loan accounts', 'lender.portfolio'),
      screen('performance', 'Portfolio performance', 'lender.portfolio')),
    menu('repayments', 'Repayments', screen('schedule', 'Schedules'),
      screen('receipts', 'Receipts', 'lender.collection')),
    menu('collections', 'Collections', screen('queue', 'Collection queue', 'lender.collection'),
      screen('cases', 'Collection cases', 'lender.collection')),
    menu('risk', 'Risk and exceptions', screen('watchlist', 'Watchlist'), screen('exceptions', 'Exceptions')),
    menu('settlement', 'Settlement and ledger', screen('settlements', 'Settlements'), screen('ledger', 'Ledger')),
    menu('reports', 'Reports', screen('portfolio', 'Portfolio reports', 'lender.portfolio'),
      screen('collections', 'Collections reports', 'lender.collection')),
    ...shared,
  ],
  MERCHANT: [
    menu('home', 'Home', screen('dashboard', 'Business dashboard', 'merchant.account')),
    menu('collect', 'Collect payments', screen('accept', 'Accept payment', 'merchant.payment'),
      screen('links', 'Payment links'), screen('refunds', 'Refunds', 'merchant.payment')),
    menu('transactions', 'Transactions', screen('payments', 'Payments', 'merchant.payment'),
      screen('disputes', 'Disputes', 'ops.dispute')),
    menu('money', 'Money', screen('balance', 'Balance', 'merchant.account'),
      screen('withdrawals', 'Withdrawals', 'merchant.withdrawal'), screen('statements', 'Statements', 'merchant.statement')),
    menu('suppliers', 'Suppliers and purchases', screen('catalogue', 'Supplier catalogue', 'merchant.order'),
      screen('orders', 'Purchase orders', 'merchant.order'), screen('invoices', 'Supplier invoices', 'merchant.invoice')),
    menu('stock', 'Stock', screen('inventory', 'Inventory', 'merchant.inventory'), screen('adjustments', 'Stock adjustments', 'merchant.inventory')),
    menu('customers', 'Customers and tabs', screen('customers', 'Customers', 'merchant.khata'),
      screen('tabs', 'Tabs', 'merchant.tab'), screen('collections', 'Tab collections', 'merchant.khata')),
    menu('financing', 'Financing', screen('applications', 'Cash advance applications', 'merchant.advance'),
      screen('offers', 'Offers', 'merchant.advance'), screen('repayments', 'Repayments', 'merchant.advance')),
    menu('reports', 'Reports', screen('sales', 'Sales reports', 'merchant.statement')),
    ...shared,
  ],
  MANUFACTURER: [
    menu('home', 'Home', screen('dashboard', 'Manufacturer dashboard')),
    menu('products', 'Products and prices', screen('catalogue', 'Product catalogue', 'supplier.catalogue'),
      screen('price-lists', 'Price lists')),
    menu('network', 'Distributor network', screen('directory', 'Distributors', 'org.tree'),
      screen('onboarding', 'Add distributor', 'org.onboarding')),
    menu('sales', 'Sales orders', screen('orders', 'Orders', 'supplier.order'), screen('returns', 'Returns')),
    menu('fulfillment', 'Fulfillment', screen('pick-pack', 'Pick and pack'), screen('delivery', 'Delivery tracking')),
    menu('invoices', 'Invoices and receivables', screen('invoices', 'Invoices', 'supplier.invoice'),
      screen('receivables', 'Receivables', 'supplier.statement')),
    menu('money', 'Money and reports', screen('accounts', 'Receiving accounts', 'supplier.bank_account'),
      screen('statements', 'Statements', 'supplier.statement')),
    ...shared,
  ],
  DISTRIBUTOR: [
    menu('home', 'Home', screen('dashboard', 'Distributor dashboard')),
    menu('buying', 'Buying', screen('catalogue', 'Manufacturer catalogue', 'supplier.catalogue'),
      screen('orders', 'Purchase orders', 'supplier.order')),
    menu('inventory', 'Inventory', screen('stock', 'Stock'), screen('transfers', 'Stock transfers')),
    menu('selling', 'Selling', screen('catalogue', 'Sales catalogue', 'supplier.catalogue'),
      screen('orders', 'Customer orders', 'supplier.order')),
    menu('fulfillment', 'Fulfillment', screen('delivery', 'Deliveries'), screen('returns', 'Returns')),
    menu('invoices', 'Invoices', screen('invoices', 'Invoices', 'supplier.invoice'),
      screen('receivables', 'Receivables', 'supplier.statement')),
    menu('money', 'Money', screen('accounts', 'Receiving accounts', 'supplier.bank_account'),
      screen('statements', 'Statements', 'supplier.statement')),
    menu('network', 'Network and reports', screen('directory', 'Retailer network', 'org.tree'),
      screen('reports', 'Sales reports', 'supplier.statement')),
    ...shared,
  ],
  SUPPLIER: [
    menu('home', 'Home', screen('dashboard', 'Supplier dashboard', 'supplier.account')),
    menu('buying', 'Buying', screen('purchase-orders', 'Purchase orders'), screen('receipts', 'Goods received')),
    menu('catalogue', 'Catalogue and stock', screen('items', 'Catalogue', 'supplier.catalogue'),
      screen('stock', 'Stock')),
    menu('orders', 'Merchant orders', screen('queue', 'Order queue', 'supplier.order'),
      screen('detail', 'Order detail', 'supplier.order')),
    menu('fulfillment', 'Fulfillment', screen('dispatch', 'Dispatch'), screen('returns', 'Returns')),
    menu('invoices', 'Invoices', screen('invoices', 'Invoices', 'supplier.invoice'),
      screen('payments', 'Invoice payments', 'supplier.invoice')),
    menu('money', 'Money', screen('accounts', 'Receiving accounts', 'supplier.bank_account'),
      screen('statements', 'Statements', 'supplier.statement')),
    menu('network', 'Merchant network and reports', screen('merchants', 'Merchant network', 'org.tree'),
      screen('reports', 'Sales reports', 'supplier.statement')),
    ...shared,
  ],
  CUSTOMER: [
    menu('pay', 'Pay', screen('checkout', 'Checkout'), screen('payment-methods', 'Payment methods')),
    menu('activity', 'Activity', screen('payments', 'Payments'), screen('receipts', 'Receipts')),
    menu('issues', 'Issues', screen('disputes', 'Raise an issue'), screen('support', 'Support cases')),
    menu('account', 'Account', screen('profile', 'Profile'), screen('security', 'Security')),
  ],
};

/** Screens with a real route and matching workflow today. Everything else in
 * the target catalogue is intentionally labelled unavailable in the builder. */
export const LIVE_SCREEN_ROUTES: Record<string, string> = {
  'PLATFORM/organizations/directory': '/organizations',
  'PLATFORM/kyc/applications': '/kyc/applications', 'PLATFORM/kyc/reviews': '/kyc/reviews',
  'PLATFORM/access/users': '/access/users', 'PLATFORM/access/roles': '/rbac',
  'MANUFACTURER/network/directory': '/network', 'MANUFACTURER/network/onboarding': '/onboarding',
  'DISTRIBUTOR/network/directory': '/network',
  'SUPPLIER/home/dashboard': '/supplier', 'SUPPLIER/catalogue/items': '/supplier/catalogue',
  'SUPPLIER/orders/queue': '/supplier/orders', 'SUPPLIER/orders/detail': '/supplier/orders',
  'SUPPLIER/invoices/invoices': '/supplier/orders', 'SUPPLIER/invoices/payments': '/supplier/orders',
  'SUPPLIER/money/accounts': '/supplier/accounts', 'SUPPLIER/money/statements': '/supplier/statements',
  'SUPPLIER/network/merchants': '/network',
  'LENDER/overview/dashboard': '/lender', 'LENDER/applications/pipeline': '/lender',
  'LENDER/applications/borrower': '/lender', 'LENDER/underwriting/assessment': '/lender',
  'LENDER/portfolio/accounts': '/lender/portfolio', 'LENDER/portfolio/performance': '/lender/portfolio',
  'LENDER/collections/queue': '/lender/collections', 'LENDER/collections/cases': '/lender/collections',
  'LENDER/reports/portfolio': '/lender/portfolio', 'LENDER/reports/collections': '/lender/collections',
  'MERCHANT/home/dashboard': '/merchant', 'MERCHANT/collect/accept': '/merchant/accept',
  'MERCHANT/transactions/payments': '/merchant/payments',
  'MERCHANT/money/balance': '/merchant/balance', 'MERCHANT/money/withdrawals': '/merchant/balance',
  'MERCHANT/money/statements': '/merchant/statements',
  'MERCHANT/suppliers/catalogue': '/merchant/orders', 'MERCHANT/suppliers/orders': '/merchant/orders',
  'MERCHANT/suppliers/invoices': '/merchant/orders',
  'MERCHANT/stock/inventory': '/merchant/inventory', 'MERCHANT/stock/adjustments': '/merchant/inventory',
  'MERCHANT/customers/customers': '/merchant/khata', 'MERCHANT/customers/tabs': '/merchant/tabs',
  'MERCHANT/customers/collections': '/merchant/khata',
  'MERCHANT/financing/applications': '/merchant/advance', 'MERCHANT/financing/offers': '/merchant/advance',
  'MERCHANT/financing/repayments': '/merchant/advance', 'MERCHANT/reports/sales': '/merchant/statements',
  ...Object.fromEntries((['MANUFACTURER', 'DISTRIBUTOR', 'SUPPLIER'] as const).flatMap((p) => [
    [`${p}/onboarding/application`, '/onboarding'], [`${p}/onboarding/documents`, '/kyc'],
    [`${p}/onboarding/status`, '/onboarding'], [`${p}/team/people`, '/users'],
  ])),
};

export const AVAILABLE_SCREEN_KEYS = new Set(Object.keys(LIVE_SCREEN_ROUTES));

/**
 * Menu cells (docs/plans/role-menu-builder.md, 2026-11-14): every submenu has its own RBAC
 * row "nav.<portal>_<menu>_<screen>" with one verb, read. A role switches a submenu on by
 * holding that cell. The cells grant no API access: they only decide what the sidebar
 * draws, so each submenu can be switched on its own even when it shares permissions.
 *
 * Opt-in per portal: a user who holds no menu cell of a portal sees today's sidebar
 * (permission-driven). Once any of their roles holds one, the portal shows only the
 * submenus whose menu cell they hold (and whose permissions they have).
 */
export const NAV_AREA = 'nav';
export function navRow(portal: BusinessPortal, menuId: string, screenId: string): string {
  return `${NAV_AREA}.${portal.toLowerCase()}_${menuId}_${screenId}`.replace(/-/g, '_');
}
export function navRowOfKey(key: string): string {
  const [portal, menuId, screenId] = key.split('/');
  return navRow(portal as BusinessPortal, menuId, screenId);
}
/** The menu cell of every screen of every portal (the backend registers these rows). */
export function allNavRows(): { id: string; description: string }[] {
  const out: { id: string; description: string }[] = [];
  for (const p of Object.keys(PORTAL_PERMISSION_CATALOGUE) as BusinessPortal[]) {
    const portalLabel = p === 'PLATFORM' ? 'Admin' : p.charAt(0) + p.slice(1).toLowerCase();
    for (const m of PORTAL_PERMISSION_CATALOGUE[p]) for (const s of m.screens) {
      out.push({ id: navRow(p, m.id, s.id), description: `Menu: ${portalLabel} portal › ${m.label} › ${s.label}` });
    }
  }
  return out;
}
/** Does the user pick this portal's submenus by menu cells (holds any of them)? */
export function navScoped(portal: BusinessPortal, can: (rows: string[]) => boolean): boolean {
  const rows = PORTAL_PERMISSION_CATALOGUE[portal].flatMap((m) => m.screens.map((s) => navRow(portal, m.id, s.id)));
  return can(rows);
}
/** May the user open this route by its menu cells? True when the portal is not menu-scoped
 *  for them, or a screen on the route has its menu cell held. Routes with no screen pass. */
export function navAllows(portal: BusinessPortal, link: string, can: (rows: string[]) => boolean): boolean {
  const rows: string[] = [];
  for (const m of PORTAL_PERMISSION_CATALOGUE[portal]) for (const s of m.screens) {
    if (LIVE_SCREEN_ROUTES[`${portal}/${m.id}/${s.id}`] === link) rows.push(navRow(portal, m.id, s.id));
  }
  return !rows.length || !navScoped(portal, can) || can(rows);
}

export interface LiveNavMenu { id: string; label: string; screens: { id: string; label: string; link: string }[] }
/** Runtime navigation uses this same catalogue; a route and held backend cell
 * are both required, and - once the user is menu-scoped for the portal - the
 * screen's menu cell. Planned screens never appear in a signed-in portal. */
export function liveNavigation(portal: BusinessPortal, can: (resources: string[]) => boolean,
                               canRoute: (link: string) => boolean = () => true): LiveNavMenu[] {
  const scoped = navScoped(portal, can);
  return PORTAL_PERMISSION_CATALOGUE[portal].map((m) => ({
    id: m.id, label: m.label,
    screens: m.screens.flatMap((s) => {
      const link = LIVE_SCREEN_ROUTES[`${portal}/${m.id}/${s.id}`];
      return link && s.resources.length && can(s.resources) && canRoute(link)
        && (!scoped || can([navRow(portal, m.id, s.id)]))
        ? [{ id: s.id, label: s.label, link }] : [];
    }),
  })).filter((m) => m.screens.length);
}
