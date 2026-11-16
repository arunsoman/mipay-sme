import { ChangeDetectionStrategy, Component, effect, signal, untracked, viewChild } from '@angular/core';
import { MiPayWidget } from '../../core/mipay-widget';
import { words } from '../../core/text';
import { MpEntityForm, MpFieldSchema } from '../../../../../src/app/design-system/organisms/mp-entity-form';
import { MpAlert } from '../../../../../src/app/design-system/molecules/alert';
import { EmptyState } from '../../../../../src/app/design-system/molecules/empty-state';
import { MpPerm } from '../../../web-core/perm-directive';
import { KYC_HINTS, refusal } from '../../../kyc/kyc-api';
import type { OrgAdminRecoveryConfig } from './admin-recovery.widget';
import type { OrgAdminRecovery, OrgAdminRecoveryOrg, OrgAdminRecoveryRequest } from './admin-recovery.contract';

const HINTS: Record<string, string> = {
  ...KYC_HINTS,
  DUPLICATE_PHONE: 'that mobile number is already registered',
  DUPLICATE_USERNAME: 'choose another login name',
  RECOVERY_PENDING: 'a request for this organization is already waiting',
};

@Component({
  selector: 'mpw-org-admin-recovery',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpEntityForm, MpAlert, EmptyState, MpPerm],
  template: `
    @if (!orgId()) {
      <empty-state title="Pick an organization" hint="Ask for a new administrator for it here." />
    } @else {
      <div><b data-org>{{ org()?.name ?? '…' }}</b> <span class="dim">{{ type(org()?.type) }}</span></div>
      <p class="dim">The request waits in the onboarding queue; once approved, the new administrator gets a set-up link.</p>
      <mp-entity-form #form *mpPerm="'org.onboarding:authorize'" data-action="request" [schema]="schema"
                      submitLabel="Ask for a new administrator" (submitted)="request($event)" />
    }
    @if (flash(); as f) {<mp-alert [kind]="f.kind" data-flash>{{ f.text }}</mp-alert>}
  `,
  styles: [`
    :host { display: grid; gap: var(--sp-3, 12px); min-width: 0; }
    .dim { color: var(--a-dim, #a3a3a3); font-size: var(--fs-sm, 12.5px); margin: 0; }
  `],
})
export class OrgAdminRecoveryWidget extends MiPayWidget<OrgAdminRecoveryConfig> {
  readonly orgId = this.input<string>('orgId');
  readonly flash = signal<{ kind: 'ok' | 'bad'; text: string } | null>(null);
  readonly org = signal<OrgAdminRecoveryOrg | null>(null);
  readonly type = words;
  private readonly form = viewChild<MpEntityForm>('form');
  readonly schema: MpFieldSchema[] = [
    { key: 'adminName', label: 'Full name', kind: 'text', required: true },
    { key: 'phone', label: 'Mobile', kind: 'text', required: true, placeholder: '+264 81 234 5678' },
    { key: 'email', label: 'Email', kind: 'text', required: true },
    { key: 'username', label: 'Login name', kind: 'text' },
    { key: 'note', label: 'Why', kind: 'text', placeholder: 'The administrator left the company' },
  ];

  constructor() {
    super();
    effect(() => {
      const id = this.orgId();
      untracked(() => {
        this.flash.set(null);
        this.org.set(null);
        if (!id) { this.state.set('empty'); return; }
        this.data.query<OrgAdminRecoveryOrg>('org.admin-recovery.org', { id }).subscribe({ next: (o) => this.org.set(o), error: () => undefined });
      });
    });
  }

  request(v: Record<string, string>) {
    const opt = (k: string) => (v[k]?.trim() ? v[k].trim() : null);
    const body: OrgAdminRecoveryRequest = {
      adminName: (v['adminName'] ?? '').trim(), phone: (v['phone'] ?? '').trim(), email: (v['email'] ?? '').trim(),
      username: opt('username'), note: opt('note'),
    };
    this.data.command<OrgAdminRecovery>('org.admin-recovery.request', { id: this.orgId(), body }).subscribe({
      next: (r) => {
        this.form()?.done('Requested.');
        this.form()?.clear();
        this.flash.set({ kind: 'ok', text: r.message ?? `Requested: ${body.adminName} (${words(r.status).toLowerCase()}).` });
      },
      error: (e: unknown) => { this.form()?.failWire(e); this.flash.set({ kind: 'bad', text: refusal(e, HINTS) }); },
    });
  }
}
