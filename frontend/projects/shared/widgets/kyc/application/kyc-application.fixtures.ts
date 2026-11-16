/**
 * Fixture scenarios for kyc.application. The shapes are the backend's (KycApplicationView,
 * SchemaResponse); the schema is the real form (NA-PSP-BUSINESS v3, shared/kyc/kyc-v3-fixture.ts):
 * five tabs, the spec's field names, every field kind the editor draws.
 *
 * TODO(capture): replace with captured responses (`npm run fixtures:capture`, plan §11) once the
 * isolated stack is up; the contract test then guards against drift.
 */
import { defineFixtures, fail } from '../../core/fixtures';
import { KYC_V3_DOC, KYC_V3_SCHEMA } from '../../../kyc/kyc-v3-fixture';

// the real form: the five tabs and the spec's field names (kyc-filed.md)
export const SCHEMA = KYC_V3_SCHEMA;

/** kyc.list.get answers by code: only the bank list is fetched here (the rest is inline). */
const BANKS = { items: [{ value: 'FNB_NAMIBIA', label: 'First National Bank Namibia' }, { value: 'BANK_WINDHOEK', label: 'Bank Windhoek' },
  { value: 'NEDBANK_NA', label: 'Nedbank Namibia' }, { value: 'STANDARD_BANK_NA', label: 'Standard Bank Namibia' }] };
const lists = (p: Readonly<Record<string, unknown>>) => (p['code'] === 'NA_BANK' ? BANKS : fail(404));
const { NA_BANK: _fetched, ...inline } = KYC_V3_SCHEMA.lists;
const FORM = { ...KYC_V3_SCHEMA, lists: inline };

const DOC = KYC_V3_DOC;

export const DRAFT = {
  id: 'kyc-app-1', orgId: 'org-sup-1', orgType: 'SUPPLIER', orgName: 'Kalahari Supplies', parentOrgId: 'org-dist-7',
  treePath: [
    { id: 'org-root', type: 'PLATFORM', name: 'MiPay', depth: 0 },
    { id: 'org-dist-7', type: 'DISTRIBUTOR', name: 'Namib Distribution', depth: 1 },
    { id: 'org-sup-1', type: 'SUPPLIER', name: 'Kalahari Supplies', depth: 2 },
  ],
  depth: 2, isSuspended: false, status: 'DRAFT', application: DOC, submittedApplication: null,
  submittedBy: null, submittedAt: null, decidedBy: null, decidedAt: null, decisionNote: null,
  version: 3, masked: false, canEdit: true, canSubmit: true, canDecide: false,
  createdAt: '2026-11-10T08:00:00Z', updatedAt: '2026-11-14T16:20:00Z',
};

const LONG = 'Namib Wholesale & Distribution (Proprietary) Limited — Walvis Bay Industrial Area Branch Office';

const base = { 'kyc.schema.get': FORM, 'kyc.list.get': lists };

export default defineFixtures({
  default: { ...base, 'kyc.application.get': DRAFT,
    'kyc.application.save': (p) => ({ ...DRAFT, version: DRAFT.version + 1, application: p['body'] }),
    'kyc.application.submit': { ...DRAFT, status: 'IN_REVIEW', canEdit: false, submittedAt: '2026-11-15T09:00:00Z' },
    'kyc.application.withdraw': { ...DRAFT, status: 'WITHDRAWN', canEdit: false } },
  empty: { ...base, 'kyc.application.get': { ...DRAFT, id: null, status: 'NOT_STARTED', application: null, version: 0 } },
  error: { ...base, 'kyc.application.get': fail(500, { title: 'Server error', detail: 'KYC service unavailable' }) },
  longText: { ...base, 'kyc.application.get': { ...DRAFT, orgName: LONG, isSuspended: true,
    treePath: DRAFT.treePath.map((t) => ({ ...t, name: t.depth ? LONG : t.name })),
    application: { ...DOC, businessProfile: { ...DOC.businessProfile, legalBusinessName: LONG, tradingName: LONG } } } },
  inReview: { ...base, 'kyc.application.get': { ...DRAFT, status: 'IN_REVIEW', canEdit: false,
    submittedApplication: DOC, submittedAt: '2026-11-15T09:00:00Z' } },
  masked: { ...base, 'kyc.application.get': { ...DRAFT, masked: true, canEdit: false, status: 'IN_REVIEW', submittedApplication:
    { ...DOC, bankAccounts: [{ ...DOC.bankAccounts[0], accountNumber: '•••••••6789' }] } } },
  invalid: { ...base, 'kyc.application.get': DRAFT,
    'kyc.application.save': fail(422, { code: 'KYC_INVALID', title: 'Invalid',
      detail: 'ubos[0].dateOfBirth: is required; bankAccounts[0].accountNumber: must be 6-34 characters' }) },
  conflict: { ...base, 'kyc.application.get': DRAFT,
    'kyc.application.save': fail(409, { code: 'KYC_VERSION_CONFLICT', title: 'Conflict', detail: 'version 3 is stale' }) },
  forbidden: { ...base, 'kyc.application.get': DRAFT, 'kyc.schema.get': fail(403, { title: 'Forbidden', detail: 'kyc.form:read' }) },
}, {
  // a not-started application is content (the "Start" view), not an empty widget
  expect: { empty: 'ready', forbidden: 'forbidden' },
});
