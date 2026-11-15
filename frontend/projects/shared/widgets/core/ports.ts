import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Injectable, InjectionToken, inject } from '@angular/core';
import { Observable, catchError, defer, finalize, of, tap, throwError, timer, switchMap } from 'rxjs';
import { BrowserSessionService } from '../../web-core/session';
import type { BusinessPortal } from '../../web-core/portal-permission-catalogue';
import { PortRecorder, WIDGET_PORTAL, WIDGET_SOURCES, WidgetStateRef } from './context';
import { buildRequest, isSafeApiPath, resolveRoute } from './sources';
import { ApiProblem, PortCall, PortalAwarePort, WidgetDataPort, WidgetProblem } from './port';
import { FixtureSet, Scenario, isFail } from './fixtures';
import type { WidgetDef } from './manifest';
import { check, describe as describeShape } from './shape';

/** What happens on 401: re-login (login_required) or step-up (RFC 9470). Swappable in tests. */
export interface ReauthHandler { login(): void; stepUp(challenge: string): void }
export const WIDGET_REAUTH = new InjectionToken<ReauthHandler>('WIDGET_REAUTH', {
  providedIn: 'root',
  factory: () => {
    const session = inject(BrowserSessionService);
    const here = () => location.pathname + location.search;
    // Step-up replay for XHR is open item O1 of the plan: until the gateway's step-up endpoint
    // lands (P4), a step-up challenge re-runs the login, and the user repeats the action.
    return { login: () => session.login(here()), stepUp: () => session.login(here()) };
  },
});

function problemOf(e: unknown): ApiProblem {
  if (e instanceof WidgetProblem) return e.problem;
  if (e instanceof HttpErrorResponse) {
    const body = (e.error && typeof e.error === 'object' ? e.error : {}) as Record<string, unknown>;
    const code = typeof body['code'] === 'string' ? body['code'] : undefined;
    const rawFields = body['fields'];
    const fields = rawFields && typeof rawFields === 'object'
      ? Object.fromEntries(Object.entries(rawFields as Record<string, unknown>).map(([key, value]) => [key, String(value)]))
      : undefined;
    return {
      title: String(body['title'] ?? e.statusText ?? 'Error'),
      detail: String(body['detail'] ?? 'Request failed'),
      ...(code ? { code } : {}),
      ...(fields ? { fields } : {}),
      status: e.status,
    };
  }
  return { title: 'Error', detail: String(e instanceof Error ? e.message : e), status: 0 };
}

/**
 * Live port: logical name → same-origin /api request through the app's HttpClient, whose one
 * interceptor (shared/web-core/bff-interceptor.ts) adds X-CSRF to state-changing calls. The
 * browser never holds a token: the gateway BFF is the FAPI 2.0 client (fapi2-design.md I4).
 */
@Injectable()
export class GatewayDataPort extends WidgetDataPort implements PortalAwarePort {
  private readonly http = inject(HttpClient);
  private readonly sources = inject(WIDGET_SOURCES);
  private readonly portal = inject(WIDGET_PORTAL);
  private readonly reauth = inject(WIDGET_REAUTH);

  /** A copy that resolves sources for another portal (a widget host in a given context). */
  forPortal(portal: string): WidgetDataPort {
    if (portal === this.portal) return this;
    const self = this;
    return new (class extends WidgetDataPort {
      query<T>(s: string, p?: Readonly<Record<string, unknown>>) { return self.send<T>(s, p, portal as BusinessPortal); }
      command<T>(c: string, p?: Readonly<Record<string, unknown>>) { return self.send<T>(c, p, portal as BusinessPortal); }
    })();
  }

  query<T>(source: string, params?: Readonly<Record<string, unknown>>): Observable<T> {
    return this.send<T>(source, params, this.portal);
  }
  command<T>(command: string, params?: Readonly<Record<string, unknown>>): Observable<T> {
    return this.send<T>(command, params, this.portal);
  }

