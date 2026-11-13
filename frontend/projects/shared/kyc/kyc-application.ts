import { MpSpinner } from '../../../src/app/design-system/atoms/mp-spinner';
import { MpAlert } from '../../../src/app/design-system/molecules/alert';
import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal, untracked, viewChild } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { catchError, firstValueFrom, of } from 'rxjs';
import { KYC_HINTS, KycApplicationView, KycDoc, kycStatusClass, readProblem, refusal } from './kyc-api';
import { KycSchemaEditor, SchemaResponse } from './kyc-schema-editor';
import { MpPerm } from '../web-core/perm-directive';

/**
 * One organization's KYC application: status, the schema-driven form, and
 * Save / Submit / Withdraw. Who may edit is decided by the server
 * (view.canEdit: the org's own ADMIN, its parent org's ADMIN, or MiPay staff);
 * everyone else gets the same form read-only, masked for higher ancestors.
 */
@Component({
  selector: 'mp-kyc-application',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpSpinner, MpAlert, DatePipe, FormsModule, KycSchemaEditor, MpPerm],
  template: `
    @if (loadError(); as e) {<mp-alert kind="bad">{{ e }}</mp-alert>}
    @if (view(); as v) {
      @if (wizard()) {
        @if (v.status === 'DRAFT' && v.decisionNote) {<div class="note warn">Returned for correction: {{ v.decisionNote }}</div>}
      } @else {
      <header class="card">
      <div class="ptitle">{{ v.orgName }} <span class="chip">{{ v.orgType }}</span>
        <span class="chip {{ cls(v.status) }}">{{ v.status.replace('_', ' ') }}</span>
        @if (v.isSuspended) {<span class="chip bad">SUSPENDED</span>}
        @if (readOnly() && allowEdit() && v.canEdit && v.orgType !== 'PLATFORM') {
          <button class="primary editbtn" (click)="edit.emit(v.orgId)">{{ doc() ? 'Edit KYC' : 'Start KYC application' }}</button>
        }
      </div>
      <div class="muted small">{{ pathText(v) }}</div>
      @if (v.masked) {<div class="note">Identity and account numbers are masked: only the organization itself, its parent and MiPay see them in full.</div>}
      @switch (v.status) {
        @case ('REJECTED') {<div class="note bad">Rejected{{ v.decisionNote ? ': ' + v.decisionNote : '' }}.</div>}
        @case ('BLOCKED') {<div class="note bad">Blocked{{ v.decisionNote ? ': ' + v.decisionNote : '' }}. Contact MiPay to resolve.</div>}
        @case ('WITHDRAWN') {<div class="note">Withdrawn {{ v.updatedAt | date:'MMM d, HH:mm' }}.</div>}
        @case ('APPROVED') {<div class="note ok">Approved@if (v.decidedAt) { on {{ v.decidedAt | date:'MMM d, y' }}}@if (v.decisionNote) { — {{ v.decisionNote }}}</div>}
        @case ('ENTRY_CHECK') {<div class="note warn">Waiting for another ADMIN of the submitting organization to check it.</div>}
        @case ('VERIFYING') {<div class="note warn">Automated verification running.</div>}
        @case ('IN_REVIEW') {<div class="note warn">Submitted {{ v.submittedAt | date:'MMM d, HH:mm' }} — awaiting review. Locked until decided.</div>}
        @case ('DRAFT') {@if (v.decisionNote) {<div class="note warn">Returned for correction: {{ v.decisionNote }}</div>}}
      }
      </header>
      }

      @if (v.orgType === 'PLATFORM') {
        <div class="muted">The platform root is MiPay itself and has no KYC application. Pick an organization below it.</div>
      } @else if (readOnly() && doc() && !hasContent(doc())) {
        <div class="emptynote">Nothing has been filled in yet.
          @if (v.canEdit) { Use <b>Edit KYC</b> to start the application. }</div>
      } @else if (doc(); as d) {
        @if (schema(); as sch) {
          @for (k of [docKey()]; track k) {
            <mp-kyc-schema-editor #ed [schema]="sch" [doc]="d" [disabled]="!v.canEdit || readOnly()" [context]="context()"
                                  [problems]="problems()" [wizard]="wizard()" [startStep]="reopenStep() ?? startStep()" />
          }
        } @else {
          <mp-spinner label="Loading the KYC form…" />
        }
      } @else if (v.canEdit) {
        <div class="muted">No KYC application yet.</div>
      } @else {
        <div class="muted">No KYC application has been started for this organization.</div>
      }

      @if (wizard() && v.canEdit && doc()) {
        <div class="wfoot">
          @if (flash(); as f) {<mp-alert [kind]="f.kind">{{ f.text }}</mp-alert>}
          <div class="wrow">
            <button type="button" class="ghost" (click)="back()" [disabled]="busy() || (editor()?.stepIdx() ?? 0) === 0">Back</button>
            <span class="grow"></span>
            <button type="button" class="ghost" (click)="save()" [disabled]="busy()">Save draft</button>
            @if (editor()?.isLastStep()) {
              <button *mpPerm="'kyc.application:submit'" type="button" class="primary" (click)="saveAndSubmit()" [disabled]="busy()">Submit for review</button>
            } @else {
              <button type="button" class="primary" (click)="next()" [disabled]="busy()">Continue</button>
            }
          </div>
        </div>
      } @else if (readOnly()) {
        @if (flash(); as f) {<mp-alert [kind]="f.kind">{{ f.text }}</mp-alert>}
      } @else if (v.canEdit && v.orgType !== 'PLATFORM') {
        <div class="actions" [class.stuck]="!!doc()">
          @if (!doc()) {
            <button class="primary" (click)="start()" [disabled]="!schema()">Start KYC application</button>
          } @else {
            <button (click)="save()" [disabled]="busy()">Save draft</button>
            <button *mpPerm="'kyc.application:submit'" class="primary" (click)="saveAndSubmit()" [disabled]="busy()">Submit for review</button>
            <label class="chk small"><input type="checkbox" [(ngModel)]="sendToChecker" [ngModelOptions]="o" />
              ask another ADMIN of my organization to check it first</label>
            @if (v.status === 'DRAFT' || v.status === 'ENTRY_CHECK') {
              @if (v.id) {<button *mpPerm="'kyc.application:delete'" class="ghost wd" (click)="withdraw()" [disabled]="busy()">Withdraw</button>}
            }
          }
          @if (flash(); as f) {<mp-alert [kind]="f.kind">{{ f.text }}</mp-alert>}
        </div>
      } @else if (flash(); as f) {<mp-alert [kind]="f.kind">{{ f.text }}</mp-alert>}
    } @else if (!loadError()) {
      <mp-spinner label="Loading…" />
    }
  `,
  styles: [`
    :host { display: grid; gap: 12px; min-width: 0; }
    .note { font-size: var(--fs-sm); padding: 8px 10px; border-radius: 8px; background: var(--a-panel-2); }
    .note.ok { color: var(--a-green); } .note.bad { color: var(--a-red); } .note.warn { color: var(--a-amber); }
    .editbtn { margin-left: auto; }
    .emptynote { padding: 18px; border: 1px dashed var(--a-border); border-radius: 12px; color: var(--a-dim); font-size: var(--fs-sm); }
    .card { display: grid; gap: 8px; padding-bottom: 10px; border-bottom: 1px solid var(--a-border); }
    .actions { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
    .actions.stuck { position: sticky; bottom: 0; z-index: 2; padding: 10px 12px; margin: 0 -12px -12px;
                     background: color-mix(in srgb, var(--a-panel) 92%, transparent); backdrop-filter: blur(6px);
                     border-top: 1px solid var(--a-border); border-radius: 0 0 10px 10px; }
    .actions .wd { margin-left: auto; }
    .actions .flash { flex-basis: 100%; }
    .chk { display: flex; align-items: center; gap: 6px; color: var(--a-dim); }
    .chk input { width: auto; }
    .small { font-size: var(--fs-xs); }
    .wfoot { position: sticky; bottom: -18px; z-index: 2; margin: 8px -22px -18px; padding: 14px 22px;
             background: color-mix(in srgb, #111 94%, transparent); backdrop-filter: blur(6px);
             border-top: 1px solid var(--a-border); display: grid; gap: 10px; }
    .wrow { display: flex; gap: 10px; align-items: center; }
    .wrow .grow { flex: 1; }
    .wrow button { min-height: 42px; padding: 0 20px; }
  `],
})
export class KycApplication {
  readonly orgId = input.required<string>();
  /** Fires after any save / submit / withdraw so hosts can refresh lists and inboxes. */
  readonly changed = output<KycApplicationView>();
  /** Display-only: the form is disabled and editors get an "Edit KYC" button instead. */
  readonly readOnly = input(false);
  /** Emitted by the read-only view's Edit / Start button. */
  readonly edit = output<string>();
  /** Onboarding wizard: no header, one footer (Back · Save draft · Continue / Submit). */
  readonly wizard = input(false);
  /** Read-only views may offer "Edit KYC"; the approver's review never does. */
  readonly allowEdit = input(true);
  /** Step the form opens on (0-based). */
  readonly startStep = input(0);
  readonly editor = viewChild<KycSchemaEditor>('ed');

