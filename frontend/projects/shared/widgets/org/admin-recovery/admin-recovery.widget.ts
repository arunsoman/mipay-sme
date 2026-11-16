/**
 * New administrator (org.admin-recovery): ask for a new administrator for the picked organization
 * (its admin left or lost access). The request is decided in the onboarding queue.
 * GET /api/orgs/{id} (which org) · POST /api/orgs/{id}/admins.
 */
import { defineWidget, span } from '../../core/manifest';
import { ORG_ADMIN_RECOVERY, ORG_ADMIN_RECOVERY_ORG, ORG_ADMIN_RECOVERY_REQUEST } from './admin-recovery.contract';

export type OrgAdminRecoveryConfig = Record<string, never>;

export default defineWidget<OrgAdminRecoveryConfig>({
  id: 'org.admin-recovery',
  version: '1.0.0',
  label: 'New administrator',
  description: 'Ask for a new administrator for an organization whose administrator left or lost access.',
  category: 'Users',
  contexts: ['PLATFORM', 'MANUFACTURER', 'DISTRIBUTOR', 'SUPPLIER'],
  requires: { all: ['org.onboarding:authorize', 'org.tree:read'] },
  actions: [
    { id: 'request', label: 'Ask for a new administrator', cell: 'org.onboarding:authorize', command: 'org.admin-recovery.request' },
  ],
  sources: ['org.admin-recovery.org'],
  commands: ['org.admin-recovery.request'],
  operations: ['view', 'create'],
  commandOperations: { 'org.admin-recovery.request': 'create' },
  contracts: { 'org.admin-recovery.org': ORG_ADMIN_RECOVERY_ORG, 'org.admin-recovery.request': ORG_ADMIN_RECOVERY },
  requests: { 'org.admin-recovery.request': ORG_ADMIN_RECOVERY_REQUEST },
  inputs: { orgId: { from: ['selection.orgId', 'context.orgId'], label: 'Organization' } },
  config: {},
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(6, 12), lg: span.lg(4, 6, 12) },
  hideOn: ['forbidden'],
  load: () => import('./admin-recovery.component').then((m) => m.OrgAdminRecoveryWidget),
});