  /** The request a name resolves to (the workbench shows it next to each call). */
  describe(name: string, params: Readonly<Record<string, unknown>> = {}, portal: string = this.portal): string {
    const r = resolveRoute(this.sources, name, portal as BusinessPortal);
    if (!r) return `unmapped for ${portal}`;
    try { const b = buildRequest(r, params); return `${b.method} ${b.url}`; } catch (e) { return String(e); }
  }

  private send<T>(name: string, params: Readonly<Record<string, unknown>> = {}, portal: BusinessPortal): Observable<T> {
    return defer(() => {
      const route = resolveRoute(this.sources, name, portal);
      if (!route) {
        return throwError(() => new WidgetProblem({ title: 'No data source', status: 0, code: 'NO_SOURCE',
          detail: `"${name}" has no route for the ${portal} portal` }));
      }
      const req = buildRequest(route, params);
      if (!isSafeApiPath(req.url)) {
        return throwError(() => new WidgetProblem({ title: 'Refused', status: 0, code: 'UNSAFE_PATH',
          detail: `"${req.url}" is not a relative /api/ path` }));
      }
      return this.http.request<T>(req.method, req.url, {
        params: new HttpParams({ fromObject: req.query }),
        body: req.body ?? (req.method === 'GET' ? undefined : {}),
      });
    }).pipe(catchError((e: unknown) => {
      if (e instanceof HttpErrorResponse && e.status === 401) {
        const challenge = e.headers?.get('WWW-Authenticate') ?? '';
        if (/insufficient_user_authentication/.test(challenge)) this.reauth.stepUp(challenge);
        else this.reauth.login();
      }
      return throwError(() => (e instanceof WidgetProblem ? e : new WidgetProblem(problemOf(e))));
    }));
  }
}

/**
 * Standalone port: answers from a fixture scenario, after an optional delay. Answers are
 * deep-copied (widgets may mutate documents in place, as the KYC editor does).
 */
@Injectable()
export class FixtureDataPort extends WidgetDataPort {
  private scenario: Scenario = {};
  latencyMs = 0;

  use(set: FixtureSet | null, scenario: string, override: Scenario = {}, latencyMs = 0) {
    this.scenario = { ...(set?.scenarios[scenario] ?? {}), ...override };
    this.latencyMs = latencyMs;
  }

  query<T>(source: string, params?: Readonly<Record<string, unknown>>): Observable<T> { return this.answer<T>(source, params); }
  command<T>(command: string, params?: Readonly<Record<string, unknown>>): Observable<T> { return this.answer<T>(command, params); }

  private answer<T>(name: string, params: Readonly<Record<string, unknown>> = {}): Observable<T> {
    const go = defer(() => {
      if (!(name in this.scenario)) {
        return throwError(() => new WidgetProblem({ title: 'No fixture', status: 0, code: 'NO_FIXTURE',
          detail: `the scenario has no answer for "${name}"` }));
      }
      const raw = this.scenario[name];
      const v = typeof raw === 'function' ? (raw as (p: typeof params) => unknown)(params) : raw;
      if (isFail(v)) {
        return throwError(() => new WidgetProblem({ status: v.__fail, title: v.problem?.title ?? `HTTP ${v.__fail}`,
          detail: v.problem?.detail ?? `fixture answered ${v.__fail}`, ...(v.problem?.code ? { code: v.problem.code } : {}),
          ...(v.problem?.fields ? { fields: v.problem.fields } : {}) }));
      }
      return of(structuredClone(v) as T);
    });
    return this.latencyMs > 0 ? timer(this.latencyMs).pipe(switchMap(() => go)) : go;
  }
}

/**
 * The port one widget instance sees: refuses names its manifest does not declare (so the
 * registry's permission check covers everything the widget can call), records each call and
 * drives the instance's state (loading → ready, 403 → forbidden, other errors → error).
 */
export class ScopedDataPort extends WidgetDataPort {
  private inflight = 0;
  /** Sources whose last read failed: the widget stays in "error" until each succeeds again. */
  private readonly failing = new Set<string>();

