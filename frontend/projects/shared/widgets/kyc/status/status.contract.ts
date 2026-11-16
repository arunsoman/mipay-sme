/**
 * Contracts of kyc.status (KycController#mine, #upgrade).
 */
import { s, type Infer } from '../../core/shape';

/** Dtos.KycApplicationView: the org's KYC application with what the caller may do. */
export const KYC_APPLICATION_VIEW = s.object('KycApplicationView', {
  id: s.nullable(s.string()),
  orgId: s.nullable(s.string()),
  orgType: s.nullable(s.string()),
  orgName: s.nullable(s.string()),
  parentOrgId: s.nullable(s.string()),
  treePath: s.nullable(s.array(s.object('OrgPathRow', {
    id: s.nullable(s.string()),
    type: s.nullable(s.string()),
    name: s.nullable(s.string()),
    depth: s.integer(),
  }))),
  depth: s.integer(),
  isSuspended: s.boolean(),
  status: s.nullable(s.string()),
  application: s.nullable(s.any()),
  submittedApplication: s.nullable(s.any()),
  submittedBy: s.nullable(s.string()),
  submittedAt: s.nullable(s.dateTime()),
  decidedBy: s.nullable(s.string()),
  decidedAt: s.nullable(s.dateTime()),
  decisionNote: s.nullable(s.string()),
  version: s.integer(),
  masked: s.boolean(),
  canEdit: s.boolean(),
  canSubmit: s.boolean(),
  canDecide: s.boolean(),
  createdAt: s.nullable(s.dateTime()),
  updatedAt: s.nullable(s.dateTime()),
});

/** GET /api/orgs/me/kyc (kyc.application:read). */
export const KYC_MINE_GET = KYC_APPLICATION_VIEW;
export type KycMineGet = Infer<typeof KYC_MINE_GET>;

/** POST /api/kyc/orgs/{id}/upgrade (kyc.application:modify): a DRAFT moved to the current form version. */
export const KYC_MINE_UPGRADE = KYC_APPLICATION_VIEW;
export type KycMineUpgrade = Infer<typeof KYC_MINE_UPGRADE>;
/** The upgrade reads no body. */
export const KYC_MINE_UPGRADE_REQUEST = s.object('NoBody', {});
