/**
 * Fixture scenarios for ops.audit (AuditPage). The fixture pages the rows as the server does.
 */
import { defineFixtures, fail } from '../../core/fixtures';
import type { AuditRow } from './audit.contract';

const entry = (i: number, action: string, entityType: string, entityId: string, meta: string | null): AuditRow => ({
  id: `aud-${i}`, actorRole: i % 2 ? 'ADMIN' : 'PLATFORM_STAFF', action, entityType, entityId, meta, simulated: false,
  createdAt: new Date(Date.UTC(2026, 10, 15, 10, 0) - i * 3_600_000).toISOString(),
});

export const ENTRIES: AuditRow[] = Array.from({ length: 30 }, (_, i) => [
  entry(i, 'ROLE_ASSIGNED', 'USER', 'u-lukas', 'stock_clerk@org-sup-1'),
  entry(i, 'KYC_APPROVED', 'ORG', 'org-sup-1', 'Compliance review passed'),
  entry(i, 'ORG_SUSPENDED', 'ORG', 'org-dist-8', 'Unpaid invoices over 90 days'),
][i % 3]);

const paged = (rows: AuditRow[]) => (p: Readonly<Record<string, unknown>>) => {
  const page = Number(p['page'] ?? 0), size = Number(p['size'] ?? 25);
  return { rows: rows.slice(page * size, page * size + size), totalElements: rows.length, page };
};

const LONG = 'Role stock_clerk given at the Oshakati Regional Wholesale & Cold-Chain Distribution Cooperative depot by its administrator';

export default defineFixtures({
  default: { 'ops.audit.page': paged(ENTRIES) },
  empty: { 'ops.audit.page': paged([]) },
  error: { 'ops.audit.page': fail(500, { title: 'Server error', detail: 'Audit store unavailable' }) },
  longText: { 'ops.audit.page': paged(ENTRIES.map((e) => ({ ...e, meta: LONG }))) },
  forbidden: { 'ops.audit.page': fail(403, { title: 'Forbidden', detail: 'ops.audit:read' }) },
}, { expect: { forbidden: 'forbidden' } });
