/**
 * KYC review queue (kyc.tasks): the open KYC review tasks the signed-in user may act on, and an
 * approve / reject decision with a note. Picking a task publishes its org (page selection
 * `orgId`), so a KYC application widget on the same page shows that organization.
 * Built with the mipay-aruns-ui skill from GET /api/kyc/tasks and POST /api/kyc/tasks/{taskId}/decision.
 */
import { defineWidget, span } from '../../core/manifest';
import { KYC_TASKS_DECIDE, KYC_TASKS_DECIDE_REQUEST, KYC_TASKS_LIST } from './tasks.contract';

export interface KycTasksConfig {
  /** Rows per page in the queue. */
  pageSize: number;
}

export default defineWidget<KycTasksConfig>({
  id: 'kyc.tasks',
  version: '1.0.0',
  label: 'KYC review queue',
  description: 'Open KYC review tasks for the signed-in reviewer, with an approve / reject decision and a note.',
  category: 'Onboarding',
  contexts: ['PLATFORM', 'MANUFACTURER', 'DISTRIBUTOR', 'SUPPLIER'],
  requires: ['kyc.task:read'],
  actions: [
    { id: 'decide', label: 'Approve or reject', cell: 'kyc.task:authorize', command: 'kyc.tasks.decide' },
  ],
  sources: ['kyc.tasks.list'],
  commands: ['kyc.tasks.decide'],
  contracts: {
    'kyc.tasks.list': KYC_TASKS_LIST,
    'kyc.tasks.decide': KYC_TASKS_DECIDE,
  },
  requests: { 'kyc.tasks.decide': KYC_TASKS_DECIDE_REQUEST },
  publishes: ['orgId'],
  config: {
    pageSize: { type: 'number', default: 10, min: 5, max: 50, label: 'Rows per page' },
  },
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(6, 12), lg: span.lg(4, 5, 12) },
  hideOn: ['forbidden'],
  load: () => import('./tasks.component').then((m) => m.KycTasksWidget),
});
