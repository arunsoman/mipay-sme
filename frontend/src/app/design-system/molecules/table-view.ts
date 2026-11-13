import { ChangeDetectionStrategy, Component, computed, input, signal, Signal } from '@angular/core';

/**
 * Search + paging state used inside mp-data-table (data-table.ts). Screens use
 * mp-data-table, not this directly; only a layout data-table can't express (the
 * permission matrix's role grid) drives a TableView + <mp-pager> itself. A TableView wraps the
 * table's source rows; the template loops over `view.rows()` (the current
 * page of the filtered rows) and puts `<mp-table-search>` above and
 * `<mp-pager>` below:
 *
 *   readonly people = new TableView(() => this.all(), p => [p.name, p.email]);
 *
 *   <mp-table-search [view]="people" placeholder="Search people" />
 *   <table>… @for (p of people.rows(); track p.id) {…} …</table>
 *   <mp-pager [view]="people" />
 *
 * Search matches every word of the query (any order, case-insensitive)
 * against the row's text: the `text` function when given, otherwise every
 * plain value on the row (one level of nesting deep). Typing jumps back to
 * the first page; a page past the end (rows removed) clamps to the last.
 */
export class TableView<T> {
  readonly query = signal('');
  private readonly pageIx = signal(0);
  readonly size = signal(10);

  /** source rows after the search */
  readonly filtered: Signal<T[]>;
  readonly total: Signal<number>;
  readonly pages: Signal<number>;
  /** the current (clamped) zero-based page */
  readonly page: Signal<number>;
  /** the rows to render */
  readonly rows: Signal<T[]>;
  /** whether any rows exist before searching (an empty table vs. no match) */
  readonly hasSource: Signal<boolean>;

  constructor(source: () => T[] | null | undefined, text?: (row: T) => unknown, size = 10) {
    this.size.set(size);
    const all = computed(() => source() ?? []);
    this.hasSource = computed(() => all().length > 0);
    this.filtered = computed(() => {
      const words = this.query().toLowerCase().split(/\s+/).filter(Boolean);
      if (!words.length) return all();
      return all().filter(r => {
        const hay = flat(text ? text(r) : r, 0).toLowerCase();
        return words.every(w => hay.includes(w));
      });
    });
    this.total = computed(() => this.filtered().length);
    this.pages = computed(() => Math.max(1, Math.ceil(this.total() / this.size())));
    this.page = computed(() => Math.min(this.pageIx(), this.pages() - 1));
    this.rows = computed(() => {
      const start = this.page() * this.size();
      return this.filtered().slice(start, start + this.size());
    });
  }

  search(q: string): void { this.query.set(q); this.pageIx.set(0); }
  go(p: number): void { this.pageIx.set(Math.max(0, Math.min(p, this.pages() - 1))); }
  setSize(n: number): void { this.size.set(n); this.pageIx.set(0); }
}

function flat(v: unknown, depth: number): string {
  if (v == null) return '';
  if (typeof v !== 'object') return String(v);
  if (depth > 2) return '';
  const vals = Array.isArray(v) ? v : Object.values(v as Record<string, unknown>);
  return vals.map(x => flat(x, depth + 1)).join(' ');
}

const SHARED_STYLES = `
  :host { display: block; }
  input.q {
    width: 100%; max-width: 320px; font: inherit; font-size: 13px;
    color: var(--mp-text); background: var(--mp-surface);
    border: 1px solid var(--mp-border); border-radius: 999px;
    padding: 7px 14px 7px 32px; outline: none;
    background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='14' height='14' viewBox='0 0 24 24' fill='none' stroke='%23999' stroke-width='2.4' stroke-linecap='round'%3E%3Ccircle cx='11' cy='11' r='7'/%3E%3Cpath d='m20 20-3.5-3.5'/%3E%3C/svg%3E");
    background-repeat: no-repeat; background-position: 12px center;
  }
  input.q:focus { border-color: var(--mp-accent); box-shadow: 0 0 0 3px color-mix(in srgb, var(--mp-accent) 22%, transparent); }
`;

