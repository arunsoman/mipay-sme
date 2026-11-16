/**
 * Fixture scenarios for org.onboard-child: GrantableView, the five-tab KYC form
 * (shared/kyc/kyc-v3-fixture.ts) and the one onboarding call's answer.
 */
import { defineFixtures, fail } from '../../core/fixtures';
import { KYC_V3_SCHEMA } from '../../../kyc/kyc-v3-fixture';

export const GRANTABLE = {
  effDirect: ['MANUFACTURER'],
  children: [
    { type: 'DISTRIBUTOR', allowedCeiling: ['SUPPLIER', 'MERCHANT'], defaultDirect: ['DISTRIBUTOR'] },
    { type: 'SUPPLIER', allowedCeiling: [], defaultDirect: ['SUPPLIER'] },
  ],
  roles: [{ name: 'org_admin', description: 'Administers the organization', baseline: true }],
  baselineRoles: ['org_admin'],
};

/** The system makes the admin login from the Legal Business Name. */
const onboarded = (p: Readonly<Record<string, unknown>>) => {
  const kyc = (p['body'] as { kyc?: { businessProfile?: { legalBusinessName?: string } } })?.kyc;
  const name = (kyc?.businessProfile?.legalBusinessName ?? 'org').toLowerCase().replace(/[^a-z0-9]/g, '');
  return { orgId: 'org-new-77', requestId: 'onb-77', status: 'SUBMITTED', adminUsername: name };
};

// every list the form needs is inline in the schema; a list asked for anyway answers empty
const base = { 'org.grantable.get': GRANTABLE, 'kyc.schema.type.get': KYC_V3_SCHEMA, 'kyc.list.get': { items: [] } };

export default defineFixtures({
  default: { ...base, 'org.children.onboard': onboarded },
  empty: { ...base, 'org.grantable.get': { ...GRANTABLE, children: [] } },
  error: { ...base, 'org.grantable.get': fail(500, { title: 'Server error', detail: 'Rights service unavailable' }) },
  longText: { ...base, 'org.grantable.get': { ...GRANTABLE, children: [{ type: 'DISTRIBUTOR', allowedCeiling: [], defaultDirect: [] }] },
    'org.children.onboard': { orgId: 'org-new-78', requestId: 'onb-78', status: 'SUBMITTED',
      adminUsername: 'oshakatiregionalwholesale-cc201904512' } },
  invalid: { ...base, 'org.children.onboard': fail(422, { title: 'Invalid', code: 'KYC_INVALID',
    detail: 'ubos[0].dateOfBirth: is required; bankAccounts[0].accountNumber: must be 6-34 characters' }) },
  refused: { ...base, 'org.children.onboard': fail(409, { title: 'Conflict', detail: 'Phone already registered', code: 'DUPLICATE_PHONE' }) },
  forbidden: { ...base, 'org.grantable.get': fail(403, { title: 'Forbidden', detail: 'org.rights:read' }) },
}, { expect: { forbidden: 'forbidden' } });
