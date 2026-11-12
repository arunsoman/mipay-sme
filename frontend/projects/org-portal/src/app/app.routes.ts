import { Routes } from '@angular/router';
import { portalGuard } from './core/guards';
import { Login } from './features/login/login';
import { Activate } from './features/activate/activate';
import { Shell } from './features/shell/shell';
import { Onboarding } from './features/onboarding/onboarding';
import { Network } from './features/network/network';
import { UserManagement } from '../../../shared/users/user-management';
import { Kyc } from './features/kyc/kyc';

/**
 * Org portal (manufacturer / distributor / supplier): an org ADMIN onboards
 * the org types its rights allow, reviews requests raised in its org, and
 * manages its own subtree (tree, suspension, rights narrowing) and staff.
 */
export const routes: Routes = [
  { path: 'login', component: Login },
  { path: 'activate', component: Activate },
  {
    path: '',
    component: Shell,
    canActivate: [portalGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'onboarding' },
      { path: 'onboarding', component: Onboarding },
      { path: 'network', component: Network },
      { path: 'users', component: UserManagement },
      { path: 'kyc', component: Kyc },
    ],
  },
  { path: '**', redirectTo: 'login' },
];
