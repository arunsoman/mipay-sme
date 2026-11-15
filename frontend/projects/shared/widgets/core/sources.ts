/**
 * Logical data sources → requests, per portal (docs/plans/portal-builder.md §3). A widget asks
 * for "kyc.application.get"; the portal it is placed in decides the URL. This is how one widget
 * fetches the right data in every portal - and the only list of URLs widgets can reach.
 *
 * Pure TypeScript: the registry tool resolves every widget's routes from here for the
 * registry server, which checks them against the backend's @Perm endpoint manifest.
 */
import type { BusinessPortal } from '../../web-core/portal-permission-catalogue';

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

export interface SourceRoute {
  readonly method: HttpMethod;
  /** Relative /api path; `{name}` segments are filled from params. */
  readonly path: string;
  /** Params sent as the query string (when present). */
  readonly query?: readonly string[];
}

/** '*' = every portal; a portal key overrides it. */
export type SourceEntry = Readonly<Partial<Record<BusinessPortal | '*', SourceRoute>>>;
export type SourceMap = Readonly<Record<string, SourceEntry>>;

export function defineSources<M extends SourceMap>(map: M): M {
  for (const [name, entry] of Object.entries(map)) {
    for (const [portal, r] of Object.entries(entry) as [string, SourceRoute][]) {
      if (!isSafeApiPath(r.path)) throw new Error(`source ${name} (${portal}): "${r.path}" is not a relative /api/ path`);
    }
  }
  return map;
}

/** Same-origin API paths only: no scheme, no protocol-relative, no traversal. */
export function isSafeApiPath(path: string): boolean {
  return path.startsWith('/api/') && !path.startsWith('//') && !path.includes('..') && !/^[a-z]+:/i.test(path);
}

export function resolveRoute(map: SourceMap, name: string, portal: BusinessPortal): SourceRoute | null {
  const e = map[name];
  return e ? (e[portal] ?? e['*'] ?? null) : null;
}

export interface BuiltRequest { method: HttpMethod; url: string; query: Record<string, string>; body: unknown }

/**
 * Fills `{name}` from params (URI-encoded), picks the route's query params, and sends
 * `params.body` as the body. A missing path param is an error, never an "undefined" URL.
 */
export function buildRequest(route: SourceRoute, params: Readonly<Record<string, unknown>> = {}): BuiltRequest {
  const url = route.path.replace(/\{(\w+)\}/g, (_, k: string) => {
    const v = params[k];
    if (v === undefined || v === null || v === '') throw new Error(`missing path parameter "${k}" for ${route.path}`);
    return encodeURIComponent(String(v));
  });
  const query: Record<string, string> = {};
  for (const k of route.query ?? []) {
    const v = params[k];
    if (v !== undefined && v !== null && v !== '') query[k] = String(v);
  }
  return { method: route.method, url, query, body: params['body'] };
}
