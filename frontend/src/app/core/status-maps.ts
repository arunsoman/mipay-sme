/**
 * THE status legend: one file, used by status-badge atom and any logic that
 * needs "is this state positive/negative". Adding a wire status = one line here.
 */
const MAP: Record<string, { color: string; label?: string }> = {
  // positive
  CONFIRMED: { color: 'var(--st-confirmed)' },
  PAID: { color: 'var(--st-paid)' },
  ACTIVE: { color: 'var(--st-active)' },
  APPROVED: { color: 'var(--st-approved)' },
  MATCHED: { color: 'var(--st-matched)' },
  VERIFIED: { color: 'var(--st-verified)' },
  PROCESSED: { color: 'var(--st-confirmed)' },
  SETTLED: { color: 'var(--st-confirmed)' },
  SUCCESS: { color: 'var(--st-confirmed)' },
  // neutral / informational
  OPEN: { color: 'var(--st-open)' },
  PENDING: { color: 'var(--st-pending)' },
  PENDING_APPROVAL: { color: 'var(--st-pending_approval)' },
  ISSUED: { color: 'var(--st-issued)' },
  IN_PROGRESS: { color: 'var(--st-in_progress)' },
  SUBMITTED: { color: 'var(--st-open)' },
  REQUESTED: { color: 'var(--st-open)' },
  RECEIVED: { color: 'var(--st-open)' },
  DRAFT: { color: 'var(--st-open)' },
  // warning family
  HELD: { color: 'var(--st-held)' },
  REPAYING: { color: 'var(--st-repaying)' },
  PROCESSING: { color: 'var(--st-processing)' },
  OFFERED: { color: 'var(--st-held)' },
  ACCEPTED: { color: 'var(--st-held)' },
  FUNDED: { color: 'var(--st-verified)' },
  BREAKS_FOUND: { color: 'var(--st-breaks_found)' },
  TIMED_OUT: { color: 'var(--st-timed_out)' },
  LOW_STOCK: { color: 'var(--st-held)' },
  OUT_OF_STOCK: { color: '#dc2626' },
  // negative
  FAILED: { color: 'var(--st-failed)' },
  REVOKED: { color: 'var(--st-revoked)' },
  SUSPENDED: { color: 'var(--st-suspended)' },
  REJECTED: { color: 'var(--st-rejected)' },
  DEFAULTED: { color: 'var(--st-suspended)' },
  BLOCKED: { color: 'var(--st-suspended)' },
  // terminal-neutral
  CLOSED: { color: 'var(--st-closed)' },
  RESOLVED: { color: 'var(--st-resolved)' },
  RETIRED: { color: 'var(--st-closed)' },
  WITHDRAWN: { color: 'var(--st-closed)' },
  CONVERTED: { color: 'var(--st-closed)' },
  RELEASED: { color: 'var(--st-closed)' },
  EXPIRED: { color: 'var(--st-timed_out)' },
};

export function statusColor(status: string): string {
  return (MAP[status?.toUpperCase()] ?? { color: 'var(--st-closed)' }).color;
}

export function statusLabel(status: string): string | undefined {
  return MAP[status?.toUpperCase()]?.label;
}
