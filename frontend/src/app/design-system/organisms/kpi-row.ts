import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { CurrencyAmount } from '../atoms/currency-amount';
import { StatTile } from '../molecules/stat-tile';

/**
 * Organism: row of KPI tiles (dashboards M04/S08/L10). Amounts render as money (N$) unless a tile
 * sets `money: false` (counts). Tiles share one row when there is room and wrap on narrow widths
 * (phones: two per row), so a row never overflows its container.
 */
@Component({
  selector: 'kpi-row',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [StatTile],
  template: `
    <div class="kpi">
      @for (t of tiles(); track t.label) {
        <stat-tile [label]="t.label" [amount]="t.amount" [sub]="t.sub ?? ''" [money]="t.money !== false" />
      }
    </div>
  `,
  styles: [`
    .kpi { display: grid; gap: var(--sp-4); grid-template-columns: repeat(auto-fit, minmax(min(100%, 140px), 1fr)); }
  `],
})
export class KpiRow {
  readonly tiles = input.required<{ label: string; amount: number | string; sub?: string; money?: boolean }[]>();
}
