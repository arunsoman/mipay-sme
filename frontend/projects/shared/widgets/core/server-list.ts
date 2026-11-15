/**
 * Server-searched lists in widgets (docs/plans/admin-console.md). The backend's ListQuery
 * contract (q, sort=field,asc|desc, filters, page, size → {items, total, page, size}) is the
 * same for every list; this file holds its two widget-side halves:
 *
 *   connectServerList  sends the list's query through the widget's port whenever it changes
 *                      (search debounced), cancels stale answers, and fills `list.result`;
 *   fixturePage        answers that query from fixture rows the way the server does, so the
 *                      workbench and unit tests exercise real search / filter / sort / paging.
 *
 *   readonly list = new ServerQuery<Row>(this.config.pageSize, { field: 'name', dir: 'asc' });
 *   constructor() { super(); connectServerList(this.data, 'admin.organizations.search', this.list, { … }); }
 *   <mp-data-table [server]="list" [rows]="list.rows()" [cols]="cols" />
 */
import { DestroyRef, PendingTasks, computed, inject } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { EMPTY, catchError, debounceTime, filter, switchMap, tap } from 'rxjs';
import type { ServerPage, ServerQuery } from '../../../../src/app/design-system/molecules/table-view';
import type { WidgetDataPort } from './port';

export type { ServerPage } from '../../../../src/app/design-system/molecules/table-view';

export interface ServerListOptions<T> {
  /** params added to every request (path params such as orgId); null = not ready, send nothing */
  extra?: () => Readonly<Record<string, unknown>> | null;
  /** each page as it arrives */
  onPage?: (page: ServerPage<T>) => void;
  onError?: (e: unknown) => void;
  /** milliseconds to wait for typing to settle (0 in unit tests) */
  debounceMs?: number;
}

/** Call in a widget constructor (needs the injection context). */
export function connectServerList<T>(data: WidgetDataPort, source: string, list: ServerQuery<T>,
                                     opts: ServerListOptions<T> = {}): void {
  const destroy = inject(DestroyRef);
  // a query waiting out the debounce counts as pending work, so whenStable() (tests, the
  // workbench) does not read a table about to change. It ends when the request is SENT: from
  // then on it is an ordinary in-flight call (the port tracks it; a test gateway answers it).
  const tasks = inject(PendingTasks);
  let done: (() => void) | null = null;
  const begin = () => { if (!done) done = tasks.add(); };
  const end = () => { done?.(); done = null; };
  destroy.onDestroy(end);

  const request = computed(() => {
    const extra = opts.extra ? opts.extra() : {};
    return extra === null ? null : { ...extra, ...list.params() };
  });
  let last = '';
  toObservable(request).pipe(
    tap(begin),
    debounceTime(opts.debounceMs ?? 250),
    // the same query again (e.g. typed and erased) sends nothing
    filter((params) => {
      const key = JSON.stringify(params);
      end();
      if (params === null || key === last) return false;
      last = key;
      return true;
    }),
    // a newer query cancels the one in flight: the table never shows a stale answer
    switchMap((params) => data.query<ServerPage<T>>(source, params!).pipe(
      tap((page) => { list.result.set(page); opts.onPage?.(page); }),
      // a failed query may be retried as is
      catchError((e: unknown) => { last = ''; opts.onError?.(e); return EMPTY; }),
    )),
    takeUntilDestroyed(destroy),
  ).subscribe();
}

/** How one fixture row is searched, filtered and sorted (mirror of the backend's ListQuery.Fields). */
export interface FixtureFields<T> {
  /** text fields matched by q (case-insensitive contains) */
  search: ((row: T) => unknown)[];
  /** filterable and sortable fields by their API name */
  fields: Record<string, (row: T) => unknown>;
}

/** A fixture answer for a ListQuery source: the same search / filter / sort / page as the server. */
export function fixturePage<T>(rows: readonly T[], spec: FixtureFields<T>) {
  return (params: Readonly<Record<string, unknown>> = {}): ServerPage<T> => {
    const q = String(params['q'] ?? '').toLowerCase().trim();
    const text = (v: unknown): string => (Array.isArray(v) ? v.map(text).join(' ') : v == null ? '' : String(v).toLowerCase());
    let out = rows.filter((r) => !q || spec.search.some((f) => text(f(r)).includes(q)));
    for (const [k, get] of Object.entries(spec.fields)) {
      const want = params[k];
      if (want === undefined || want === null || want === '') continue;
      const any = String(want).split(',').map((s) => s.trim().toLowerCase());
      out = out.filter((r) => {
        const v = get(r);
        const vals = Array.isArray(v) ? v : [v];
        return vals.some((x) => any.includes(String(x ?? '').toLowerCase()));
      });
    }
    const [field, dir] = String(params['sort'] ?? '').split(',');
    const key = field ? spec.fields[field] : undefined;
    if (key) {
      const norm = (v: unknown) => (typeof v === 'number' ? v : Array.isArray(v) ? v.length : String(v ?? '').toLowerCase());
      out = [...out].sort((a, b) => {
        const x = norm(key(a)), y = norm(key(b));
        const c = x < y ? -1 : x > y ? 1 : 0;
        return dir === 'desc' ? -c : c;
      });
    }
    const size = Math.max(1, Number(params['size'] ?? 20));
    const page = Math.max(0, Number(params['page'] ?? 0));
    return { items: out.slice(page * size, page * size + size), total: out.length, page, size };
  };
}
