import { MpSpinner } from '../../../src/app/design-system/atoms/mp-spinner';
import { MpAlert } from '../../../src/app/design-system/molecules/alert';
import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, input, OnInit, output, signal, viewChildren } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { GrantableChild, GrantableView, KYC_HINTS, OrgDecisionResult, readProblem, refusal, titleCase } from './kyc-api';
import { KycSchemaEditor, SchemaResponse } from './kyc-schema-editor';
import { KycProgress } from './kyc-progress';
import { Doc } from './kyc-conditions';
import { Modal } from '../../../src/app/design-system/organisms/modal';

interface Onboarded { orgId: string; requestId: string; status: string; adminUsername: string; }

/**
 * "Onboard an organization", the same in every portal: one five-step KYC wizard.
 * The org type is picked on step 1, then the KYC sections are filled in the browser;
 * nothing exists on the server until "Submit for review", which creates the org, its
 * administrator (UBO Person 1; login name = legal business name + MCC) and the
 * submitted KYC in one all-or-nothing call (POST /api/orgs/me/children/with-kyc).
 * The child types on offer are the caller's org's effective DIRECT rights; the new org
 * gets the default onboarding rights and role ceiling for its type.
 */
@Component({
  selector: 'mp-onboard-child',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpSpinner, MpAlert, FormsModule, KycSchemaEditor, KycProgress, Modal],
  template: `
    @if (compact()) {
      <button class="primary" type="button" (click)="openForm()" [disabled]="!grantable()?.children?.length"
              [title]="grantError() || (grantable()?.children?.length ? 'You may onboard: ' + allowedSummary(grantable()!) : 'Your organization has no onboarding rights')">＋ Onboard organization</button>
    } @else {
    <section class="panel pad">
      <div class="ptitle">Onboard an organization</div>
      @if (grantError(); as e) {<mp-alert kind="bad">{{ e }}</mp-alert>}
      @if (grantable(); as g) {
        @if (g.children.length === 0) {
          <div class="muted">Your organization has no onboarding rights. Ask your parent organization or MiPay to grant them.</div>
        } @else {
          <div class="row">
            <button class="primary" type="button" (click)="openForm()">＋ Onboard a new organization</button>
            <span class="muted small">you may onboard: {{ allowedSummary(g) }}</span>
          </div>
        }
      } @else if (!grantError()) {
        <mp-spinner label="Loading your onboarding rights…" />
      }
    </section>
    }

    <mp-modal [open]="open()" size="wide" eyebrow="New organization"
              [title]="done() ? legalName() : 'Onboard an organization'"
              [dirty]="!done() && dirty()" discardText="Discard this organization? Nothing has been created yet."
              (closed)="closeForm()">
      @if (done(); as r) {
        <div class="done">
          <div class="mark">✓</div>
          <h3>Sent for review</h3>
          <p>{{ legalName() }}’s KYC is with MiPay. Once it is approved, the administrator gets the login
            <b class="mono">{{ r.adminUsername }}</b> and an activation link by email.</p>
          <button type="button" class="primary" (click)="closeForm()">Close</button>
        </div>
      } @else if (grantable(); as g) {
        @if (wiz(); as w) {
          <mp-kyc-progress [labels]="w.stepLabels()" [current]="w.stepIdx()" [done]="w.stepsDone()" [bad]="w.stepsBad()"
                           [clickable]="true" (pick)="goto($event)" />
        }
        @if ((wiz()?.stepIdx() ?? 0) === 0) {
          <div class="typerow">
            <label class="fl"><span class="lb">Organization type</span>
              <select class="inp" [ngModel]="childType()" (ngModelChange)="pickTypeByName($event)">
                @for (c of g.children; track c.type) {<option [value]="c.type">{{ title(c.type) }}</option>}
              </select></label>
            @if (childType() === 'LENDER') {
              <label class="fl"><span class="lb">Lending licence reference</span>
                <input class="inp" [(ngModel)]="licenceRef" [class.bad]="tried() && !licenceRef.trim()" /></label>
            }
          </div>
        }
        @if (schemaError(); as e) {<mp-alert kind="bad">{{ e }}</mp-alert>}
        @if (schema(); as sch) {
          @for (k of [schemaKey()]; track k) {
            <mp-kyc-schema-editor #wiz [schema]="sch" [doc]="doc" [context]="context()" [problems]="problems()"
                                  [wizard]="true" [hideProgress]="true" [blurbs]="blurbs" />
          }
        } @else if (!schemaError()) {
          <mp-spinner label="Loading the KYC form…" />
        }

        @if (flash(); as f) {<mp-alert [kind]="f.kind">{{ f.text }}</mp-alert>}
        <div class="wfoot">
          @if ((wiz()?.stepIdx() ?? 0) === 0) {
            <button type="button" class="ghost" (click)="closeForm()">Cancel</button>
          } @else {
            <button type="button" class="ghost" (click)="back()" [disabled]="busy()">Back</button>
          }
          <span class="grow"></span>
          @if (wiz()?.isLastStep()) {
            <button type="button" class="primary" (click)="submit()" [disabled]="busy() || !schema()">
              {{ busy() ? 'Submitting…' : 'Submit for review' }}</button>
          } @else {
            <button type="button" class="primary" (click)="next()" [disabled]="!schema()">Continue</button>
          }
        </div>
      }
    </mp-modal>
  `,
  styles: [`
    .row { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; margin-top: 8px; }
    .small { font-size: var(--fs-xs); }
    .muted { color: var(--a-dim); }
    .mono { font-family: ui-monospace, "SF Mono", Menlo, Consolas, monospace; color: var(--a-text); }

    .typerow { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px 20px; }
    @media (max-width: 640px) { .typerow { grid-template-columns: 1fr; } }
    .fl { display: grid; gap: 7px; min-width: 0; }
    .lb { font-size: 13px; font-weight: 550; color: #d9d9d9; }
    .inp { box-sizing: border-box; height: 44px; width: 100%; font-size: 14.5px; border-radius: 10px;
           background: #0d0d0d; border-color: #2a2a2a; padding: 0 14px; }
    .inp.bad { border-color: var(--a-red); }
    .wfoot { position: sticky; bottom: -18px; z-index: 2; margin: 8px -22px -18px; padding: 14px 22px;
             background: color-mix(in srgb, #111 94%, transparent); backdrop-filter: blur(6px);
             border-top: 1px solid var(--a-border); display: flex; gap: 10px; align-items: center; }
    .wfoot .grow { flex: 1; }
    .wfoot button { min-height: 42px; padding: 0 22px; }
    .done { display: grid; justify-items: center; text-align: center; gap: 10px; padding: 48px 16px 40px; }
    .done .mark { width: 56px; height: 56px; border-radius: 50%; display: grid; place-items: center; font-size: 26px;
                  background: var(--a-green-bg, #173a29); color: var(--a-green); }
    .done h3 { margin: 6px 0 0; font-size: 22px; color: var(--a-text); }
    .done p { margin: 0 0 12px; max-width: 460px; color: var(--a-dim); font-size: 14px; line-height: 1.55; }
  `],
})
export class OnboardChild implements OnInit {
  /** Fires when an org was created (hosts refresh their request queues). */
  readonly onboarded = output<OrgDecisionResult>();
  /** Fires when the new org's KYC was submitted. */
  readonly kycChanged = output<string>();
  /** Just the "Onboard organization" button (a page header), no panel around it. */
  readonly compact = input(false);

  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);
  private loadingGrantable = false;

  readonly grantable = signal<GrantableView | null>(null);
  readonly grantError = signal('');
  readonly open = signal(false);
  readonly childType = signal<string | null>(null);
  readonly child = computed<GrantableChild | null>(() =>
    this.grantable()?.children.find((c) => c.type === this.childType()) ?? null);
  readonly direct = signal<Set<string>>(new Set());
  readonly ceiling = signal<Set<string>>(new Set());
  readonly roleUse = signal<Set<string>>(new Set());
  readonly roleHand = signal<Set<string>>(new Set());

  readonly schema = signal<SchemaResponse | null>(null);
  readonly schemaError = signal('');
  readonly schemaKey = signal(0);
  /** The form on screen (a type change can rebuild it; never talk to a discarded one). */
  private readonly wizards = viewChildren<KycSchemaEditor>('wiz');
  readonly wiz = computed(() => this.wizards().at(-1));
  /** The KYC document being filled; sent whole on submit. */
  doc: Doc = {};
  licenceRef = '';
  readonly problems = signal<string[]>([]);
  readonly tried = signal(false);
  readonly busy = signal(false);
  readonly flash = signal<{ kind: 'ok' | 'bad'; text: string } | null>(null);
  readonly done = signal<Onboarded | null>(null);
  readonly blurbs = {
    ubos: 'Everyone who owns or controls the business, and its key managers. Person 1 becomes the MiPay administrator: '
      + 'their phone and email are the login contact.',
  };

  readonly title = titleCase;
  readonly context = computed<Doc>(() => ({ orgType: this.childType() ?? '', country: 'NA', licenceType: 'PSP' }));
  allowedSummary(g: GrantableView) { return g.children.map((c) => this.title(c.type)).join(', '); }

  legalName() {
    const b = this.doc['businessProfile'] as Doc | undefined;
    return (b?.['legalBusinessName'] as string) || 'The organization';
  }

  async ngOnInit() {
    const timer = window.setInterval(() => { if (!document.hidden && !this.open()) void this.loadGrantable(); }, 10000);
    this.destroyRef.onDestroy(() => window.clearInterval(timer));
    await this.loadGrantable();
  }

  /** Anything typed that closing would lose (the prefilled countries and No answers do not count). */
  dirty() {
    const has = (v: unknown): boolean => v === null || v === undefined || v === '' || v === false ? false
      : Array.isArray(v) ? v.some(has) : typeof v === 'object' ? Object.entries(v as object)
        .some(([k, x]) => k !== 'country' && k !== 'nationality' && has(x)) : true;
    return has(this.doc) || !!this.licenceRef.trim();
  }

  async loadGrantable() {
    if (this.loadingGrantable) return;
    this.loadingGrantable = true;
    this.grantError.set('');
    try {
      this.grantable.set(await firstValueFrom(this.http.get<GrantableView>('/api/orgs/me/grantable')));
    } catch (e) {
      this.grantError.set(refusal(e, KYC_HINTS));
    } finally {
      this.loadingGrantable = false;
    }
  }

  openForm() {
    this.doc = {};
    this.licenceRef = '';
    this.problems.set([]);
    this.flash.set(null);
    this.tried.set(false);
    this.done.set(null);
    this.open.set(true);
    const g = this.grantable();
    if (g?.children.length) this.pickType(g.children[0]);
  }

  closeForm() {
    if (this.done()) this.kycChanged.emit(this.done()!.orgId);
    this.open.set(false);
    this.done.set(null);
  }

  pickTypeByName(t: string) {
    const c = this.grantable()?.children.find((x) => x.type === t);
    if (c) this.pickType(c);
  }

  /** Default rights and role ceiling for the type, then the KYC form that type fills in. */
  pickType(c: GrantableChild) {
    this.childType.set(c.type);
    const allowed = new Set(c.allowedCeiling);
    this.direct.set(new Set(c.defaultDirect.filter((t) => allowed.has(t))));
    this.ceiling.set(new Set(c.allowedCeiling));
    const roles = (this.grantable()?.roles ?? []).map((r) => r.name);
    this.roleUse.set(new Set(roles));
    this.roleHand.set(new Set(c.allowedCeiling.length ? roles : []));
    void this.loadSchema(c.type);
  }

  private async loadSchema(type: string) {
    this.schemaError.set('');
    try {
      const s = await firstValueFrom(this.http.get<SchemaResponse>(`/api/kyc/schema/types/${type}`));
      if (type !== this.childType()) return;
      const before = this.schema();
      this.schema.set(s);
      // every type may share one form: rebuild the editor only when the form itself differs
      if (!before || before.schema.code !== s.schema.code || before.schema.version !== s.schema.version
          || before.profile.code.split('-').slice(0, 3).join('-') !== s.profile.code.split('-').slice(0, 3).join('-')) {
        this.schemaKey.update((k) => k + 1);   // what was typed stays in doc
      }
    } catch (e) {
      this.schemaError.set(refusal(e, KYC_HINTS));
    }
  }

  goto(i: number) {
    const w = this.wiz();
    if (!w) return;
    // forward only through checked steps; back freely
    if (i > w.stepIdx() && !this.checkStep()) return;
    this.flash.set(null);
    w.goto(i);
  }

  back() {
    this.flash.set(null);
    this.problems.set([]);
    const w = this.wiz();
    if (w) w.goto(w.stepIdx() - 1);
  }

  next() {
    const w = this.wiz();
    if (w && this.checkStep()) w.goto(w.stepIdx() + 1);
  }

  /** Highlights the current step's empty required fields; true when there are none. */
  private checkStep(): boolean {
    const w = this.wiz();
    if (!w) return false;
    this.tried.set(true);
    const missing = w.missingOn(w.stepIdx());
    const licence = w.stepIdx() === 0 && this.childType() === 'LENDER' && !this.licenceRef.trim();
    this.problems.set(missing);
    if (missing.length || licence) {
      this.flash.set({ kind: 'bad', text: 'Fill in the highlighted fields to continue.' });
      return false;
    }
    this.flash.set(null);
    this.tried.set(false);
    return true;
  }

  private roleCeilingBody() {
    const g = this.grantable();
    if (!g?.roles?.length) return { roleCeiling: null, roleDelegable: null };
    const base = new Set(g.baselineRoles ?? []);
    const use = new Set([...this.roleUse(), ...base]);
    return { roleCeiling: [...use], roleDelegable: [...this.roleHand()].filter((r) => use.has(r)) };
  }

  /** One all-or-nothing call: the org, its administrator (UBO Person 1) and the submitted KYC. */
  async submit() {
    const c = this.child();
    if (!c || !this.checkStep()) return;
    this.busy.set(true);
    this.flash.set(null);
    try {
      const r = await firstValueFrom(this.http.post<Onboarded>('/api/orgs/me/children/with-kyc', {
        type: c.type, kyc: this.doc, licenceRef: this.licenceRef.trim() || null,
        rightsDirect: [...this.direct()], rightsCeiling: [...this.ceiling()], ...this.roleCeilingBody(),
      }));
      this.problems.set([]);
      this.done.set(r);
      this.onboarded.emit({ requestId: r.requestId, orgId: r.orgId, status: r.status, message: '' });
      void this.loadGrantable();
    } catch (e) {
      const p = readProblem(e);
      if (p.code === 'KYC_INVALID' || p.code === 'KYC_INCOMPLETE') {
        const list = p.detail.split('; ').filter(Boolean);
        this.problems.set(list);   // the editor jumps to the first step with a problem
        this.flash.set({ kind: 'bad', text: `Not submitted — nothing was created. ${list.length} thing(s) to fix, highlighted in the form.` });
      } else {
        this.flash.set({ kind: 'bad', text: `Not submitted — nothing was created. ${refusal(e, KYC_HINTS)}` });
      }
    } finally {
      this.busy.set(false);
    }
  }
}
