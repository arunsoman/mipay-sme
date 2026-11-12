import { Routes } from '@angular/router';
import { portalGuard } from './core/guards';
import { permGuard } from '../../../shared/web-core/perm';
import { EmptyAccessShell } from '../../../shared/web-core/empty-shell';
import { Login } from './features/login/login';
import { Activate } from './features/activate/activate';
import { Shell } from './features/shell/shell';
import { Onboarding } from './features/onboarding/onboarding';
import { Network } from './features/network/network';
import { UserManagement } from '../../../shared/users/user-management';
import { Kyc } from './features/kyc/kyc';
// The shared commerce screens (sme-ui feature set). Visible in whichever org
// portal holds the matching supplier.* rows - a manufacturer or distributor
// granted "manage catalogue" or "manage orders" gets the full commerce UI here.
import {
  SupplierHome, SupplierCatalogue, SupplierOrders, SupplierInvoiceDetail,
  SupplierAccounts, SupplierStatements,
} from '../../../../src/app/screens';

/**
 * Org portal (manufacturer / distributor / reseller / supplier): permission-driven
 * from /api/orgs/me/access. Pages and the permissions that unlock them:
 *   onboarding      org.onboarding
 *   network         org.tree
 *   users           org.user:administer
 *   kyc             kyc.application | kyc.task
 *   supplier/*      supplier.* rows (commerce, shared with sme-ui)
 * A user with no rights at all lands on the shared empty shell (/no-access).
 */
const PERMS: Record<string, string[]> = {
  '/onboarding': ['org.onboarding'],
  '/network': ['org.tree'],
  '/users': ['org.user:administer'],
  '/kyc': ['kyc.application', 'kyc.task'],
  // commerce routes unlock at ROW level (any held verb); the exact cells gate
  // the individual buttons inside (e.g. supplier.catalogue:create -> "Add item").
  '/supplier': ['supplier.account'],
  '/supplier/catalogue': ['supplier.catalogue'],
  '/supplier/orders': ['supplier.order', 'supplier.invoice'],
  '/supplier/invoices/:id': ['supplier.invoice'],
  '/supplier/accounts': ['supplier.bank_account'],
  '/supplier/statements': ['supplier.statement'],
};
const pg = (path: string) => permGuard(PERMS[path] ?? []);

export const routes: Routes = [
  { path: 'login', component: Login },
  { path: 'activate', component: Activate },
  {
    path: '',
    component: Shell,
    canActivate: [portalGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'network' },
      { path: 'no-access', component: EmptyAccessShell },
      { path: 'onboarding', component: Onboarding, canActivate: [pg('/onboarding')] },
      { path: 'network', component: Network, canActivate: [pg('/network')] },
      { path: 'users', component: UserManagement, canActivate: [pg('/users')] },
      { path: 'kyc', component: Kyc, canActivate: [pg('/kyc')] },
      // commerce (supplier.* rows; shared with sme-ui)
      { path: 'supplier', component: SupplierHome, canActivate: [pg('/supplier')] },
      { path: 'supplier/catalogue', component: SupplierCatalogue, canActivate: [pg('/supplier/catalogue')] },
      { path: 'supplier/orders', component: SupplierOrders, canActivate: [pg('/supplier/orders')] },
      { path: 'supplier/invoices/:id', component: SupplierInvoiceDetail, canActivate: [pg('/supplier/invoices/:id')] },
      { path: 'supplier/accounts', component: SupplierAccounts, canActivate: [pg('/supplier/accounts')] },
      { path: 'supplier/statements', component: SupplierStatements, canActivate: [pg('/supplier/statements')] },
    ],
  },
  { path: '**', redirectTo: 'login' },
];
