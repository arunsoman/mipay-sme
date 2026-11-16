/**
 * Contracts of org.onboard-child (OrgController#grantable, #submitChild).
 */
import { s, type Infer } from '../../core/shape';
import { ORG_DECISION_RESULT } from '../onboarding-queue/onboarding-queue.contract';

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

/** POST /api/orgs/me/children → Dtos.OrgDecisionResult (org.onboarding:create). */
export const ORG_CHILDREN_SUBMIT = ORG_DECISION_RESULT;
export type OrgChildrenSubmit = Infer<typeof ORG_CHILDREN_SUBMIT>;

/** The new organization (Dtos.SubmitOrgRequest): type and phone are required. */
export const ORG_CHILDREN_SUBMIT_REQUEST = s.object('SubmitOrgRequest', {
  type: s.string(),
  name: s.nullable(s.string()),
  businessName: s.nullable(s.string()),
  businessType: s.nullable(s.string()),
  registrationNumber: s.nullable(s.string()),
  ownerName: s.nullable(s.string()),
  phone: s.string(),
  username: s.nullable(s.string()),
  email: s.nullable(s.string()),
  bankName: s.nullable(s.string()),
  accountNumber: s.nullable(s.string()),
  accountHolder: s.nullable(s.string()),
  licenceRef: s.nullable(s.string()),
  supplierType: s.nullable(s.string()),
  ownerIdNumber: s.nullable(s.string()),
  rightsDirect: s.nullable(s.array(s.string())),
  rightsCeiling: s.nullable(s.array(s.string())),
  roleCeiling: s.nullable(s.array(s.string())),
  roleDelegable: s.nullable(s.array(s.string())),
});
export type OrgChildrenSubmitRequest = Infer<typeof ORG_CHILDREN_SUBMIT_REQUEST>;
