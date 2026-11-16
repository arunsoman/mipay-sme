/**
 * Access reconciliation (rbac.reconciliation): for the picked organization, where the people's
 * roles in the core and in RBAC disagree, and whether RBAC is enforcing yet. MiPay only.
 * GET /api/admin/rbac/reconciliation/{orgId} · GET /api/admin/rbac/mode.
 */
import { defineWidget, span } from '../../core/manifest';
import { RBAC_MODE_GET, RBAC_RECON_GET } from './reconciliation.contract';

export type RbacReconciliationConfig = Record<string, never>;

export default defineWidget<RbacReconciliationConfig>({
  id: 'rbac.reconciliation',
  version: '1.0.0',
  label: 'Access reconciliation',
  description: 'Where an organization’s roles in the core and in RBAC disagree, and whether RBAC enforces yet.',
  category: 'Access',
  contexts: ['PLATFORM'],
  requires: { all: ['rbac.assignment:read', 'rbac.role:read'] },
  actions: [],
  sources: ['rbac.recon.get', 'rbac.mode.get'],
  commands: [],
  operations: ['view'],
  commandOperations: {},
  contracts: { 'rbac.recon.get': RBAC_RECON_GET, 'rbac.mode.get': RBAC_MODE_GET },
  requests: {},
  inputs: { orgId: { from: ['selection.orgId', 'context.orgId'], label: 'Organization' } },
  config: {},
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(6, 12), lg: span.lg(6, 12) },
  hideOn: ['forbidden'],
  load: () => import('./reconciliation.component').then((m) => m.RbacReconciliationWidget),
});
