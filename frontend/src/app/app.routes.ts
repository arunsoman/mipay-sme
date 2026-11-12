import { Routes } from '@angular/router';
import { authGuard, roleGuard } from './core/guards';
import { permGuard } from '../../projects/shared/web-core/perm';
import { EmptyAccessShell } from '../../projects/shared/web-core/empty-shell';
import { PermInventory } from '../../projects/shared/web-core/permission-inventory';
import { ROUTE_PERMS } from './access-map';
import { Login } from './features/auth/login';
import { Shell } from './features/shell/shell';
import {
  MerchantDashboard, MerchantAccept, MerchantPayments, MerchantPaymentDetail,
  MerchantBalance, MerchantOrders, MerchantInventory, MerchantKhata, MerchantTabs,
  MerchantAdvance, MerchantStatements, MerchantSettings,
  SupplierHome, SupplierCatalogue, SupplierOrders, SupplierInvoiceDetail,
  SupplierAccounts, SupplierStatements,
  LenderHome, LenderPortfolio, LenderCollections,
  OpsApprovals, OpsRecon, OpsIncidents, OpsDesk, OpsReports, OpsAudit,
  AdminHome, AdminConnectors, AdminConfig, AdminSimulator, AdminAccess, AdminCompliance, AdminHygiene,
  CustomerSim, MerchantOnboard, SupplierOnboard,
} from './screens';

/** route keyed to its permission rows (ROUTE_PERMS) so nav + guard speak one truth. */
const pg = (path: string) => permGuard(ROUTE_PERMS[path] ?? []);

export const routes: Routes = [
  { path: 'login', component: Login },
  // stand-alone customer simulator (basic-phone payer, no login required)
  { path: 'customer', component: CustomerSim },
  // public self-onboarding (UAT SB p.8 M02/S02)
  { path: 'onboarding/merchant', component: MerchantOnboard },
  { path: 'onboarding/supplier', component: SupplierOnboard },
  {
    path: '',
    component: Shell,
    canActivate: [authGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'merchant' },
      // the empty shell: signed in, but the org has granted no rights yet
      { path: 'no-access', component: EmptyAccessShell },

      // ---------------- merchant (M01-M29)
      { path: 'merchant', component: MerchantDashboard, canActivate: [roleGuard('MERCHANT'), pg('/merchant')] },
      { path: 'merchant/accept', component: MerchantAccept, canActivate: [roleGuard('MERCHANT'), pg('/merchant/accept')] },
      { path: 'merchant/payments', component: MerchantPayments, canActivate: [roleGuard('MERCHANT'), pg('/merchant/payments')] },
      { path: 'merchant/payments/:id', component: MerchantPaymentDetail, canActivate: [roleGuard('MERCHANT'), pg('/merchant/payments/:id')] },
      { path: 'merchant/balance', component: MerchantBalance, canActivate: [roleGuard('MERCHANT'), pg('/merchant/balance')] },
      { path: 'merchant/orders', component: MerchantOrders, canActivate: [roleGuard('MERCHANT'), pg('/merchant/orders')] },
      { path: 'merchant/inventory', component: MerchantInventory, canActivate: [roleGuard('MERCHANT'), pg('/merchant/inventory')] },
      { path: 'merchant/khata', component: MerchantKhata, canActivate: [roleGuard('MERCHANT'), pg('/merchant/khata')] },
      { path: 'merchant/tabs', component: MerchantTabs, canActivate: [roleGuard('MERCHANT'), pg('/merchant/tabs')] },
      { path: 'merchant/advance', component: MerchantAdvance, canActivate: [roleGuard('MERCHANT'), pg('/merchant/advance')] },
      { path: 'merchant/statements', component: MerchantStatements, canActivate: [roleGuard('MERCHANT'), pg('/merchant/statements')] },
      { path: 'merchant/settings', component: MerchantSettings, canActivate: [roleGuard('MERCHANT'), pg('/merchant/settings')] },

      // ---------------- supplier (S01-S08)
      { path: 'supplier', component: SupplierHome, canActivate: [roleGuard('SUPPLIER'), pg('/supplier')] },
      { path: 'supplier/catalogue', component: SupplierCatalogue, canActivate: [roleGuard('SUPPLIER'), pg('/supplier/catalogue')] },
      { path: 'supplier/orders', component: SupplierOrders, canActivate: [roleGuard('SUPPLIER'), pg('/supplier/orders')] },
      { path: 'supplier/invoices/:id', component: SupplierInvoiceDetail, canActivate: [roleGuard('SUPPLIER'), pg('/supplier/invoices/:id')] },
      { path: 'supplier/accounts', component: SupplierAccounts, canActivate: [roleGuard('SUPPLIER'), pg('/supplier/accounts')] },
      { path: 'supplier/statements', component: SupplierStatements, canActivate: [roleGuard('SUPPLIER'), pg('/supplier/statements')] },

      // ---------------- lender (L01-L15)
      { path: 'lender', component: LenderHome, canActivate: [roleGuard('LENDER'), pg('/lender')] },
      { path: 'lender/portfolio', component: LenderPortfolio, canActivate: [roleGuard('LENDER'), pg('/lender/portfolio')] },
      { path: 'lender/collections', component: LenderCollections, canActivate: [roleGuard('LENDER'), pg('/lender/collections')] },

      // ---------------- ops (T01-T20)
      { path: 'ops/approvals', component: OpsApprovals, canActivate: [roleGuard('TPTS_OPS'), pg('/ops/approvals')] },
      { path: 'ops/recon', component: OpsRecon, canActivate: [roleGuard('TPTS_OPS'), pg('/ops/recon')] },
      { path: 'ops/incidents', component: OpsIncidents, canActivate: [roleGuard('TPTS_OPS'), pg('/ops/incidents')] },
      { path: 'ops/desk', component: OpsDesk, canActivate: [roleGuard('TPTS_OPS'), pg('/ops/desk')] },
      { path: 'ops/reports', component: OpsReports, canActivate: [roleGuard('TPTS_OPS'), pg('/ops/reports')] },
      { path: 'ops/audit', component: OpsAudit, canActivate: [roleGuard('TPTS_OPS'), pg('/ops/audit')] },

      // ---------------- admin (A01, C01-C45, E30-E45, X)
      { path: 'admin', component: AdminHome, canActivate: [roleGuard('MINDLABS_ADMIN'), pg('/admin')] },
      { path: 'admin/connectors', component: AdminConnectors, canActivate: [roleGuard('MINDLABS_ADMIN'), pg('/admin/connectors')] },
      { path: 'admin/config', component: AdminConfig, canActivate: [roleGuard('MINDLABS_ADMIN'), pg('/admin/config')] },
      { path: 'admin/simulator', component: AdminSimulator, canActivate: [roleGuard('MINDLABS_ADMIN'), pg('/admin/simulator')] },
      { path: 'admin/access', component: AdminAccess, canActivate: [roleGuard('MINDLABS_ADMIN'), pg('/admin/access')] },
      { path: 'admin/compliance', component: AdminCompliance, canActivate: [roleGuard('MINDLABS_ADMIN'), pg('/admin/compliance')] },
      { path: 'admin/hygiene', component: AdminHygiene, canActivate: [roleGuard('MINDLABS_ADMIN'), pg('/admin/hygiene')] },
      { path: 'inventory', component: PermInventory, canActivate: [roleGuard('MINDLABS_ADMIN'), pg('/inventory')] },
    ],
  },
  { path: '**', redirectTo: 'login' },
];
