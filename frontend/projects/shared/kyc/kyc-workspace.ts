import { PageHeader } from '../../../src/app/design-system/organisms/page-header';
import { MpAlert } from '../../../src/app/design-system/molecules/alert';
import { ChangeDetectionStrategy, Component, computed, inject, input, OnInit, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import {
  KYC_HINTS, KycApplicationView, KycFlatNode, KycSummaryRow, KycTaskRow, KycTreeNode,
  flattenTree, kycStatusClass, refusal,
} from './kyc-api';
import { KycApplication } from './kyc-application';
import { Modal } from '../../../src/app/design-system/organisms/modal';
import { MpPerm } from '../web-core/perm-directive';
import { AccessService } from '../web-core/access';

const DECISIONS: Record<string, string[]> = {
  ENTRY_CHECK: ['CONFIRM', 'SEND_BACK'],
  STAGE: ['APPROVE', 'RETURN', 'REJECT', 'BLOCK'],
};
const LABELS: Record<string, string> = {
  CONFIRM: 'Confirm', SEND_BACK: 'Send back', APPROVE: 'Approve', RETURN: 'Return', REJECT: 'Reject', BLOCK: 'Block',
};

/**
 * The KYC page every portal shows: the caller's org tree with each org's KYC
 * status, the selected org's application (editable where the server says the
 * caller may edit) and the review tasks the caller may act on. Org portals
 * see their own org and everything below it; MiPay staff see the whole tree
 * from the platform root.
 */
@Component({
  selector: 'mp-kyc-workspace',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PageHeader, MpAlert, FormsModule, KycApplication, Modal, MpPerm],
  template: `
    <page-header title="Organizations">
      <div actions>
        <button class="ghost" (click)="reload()">Refresh</button>
      </div>
    </page-header>
    @if (problem(); as p) {<mp-alert kind="bad">{{ p }}</mp-alert>}

    <section class="panel tasks" [class.quiet]="!tasks().length">
      <div class="thead">
        <h3>Waiting for review <span class="chip" [class.warn]="tasks().length">{{ tasks().length }}</span></h3>
        <span class="muted small">applications waiting for your decision</span>
        @if (tasks().length) {
          <button class="ghost tog" (click)="tasksOpen.set(!tasksOpen())">{{ tasksOpen() ? 'Hide' : 'Show' }}</button>
        }
      </div>
      @if (taskFlash(); as f) {<mp-alert [kind]="f.kind">{{ f.text }}</mp-alert>}
      @if (tasksOpen()) {
      @for (t of tasks(); track t.id) {
        <div class="taskrow">
          <span class="chip warn small">{{ t.kind === 'ENTRY_CHECK' ? 'second check' : (t.stageKey === 'ops-review' ? 'MiPay review' : t.stageKey) }}</span>
          <button class="link nm" (click)="pick(t.orgId)">{{ t.orgName }}</button>
          <span class="chip {{ cls(t.applicationStatus) }}">{{ t.applicationStatus.replace('_', ' ') }}</span>
          <span class="grow"></span>
          <button *mpPerm="'kyc.task:authorize'" class="primary dec" [disabled]="busy()" (click)="openReview(t)">Review</button>
        </div>
      } @empty {
        <div class="muted small">No open tasks for you right now.</div>
      }
      }
    </section>

    <div class="cols">
      <div class="panel list">
        <div class="lhead"><span>Organizations</span><span class="muted small">{{ nodes().length }}</span></div>
        <input class="filter" [(ngModel)]="filter" [ngModelOptions]="o" placeholder="Filter organizations" />
        @for (n of shownNodes(); track n.id) {
          @let s = summary(n.id);
          <div class="node" [class.selected]="n.id === selectedId()" (click)="pick(n.id)"
               role="button" tabindex="0" (keydown.enter)="pick(n.id)">
            <span class="indent" [style.width.px]="n.depth * 14"></span>
            <span class="nm"><span class="nmt">{{ n.name }}</span><span class="ty">{{ n.type }}</span></span>
            <span class="dot {{ cls(s?.status ?? '') }}" [title]="(s?.status ?? 'NOT STARTED').replace('_', ' ')"></span>
            <span class="st {{ cls(s?.status ?? '') }}">{{ (s?.status ?? 'not started').replace('_', ' ').toLowerCase() }}</span>
          </div>
        } @empty {
          <div class="muted small">No organizations.</div>
        }
      </div>
      <div class="panel details">
        @if (selectedId(); as id) {
          @for (k of [viewKey()]; track k) {
            <mp-kyc-application [orgId]="id" [readOnly]="true" [allowEdit]="access.can('kyc.application:modify')" (edit)="editing.set(true)" />
          }
          <mp-modal [open]="editing()" size="wide" eyebrow="KYC application" [title]="selectedName()"
                    [dirty]="editor()?.isDirty() ?? false"
                    discardText="Discard the unsaved changes to this KYC application?"
                    (closed)="closeEditor()">
            @if (editing()) {
              <!-- the same five-step form as onboarding, opened on the saved data -->
              <mp-kyc-application #editor [orgId]="id" [wizard]="true" (changed)="onChanged($event)" />
            }
          </mp-modal>
        } @else {
          <div class="muted">Pick an organization.</div>
        }
      </div>
    </div>

    <!-- the approver's view: the same KYC form, read-only, with Approve / Reject and a reason -->
    <mp-modal [open]="!!reviewing()" size="wide" eyebrow="KYC review" [title]="reviewing()?.orgName ?? ''"
              [dirty]="!!reviewReason.trim()" discardText="Close without deciding? The reason you typed will be lost."
              (closed)="reviewing.set(null)">
      @if (reviewing(); as t) {
        <mp-kyc-application [orgId]="t.orgId" [readOnly]="true" [allowEdit]="false" />
        <label class="reason"><span>Reason <em>required to reject; sent to the organization</em></span>
          <textarea rows="2" [(ngModel)]="reviewReason" [ngModelOptions]="o"
                    placeholder="e.g. The registration certificate does not match the legal name"></textarea></label>
        @if (reviewError(); as e) {<mp-alert kind="bad">{{ e }}</mp-alert>}
      }
      <div modal-footer>
        <button type="button" class="danger" [disabled]="busy() || !reviewReason.trim()" (click)="decideReview(false)">Reject</button>
        <button type="button" class="primary" [disabled]="busy()" (click)="decideReview(true)">Approve</button>
      </div>
    </mp-modal>
  `,
  styles: [`
    .reason { display: grid; gap: 7px; margin-top: 8px; font-size: 13px; font-weight: 550; color: #d9d9d9; }
    .reason em { font-style: normal; font-weight: 400; color: var(--a-dim); font-size: 12px; margin-left: 6px; }
    .reason textarea { width: 100%; box-sizing: border-box; border-radius: 10px; background: #0d0d0d; border: 1px solid #2a2a2a;
                       color: var(--a-text); padding: 10px 12px; font: inherit; font-weight: 400; resize: vertical; }
    h2 { margin: 0; font-size: 30px; line-height: 1.1; flex: 1; }
    h3 { margin: 0; font-size: 14px; display: flex; gap: 8px; align-items: center; }
    .cols { display: grid; grid-template-columns: minmax(240px, 340px) minmax(0, 1fr); gap: 14px; align-items: start; }
    @media (max-width: 900px) { .cols { grid-template-columns: minmax(0, 1fr); } }
    .panel { padding: 12px; }
    .list { overflow-x: hidden; }
    .list { position: sticky; top: 12px; max-height: calc(100vh - 120px); overflow: auto; display: grid; gap: 2px; align-content: start; }
    @media (max-width: 900px) { .list { position: static; max-height: 40vh; } }
    .lhead { display: flex; justify-content: space-between; font-size: 12.5px; font-weight: 600; margin-bottom: 6px; }
    .dot { flex: none; width: 8px; height: 8px; border-radius: 50%; background: var(--a-border); }
    .dot.ok { background: var(--a-green); } .dot.warn { background: var(--a-amber); } .dot.bad { background: var(--a-red); }
    .st { font-size: 10.5px; color: var(--a-dim); white-space: nowrap; }
    .st.ok { color: var(--a-green); } .st.warn { color: var(--a-amber); } .st.bad { color: var(--a-red); }
    .filter { margin-bottom: 8px; width: 100%; box-sizing: border-box; min-width: 0; }
    .node { display: flex; align-items: center; gap: 6px; padding: 6px 8px; border-radius: 8px; cursor: pointer; min-width: 0; }
    .node .nm { display: flex; flex-direction: column; line-height: 1.25; }
    .nmt { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .node:hover { background: var(--a-panel-2); }
    .node.selected { background: color-mix(in srgb, var(--a-teal) 14%, transparent); }
    .indent { flex: none; }
    .nm { flex: 1; min-width: 0; font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; text-align: left; }
    .ty { font-size: 10px; color: var(--a-dim); }
    .details { min-width: 0; }
    .tasks { margin-bottom: 14px; }
    .tasks.quiet { padding: 8px 12px; }
    .tasks.quiet .thead { margin-bottom: 0; }
    .tog { margin-left: auto; padding: 3px 10px; }
    .thead { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; flex-wrap: wrap; }
    .taskrow { display: flex; align-items: center; gap: 8px; border-top: 1px solid var(--a-border); padding: 8px 2px; flex-wrap: wrap; }
    .taskrow .nm { flex: 1 1 140px; }
    .tnote { flex: 1 1 200px; min-width: 150px; }
    .link { background: none; border: none; padding: 0; color: var(--a-teal); cursor: pointer; }
    .dec { padding: 6px 14px; font-size: 11.5px; font-weight: 800; letter-spacing: .06em; text-transform: uppercase; }
    .small { font-size: var(--fs-xs); }
  `],
})
export class KycWorkspace implements OnInit {
  /** Org to open first (e.g. from ?org= in the URL); defaults to the caller's own org. */
  readonly initialOrgId = input<string | null>(null);

