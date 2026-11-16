/**
 * Fixture scenarios for kyc.status (KycApplicationView).
 */
import { defineFixtures, fail } from '../../core/fixtures';
import type { KycMineGet } from './status.contract';

export const VIEW: KycMineGet = {
  id: 'kyc-org-dist-7', orgId: 'org-dist-7', orgType: 'DISTRIBUTOR', orgName: 'Namib Distribution', parentOrgId: 'org-mfg-1',
  treePath: [{ id: 'org-mfg-1', type: 'MANUFACTURER', name: 'Swakop Foods Manufacturing', depth: 0 },
             { id: 'org-dist-7', type: 'DISTRIBUTOR', name: 'Namib Distribution', depth: 1 }],
  depth: 1, isSuspended: false, status: 'DRAFT', application: { businessProfile: { legalName: 'Namib Distribution' } },
  submittedApplication: null, submittedBy: null, submittedAt: null, decidedBy: null, decidedAt: null, decisionNote: null,
  version: 3, masked: false, canEdit: true, canSubmit: true, canDecide: false,
  createdAt: '2026-11-10T08:00:00Z', updatedAt: '2026-11-14T09:30:00Z',
};

const LONG = 'Oshakati Regional Wholesale & Cold-Chain Distribution Cooperative (Proprietary) Limited';

export default defineFixtures({
  default: { 'kyc.mine.get': VIEW, 'kyc.mine.upgrade': { ...VIEW, version: 4 } },
  empty: { 'kyc.mine.get': { ...VIEW, id: null, status: 'NOT_STARTED', application: null, version: 0 } },
  error: { 'kyc.mine.get': fail(500, { title: 'Server error', detail: 'KYC unavailable' }) },
  approved: { 'kyc.mine.get': { ...VIEW, status: 'APPROVED', canEdit: false, submittedAt: '2026-11-11T08:00:00Z',
    decidedAt: '2026-11-12T10:00:00Z', decisionNote: 'All documents in order.' } },
  longText: { 'kyc.mine.get': { ...VIEW, orgName: LONG, status: 'REJECTED', canEdit: false, decisionNote: `${LONG}: ${LONG}` } },
  refused: { 'kyc.mine.get': VIEW,
    'kyc.mine.upgrade': fail(409, { title: 'Conflict', detail: 'Only a draft can be upgraded', code: 'KYC_NOT_DRAFT' }) },
  forbidden: { 'kyc.mine.get': fail(403, { title: 'Forbidden', detail: 'kyc.application:read' }) },
}, { expect: { forbidden: 'forbidden', empty: 'ready' } });
