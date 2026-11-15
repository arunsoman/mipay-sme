/**
 * Fixture scenarios for admin.organizations (shape: a ListQuery page of OrgRow).
 * Answered by fixturePage, which searches, filters, sorts and pages like the server.
 */
import { defineFixtures, fail } from '../../core/fixtures';
import { fixturePage } from '../../core/server-list';

const org = (id: string, name: string, type: string, status: string, kycStatus: string | null, users: number,
             preset: string | null, features: number, warnings: string[], parentName: string | null = 'MiPay') => ({
  id, name, type, status, kycStatus, parentId: parentName ? 'org-root' : null, parentName, users, preset, features, warnings,
  createdAt: '2026-11-01T09:00:00Z',
});

export const ORGS = [
  org('org-root', 'MiPay', 'PLATFORM', 'ACTIVE', 'APPROVED', 6, null, 0, [], null),
  org('org-sup-1', 'Kalahari Supplies', 'SUPPLIER', 'ACTIVE', 'APPROVED', 4, 'kyc_only', 2, ["Can't add users"]),
  org('org-sup-2', 'Etosha Traders', 'SUPPLIER', 'ACTIVE', 'APPROVED', 9, 'full', 11, []),
  org('org-dist-7', 'Namib Distribution', 'DISTRIBUTOR', 'PENDING', 'PENDING', 1, 'full', 11, []),
  org('org-sup-3', 'Rundu Fresh Produce', 'SUPPLIER', 'ACTIVE', 'APPROVED', 2, null, 0, ['Access not set']),
  org('org-mfg-2', 'Swakop Foods Manufacturing', 'MANUFACTURER', 'BLOCKED', 'REJECTED', 3, 'essentials', 4, []),
];
type Row = (typeof ORGS)[number];

/** The same fields as AdminConsoleController.ORG_FIELDS. */
const search = (rows: Row[]) => fixturePage(rows, {
  search: [(o) => o.name, (o) => o.parentName, (o) => o.id],
  fields: {
    name: (o) => o.name, type: (o) => o.type, status: (o) => o.status, kycStatus: (o) => o.kycStatus,
    parentName: (o) => o.parentName, users: (o) => o.users, preset: (o) => o.preset, features: (o) => o.features,
    warnings: (o) => o.warnings, createdAt: (o) => o.createdAt,
  },
});

const LONG = 'Katima Mulilo Cross-Border Agricultural Trading & Logistics Cooperative (Proprietary) Limited';

export default defineFixtures({
  default: { 'admin.organizations.search': search(ORGS) },
  empty: { 'admin.organizations.search': search([]) },
  error: { 'admin.organizations.search': fail(500, { title: 'Server error', detail: 'Directory unavailable' }) },
  longText: { 'admin.organizations.search': search(ORGS.map((o) => ({ ...o, name: LONG, parentName: LONG,
    warnings: o.warnings.length ? ['Access not set', "Can't add users", "Can't complete KYC"] : [] }))) },
  forbidden: { 'admin.organizations.search': fail(403, { title: 'Forbidden', detail: 'admin.org_directory:read' }) },
}, { expect: { forbidden: 'forbidden' } });