  private readonly http = inject(HttpClient);
  /** Every action here hides itself without its permission (*mpPerm). */
  readonly access = inject(AccessService);
  readonly cls = kycStatusClass;
  readonly o = { standalone: true };

  readonly tree = signal<KycTreeNode | null>(null);
  readonly summaries = signal<KycSummaryRow[]>([]);
  readonly selectedId = signal<string | null>(null);
  readonly tasks = signal<KycTaskRow[]>([]);
  readonly busy = signal(false);
  readonly tasksOpen = signal(true);
  readonly editing = signal(false);
  readonly viewKey = signal(0);
  readonly editor = viewChild<KycApplication>('editor');
  selectedName() {
    const id = this.selectedId();
    return this.nodes().find((n) => n.id === id)?.name ?? 'KYC';
  }

  /** Close the editor popup and refresh the read-only view behind it. */
  closeEditor() {
    this.editing.set(false);
    this.viewKey.update((k) => k + 1);
    void this.loadSummaries();
  }
  readonly problem = signal('');
  readonly taskFlash = signal<{ kind: 'ok' | 'bad'; text: string } | null>(null);
  taskNotes: Record<string, string | null> = {};
  filter = '';

  readonly nodes = computed<KycFlatNode[]>(() => (this.tree() ? flattenTree(this.tree()!) : []));
  private readonly byOrg = computed(() => new Map(this.summaries().map((s) => [s.orgId, s])));

