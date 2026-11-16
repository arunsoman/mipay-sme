/**
 * Onboarding requests (org.onboarding-queue): organizations waiting to be let in (new orgs and
 * administrator replacements), with an approve / reject decision. Picking one publishes its org.
 * GET /api/orgs/onboarding?status · POST /api/orgs/onboarding/{id}/decide.
 */
import { defineWidget, span } from '../../core/manifest';
import { ORG_DECISION_RESULT, ORG_ONBOARDING_DECIDE_REQUEST, ORG_ONBOARDING_LIST } from './onboarding-queue.contract';

export interface OrgOnboardingQueueConfig {
  /** Rows per page. */
  pageSize: number;
}

export default defineWidget<OrgOnboardingQueueConfig>({
  id: 'org.onboarding-queue',
  version: '1.0.0',
  label: 'Onboarding requests',
  description: 'Organizations and administrator replacements waiting for approval, with an approve / reject decision.',
  category: 'Onboarding',
  contexts: ['PLATFORM', 'MANUFACTURER', 'DISTRIBUTOR', 'SUPPLIER'],
  requires: ['org.onboarding:read'],
  actions: [
    { id: 'decide', label: 'Approve or reject', cell: 'org.onboarding:authorize', command: 'org.onboarding.decide' },
  ],
  sources: ['org.onboarding.list'],
  commands: ['org.onboarding.decide'],
  operations: ['view', 'approve'],
  commandOperations: { 'org.onboarding.decide': 'approve' },
  contracts: {
    'org.onboarding.list': ORG_ONBOARDING_LIST,
    'org.onboarding.decide': ORG_DECISION_RESULT,
  },
  requests: { 'org.onboarding.decide': ORG_ONBOARDING_DECIDE_REQUEST },
  publishes: ['orgId', 'orgName'],
  config: {
    pageSize: { type: 'number', default: 10, min: 5, max: 100, label: 'Rows per page' },
  },
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(6, 12), lg: span.lg(6, 12) },
  hideOn: ['forbidden'],
  load: () => import('./onboarding-queue.component').then((m) => m.OrgOnboardingQueueWidget),
});
