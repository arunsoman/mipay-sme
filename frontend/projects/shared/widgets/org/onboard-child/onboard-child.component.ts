import { ChangeDetectionStrategy, Component, computed, effect, signal, untracked, viewChild } from '@angular/core';
import { MiPayWidget } from '../../core/mipay-widget';
import { words } from '../../core/text';
import { MpEntityForm, MpFieldSchema } from '../../../../../src/app/design-system/organisms/mp-entity-form';
import { MpAlert } from '../../../../../src/app/design-system/molecules/alert';
import { EmptyState } from '../../../../../src/app/design-system/molecules/empty-state';
import { MpSpinner } from '../../../../../src/app/design-system/atoms/mp-spinner';
import { MpPerm } from '../../../web-core/perm-directive';
import { KYC_HINTS, refusal } from '../../../kyc/kyc-api';
import type { OrgOnboardChildConfig } from './onboard-child.widget';
import type { OrgChildrenSubmit, OrgChildrenSubmitRequest, OrgGrantableGet } from './onboard-child.contract';

const ONBOARD_HINTS: Record<string, string> = {
  ...KYC_HINTS,
  DUPLICATE_PHONE: 'that mobile number is already registered',
  DUPLICATE_USERNAME: 'choose another login name',
  TYPE_NOT_ALLOWED: 'your organization may not onboard this type',
  RIGHTS_EXCEED_CEILING: 'you can only pass on rights your organization may hand down',
};

/** The types this org may onboard (grantable), then one form: the org and its first administrator. */
@Component({
  selector: 'mpw-org-onboard-child',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpEntityForm, MpAlert, EmptyState, MpSpinner, MpPerm],
  template: `
    @if (grantable(); as g) {
      @if (types().length) {
        <mp-entity-form #form *mpPerm="'org.onboarding:create'" data-action="submit" [schema]="schema()" [initial]="initial()"
                        submitLabel="Submit for approval" (submitted)="submit($event)" />
      } @else {
        <empty-state title="Nothing to onboard" hint="Your organization may not onboard other organizations." />
      }
    } @else {
      <mp-spinner label="Loading what you may onboard…" />
    }
    @if (flash(); as f) {<mp-alert [kind]="f.kind" data-flash>{{ f.text }}</mp-alert>}
  `,
  styles: [`:host { display: grid; gap: var(--sp-3, 12px); min-width: 0; }`],
})
export class OrgOnboardChildWidget extends MiPayWidget<OrgOnboardChildConfig> {
  readonly grantable = signal<OrgGrantableGet | null>(null);
  readonly flash = signal<{ kind: 'ok' | 'bad'; text: string } | null>(null);
  readonly types = computed(() => (this.grantable()?.children ?? []).filter((c) => !!c.type));
  readonly initial = computed<Record<string, string>>(() => ({ type: this.types()[0]?.type ?? '' }));
  readonly schema = computed<MpFieldSchema[]>(() => [
    { key: 'type', label: 'Type', kind: 'select', required: true,
      options: this.types().map((c) => ({ value: c.type!, label: words(c.type) })) },
    { key: 'name', label: 'Business name', kind: 'text', required: true, placeholder: 'Namib Distribution (Pty) Ltd' },
    { key: 'registrationNumber', label: 'Registration number', kind: 'text', placeholder: 'CC/2019/04512' },
    { key: 'ownerName', label: 'Administrator’s full name', kind: 'text', required: true },
    { key: 'phone', label: 'Administrator’s mobile', kind: 'text', required: true, placeholder: '+264 81 234 5678' },
    { key: 'email', label: 'Administrator’s email', kind: 'text' },
    { key: 'username', label: 'Login name', kind: 'text', hint: 'Left empty, the server makes one from the business name.' },
  ]);

  private readonly form = viewChild<MpEntityForm>('form');

  constructor() {
    super();
    // the form takes its initial values (the first type) once it is drawn
    effect(() => { const f = this.form(); this.initial(); if (f) untracked(() => queueMicrotask(() => f.clear())); });
    this.data.query<OrgGrantableGet>('org.grantable.get').subscribe({
      next: (g) => { this.grantable.set(g); if (!this.types().length) this.state.set('empty'); },
      error: () => undefined,
    });
  }

  submit(v: Record<string, string>) {
    const child = this.types().find((c) => c.type === v['type']);
    const opt = (k: string) => (v[k]?.trim() ? v[k].trim() : null);
    const body: OrgChildrenSubmitRequest = {
      type: v['type'], name: opt('name'), businessName: opt('name'), businessType: null,
      registrationNumber: opt('registrationNumber'), ownerName: opt('ownerName'), phone: (v['phone'] ?? '').trim(),
      username: opt('username'), email: opt('email'), bankName: null, accountNumber: null, accountHolder: null,
      licenceRef: null, supplierType: null, ownerIdNumber: null,
      // the type's defaults: what it may do, and the most it may hand down
      rightsDirect: child?.defaultDirect ?? null, rightsCeiling: child?.allowedCeiling ?? null,
      roleCeiling: null, roleDelegable: null,
    };
    this.flash.set(null);
    this.data.command<OrgChildrenSubmit>('org.children.submit', { body }).subscribe({
      next: (r) => {
        this.form()?.done('Submitted.');
        this.form()?.clear();
        this.flash.set({ kind: 'ok', text: r.message ?? `${v['name']} is submitted and ${words(r.status).toLowerCase()}.` });
        if (r.orgId) this.publish('orgId', r.orgId);
      },
      error: (e: unknown) => { this.form()?.failWire(e); this.flash.set({ kind: 'bad', text: refusal(e, ONBOARD_HINTS) }); },
    });
  }
}
