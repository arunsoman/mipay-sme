import { menuPerms } from '../../projects/shared/web-core/menu';

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
 *
 * Sidebar routes take their rows from THE menu registry (shared/web-core/menu.ts) -
 * one label and one permission set per menu point across all portals. Only pages
 * that carry no sidebar item of their own are listed here by hand.
 */
export const ROUTE_PERMS: Record<string, string[]> = {
  ...Object.fromEntries(menuPerms('sme')),

  // pages with no menu item (reachable by deep link only)
  '/merchant/payments/:id': ['merchant.payment'],
  '/merchant/tabs': ['merchant.tab'],
  '/merchant/settings': ['merchant.account'],
  '/supplier/invoices/:id': ['supplier.invoice'],
};
