import { Routes } from '@angular/router';
import { adminGuard } from './core/guards';
import { Login } from './features/login/login';
import { Shell } from './features/shell/shell';
import { Onboarding } from './features/onboarding/onboarding';
import { Users } from './features/users/users';
import { Permissions } from './features/permissions/permissions';
import { Kyc } from './features/kyc/kyc';
import { PermissionMatrix } from './features/permission-matrix/permission-matrix';

/**
 * Admin portal surface (deliberately minimal): Onboarding, KYC, User
 * management, Permissions - everything else stays in the operator consoles.
 */
export const routes: Routes = [
  { path: 'login', component: Login },
  {
    path: '',
    component: Shell,
    canActivate: [adminGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'onboarding' },
      { path: 'onboarding', component: Onboarding, canActivate: [adminGuard] },
      { path: 'kyc', component: Kyc, canActivate: [adminGuard] },
      { path: 'users', component: Users, canActivate: [adminGuard] },
      { path: 'permissions', component: Permissions, canActivate: [adminGuard] },
      { path: 'rbac', component: PermissionMatrix, canActivate: [adminGuard] },
    ],
  },
  { path: '**', redirectTo: 'login' },
];
