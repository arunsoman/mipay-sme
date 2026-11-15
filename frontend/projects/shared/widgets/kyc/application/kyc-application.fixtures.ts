/**
 * Fixture scenarios for kyc.application. The shapes are the backend's (KycApplicationView,
 * SchemaResponse); the schema is a trimmed supplier profile with every field kind the editor
 * draws: text, enum, date, boolean, percent, country, chained bank → branch lists, a LIST
 * section of beneficial owners.
 *
 * TODO(capture): replace with captured responses (`npm run fixtures:capture`, plan §11) once the
 * isolated stack is up; the contract test then guards against drift.
 */
import { defineFixtures, fail } from '../../core/fixtures';

const field = (key: string, label: string, dataType: string, extra: Record<string, unknown> = {}) => ({
  key, label, helpText: null, dataType, listCode: null, listParentField: null, required: 'ALWAYS',
  requiredWhen: null, visibleWhen: null, pattern: null, minLength: null, maxLength: null, minValue: null,
  maxValue: null, derivedFrom: null, canonicalTag: null, sensitivity: 'NORMAL', sensitive: false, ...extra,
});

export const SCHEMA = {
  profile: { code: 'NA-PSP-SUPPLIER', country: 'NA', licenceType: 'PSP' },
  schema: { code: 'KYC-ORG', version: 3 },
  sections: [
    { key: 'businessProfile', title: 'Business', kind: 'OBJECT', step: 1, minItems: null, maxItems: null, visible: true, fields: [
      field('legalName', 'Registered name', 'TEXT', { maxLength: 160 }),
      field('tradingName', 'Trading name', 'TEXT', { required: 'NEVER' }),
      field('entityType', 'Entity type', 'ENUM', { listCode: 'ENTITY_TYPE' }),
      field('registrationNumber', 'Registration number', 'TEXT', { pattern: '^[A-Z0-9/]{4,20}$', helpText: 'As on the BIPA certificate' }),
      field('incorporatedOn', 'Date of incorporation', 'DATE'),
      field('vatRegistered', 'Registered for VAT', 'BOOLEAN'),
    ] },
    { key: 'bank', title: 'Receiving bank account', kind: 'OBJECT', step: 2, minItems: null, maxItems: null, visible: true, fields: [
      field('bankCode', 'Bank', 'ENUM', { listCode: 'BANK' }),
      field('branchCode', 'Branch', 'ENUM', { listCode: 'BANK_BRANCH', listParentField: 'bankCode' }),
      field('accountName', 'Account holder', 'TEXT'),
      field('accountNumber', 'Account number', 'TEXT', { pattern: '^[0-9]{8,11}$', sensitive: true, sensitivity: 'HIGH' }),
    ] },
    { key: 'ubos', title: 'Owners and controllers', kind: 'LIST', step: 3, minItems: 1, maxItems: 10, visible: true, fields: [
      field('fullName', 'Full name', 'TEXT'),
      field('dateOfBirth', 'Date of birth', 'DATE'),
      field('nationality', 'Nationality', 'COUNTRY', { listCode: 'COUNTRY' }),
      field('ownershipPct', 'Ownership', 'PERCENT', { minValue: 0, maxValue: 100, canonicalTag: 'UBO_OWNERSHIP' }),
    ] },
  ],
  rules: [],
  submitRules: [],
  lists: {
    ENTITY_TYPE: [
      { value: 'PTY_LTD', label: 'Private company (Pty) Ltd' }, { value: 'CC', label: 'Close corporation' },
      { value: 'SOLE', label: 'Sole proprietor' }, { value: 'PARTNERSHIP', label: 'Partnership' },
    ],
    COUNTRY: [{ value: 'NA', label: 'Namibia' }, { value: 'ZA', label: 'South Africa' }, { value: 'BW', label: 'Botswana' }],
  },
  params: { uboThresholdPct: 25 },
};

const BANKS = { items: [{ value: 'FNB', label: 'First National Bank Namibia' }, { value: 'BWK', label: 'Bank Windhoek' },
  { value: 'NED', label: 'Nedbank Namibia' }, { value: 'SBN', label: 'Standard Bank Namibia' }] };
