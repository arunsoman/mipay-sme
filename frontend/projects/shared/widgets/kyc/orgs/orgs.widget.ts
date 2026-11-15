/**
 * KYC applications (kyc.orgs): every KYC application the user can see, searched, filtered by
 * status, sorted and paged BY THE SERVER (GET /api/kyc/orgs/search, ListQuery). Picking one
 * publishes its org (page selection `orgId`), so a KYC application widget on the same page opens it.
 */
import { defineWidget, span } from '../../core/manifest';
import { KYC_ORGS_SEARCH } from './orgs.contract';

export interface KycOrgsConfig {
  /** Rows per page. */
  pageSize: number;
}

export default defineWidget<KycOrgsConfig>({
  id: 'kyc.orgs',
  version: '2.0.0',
  label: 'KYC applications',
  description: 'Every KYC application you can see: search, status filter, sorting and paging done by the server; picking one opens it.',
  category: 'Onboarding',
  contexts: ['PLATFORM', 'MANUFACTURER', 'DISTRIBUTOR', 'SUPPLIER'],
  requires: ['kyc.application:read'],
  actions: [],
  sources: ['kyc.orgs.search'],
  commands: [],
  operations: ['view'],
  commandOperations: {},
  contracts: { 'kyc.orgs.search': KYC_ORGS_SEARCH },
  publishes: ['orgId', 'orgName'],
  config: {
    pageSize: { type: 'number', default: 10, min: 5, max: 100, label: 'Rows per page' },
  },
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(6, 12), lg: span.lg(4, 6, 12) },
  hideOn: ['forbidden'],
  load: () => import('./orgs.component').then((m) => m.KycOrgsWidget),
});
