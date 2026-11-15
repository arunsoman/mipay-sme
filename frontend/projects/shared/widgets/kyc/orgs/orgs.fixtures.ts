/**
 * Fixture scenarios for kyc.orgs (shape: KycSummaryRow - see orgs.contract.ts).
 */
import { defineFixtures, fail } from '../../core/fixtures';

const row = (id: string, orgName: string, orgType: string, status: string, updatedAt: string,
             extra: Partial<{ isSuspended: boolean; submittedAt: string | null; decidedAt: string | null; parent: string }> = {}) => ({
  id: `kyc-${id}`, orgId: id, orgType, orgName, parentOrgId: extra.parent ?? 'org-dist-7',
  treePath: `MiPay › Namib Distribution › ${orgName}`, depth: 2, isSuspended: extra.isSuspended ?? false, status,
  submittedAt: extra.submittedAt ?? null, decidedAt: extra.decidedAt ?? null, updatedAt,
});

const ORGS = [
  row('org-sup-1', 'Kalahari Supplies', 'SUPPLIER', 'IN_REVIEW', '2026-11-14T16:20:00Z', { submittedAt: '2026-11-14T08:00:00Z' }),
  row('org-sup-2', 'Etosha Traders', 'SUPPLIER', 'APPROVED', '2026-11-10T09:15:00Z',
    { submittedAt: '2026-11-02T10:00:00Z', decidedAt: '2026-11-10T09:15:00Z' }),
  row('org-sup-3', 'Rundu Fresh Produce', 'SUPPLIER', 'DRAFT', '2026-11-15T07:45:00Z'),
  row('org-dist-9', 'Okahandja Wholesale', 'DISTRIBUTOR', 'ENTRY_CHECK', '2026-11-13T12:00:00Z',
    { submittedAt: '2026-11-13T12:00:00Z', parent: 'org-mfg-2' }),
  row('org-sup-4', 'Lüderitz Marine Foods', 'SUPPLIER', 'REJECTED', '2026-11-09T14:30:00Z',
    { submittedAt: '2026-11-05T09:00:00Z', decidedAt: '2026-11-09T14:30:00Z' }),
  row('org-mfg-2', 'Swakop Foods Manufacturing', 'MANUFACTURER', 'APPROVED', '2026-10-28T11:00:00Z',
    { submittedAt: '2026-10-20T09:00:00Z', decidedAt: '2026-10-28T11:00:00Z', parent: 'org-root' }),
];

const LONG = 'Katima Mulilo Cross-Border Agricultural Trading & Logistics Cooperative (Proprietary) Limited';

export default defineFixtures({
  default: { 'kyc.orgs.list': ORGS },
  empty: { 'kyc.orgs.list': [] },
  error: { 'kyc.orgs.list': fail(500, { title: 'Server error', detail: 'KYC directory unavailable' }) },
  longText: { 'kyc.orgs.list': ORGS.map((o) => ({ ...o, orgName: LONG, treePath: `MiPay › ${LONG} › ${LONG}` })) },
  forbidden: { 'kyc.orgs.list': fail(403, { title: 'Forbidden', detail: 'kyc.application:read' }) },
}, { expect: { forbidden: 'forbidden' } });
