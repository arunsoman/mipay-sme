/**
 * Fixture scenarios for rbac.reconciliation (Report, mode).
 */
import { defineFixtures, fail } from '../../core/fixtures';
import type { RbacReconGet } from './reconciliation.contract';

export const REPORT: RbacReconGet = {
  orgId: 'org-sup-1', lifecycle: 'ACTIVE', users: 6, activeCoreRoleAssignments: 6, mismatches: 2,
  rows: [
    { userId: 'u-lukas', username: 'lukas.n', status: 'ACTIVE', coreRole: 'STAFF', expectedRole: 'org_staff',
      activeRbacRoles: [], reason: 'No RBAC role yet' },
    { userId: 'u-anna', username: 'anna.k', status: 'ACTIVE', coreRole: 'ADMIN', expectedRole: 'org_admin',
      activeRbacRoles: ['org_viewer'], reason: 'Admin in the core, viewer in RBAC' },
  ],
};

const MODE = { mode: 'shadow' };
const LONG = 'Administrator of the Oshakati Regional Wholesale & Cold-Chain Distribution Cooperative in the core, viewer in RBAC';

export default defineFixtures({
  default: { 'rbac.mode.get': MODE, 'rbac.recon.get': REPORT },
  empty: { 'rbac.mode.get': MODE, 'rbac.recon.get': { ...REPORT, mismatches: 0, rows: [] } },
  error: { 'rbac.mode.get': MODE, 'rbac.recon.get': fail(500, { title: 'Server error', detail: 'RBAC unavailable' }) },
  longText: { 'rbac.mode.get': MODE, 'rbac.recon.get': { ...REPORT, rows: REPORT.rows!.map((r) => ({ ...r, reason: LONG })) } },
  forbidden: { 'rbac.mode.get': fail(403, { title: 'Forbidden', detail: 'rbac.role:read' }),
    'rbac.recon.get': fail(403, { title: 'Forbidden', detail: 'rbac.assignment:read' }) },
}, { expect: { forbidden: 'forbidden', empty: 'ready' } });
