/**
 * THE menu registry - one canonical catalogue of every portal's sidebar
 * (fapi2-design App G: nav, route guards and buttons speak the same RBAC truth).
 *
 * Three shells used to hand-maintain their own lists, so the same page drifted
 * into two labels ("Service desk" vs "Complaints & disputes", "Audit" vs
 * "Audit trail", "Perms inventory" vs "All permissions") and two permission
 * sets (/ops/desk and /ops/approvals were gated differently per portal, and
 * /inventory had a row gate behind a cell gate). This registry makes the rules
 * structural:
 *
 *  1. One menu point, one label. The same screen reads the same label in every
 *     portal that shows it - a label is defined HERE, once.
 *  2. One menu point, one permission list. The sidebar and every portal's
 *     permGuard read the same `perm` array: access-map.ts and the portal
 *     route tables derive their maps from here (never retype the rows).
 *  3. No ungated items. `perm` is required; there is no "show for anyone with
 *     the persona" fallback. An empty `perm` simply shows nothing.
 *  4. A shared page is gated by the union of the rows its panels need
 *     (e.g. /ops/approvals carries the KYC-check read panel; /ops/desk the
 *     suspension ledger), so every portal opens the page for the same users.
 *
 * Fields:
 *  - label / icon / link: presentation. Icons render only where a shell draws
 *    them (the sme-ui sidebar); the admin and org portals keep plain text.
 *  - perm: rows (any held verb) or exact cells ("row:verb") - OR semantics,
 *    exactly what `permGuard` and `AccessService.can` accept.
 *  - group: optional sidebar header, rendered once when the group changes.
 *    Every portal that shows the item renders the same header.
 *  - portals: which sidebars carry the point ('sme' = the five-persona sme-ui;
 *    'admin' = the MiPay Admin portal; 'org' = manufacturer/distributor/
 *    supplier portals).
 *  - roles: the sme-ui persona gate, mirroring its route roleGuards (RBAC is
 *    legacy-anchored there until cut-over P6). Portals that guard purely by
 *    permission - admin and org - ignore it.
 *  - links: the rare per-portal URL for one shared page (compliance); `link`
 *    is the sme-ui route.
 */
export type PortalId = 'sme' | 'admin' | 'org';

export interface MenuItem {
  /** Stable id (nav tracking, tests). */
  id: string;
  /** THE label - one name per menu point, everywhere. */
  label: string;
  icon: string;
  /** sme-ui route; portals may override via `links`. */
  link: string;
  /** Rows (any verb) or exact cells ("row:verb") - OR semantics. Required. */
  perm: string[];
  group?: string;
  portals: PortalId[];
  /** sme-ui persona gate (mirrors its roleGuards); ignored by admin/org. */
  roles?: string[];
  /** Per-portal URL override for a shared page (rare). */
  links?: Partial<Record<PortalId, string>>;
}

/**
 * The registry order is the one canonical sidebar order; every portal renders
 * the subset it carries, in this order. Governance first (it is the admin and
 * org portals' opening block), then the sme-ui personas, operations, the
 * platform admin surface and compliance.
 */
