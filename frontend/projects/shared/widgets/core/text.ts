/**
 * Plain text helpers the widgets share (no Angular: functions only), so every widget words
 * statuses, dates and role names the same way.
 */

/** "PENDING_REVIEW" → "Pending review"; null → "—" */
export function words(s: string | null | undefined): string {
  return s ? s.charAt(0) + s.slice(1).toLowerCase().replace(/_/g, ' ') : '—';
}

/** An ISO date-time as "14 Nov 2026"; null → "—" */
export function when(iso: string | null | undefined): string {
  return iso ? new Date(iso).toLocaleDateString('en-NA', { day: 'numeric', month: 'short', year: 'numeric' }) : '—';
}

/** An ISO date-time as "14 Nov 2026, 08:12"; null → "—" */
export function whenTime(iso: string | null | undefined): string {
  return iso
    ? new Date(iso).toLocaleString('en-NA', { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' })
    : '—';
}

/** "organization_access" → "Organization access" */
export function roleName(r: string | null | undefined): string {
  return r ? (r.charAt(0).toUpperCase() + r.slice(1)).replace(/_/g, ' ') : '—';
}

/** "a,b, c" → ["a", "b", "c"] (the server joins some lists with commas) */
export function splitList(s: string | null | undefined): string[] {
  return (s ?? '').split(',').map((x) => x.trim()).filter(Boolean);
}
