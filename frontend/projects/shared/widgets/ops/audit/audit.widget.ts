/**
 * Audit trail (ops.audit): who did what, to which record, and when - newest first, a page at a
 * time. MiPay only. GET /api/ops/audit?page&size.
 */
import { defineWidget, span } from '../../core/manifest';
import { OPS_AUDIT_PAGE } from './audit.contract';

export interface OpsAuditConfig {
  /** Entries per page (asked of the server). */
  pageSize: number;
}

export default defineWidget<OpsAuditConfig>({
  id: 'ops.audit',
  version: '1.0.0',
  label: 'Audit trail',
  description: 'Who did what, to which record, and when; newest first.',
  category: 'Access',
  contexts: ['PLATFORM'],
  requires: ['ops.audit:read'],
  actions: [],
  sources: ['ops.audit.page'],
  commands: [],
  operations: ['view'],
  commandOperations: {},
  contracts: { 'ops.audit.page': OPS_AUDIT_PAGE },
  requests: {},
  config: {
    pageSize: { type: 'number', default: 25, min: 10, max: 100, label: 'Entries per page' },
  },
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(8, 12), lg: span.lg(6, 12) },
  hideOn: ['forbidden'],
  load: () => import('./audit.component').then((m) => m.OpsAuditWidget),
});
