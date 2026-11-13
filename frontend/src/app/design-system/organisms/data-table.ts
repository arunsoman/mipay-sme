import {
  ChangeDetectionStrategy, Component, computed, contentChildren, Directive, effect, inject, input, output, TemplateRef,
} from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { MpPager, MpTableSearch, TableView } from '../molecules/table-view';
import { MpSpinner } from '../atoms/mp-spinner';

/**
 * THE table. Every table in every portal and in sme-ui renders through this one
 * component: search, paging, empty / no-match messages, row clicks and the look
 * all live here, so a change to tables is made here only. Never write a bare
 * <table> in a feature screen.
 *
 *   <mp-data-table [cols]="cols" [rows]="people()" placeholder="Search people">
 *     <ng-template mpCell="roles" let-p>…chips…</ng-template>      <!-- custom cell -->
 *   </mp-data-table>
 *
 * A column shows `text(row)` or `row[key]` as text unless a cell template is given for its key
 * (`mpCell="roles"`) or for its type (`mpCell="type:money"`, how sme-ui's
 * mp-table draws money / status chips). Search reads `search(row)` when the
 * column has one, else what the cell shows. Pass rows as null while they load:
 * the table shows a spinner with `loading` text instead.
 */
export interface DataCol<T = any> {
  key: string;
  label: string;
  type?: string;
  align?: 'left' | 'right';
  width?: string;
  /** plain text to show (and search) instead of row[key] */
  text?: (row: T) => unknown;
  /** what search matches for this column (defaults to the shown text) */
  search?: (row: T) => unknown;
  /** extra class on the cells (e.g. 'small', 'mono', 'muted') */
  cls?: string;
}

/** A cell template: `<ng-template mpCell="key" let-row let-v="value">`. */
@Directive({ selector: 'ng-template[mpCell]' })
export class MpCell {
  readonly name = input.required<string>({ alias: 'mpCell' });
  readonly tpl = inject(TemplateRef);
}

@Component({
  selector: 'mp-data-table',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NgTemplateOutlet, MpTableSearch, MpPager, MpSpinner],
  template: `
    @if (rows() == null) {
      <div class="loading"><mp-spinner [label]="loading()" /></div>
    } @else {
    @if (searchable()) {<mp-table-search [view]="view" [placeholder]="placeholder()" />}
    <div class="tbl">
      <table>
        <thead><tr>
          @for (c of cols(); track c.key) {
            <th [style.width]="c.width" [class.right]="c.align === 'right'">{{ c.label }}</th>
          }
        </tr></thead>
        <tbody>
          @for (r of view.rows(); track key(r, $index)) {
            <tr [class]="rowClass()?.(r) ?? ''" [class.clickable]="selectable()" (click)="rowClick.emit(r)">
              @for (c of cols(); track c.key) {
                <td [class.right]="c.align === 'right'" [class]="c.cls ?? ''">
                  @if (tplFor(c); as t) {
                    <ng-container *ngTemplateOutlet="t; context: { $implicit: r, value: cell(r, c), col: c }" />
                  } @else { {{ cell(r, c) }} }
                </td>
              }
            </tr>
          } @empty {
            <tr><td class="none" [attr.colspan]="cols().length">
              {{ view.hasSource() ? noMatch() : empty() }}
            </td></tr>
          }
        </tbody>
      </table>
    </div>
    <mp-pager [view]="view" />
    }
  `,
  styles: [`
    :host { display: block; min-width: 0; }
    .tbl { width: 100%; overflow-x: auto; }
    table { width: 100%; border-collapse: collapse; font-size: var(--fs-sm); }
    th { text-align: left; color: var(--mp-text-dim); font-weight: 800; font-size: 10.5px; text-transform: uppercase;
         letter-spacing: .1em; padding: 10px 12px; border-bottom: 1px solid var(--mp-border); white-space: nowrap; }
    td { padding: 11px 12px; vertical-align: top;
         border-bottom: 1px solid color-mix(in srgb, var(--mp-border) 60%, transparent); }
    tr:last-child td { border-bottom: none; }
    tbody tr { transition: background .12s ease; }
    tbody tr:hover td { background: color-mix(in srgb, var(--mp-text) 3%, transparent); }
    tr.clickable { cursor: pointer; }
    tr.off td { opacity: .55; }
    .right { text-align: right; }
    td.small { font-size: var(--fs-xs); }
    td.mono { font-family: ui-monospace, "SF Mono", Menlo, Consolas, monospace; font-size: var(--fs-xs); }
    td.muted, .none { color: var(--mp-text-dim); }
    td.nowrap { white-space: nowrap; }
    .none { text-align: center; padding: 20px 12px; }
    .loading { padding: 14px 4px; }
  `],
})
export class DataTable<T = any> {
  readonly cols = input.required<DataCol<T>[]>();
  readonly rows = input.required<readonly T[] | T[] | null | undefined>();
  readonly empty = input('Nothing here yet.');
  /** shown (with a spinner) while rows is still null / undefined */
  readonly loading = input('Loading…');
  readonly noMatch = input('Nothing matches your search.');
  readonly searchable = input(true);
  readonly placeholder = input('Search');
  readonly pageSize = input(10);
  readonly selectable = input(false);
  /** stable row identity (defaults to id / name / index) */
  readonly trackBy = input<(row: T, index: number) => unknown>();
  readonly rowClass = input<(row: T) => string>();
  readonly rowClick = output<T>();

  private readonly cells = contentChildren(MpCell);
  private readonly tpls = computed(() => new Map(this.cells().map((c) => [c.name(), c.tpl])));

  readonly view = new TableView<T>(() => [...(this.rows() ?? [])],
    (r) => this.cols().map((c) => (c.search ? c.search(r) : this.cell(r, c))));

  constructor() {
    effect(() => this.view.setSize(this.pageSize()));
  }

  tplFor(c: DataCol<T>): TemplateRef<unknown> | null {
    return this.tpls().get(c.key) ?? (c.type ? this.tpls().get('type:' + c.type) : undefined) ?? null;
  }

  val(r: T, key: string): unknown { return (r as Record<string, unknown>)?.[key]; }

  cell(r: T, c: DataCol<T>): unknown { return c.text ? c.text(r) : this.val(r, c.key); }

  key(r: T, i: number): unknown {
    const f = this.trackBy();
    if (f) return f(r, i);
    const x = r as Record<string, unknown>;
    return x?.['id'] ?? x?.['name'] ?? i;
  }
}

/** `imports: [...DATA_TABLE]` */
export const DATA_TABLE = [DataTable, MpCell] as const;