  back() {
    const e = this.editor();
    if (e) e.goto(e.stepIdx() - 1);
    this.flash.set(null);
  }

  /** Continue: the draft is saved on the way, so nothing typed is ever lost. */
  async next() {
    const e = this.editor();
    if (!e) return;
    await this.save(true, e.stepIdx() + 1);
  }
  private snapshot = '';

  /** True when the form has changes that are not saved yet. */
  isDirty(): boolean {
    const d = this.doc();
    return !!d && !this.readOnly() && JSON.stringify(d) !== this.snapshot;
  }

  private readonly http = inject(HttpClient);
  readonly cls = kycStatusClass;
  readonly o = { standalone: true };

  readonly view = signal<KycApplicationView | null>(null);
  readonly schema = signal<SchemaResponse | null>(null);
  readonly doc = signal<KycDoc | null>(null);
  readonly docKey = signal(0);
  readonly problems = signal<string[]>([]);
  readonly busy = signal(false);
  readonly loadError = signal('');
  readonly flash = signal<{ kind: 'ok' | 'bad'; text: string } | null>(null);
  sendToChecker = false;

  readonly context = computed<KycDoc>(() => ({
    orgType: this.view()?.orgType ?? '',
    country: this.schema()?.profile.country ?? 'NA',
    licenceType: this.schema()?.profile.licenceType ?? 'PSP',
  }));

