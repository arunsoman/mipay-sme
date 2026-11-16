/**
 * Fixture scenarios for rbac.assignments (members, assignments, the org's roles).
 */
import { defineFixtures, fail } from '../../core/fixtures';
import { ORG_VIEW } from '../roles/roles.fixtures';
import type { Assignment, OrgMembers } from './assignments.contract';

export const MEMBERS: OrgMembers = [
  { id: 'u-anna', name: 'Anna Kambatu', username: 'anna.k', status: 'ACTIVE' },
  { id: 'u-lukas', name: 'Lukas Ndeitunga', username: 'lukas.n', status: 'ACTIVE' },
  { id: 'u-petrus', name: 'Petrus Shiimi', username: 'petrus.s', status: 'PENDING' },
];

export const ASSIGNMENTS: Assignment[] = [
  { id: 'as-1', subject: 'u-anna', role: 'org_admin', scope: 'own', status: 'ACTIVE', userName: 'Anna Kambatu', username: 'anna.k' },
  { id: 'as-2', subject: 'u-lukas', role: 'stock_clerk@org-sup-1', scope: 'own', status: 'PENDING_APPROVAL', userName: 'Lukas Ndeitunga', username: 'lukas.n' },
  { id: 'as-3', subject: 'u-petrus', role: 'org_viewer', scope: 'subtree', status: 'REVOKED', userName: 'Petrus Shiimi', username: 'petrus.s' },
];

const assigned = (p: Readonly<Record<string, unknown>>) => {
  const b = (p['body'] ?? {}) as { userId?: string; role?: string; scope?: string };
  return { id: 'as-9', subject: b.userId ?? 'u-anna', role: b.role ?? 'org_viewer', scope: b.scope ?? 'own',
    status: b.role === 'org_admin' ? 'PENDING_APPROVAL' : 'ACTIVE' };
};

const LONG = 'Ndapewa Ndinelago Shikongo-Haufiku of the Oshakati Regional Wholesale Cooperative';

export default defineFixtures({
  default: {
    'rbac.assignments.list': ASSIGNMENTS, 'rbac.assignments.members': MEMBERS, 'rbac.assignments.roles': ORG_VIEW,
    'rbac.assignments.assign': assigned, 'rbac.assignments.revoke': { status: 'REVOKED' },
  },
  empty: { 'rbac.assignments.list': [], 'rbac.assignments.members': MEMBERS, 'rbac.assignments.roles': ORG_VIEW },
  error: { 'rbac.assignments.list': fail(500, { title: 'Server error', detail: 'RBAC unavailable', code: 'RBAC_UNAVAILABLE' }),
    'rbac.assignments.members': MEMBERS, 'rbac.assignments.roles': ORG_VIEW },
  longText: { 'rbac.assignments.list': ASSIGNMENTS.map((a) => ({ ...a, userName: LONG })),
    'rbac.assignments.members': MEMBERS.map((m) => ({ ...m, name: LONG })), 'rbac.assignments.roles': ORG_VIEW,
    'rbac.assignments.assign': assigned },
  refused: { 'rbac.assignments.list': ASSIGNMENTS, 'rbac.assignments.members': MEMBERS, 'rbac.assignments.roles': ORG_VIEW,
    'rbac.assignments.assign': fail(422, { title: 'Refused', detail: 'Breaks a separation-of-duty rule', code: 'SOD_CONFLICT' }),
    'rbac.assignments.revoke': fail(409, { title: 'Refused', detail: 'Keeps one administrator', code: 'LAST_ADMIN' }) },
  forbidden: { 'rbac.assignments.list': fail(403, { title: 'Forbidden', detail: 'rbac.assignment:read' }),
    'rbac.assignments.members': MEMBERS, 'rbac.assignments.roles': ORG_VIEW },
}, { expect: { forbidden: 'forbidden', empty: 'ready' } });
