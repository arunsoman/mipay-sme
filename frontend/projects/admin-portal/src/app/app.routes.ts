import { LayoutPage, layoutScreenGuard } from '../../../shared/web-core/portal-layout';
import { Routes } from '@angular/router';
import { adminGuard } from './core/guards';
import { permGuard } from '../../../shared/web-core/perm';
import { EmptyAccessShell } from '../../../shared/web-core/empty-shell';
import { Login } from './features/login/login';
import { Activate } from './features/activate/activate';
import { Install } from './features/install/install';
import { Shell } from './features/shell/shell';
import { Overview } from './features/overview/overview';
import { ApiClients } from './features/api-clients/api-clients';
import { OrgDirectory } from './features/organizations/directory';
import { Onboarding } from './features/onboarding/onboarding';
import { UserManagement } from '../../../shared/users/user-management';
import { menuPerms } from '../../../shared/web-core/menu';
import { Permissions } from './features/permissions/permissions';
import { Kyc } from './features/kyc/kyc';
import { PermissionMatrix } from './features/permission-matrix/permission-matrix';
import { PermInventory } from '../../../shared/web-core/permission-inventory';
import { WidgetPage } from '../../../shared/web-core/widget-page';
// operations and compliance screens (shared with sme-ui), each behind its permission rows
import { OpsApprovals, OpsRecon, OpsIncidents, OpsDesk, OpsReports, OpsAudit, AdminCompliance, AdminConnectors, AdminConfig, AdminAccess } from '../../../../src/app/screens';

/**
 * Admin portal surface - the platform superset. Every page is gated twice:
 * adminGuard (token + /api/me, keeps non-platform accounts out) and
 * permGuard (held RBAC cells from /api/orgs/me/access). Users with no
 * granted rows land on the shared empty shell (/no-access).
 *
 * The route → permission rows come from THE menu registry (shared/web-core/menu.ts):
 * the sidebar and these guards read one truth - one label and one permission set
 * per menu point, identical to every other portal. An unmapped path denies (fail-closed).
 */
const pg = (path: string) =>
  permGuard(menuPerms('admin').find(([link]) => link === path)?.[1] ?? ['__no_menu_point__'], { portal: 'PLATFORM', link: path });

export const routes: Routes = [
  { path: 'login', component: Login },
  { path: 'activate', component: Activate },
  {
    path: '',
    component: Shell,
    canActivate: [adminGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'organizations' },
      // ---- the admin console (docs/plans/admin-console.md): pages made of registered widgets
      { path: 'organizations', component: WidgetPage, canActivate: [pg('/organizations')], data: {
        portal: 'PLATFORM', title: 'Organizations',
        subtitle: 'Every organization, what needs attention, and what each one can do. Pick one to open it.',
        main: { widget: 'admin.organizations' },
        detail: [
          { label: 'What it can do', widget: 'org.access' },
          { label: 'People', widget: 'org.people' },
          { label: 'KYC', widget: 'kyc.application', config: { mode: 'review' } },
        ],
      } },
      { path: 'kyc/applications', component: WidgetPage, canActivate: [pg('/kyc/applications')], data: {
        portal: 'PLATFORM', title: 'KYC applications', subtitle: 'Every KYC application. Pick one to open it.',
        main: { widget: 'kyc.orgs' },
        detail: [{ label: 'Application', widget: 'kyc.application' }],
      } },
      { path: 'kyc/reviews', component: WidgetPage, canActivate: [pg('/kyc/reviews')], data: {
        portal: 'PLATFORM', title: 'Review queue', subtitle: 'KYC reviews waiting for you. Pick one to read it and decide.',
        main: { widget: 'kyc.tasks' },
        detail: [{ label: 'Application', widget: 'kyc.application', config: { mode: 'review' } }],
      } },
      { path: 'access/users', component: WidgetPage, canActivate: [pg('/access/users')], data: {
        portal: 'PLATFORM', title: 'Platform users', subtitle: 'The people of the platform organization.',
        main: { widget: 'users.organization-users' },
      } },
      { path: 'no-access', component: EmptyAccessShell },
      // pages built in the portal builder and published (portal-builder.md steps 5 + 7)
      { path: 'p/:menu/:screen', component: LayoutPage, canActivate: [layoutScreenGuard] },
      { path: 'install', component: Install },
      { path: 'overview', component: Overview, canActivate: [pg('/overview')] },
      { path: 'orgs', component: OrgDirectory, canActivate: [pg('/orgs')] },
      { path: 'onboarding', component: Onboarding, canActivate: [pg('/onboarding')] },
      { path: 'kyc', component: Kyc, canActivate: [pg('/kyc')] },
      { path: 'users', component: UserManagement, canActivate: [pg('/users')] },
      { path: 'permissions', component: Permissions, canActivate: [pg('/permissions')] },
      { path: 'rbac', component: PermissionMatrix, canActivate: [pg('/rbac')] },
      { path: 'inventory', component: PermInventory, canActivate: [pg('/inventory')] },
      { path: 'admin/connectors', component: AdminConnectors, canActivate: [pg('/admin/connectors')] },
      { path: 'admin/config', component: AdminConfig, canActivate: [pg('/admin/config')] },
      { path: 'admin/api-clients', component: ApiClients, canActivate: [pg('/admin/api-clients')] },
      { path: 'admin/access', component: AdminAccess, canActivate: [pg('/admin/access')] },
      { path: 'ops/approvals', component: OpsApprovals, canActivate: [pg('/ops/approvals')] },
      { path: 'ops/recon', component: OpsRecon, canActivate: [pg('/ops/recon')] },
      { path: 'ops/incidents', component: OpsIncidents, canActivate: [pg('/ops/incidents')] },
      { path: 'ops/desk', component: OpsDesk, canActivate: [pg('/ops/desk')] },
      { path: 'ops/reports', component: OpsReports, canActivate: [pg('/ops/reports')] },
      { path: 'ops/audit', component: OpsAudit, canActivate: [pg('/ops/audit')] },
      { path: 'compliance', component: AdminCompliance, canActivate: [pg('/compliance')] },
    ],
  },
  { path: '**', redirectTo: 'login' },
];
