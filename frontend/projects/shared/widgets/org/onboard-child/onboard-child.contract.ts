/**
 * Contracts of org.onboard-child (OrgController#grantable, #submitChildWithKyc). The KYC form and
 * its lists use kyc.application's contracts (KycController#schemaForType answers the same shape).
 */
import { s, type Infer } from '../../core/shape';

/** GET /api/orgs/me/grantable → Dtos.GrantableView (org.rights:read): the org types this org may onboard. */
export const ORG_GRANTABLE_GET = s.object('GrantableView', {
  effDirect: s.nullable(s.array(s.string())),
  children: s.nullable(s.array(s.object('GrantableChild', {
    type: s.nullable(s.string()),
    allowedCeiling: s.nullable(s.array(s.string())),
    defaultDirect: s.nullable(s.array(s.string())),
  }))),
  roles: s.nullable(s.array(s.object('GrantableRole', {
    name: s.nullable(s.string()),
    description: s.nullable(s.string()),
    baseline: s.boolean(),
  }))),
  baselineRoles: s.nullable(s.array(s.string())),
});
export type OrgGrantableGet = Infer<typeof ORG_GRANTABLE_GET>;

/**
 * POST /api/orgs/me/children/with-kyc → Map (org.onboarding:create): the org, its request, the
 * KYC status, and the login name the system gave the administrator.
 */
export const ORG_CHILDREN_ONBOARD = s.object('Onboarded', {
  orgId: s.string(),
  requestId: s.string(),
  status: s.string(),
  adminUsername: s.string(),
});
export type OrgChildrenOnboard = Infer<typeof ORG_CHILDREN_ONBOARD>;

/** OrgController.OnboardWithKycRequest: the type and the whole five-section KYC document. */
export const ORG_CHILDREN_ONBOARD_REQUEST = s.object('OnboardWithKycRequest', {
  type: s.string(),
  kyc: s.map(s.any()),
  licenceRef: s.nullable(s.string()),
  rightsDirect: s.nullable(s.array(s.string())),
  rightsCeiling: s.nullable(s.array(s.string())),
  roleCeiling: s.nullable(s.array(s.string())),
  roleDelegable: s.nullable(s.array(s.string())),
});
export type OrgChildrenOnboardRequest = Infer<typeof ORG_CHILDREN_ONBOARD_REQUEST>;
