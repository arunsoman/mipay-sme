import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, OnInit, output, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { GrantableChild, GrantableView, KYC_HINTS, OrgDecisionResult, refusal, titleCase } from './kyc-api';
import { KycApplication } from './kyc-application';
import { Modal } from '../ui/modal';
import { KYC_STEP_LABELS, KycProgress } from './kyc-progress';
import { KycApplicationView } from './kyc-api';

interface Draft {
  name: string; registrationNumber: string; businessType: string; ownerName: string;
  tradingName: string; entityType: string; incorporationDate: string; taxIdentificationNumber: string;
  phone: string; username: string; email: string;
  bankName: string; accountNumber: string; accountHolder: string;
  supplierType: string; ownerIdNumber: string; licenceRef: string;
}

const emptyDraft = (): Draft => ({
  tradingName: '', entityType: '', incorporationDate: '', taxIdentificationNumber: '',
  name: '', registrationNumber: '', businessType: '', ownerName: '', phone: '', username: '', email: '',
  bankName: '', accountNumber: '', accountHolder: '', supplierType: '', ownerIdNumber: '', licenceRef: '',
});

/**
 * "Onboard an organization", the same in every portal. The child types on
 * offer are exactly the caller's org's effective DIRECT rights
 * (GET /api/orgs/me/grantable); the onboarder chooses what the new org may
 * onboard in turn (DIRECT ⊆ CEILING ⊆ what this type allows). Step 2 opens
 * the new org's KYC application (created as an empty draft by the server)
 * so the onboarder fills it in and submits it in the same sitting. The KYC
 * workflow's final approval is what makes the org live (design D8).
 */
