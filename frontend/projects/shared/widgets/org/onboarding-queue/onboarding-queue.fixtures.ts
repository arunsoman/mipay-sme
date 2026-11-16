/**
 * Fixture scenarios for org.onboarding-queue (OnboardingRequestRow, OrgDecisionResult).
 */
import { defineFixtures, fail } from '../../core/fixtures';
import type { OnboardingRequest } from './onboarding-queue.contract';

const row = (id: string, orgName: string, orgType: string, status: string, extra: Partial<OnboardingRequest> = {}): OnboardingRequest => ({
  id, kind: 'ORG_ONBOARDING', status, orgId: `org-${id}`, orgName, orgType, parentOrgId: 'org-mfg-1',
  requestedBy: 'Ndapewa Shikongo', requestedByRole: 'ADMIN', requestedByOrgId: 'org-mfg-1',
  reviewedBy: null, decision: null, rightsDirect: 'DISTRIBUTOR', rightsCeiling: 'SUPPLIER,MERCHANT',
  note: null, meta: null, createdAt: '2026-11-14T08:12:00Z', reviewedAt: null, ...extra,
});

export const REQUESTS: OnboardingRequest[] = [
  row('onb-1', 'Namib Distribution', 'DISTRIBUTOR', 'PENDING_REVIEW', { note: 'Walvis Bay depot; bank letter attached.' }),
  row('onb-2', 'Kalahari Supplies', 'SUPPLIER', 'PENDING_REVIEW', { rightsDirect: 'SUPPLIER', rightsCeiling: '' }),
  row('onb-3', 'Swakop Foods', 'SUPPLIER', 'APPROVED',
    { reviewedBy: 'Anna Kambatu', decision: 'APPROVE', reviewedAt: '2026-11-13T10:00:00Z' }),
  row('onb-4', 'Oshana Traders', 'DISTRIBUTOR', 'PENDING_REVIEW', { kind: 'ADMIN_RECOVERY' }),
];

const byStatus = (rows: OnboardingRequest[]) => (p: Readonly<Record<string, unknown>>) =>
  (p['status'] ? rows.filter((r) => r.status === p['status']) : rows);

const LONG = 'Oshakati Regional Wholesale & Cold-Chain Distribution Cooperative (Proprietary) Limited';

export default defineFixtures({
  default: {
    'org.onboarding.list': byStatus(REQUESTS),
    'org.onboarding.decide': (p) => ({
      requestId: String(p['id']), orgId: `org-${String(p['id'])}`,
      status: (p['body'] as { approve?: boolean })?.approve ? 'APPROVED' : 'REJECTED', message: null,
    }),
  },
  empty: { 'org.onboarding.list': [] },
  error: { 'org.onboarding.list': fail(500, { title: 'Server error', detail: 'Onboarding queue unavailable' }) },
  longText: { 'org.onboarding.list': byStatus(REQUESTS.map((r) => ({ ...r, orgName: LONG, note: `${LONG} — ${r.note ?? ''}` }))),
    'org.onboarding.decide': { requestId: 'onb-1', orgId: 'org-onb-1', status: 'APPROVED', message: null } },
  refused: { 'org.onboarding.list': byStatus(REQUESTS),
    'org.onboarding.decide': fail(409, { title: 'Conflict', detail: 'Whoever submitted a request cannot decide it', code: 'MAKER_CANNOT_ACT' }) },
  forbidden: { 'org.onboarding.list': fail(403, { title: 'Forbidden', detail: 'org.onboarding:read' }) },
}, { expect: { forbidden: 'forbidden' } });
