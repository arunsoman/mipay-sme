/**
 * Roles (rbac.roles): every role the portal may use, what each one allows, and changing or
 * deleting the ones that may be changed. PLATFORM sees the standard roles (GET/PUT/DELETE
 * /api/admin/rbac/roles[/{name}]); org portals see the standard roles inside their ceiling and
 * their own roles, and change only their own (GET /api/orgs/{id}/roles, PUT/DELETE …/roles/{name}).
 */
import { defineWidget, span } from '../../core/manifest';
import { RBAC_ROLES_DELETE, RBAC_ROLES_LIST, RBAC_ROLES_UPDATE, RBAC_ROLES_UPDATE_REQUEST } from './roles.contract';

export interface RbacRolesConfig {
  /** Rows per page. */
  pageSize: number;
}

export default defineWidget<RbacRolesConfig>({
  id: 'rbac.roles',
  version: '1.0.0',
  label: 'Roles',
  description: 'Every role, what it allows, and changing or deleting the roles that may be changed.',
  category: 'Access',
  contexts: ['PLATFORM', 'MANUFACTURER', 'DISTRIBUTOR', 'SUPPLIER'],
  requires: ['rbac.role:read'],
  actions: [
    { id: 'edit', label: 'Change role', cell: 'rbac.role:modify', command: 'rbac.roles.update' },
    { id: 'delete', label: 'Delete role', cell: 'rbac.role:delete', command: 'rbac.roles.delete' },
  ],
  sources: ['rbac.roles.list'],
  commands: ['rbac.roles.update', 'rbac.roles.delete'],
  operations: ['view', 'modify', 'approve'],
  commandOperations: { 'rbac.roles.update': 'modify', 'rbac.roles.delete': 'approve' },
  contracts: {
    'rbac.roles.list': RBAC_ROLES_LIST,
    'rbac.roles.update': RBAC_ROLES_UPDATE,
    'rbac.roles.delete': RBAC_ROLES_DELETE,
  },
  requests: { 'rbac.roles.update': RBAC_ROLES_UPDATE_REQUEST },
  inputs: { orgId: { from: ['selection.orgId', 'context.orgId'], label: 'Organization' } },
  publishes: ['roleName'],
  config: {
    pageSize: { type: 'number', default: 10, min: 5, max: 100, label: 'Rows per page' },
  },
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(8, 12), lg: span.lg(6, 12) },
  hideOn: ['forbidden'],
  load: () => import('./roles.component').then((m) => m.RbacRolesWidget),
});
