/**
 * Fixture scenarios for ops.kyc-checks (OrgView, KycCheckRow).
 */
import { defineFixtures, fail } from '../../core/fixtures';
import type { KycCheck, OpsKycOrgGet } from './kyc-checks.contract';

export const ORG: OpsKycOrgGet = {
  id: 'org-sup-1', type: 'SUPPLIER', name: 'Kalahari Supplies', status: 'ACTIVE', effectiveStatus: 'ACTIVE',
  parentOrgId: 'org-dist-7', entityId: 'sup-118', registrationNumber: 'CC/2019/04512', createdAt: '2026-11-01T08:00:00Z',
  kycStatus: 'PENDING', kycRiskRating: 'MEDIUM', kycNextReviewDate: null,
};

export const CHECKS: KycCheck[] = [
  { id: 'chk-1', entityType: 'supplier', entityId: 'sup-118', checkType: 'IDENTITY', result: 'PASS',
    details: 'National ID 85010100123 matched', checkedAt: '2026-11-12T09:00:00Z' },
  { id: 'chk-2', entityType: 'supplier', entityId: 'sup-118', checkType: 'SANCTIONS', result: 'MANUAL_REVIEW',
    details: 'Name similar to a listed entity; checking', checkedAt: '2026-11-13T11:20:00Z' },
];

const recorded = (p: Readonly<Record<string, unknown>>) => {
  const b = (p['body'] ?? {}) as { checkType?: string; result?: string; details?: string | null };
  return { id: 'chk-9', entityType: String(p['entityType']), entityId: String(p['entityId']), checkType: b.checkType ?? 'IDENTITY',
    result: b.result ?? 'PASS', details: b.details ?? null, checkedAt: '2026-11-15T10:00:00Z' };
};

const LONG = 'Oshakati Regional Wholesale & Cold-Chain Distribution Cooperative (Proprietary) Limited';

export default defineFixtures({
  default: { 'ops.kyc.org.get': ORG, 'ops.kyc-checks.list': CHECKS, 'ops.kyc-checks.record': recorded },
  empty: { 'ops.kyc.org.get': ORG, 'ops.kyc-checks.list': [] },
  error: { 'ops.kyc.org.get': fail(500, { title: 'Server error', detail: 'Org service unavailable' }) },
  longText: { 'ops.kyc.org.get': { ...ORG, name: LONG },
    'ops.kyc-checks.list': CHECKS.map((c) => ({ ...c, details: `${LONG} — ${c.details}` })), 'ops.kyc-checks.record': recorded },
  unsupported: { 'ops.kyc.org.get': { ...ORG, type: 'PLATFORM', entityId: null } },
  refused: { 'ops.kyc.org.get': ORG, 'ops.kyc-checks.list': CHECKS,
    'ops.kyc-checks.record': fail(422, { title: 'Invalid', detail: 'checkType: unknown value' }) },
  forbidden: { 'ops.kyc.org.get': fail(403, { title: 'Forbidden', detail: 'org.tree:read' }) },
}, { expect: { forbidden: 'forbidden', unsupported: 'empty', empty: 'ready' } });
