/**
 * KYC application editor - the first MiPay widget (docs/plans/portal-builder.md, Appendix A).
 *
 * One organization's KYC application: status, the schema-driven form, Save / Submit / Withdraw.
 * Which org: the page's selection (an admin directory) or else the user's own org (org portals).
 * Who may edit is still decided by the server (view.canEdit); masked for higher ancestors.
 */
import { defineWidget, span } from '../../core/manifest';
import {
  KYC_APPLICATION_GET, KYC_APPLICATION_SAVE_REQUEST, KYC_APPLICATION_SUBMIT_REQUEST, KYC_APPLICATION_WITHDRAW_REQUEST,
  KYC_LIST_GET, KYC_SCHEMA_GET,
} from './kyc-application.contract';

export interface KycApplicationConfig {
  /** edit: the full editor · review: read-only (approvers) · wizard: onboarding footer. */
  mode: 'edit' | 'review' | 'wizard';
  /** Step the form opens on (0-based). */
  startStep: number;
}

export default defineWidget<KycApplicationConfig>({
  id: 'kyc.application',
  version: '1.0.0',
  label: 'KYC application',
  description: "An organization's KYC application: status, the schema-driven form, save, submit and withdraw.",
  category: 'Onboarding',
  contexts: ['PLATFORM', 'MANUFACTURER', 'DISTRIBUTOR', 'SUPPLIER'],
  // the form is useless without its schema: both rows, not either
  requires: { all: ['kyc.application:read', 'kyc.form:read'] },
  actions: [
    { id: 'save', label: 'Save draft', cell: 'kyc.application:modify', command: 'kyc.application.save' },
    { id: 'submit', label: 'Submit for review', cell: 'kyc.application:submit', command: 'kyc.application.submit' },
    { id: 'withdraw', label: 'Withdraw', cell: 'kyc.application:delete', command: 'kyc.application.withdraw' },
  ],
  sources: ['kyc.application.get', 'kyc.schema.get', 'kyc.list.get'],
  commands: ['kyc.application.save', 'kyc.application.submit', 'kyc.application.withdraw'],
  // what the server must send: every answer is checked, mismatches show in the harness
  contracts: {
    'kyc.application.get': KYC_APPLICATION_GET,
    'kyc.schema.get': KYC_SCHEMA_GET,
    'kyc.list.get': KYC_LIST_GET,
    // save / submit / withdraw answer the updated application (KycController#save, #submit, #withdraw)
    'kyc.application.save': KYC_APPLICATION_GET,
    'kyc.application.submit': KYC_APPLICATION_GET,
    'kyc.application.withdraw': KYC_APPLICATION_GET,
  },
  // what the editor sends: every outgoing body is checked too
  requests: {
    'kyc.application.save': KYC_APPLICATION_SAVE_REQUEST,
    'kyc.application.submit': KYC_APPLICATION_SUBMIT_REQUEST,
    'kyc.application.withdraw': KYC_APPLICATION_WITHDRAW_REQUEST,
  },
  inputs: {
    orgId: { from: ['selection.orgId', 'context.orgId'], label: 'Organization' },
  },
  config: {
    mode: { type: 'enum', options: ['edit', 'review', 'wizard'], default: 'edit', label: 'Mode' },
    startStep: { type: 'number', default: 0, min: 0, max: 10, label: 'Opening step' },
  },
  size: {
    xs: span.xs(4),          // a form: full width on phones
    sm: span.sm(8),
    md: span.md(8, 12),      // two-column fields need at least 8 of 12
    lg: span.lg(8, 12),
  },
  hideOn: ['forbidden'],
  load: () => import('./kyc-application.component').then((m) => m.KycApplicationWidget),
});
