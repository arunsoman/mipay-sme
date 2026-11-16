/**
 * KYC checks (ops.kyc-checks): the identity, sanctions and adverse-media checks recorded on the
 * picked organization, and recording a new one. MiPay operations only.
 * GET /api/orgs/{id} (its type and entity) · GET/POST /api/ops/kyc/{entityType}/{entityId}.
 */
import { defineWidget, span } from '../../core/manifest';
import {
  OPS_KYC_CHECKS_LIST, OPS_KYC_CHECKS_RECORD, OPS_KYC_CHECKS_RECORD_REQUEST, OPS_KYC_ORG_GET,
} from './kyc-checks.contract';

export type OpsKycChecksConfig = Record<string, never>;

export default defineWidget<OpsKycChecksConfig>({
  id: 'ops.kyc-checks',
  version: '1.0.0',
  label: 'KYC checks',
  description: 'Identity, sanctions and adverse-media checks on the picked organization, and recording a new one.',
  category: 'Onboarding',
  contexts: ['PLATFORM'],
  requires: { all: ['ops.kyc_check:read', 'org.tree:read'] },
  actions: [
    { id: 'record', label: 'Record a check', cell: 'ops.kyc_check:create', command: 'ops.kyc-checks.record' },
  ],
  sources: ['ops.kyc.org.get', 'ops.kyc-checks.list'],
  commands: ['ops.kyc-checks.record'],
  operations: ['view', 'create'],
  commandOperations: { 'ops.kyc-checks.record': 'create' },
  contracts: {
    'ops.kyc.org.get': OPS_KYC_ORG_GET,
    'ops.kyc-checks.list': OPS_KYC_CHECKS_LIST,
    'ops.kyc-checks.record': OPS_KYC_CHECKS_RECORD,
  },
  requests: { 'ops.kyc-checks.record': OPS_KYC_CHECKS_RECORD_REQUEST },
  inputs: { orgId: { from: ['selection.orgId', 'context.orgId'], label: 'Organization' } },
  config: {},
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(6, 12), lg: span.lg(4, 6, 12) },
  hideOn: ['forbidden'],
  load: () => import('./kyc-checks.component').then((m) => m.OpsKycChecksWidget),
});
