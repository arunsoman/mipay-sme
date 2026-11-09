import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { CurrencyAmount } from '../atoms/currency-amount';
import { StatTile } from '../molecules/stat-tile';

/** Organism: row of KPI tiles (dashboards M04/S08/L10). */
@Component({
  selector: 'kpi-row',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [StatTile],
  template: `
    <div class="kpi" [style.gridTemplateColumns]="'repeat(' + tiles().length + ', minmax(0, 1fr))'">
      @for (t of tiles(); track t.label) {
        <stat-tile [label]="t.label" [amount]="t.amount" [sub]="t.sub ?? ''" [money]="t.money !== false" />
      }
    </div>
  `,
  styles: [`
    .kpi { display: grid; gap: var(--sp-4); }
  `],
})
export class KpiRow {
  readonly tiles = input.required<{ label: string; amount: number | string; sub?: string; money?: boolean }[]>();
}
