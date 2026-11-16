/**
 * Platform role assignments (rbac.platform-assignments): every RBAC role assignment across the
 * platform, giving any user a role in an organization, and revoking one. MiPay only.
 * GET/POST /api/admin/rbac/assignments · DELETE /api/admin/rbac/assignments/{id} ·
 * GET /api/admin/rbac/users · GET /api/admin/rbac/roles.
 */
import { defineWidget, span } from '../../core/manifest';
import {
  PLATFORM_ASSIGNMENTS_ASSIGN, PLATFORM_ASSIGNMENTS_ASSIGN_REQUEST, PLATFORM_ASSIGNMENTS_LIST, PLATFORM_ASSIGNMENTS_REVOKE,
  PLATFORM_ASSIGNMENTS_ROLES, PLATFORM_ASSIGNMENTS_USERS,
} from './platform-assignments.contract';

export interface RbacPlatformAssignmentsConfig {
  /** Rows per page. */
  pageSize: number;
}

export default defineWidget<RbacPlatformAssignmentsConfig>({
  id: 'rbac.platform-assignments',
  version: '1.0.0',
  label: 'All role assignments',
  description: 'Every role assignment on the platform; give any user a role in an organization, or revoke one.',
  category: 'Access',
  contexts: ['PLATFORM'],
  requires: { all: ['rbac.assignment:read', 'rbac.user:read', 'rbac.role:read'] },
  actions: [
    { id: 'assign', label: 'Give a role', cell: 'rbac.assignment:create', command: 'rbac.platform-assignments.assign' },
    { id: 'revoke', label: 'Revoke', cell: 'rbac.assignment:administer', command: 'rbac.platform-assignments.revoke' },
  ],
  sources: ['rbac.platform-assignments.list', 'rbac.platform-assignments.users', 'rbac.platform-assignments.roles'],
  commands: ['rbac.platform-assignments.assign', 'rbac.platform-assignments.revoke'],
  operations: ['view', 'create', 'approve'],
  commandOperations: { 'rbac.platform-assignments.assign': 'create', 'rbac.platform-assignments.revoke': 'approve' },
  contracts: {
    'rbac.platform-assignments.list': PLATFORM_ASSIGNMENTS_LIST,
    'rbac.platform-assignments.users': PLATFORM_ASSIGNMENTS_USERS,
    'rbac.platform-assignments.roles': PLATFORM_ASSIGNMENTS_ROLES,
    'rbac.platform-assignments.assign': PLATFORM_ASSIGNMENTS_ASSIGN,
    'rbac.platform-assignments.revoke': PLATFORM_ASSIGNMENTS_REVOKE,
  },
  requests: { 'rbac.platform-assignments.assign': PLATFORM_ASSIGNMENTS_ASSIGN_REQUEST },
  publishes: ['userId'],
  config: {
    pageSize: { type: 'number', default: 15, min: 5, max: 100, label: 'Rows per page' },
  },
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(8, 12), lg: span.lg(6, 12) },
  hideOn: ['forbidden'],
  load: () => import('./platform-assignments.component').then((m) => m.RbacPlatformAssignmentsWidget),
});
