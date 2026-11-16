/**
 * Fixture scenarios for org.onboard-child (GrantableView, OrgDecisionResult).
 */
import { defineFixtures, fail } from '../../core/fixtures';

export const GRANTABLE = {
  effDirect: ['MANUFACTURER'],
  children: [
    { type: 'DISTRIBUTOR', allowedCeiling: ['SUPPLIER', 'MERCHANT'], defaultDirect: ['DISTRIBUTOR'] },
    { type: 'SUPPLIER', allowedCeiling: [], defaultDirect: ['SUPPLIER'] },
  ],
  roles: [{ name: 'org_admin', description: 'Administers the organization', baseline: true }],
  baselineRoles: ['org_admin'],
};

const submitted = (p: Readonly<Record<string, unknown>>) => ({
  requestId: 'onb-77', orgId: 'org-new-77', status: 'PENDING_REVIEW',
  message: `${(p['body'] as { name?: string })?.name ?? 'The organization'} is waiting for approval.`,
});

export default defineFixtures({
  default: { 'org.grantable.get': GRANTABLE, 'org.children.submit': submitted },
  empty: { 'org.grantable.get': { ...GRANTABLE, children: [] } },
  error: { 'org.grantable.get': fail(500, { title: 'Server error', detail: 'Rights service unavailable' }) },
  longText: { 'org.grantable.get': { ...GRANTABLE, children: [{ type: 'DISTRIBUTOR', allowedCeiling: [], defaultDirect: [] }] },
    'org.children.submit': { requestId: 'onb-78', orgId: 'org-new-78', status: 'PENDING_REVIEW',
      message: 'Oshakati Regional Wholesale & Cold-Chain Distribution Cooperative (Proprietary) Limited is waiting for approval by Swakop Foods Manufacturing.' } },
  refused: { 'org.grantable.get': GRANTABLE,
    'org.children.submit': fail(409, { title: 'Conflict', detail: 'Phone already registered', code: 'DUPLICATE_PHONE' }) },
  forbidden: { 'org.grantable.get': fail(403, { title: 'Forbidden', detail: 'org.rights:read' }) },
}, { expect: { forbidden: 'forbidden' } });
