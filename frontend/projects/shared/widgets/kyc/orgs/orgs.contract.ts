/**
 * Response contracts: what the server must send. The port checks every answer against these and
 * the harness shows any mismatch. Regenerate after a backend change (widgets:contract).
 */
import { s, type Infer } from '../../core/shape';

/** One KYC application row (Dtos.KycSummaryRow). */
export const KYC_SUMMARY_ROW = s.object('KycSummaryRow', {
  id: s.nullable(s.string()),
  orgId: s.nullable(s.string()),
  orgType: s.nullable(s.string()),
  orgName: s.nullable(s.string()),
  parentOrgId: s.nullable(s.string()),
  treePath: s.nullable(s.string()),
  depth: s.integer(),
  isSuspended: s.boolean(),
  status: s.nullable(s.string()),
  submittedAt: s.nullable(s.dateTime()),
  decidedAt: s.nullable(s.dateTime()),
  updatedAt: s.nullable(s.dateTime()),
});

/**
 * Hand-written: GET /api/kyc/orgs/search → ListQuery.Page<Dtos.KycSummaryRow>
 * (KycController#searchV2, kyc.application:read). The generator does not read generic pages.
 * Query: q, sort=orgName|orgType|status|isSuspended|submittedAt|decidedAt|updatedAt,asc|desc,
 * orgType, status (comma = any of), isSuspended, page, size.
 */
export const KYC_ORGS_SEARCH = s.object('Page', {
  items: s.array(KYC_SUMMARY_ROW),
  total: s.integer(),
  page: s.integer(),
  size: s.integer(),
});
export type KycOrgsSearch = Infer<typeof KYC_ORGS_SEARCH>;
export type KycOrgsRow = KycOrgsSearch['items'][number];
