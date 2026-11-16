/**
 * Every logical source and command a widget may call, mapped to a request per portal
 * ('*' = all portals). Widgets name these; they never hold URLs. The registry tool resolves
 * each widget's routes from here, and the registry server checks every route against the
 * backend's @Perm endpoint manifest (decision 6).
 *
 * Add an entry here when `widgets:check` reports "unmapped".
 */
import { defineSources } from './core/sources';

export const SOURCES = defineSources({
  // ---------------- KYC (KycController) - same URL in every portal; context picks the org
  'kyc.application.get':      { '*': { method: 'GET',  path: '/api/kyc/orgs/{orgId}' } },
  'kyc.schema.get':           { '*': { method: 'GET',  path: '/api/kyc/schema/{orgId}' } },
  'kyc.list.get':             { '*': { method: 'GET',  path: '/api/kyc/lists/{code}', query: ['parent'] } },
  'kyc.application.save':     { '*': { method: 'PUT',  path: '/api/kyc/orgs/{orgId}', query: ['expectedVersion'] } },
  'kyc.application.submit':   { '*': { method: 'POST', path: '/api/kyc/orgs/{orgId}/submit' } },
  'kyc.application.withdraw': { '*': { method: 'POST', path: '/api/kyc/orgs/{orgId}/withdraw' } },
  // review queue (KycController#searchInbox, #decide): ListQuery (q, sort, filters, page, size)
  'kyc.tasks.search':         { '*': { method: 'GET',  path: '/api/kyc/tasks/search',
                                       query: ['q', 'sort', 'applicationStatus', 'kind', 'stageKey', 'page', 'size'] } },
  'kyc.tasks.decide':         { '*': { method: 'POST', path: '/api/kyc/tasks/{taskId}/decision' } },
  // <widget kyc.orgs>
  // KycController#searchV2: ListQuery (q, sort, filters, page, size)
  'kyc.orgs.search': { '*': { method: 'GET', path: '/api/kyc/orgs/search',
                              query: ['q', 'sort', 'orgType', 'status', 'isSuspended', 'page', 'size'] } },
  // </widget kyc.orgs>

  // ---------------- Admin console (AdminConsoleController): every list is a ListQuery ----------------
  // <widget admin.organizations>
  'admin.organizations.search': { PLATFORM: { method: 'GET', path: '/api/admin/organizations/search',
                                              query: ['q', 'sort', 'type', 'status', 'kycStatus', 'preset', 'warnings', 'page', 'size'] } },
  // </widget admin.organizations>
  // <widget org.access>
  'org.features.get': { '*': { method: 'GET', path: '/api/orgs/{orgId}/features' } },
  'org.features.set': { '*': { method: 'PUT', path: '/api/orgs/{orgId}/features' } },
  // </widget org.access>
  // <widget org.people>
  'org.people.search': { '*': { method: 'GET', path: '/api/orgs/{orgId}/people',
                                query: ['q', 'sort', 'orgRole', 'status', 'roles', 'page', 'size'] } },
  // </widget org.people>

  // ---------------- Organization users (OrgController / ActivationController) ----------------
  // <widget users.organization-users>
  'users.organization.list': { '*': { method: 'GET', path: '/api/orgs/me/users' } },
  'users.organization.create': { '*': { method: 'POST', path: '/api/orgs/me/users' } },
  'users.organization.setup-link': { '*': { method: 'POST', path: '/api/orgs/users/{id}/resend-activation' } },
  'users.organization.deactivate': { '*': { method: 'POST', path: '/api/orgs/users/{id}/deactivate' } },
  // </widget users.organization-users>

  // ---------------- New role by widget (UiWidgetController / PlatformRbacController / OrgAccessController)
  // PLATFORM builds standard roles; the org portals build org-defined roles for the org in context.
  // <widget rbac.new-role>
  'rbac.new-role.widgets': {
    PLATFORM: { method: 'GET', path: '/api/ui/widgets', query: ['portal'] },
    '*': { method: 'GET', path: '/api/orgs/{id}/ui/widgets', query: ['portal'] },
  },
  'rbac.new-role.roles': {
    PLATFORM: { method: 'GET', path: '/api/admin/rbac/roles' },
    '*': { method: 'GET', path: '/api/orgs/{id}/roles' },
  },
  'rbac.new-role.create': {
    PLATFORM: { method: 'POST', path: '/api/admin/rbac/roles' },
    '*': { method: 'POST', path: '/api/orgs/{id}/roles' },
  },
  // </widget rbac.new-role>

  // ---------------- Onboarding (OrgController) ----------------
  // <widget org.onboarding-queue>
  'org.onboarding.list': { '*': { method: 'GET', path: '/api/orgs/onboarding', query: ['status'] } },
  'org.onboarding.decide': { '*': { method: 'POST', path: '/api/orgs/onboarding/{id}/decide' } },
  // </widget org.onboarding-queue>
  // <widget org.onboard-child>
  'org.grantable.get': { '*': { method: 'GET', path: '/api/orgs/me/grantable' } },
  'org.children.submit': { '*': { method: 'POST', path: '/api/orgs/me/children' } },
  // </widget org.onboard-child>
  // <widget org.network>
  'org.tree.get': { '*': { method: 'GET', path: '/api/orgs/me/tree' } },
  'org.suspend': { '*': { method: 'POST', path: '/api/orgs/{id}/suspend' } },
  'org.suspension.lift': { '*': { method: 'POST', path: '/api/orgs/suspensions/{sid}/lift' } },
  // </widget org.network>

  // ---------------- KYC status and ops checks (KycController / OpsController) ----------------
  // <widget kyc.status>
  'kyc.mine.get': { '*': { method: 'GET', path: '/api/orgs/me/kyc' } },
  'kyc.mine.upgrade': { '*': { method: 'POST', path: '/api/kyc/orgs/{id}/upgrade' } },
  // </widget kyc.status>
  // <widget ops.kyc-checks>
  'ops.kyc.org.get': { PLATFORM: { method: 'GET', path: '/api/orgs/{id}' } },
  'ops.kyc-checks.list': { PLATFORM: { method: 'GET', path: '/api/ops/kyc/{entityType}/{entityId}' } },
  'ops.kyc-checks.record': { PLATFORM: { method: 'POST', path: '/api/ops/kyc/{entityType}/{entityId}' } },
  // </widget ops.kyc-checks>

  // ---------------- Access (PlatformRbacController / OrgAccessController / OrgController) ----------------
  // <widget rbac.roles>
  'rbac.roles.list': {
    PLATFORM: { method: 'GET', path: '/api/admin/rbac/roles' },
    '*': { method: 'GET', path: '/api/orgs/{id}/roles' },
  },
  'rbac.roles.update': {
    PLATFORM: { method: 'PUT', path: '/api/admin/rbac/roles/{name}' },
    '*': { method: 'PUT', path: '/api/orgs/{id}/roles/{name}' },
  },
  'rbac.roles.delete': {
    PLATFORM: { method: 'DELETE', path: '/api/admin/rbac/roles/{name}' },
    '*': { method: 'DELETE', path: '/api/orgs/{id}/roles/{name}' },
  },
  // </widget rbac.roles>
  // <widget rbac.assignments>
  'rbac.assignments.list': { '*': { method: 'GET', path: '/api/orgs/{id}/role-assignments' } },
  'rbac.assignments.members': { '*': { method: 'GET', path: '/api/orgs/{id}/members' } },
  'rbac.assignments.roles': { '*': { method: 'GET', path: '/api/orgs/{id}/roles' } },
  'rbac.assignments.assign': { '*': { method: 'POST', path: '/api/orgs/{id}/role-assignments' } },
  'rbac.assignments.revoke': { '*': { method: 'POST', path: '/api/orgs/{id}/role-assignments/{assignmentId}/revoke' } },
  // </widget rbac.assignments>
  // <widget rbac.role-approvals>
  'rbac.approvals.list': { '*': { method: 'GET', path: '/api/orgs/{id}/role-approvals' } },
  'rbac.approvals.members': { '*': { method: 'GET', path: '/api/orgs/{id}/members' } },
  'rbac.approvals.decide': { '*': { method: 'POST', path: '/api/orgs/{id}/role-approvals/{assignmentId}' } },
  // </widget rbac.role-approvals>
  // <widget org.role-ceiling>
  'org.ceiling.get': { '*': { method: 'GET', path: '/api/orgs/{id}/role-ceiling' } },
  'org.ceiling.set': { '*': { method: 'PUT', path: '/api/orgs/{id}/role-ceiling' } },
  // </widget org.role-ceiling>
  // <widget org.rights>
  'org.rights.get': { '*': { method: 'GET', path: '/api/orgs/{id}/rights' } },
  'org.rights.narrow': { '*': { method: 'PUT', path: '/api/orgs/{id}/rights' } },
  // </widget org.rights>
  // <widget rbac.my-access>
  'rbac.my-access.get': { '*': { method: 'GET', path: '/api/orgs/me/access' } },
  // </widget rbac.my-access>
  // <widget rbac.resources>
  'rbac.resources.list': { PLATFORM: { method: 'GET', path: '/api/admin/rbac/resources' } },
  // </widget rbac.resources>
  // <widget rbac.reconciliation>
  'rbac.recon.get': { PLATFORM: { method: 'GET', path: '/api/admin/rbac/reconciliation/{orgId}' } },
  'rbac.mode.get': { PLATFORM: { method: 'GET', path: '/api/admin/rbac/mode' } },
  // </widget rbac.reconciliation>
  // <widget rbac.platform-assignments>
  'rbac.platform-assignments.list': { PLATFORM: { method: 'GET', path: '/api/admin/rbac/assignments', query: ['status'] } },
  'rbac.platform-assignments.users': { PLATFORM: { method: 'GET', path: '/api/admin/rbac/users' } },
  'rbac.platform-assignments.roles': { PLATFORM: { method: 'GET', path: '/api/admin/rbac/roles' } },
  'rbac.platform-assignments.assign': { PLATFORM: { method: 'POST', path: '/api/admin/rbac/assignments' } },
  'rbac.platform-assignments.revoke': { PLATFORM: { method: 'DELETE', path: '/api/admin/rbac/assignments/{id}', query: ['reason'] } },
  // </widget rbac.platform-assignments>
  // <widget ops.audit>
  'ops.audit.page': { PLATFORM: { method: 'GET', path: '/api/ops/audit', query: ['simulated', 'page', 'size'] } },
  // </widget ops.audit>

  // ---------------- Users: sign-in, administrators, platform access ----------------
  // <widget users.sign-in>
  'users.sign-in.list': { '*': { method: 'GET', path: '/api/orgs/{id}/auth/users' } },
  'users.sign-in.resend-link': { '*': { method: 'POST', path: '/api/orgs/{id}/auth/users/{uid}/resend-link' } },
  'users.sign-in.reset': { '*': { method: 'POST', path: '/api/orgs/{id}/auth/users/{uid}/reset-credential' } },
  'users.sign-in.revoke-sessions': { '*': { method: 'POST', path: '/api/orgs/{id}/auth/users/{uid}/revoke-sessions' } },
  // </widget users.sign-in>
  // <widget org.admin-recovery>
  'org.admin-recovery.org': { '*': { method: 'GET', path: '/api/orgs/{id}' } },
  'org.admin-recovery.request': { '*': { method: 'POST', path: '/api/orgs/{id}/admins' } },
  // </widget org.admin-recovery>
  // <widget admin.platform-access>
  'admin.access.list': { PLATFORM: { method: 'GET', path: '/api/admin/access' } },
  'admin.access.detail': { PLATFORM: { method: 'GET', path: '/api/admin/access/{userId}' } },
  'admin.access.revoke': { PLATFORM: { method: 'POST', path: '/api/admin/access/{userId}/revoke' } },
  'admin.access.disable-link': { PLATFORM: { method: 'POST', path: '/api/admin/access/links/{linkId}/disable' } },
  // </widget admin.platform-access>
});
