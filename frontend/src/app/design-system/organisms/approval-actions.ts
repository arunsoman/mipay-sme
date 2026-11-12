import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { MpButton } from '../atoms/mp-button';
import { StatusBadge } from '../atoms/status-badge';
import { AccessService } from '../../../../projects/shared/web-core/access';

/**
 * Organism: approve / reject decision strip used by ALL approval queues
 * (merchant, supplier, bank-account; T02/T03/T04). Blocks on the same-state
 * rules server-side; the UI just collects approve + optional note/sanctions.
 * Callers pass the queue's AUTHORIZE cell (e.g. "ops.merchant_approval:authorize"):
 * without it the decision buttons render away - the queue stays read-only.
 */
@Component({
  selector: 'approval-actions',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpButton, StatusBadge],
  template: `
    <div class="strip">
      <status-badge [status]="status()" [simulated]="simulated()" />
      <span class="grow"></span>
      @if (mayDecide()) {
        <mp-button variant="ghost" (onClick)="decide.emit({approve:false})">{{ rejectLabel() }}</mp-button>
        <mp-button variant="primary" (onClick)="decide.emit({approve:true, note: note(), sanctionsResult: sanctions()})">
          {{ approveLabel() }}
        </mp-button>
      }
    </div>
  `,
  styles: [`
    .strip { display: flex; align-items: center; gap: var(--sp-2); }
    .grow { flex: 1; }
  `],
})
export class ApprovalActions {
  readonly status = input.required<string>();
  readonly simulated = input<boolean | string>(false);
  readonly approveLabel = input('Approve');
  readonly rejectLabel = input('Reject');
  readonly note = input('');
  readonly sanctions = input('');
  /** The AUTHORIZE cell that unlocks the decision buttons ("ops.merchant_approval:authorize"). */
  readonly perm = input('');
  readonly decide = output<{ approve: boolean; note?: string; sanctionsResult?: string }>();

  private readonly access = inject(AccessService);
  readonly mayDecide = computed(() => !this.perm() || this.access.can(this.perm()));
}
