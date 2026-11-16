/**
 * Fixture scenarios for rbac.platform-assignments (RbacAssignmentView, RbacUserView, RbacRoleView).
 */
import { defineFixtures, fail } from '../../core/fixtures';
import { PLATFORM_ROLES } from '../roles/roles.fixtures';
import type { PlatformAssignment, PlatformAssignmentsUsers } from './platform-assignments.contract';

export const USERS: PlatformAssignmentsUsers = [
  { id: 'u-tuli', displayName: 'Tuli Amukwaya', orgId: 'org-root', kind: 'PLATFORM_STAFF', status: 'ACTIVE' },
  { id: 'u-anna', displayName: 'Anna Kambatu', orgId: 'org-sup-1', kind: 'ORG_USER', status: 'ACTIVE' },
];

export const ASSIGNMENTS: PlatformAssignment[] = [
  { id: 'pa-1', subject: 'u-tuli', role: 'kyc_reviewer', tenant: 'org-root', scope: 'subtree', status: 'ACTIVE' },
  { id: 'pa-2', subject: 'u-anna', role: 'org_admin', tenant: 'org-sup-1', scope: 'own', status: 'ACTIVE' },
  { id: 'pa-3', subject: 'u-anna', role: 'support_desk', tenant: 'org-sup-1', scope: 'own', status: 'PENDING_APPROVAL' },
];

const byStatus = (p: Readonly<Record<string, unknown>>) => (p['status'] ? ASSIGNMENTS.filter((a) => a.status === p['status']) : ASSIGNMENTS);
const assigned = (p: Readonly<Record<string, unknown>>) => {
  const b = (p['body'] ?? {}) as { subject?: string; role?: string; tenant?: string | null; scope?: string };
  return { id: 'pa-9', subject: b.subject ?? null, role: b.role ?? null, tenant: b.tenant ?? null, scope: b.scope ?? null, status: 'ACTIVE' };
};

export default defineFixtures({
  default: {
    'rbac.platform-assignments.list': byStatus, 'rbac.platform-assignments.users': USERS, 'rbac.platform-assignments.roles': PLATFORM_ROLES,
    'rbac.platform-assignments.assign': assigned,
    'rbac.platform-assignments.revoke': (p) => ({ ...(ASSIGNMENTS.find((a) => a.id === p['id']) ?? ASSIGNMENTS[0]), status: 'REVOKED' }),
  },
  empty: { 'rbac.platform-assignments.list': [], 'rbac.platform-assignments.users': USERS, 'rbac.platform-assignments.roles': PLATFORM_ROLES },
  error: { 'rbac.platform-assignments.list': fail(500, { title: 'Server error', detail: 'RBAC unavailable' }),
    'rbac.platform-assignments.users': USERS, 'rbac.platform-assignments.roles': PLATFORM_ROLES },
  longText: { 'rbac.platform-assignments.list': byStatus,
    'rbac.platform-assignments.users': USERS.map((u) => ({ ...u, displayName: `${u.displayName} of the Oshakati Regional Wholesale & Cold-Chain Distribution Cooperative` })),
    'rbac.platform-assignments.roles': PLATFORM_ROLES, 'rbac.platform-assignments.assign': assigned },
  refused: { 'rbac.platform-assignments.list': byStatus, 'rbac.platform-assignments.users': USERS, 'rbac.platform-assignments.roles': PLATFORM_ROLES,
    'rbac.platform-assignments.assign': fail(422, { title: 'Refused', detail: 'Breaks a separation-of-duty rule', code: 'SOD_CONFLICT' }),
    'rbac.platform-assignments.revoke': fail(409, { title: 'Refused', detail: 'Keeps one administrator', code: 'LAST_ADMIN' }) },
  forbidden: { 'rbac.platform-assignments.list': fail(403, { title: 'Forbidden', detail: 'rbac.assignment:read' }),
    'rbac.platform-assignments.users': USERS, 'rbac.platform-assignments.roles': PLATFORM_ROLES },
}, { expect: { forbidden: 'forbidden', empty: 'ready' } });