  constructor() {
    effect(() => {
      const id = this.orgId();
      untracked(() => void this.load(id));
    });
  }

  async load(orgId = this.orgId()) {
    this.loadError.set('');
    this.flash.set(null);
    this.problems.set([]);
    this.view.set(null);
    try {
      const [v, schema] = await Promise.all([
        // /api/orgs/{id}/kyc is served by every backend build (the /api/kyc/orgs/{id} GET is newer)
        firstValueFrom(this.http.get<KycApplicationView>(`/api/orgs/${orgId}/kyc`)),
        firstValueFrom(this.http.get<SchemaResponse>(`/api/kyc/schema/${orgId}`).pipe(catchError(() => of(null)))),
      ]);
      if (orgId !== this.orgId()) return;
      this.schema.set(schema);
      this.show(v);
    } catch (e) {
      this.loadError.set(refusal(e, KYC_HINTS));
    }
  }

  /** Step to reopen on after a save re-creates the editor. */
  readonly reopenStep = signal<number | null>(null);

  private show(v: KycApplicationView, keepStep?: number) {
    if (keepStep !== undefined) this.reopenStep.set(keepStep);
    this.view.set(v);
    // the editable draft for editors; the submitted copy for read-only viewers
    const editableDraft = v.canEdit && v.id && v.status === 'DRAFT';
    const source = editableDraft ? (v.application ?? {}) : (v.submittedApplication ?? v.application);
    this.doc.set(source ? structuredClone(source) : null);
    this.docKey.update((k) => k + 1);
    // the editor fills its skeleton (nulls, defaults) on init; snapshot after that settles
    queueMicrotask(() => setTimeout(() => { this.snapshot = JSON.stringify(this.doc()); }));
  }

