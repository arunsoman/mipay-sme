/**
 * People (org.people, docs/plans/admin-console.md): an organization's people with the roles
 * they hold and have requested, searched, filtered, sorted and paged by the server. Which org:
 * the page selection (the organizations list), else the signed-in user's own org.
 */
import { defineWidget, span } from '../../core/manifest';
import { ORG_PEOPLE_SEARCH } from './people.contract';

export interface OrgPeopleConfig {
  /** Rows per page. */
  pageSize: number;
}

export default defineWidget<OrgPeopleConfig>({
  id: 'org.people',
  version: '1.0.0',
  label: 'People and roles',
  description: "An organization's people with the roles they hold; search, filters, sorting and paging done by the server.",
  category: 'Organizations',
  contexts: ['PLATFORM', 'MANUFACTURER', 'DISTRIBUTOR', 'SUPPLIER'],
  requires: ['org.user:read'],
  actions: [],
  sources: ['org.people.search'],
  commands: [],
  operations: ['view'],
  commandOperations: {},
  contracts: { 'org.people.search': ORG_PEOPLE_SEARCH },
  inputs: {
    orgId: { from: ['selection.orgId', 'context.orgId'], label: 'Organization' },
  },
  config: {
    pageSize: { type: 'number', default: 10, min: 5, max: 100, label: 'Rows per page' },
  },
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(6, 12), lg: span.lg(6, 12) },
  hideOn: ['forbidden'],
  load: () => import('./people.component').then((m) => m.OrgPeopleWidget),
});
