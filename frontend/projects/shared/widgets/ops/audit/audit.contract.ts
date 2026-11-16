/**
 * Contract of ops.audit (OpsController#audit).
 */
import { s, type Infer } from '../../core/shape';

/** GET /api/ops/audit?simulated&page&size → Dtos.AuditPage (ops.audit:read): newest first. */
export const OPS_AUDIT_PAGE = s.object('AuditPage', {
  rows: s.nullable(s.array(s.object('AuditRow', {
    id: s.nullable(s.string()),
    actorRole: s.nullable(s.string()),
    action: s.nullable(s.string()),
    entityType: s.nullable(s.string()),
    entityId: s.nullable(s.string()),
    meta: s.nullable(s.string()),
    simulated: s.nullable(s.boolean()),
    createdAt: s.nullable(s.dateTime()),
  }))),
  totalElements: s.integer(),
  page: s.integer(),
});
export type OpsAuditPage = Infer<typeof OPS_AUDIT_PAGE>;
export type AuditRow = NonNullable<OpsAuditPage['rows']>[number];
