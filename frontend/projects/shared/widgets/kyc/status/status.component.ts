import { ChangeDetectionStrategy, Component, computed, signal } from '@angular/core';
import { MiPayWidget } from '../../core/mipay-widget';
import { when } from '../../core/text';
import { MpCard } from '../../../../../src/app/design-system/atoms/mp-card';
import { StatusBadge } from '../../../../../src/app/design-system/atoms/status-badge';
import { MpButton } from '../../../../../src/app/design-system/atoms/mp-button';
import { KvPair } from '../../../../../src/app/design-system/molecules/kv-pair';
import { MpAlert } from '../../../../../src/app/design-system/molecules/alert';
import { MpSpinner } from '../../../../../src/app/design-system/atoms/mp-spinner';
import { MpPerm } from '../../../web-core/perm-directive';
import { KYC_HINTS, refusal } from '../../../kyc/kyc-api';
import type { KycStatusConfig } from './status.widget';
import type { KycMineGet, KycMineUpgrade } from './status.contract';

/** What the org should do next, by status. */
const NEXT: Record<string, string> = {
  NOT_STARTED: 'Start your KYC application to be fully onboarded.',
  DRAFT: 'Finish your application and submit it for review.',
  ENTRY_CHECK: 'A second administrator in your organization checks it before it goes to review.',
  SUBMITTED: 'Your application is waiting for a reviewer.',
  IN_REVIEW: 'A reviewer is looking at your application.',
  WITHDRAWN: 'The application was withdrawn. Start again when you are ready.',
  APPROVED: 'You are verified. Nothing to do until your next review.',
  REJECTED: 'Your application was rejected. See the note.',
};

/** One card: status, dates, the decision note and the next step. */
@Component({
  selector: 'mpw-kyc-status',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpCard, StatusBadge, MpButton, KvPair, MpAlert, MpSpinner, MpPerm],
  template: `
    @if (view(); as v) {
      <mp-card [title]="v.orgName ?? 'Your organization'" subtitle="KYC application">
        <div class="body">
          <status-badge [status]="v.status ?? 'NOT_STARTED'" />
          <p class="next" data-next>{{ next() }}</p>
          <kv-pair k="Submitted">{{ day(v.submittedAt) }}</kv-pair>
          <kv-pair k="Decided">{{ day(v.decidedAt) }}</kv-pair>
          @if (v.decisionNote) {<kv-pair k="Reviewer’s note">{{ v.decisionNote }}</kv-pair>}
          @if (v.isSuspended) {<mp-alert kind="bad">Your organization (or one above it) is suspended.</mp-alert>}
          @if (v.status === 'DRAFT' && v.canEdit) {
            <mp-button *mpPerm="'kyc.application:modify'" data-action="upgrade" variant="ghost" (onClick)="upgrade(v)">
              Move to the current form
            </mp-button>
          }
        </div>
      </mp-card>
    } @else {
      <mp-spinner label="Loading your KYC status…" />
    }
    @if (flash(); as f) {<mp-alert [kind]="f.kind" data-flash>{{ f.text }}</mp-alert>}
  `,
  styles: [`
    :host { display: grid; gap: var(--sp-3, 12px); min-width: 0; }
    .body { display: grid; gap: var(--sp-2, 8px); justify-items: start; }
    .next { margin: 0; overflow-wrap: anywhere; }
  `],
})
export class KycStatusWidget extends MiPayWidget<KycStatusConfig> {
  readonly view = signal<KycMineGet | null>(null);
  readonly flash = signal<{ kind: 'ok' | 'bad'; text: string } | null>(null);
  readonly next = computed(() => NEXT[this.view()?.status ?? 'NOT_STARTED'] ?? '');
  readonly day = when;

  constructor() {
    super();
    this.data.query<KycMineGet>('kyc.mine.get').subscribe({
      next: (v) => {
        this.view.set(v);
        this.publish('orgId', v.orgId);
        this.publish('orgName', v.orgName);
      },
      error: () => undefined,
    });
  }

  upgrade(v: KycMineGet) {
    this.data.command<KycMineUpgrade>('kyc.mine.upgrade', { id: v.orgId, body: {} }).subscribe({
      next: (u) => { this.view.set(u); this.flash.set({ kind: 'ok', text: 'Your draft now uses the current form.' }); },
      error: (e: unknown) => this.flash.set({ kind: 'bad', text: refusal(e, KYC_HINTS) }),
    });
  }
}
