import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { CurrencyAmount } from '../atoms/currency-amount';
import { StatusBadge } from '../atoms/status-badge';

/**
 * THE table organism. Every list in the product renders through this one
 * component with a column config - there is no bespoke <table> markup in any
 * feature screen (single place for empty state, alignment, NAD amounts,
 * status chips, simulated chips and row actions).
 *
 * Column shape:
 *   { key: 'amount', label: 'Amount', type: 'money'|'status'|'sim'|'text'|'time'|'date',
 *     align?: 'right', width?: '120px', actions for row buttons? }
 */
export interface MpColumn {
  key: string;
  label: string;
  type?: 'text' | 'money' | 'status' | 'sim' | 'time' | 'date' | 'num' | 'link';
  align?: 'left' | 'right';
  width?: string;
}

@Component({
  selector: 'mp-table',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CurrencyAmount, StatusBadge],
  template: `
    <div class="tbl" [style.--cols]="cols().length">
      <table>
        <thead>
          <tr>
            @for (c of cols(); track c.key) {
              <th [style.width]="c.width" [class.right]="c.align === 'right'">{{ c.label }}</th>
            }
          </tr>
        </thead>
        <tbody>
          @for (r of rows(); track trackBy($index, r)) {
            <tr (click)="rowClick.emit(rowId(r))" [class.clickable]="selectable()">
              @for (c of cols(); track c.key) {
                <td [class.right]="c.align === 'right'">
                  @switch (c.type) {
                    @case ('money') { <currency-amount [amount]="sval(r, c.key)" /> }
                    @case ('num') { {{ val(r, c.key) }} }
                    @case ('status') {
                      @if (val(r, c.key)) {<status-badge [status]="sval(r, c.key)" />}
                    }
                    @case ('sim') {
                      <status-badge status="APPROVED" [simulated]="true" />
                      @if (val(r, c.key)) { <span>{{ val(r, c.key) }}</span> }
                    }
                    @case ('time') { <span class="dim">{{ shortTime(val(r, c.key)) }}</span> }
                    @case ('date') { <span class="dim">{{ shortDate(val(r, c.key)) }}</span> }
                    @case ('link') { <a class="lnk" [title]="sval(r, c.key)">{{ shortId(sval(r, c.key)) }}</a> }
                    @default { {{ val(r, c.key) }} }
                  }
                </td>
              }
            </tr>
          }
          @empty {
            <tr><td class="none" [attr.colspan]="cols().length">
              <div class="dim empty">{{ empty() }}</div>
            </td></tr>
          }
        </tbody>
      </table>
    </div>
  `,
  styles: [`
    .tbl { width: 100%; overflow-x: auto; }
    table { width: 100%; border-collapse: collapse; font-size: var(--fs-md); }
    th {
      text-align: left; font-size: var(--fs-xs); text-transform: uppercase; letter-spacing: .06em;
      color: var(--mp-text-dim); padding: 8px; border-bottom: 1px solid var(--mp-border);
      white-space: nowrap;
    }
    td { padding: 9px 8px; border-bottom: 1px solid color-mix(in srgb, var(--mp-border) 55%, transparent); }
    tr:last-child td { border-bottom: none; }
    tr.clickable { cursor: pointer; }
    tr.clickable:hover td { background: color-mix(in srgb, var(--mp-text) 5%, transparent); }
    .right { text-align: right; }
    .dim { color: var(--mp-text-dim); font-size: var(--fs-sm); }
    .none { text-align: center; color: var(--mp-text-dim); padding: var(--sp-5); }
    .lnk { word-break: break-all; }
  `],
})
export class MpTable {
  readonly cols = input.required<MpColumn[]>();
  /** rows are plain backend DTO records; typed as Record for config-driven access */
  readonly rows = input.required<unknown[]>();
  readonly empty = input('Nothing here yet (no rows match this view)');
  readonly selectable = input(false);
  readonly rowClick = output<string>();

  val(r: unknown, key: string): unknown {
    return (r as Record<string, unknown>)[key];
  }

  sval(r: unknown, key: string): string { return String(this.val(r, key) ?? ''); }

  rowId(r: unknown): string {
    const x = r as Record<string, unknown>;
    return String(x['id'] ?? x['consentId'] ?? x['runId'] ?? x['userId'] ?? '');
  }

  trackBy(_i: number, r: unknown): string {
    const x = r as Record<string, unknown>;
    return String(x['id'] ?? x['consentId'] ?? x['runId'] ?? x['userId'] ?? _i);
  }

  shortTime(v: unknown): string {
    const s = String(v ?? '');
    const d = new Date(s);
    if (isNaN(d.getTime())) return s ? s.slice(0, 16) : '';
    return d.toLocaleString(undefined, { month: 'short', day: '2-digit', hour: '2-digit', minute: '2-digit' });
  }

  shortDate(v: unknown): string { return String(v ?? '').slice(0, 10); }

  shortId(v: unknown): string { return String(v ?? '').slice(0, 8); }
}
