/**
 * Organizations (admin console, docs/plans/admin-console.md): every organization with its KYC
 * status, people, preset and warning badges ("Can't add users", "Access not set"), searched,
 * filtered, sorted and paged by the server. Picking one publishes its org (page selection
 * `orgId`) so the page opens its KYC, What it can do and People tabs.
 */
import { defineWidget, span } from '../../core/manifest';
import { ADMIN_ORGANIZATIONS_SEARCH } from './organizations.contract';

export interface AdminOrganizationsConfig {
  /** Rows per page. */
  pageSize: number;
}

export default defineWidget<AdminOrganizationsConfig>({
  id: 'admin.organizations',
  version: '1.0.0',
  label: 'Organizations',
  description: 'Every organization with KYC status, people, preset and warnings; search, filters, sorting and paging done by the server.',
  category: 'Organizations',
  contexts: ['PLATFORM'],
  requires: ['admin.org_directory:read'],
  actions: [],
  sources: ['admin.organizations.search'],
  commands: [],
  operations: ['view'],
  commandOperations: {},
  contracts: { 'admin.organizations.search': ADMIN_ORGANIZATIONS_SEARCH },
  publishes: ['orgId', 'orgName'],
  config: {
    pageSize: { type: 'number', default: 10, min: 5, max: 100, label: 'Rows per page' },
  },
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(12), lg: span.lg(12) },
  hideOn: ['forbidden'],
  load: () => import('./organizations.component').then((m) => m.AdminOrganizationsWidget),
});