  constructor(private readonly base: WidgetDataPort, private readonly def: WidgetDef<any>,
              private readonly state: WidgetStateRef, private readonly recorder: PortRecorder | null,
              private readonly describe: (name: string, params: Readonly<Record<string, unknown>>) => string) {
    super();
  }

  query<T>(source: string, params: Readonly<Record<string, unknown>> = {}): Observable<T> {
    if (!this.def.sources.includes(source)) return this.undeclared(source, 'sources');
    return this.track(this.base.query<T>(source, params), 'query', source, params);
  }

  command<T>(command: string, params: Readonly<Record<string, unknown>> = {}): Observable<T> {
    if (!this.def.commands.includes(command)) return this.undeclared(command, 'commands');
    return this.track(this.base.command<T>(command, params), 'command', command, params);
  }

  private undeclared<T>(name: string, list: string): Observable<T> {
    const detail = `${this.def.id} calls "${name}", which its manifest does not list in ${list}`;
    this.state.diagnose(name, { name, kind: 'setup', message: `UNDECLARED: ${detail}` });
    return throwError(() => new WidgetProblem({ title: 'Undeclared', status: 0, code: 'UNDECLARED', detail }));
  }

  private track<T>(obs: Observable<T>, kind: PortCall['kind'], name: string, params: Readonly<Record<string, unknown>>): Observable<T> {
    return defer(() => {
      const started = performance.now();
      // self-diagnosis of what we SEND: the body against the command's request contract
      const reqShape = kind === 'command' ? this.def.requests?.[name] : undefined;
      const sent = reqShape ? check(reqShape, params['body']) : null;
      const call = this.recorder?.start({ kind, name, params, target: this.describe(name, params), request: sent }) ?? null;
      const sentErrors = sent?.filter((x) => x.level === 'error') ?? [];
      if (sent?.length) {
        this.state.diagnose(`${name} (request)`, { name: `${name} (request)`, kind: 'request', issues: sent,
          message: sentErrors.length
            ? `the body sent does not match what the server expects (${describeShape(reqShape!)}): ${sentErrors.length} problem(s)`
            : `the body sent has ${sent.length} field(s) the server does not expect` });
      } else if (reqShape) {
        this.state.diagnose(`${name} (request)`, null);
      }
      this.inflight++;
      if (kind === 'query' && !this.failing.size) this.state.busy();
      return obs.pipe(
        tap({
          next: (v) => {
            // self-diagnosis: does the answer match what the widget was built for?
            const shape = this.def.contracts?.[name];
            const issues = shape ? check(shape, v) : null;
            if (call) this.recorder!.finish(call, 200, v, undefined, Math.round(performance.now() - started), issues);
            const errors = issues?.filter((x) => x.level === 'error') ?? [];
            this.state.diagnose(name, issues?.length ? {
              name, kind: 'contract', issues,
              message: errors.length
                ? `the answer does not match the contract (${describeShape(shape!)}): ${errors.length} problem(s)`
                : `the answer has ${issues.length} field(s) the contract does not know`,
            } : null);
            if (kind === 'query') this.failing.delete(name);
          },
          error: (e: unknown) => {
            const p = problemOf(e);
            if (call) this.recorder!.finish(call, p.status, undefined, p, Math.round(performance.now() - started), null);
            // status 0 = the widget's own setup (unmapped, undeclared, no fixture); else the server's answer
            this.state.diagnose(name, { name, kind: p.status === 0 ? 'setup' : 'server',
              message: `${p.code ? p.code + ': ' : ''}${p.detail}${p.status ? ` (HTTP ${p.status})` : ''}` });
            if (kind === 'query') this.failing.add(name);
            // a refused write (422, 409…) is the widget's to explain; a 403 hides it either way
            if (kind === 'query' || p.status === 403) this.state.failed(p);
          },
        }),
        finalize(() => {
          this.inflight--;
          if (this.inflight === 0 && !this.failing.size) this.state.settled();
        }),
      );
    });
  }
}
