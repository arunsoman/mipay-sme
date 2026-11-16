/**
 * Fixture scenarios for rbac.role-approvals (role requests, members).
 */
import { defineFixtures, fail } from '../../core/fixtures';
import { MEMBERS } from '../assignments/assignments.fixtures';
import type { RoleRequest } from './role-approvals.contract';

export const REQUESTS: RoleRequest[] = [
  { id: 'as-2', subject: 'u-lukas', role: 'stock_clerk@org-sup-1', scope: 'own', status: 'PENDING_APPROVAL',
    requested_by: 'u-anna', requested_at: '2026-11-14T09:00:00Z' },
  { id: 'as-5', subject: 'u-petrus', role: 'org_admin', scope: 'own', status: 'PENDING_APPROVAL',
    requested_by: 'u-anna', requested_at: '2026-11-15T07:30:00Z' },
];

const LONG = 'Ndapewa Ndinelago Shikongo-Haufiku of the Oshakati Regional Wholesale Cooperative';

export default defineFixtures({
  default: { 'rbac.approvals.list': REQUESTS, 'rbac.approvals.members': MEMBERS, 'rbac.approvals.decide': { status: 'ACTIVE' } },
  empty: { 'rbac.approvals.list': [], 'rbac.approvals.members': MEMBERS },
  error: { 'rbac.approvals.list': fail(500, { title: 'Server error', detail: 'RBAC unavailable', code: 'RBAC_UNAVAILABLE' }),
    'rbac.approvals.members': MEMBERS },
  longText: { 'rbac.approvals.list': REQUESTS, 'rbac.approvals.members': MEMBERS.map((m) => ({ ...m, name: LONG })),
    'rbac.approvals.decide': { status: 'ACTIVE' } },
  refused: { 'rbac.approvals.list': REQUESTS, 'rbac.approvals.members': MEMBERS,
    'rbac.approvals.decide': fail(403, { title: 'Refused', detail: 'You asked for it', code: 'four_eyes' }) },
  forbidden: { 'rbac.approvals.list': fail(403, { title: 'Forbidden', detail: 'rbac.assignment:read' }), 'rbac.approvals.members': MEMBERS },
}, { expect: { forbidden: 'forbidden' } });
