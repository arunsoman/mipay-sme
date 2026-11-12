import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';

// ---------------------------------------------------------------------------
// API shapes (mirrors com.mipay.sme.web.Dtos records)
// ---------------------------------------------------------------------------

export interface TokenView {
  token: string;
  tokenType: string;
  expiresInSeconds: number;
  role: string;
}

export interface MeView {
  userId: string;
  name: string;
  role: string;
  phone: string;
  merchantId: string | null;
  supplierId: string | null;
  lenderId: string | null;
  environment: string;
  simulated: boolean;
  username: string | null;
  orgId: string | null;
  orgType: string | null;
  orgRole: string | null;
}

export interface OrgUserRow {
  id: string;
  name: string;
  username: string | null;
  role: string;
  orgRole: string | null;
  status: string;
  phone: string | null;
  email: string | null;
}

export interface OrgSubtreeCounts {
  descendants: number;
  activeDescendants: number;
  users: number;
  activeUsers: number;
}

/** A node of GET /api/orgs/me/tree (children nest under `children`). */
export interface OrgNodeRow {
  id: string;
  type: string;
  name: string;
  lifecycle: string;
  effectiveStatus: string;
  activeHolds: number;
  userCount: number;
  effectiveRights: string[] | null;
  users: OrgUserRow[];
  subtree: OrgSubtreeCounts;
  children: OrgNodeRow[];
}

export type OnboardingStatus = 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED' | 'BLOCKED';

export interface OnboardingRequestRow {
  id: string;
  kind: string;
  status: string;
  orgId: string;
  orgName: string | null;
  orgType: string | null;
  parentOrgId: string | null;
  requestedBy: string;
  requestedByRole: string | null;
  requestedByOrgId: string | null;
  reviewedBy: string | null;
  decision: string | null;
  rightsDirect: string | null;
  rightsCeiling: string | null;
  note: string | null;
  meta: string | null;
  createdAt: string | null;
  reviewedAt: string | null;
}

export interface OrgStaffView {
  id: string;
  name: string;
  username: string | null;
  phone: string;
  email: string;
  role: string;
  orgRole: string | null;
  status: string;
  activatedAt: string | null;
}

export interface OrgRightsView {
  direct: string;
  ceiling: string;
  effDirect: string;
  effCeiling: string;
}

export interface GrantableChild {
  type: string;
  allowedCeiling: string[];
  defaultDirect: string[];
}

export interface GrantableView {
  effDirect: string[];
  children: GrantableChild[];
}

export interface KycCheckRow {
  id: string;
  entityType: string;
  entityId: string;
  checkType: string;
  result: string;
  details: string;
  checkedAt: string;
}

export interface KycCheckRequest {
  checkType: string;
  result: string;
  details: string;
}

export interface OutboxRow {
  id: string;
  eventCode: string;
  channel: string;
  recipient: string;
  status: string;
  createdAt: string;
  activationLink: string | null;
}

export interface OrgView {
  id: string;
  type: string;
  name: string;
  status: string;
  effectiveStatus: string;
  parentOrgId: string | null;
  entityId: string | null;
  registrationNumber: string | null;
  createdAt: string | null;
}

export interface MessageView {
  message: string;
  id: string | null;
}

export interface OrgDecisionResult {
  requestId: string;
  orgId: string;
  status: string;
  message: string;
}

// ---------------------------------------------------------------------------
// Error normalisation (ProblemDetail + machine-readable code)
// ---------------------------------------------------------------------------

export interface ApiProblem {
  title: string;
  detail: string;
  code?: string;
  status: number;
}

export function readProblem(err: unknown): ApiProblem {
  if (err instanceof HttpErrorResponse) {
    const body = (err.error ?? {}) as Record<string, unknown>;
    const code = typeof body['code'] === 'string' ? body['code'] : undefined;
    return {
      title: String(body['title'] ?? err.statusText ?? 'Error'),
      detail: String(body['detail'] ?? 'Request failed'),
      ...(code ? { code } : {}),
      status: err.status,
    };
  }
  return { title: 'Error', detail: String(err), status: 0 };
}

