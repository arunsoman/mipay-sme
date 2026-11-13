import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { CurrencyAmount } from '../atoms/currency-amount';
import { StatusBadge } from '../atoms/status-badge';
import { DataTable, MpCell } from './data-table';

/**
 * sme-ui's column-config table: a thin skin over the shared mp-data-table
 * (projects/shared/ui/data-table.ts, where search, paging, empty states and
 * the table look live). This only adds the sme-ui cell types: NAD amounts,
 * status chips, simulated chips, times, dates and short ids.
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
  imports: [CurrencyAmount, StatusBadge, DataTable, MpCell],
  template: `
    <mp-data-table [cols]="cols()" [rows]="rows()" [empty]="empty()" [loading]="loading()" [searchable]="searchable()"
                   [selectable]="selectable()" [trackBy]="rowKey" (rowClick)="rowClick.emit(rowId($event))">
      <ng-template mpCell="type:money" let-v="value"><currency-amount [amount]="str(v)" /></ng-template>
      <ng-template mpCell="type:status" let-v="value">@if (v) {<status-badge [status]="str(v)" />}</ng-template>
      <ng-template mpCell="type:sim" let-v="value">
        <status-badge status="APPROVED" [simulated]="true" />@if (v) { <span>{{ v }}</span> }
      </ng-template>
      <ng-template mpCell="type:time" let-v="value"><span class="dim">{{ shortTime(v) }}</span></ng-template>
      <ng-template mpCell="type:date" let-v="value"><span class="dim">{{ shortDate(v) }}</span></ng-template>
      <ng-template mpCell="type:link" let-v="value"><a class="lnk" [title]="str(v)">{{ shortId(v) }}</a></ng-template>
    </mp-data-table>
  `,
  styles: [`
    .dim { color: var(--mp-text-dim); font-size: var(--fs-sm); }
    .lnk { word-break: break-all; }
  `],
})
export class MpTable {
  readonly cols = input.required<MpColumn[]>();
  /** rows are plain backend DTO records; typed as Record for config-driven access */
  readonly rows = input.required<readonly unknown[] | unknown[] | null | undefined>();
  /** text beside the spinner while rows is null */
  readonly loading = input('Loading…');
  readonly empty = input('Nothing here yet (no rows match this view)');
  readonly selectable = input(false);
  readonly searchable = input(true);
  readonly rowClick = output<string>();

  readonly rowKey = (r: unknown, i: number) => this.trackBy(i, r);

  val(r: unknown, key: string): unknown {
    return (r as Record<string, unknown>)[key];
  }

  str(v: unknown): string { return String(v ?? ''); }

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
