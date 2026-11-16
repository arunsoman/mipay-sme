/**
 * Contracts of org.onboarding-queue (OrgController#queue, #decide).
 */
import { s, type Infer } from '../../core/shape';

/** One onboarding request (Dtos.OnboardingRequestRow). */
export const ONBOARDING_REQUEST_ROW = s.object('OnboardingRequestRow', {
  id: s.nullable(s.string()),
  kind: s.nullable(s.string()),
  status: s.nullable(s.string()),
  orgId: s.nullable(s.string()),
  orgName: s.nullable(s.string()),
  orgType: s.nullable(s.string()),
  parentOrgId: s.nullable(s.string()),
  requestedBy: s.nullable(s.string()),
  requestedByRole: s.nullable(s.string()),
  requestedByOrgId: s.nullable(s.string()),
  reviewedBy: s.nullable(s.string()),
  decision: s.nullable(s.string()),
  rightsDirect: s.nullable(s.string()),
  rightsCeiling: s.nullable(s.string()),
  note: s.nullable(s.string()),
  meta: s.nullable(s.string()),
  createdAt: s.nullable(s.dateTime()),
  reviewedAt: s.nullable(s.dateTime()),
});

/** GET /api/orgs/onboarding?status → List<OnboardingRequestRow> (org.onboarding:read). */
export const ORG_ONBOARDING_LIST = s.array(ONBOARDING_REQUEST_ROW);
export type OrgOnboardingList = Infer<typeof ORG_ONBOARDING_LIST>;
export type OnboardingRequest = OrgOnboardingList[number];

/** POST /api/orgs/onboarding/{id}/decide → Dtos.OrgDecisionResult (org.onboarding:authorize). */
export const ORG_DECISION_RESULT = s.object('OrgDecisionResult', {
  requestId: s.nullable(s.string()),
  orgId: s.nullable(s.string()),
  status: s.nullable(s.string()),
  message: s.nullable(s.string()),
});
export type OrgDecisionResult = Infer<typeof ORG_DECISION_RESULT>;

/** The decision body (Dtos.OrgDecideRequest). */
export const ORG_ONBOARDING_DECIDE_REQUEST = s.object('OrgDecideRequest', {
  approve: s.boolean(),
  note: s.nullable(s.string()),
  sanctionsResult: s.nullable(s.string()),
});
export type OrgOnboardingDecideRequest = Infer<typeof ORG_ONBOARDING_DECIDE_REQUEST>;