  shownNodes(): KycFlatNode[] {
    const f = this.filter.trim().toLowerCase();
    return f ? this.nodes().filter((n) => n.name.toLowerCase().includes(f) || n.type.toLowerCase().includes(f)) : this.nodes();
  }

  summary(orgId: string) { return this.byOrg().get(orgId) ?? null; }
  label(oc: string) { return LABELS[oc] ?? oc; }
  decisionsFor(t: KycTaskRow) { return DECISIONS[t.kind] ?? DECISIONS['STAGE']; }

  async ngOnInit() {
    await this.reload();
  }

  async reload() {
    this.problem.set('');
    try {
      const t = await firstValueFrom(this.http.get<KycTreeNode>('/api/orgs/me/tree'));
      this.tree.set(t);
      // the platform root has no KYC: open on the first organization below it
      const first = t.type === 'PLATFORM' ? (flattenTree(t).find((n) => n.type !== 'PLATFORM')?.id ?? t.id) : t.id;
      if (!this.selectedId()) this.selectedId.set(this.initialOrgId() ?? first);
    } catch (e) {
      this.problem.set(refusal(e, KYC_HINTS));
    }
    await Promise.all([this.loadSummaries(), this.loadTasks()]);
  }

  pick(orgId: string) { this.editing.set(false); this.selectedId.set(orgId); }

