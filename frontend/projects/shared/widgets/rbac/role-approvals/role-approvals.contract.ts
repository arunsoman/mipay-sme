/**
 * Contracts of rbac.role-approvals (OrgAccessController#approvals, #decide, #members).
 */
import { s, type Infer } from '../../core/shape';
import { ORG_MEMBERS } from '../assignments/assignments.contract';

/** GET /api/orgs/{id}/role-approvals (rbac.assignment:read): role requests waiting for the caller (Maps from RBAC). */
export const RBAC_APPROVALS_LIST = s.array(s.object('RoleRequest', {
  id: s.string(),
  subject: s.string(),
  role: s.string(),
  scope: s.nullable(s.string()),
  status: s.string(),
  requested_by: s.nullable(s.string()),
  requested_at: s.nullable(s.string()),
}));
export type RbacApprovalsList = Infer<typeof RBAC_APPROVALS_LIST>;
export type RoleRequest = RbacApprovalsList[number];

/** GET /api/orgs/{id}/members (org.user:read): names for the people in the requests. */
export const RBAC_APPROVALS_MEMBERS = ORG_MEMBERS;
export type RbacApprovalsMembers = Infer<typeof RBAC_APPROVALS_MEMBERS>;

/** POST /api/orgs/{id}/role-approvals/{assignmentId} (rbac.assignment:authorize): Map from RBAC, untyped. */
export const RBAC_APPROVALS_DECIDE = s.any();
/**
 * OrgAccessController.DecideBody {approve, reason}. Hand-written: widgets:endpoint resolves the
 * same-named PortalBuilderInternalController.DecideBody {approve, note}, which is not this one.
 */
export const RBAC_APPROVALS_DECIDE_REQUEST = s.object('DecideBody', {
  approve: s.nullable(s.boolean()),
  reason: s.nullable(s.string()),
});
export type RbacApprovalsDecideRequest = Infer<typeof RBAC_APPROVALS_DECIDE_REQUEST>;
