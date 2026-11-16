/**
 * My KYC status (kyc.status): where the signed-in org's KYC application stands, what happens next,
 * and - for a draft on an older form - moving it to the current form.
 * GET /api/orgs/me/kyc · POST /api/kyc/orgs/{id}/upgrade.
 */
import { defineWidget, span } from '../../core/manifest';
import { KYC_MINE_GET, KYC_MINE_UPGRADE, KYC_MINE_UPGRADE_REQUEST } from './status.contract';

export type KycStatusConfig = Record<string, never>;

export default defineWidget<KycStatusConfig>({
  id: 'kyc.status',
  version: '1.0.0',
  label: 'My KYC status',
  description: 'Where your organization’s KYC application stands and what happens next.',
  category: 'Onboarding',
  contexts: ['MANUFACTURER', 'DISTRIBUTOR', 'SUPPLIER'],
  requires: ['kyc.application:read'],
  actions: [
    { id: 'upgrade', label: 'Move to the current form', cell: 'kyc.application:modify', command: 'kyc.mine.upgrade' },
  ],
  sources: ['kyc.mine.get'],
  commands: ['kyc.mine.upgrade'],
  operations: ['view', 'modify'],
  commandOperations: { 'kyc.mine.upgrade': 'modify' },
  contracts: { 'kyc.mine.get': KYC_MINE_GET, 'kyc.mine.upgrade': KYC_MINE_UPGRADE },
  requests: { 'kyc.mine.upgrade': KYC_MINE_UPGRADE_REQUEST },
  publishes: ['orgId', 'orgName'],
  config: {},
  size: { xs: span.xs(4), sm: span.sm(4, 8), md: span.md(4, 6, 12), lg: span.lg(3, 4, 12) },
  hideOn: ['forbidden'],
  load: () => import('./status.component').then((m) => m.KycStatusWidget),
});