export const MENU_ITEMS: MenuItem[] = [
  // ---------------- governance (admin + org portals)
  { id: 'platform-overview', label: 'Overview', icon: '◫', link: '/overview', perm: ['rbac.role', 'admin.platform_ops', 'org.user'], portals: ['admin'] },
  { id: 'platform-organizations', label: 'Organizations', icon: '◈', link: '/orgs', perm: ['admin.org_directory'], portals: ['admin'] },
  { id: 'onboarding', label: 'Onboarding', icon: '⇲', link: '/onboarding', perm: ['org.onboarding'], portals: ['admin', 'org'] },
  { id: 'network', label: 'My network', icon: '⑃', link: '/network', perm: ['org.tree'], portals: ['org'] },
  { id: 'users', label: 'User management', icon: '☰', link: '/users', perm: ['org.user:administer'], portals: ['admin', 'org'] },
  { id: 'kyc', label: 'Organizations', icon: '◈', link: '/kyc', perm: ['kyc.application', 'kyc.task'], portals: ['admin', 'org'] },
  { id: 'rights', label: 'Onboarding rights', icon: '⚿', link: '/permissions', perm: ['org.rights'], portals: ['admin'] },
  { id: 'rbac', label: 'Roles and permissions', icon: '⚿', link: '/rbac', perm: ['rbac.role'], portals: ['admin'] },
  // the permission inventory is a read-only catalogue: the exact read cell opens it
  { id: 'inventory', label: 'All permissions', icon: '⌗', link: '/inventory', perm: ['admin.access:read'], portals: ['admin'] },

  // ---------------- merchant (sme-ui)
  { id: 'merchant-home', label: 'Dashboard', icon: '◫', link: '/merchant', perm: ['merchant.account'], portals: ['sme'], roles: ['MERCHANT'] },
  { id: 'accept', label: 'Accept payment', icon: '＋', link: '/merchant/accept', perm: ['merchant.payment'], portals: ['sme'], roles: ['MERCHANT'] },
  { id: 'payments', label: 'Payments', icon: '≡', link: '/merchant/payments', perm: ['merchant.payment'], portals: ['sme'], roles: ['MERCHANT'] },
  { id: 'morders', label: 'Stock & orders', icon: '⇄', link: '/merchant/orders', perm: ['merchant.order'], portals: ['sme'], roles: ['MERCHANT'] },
  { id: 'minventory', label: 'Inventory', icon: '▥', link: '/merchant/inventory', perm: ['merchant.inventory'], portals: ['sme'], roles: ['MERCHANT'] },
  { id: 'khata', label: 'Khata & tabs', icon: '✎', link: '/merchant/khata', perm: ['merchant.khata', 'merchant.tab'], portals: ['sme'], roles: ['MERCHANT'] },
  { id: 'advance', label: 'Cash advance', icon: '⇪', link: '/merchant/advance', perm: ['merchant.advance'], portals: ['sme'], roles: ['MERCHANT'] },
  { id: 'balance', label: 'Balance & withdrawal', icon: 'N$', link: '/merchant/balance', perm: ['merchant.account'], portals: ['sme'], roles: ['MERCHANT'] },
  { id: 'mstatements', label: 'Statements', icon: '☰', link: '/merchant/statements', perm: ['merchant.statement'], portals: ['sme'], roles: ['MERCHANT'] },

  // ---------------- commerce (sme-ui; the org portals carry the supplier.* points)
  { id: 'supplier-home', label: 'Supplier home', icon: '⌂', link: '/supplier', perm: ['supplier.account'], portals: ['sme'], roles: ['SUPPLIER'] },
  { id: 'catalogue', label: 'Catalogue', icon: '▤', link: '/supplier/catalogue', perm: ['supplier.catalogue'], portals: ['sme', 'org'], roles: ['SUPPLIER'] },
  { id: 'orders', label: 'Orders & invoices', icon: '⇄', link: '/supplier/orders', perm: ['supplier.order', 'supplier.invoice'], portals: ['sme', 'org'], roles: ['SUPPLIER'] },
  { id: 'accounts', label: 'Receiving accounts', icon: '▣', link: '/supplier/accounts', perm: ['supplier.bank_account'], portals: ['sme', 'org'], roles: ['SUPPLIER'] },
  { id: 'statements', label: 'Statements', icon: '☰', link: '/supplier/statements', perm: ['supplier.statement'], portals: ['sme', 'org'], roles: ['SUPPLIER'] },

  // ---------------- lender (sme-ui)
  { id: 'lender-home', label: 'Consents & records', icon: '☰', link: '/lender', perm: ['lender.advance', 'lender.assessment'], portals: ['sme'], roles: ['LENDER'] },
  { id: 'portfolio', label: 'Portfolio', icon: '◫', link: '/lender/portfolio', perm: ['lender.portfolio'], portals: ['sme'], roles: ['LENDER'] },
  { id: 'collections', label: 'Collections', icon: '⇩', link: '/lender/collections', perm: ['lender.collection'], portals: ['sme'], roles: ['LENDER'] },

  // ---------------- operations (sme-ui + MiPay Admin)
  { id: 'approvals', label: 'Approvals', icon: '✓', link: '/ops/approvals', perm: ['ops.merchant_approval', 'ops.supplier_approval', 'ops.bank_account_approval', 'ops.kyc_check', 'ops.onboarding_request'], group: 'Operations', portals: ['sme', 'admin'], roles: ['TPTS_OPS'] },
  { id: 'recon', label: 'Reconciliation', icon: '⚖', link: '/ops/recon', perm: ['ops.recon'], group: 'Operations', portals: ['sme', 'admin'], roles: ['TPTS_OPS'] },
  { id: 'incidents', label: 'Incidents', icon: '⚑', link: '/ops/incidents', perm: ['ops.incident'], group: 'Operations', portals: ['sme', 'admin'], roles: ['TPTS_OPS'] },
  // the desk page owns complaints, disputes and the suspension ledger
  { id: 'desk', label: 'Complaints & disputes', icon: '☎', link: '/ops/desk', perm: ['ops.complaint', 'ops.dispute', 'ops.suspension'], group: 'Operations', portals: ['sme', 'admin'], roles: ['TPTS_OPS'] },
  { id: 'reports', label: 'Reports', icon: '▦', link: '/ops/reports', perm: ['ops.report'], group: 'Operations', portals: ['sme', 'admin'], roles: ['TPTS_OPS'] },
  { id: 'audit', label: 'Audit trail', icon: '☰', link: '/ops/audit', perm: ['ops.audit'], group: 'Operations', portals: ['sme', 'admin'], roles: ['TPTS_OPS'] },

  // ---------------- platform admin (sme-ui)
  { id: 'admin-home', label: 'Overview', icon: '◫', link: '/admin', perm: ['admin.access'], portals: ['sme'], roles: ['MINDLABS_ADMIN'] },
  { id: 'connectors', label: 'Connectors', icon: '⑂', link: '/admin/connectors', perm: ['admin.connector'], portals: ['sme', 'admin'], roles: ['MINDLABS_ADMIN'] },
  { id: 'config', label: 'Config', icon: '⚙', link: '/admin/config', perm: ['admin.config'], portals: ['sme', 'admin'], roles: ['MINDLABS_ADMIN'] },
  { id: 'api-clients', label: 'API clients', icon: '⑂', link: '/admin/api-clients', perm: ['admin.api_client'], portals: ['admin'] },
  { id: 'simulator', label: 'Simulator', icon: '⌁', link: '/admin/simulator', perm: ['admin.simulation'], portals: ['sme'], roles: ['MINDLABS_ADMIN'] },
  { id: 'access-control', label: 'Access control', icon: '⚿', link: '/admin/access', perm: ['admin.access'], portals: ['sme', 'admin'], roles: ['MINDLABS_ADMIN'] },
  { id: 'hygiene', label: 'Ops hygiene', icon: '◷', link: '/admin/hygiene', perm: ['admin.platform_ops'], portals: ['sme'], roles: ['MINDLABS_ADMIN'] },

  // ---------------- compliance (sme-ui + MiPay Admin; one page, one per-portal URL)
  { id: 'compliance', label: 'AML, SAR and data requests', icon: '⛨', link: '/admin/compliance', links: { admin: '/compliance' }, perm: ['admin.aml_alert', 'admin.sar', 'admin.dsar'], group: 'Compliance', portals: ['sme', 'admin'], roles: ['MINDLABS_ADMIN'] },
];

