/**
 * Response contracts: what the server must send. The port checks every answer against these and
 * the harness shows any mismatch. Regenerate after a backend change (widgets:contract).
 */
import { s, type Infer } from '../../core/shape';

// <contract kyc.orgs.list>
/** GET /api/kyc/orgs → List<Dtos.KycSummaryRow> (KycController#listV2, kyc.application:read). */
export const KYC_ORGS_LIST = s.array(s.object('KycSummaryRow', {
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
}));
export type KycOrgsList = Infer<typeof KYC_ORGS_LIST>;
// </contract kyc.orgs.list>
