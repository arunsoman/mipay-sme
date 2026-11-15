/**
 * Fixture scenarios for org.access (shape: AccessView, see access.contract.ts).
 */
import { defineFixtures, fail } from '../../core/fixtures';

const FEATURES = [
  ['home', 'Home', ['Dashboard']],
  ['buying', 'Buying', ['Purchase orders', 'Suppliers']],
  ['catalogue', 'Catalogue and stock', ['Products', 'Stock levels']],
  ['money', 'Money', ['Balances', 'Withdrawals']],
  ['onboarding', 'Onboarding', ['Organization application', 'KYC and documents', 'Verification status']],
  ['team', 'Team and access', ['People and invitations', 'Roles and assignments', 'Security settings']],
  ['help', 'Help and support', ['Support cases']],
] as const;
const ALL = FEATURES.map(([id]) => id as string);
const PRESETS = [
  { code: 'full', label: 'Full access', description: "Every menu of the organization's portal.", features: ALL },
  { code: 'essentials', label: 'Essentials', description: 'Home, onboarding and KYC, team and access, help.', features: ['home', 'onboarding', 'team', 'help'] },
  { code: 'kyc_only', label: 'KYC only', description: 'Onboarding and KYC, and help. The admin cannot add users.', features: ['onboarding', 'help'] },
];

/** What the server answers for a set of switched-on features. */
export const viewOf = (on: readonly string[], extra: Partial<{ applyError: string | null; orgName: string; partial: string[] }> = {}) => {
  const preset = PRESETS.find((p) => p.features.length === on.length && p.features.every((f) => on.includes(f)))?.code ?? null;
  const warnings: string[] = [];
  if (!on.includes('team')) warnings.push("Can't add users");
  if (!on.includes('onboarding')) warnings.push("Can't complete KYC");
  if (extra.applyError) warnings.push('Access not applied');
  return {
    orgId: 'org-sup-1', orgName: extra.orgName ?? 'Kalahari Supplies', orgType: 'SUPPLIER', preset,
    features: FEATURES.map(([id, label, screens]) => ({ id, label, screens: [...screens], on: on.includes(id),
      partial: (extra.partial ?? []).includes(id) })),
    presets: PRESETS, warnings, role: 'organization_access@org-sup-1',
    updatedAt: '2026-11-15T09:00:00Z', appliedAt: extra.applyError ? null : '2026-11-15T09:00:00Z', applyError: extra.applyError ?? null,
  };
};

const set = (p: Readonly<Record<string, unknown>>) => {
  const body = (p['body'] ?? {}) as { preset?: string | null; features?: string[] | null };
  const on = body.features ?? PRESETS.find((x) => x.code === body.preset)?.features ?? [];
  return viewOf(on);
};

const LONG = 'Katima Mulilo Cross-Border Agricultural Trading & Logistics Cooperative (Proprietary) Limited';

export default defineFixtures({
  default: { 'org.features.get': viewOf(['onboarding', 'help']), 'org.features.set': set },
  empty: { 'org.features.get': viewOf(ALL), 'org.features.set': set },
  error: { 'org.features.get': fail(500, { title: 'Server error', detail: 'Features unavailable' }), 'org.features.set': set },
  longText: { 'org.features.get': viewOf(['home'], { orgName: LONG, partial: ['home'] }), 'org.features.set': set },
  forbidden: { 'org.features.get': fail(403, { title: 'Forbidden', detail: 'org.rights:read' }), 'org.features.set': set },
  notApplied: { 'org.features.get': viewOf(ALL, { applyError: 'you can only give permissions you hold yourself: [org.user:create]' }),
    'org.features.set': set },
  refused: { 'org.features.get': viewOf(ALL),
    'org.features.set': fail(403, { code: 'ANCESTOR_REQUIRED', title: 'Forbidden', detail: 'Only an admin of an org above it, or platform staff, sets what an org can do' }) },
  // `empty` here is an org with nothing to warn about: every feature on, the widget is ready
}, { expect: { forbidden: 'forbidden', empty: 'ready', notApplied: 'ready', refused: 'ready' } });
