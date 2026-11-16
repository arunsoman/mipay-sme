/**
 * Fixture scenarios for rbac.resources (RbacResourceView).
 */
import { defineFixtures, fail } from '../../core/fixtures';
import type { RbacResource } from './resources.contract';

export const RESOURCES: RbacResource[] = [
  { id: 'org.user', service: 'sme-core', description: 'People of an organization', used: ['read', 'create', 'administer', 'delete'],
    tenantTypes: ['MANUFACTURER', 'DISTRIBUTOR', 'SUPPLIER'], endpoints: [
      { verb: 'read', method: 'GET', path: '/api/orgs/me/users', handler: 'OrgController#users' },
      { verb: 'create', method: 'POST', path: '/api/orgs/me/users', handler: 'OrgController#addUser' },
    ] },
  { id: 'kyc.task', service: 'sme-core', description: 'KYC review tasks', used: ['read', 'authorize'], tenantTypes: [],
    endpoints: [{ verb: 'authorize', method: 'POST', path: '/api/kyc/tasks/{taskId}/decision', handler: 'KycController#decide' }] },
];

const LONG = 'Everything a person of the Oshakati Regional Wholesale & Cold-Chain Distribution Cooperative may do with stock';

export default defineFixtures({
  default: { 'rbac.resources.list': RESOURCES },
  empty: { 'rbac.resources.list': [] },
  error: { 'rbac.resources.list': fail(500, { title: 'Server error', detail: 'RBAC unavailable' }) },
  longText: { 'rbac.resources.list': RESOURCES.map((r) => ({ ...r, description: LONG })) },
  forbidden: { 'rbac.resources.list': fail(403, { title: 'Forbidden', detail: 'rbac.role:read' }) },
}, { expect: { forbidden: 'forbidden' } });
