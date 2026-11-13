import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { PORTAL } from '../portal.config';

// ---------------------------------------------------------------------------
// API shapes (mirror com.mipay.sme.web.Dtos records used by /api/orgs/**)
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
  environment: string;
  simulated: boolean;
  username: string | null;
  orgId: string | null;
  orgType: string | null;
  orgRole: string | null;
}

export interface OrgView {
  id: string;
  type: string;
  name: string;
  status: string;
  effectiveStatus: string;
  parentOrgId: string | null;
  registrationNumber: string | null;
  createdAt: string | null;
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

export interface OrgHoldRow {
  id: string;
  reason: string;
  createdAt: string;
  createdBy: string;
  releasedAt: string | null;
}

export interface OrgSubtreeCounts {
  descendants: number;
  activeDescendants: number;
  users: number;
  activeUsers: number;
}

export interface OrgRightsView {
  direct: string;
  ceiling: string;
  effDirect: string;
  effCeiling: string;
}

/** A node of GET /api/orgs/me/tree (children nest under `children`). */
export interface OrgNodeRow {
  id: string;
  type: string;
  name: string;
  lifecycle: string;
  effectiveStatus: string;
  activeHolds: number;
  holds: OrgHoldRow[] | null;
  onboardedBy: string | null;
  onboardedAt: string | null;
  effectiveRights: string[] | null;
  users: OrgUserRow[] | null;
  userCount: number;
  subtree: OrgSubtreeCounts | null;
  rights: OrgRightsView | null;
  children: OrgNodeRow[] | null;
}

export type FlatNode = OrgNodeRow & { depth: number };

export interface GrantableChild {
  type: string;
  allowedCeiling: string[];
  defaultDirect: string[];
}

export interface GrantableView {
  effDirect: string[];
  children: GrantableChild[];
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
  reviewedBy: string | null;
  rightsDirect: string | null;
  rightsCeiling: string | null;
  note: string | null;
  createdAt: string | null;
  reviewedAt: string | null;
}

export interface OrgDecisionResult {
  requestId: string;
  orgId: string;
  status: string;
  message: string;
}

export interface OrgStaffView {
  id: string;
  name: string;
  username: string | null;
  status: string;
}

export interface MessageView {
  message: string;
  label: string | null;
}

export interface SuspensionRow {
  id: string;
  entityType: string;
  entityId: string;
  reason: string;
  suspendedAt: string;
  liftedAt: string | null;
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

export function refusal(e: unknown, hints: Record<string, string> = {}): string {
  const p = readProblem(e);
  const hint = p.code && hints[p.code] ? ` — ${hints[p.code]}` : '';
  return `Refused (${p.code ?? p.status}): ${p.detail}${hint}`;
}

/** Bearer token from this portal's own session into every /api call. */
export const portalAuthInterceptor: HttpInterceptorFn = (req, next) => {
  let raw: string | null = null;
  try { raw = localStorage.getItem(PORTAL.storageKey); } catch { /* storage blocked */ }
  if (raw && req.url.startsWith('/api/') && !req.url.startsWith('/api/auth/')) {
    req = req.clone({ setHeaders: { Authorization: `Bearer ${raw}` } });
  }
  return next(req);
};

// ---------------------------------------------------------------------------
// Tree helpers
// ---------------------------------------------------------------------------

export function flatten(root: OrgNodeRow): FlatNode[] {
  const out: FlatNode[] = [];
  const walk = (n: OrgNodeRow, d: number) => {
    out.push({ ...n, depth: d });
    (n.children ?? []).forEach((c) => walk(c, d + 1));
  };
  walk(root, 0);
  return out;
}

export function nodeById(root: OrgNodeRow, id: string): OrgNodeRow | null {
  if (root.id === id) return root;
  for (const c of root.children ?? []) {
    const hit = nodeById(c, id);
    if (hit) return hit;
  }
  return null;
}

export function splitList(s: string | null | undefined): string[] {
  return s ? s.split(',').map((x) => x.trim()).filter(Boolean) : [];
}

/** KYC lifecycle colours ([D6]/§6.2): live states warn, terminal bad, approved ok. */
export function kycStatusClass(s: string | null | undefined): string {
  if (!s) return '';
  if (s === 'APPROVED') return 'ok';
  if (s === 'REJECTED' || s === 'BLOCKED' || s === 'WITHDRAWN') return 'bad';
  if (['DRAFT', 'ENTRY_CHECK', 'VERIFYING', 'IN_REVIEW'].includes(s)) return 'warn';
  return '';
}

/** One open review task (§12 GET /api/kyc/tasks). */
export interface KycTaskRow {
  id: string; orgId: string; orgName: string; kind: string; stageKey: string;
  stageSeq: number; actorRule: string; applicationStatus: string;
  createdAt: string; openNote: string | null;
}

export function statusClass(s: string | null | undefined): string {
  if (!s) return '';
  if (s === 'ACTIVE' || s === 'APPROVED') return 'ok';
  if (s.startsWith('SUSPENDED') || s === 'REJECTED' || s === 'BLOCKED') return 'bad';
  if (s === 'PENDING' || s === 'PENDING_REVIEW') return 'warn';
  return '';
}

// ---------------------------------------------------------------------------
// KYC application (JSON document + tree details + isSuspended)
// ---------------------------------------------------------------------------

