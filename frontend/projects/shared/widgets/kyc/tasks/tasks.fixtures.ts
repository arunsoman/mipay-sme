/**
 * Fixture scenarios for kyc.tasks (shapes: KycTaskRow, KycApplicationView - see tasks.contract.ts).
 */
import { defineFixtures, fail } from '../../core/fixtures';
import { fixturePage } from '../../core/server-list';

export const TASKS = [
  { id: 'task-101', orgId: 'org-sup-1', orgName: 'Kalahari Supplies', kind: 'KYC_REVIEW', stageKey: 'COMPLIANCE_REVIEW',
    stageSeq: 2, actorRule: 'PLATFORM_FUNCTION:kyc_reviewer', applicationStatus: 'IN_REVIEW',
    createdAt: '2026-11-14T08:12:00Z', openNote: 'Bank letter uploaded after the first return.' },
  { id: 'task-102', orgId: 'org-dist-7', orgName: 'Namib Distribution', kind: 'KYC_REVIEW', stageKey: 'ENTRY_CHECK',
    stageSeq: 1, actorRule: 'ORG_ADMIN:other', applicationStatus: 'ENTRY_CHECK',
    createdAt: '2026-11-13T15:40:00Z', openNote: null },
  { id: 'task-103', orgId: 'org-mfg-2', orgName: 'Swakop Foods Manufacturing', kind: 'KYC_REVIEW', stageKey: 'COMPLIANCE_REVIEW',
    stageSeq: 2, actorRule: 'PLATFORM_FUNCTION:kyc_reviewer', applicationStatus: 'IN_REVIEW',
    createdAt: '2026-11-12T10:05:00Z', openNote: 'UBO declared at 24% - check for a controller.' },
];

/** The application as the decision answers it (KycApplicationView). */
const decided = (outcome: string, orgId: string, orgName: string) => ({
  id: `kyc-${orgId}`, orgId, orgType: 'SUPPLIER', orgName, parentOrgId: 'org-dist-7',
  treePath: [{ id: 'org-root', type: 'PLATFORM', name: 'MiPay', depth: 0 }, { id: orgId, type: 'SUPPLIER', name: orgName, depth: 1 }],
  depth: 1, isSuspended: false, status: outcome === 'APPROVE' ? 'APPROVED' : 'REJECTED',
  application: null, submittedApplication: { businessProfile: { legalName: orgName } },
  submittedBy: 'user-ndapewa', submittedAt: '2026-11-14T08:00:00Z', decidedBy: 'user-reviewer',
  decidedAt: '2026-11-15T09:30:00Z', decisionNote: null, version: 5, masked: false,
  canEdit: false, canSubmit: false, canDecide: false, createdAt: '2026-11-10T08:00:00Z', updatedAt: '2026-11-15T09:30:00Z',
});

type Task = (typeof TASKS)[number];
/** The same fields as KycApplicationService.TASK_FIELDS. */
const search = (rows: Task[]) => fixturePage(rows, {
  search: [(t) => t.orgName, (t) => t.kind, (t) => t.stageKey, (t) => t.openNote],
  fields: { orgName: (t) => t.orgName, kind: (t) => t.kind, stageKey: (t) => t.stageKey,
            applicationStatus: (t) => t.applicationStatus, createdAt: (t) => t.createdAt },
});

const LONG = 'Oshakati Regional Wholesale & Cold-Chain Distribution Cooperative (Proprietary) Limited';

export default defineFixtures({
  default: {
    'kyc.tasks.search': search(TASKS),
    'kyc.tasks.decide': (p) => {
      const t = TASKS.find((x) => x.id === p['taskId']) ?? TASKS[0];
      return decided(String((p['body'] as { outcome?: string } | undefined)?.outcome ?? 'APPROVE'), t.orgId, t.orgName);
    },
  },
  empty: { 'kyc.tasks.search': search([]) },
  error: { 'kyc.tasks.search': fail(500, { title: 'Server error', detail: 'KYC inbox unavailable' }) },
  longText: { 'kyc.tasks.search': search(TASKS.map((t) => ({ ...t, orgName: LONG, openNote: `${LONG} - ${t.openNote ?? ''}` }))),
    'kyc.tasks.decide': decided('APPROVE', 'org-sup-1', LONG) },
  forbidden: { 'kyc.tasks.search': fail(403, { title: 'Forbidden', detail: 'kyc.task:read' }) },
}, { expect: { forbidden: 'forbidden' } });
