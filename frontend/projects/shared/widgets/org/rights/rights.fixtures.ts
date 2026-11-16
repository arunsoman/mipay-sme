/**
 * Fixture scenarios for org.rights (OrgRightsView).
 */
import { defineFixtures, fail } from '../../core/fixtures';
import type { OrgRightsView } from './rights.contract';

export const RIGHTS: OrgRightsView = {
  direct: 'DISTRIBUTOR,SUPPLIER', ceiling: 'MERCHANT,SUPPLIER',
  effDirect: 'DISTRIBUTOR,SUPPLIER', effCeiling: 'MERCHANT,SUPPLIER',
};

const narrowed = (p: Readonly<Record<string, unknown>>) => {
  const b = (p['body'] ?? {}) as { rightsDirect?: string[]; rightsCeiling?: string[] };
  const d = (b.rightsDirect ?? []).join(','), c = (b.rightsCeiling ?? []).join(',');
  return { direct: d, ceiling: c, effDirect: d, effCeiling: c };
};

export default defineFixtures({
  default: { 'org.rights.get': RIGHTS, 'org.rights.narrow': narrowed },
  empty: { 'org.rights.get': { direct: '', ceiling: '', effDirect: '', effCeiling: '' } },
  error: { 'org.rights.get': fail(500, { title: 'Server error', detail: 'Rights service unavailable' }) },
  longText: { 'org.rights.get': { direct: 'DISTRIBUTOR,SUPPLIER,MERCHANT,MANUFACTURER,LENDER', ceiling: 'DISTRIBUTOR,SUPPLIER,MERCHANT,MANUFACTURER,LENDER',
    effDirect: 'DISTRIBUTOR,SUPPLIER,MERCHANT,MANUFACTURER,LENDER', effCeiling: 'DISTRIBUTOR,SUPPLIER,MERCHANT,MANUFACTURER,LENDER' } },
  refused: { 'org.rights.get': RIGHTS,
    'org.rights.narrow': fail(403, { title: 'Forbidden', detail: 'Only an org above it may narrow its rights', code: 'ANCESTOR_REQUIRED' }) },
  forbidden: { 'org.rights.get': fail(403, { title: 'Forbidden', detail: 'org.rights:read' }) },
}, { expect: { forbidden: 'forbidden', empty: 'ready' } });
