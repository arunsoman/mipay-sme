/**
 * Fixture scenarios for rbac.my-access (MyAccess + the widget catalogue).
 */
import { defineFixtures, fail } from '../../core/fixtures';
import { WIDGETS } from '../new-role/new-role.fixtures';
import type { RbacMyAccessGet } from './my-access.contract';

const held = (row: string, verb: string, rowDescription: string) => ({ id: `${row}:${verb}`, row, verb, rowDescription });

/** Holds: KYC application View + Modify, Organization users View + Create; nothing on the review queue. */
export const ACCESS: RbacMyAccessGet = {
  orgId: 'org-sup-1', orgName: 'Kalahari Supplies', orgType: 'SUPPLIER',
  roles: ['org_admin', 'stock_clerk@org-sup-1'],
  permissions: [
    held('org.user', 'read', 'People of the organization'),
    held('org.user', 'create', 'People of the organization'),
    held('kyc.application', 'read', 'The organization’s KYC application'),
    held('kyc.application', 'modify', 'The organization’s KYC application'),
    held('kyc.application', 'submit', 'The organization’s KYC application'),
    held('rbac.role', 'read', 'Roles the organization may use'),
  ],
};

const LONG = 'Everything about the people of the Oshakati Regional Wholesale & Cold-Chain Distribution Cooperative';

export default defineFixtures({
  default: { 'rbac.my-access.get': ACCESS, 'rbac.my-access.widgets': WIDGETS },
  empty: { 'rbac.my-access.get': { ...ACCESS, roles: [], permissions: [] }, 'rbac.my-access.widgets': WIDGETS },
  error: { 'rbac.my-access.get': fail(500, { title: 'Server error', detail: 'Access unavailable' }) },
  longText: {
    'rbac.my-access.get': { ...ACCESS, orgName: LONG },
    'rbac.my-access.widgets': WIDGETS.map((w) => ({ ...w, label: `${w.label} for regional compliance officers`, description: LONG })),
  },
  forbidden: { 'rbac.my-access.get': fail(403, { title: 'Forbidden', detail: 'not signed in' }) },
  catalogueRefused: {
    'rbac.my-access.get': ACCESS,
    'rbac.my-access.widgets': fail(403, { title: 'Forbidden', detail: 'rbac.role:read' }),
  },
}, { expect: { forbidden: 'forbidden' } });