const BRANCHES: Record<string, { items: { value: string; label: string }[] }> = {
  FNB: { items: [{ value: '280172', label: 'Windhoek Main' }, { value: '281872', label: 'Walvis Bay' }] },
  BWK: { items: [{ value: '483772', label: 'Independence Avenue' }, { value: '482172', label: 'Oshakati' }] },
  NED: { items: [{ value: '461038', label: 'Windhoek' }] },
  SBN: { items: [{ value: '087373', label: 'Swakopmund' }] },
};

/** kyc.list.get answers by code (and parent for dependent lists). */
const lists = (p: Readonly<Record<string, unknown>>) =>
  p['code'] === 'BANK' ? BANKS : p['code'] === 'BANK_BRANCH' ? (BRANCHES[String(p['parent'])] ?? { items: [] }) : fail(404);

const DOC = {
  businessProfile: { legalName: 'Kalahari Supplies (Pty) Ltd', tradingName: 'Kalahari Supplies', entityType: 'PTY_LTD',
    registrationNumber: '2019/0412', incorporatedOn: '2019-03-14', vatRegistered: true },
  bank: { bankCode: 'FNB', branchCode: '280172', accountName: 'Kalahari Supplies (Pty) Ltd', accountNumber: '62123456789' },
  ubos: [{ fullName: 'Ndapewa Shikongo', dateOfBirth: '1984-07-02', nationality: 'NA', ownershipPct: 60 }],
};

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

const base = { 'kyc.schema.get': SCHEMA, 'kyc.list.get': lists };

export default defineFixtures({
  default: { ...base, 'kyc.application.get': DRAFT,
    'kyc.application.save': (p) => ({ ...DRAFT, version: DRAFT.version + 1, application: p['body'] }),
    'kyc.application.submit': { ...DRAFT, status: 'IN_REVIEW', canEdit: false, submittedAt: '2026-11-15T09:00:00Z' },
    'kyc.application.withdraw': { ...DRAFT, status: 'WITHDRAWN', canEdit: false } },
  empty: { ...base, 'kyc.application.get': { ...DRAFT, id: null, status: 'NOT_STARTED', application: null, version: 0 } },
  error: { ...base, 'kyc.application.get': fail(500, { title: 'Server error', detail: 'KYC service unavailable' }) },
  longText: { ...base, 'kyc.application.get': { ...DRAFT, orgName: LONG, isSuspended: true,
    treePath: DRAFT.treePath.map((t) => ({ ...t, name: t.depth ? LONG : t.name })),
    application: { ...DOC, businessProfile: { ...DOC.businessProfile, legalName: LONG, tradingName: LONG } } } },
  inReview: { ...base, 'kyc.application.get': { ...DRAFT, status: 'IN_REVIEW', canEdit: false,
    submittedApplication: DOC, submittedAt: '2026-11-15T09:00:00Z' } },
  masked: { ...base, 'kyc.application.get': { ...DRAFT, masked: true, canEdit: false, status: 'IN_REVIEW', submittedApplication:
    { ...DOC, bank: { ...DOC.bank, accountNumber: '•••••••6789' } } } },
  invalid: { ...base, 'kyc.application.get': DRAFT,
    'kyc.application.save': fail(422, { code: 'KYC_INVALID', title: 'Invalid',
      detail: 'ubos[0].dateOfBirth: required; bank.accountNumber: must be 8-11 digits' }) },
  conflict: { ...base, 'kyc.application.get': DRAFT,
    'kyc.application.save': fail(409, { code: 'KYC_VERSION_CONFLICT', title: 'Conflict', detail: 'version 3 is stale' }) },
  forbidden: { ...base, 'kyc.application.get': DRAFT, 'kyc.schema.get': fail(403, { title: 'Forbidden', detail: 'kyc.form:read' }) },
}, {
  // a not-started application is content (the "Start" view), not an empty widget
  expect: { empty: 'ready', forbidden: 'forbidden' },
});