  async onChanged(_v: KycApplicationView) {
    await Promise.all([this.loadSummaries(), this.loadTasks()]);
  }

  private async loadSummaries() {
    try {
      // every status, not only open ones, so approved / rejected orgs show their state too
      const [open, all] = await Promise.all([
        firstValueFrom(this.http.get<KycSummaryRow[]>('/api/kyc/orgs')),
        firstValueFrom(this.http.get<KycSummaryRow[]>('/api/kyc/orgs', { params: { suspended: 'false' } }))
          .catch(() => [] as KycSummaryRow[]),
      ]);
      const latest = new Map<string, KycSummaryRow>();
      for (const r of [...all, ...open]) {
        const cur = latest.get(r.orgId);
        if (!cur || (r.updatedAt ?? '') >= (cur.updatedAt ?? '')) latest.set(r.orgId, r);
      }
      this.summaries.set([...latest.values()]);
    } catch { /* the list is decoration; the editor still works */ }
  }

  private async loadTasks() {
    try {
      this.tasks.set(await firstValueFrom(this.http.get<KycTaskRow[]>('/api/kyc/tasks')));
    } catch {
      this.tasks.set([]);
    }
  }

  readonly reviewing = signal<KycTaskRow | null>(null);
  readonly reviewError = signal('');
  reviewReason = '';

  openReview(t: KycTaskRow) {
    this.reviewReason = '';
    this.reviewError.set('');
    this.reviewing.set(t);
  }

  /** Approve / Reject from the read-only review (an entry check confirms / sends back). */
  async decideReview(approve: boolean) {
    const t = this.reviewing();
    if (!t) return;
    if (!approve && !this.reviewReason.trim()) { this.reviewError.set('Give a reason to reject.'); return; }
    const outcome = t.kind === 'ENTRY_CHECK' ? (approve ? 'CONFIRM' : 'SEND_BACK') : (approve ? 'APPROVE' : 'REJECT');
    this.taskNotes = { ...this.taskNotes, [t.id]: this.reviewReason.trim() || null };
    if (await this.decide(t, outcome)) this.reviewing.set(null);
    else this.reviewError.set(this.taskFlash()?.text ?? 'Not decided');
  }

  async decide(t: KycTaskRow, outcome: string): Promise<boolean> {
    this.busy.set(true);
    this.taskFlash.set(null);
    try {
      const v = await firstValueFrom(this.http.post<KycApplicationView>(
        `/api/kyc/tasks/${t.id}/decision`, { outcome, note: this.taskNotes[t.id] || null }));
      this.taskFlash.set({ kind: outcome === 'APPROVE' || outcome === 'CONFIRM' ? 'ok' : 'bad',
        text: `${this.label(outcome)}: ${v.orgName}'s application is now ${v.status.replace('_', ' ').toLowerCase()}.` });
      this.taskNotes = { ...this.taskNotes, [t.id]: null };
      // re-open the decided org so the form shows its new state
      if (this.selectedId() === v.orgId) { this.selectedId.set(null); queueMicrotask(() => this.selectedId.set(v.orgId)); }
      await Promise.all([this.loadSummaries(), this.loadTasks()]);
      return true;
    } catch (e) {
      this.taskFlash.set({ kind: 'bad', text: refusal(e, KYC_HINTS) });
      return false;
    } finally {
      this.busy.set(false);
    }
  }
}
