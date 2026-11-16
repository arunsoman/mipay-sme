/**
 * Fixture scenarios for org.role-ceiling (RoleCeilingView).
 */
import { defineFixtures, fail } from '../../core/fixtures';
import type { RoleCeilingView } from './role-ceiling.contract';

export const CEILING: RoleCeilingView = {
  orgId: 'org-dist-7', explicit: true, inheritedFrom: null, unrestricted: false,
  roles: ['org_admin', 'org_viewer', 'stock_clerk'], delegableRoles: ['org_viewer'], baselineRoles: ['org_admin'],
  parentHandDown: ['org_admin', 'org_viewer', 'stock_clerk', 'cashier'],
  catalogue: [
    { name: 'org_admin', description: 'Administers the organization' },
    { name: 'org_viewer', description: 'Sees the organization, changes nothing' },
    { name: 'stock_clerk', description: 'Counts and receives stock' },
    { name: 'cashier', description: 'Takes payments at the counter' },
    { name: 'kyc_reviewer', description: 'Reviews KYC applications (MiPay only)' },
  ],
};

const saved = (p: Readonly<Record<string, unknown>>) => {
  const b = (p['body'] ?? {}) as { roles?: string[] | null; delegableRoles?: string[] | null; inherit?: boolean | null };
  return b.inherit
    ? { ...CEILING, explicit: false, inheritedFrom: 'Swakop Foods Manufacturing', roles: CEILING.parentHandDown, delegableRoles: CEILING.parentHandDown }
    : { ...CEILING, roles: b.roles ?? [], delegableRoles: b.delegableRoles ?? [] };
};

export default defineFixtures({
  default: { 'org.ceiling.get': CEILING, 'org.ceiling.set': saved },
  empty: { 'org.ceiling.get': { ...CEILING, catalogue: [], roles: [], delegableRoles: [] } },
  error: { 'org.ceiling.get': fail(500, { title: 'Server error', detail: 'RBAC unavailable', code: 'RBAC_UNAVAILABLE' }) },
  longText: { 'org.ceiling.get': { ...CEILING, explicit: false,
    inheritedFrom: 'Oshakati Regional Wholesale & Cold-Chain Distribution Cooperative (Proprietary) Limited',
    catalogue: CEILING.catalogue.map((c) => ({ ...c, name: `${c.name}_for_the_oshakati_regional_cold_chain_depot` })) } },
  inherited: { 'org.ceiling.get': { ...CEILING, explicit: false, inheritedFrom: 'Swakop Foods Manufacturing' } },
  refused: { 'org.ceiling.get': CEILING,
    'org.ceiling.set': fail(403, { title: 'Forbidden', detail: 'Only an org above it', code: 'ANCESTOR_REQUIRED' }) },
  forbidden: { 'org.ceiling.get': fail(403, { title: 'Forbidden', detail: 'org.rights:read' }) },
}, { expect: { forbidden: 'forbidden', empty: 'ready' } });
