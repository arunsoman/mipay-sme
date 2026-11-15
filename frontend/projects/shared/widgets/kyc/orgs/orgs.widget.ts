/**
 * KYC status board (kyc.orgs): the organizations the user can see, counted by KYC state, filtered
 * by tabs, listed in a table. Picking one publishes its org (page selection `orgId`), so a KYC
 * application widget on the same page opens it.
 */
import { defineWidget, span } from '../../core/manifest';
import { KYC_ORGS_LIST } from './orgs.contract';

export interface KycOrgsConfig {
  /** Rows per page. */
  pageSize: number;
}

export default defineWidget<KycOrgsConfig>({
  id: 'kyc.orgs',
  version: '1.0.0',
  label: 'KYC status board',
  description: 'Organizations you can see, counted by KYC state, with a filter and a table; picking one opens its application.',
  category: 'Onboarding',
  contexts: ['PLATFORM', 'MANUFACTURER', 'DISTRIBUTOR', 'SUPPLIER'],
  requires: ['kyc.application:read'],
  actions: [],
  sources: ['kyc.orgs.list'],
  commands: [],
  operations: ['view'],
  commandOperations: {},
  contracts: { 'kyc.orgs.list': KYC_ORGS_LIST },
  publishes: ['orgId'],
  config: {
    pageSize: { type: 'number', default: 10, min: 5, max: 50, label: 'Rows per page' },
  },
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(6, 12), lg: span.lg(4, 6, 12) },
  hideOn: ['forbidden'],
  load: () => import('./orgs.component').then((m) => m.KycOrgsWidget),
});
