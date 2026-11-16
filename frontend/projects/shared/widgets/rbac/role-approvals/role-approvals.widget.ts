/**
 * Role approvals (rbac.role-approvals): role requests in the organization waiting for a second
 * person, and approving or rejecting them (four-eyes: requesters and grantees never decide their own).
 * GET /api/orgs/{id}/role-approvals · /members · POST /role-approvals/{assignmentId}.
 */
import { defineWidget, span } from '../../core/manifest';
import {
  RBAC_APPROVALS_DECIDE, RBAC_APPROVALS_DECIDE_REQUEST, RBAC_APPROVALS_LIST, RBAC_APPROVALS_MEMBERS,
} from './role-approvals.contract';

export interface RbacRoleApprovalsConfig {
  /** Rows per page. */
  pageSize: number;
}

export default defineWidget<RbacRoleApprovalsConfig>({
  id: 'rbac.role-approvals',
  version: '1.0.0',
  label: 'Role approvals',
  description: 'Role requests waiting for a second person, with an approve / reject decision.',
  category: 'Access',
  contexts: ['PLATFORM', 'MANUFACTURER', 'DISTRIBUTOR', 'SUPPLIER'],
  requires: { all: ['rbac.assignment:read', 'org.user:read'] },
  actions: [
    { id: 'decide', label: 'Approve or reject', cell: 'rbac.assignment:authorize', command: 'rbac.approvals.decide' },
  ],
  sources: ['rbac.approvals.list', 'rbac.approvals.members'],
  commands: ['rbac.approvals.decide'],
  operations: ['view', 'approve'],
  commandOperations: { 'rbac.approvals.decide': 'approve' },
  contracts: {
    'rbac.approvals.list': RBAC_APPROVALS_LIST,
    'rbac.approvals.members': RBAC_APPROVALS_MEMBERS,
    'rbac.approvals.decide': RBAC_APPROVALS_DECIDE,
  },
  requests: { 'rbac.approvals.decide': RBAC_APPROVALS_DECIDE_REQUEST },
  inputs: { orgId: { from: ['selection.orgId', 'context.orgId'], label: 'Organization' } },
  config: {
    pageSize: { type: 'number', default: 10, min: 5, max: 100, label: 'Rows per page' },
  },
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(6, 12), lg: span.lg(4, 6, 12) },
  hideOn: ['forbidden'],
  load: () => import('./role-approvals.component').then((m) => m.RbacRoleApprovalsWidget),
});
