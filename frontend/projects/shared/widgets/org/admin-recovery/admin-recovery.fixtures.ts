/**
 * Fixture scenarios for org.admin-recovery (OrgView, OrgDecisionResult).
 */
import { defineFixtures, fail } from '../../core/fixtures';
import { ORG } from '../../ops/kyc-checks/kyc-checks.fixtures';

const DIST = { ...ORG, id: 'org-dist-7', type: 'DISTRIBUTOR', name: 'Namib Distribution', entityId: 'dist-7' };

const requested = (p: Readonly<Record<string, unknown>>) => ({
  requestId: 'onb-81', orgId: String(p['id']), status: 'PENDING_REVIEW',
  message: `${(p['body'] as { adminName?: string })?.adminName ?? 'The new administrator'} waits for approval.`,
});

export default defineFixtures({
  default: { 'org.admin-recovery.org': DIST, 'org.admin-recovery.request': requested },
  empty: { 'org.admin-recovery.org': { ...DIST, name: null }, 'org.admin-recovery.request': requested },
  error: { 'org.admin-recovery.org': fail(500, { title: 'Server error', detail: 'Org service unavailable' }) },
  forbidden: { 'org.admin-recovery.org': fail(403, { title: 'Forbidden', detail: 'org.tree:read' }) },
  longText: { 'org.admin-recovery.org': { ...DIST, name: 'Oshakati Regional Wholesale & Cold-Chain Distribution Cooperative (Proprietary) Limited' },
    'org.admin-recovery.request': { requestId: 'onb-82', orgId: 'org-dist-7', status: 'PENDING_REVIEW',
    message: 'Ndapewa Ndinelago Shikongo-Haufiku of the Oshakati Regional Wholesale & Cold-Chain Distribution Cooperative waits for approval.' } },
  invalid: { 'org.admin-recovery.org': DIST, 'org.admin-recovery.request': fail(422, { title: 'Invalid', detail: 'email: must be a well-formed email address' }) },
  refused: { 'org.admin-recovery.org': DIST, 'org.admin-recovery.request': fail(409, { title: 'Conflict', detail: 'Already waiting', code: 'RECOVERY_PENDING' }) },
}, { expect: { empty: 'ready', forbidden: 'forbidden' } });
