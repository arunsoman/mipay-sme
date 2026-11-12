import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, OnInit, output, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { GrantableChild, GrantableView, KYC_HINTS, OrgDecisionResult, refusal, titleCase } from './kyc-api';
import { KycApplication } from './kyc-application';
import { Modal } from '../ui/modal';

interface Draft {
  name: string; registrationNumber: string; businessType: string; ownerName: string;
  phone: string; username: string; email: string;
  bankName: string; accountNumber: string; accountHolder: string;
  supplierType: string; ownerIdNumber: string; licenceRef: string;
}

const emptyDraft = (): Draft => ({
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
  imports: [FormsModule, KycApplication, Modal],
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
              [eyebrow]="created() ? 'Step 2 of 2' : 'Step 1 of 2'"
              [title]="created() ? 'KYC for ' + created()!.name : 'Onboard an organization'"
              [dirty]="created() ? (kycEditor()?.isDirty() ?? false) : dirty()" [discardText]="created() ? 'Close without saving? The organization exists; unsaved KYC changes will be lost (you can finish later from the KYC page).' : 'Discard this organization? Nothing has been created yet.'"
              (closed)="onClosed()">
          @if (flash(); as f) {<div class="flash {{ f.kind }}">{{ f.text }}</div>}
          @if (created(); as c) {
            <div class="hint">The organization stays <b>PENDING</b> until its KYC is approved; the approval sends its
              administrator the activation link. You can save a draft and finish later from the KYC page.</div>
            <mp-kyc-application #kycEditor [orgId]="c.orgId" (changed)="kycChanged.emit(c.orgId)" />
          } @else if (grantable(); as g) {
            <div class="types">
              @for (c of g.children; track c.type) {
                <button type="button" [class.sel]="childType() === c.type" (click)="pickType(c)">{{ title(c.type) }}</button>
              }
            </div>
            @if (child(); as c) {
              <form (ngSubmit)="submit()" class="form">
                <fieldset>
                  <legend>Organization</legend>
                  <div class="fields">
                    <label>{{ c.type === 'MERCHANT' || c.type === 'SUPPLIER' ? 'Business name' : 'Legal name' }} *
                      <input name="name" [(ngModel)]="d.name" required /></label>
                    <label>Registration number {{ needsReg(c.type) ? '*' : '' }}
                      <input name="reg" [(ngModel)]="d.registrationNumber" [required]="needsReg(c.type)" /></label>
                    @if (c.type === 'MERCHANT') {
                      <label>Business type
                        <select name="btype" [(ngModel)]="d.businessType">
                          <option value="">—</option>
                          @for (b of businessTypes; track b) {<option [value]="b">{{ b }}</option>}
                        </select></label>
                    }
                    @if (c.type === 'SUPPLIER') {
                      <label>Supplier type <input name="stype" [(ngModel)]="d.supplierType" placeholder="e.g. WHOLESALE" /></label>
                      <label>Owner ID number <input name="oid" [(ngModel)]="d.ownerIdNumber" /></label>
                    }
                    @if (c.type === 'LENDER') {
                      <label>Licence reference <input name="lic" [(ngModel)]="d.licenceRef" /></label>
                    }
                  </div>
                </fieldset>

                <fieldset>
                  <legend>Administrator (gets their MiPay username and activation link by email once KYC is approved)</legend>
                  <div class="fields">
                    <label>Full name {{ needsOwner(c.type) ? '*' : '' }}
                      <input name="owner" [(ngModel)]="d.ownerName" [required]="needsOwner(c.type)" /></label>
                    <label>Phone * <input name="phone" [(ngModel)]="d.phone" placeholder="08x…" required /></label>
                    <label>Email * <input name="email" type="email" [(ngModel)]="d.email" required /></label>
                    <label>Username <input name="user" value="" readonly tabindex="-1" class="ro"
                                           placeholder="created by MiPay · emailed to the address above" /></label>
                  </div>
                </fieldset>

                @if (c.type === 'SUPPLIER') {
                  <fieldset>
                    <legend>Payout bank account (approved separately)</legend>
                    <div class="fields">
                      <label>Bank * <input name="bank" [(ngModel)]="d.bankName" required /></label>
                      <label>Account number * <input name="acct" [(ngModel)]="d.accountNumber" required /></label>
                      <label>Account holder * <input name="holder" [(ngModel)]="d.accountHolder" required /></label>
                    </div>
                  </fieldset>
                }

                @if (g.roles?.length) {
                  <fieldset>
                    <legend>Which roles may {{ d.name || 'this organization' }} use?</legend>
                    <table class="grid rights">
                      <thead><tr><th>Role</th><th>Its people may hold it</th>
                        @if (c.allowedCeiling.length) {<th>It may pass it on</th>}</tr></thead>
                      <tbody>
                        @for (r of g.roles; track r.name) {
                          <tr>
                            <td>{{ r.name }}@if (r.description) {<div class="hint">{{ r.description }}</div>}</td>
                            <td><input type="checkbox" [checked]="r.baseline || roleUse().has(r.name)" [disabled]="r.baseline"
                                       (change)="toggleRoleUse(r.name)" /></td>
                            @if (c.allowedCeiling.length) {
                              <td><input type="checkbox" [checked]="roleHand().has(r.name)"
                                         [disabled]="!r.baseline && !roleUse().has(r.name)" (change)="toggleRoleHand(r.name)" /></td>
                            }
                          </tr>
                        }
                      </tbody>
                    </table>
                    <div class="hint">You can only give roles your own organization may pass on. The administrator and KYC roles
                      are always included. Organizations above it can narrow this later.</div>
                  </fieldset>
                }

                @if (c.allowedCeiling.length) {
                  <fieldset>
                    <legend>What may {{ d.name || 'this organization' }} onboard?</legend>
                    <table class="grid rights">
                      <thead><tr><th>Type</th><th>Directly</th><th>Anywhere below it</th></tr></thead>
                      <tbody>
                        @for (t of c.allowedCeiling; track t) {
                          <tr>
                            <td>{{ title(t) }}</td>
                            <td><input type="checkbox" [checked]="direct().has(t)" (change)="toggleDirect(t)" /></td>
                            <td><input type="checkbox" [checked]="ceiling().has(t)" (change)="toggleCeiling(t)" /></td>
                          </tr>
                        }
                      </tbody>
                    </table>
                    <div class="hint">"Anywhere below it" is the most its whole sub-network may ever onboard. Ticking
                      "Directly" also ticks it. Rights can be narrowed later, never widened.</div>
                  </fieldset>
                } @else {
                  <div class="hint">{{ title(c.type) }} organizations cannot onboard others.</div>
                }

                <div class="row">
                  <button class="primary" type="submit" [disabled]="busy()">Create and continue to KYC</button>
                  <button class="ghost" type="button" (click)="resetDraft()">Clear</button>
                </div>
              </form>
            }
          }
        @if (created()) {
          <div modal-footer><button type="button" (click)="finish()">Done</button></div>
        }
    </mp-modal>
  `,
  styles: [`
    .pad { padding: 16px; }
    .ro { opacity: .7; cursor: not-allowed; font-style: italic; }

    .ptitle { font-size: 14px; font-weight: 600; margin-bottom: 12px; }
    .step { font-size: var(--fs-sm); color: var(--a-teal); margin-bottom: 8px; }
    .types { display: flex; gap: 8px; flex-wrap: wrap; }
    .types button.sel { border-color: var(--a-teal); color: var(--a-teal); }
    .form { display: grid; gap: 12px; }
    fieldset { border: 1px solid var(--a-border); border-radius: 8px; padding: 12px; margin: 0; min-width: 0; }
    legend { color: var(--a-dim); font-size: var(--fs-sm); padding: 0 6px; }
    .fields { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 10px; }
    label { display: grid; gap: 5px; font-size: var(--fs-sm); color: var(--a-dim); }
    .rights { max-width: 520px; }
    .rights input { width: auto; }
    .row { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; margin-top: 8px; }
    .hint { color: var(--a-dim); font-size: var(--fs-xs); margin: 6px 0 10px; }
    .small { font-size: var(--fs-xs); }
    .err { color: var(--a-red); font-size: var(--fs-sm); margin: 6px 0; }
    .flash { font-size: var(--fs-sm); margin: 0 0 12px; padding: 8px 12px; border-radius: 8px; }
    .flash.ok { background: color-mix(in srgb, var(--a-green) 14%, transparent); color: var(--a-green); }
    .flash.bad { background: color-mix(in srgb, var(--a-red) 14%, transparent); color: var(--a-red); }
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
    await this.loadGrantable();
  }

  /** Something typed in step 1 that closing would lose. */
  dirty() {
    const d = this.d;
    return !!(d.name || d.phone || d.ownerName || d.registrationNumber || d.email || d.username);
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
        bankName: blank(d.bankName), accountNumber: blank(d.accountNumber), accountHolder: blank(d.accountHolder),
        supplierType: blank(d.supplierType), ownerIdNumber: blank(d.ownerIdNumber), licenceRef: blank(d.licenceRef),
        rightsDirect: [...this.direct()], rightsCeiling: [...this.ceiling()],
        ...this.roleCeilingBody(),
      }));
      this.created.set({ orgId: res.orgId, name: d.name.trim() });
      this.flash.set({ kind: 'ok', text: `${d.name.trim()} created (PENDING). Now complete its KYC.` });
      this.onboarded.emit(res);
    } catch (e) {
      this.flash.set({ kind: 'bad', text: refusal(e, KYC_HINTS) });
    } finally {
      this.busy.set(false);
    }
  }

  finish() {
    this.created.set(null);
    this.flash.set(null);
    this.resetDraft();
    this.formOpen.set(false);
  }
}
