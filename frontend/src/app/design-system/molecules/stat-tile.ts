import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { CurrencyAmount } from '../atoms/currency-amount';

/**
 * Molecule: one KPI number (M04 dashboard tiles). label + amount + sub text.
 * Money renders with the single money atom so N$ formatting lives in one place.
 */
@Component({
  selector: 'stat-tile',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CurrencyAmount],
  template: `
    <div class="tile">
      <div class="dim label">{{ label() }}</div>
      <div class="value">
        @if (money()) {
          <currency-amount [amount]="amount()" />
        } @else {
          {{ amount() }}
        }
      </div>
      @if (sub()) {<div class="dim sub">{{ sub() }}</div>}
    </div>
  `,
  styles: [`
    .tile { min-width: 0; }
    .label { text-transform: uppercase; letter-spacing: .06em; font-weight: 600; }
    .value { font-size: var(--fs-xl); font-weight: 700; margin-top: 2px; font-variant-numeric: tabular-nums; }
    .sub { margin-top: 2px; }
  `],
})
export class StatTile {
  readonly label = input.required<string>();
  readonly amount = input.required<number | string>();
  readonly sub = input('');
  readonly money = input(true);

  readonly fmt = computed(() => String(this.amount()));
}
