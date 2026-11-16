/**
 * Contracts of ops.kyc-checks (OrgController#org, OpsController#kycChecks, #recordKycCheck).
 */
import { s, type Infer } from '../../core/shape';

/** GET /api/orgs/{id} → Dtos.OrgView (org.tree:read). */
export const ORG_VIEW = s.object('OrgView', {
  id: s.nullable(s.string()),
  type: s.nullable(s.string()),
  name: s.nullable(s.string()),
  status: s.nullable(s.string()),
  effectiveStatus: s.nullable(s.string()),
  parentOrgId: s.nullable(s.string()),
  entityId: s.nullable(s.string()),
  registrationNumber: s.nullable(s.string()),
  createdAt: s.nullable(s.dateTime()),
  kycStatus: s.nullable(s.string()),
  kycRiskRating: s.nullable(s.string()),
  kycNextReviewDate: s.nullable(s.dateTime()),
});
export const OPS_KYC_ORG_GET = ORG_VIEW;
export type OpsKycOrgGet = Infer<typeof OPS_KYC_ORG_GET>;

const KYC_CHECK_ROW = s.object('KycCheckRow', {
  id: s.nullable(s.string()),
  entityType: s.nullable(s.string()),
  entityId: s.nullable(s.string()),
  checkType: s.nullable(s.string()),
  result: s.nullable(s.string()),
  details: s.nullable(s.string()),
  checkedAt: s.nullable(s.dateTime()),
});

/** GET /api/ops/kyc/{entityType}/{entityId} → List<KycCheckRow> (ops.kyc_check:read). */
export const OPS_KYC_CHECKS_LIST = s.array(KYC_CHECK_ROW);
export type OpsKycChecksList = Infer<typeof OPS_KYC_CHECKS_LIST>;
export type KycCheck = OpsKycChecksList[number];

/** POST /api/ops/kyc/{entityType}/{entityId} → KycCheckRow (ops.kyc_check:create). */
export const OPS_KYC_CHECKS_RECORD = KYC_CHECK_ROW;
export type OpsKycChecksRecord = Infer<typeof OPS_KYC_CHECKS_RECORD>;

/** Dtos.KycCheckRequest: checkType IDENTITY | SANCTIONS | ADVERSE_MEDIA; result PASS | FAIL | MANUAL_REVIEW. */
export const OPS_KYC_CHECKS_RECORD_REQUEST = s.object('KycCheckRequest', {
  checkType: s.nullable(s.string()),
  result: s.nullable(s.string()),
  details: s.nullable(s.string()),
});
export type OpsKycChecksRecordRequest = Infer<typeof OPS_KYC_CHECKS_RECORD_REQUEST>;
