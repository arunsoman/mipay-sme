import { HttpErrorResponse } from '@angular/common/http';

/**
 * Types and helpers shared by every portal's KYC screens (admin portal and
 * the manufacturer / distributor / supplier org portals). Kept free of any
 * portal-specific config so both Angular applications can import it.
 */

export type KycDoc = Record<string, unknown>;

export interface OrgPathRow { id: string; type: string; name: string; depth: number; }

export type KycApplicationStatus =
  'NOT_STARTED' | 'DRAFT' | 'ENTRY_CHECK' | 'VERIFYING' | 'IN_REVIEW'
  | 'APPROVED' | 'REJECTED' | 'BLOCKED' | 'WITHDRAWN';

export interface KycApplicationView {
  id: string | null;
  orgId: string; orgType: string; orgName: string; parentOrgId: string | null;
  treePath: OrgPathRow[]; depth: number; isSuspended: boolean;
  status: KycApplicationStatus;
  application: KycDoc | null; submittedApplication: KycDoc | null;
  submittedBy: string | null; submittedAt: string | null;
  decidedBy: string | null; decidedAt: string | null; decisionNote: string | null;
  version: number; masked: boolean; canEdit: boolean; canSubmit: boolean; canDecide: boolean;
  createdAt: string | null; updatedAt: string | null;
}

export interface KycSummaryRow {
  id: string; orgId: string; orgType: string; orgName: string; parentOrgId: string | null;
  treePath: string; depth: number; isSuspended: boolean; status: string;
  submittedAt: string | null; decidedAt: string | null; updatedAt: string;
}

export interface KycTaskRow {
  id: string; orgId: string; orgName: string; kind: string; stageKey: string;
  stageSeq: number; actorRule: string; applicationStatus: string;
  createdAt: string; openNote?: string | null; note?: string | null;
}

/** The parts of an org tree node the KYC screens need (GET /api/orgs/me/tree). */
export interface KycTreeNode {
  id: string; type: string; name: string; lifecycle: string;
  children: KycTreeNode[] | null;
}
export type KycFlatNode = KycTreeNode & { depth: number };

export interface GrantableChild { type: string; allowedCeiling: string[]; defaultDirect: string[]; }
export interface GrantableView { effDirect: string[]; children: GrantableChild[]; }
export interface OrgDecisionResult { requestId: string; orgId: string; status: string; message: string; }

export interface ApiProblem { title: string; detail: string; code?: string; status: number; }

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

export function flattenTree(root: KycTreeNode): KycFlatNode[] {
  const out: KycFlatNode[] = [];
  const walk = (n: KycTreeNode, d: number) => {
    out.push({ ...n, depth: d });
    (n.children ?? []).forEach((c) => walk(c, d + 1));
  };
  walk(root, 0);
  return out;
}

/** KYC lifecycle colours: live states warn, terminal bad, approved ok. */
export function kycStatusClass(s: string | null | undefined): string {
  if (!s) return '';
  if (s === 'APPROVED') return 'ok';
  if (s === 'REJECTED' || s === 'BLOCKED' || s === 'WITHDRAWN') return 'bad';
  if (['DRAFT', 'ENTRY_CHECK', 'VERIFYING', 'IN_REVIEW'].includes(s)) return 'warn';
  return '';
}

export function titleCase(t: string | null | undefined): string {
  return t ? t.charAt(0) + t.slice(1).toLowerCase().replace(/_/g, ' ') : '—';
}

/** Server problems that every KYC screen explains the same way. */
export const KYC_HINTS: Record<string, string> = {
  KYC_LOCKED: 'submitted applications are locked until they are decided',
  KYC_VERSION_CONFLICT: 'someone else saved this application meanwhile — reload it',
  KYC_EDIT_AUTHORITY: 'only the organization’s ADMIN, its parent’s ADMIN or MiPay staff can edit',
  KYC_NOT_WITHDRAWABLE: 'only drafts and entry checks can be withdrawn',
  KYC_NOT_DRAFT: 'only a draft can be submitted',
  ENTRY_CHECKER_UNAVAILABLE: 'no other active ADMIN in your organization can check it — submit without a checker',
  OUTCOME_NOT_ALLOWED: 'this outcome is not allowed for the task stage',
  MAKER_CANNOT_ACT: 'whoever submitted the application cannot review it (second-person rule)',
  DOUBLE_ACT: 'you already acted on this submission; another reviewer must take this step',
  NO_FUNCTION: 'your account does not hold the platform function this stage needs',
  ORG_SUSPENDED: 'the organization (or one above it) is suspended',
  RIGHTS_STALE: 'the onboarder’s rights were narrowed since submission',
  ONBOARDING_NOT_ALLOWED: 'your organization is not allowed to onboard this type',
  NO_RIGHT: 'your organization is not allowed to onboard this type',
  RIGHTS_EXCEED_CEILING: 'you can only pass on rights your organization holds',
  RIGHTS_INVALID_FOR_TYPE: 'that type can only onboard types below its own rank',
  RANK_RULE: 'that type cannot sit under your organization',
  PARENT_NOT_OPERATIONAL: 'your organization is suspended',
  DUPLICATE_PHONE: 'that phone number is already registered',
};
