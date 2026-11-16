/**
 * Fixture scenarios for rbac.my-access (MyAccess).
 */
import { defineFixtures, fail } from '../../core/fixtures';
import type { RbacMyAccessGet } from './my-access.contract';

const held = (row: string, verb: string, rowDescription: string) => ({ id: `${row}:${verb}`, row, verb, rowDescription });

export const ACCESS: RbacMyAccessGet = {
  orgId: 'org-sup-1', orgName: 'Kalahari Supplies', orgType: 'SUPPLIER',
  roles: ['org_admin', 'stock_clerk@org-sup-1'],
  permissions: [
    held('org.user', 'read', 'People of the organization'),
    held('org.user', 'create', 'People of the organization'),
    held('kyc.application', 'read', 'The organization’s KYC application'),
    held('rbac.role', 'read', 'Roles the organization may use'),
  ],
};

const LONG = 'Everything about the people of the Oshakati Regional Wholesale & Cold-Chain Distribution Cooperative';

export default defineFixtures({
  default: { 'rbac.my-access.get': ACCESS },
  empty: { 'rbac.my-access.get': { ...ACCESS, roles: [], permissions: [] } },
  error: { 'rbac.my-access.get': fail(500, { title: 'Server error', detail: 'Access unavailable' }) },
  longText: { 'rbac.my-access.get': { ...ACCESS, orgName: LONG, permissions: ACCESS.permissions!.map((p) => ({ ...p, rowDescription: LONG })) } },
  forbidden: { 'rbac.my-access.get': fail(403, { title: 'Forbidden', detail: 'not signed in' }) },
}, { expect: { forbidden: 'forbidden' } });
