/**
 * Role assignments (rbac.assignments): who in the organization holds which role, giving a person a
 * role (four-eyes by the role's risk: it may wait for approval) and taking one away.
 * GET /api/orgs/{id}/role-assignments · /members · /roles · POST /role-assignments ·
 * POST /role-assignments/{assignmentId}/revoke.
 */
import { defineWidget, span } from '../../core/manifest';
import {
  RBAC_ASSIGNMENTS_ASSIGN, RBAC_ASSIGNMENTS_ASSIGN_REQUEST, RBAC_ASSIGNMENTS_LIST, RBAC_ASSIGNMENTS_MEMBERS,
  RBAC_ASSIGNMENTS_REVOKE, RBAC_ASSIGNMENTS_REVOKE_REQUEST, RBAC_ASSIGNMENTS_ROLES,
} from './assignments.contract';

export interface RbacAssignmentsConfig {
  /** Rows per page. */
  pageSize: number;
}

export default defineWidget<RbacAssignmentsConfig>({
  id: 'rbac.assignments',
  version: '1.0.0',
  label: 'Role assignments',
  description: 'Who in the organization holds which role; give a person a role or take one away.',
  category: 'Access',
  contexts: ['PLATFORM', 'MANUFACTURER', 'DISTRIBUTOR', 'SUPPLIER'],
  requires: { all: ['rbac.assignment:read', 'org.user:read', 'rbac.role:read'] },
  actions: [
    { id: 'assign', label: 'Give a role', cell: 'rbac.assignment:create', command: 'rbac.assignments.assign' },
    { id: 'revoke', label: 'Take a role away', cell: 'rbac.assignment:administer', command: 'rbac.assignments.revoke' },
  ],
  sources: ['rbac.assignments.list', 'rbac.assignments.members', 'rbac.assignments.roles'],
  commands: ['rbac.assignments.assign', 'rbac.assignments.revoke'],
  operations: ['view', 'create', 'approve'],
  commandOperations: { 'rbac.assignments.assign': 'create', 'rbac.assignments.revoke': 'approve' },
  contracts: {
    'rbac.assignments.list': RBAC_ASSIGNMENTS_LIST,
    'rbac.assignments.members': RBAC_ASSIGNMENTS_MEMBERS,
    'rbac.assignments.roles': RBAC_ASSIGNMENTS_ROLES,
    'rbac.assignments.assign': RBAC_ASSIGNMENTS_ASSIGN,
    'rbac.assignments.revoke': RBAC_ASSIGNMENTS_REVOKE,
  },
  requests: {
    'rbac.assignments.assign': RBAC_ASSIGNMENTS_ASSIGN_REQUEST,
    'rbac.assignments.revoke': RBAC_ASSIGNMENTS_REVOKE_REQUEST,
  },
  inputs: { orgId: { from: ['selection.orgId', 'context.orgId'], label: 'Organization' } },
  publishes: ['userId'],
  config: {
    pageSize: { type: 'number', default: 10, min: 5, max: 100, label: 'Rows per page' },
  },
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(6, 12), lg: span.lg(6, 12) },
  hideOn: ['forbidden'],
  load: () => import('./assignments.component').then((m) => m.RbacAssignmentsWidget),
});