/** Bearer token from the admin-portal session into every /api call. */
export const adminAuthInterceptor: HttpInterceptorFn = (req, next) => {
  const raw = localStorage.getItem('mp.admin.token');
  if (raw && req.url.startsWith('/api/')) {
    req = req.clone({ setHeaders: { Authorization: `Bearer ${raw}` } });
  }
  return next(req);
};

// ---------------------------------------------------------------------------
// KYC application (JSON document + tree details + isSuspended)
// ---------------------------------------------------------------------------

export interface KycPayload {
  businessProfile: Record<string, string | null> | null;
  operationalDetails: Record<string, string | number | null> | null;
  addresses: Record<string, string | null>[] | null;
  ubos: Record<string, string | number | boolean | null>[] | null;
  bankAccounts: Record<string, string | null>[] | null;
}

/** One open review task in the caller's inbox (§12 GET /api/kyc/tasks). */
export interface KycTaskRow {
  id: string; orgId: string; orgName: string; kind: string; stageKey: string;
  stageSeq: number; actorRule: string; applicationStatus: string;
  createdAt: string; openNote: string | null;
}

export type KycApplicationStatus =
  'NOT_STARTED' | 'DRAFT' | 'ENTRY_CHECK' | 'VERIFYING' | 'IN_REVIEW'
  | 'APPROVED' | 'REJECTED' | 'BLOCKED' | 'WITHDRAWN';

export interface KycApplicationView {
  id: string | null;
  orgId: string; orgType: string; orgName: string; parentOrgId: string | null;
  treePath: { id: string; type: string; name: string; depth: number }[];
  depth: number; isSuspended: boolean;
  status: KycApplicationStatus;
  application: KycPayload | null; submittedApplication: KycPayload | null;
  submittedBy: string | null; submittedAt: string | null;
  decidedBy: string | null; decidedAt: string | null; decisionNote: string | null;
  version: number; masked: boolean; canEdit: boolean; canSubmit: boolean; canDecide: boolean;
}

// ---------------------------------------------------------------------------
// RBAC administration (cut-over P5.5): /api/admin/rbac/*, backed by RBAC's mesh API.
// Mirrors com.mipay.sme.web.Dtos.RbacRoleView, RbacRoleUpdate, RbacResourceView,
// RbacUserView, RbacAssignmentView.
// ---------------------------------------------------------------------------

/** own = cells ticked on the role itself (what the matrix edits); permissions = effective. */
export interface RbacRoleView {
  name: string;
  description: string;
  permissions: string[];
  kind: string;
  approval: string;
  seeded: boolean;
  /** seeded and the environment is uat/production: change it through the reviewed grid.yaml */
  locked: boolean;
  inherits: string[];
  own: string[];
  allRows: string[];
}

/** The role's OWN cells ("row:verb"). */
export interface RbacRoleUpdate {
  permissions: string[];
}

/** A grid row; only the verbs in `used` exist as cells. */
export interface RbacEndpointView { verb: string; method: string; path: string; handler: string; }

export interface RbacResourceView {
  id: string;
  service: string;
  description: string;
  used: string[];
  tenantTypes: string[];
  /** The endpoints guarded by this row's cells (handler "Controller#method"). */
  endpoints?: RbacEndpointView[];
}

export interface RbacUserView {
  id: string;
  displayName: string;
  orgId: string | null;
  kind: string;
  status: string;
}

export interface RbacAssignmentView {
  id: string;
  subject: string;
  role: string;
  tenant: string;
  scope: string;
  status: string;
}

export type RbacVerb =
  | 'read' | 'create' | 'modify' | 'delete' | 'submit'
  | 'authorize' | 'execute' | 'export' | 'administer' | 'simulate';

export const RBAC_VERBS: RbacVerb[] = [
  'read', 'create', 'modify', 'delete', 'submit',
  'authorize', 'execute', 'export', 'administer', 'simulate',
];

/** own: ticked on the role (editable) · inherited: from a junior role or an all-rows tick (locked) ·
 *  off: not granted (editable) · na: no endpoint uses this cell, so it doesn't exist. */
export type RbacCellState = 'own' | 'inherited' | 'off' | 'na';