/** The menu points one portal carries, in the one canonical order. */
export function menuFor(portal: PortalId): MenuItem[] {
  return MENU_ITEMS.filter((i) => i.portals.includes(portal));
}

/** The route a menu point uses in a specific portal. */
export function linkFor(item: MenuItem, portal: PortalId): string {
  return (item.links && item.links[portal]) || item.link;
}

/** [route, permission rows] for one portal's menu - the sidebar and the permGuards pair by construction. */
export function menuPerms(portal: PortalId): Array<readonly [string, string[]]> {
  return menuFor(portal).map((i) => [linkFor(i, portal), i.perm] as const);
}

/** A sidebar has two render shapes: a group header or a gated item. */
export type MenuNavEntry =
  | { kind: 'group'; label: string }
  | { kind: 'item'; label: string; icon: string; link: string };

/**
 * Flattens a portal's menu into sidebar render entries: drop the points the
 * gate (`visible`) refuses, then emit each group header once, the first time
 * a point of that group becomes visible.
 */
export function navEntries(
  portal: PortalId,
  visible: (item: MenuItem) => boolean,
): MenuNavEntry[] {
  const out: MenuNavEntry[] = [];
  let lastGroup: string | undefined;
  for (const item of menuFor(portal)) {
    if (!visible(item)) continue;
    if (item.group && item.group !== lastGroup) out.push({ kind: 'group', label: item.group });
    lastGroup = item.group;
    out.push({ kind: 'item', label: item.label, icon: item.icon, link: linkFor(item, portal) });
  }
  return out;
}
