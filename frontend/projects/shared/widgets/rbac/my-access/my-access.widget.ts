/**
 * My access (rbac.my-access): the signed-in person's roles and every permission they hold, with
 * what each one is for - "why can't I see X?". GET /api/orgs/me/access needs only a session, but a
 * widget is always gated, so it shows to anyone who reads users, roles or assignments (the people
 * who answer that question).
 */
import { defineWidget, span } from '../../core/manifest';
import { RBAC_MY_ACCESS_GET } from './my-access.contract';

export interface RbacMyAccessConfig {
  /** Rows per page. */
  pageSize: number;
}

export default defineWidget<RbacMyAccessConfig>({
  id: 'rbac.my-access',
  version: '1.0.0',
  label: 'My access',
  description: 'Your roles and every permission you hold, with what each one is for.',
  category: 'Access',
  contexts: ['PLATFORM', 'MANUFACTURER', 'DISTRIBUTOR', 'SUPPLIER'],
  requires: ['org.user:read', 'rbac.role:read', 'rbac.assignment:read'],
  actions: [],
  sources: ['rbac.my-access.get'],
  commands: [],
  operations: ['view'],
  commandOperations: {},
  contracts: { 'rbac.my-access.get': RBAC_MY_ACCESS_GET },
  requests: {},
  config: {
    pageSize: { type: 'number', default: 15, min: 5, max: 100, label: 'Rows per page' },
  },
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(6, 12), lg: span.lg(4, 6, 12) },
  hideOn: ['forbidden'],
  load: () => import('./my-access.component').then((m) => m.RbacMyAccessWidget),
});
