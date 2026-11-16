/**
 * Permission catalogue (rbac.resources): every permission row roles are built from, the verbs
 * some endpoint uses, which org types it applies to, and the endpoints each cell guards.
 * MiPay only. GET /api/admin/rbac/resources.
 */
import { defineWidget, span } from '../../core/manifest';
import { RBAC_RESOURCES_LIST } from './resources.contract';

export interface RbacResourcesConfig {
  /** Rows per page. */
  pageSize: number;
}

export default defineWidget<RbacResourcesConfig>({
  id: 'rbac.resources',
  version: '1.0.0',
  label: 'Permission catalogue',
  description: 'Every permission row roles are built from, its verbs, the org types it applies to and the endpoints it guards.',
  category: 'Access',
  contexts: ['PLATFORM'],
  requires: ['rbac.role:read'],
  actions: [],
  sources: ['rbac.resources.list'],
  commands: [],
  operations: ['view'],
  commandOperations: {},
  contracts: { 'rbac.resources.list': RBAC_RESOURCES_LIST },
  requests: {},
  config: {
    pageSize: { type: 'number', default: 15, min: 5, max: 100, label: 'Rows per page' },
  },
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(8, 12), lg: span.lg(6, 12) },
  hideOn: ['forbidden'],
  load: () => import('./resources.component').then((m) => m.RbacResourcesWidget),
});
