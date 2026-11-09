import { ChangeDetectionStrategy, Component, inject, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MpCard } from '../../design-system/atoms/mp-card';
import { PageHeader } from '../../design-system/organisms/page-header';
import { MpEntityForm, MpFieldSchema } from '../../design-system/organisms/mp-entity-form';
import { OnboardingService } from '../../core/services';

/**
 * Public self-onboarding screens (UAT SB p.8, M02/S02 - reference implementation
 * merge). Anonymous; the backend creates the merchant/supplier in PENDING and
 * TPTS ops approve in the ops console (approver ≠ requester, KYC, sanctions).
 */
@Component({
  selector: 'onboard-merchant',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpCard, PageHeader, MpEntityForm, RouterLink],
  template: `
    <div class="wrap">
      <page-header title="Merchant self-registration (M02)"
        subtitle="Creates a PENDING merchant — TPTS ops completes KYC + sanctions before a device can sign in" />
      <mp-card>
        @if (!done(); as _v) {
          <mp-entity-form #f [schema]="schema" submitLabel="Register merchant" (submitted)="submit($event)" />
        } @else {
          <div class="ok">{{ done() }}</div>
          <div class="dim mt">Return to <a routerLink="/login">login</a>. Ops will approve in the TPTS console; the dev OTP flow unlocks on approval.</div>
        }
      </mp-card>
      <div class="alt dim">Registering a supplier? <a routerLink="/onboarding/supplier">Supplier registration →</a></div>
    </div>
  `,
  styles: [`
    .wrap { max-width: 560px; margin: 4vh auto 0; padding: var(--sp-4); }
    .ok { font-size: var(--fs-md); font-weight: 600; font-family: ui-monospace, monospace; }
    .mt { margin-top: var(--sp-3); }
    .alt { margin-top: var(--sp-4); }
  `],
})
export class MerchantOnboard {
  private readonly api = inject(OnboardingService);
  readonly schema: MpFieldSchema[] = [
    { key: 'phone', label: 'Mobile number (e.g. 0851234567)', kind: 'text', required: true },
    { key: 'businessName', label: 'Business name', kind: 'text', required: true },
    { key: 'ownerName', label: 'Owner name', kind: 'text', required: true },
    { key: 'ownerIdNumber', label: 'Owner ID number', kind: 'text', required: true },
    { key: 'businessType', label: 'Business type (e.g. spaza-shop, salon)', kind: 'text' },
    { key: 'registrationNumber', label: 'Business registration number (optional)', kind: 'text' },
  ];

  private readonly f = viewChild('f');
  private readonly doneSig = signal('');
  done() { return this.doneSig(); }

  async submit(v: Record<string, string>) {
    const form = this.f() as unknown as MpEntityForm;
    try {
      const res = await this.api.registerMerchant({
        phone: v['phone'], businessName: v['businessName'], ownerName: v['ownerName'],
        ownerIdNumber: v['ownerIdNumber'], businessType: v['businessType'] || undefined,
        registrationNumber: v['registrationNumber'] || undefined,
      });
      this.doneSig.set(res.message);
      form.done(res.message);
    } catch (e) { form.failWire(e); }
  }
}

@Component({
  selector: 'onboard-supplier',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpCard, PageHeader, MpEntityForm, RouterLink],
  template: `
    <div class="wrap">
      <page-header title="Supplier self-registration (S02)"
        subtitle="Creates a PENDING supplier with a bank receiving account awaiting ops + approver≠requester bank approval" />
      <mp-card>
        @if (!done(); as _v) {
          <mp-entity-form #f [schema]="schema" submitLabel="Register supplier" (submitted)="submit($event)" />
        } @else {
          <div class="ok">{{ done() }}</div>
          <div class="dim mt">Return to <a routerLink="/login">login</a>. Ops will approve; password set after approval.</div>
        }
      </mp-card>
      <div class="alt dim">Registering a merchant? <a routerLink="/onboarding/merchant">Merchant registration →</a></div>
    </div>
  `,
  styles: [`
    .wrap { max-width: 560px; margin: 4vh auto 0; padding: var(--sp-4); }
    .ok { font-size: var(--fs-md); font-weight: 600; font-family: ui-monospace, monospace; }
    .mt { margin-top: var(--sp-3); }
    .alt { margin-top: var(--sp-4); }
  `],
})
export class SupplierOnboard {
  private readonly api = inject(OnboardingService);
  readonly schema: MpFieldSchema[] = [
    { key: 'phone', label: 'Mobile number (e.g. 0857654321)', kind: 'text', required: true },
    { key: 'businessName', label: 'Business name', kind: 'text', required: true },
    { key: 'ownerName', label: 'Owner name', kind: 'text', required: true },
    { key: 'registrationNumber', label: 'Registration number (optional)', kind: 'text' },
    { key: 'bankName', label: 'Bank name (e.g. FNB)', kind: 'text', required: true },
    { key: 'accountNumber', label: 'Account number', kind: 'text', required: true },
    { key: 'accountHolder', label: 'Account holder', kind: 'text', required: true },
  ];

  private readonly f = viewChild('f');
  private readonly doneSig = signal('');
  done() { return this.doneSig(); }

  async submit(v: Record<string, string>) {
    const form = this.f() as unknown as MpEntityForm;
    try {
      const res = await this.api.registerSupplier({
        phone: v['phone'], businessName: v['businessName'], ownerName: v['ownerName'],
        registrationNumber: v['registrationNumber'] || undefined,
        bankName: v['bankName'], accountNumber: v['accountNumber'], accountHolder: v['accountHolder'],
      });
      this.doneSig.set(res.message);
      form.done(res.message);
    } catch (e) { form.failWire(e); }
  }
}
