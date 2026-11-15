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
});
