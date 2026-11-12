import { Routes } from '@angular/router';
import { adminGuard } from './core/guards';
import { permGuard } from '../../../shared/web-core/perm';
import { EmptyAccessShell } from '../../../shared/web-core/empty-shell';
import { Login } from './features/login/login';
import { Activate } from './features/activate/activate';
import { Shell } from './features/shell/shell';
import { Onboarding } from './features/onboarding/onboarding';
import { UserManagement } from '../../../shared/users/user-management';
import { Permissions } from './features/permissions/permissions';
import { Kyc } from './features/kyc/kyc';
import { PermissionMatrix } from './features/permission-matrix/permission-matrix';
import { PermInventory } from '../../../shared/web-core/permission-inventory';

/**
 * Admin portal surface - the platform superset. Every page is gated twice:
 * adminGuard (token + /api/me, keeps non-platform accounts out) and
 * permGuard (held RBAC cells from /api/orgs/me/access). Users with no
 * granted rows land on the shared empty shell (/no-access).
 *
 *   onboarding    org.onboarding
 *   kyc           kyc.application | kyc.task
 *   users         org.user:administer
 *   permissions   org.rights
 *   rbac          rbac.role
 */
const PERMS: Record<string, string[]> = {
  '/onboarding': ['org.onboarding'],
  '/kyc': ['kyc.application', 'kyc.task'],
  '/users': ['org.user:administer'],
  '/permissions': ['org.rights'],
  '/rbac': ['rbac.role'],
  '/inventory': ['admin.access:read'],
};
const pg = (path: string) => permGuard(PERMS[path] ?? []);

export const routes: Routes = [
  { path: 'login', component: Login },
  { path: 'activate', component: Activate },
  {
    path: '',
    component: Shell,
    canActivate: [adminGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'onboarding' },
      { path: 'no-access', component: EmptyAccessShell },
      { path: 'onboarding', component: Onboarding, canActivate: [pg('/onboarding')] },
      { path: 'kyc', component: Kyc, canActivate: [pg('/kyc')] },
      { path: 'users', component: UserManagement, canActivate: [pg('/users')] },
      { path: 'permissions', component: Permissions, canActivate: [pg('/permissions')] },
      { path: 'rbac', component: PermissionMatrix, canActivate: [pg('/rbac')] },
      { path: 'inventory', component: PermInventory, canActivate: [pg('/inventory')] },
    ],
  },
  { path: '**', redirectTo: 'login' },
];