/** The search box above a table. */
@Component({
  selector: 'mp-table-search',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="bar">
      <input class="q" type="search" [placeholder]="placeholder()" [attr.aria-label]="placeholder()"
             [value]="view().query()" (input)="view().search($any($event.target).value)" />
      @if (view().query()) {
        <span class="hits">{{ view().total() }} {{ view().total() === 1 ? 'match' : 'matches' }}</span>
      }
    </div>
  `,
  styles: [SHARED_STYLES + `
    .bar { display: flex; align-items: center; gap: 10px; margin: 0 0 10px; }
    .hits { font-size: 12px; color: var(--mp-text-dim); white-space: nowrap; }
  `],
})
export class MpTableSearch {
  readonly view = input.required<TableView<unknown>>();
  readonly placeholder = input('Search');
}

/** The page controls below a table: "11–20 of 47", rows per page, prev / numbers / next. */
@Component({
  selector: 'mp-pager',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let v = view();
    @if (v.total() > 0) {
      <div class="pager">
        <span class="count">{{ from() }}–{{ to() }} of {{ v.total() }}</span>
        <label class="size">
          <span>Rows</span>
          <select [value]="'' + v.size()" (change)="v.setSize(+$any($event.target).value)" aria-label="Rows per page">
            @for (n of sizes; track n) {<option [value]="'' + n" [selected]="n === v.size()">{{ n }}</option>}
          </select>
        </label>
        <span class="grow"></span>
        @if (v.pages() > 1) {
          <nav class="nums" aria-label="Pages">
            <button type="button" class="pg" (click)="v.go(v.page() - 1)" [disabled]="v.page() === 0" aria-label="Previous page">‹</button>
            @for (p of nums(); track $index) {
              @if (p < 0) {<span class="gap">…</span>}
              @else {
                <button type="button" class="pg" [class.on]="p === v.page()" (click)="v.go(p)"
                        [attr.aria-current]="p === v.page() ? 'page' : null">{{ p + 1 }}</button>
              }
            }
            <button type="button" class="pg" (click)="v.go(v.page() + 1)" [disabled]="v.page() >= v.pages() - 1" aria-label="Next page">›</button>
          </nav>
        }
      </div>
    }
  `,
  styles: [`
    :host { display: block; }
    .pager { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 14px; padding: 10px 2px 0;
             font-size: 12.5px; color: var(--mp-text-dim); }
    .size { display: inline-flex; align-items: center; gap: 6px; }
    .size select { width: auto; font: inherit; font-size: 12.5px; padding: 3px 8px; border-radius: 8px;
                   color: var(--mp-text); background: var(--mp-surface); border: 1px solid var(--mp-border); }
    .nums { display: flex; align-items: center; gap: 4px; flex-wrap: wrap; }
    .pg { min-width: 30px; height: 30px; padding: 0 8px; border-radius: 999px; font: inherit; font-size: 12.5px;
          font-weight: 600; cursor: pointer; color: var(--mp-text); background: transparent;
          border: 1px solid var(--mp-border); line-height: 1; }
    .pg:hover:not(:disabled):not(.on) { border-color: var(--mp-text-dim); }
    .pg.on { background: var(--mp-accent); border-color: var(--mp-accent); color: var(--mp-accent-ink); font-weight: 800; }
    .pg:disabled { opacity: .35; cursor: default; }
    .gap { padding: 0 2px; }
  `],
})
export class MpPager {
  readonly view = input.required<TableView<unknown>>();
  readonly sizes = [10, 25, 50, 100];

  readonly from = computed(() => this.view().page() * this.view().size() + 1);
  readonly to = computed(() => Math.min(this.view().total(), (this.view().page() + 1) * this.view().size()));

  /** page numbers to show; -1 marks a gap: 1 … 4 5 6 … 12 */
  readonly nums = computed(() => {
    const n = this.view().pages(), c = this.view().page();
    if (n <= 7) return Array.from({ length: n }, (_, i) => i);
    const keep = new Set([0, n - 1, c - 1, c, c + 1].filter(i => i >= 0 && i < n));
    if (c <= 2) [1, 2, 3].forEach(i => keep.add(i));
    if (c >= n - 3) [n - 4, n - 3, n - 2].forEach(i => keep.add(i));
    const sorted = [...keep].sort((a, b) => a - b);
    const out: number[] = [];
    sorted.forEach((p, i) => { if (i && p - sorted[i - 1] > 1) out.push(-1); out.push(p); });
    return out;
  });
}

/** Import both in one go: `imports: [...TABLE_TOOLS]`. */
export const TABLE_TOOLS = [MpTableSearch, MpPager] as const;