  start() {
    this.doc.set({});
    this.docKey.update((k) => k + 1);
    queueMicrotask(() => setTimeout(() => { this.snapshot = JSON.stringify(this.doc()); }));
  }

  /** True when any field of the document holds a value. */
  hasContent(d: unknown): boolean {
    if (d === null || d === undefined || d === '' || d === false) return false;
    if (Array.isArray(d)) return d.some((x) => this.hasContent(x));
    if (typeof d === 'object') return Object.values(d as object).some((x) => this.hasContent(x));
    return true;
  }

  pathText(v: KycApplicationView) { return v.treePath.map((p) => p.name).join(' › '); }

  private put(): Promise<KycApplicationView> {
    const v = this.view()!;
    const params: Record<string, string> = v.id ? { expectedVersion: String(v.version) } : {};
    return firstValueFrom(this.http.put<KycApplicationView>(`/api/kyc/orgs/${v.orgId}`, this.doc() ?? {}, { params }));
  }

  private listOf(detail: string) { return detail.split('; ').filter(Boolean); }

  /** quiet: no "Draft saved." note; then: the step to open once saved (Continue). */
  async save(quiet = false, then?: number): Promise<boolean> {
    this.busy.set(true);
    this.flash.set(null);
    const step = then ?? this.editor()?.stepIdx() ?? 0;
    try {
      const v = await this.put();
      this.problems.set([]);
      this.show(v, step);
      if (!quiet) this.flash.set({ kind: 'ok', text: 'Draft saved.' });
      this.changed.emit(v);
      return true;
    } catch (e) {
      this.onRefused(e, 'Not saved');
      return false;
    } finally {
      this.busy.set(false);
    }
  }

  async saveAndSubmit() {
    this.busy.set(true);
    this.flash.set(null);
    try {
      const saved = await this.put();
      this.show(saved, this.editor()?.stepIdx() ?? 0);
      this.changed.emit(saved);
      try {
        const v = await firstValueFrom(this.http.post<KycApplicationView>(
          `/api/kyc/orgs/${saved.orgId}/submit`, { sendToChecker: this.sendToChecker }));
        this.problems.set([]);
        this.show(v);
        this.flash.set({ kind: 'ok', text: v.status === 'ENTRY_CHECK'
          ? 'Submitted — another ADMIN of your organization must check it next.'
          : `Submitted for review (${v.status.replace('_', ' ').toLowerCase()}).` });
        this.changed.emit(v);
      } catch (e) {
        this.onRefused(e, 'Saved as a draft, but not ready to submit');
      }
    } catch (e) {
      this.onRefused(e, 'Not saved');
    } finally {
      this.busy.set(false);
    }
  }

  async withdraw() {
    const v = this.view();
    if (!v) return;
    this.busy.set(true);
    try {
      const w = await firstValueFrom(this.http.post<KycApplicationView>(`/api/kyc/orgs/${v.orgId}/withdraw`, {}));
      this.show(w);
      this.flash.set({ kind: 'ok', text: 'Application withdrawn.' });
      this.changed.emit(w);
    } catch (e) {
      this.flash.set({ kind: 'bad', text: refusal(e, KYC_HINTS) });
    } finally {
      this.busy.set(false);
    }
  }

  private onRefused(e: unknown, lead: string) {
    const p = readProblem(e);
    if (p.code === 'KYC_INVALID' || p.code === 'KYC_INCOMPLETE') {
      this.problems.set(this.listOf(p.detail));
      this.flash.set({ kind: 'bad', text: `${lead}: ${this.problems().length} problem(s) — see the highlighted fields.` });
    } else {
      this.flash.set({ kind: 'bad', text: refusal(e, KYC_HINTS) });
    }
  }
}
