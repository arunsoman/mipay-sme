/**
 * Fixture scenarios for org.network (OrgNodeRow tree, SuspensionRow).
 */
import { defineFixtures, fail } from '../../core/fixtures';
import type { OrgNode } from './network.contract';

const node = (id: string, name: string, type: string, extra: Partial<OrgNode> = {}): OrgNode => ({
  id, type, name, lifecycle: 'ACTIVE', effectiveStatus: 'ACTIVE', activeHolds: 0, holds: [], onboardedBy: 'Anna Kambatu',
  onboardedAt: '2026-10-01T08:00:00Z', effectiveRights: [type], users: [], userCount: 3,
  subtree: { descendants: 0, activeDescendants: 0, users: 3, activeUsers: 3 },
  rights: { direct: type, ceiling: '', effDirect: type, effCeiling: '' }, children: [], ...extra,
});

export const TREE: OrgNode = node('org-mfg-1', 'Swakop Foods Manufacturing', 'MANUFACTURER', {
  subtree: { descendants: 3, activeDescendants: 2, users: 11, activeUsers: 10 },
  rights: { direct: 'MANUFACTURER', ceiling: 'DISTRIBUTOR,SUPPLIER', effDirect: 'MANUFACTURER', effCeiling: 'DISTRIBUTOR,SUPPLIER' },
  children: [
    node('org-dist-7', 'Namib Distribution', 'DISTRIBUTOR', {
      subtree: { descendants: 1, activeDescendants: 0, users: 4, activeUsers: 4 },
      children: [node('org-sup-1', 'Kalahari Supplies', 'SUPPLIER', {
        lifecycle: 'ACTIVE', effectiveStatus: 'SUSPENDED', activeHolds: 1,
        holds: [{ id: 'hold-3', reason: 'Bank account under review', createdAt: '2026-11-12T08:00:00Z', createdBy: 'Anna Kambatu', releasedAt: null }],
      })],
    }),
    node('org-dist-8', 'Oshana Traders', 'DISTRIBUTOR'),
  ],
});

const LONG = 'Oshakati Regional Wholesale & Cold-Chain Distribution Cooperative (Proprietary) Limited';

export default defineFixtures({
  default: {
    'org.tree.get': TREE,
    'org.suspend': (p) => ({ id: 'hold-9', entityType: 'ORG', entityId: String(p['id']),
      reason: String((p['body'] as { reason?: string })?.reason ?? ''), suspendedAt: '2026-11-15T10:00:00Z', liftedAt: null }),
    'org.suspension.lift': (p) => [{ id: String(p['sid']), entityType: 'ORG', entityId: 'org-sup-1', reason: 'Bank account under review',
      suspendedAt: '2026-11-12T08:00:00Z', liftedAt: '2026-11-15T10:00:00Z' }],
  },
  empty: { 'org.tree.get': { ...TREE, children: [] } },
  error: { 'org.tree.get': fail(500, { title: 'Server error', detail: 'Org tree unavailable' }) },
  longText: { 'org.tree.get': { ...TREE, name: LONG, children: [node('org-x', LONG, 'DISTRIBUTOR')] } },
  refused: { 'org.tree.get': TREE,
    'org.suspend': fail(403, { title: 'Forbidden', detail: 'Only an org above it may suspend it', code: 'ANCESTOR_REQUIRED' }),
    'org.suspension.lift': fail(409, { title: 'Conflict', detail: 'Already lifted' }) },
  forbidden: { 'org.tree.get': fail(403, { title: 'Forbidden', detail: 'org.tree:read' }) },
}, { expect: { forbidden: 'forbidden', empty: 'ready' } });