@Component({
  selector: 'mp-onboard-child',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, KycApplication, Modal, KycProgress],
  template: `
    <section class="panel pad">
      <div class="ptitle">Onboard an organization</div>
      @if (grantError(); as e) {<div class="err">{{ e }}</div>}
      @if (!open() && flash(); as f) {<div class="flash {{ f.kind }}">{{ f.text }}</div>}
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
        <div class="muted">Loading your onboarding rights…</div>
      }
    </section>

    <mp-modal [open]="open()" size="wide"
              [eyebrow]="created() ? 'New organization · KYC' : 'New organization'"
              [title]="created() ? created()!.name : 'Onboard an organization'"
              [dirty]="created() ? (!submitted() && (kycEditor()?.isDirty() ?? false)) : dirty()"
              [discardText]="created() ? 'Close without saving? The organization exists; unsaved changes on this step will be lost (you can finish later from the KYC page).' : 'Discard this organization? Nothing has been created yet.'"
              (closed)="onClosed()">
      @if (submitted(); as sub) {
        <div class="done">
          <div class="mark">✓</div>
          <h3>Sent for review</h3>
          <p>{{ sub }}’s KYC is with MiPay. Its administrator gets their username and activation link by email once it is approved.</p>
          <button type="button" class="primary" (click)="finish()">Close</button>
        </div>
      } @else if (created(); as c) {
        @if (flash(); as fl) {<div class="flash {{ fl.kind }}">{{ fl.text }}</div>}
        <mp-kyc-application #kycEditor [orgId]="c.orgId" [wizard]="true" [startStep]="kycStart()"
                            (changed)="onKycChanged($event)" />
      } @else if (grantable(); as g) {
        <form #f="ngForm" (ngSubmit)="f.valid && submit()" class="form" [class.tried]="f.submitted" novalidate>
          <mp-kyc-progress [labels]="stepLabels" [current]="0" />

          <label class="typepick">
            <span class="lb">Organization type</span>
            <select class="inp" name="otype" [ngModel]="childType()" (ngModelChange)="pickTypeByName($event)" required>
              @for (c of g.children; track c.type) {<option [value]="c.type">{{ title(c.type) }}</option>}
            </select>
          </label>

          @if (child(); as c) {
            <section class="sec">
              <div class="shead">
                <h3>Business Profile &amp; General Information</h3>
                <p>The legal identity of the business, as registered.</p>
              </div>
              <div class="grid">
                <label class="fl"><span class="lb">Legal Business Name</span>
                  <input class="inp" name="legalName" [(ngModel)]="d.name" required minlength="2" maxlength="200" />
                  <span class="help">Exact registered name as listed in legal government tax registries.</span></label>
                <label class="fl"><span class="lb">Trading Name</span>
                  <input class="inp" name="trading" [(ngModel)]="d.tradingName" required maxlength="200" placeholder="Doing Business As (DBA)" />
                  <span class="help">The public-facing name shown to customers at checkout and on bank statements.</span></label>
                <label class="fl"><span class="lb">Entity Type / Legal Structure</span>
                  <select class="inp" name="entity" [(ngModel)]="d.entityType" required>
                    <option value="" disabled>Select…</option>
                    @for (e of entityTypes(); track e.value) {<option [value]="e.value">{{ e.label }}</option>}
                  </select></label>
                <label class="fl"><span class="lb">Business Registration / Corporate ID Number</span>
                  <input class="inp" name="reg" [(ngModel)]="d.registrationNumber" required minlength="3" maxlength="32" placeholder="e.g. CC/2019/0421" />
                  <span class="help">Official registration number (e.g. CIN, Company Registration No.).</span></label>
                <label class="fl"><span class="lb">Date of Incorporation / Establishment</span>
                  <input class="inp" name="incorp" type="date" [(ngModel)]="d.incorporationDate" [max]="today" required /></label>
                <label class="fl"><span class="lb">Tax Identification Number</span>
                  <input class="inp" name="tin" [(ngModel)]="d.taxIdentificationNumber" required minlength="5" maxlength="32"
                         autocomplete="off" placeholder="TIN / EIN / GSTIN / VAT ID" /></label>
                @if (c.type === 'LENDER') {
                  <label class="fl"><span class="lb">Lending licence reference</span>
                    <input class="inp" name="lic" [(ngModel)]="d.licenceRef" required /></label>
                }
              </div>
            </section>

            <section class="sec admin">
              <div class="shead">
                <h4>Administrator login</h4>
                <p>We email their MiPay username and activation link once the KYC is approved.</p>
              </div>
              <div class="grid three">
                <label class="fl"><span class="lb">Full name</span>
                  <input class="inp" name="owner" [(ngModel)]="d.ownerName" required autocomplete="off" /></label>
                <label class="fl"><span class="lb">Mobile number</span>
                  <input class="inp" name="phone" type="tel" [(ngModel)]="d.phone" required placeholder="08x xxx xxxx" /></label>
                <label class="fl"><span class="lb">Email</span>
                  <input class="inp" name="email" type="email" [(ngModel)]="d.email" required email autocomplete="off" /></label>
              </div>
            </section>

            @if (flash(); as fl) {<div class="flash {{ fl.kind }}">{{ fl.text }}</div>}
            @if (f.submitted && f.invalid) {<div class="flash bad">Fill in the highlighted fields to continue.</div>}

            <div class="wfoot">
              <button type="button" class="ghost" (click)="closeForm()">Cancel</button>
              <span class="grow"></span>
              <button class="primary" type="submit" [disabled]="busy()">{{ busy() ? 'Creating…' : 'Continue' }}</button>
            </div>
          }
        </form>
      }
    </mp-modal>
  `,
  styles: [`
    .pad { padding: 16px; }
    .ptitle { font-size: 14px; font-weight: 600; margin-bottom: 12px; }
    .row { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; margin-top: 8px; }
    .small { font-size: var(--fs-xs); }
    .muted { color: var(--a-dim); }
    .err { color: var(--a-red); font-size: var(--fs-sm); margin: 6px 0; }

    .form { display: grid; gap: 28px; }
    .typepick { display: grid; gap: 7px; max-width: 340px; }
    .sec { display: grid; gap: 18px; }
    .shead h3 { margin: 0; font-size: 19px; font-weight: 650; letter-spacing: -0.01em; color: var(--a-text); }
    .shead h4 { margin: 0; font-size: 15px; font-weight: 650; color: var(--a-text); }
    .shead p { margin: 4px 0 0; color: var(--a-dim); font-size: 13.5px; }
    .admin { padding: 18px; border-radius: 14px; background: #0f0f0f; border: 1px solid #222; }
    .grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px 20px; }
    .grid.three { grid-template-columns: repeat(3, minmax(0, 1fr)); }
    @media (max-width: 760px) { .grid.three { grid-template-columns: 1fr 1fr; } }
    @media (max-width: 640px) { .grid, .grid.three { grid-template-columns: 1fr; } }
    .fl { display: grid; gap: 7px; min-width: 0; align-content: start; }
    .lb { font-size: 13px; font-weight: 550; color: #d9d9d9; }
    .inp { box-sizing: border-box; height: 44px; width: 100%; font-size: 14.5px; border-radius: 10px;
           background: #0d0d0d; border-color: #2a2a2a; padding: 0 14px; }
    .inp::placeholder { color: #5f5f5f; }
    .tried .inp.ng-invalid { border-color: var(--a-red); }
    .help { display: none; font-size: 12px; line-height: 1.45; color: var(--a-dim); }
    .fl:focus-within .help { display: block; }
    .wfoot { position: sticky; bottom: -18px; z-index: 2; margin: -8px -22px -18px; padding: 14px 22px;
             background: color-mix(in srgb, #111 94%, transparent); backdrop-filter: blur(6px);
             border-top: 1px solid var(--a-border); display: flex; gap: 10px; align-items: center; }
    .wfoot .grow { flex: 1; }
    .wfoot button { min-height: 42px; padding: 0 22px; }
    .flash { font-size: 13px; padding: 10px 12px; border-radius: 10px; }
    .flash.ok { background: color-mix(in srgb, var(--a-green) 12%, transparent); color: var(--a-green); }
    .flash.bad { background: color-mix(in srgb, var(--a-red) 10%, transparent); color: var(--a-red); }
    .done { display: grid; justify-items: center; text-align: center; gap: 10px; padding: 48px 16px 40px; }
    .done .mark { width: 56px; height: 56px; border-radius: 50%; display: grid; place-items: center; font-size: 26px;
                  background: var(--a-green-bg, #173a29); color: var(--a-green); }
    .done h3 { margin: 6px 0 0; font-size: 22px; color: var(--a-text); }
    .done p { margin: 0 0 12px; max-width: 440px; color: var(--a-dim); font-size: 14px; line-height: 1.5; }
  `],
})
export class OnboardChild implements OnInit {
  /** Fires when an org was created (hosts refresh their request queues). */
  readonly onboarded = output<OrgDecisionResult>();
  /** Fires when the new org's KYC was saved or submitted. */
  readonly kycChanged = output<string>();

  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);
  private loadingGrantable = false;
  readonly businessTypes = ['RETAIL', 'WHOLESALE', 'SERVICES', 'FOOD', 'TRANSPORT', 'OTHER'];

  readonly grantable = signal<GrantableView | null>(null);
  readonly grantError = signal('');
  readonly formOpen = signal(false);
  readonly childType = signal<string | null>(null);
  readonly child = computed<GrantableChild | null>(() =>
    this.grantable()?.children.find((c) => c.type === this.childType()) ?? null);
  readonly direct = signal<Set<string>>(new Set());
  readonly ceiling = signal<Set<string>>(new Set());
  /** Role ceiling for the new org (extended-rbac): roles it may use / pass on. */
  readonly roleUse = signal<Set<string>>(new Set());
  readonly roleHand = signal<Set<string>>(new Set());
  readonly created = signal<{ orgId: string; name: string } | null>(null);
  readonly kycEditor = viewChild<KycApplication>('kycEditor');
  readonly open = computed(() => this.formOpen() || this.created() !== null);
  readonly busy = signal(false);
  readonly flash = signal<{ kind: 'ok' | 'bad'; text: string } | null>(null);
  d: Draft = emptyDraft();

  readonly title = titleCase;
  readonly stepLabels = KYC_STEP_LABELS;
  /** The org name once its KYC was submitted: the modal shows the finish screen. */
  readonly submitted = signal<string | null>(null);
  /** Section 1 was saved with the org: the KYC opens on section 2 (or back on 1 when that save failed). */
  readonly kycStart = signal(1);

  onKycChanged(v: KycApplicationView) {
    this.kycChanged.emit(v.orgId);
    if (v.status !== 'DRAFT') this.submitted.set(this.created()?.name ?? v.orgName);
  }
  readonly entityTypes = signal<{ value: string; label: string }[]>([]);
  readonly today = new Date().toISOString().slice(0, 10);
  pickTypeByName(t: string) {
    const c = this.grantable()?.children.find((x) => x.type === t);
    if (c) this.pickType(c);
  }
  needsReg(t: string) { return t === 'MANUFACTURER' || t === 'DISTRIBUTOR'; }
  needsOwner(t: string) { return t === 'MERCHANT' || t === 'SUPPLIER'; }
  allowedSummary(g: GrantableView) { return g.children.map((c) => this.title(c.type)).join(', '); }

  async ngOnInit() {
    const timer = window.setInterval(() => {
      if (!document.hidden) void this.loadGrantable();
    }, 10000);
    const refresh = () => { if (!document.hidden) void this.loadGrantable(); };
    document.addEventListener('visibilitychange', refresh);
    this.destroyRef.onDestroy(() => {
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', refresh);
    });
    firstValueFrom(this.http.get<{ items: { value: string; label: string }[] }>('/api/kyc/lists/KYC_ENTITY_TYPE'))
      .then((r) => this.entityTypes.set(r.items ?? [])).catch(() => this.entityTypes.set([]));
    await this.loadGrantable();
  }

  /** Something typed in step 1 that closing would lose. */
  dirty() {
    const d = this.d;
    return !!(d.name || d.phone || d.ownerName || d.registrationNumber || d.email || d.username
      || d.tradingName || d.taxIdentificationNumber);
  }

  onClosed() {
    if (this.created()) this.finish(); else this.closeForm();
  }

  async loadGrantable() {
    if (this.loadingGrantable) return;
    this.loadingGrantable = true;
    this.grantError.set('');
    try {
      const g = await firstValueFrom(this.http.get<GrantableView>('/api/orgs/me/grantable'));
      this.grantable.set(g);
      const offered = new Set((g.roles ?? []).map((r) => r.name));
      this.roleUse.update(cur => new Set([...cur].filter(r => offered.has(r))));
      this.roleHand.update(cur => new Set([...cur].filter(r => offered.has(r))));
      const keep = g.children.find((c) => c.type === this.childType());
      if (keep) {
        const allowed = new Set(keep.allowedCeiling);
        this.ceiling.update(current => new Set([...current].filter(t => allowed.has(t))));
        this.direct.update(current => new Set([...current].filter(t => allowed.has(t))));
      } else if (this.childType()) {
        this.childType.set(null);
        this.direct.set(new Set());
        this.ceiling.set(new Set());
      }
    } catch (e) {
      this.grantError.set(refusal(e, KYC_HINTS));
    } finally {
      this.loadingGrantable = false;
    }
  }

  openForm() {
    this.formOpen.set(true);
    this.flash.set(null);
    const g = this.grantable();
    if (g?.children.length && !this.child()) this.pickType(g.children[0]);
  }

  closeForm() {
    this.formOpen.set(false);
    this.childType.set(null);
  }

  pickType(c: GrantableChild) {
    this.childType.set(c.type);
    const allowed = new Set(c.allowedCeiling);
    this.direct.set(new Set(c.defaultDirect.filter((t) => allowed.has(t))));
    this.ceiling.set(new Set(c.allowedCeiling));
    // default: everything the onboarder may pass on; pass-on only for types that onboard further
    const roles = (this.grantable()?.roles ?? []).map((r) => r.name);
    this.roleUse.set(new Set(roles));
    this.roleHand.set(new Set(c.allowedCeiling.length ? roles : []));
  }

  toggleRoleUse(r: string) {
    const s = new Set(this.roleUse());
    if (s.has(r)) {
      s.delete(r);
      const h = new Set(this.roleHand());
      h.delete(r);
      this.roleHand.set(h);
    } else s.add(r);
    this.roleUse.set(s);
  }

  toggleRoleHand(r: string) {
    const h = new Set(this.roleHand());
    if (h.has(r)) h.delete(r); else h.add(r);
    this.roleHand.set(h);
  }

  /** null = no role ceiling offered (older backend): the new org inherits. */
  private roleCeilingBody() {
    const g = this.grantable();
    if (!g?.roles?.length) return { roleCeiling: null, roleDelegable: null };
    const base = new Set(g.baselineRoles ?? []);
    const use = new Set([...this.roleUse(), ...base]);
    return { roleCeiling: [...use], roleDelegable: [...this.roleHand()].filter((r) => use.has(r)) };
  }

  toggleDirect(t: string) {
    const dir = new Set(this.direct());
    if (dir.has(t)) dir.delete(t);
    else { dir.add(t); this.ceiling.set(new Set(this.ceiling()).add(t)); }
    this.direct.set(dir);
  }

  toggleCeiling(t: string) {
    const ceil = new Set(this.ceiling());
    if (ceil.has(t)) {
      ceil.delete(t);
      const dir = new Set(this.direct());
      dir.delete(t);
      this.direct.set(dir);
    } else ceil.add(t);
    this.ceiling.set(ceil);
  }

  resetDraft() {
    this.d = emptyDraft();
    const c = this.child();
    if (c) this.pickType(c);
  }

  async submit() {
    const c = this.child();
    if (!c) return;
    this.busy.set(true);
    this.flash.set(null);
    const blank = (s: string) => (s.trim() ? s.trim() : null);
    const d = this.d;
    try {
      const res = await firstValueFrom(this.http.post<OrgDecisionResult>('/api/orgs/me/children', {
        type: c.type,
        name: blank(d.name), registrationNumber: blank(d.registrationNumber),
        businessType: blank(d.businessType), ownerName: blank(d.ownerName),
        phone: blank(d.phone), username: null, email: blank(d.email),   // username generated by the backend, emailed
        bankName: null, accountNumber: null, accountHolder: null,   // the KYC bank section captures these
        supplierType: null, ownerIdNumber: null, licenceRef: blank(d.licenceRef),
        rightsDirect: [...this.direct()], rightsCeiling: [...this.ceiling()],
        ...this.roleCeilingBody(),
      }));
      // section 1 goes straight into the new org's KYC draft; the editor continues from there
      let saved = true;
      this.kycStart.set(1);
      try {
        await firstValueFrom(this.http.put(`/api/kyc/orgs/${res.orgId}`, { businessProfile: {
          legalBusinessName: d.name.trim(), tradingName: d.tradingName.trim(), entityType: d.entityType,
          registrationNumber: d.registrationNumber.trim(), incorporationDate: d.incorporationDate,
          taxIdentificationNumber: d.taxIdentificationNumber.trim(),
        } }));
      } catch (e) {
        saved = false;
        this.kycStart.set(0);
        this.flash.set({ kind: 'bad', text: `${d.name.trim()} was created, but its Business Profile was not saved: ${refusal(e, KYC_HINTS)}. Correct it below.` });
      }
      this.created.set({ orgId: res.orgId, name: d.name.trim() });
      if (saved) this.flash.set(null);
      this.onboarded.emit(res);
    } catch (e) {
      this.flash.set({ kind: 'bad', text: refusal(e, KYC_HINTS) });
    } finally {
      this.busy.set(false);
    }
  }

  finish() {
    this.submitted.set(null);
    this.created.set(null);
    this.flash.set(null);
    this.resetDraft();
    this.formOpen.set(false);
  }
}
