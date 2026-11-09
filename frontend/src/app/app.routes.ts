import { Routes } from '@angular/router';
import { authGuard, roleGuard } from './core/guards';
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

      // ---------------- merchant (M01-M29)
      { path: 'merchant', component: MerchantDashboard, canActivate: [roleGuard('MERCHANT')] },
      { path: 'merchant/accept', component: MerchantAccept, canActivate: [roleGuard('MERCHANT')] },
      { path: 'merchant/payments', component: MerchantPayments, canActivate: [roleGuard('MERCHANT')] },
      { path: 'merchant/payments/:id', component: MerchantPaymentDetail, canActivate: [roleGuard('MERCHANT')] },
      { path: 'merchant/balance', component: MerchantBalance, canActivate: [roleGuard('MERCHANT')] },
      { path: 'merchant/orders', component: MerchantOrders, canActivate: [roleGuard('MERCHANT')] },
      { path: 'merchant/inventory', component: MerchantInventory, canActivate: [roleGuard('MERCHANT')] },
      { path: 'merchant/khata', component: MerchantKhata, canActivate: [roleGuard('MERCHANT')] },
      { path: 'merchant/tabs', component: MerchantTabs, canActivate: [roleGuard('MERCHANT')] },
      { path: 'merchant/advance', component: MerchantAdvance, canActivate: [roleGuard('MERCHANT')] },
      { path: 'merchant/statements', component: MerchantStatements, canActivate: [roleGuard('MERCHANT')] },
      { path: 'merchant/settings', component: MerchantSettings, canActivate: [roleGuard('MERCHANT')] },

      // ---------------- supplier (S01-S08)
      { path: 'supplier', component: SupplierHome, canActivate: [roleGuard('SUPPLIER')] },
      { path: 'supplier/catalogue', component: SupplierCatalogue, canActivate: [roleGuard('SUPPLIER')] },
      { path: 'supplier/orders', component: SupplierOrders, canActivate: [roleGuard('SUPPLIER')] },
      { path: 'supplier/invoices/:id', component: SupplierInvoiceDetail, canActivate: [roleGuard('SUPPLIER')] },
      { path: 'supplier/accounts', component: SupplierAccounts, canActivate: [roleGuard('SUPPLIER')] },
      { path: 'supplier/statements', component: SupplierStatements, canActivate: [roleGuard('SUPPLIER')] },

      // ---------------- lender (L01-L15)
      { path: 'lender', component: LenderHome, canActivate: [roleGuard('LENDER')] },
      { path: 'lender/portfolio', component: LenderPortfolio, canActivate: [roleGuard('LENDER')] },
      { path: 'lender/collections', component: LenderCollections, canActivate: [roleGuard('LENDER')] },

      // ---------------- ops (T01-T20)
      { path: 'ops/approvals', component: OpsApprovals, canActivate: [roleGuard('TPTS_OPS')] },
      { path: 'ops/recon', component: OpsRecon, canActivate: [roleGuard('TPTS_OPS')] },
      { path: 'ops/incidents', component: OpsIncidents, canActivate: [roleGuard('TPTS_OPS')] },
      { path: 'ops/desk', component: OpsDesk, canActivate: [roleGuard('TPTS_OPS')] },
      { path: 'ops/reports', component: OpsReports, canActivate: [roleGuard('TPTS_OPS')] },
      { path: 'ops/audit', component: OpsAudit, canActivate: [roleGuard('TPTS_OPS')] },

      // ---------------- admin (A01, C01-C45, E30-E45, X)
      { path: 'admin', component: AdminHome, canActivate: [roleGuard('MINDLABS_ADMIN')] },
      { path: 'admin/connectors', component: AdminConnectors, canActivate: [roleGuard('MINDLABS_ADMIN')] },
      { path: 'admin/config', component: AdminConfig, canActivate: [roleGuard('MINDLABS_ADMIN')] },
      { path: 'admin/simulator', component: AdminSimulator, canActivate: [roleGuard('MINDLABS_ADMIN')] },
      { path: 'admin/access', component: AdminAccess, canActivate: [roleGuard('MINDLABS_ADMIN')] },
      { path: 'admin/compliance', component: AdminCompliance, canActivate: [roleGuard('MINDLABS_ADMIN')] },
      { path: 'admin/hygiene', component: AdminHygiene, canActivate: [roleGuard('MINDLABS_ADMIN')] },
    ],
  },
  { path: '**', redirectTo: 'login' },
];
