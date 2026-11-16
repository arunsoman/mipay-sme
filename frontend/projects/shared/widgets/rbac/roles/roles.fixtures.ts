/**
 * Fixture scenarios for rbac.roles: the list answers as the platform roles (no org id, PLATFORM)
 * or as the org's view (with an org id, org portals), as the server does.
 */
import { defineFixtures, fail } from '../../core/fixtures';
import type { OrgRolesView, PlatformRole } from './roles.contract';

export const PLATFORM_ROLES: PlatformRole[] = [
  { name: 'kyc_reviewer', description: 'Reviews KYC applications', permissions: ['kyc.application:read', 'kyc.task:read', 'kyc.task:authorize'],
    kind: 'PLATFORM', approval: 'platform', seeded: true, locked: false, inherits: [], own: ['kyc.task:authorize'], allRows: ['kyc.task', 'kyc.application'] },
  { name: 'org_admin', description: 'Administers an organization', permissions: ['org.user:read', 'org.user:create', 'kyc.application:read'],
    kind: 'ORG', approval: 'org_admin', seeded: true, locked: true, inherits: [], own: ['org.user:create'], allRows: ['org.user'] },
  { name: 'support_desk', description: 'Answers organizations’ questions', permissions: ['org.user:read', 'org.tree:read'],
    kind: 'PLATFORM', approval: 'none', seeded: false, locked: false, inherits: [], own: ['org.tree:read'], allRows: ['org.tree'] },
];

export const ORG_VIEW: OrgRolesView = {
  roles: [
    { name: 'org_admin', description: 'Administers Kalahari Supplies', kind: 'ORG', base_roles: [],
      permissions: ['org.user:read', 'org.user:create', 'org.user:administer', 'kyc.application:read', 'kyc.application:modify'] },
    { name: 'org_viewer', description: 'Sees the organization, changes nothing', kind: 'ORG', base_roles: [],
      permissions: ['org.user:read', 'kyc.application:read'] },
  ],
  org_roles: [
    { name: 'stock_clerk@org-sup-1', description: 'Counts stock at the Rundu depot', kind: 'ORG_DEFINED', base_roles: ['org_viewer'],
      permissions: ['org.user:read'] },
  ],
  rbac_mode: 'shadow',
};

const list = (p: Readonly<Record<string, unknown>>) => (p['id'] ? ORG_VIEW : PLATFORM_ROLES);
const updated = (p: Readonly<Record<string, unknown>>) => {
  const b = (p['body'] ?? {}) as { description?: string | null; baseRoles?: string[]; permissions?: string[] };
  if (p['id']) return { name: String(p['name']), description: b.description ?? null, kind: 'ORG_DEFINED', base_roles: b.baseRoles ?? [], permissions: b.permissions ?? [] };
  const r = PLATFORM_ROLES.find((x) => x.name === p['name']) ?? PLATFORM_ROLES[0];
  return { ...r, permissions: b.permissions ?? [] };
};

const LONG = 'Counts, receives and returns stock at the Oshakati Regional Wholesale & Cold-Chain Distribution Cooperative depot';

export default defineFixtures({
  default: { 'rbac.roles.list': list, 'rbac.roles.update': updated, 'rbac.roles.delete': { deleted: true } },
  empty: { 'rbac.roles.list': (p) => (p['id'] ? { ...ORG_VIEW, roles: [], org_roles: [] } : []) },
  error: { 'rbac.roles.list': fail(500, { title: 'Server error', detail: 'RBAC unavailable', code: 'RBAC_UNAVAILABLE' }) },
  longText: { 'rbac.roles.list': (p) => (p['id']
    ? { ...ORG_VIEW, org_roles: ORG_VIEW.org_roles.map((r) => ({ ...r, description: LONG })) }
    : PLATFORM_ROLES.map((r) => ({ ...r, description: LONG }))), 'rbac.roles.update': updated },
  refused: { 'rbac.roles.list': list,
    'rbac.roles.update': fail(422, { title: 'Invalid', detail: 'Wider than its base roles', code: 'EXCEEDS_BASE_ROLES' }),
    'rbac.roles.delete': fail(409, { title: 'Conflict', detail: 'The role is still held', code: 'ROLE_IN_USE' }) },
  forbidden: { 'rbac.roles.list': fail(403, { title: 'Forbidden', detail: 'rbac.role:read' }) },
}, { expect: { forbidden: 'forbidden' } });
