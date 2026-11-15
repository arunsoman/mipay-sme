/**
 * Fixture scenarios for rbac.new-role (shapes: RegisteredWidget, RbacRoleView, OrgRolesView,
 * RbacRoleCreate / OrgRoleBody - see new-role.contract.ts). The widget list is the registry's
 * answer for a portal; roles answer as the platform list (PLATFORM) or the org's view (org portals).
 */
import { defineFixtures, fail } from '../../core/fixtures';

type Op = 'view' | 'create' | 'modify' | 'approve';
const reg = (id: string, label: string, description: string, requires: string[],
             actions: [string, string, string, string, Exclude<Op, 'view'>][], extra: Partial<{ legacy: boolean }> = {}) => ({
  id, version: '1.0.0', label, description, category: id.split('.')[0].toUpperCase(),
  contexts: ['PLATFORM', 'SUPPLIER', 'MANUFACTURER', 'DISTRIBUTOR'], requires,
  actions: actions.map(([aid, alabel, cell, command]) => ({ id: aid, label: alabel, cell, command })),
  sources: [`${id}.list`], commands: actions.map((a) => a[3]),
  operations: extra.legacy ? null : (['view', ...new Set(actions.map((a) => a[4]))] as Op[]),
  commandOperations: extra.legacy ? null : Object.fromEntries(actions.map((a) => [a[3], a[4]])),
  bundle: 'admin-portal@7334080', registeredAt: '2026-11-15T09:00:00Z',
});

export const WIDGETS = [
  reg('kyc.tasks', 'KYC review queue', 'Open KYC review tasks, with an approve / reject decision.', ['kyc.task:read'],
    [['decide', 'Approve or reject', 'kyc.task:authorize', 'kyc.tasks.decide', 'approve']]),
  reg('kyc.application', 'KYC application', 'One organization’s KYC application: save, submit, withdraw.',
    ['kyc.application:read'],
    [['save', 'Save draft', 'kyc.application:modify', 'kyc.application.save', 'modify'],
     ['submit', 'Submit for review', 'kyc.application:submit', 'kyc.application.submit', 'modify'],
     ['withdraw', 'Withdraw', 'kyc.application:delete', 'kyc.application.withdraw', 'approve']]),
  reg('users.organization-users', 'Organization users', 'People of the organization: add, issue a setup link, deactivate.',
    ['org.user:read'],
    [['add', 'Add person', 'org.user:create', 'users.organization.create', 'create'],
     ['setup-link', 'Issue setup link', 'org.user:administer', 'users.organization.setup-link', 'approve'],
     ['deactivate', 'Deactivate person', 'org.user:delete', 'users.organization.deactivate', 'approve']]),
  reg('kyc.orgs', 'Organizations by KYC status', 'Every organization with its KYC status.', ['kyc.application'], [],
    { legacy: true }),
];

const PLATFORM_ROLES = [
  { name: 'kyc_reviewer', description: 'Reviews KYC applications', permissions: ['kyc.application:read', 'kyc.task:read', 'kyc.task:authorize'],
    kind: 'PLATFORM', approval: 'NONE', seeded: true, locked: false, inherits: [], own: ['kyc.task:authorize'], allRows: ['kyc.task', 'kyc.application'] },
  { name: 'org_admin', description: 'Administers an organization', permissions: ['org.user:read', 'org.user:create', 'kyc.application:read'],
    kind: 'ORG', approval: 'NONE', seeded: true, locked: false, inherits: [], own: ['org.user:create'], allRows: ['org.user'] },
];

const ORG_ROLES = {
  ceiling: { unrestricted: false, explicit: true, inherited_from: null, roles: ['org_admin', 'org_viewer'], delegable: [], baseline: [] },
  roles: [
    { name: 'org_admin', description: 'Administers Kalahari Supplies', kind: 'ORG', base_roles: [],
      permissions: ['org.user:read', 'org.user:create', 'org.user:administer', 'kyc.application:read', 'kyc.application:modify'] },
    { name: 'org_viewer', description: 'Sees the organization, changes nothing', kind: 'ORG', base_roles: [],
      permissions: ['org.user:read', 'kyc.application:read'] },
  ],
  org_roles: [],
  rbac_mode: 'shadow',
};

const created = (p: Readonly<Record<string, unknown>>) => {
  const b = (p['body'] ?? {}) as { name?: string; description?: string; kind?: string; inherits?: string[]; baseRoles?: string[]; permissions?: string[] };
  if (p['id']) return { name: b.name ?? 'new_role', description: b.description ?? '', kind: 'ORG', base_roles: b.baseRoles ?? [], permissions: b.permissions ?? [] };
  return { name: b.name ?? 'new_role', description: b.description ?? '', permissions: b.permissions ?? [], kind: b.kind ?? 'ORG',
    approval: 'NONE', seeded: false, locked: false, inherits: b.inherits ?? [], own: b.permissions ?? [], allRows: [] };
};
const roles = (p: Readonly<Record<string, unknown>>) => (p['id'] ? ORG_ROLES : PLATFORM_ROLES);

const LONG = 'Reviews and approves KYC applications of suppliers, manufacturers and distributors across every Namibian region, including enhanced due diligence';

export default defineFixtures({
  default: { 'rbac.new-role.widgets': WIDGETS, 'rbac.new-role.roles': roles, 'rbac.new-role.create': created },
  empty: { 'rbac.new-role.widgets': [], 'rbac.new-role.roles': roles },
  error: { 'rbac.new-role.widgets': fail(500, { title: 'Server error', detail: 'Widget registry unavailable' }), 'rbac.new-role.roles': roles },
  longText: {
    'rbac.new-role.widgets': WIDGETS.map((w) => ({ ...w, label: `${w.label} for regional compliance officers`, description: LONG })),
    'rbac.new-role.roles': roles, 'rbac.new-role.create': created,
  },
  forbidden: { 'rbac.new-role.widgets': fail(403, { title: 'Forbidden', detail: 'admin.access:read' }), 'rbac.new-role.roles': roles },
  sodConflict: {
    'rbac.new-role.widgets': WIDGETS, 'rbac.new-role.roles': roles,
    'rbac.new-role.create': fail(409, { title: 'Conflict', detail: 'kyc.application:submit and kyc.task:authorize may not be held together', code: 'SOD_CONFLICT' }),
  },
  nameRequired: {
    'rbac.new-role.widgets': WIDGETS, 'rbac.new-role.roles': roles,
    'rbac.new-role.create': fail(400, { title: 'Bad request', detail: 'A role name is required', code: 'ROLE_NAME_REQUIRED' }),
  },
}, { expect: { forbidden: 'forbidden' } });
