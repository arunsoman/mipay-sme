/**
 * Contracts of org.admin-recovery (OrgController#requestAdminRecovery).
 */
import { s, type Infer } from '../../core/shape';
import { ORG_DECISION_RESULT } from '../onboarding-queue/onboarding-queue.contract';
import { ORG_VIEW } from '../../ops/kyc-checks/kyc-checks.contract';

/** GET /api/orgs/{id} → Dtos.OrgView (org.tree:read): which organization the request is for. */
export const ORG_ADMIN_RECOVERY_ORG = ORG_VIEW;
export type OrgAdminRecoveryOrg = Infer<typeof ORG_ADMIN_RECOVERY_ORG>;

/** POST /api/orgs/{id}/admins → Dtos.OrgDecisionResult (org.onboarding:authorize): an ADMIN_RECOVERY request, decided in the onboarding queue. */
export const ORG_ADMIN_RECOVERY = ORG_DECISION_RESULT;
export type OrgAdminRecovery = Infer<typeof ORG_ADMIN_RECOVERY>;

/** Dtos.AdminRecoveryRequest: name, phone and email are required. */
export const ORG_ADMIN_RECOVERY_REQUEST = s.object('AdminRecoveryRequest', {
  adminName: s.string(),
  phone: s.string(),
  username: s.nullable(s.string()),
  email: s.string(),
  note: s.nullable(s.string()),
});
export type OrgAdminRecoveryRequest = Infer<typeof ORG_ADMIN_RECOVERY_REQUEST>;
