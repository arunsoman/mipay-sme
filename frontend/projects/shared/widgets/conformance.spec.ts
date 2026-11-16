/**
 * Generated conformance, run for EVERY registered widget (docs/plans/portal-builder.md §10).
 *
 * The widget runs on the LIVE port (GatewayDataPort + the portals' one interceptor), with
 * HttpTestingController standing in for the gateway and answering from the widget's own
 * fixtures. It proves what makes a placed widget FAPI 2.0-compliant through the BFF:
 *   - every request is a relative /api/ URL, mapped in data-sources.ts
 *   - no request ever carries an Authorization header (the browser holds no token)
 *   - every state-changing request carries the session's X-CSRF proof
 *   - a 403 hides the widget; a 401 hands over to the re-login / step-up handler
 */
import { Component, Type, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { HttpRequest, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { WIDGETS, RegisteredWidget } from './registry.generated';
import { SOURCES } from './data-sources';
import { resolveRoute } from './core/sources';
import { isFail } from './core/fixtures';
import { cellsOf } from './core/manifest';
import { FixedAccess } from './core/fixed-access';
import { PageSelection, WIDGET_CONTEXT, WIDGET_PORTAL, WidgetContext } from './core/context';
import { WidgetDataPort } from './core/port';
import { GatewayDataPort, WIDGET_REAUTH } from './core/ports';
import { WidgetHost } from './core/widget-host';
import { AccessService } from '../web-core/access';
import { BrowserSessionService } from '../web-core/session';
import { fapiBffInterceptor } from '../web-core/bff-interceptor';
import type { BusinessPortal } from '../web-core/portal-permission-catalogue';

if (typeof (globalThis as { ResizeObserver?: unknown }).ResizeObserver === 'undefined') {
  (globalThis as { ResizeObserver?: unknown }).ResizeObserver = class { observe() {} unobserve() {} disconnect() {} };
}

const CSRF = 'csrf-test-token';

/** Which source/command a request is, and its params, by matching the portal's route table. */
function identify(req: HttpRequest<unknown>, names: readonly string[], portal: BusinessPortal) {
  for (const name of names) {
    const r = resolveRoute(SOURCES, name, portal);
    if (!r || r.method !== req.method) continue;
    const keys: string[] = [];
    const re = new RegExp('^' + r.path.replace(/[.*+?^$()|[\]\\]/g, '\\$&')
      .replace(/\{(\w+)\}/g, (_m, k: string) => { keys.push(k); return '([^/]+)'; }) + '$');
    const m = re.exec(req.url);
    if (!m) continue;
    const params: Record<string, unknown> = {};
    keys.forEach((k, i) => (params[k] = decodeURIComponent(m[i + 1])));
    for (const k of req.params.keys()) params[k] = req.params.get(k);
    if (req.body !== null && req.body !== undefined) params['body'] = req.body;
    return { name, params };
  }
  return null;
}

async function mount(entry: RegisteredWidget, portal: BusinessPortal, cells: string[]) {
  const ctx = signal<WidgetContext>({ portal, orgId: 'org-conformance', mode: 'live' });
  const reauth = { login: vi.fn(), stepUp: vi.fn() };
  @Component({ selector: 'mp-conf-host', imports: [WidgetHost], template: '<mp-widget-host [def]="def" />' })
  class Host { readonly def = entry.def; }
  TestBed.configureTestingModule({
    imports: [Host],
    providers: [
      provideHttpClient(withInterceptors([fapiBffInterceptor])),
      provideHttpClientTesting(),
      { provide: WIDGET_PORTAL, useValue: portal },
      GatewayDataPort,
      { provide: WidgetDataPort, useExisting: GatewayDataPort },
      { provide: WIDGET_REAUTH, useValue: reauth },
      FixedAccess,
      { provide: AccessService, useExisting: FixedAccess },
      PageSelection,
      { provide: WIDGET_CONTEXT, useValue: ctx },
    ],
  });
  TestBed.inject(BrowserSessionService).current.set({ uid: 'u-1', sub: 's-1', csrf: CSRF, expires_at: 0 });
  TestBed.inject(FixedAccess).set(cells);
  const fixture = TestBed.createComponent(Host as Type<unknown>);
  const http = TestBed.inject(HttpTestingController);
  const seen: HttpRequest<unknown>[] = [];
  return { fixture, http, seen, reauth, host: () => (fixture.nativeElement as HTMLElement).querySelector('mp-widget-host')! };
}

/** Fills every empty text field and unpicked select inside `scope` (a form or a confirmation in front of a write). */
function fillEmpty(scope: HTMLElement) {
  for (const i of scope.querySelectorAll<HTMLInputElement>('input:not([type="checkbox"]):not([type="radio"])')) {
    if (i.value) continue;
    i.value = 'conformance';
    i.dispatchEvent(new Event('input'));
  }
  for (const sel of scope.querySelectorAll<HTMLSelectElement>('select')) {
    if (sel.value) continue;
    const first = [...sel.options].find((o) => o.value);
    if (!first) continue;
    sel.value = first.value;
    sel.dispatchEvent(new Event('change'));
  }
}

/** The widget's lazy chunk has loaded and its component is in the DOM (max 5 s). */
async function mounted(m: Awaited<ReturnType<typeof mount>>) {
  const deadline = Date.now() + 3000;
  while (Date.now() < deadline && m.host().childElementCount === 0) {
    m.fixture.detectChanges();
    await m.fixture.whenStable();
    await new Promise((r) => setTimeout(r, 5));
  }
}

/** The first request the widget sends (max 5 s). */
async function firstRequest(m: Awaited<ReturnType<typeof mount>>) {
  await mounted(m);
  const deadline = Date.now() + 3000;
  while (Date.now() < deadline) {
    m.fixture.detectChanges();
    await new Promise((r) => setTimeout(r, 5));
    const [t] = m.http.match(() => true);
    if (t) return t;
  }
  throw new Error('the widget sent no request');
}

/** Answer every pending request from the fixture scenario until the widget is quiet. */
async function serve(m: Awaited<ReturnType<typeof mount>>, entry: RegisteredWidget, portal: BusinessPortal,
                     answer: (name: string) => { status: number; body: unknown } | null = () => null) {
  const set = await entry.fixtures();
  const names = [...entry.def.sources, ...entry.def.commands];
  await mounted(m);
  const deadline = Date.now() + 3000;
  let quiet = 0;
  while (Date.now() < deadline && quiet < 4) {
    m.fixture.detectChanges();
    await m.fixture.whenStable();
    await new Promise((r) => setTimeout(r, 5));
    const pending = m.http.match(() => true);
    quiet = pending.length ? 0 : quiet + 1;
    for (const t of pending) {
      m.seen.push(t.request);
      const id = identify(t.request, names, portal);
      expect(id, `${t.request.method} ${t.request.url} is not a route of ${entry.def.id}`).not.toBeNull();
      const forced = answer(id!.name);
      if (forced) { t.flush(forced.body as object | null, { status: forced.status, statusText: String(forced.status) }); continue; }
      const raw = set.scenarios.default[id!.name];
      const v = typeof raw === 'function' ? (raw as (p: unknown) => unknown)(id!.params) : raw;
      if (isFail(v)) t.flush(v.problem ?? {}, { status: v.__fail, statusText: 'fixture' });
      else t.flush(structuredClone(v) as object);
    }
  }
  m.fixture.detectChanges();
}

describe.each(WIDGETS.map((w) => [w.def.id, w] as const))('conformance: %s', (_id, entry) => {
  const portal = entry.def.contexts[0];

  it('reaches the API only through same-origin /api URLs with no Authorization header', async () => {
    const m = await mount(entry, portal, cellsOf(entry.def));
    await serve(m, entry, portal);
    expect(m.seen.length).toBeGreaterThan(0);
    for (const r of m.seen) {
      expect(r.url.startsWith('/api/') && !r.url.startsWith('//'), r.url).toBe(true);
      expect(r.headers.has('Authorization'), `${r.method} ${r.url}`).toBe(false);
    }
    expect(m.host().getAttribute('data-widget-state')).toBe('ready');
  });

  it('sends X-CSRF on every state-changing request its actions make', async () => {
    const m = await mount(entry, portal, cellsOf(entry.def));
    await serve(m, entry, portal);
    const root = m.fixture.nativeElement as HTMLElement;
    let reached = 0;
    for (const action of entry.def.actions) {
      let el = root.querySelector<HTMLElement>(`[data-action="${action.id}"]`);
      if (!el) {
        // actions on a selected row (queues): pick the first row, then look again
        root.querySelector<HTMLElement>('mp-data-table tbody tr')?.click();
        await serve(m, entry, portal);
        el = root.querySelector<HTMLElement>(`[data-action="${action.id}"]`);
      }
      if (!el) continue; // offered only in some states (e.g. withdraw); covered by the widget's own spec
      // an organism carrying the action (approval-actions): press its last button (the primary one)
      const press = el.matches('button') ? el : ([...el.querySelectorAll<HTMLElement>('button')].pop() ?? el);
      const before = m.seen.length;
      press.click();
      await serve(m, entry, portal);
      if (!m.seen.slice(before).some((r) => r.method !== 'GET')) {
        // a form or a confirmation in front of the write: fill what it asks for, then press the action there
        const modal = root.querySelector<HTMLElement>(`mp-modal [data-action="${action.id}"]`)?.closest<HTMLElement>('mp-modal');
        const scope = modal ?? (el.matches('mp-entity-form') ? el : null);
        if (scope) {
          fillEmpty(scope);
          await serve(m, entry, portal);
          const target = modal ? modal.querySelector<HTMLElement>(`[data-action="${action.id}"]`)! : el;
          const again = target.matches('button') ? target : ([...target.querySelectorAll<HTMLElement>('button')].pop() ?? target);
          again.click();
          await serve(m, entry, portal);
        }
      }
      if (m.seen.slice(before).some((r) => r.method !== 'GET')) reached++;
      const writes = m.seen.slice(before).filter((r) => !['GET', 'HEAD', 'OPTIONS'].includes(r.method));
      for (const w of writes) {
        expect(w.headers.get('X-CSRF'), `${w.method} ${w.url}`).toBe(CSRF);
        expect(w.headers.has('Authorization')).toBe(false);
      }
    }
    // every widget with write actions must have at least one reachable here, or this test proves nothing
    if (entry.def.actions.some((a) => a.command)) expect(reached, 'no write action was reachable').toBeGreaterThan(0);
  });

  it('hides itself when the gateway answers 403', async () => {
    const m = await mount(entry, portal, cellsOf(entry.def));
    await serve(m, entry, portal, () => ({ status: 403, body: { title: 'Forbidden', detail: 'denied' } }));
    expect(m.host().getAttribute('data-widget-state')).toBe('forbidden');
    expect(m.host().classList.contains('hidden')).toBe(true);
  });

  it('hands a 401 to re-login, and an RFC 9470 challenge to step-up', async () => {
    const m = await mount(entry, portal, cellsOf(entry.def));
    const first = await firstRequest(m);
    first.flush({ error: 'login_required' }, { status: 401, statusText: 'Unauthorized' });
    expect(m.reauth.login).toHaveBeenCalled();

    const m2 = await (TestBed.resetTestingModule(), mount(entry, portal, cellsOf(entry.def)));
    const again = await firstRequest(m2);
    again.flush({}, { status: 401, statusText: 'Unauthorized',
      headers: { 'WWW-Authenticate': 'DPoP error="insufficient_user_authentication", acr_values="aal2"' } });
    expect(m2.reauth.stepUp).toHaveBeenCalled();
  });
});
