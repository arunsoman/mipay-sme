import { Component, Type, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import type { BusinessPortal } from '../../web-core/portal-permission-catalogue';
import { AccessService } from '../../web-core/access';
import { PageSelection, PortRecorder, WIDGET_CONTEXT, WIDGET_DIAGNOSTICS, WidgetContext } from './context';
import { FixedAccess } from './fixed-access';
import { FixtureSet, Scenario } from './fixtures';
import { WidgetDef } from './manifest';
import { PortCall, WidgetDataPort } from './port';
import { FixtureDataPort } from './ports';
import { WidgetHost } from './widget-host';
import { TIER_TEST_WIDTH, Tier } from './grid';

/**
 * jsdom has no layout and no ResizeObserver. The host only uses it for WIDGET_SIZE, so the stub
 * reports the width of the tier the test asks for (RenderOpts.tier, laptop by default).
 */
const G = globalThis as { ResizeObserver?: unknown; __wbTestWidth?: number };
if (typeof G.ResizeObserver === 'undefined' || (G.ResizeObserver as { __wbStub?: boolean }).__wbStub) {
  G.ResizeObserver = class {
    static __wbStub = true;
    constructor(private readonly cb: (e: { contentRect: { width: number } }[]) => void) {}
    observe() { this.cb([{ contentRect: { width: G.__wbTestWidth ?? TIER_TEST_WIDTH.md } }]); }
    unobserve() {}
    disconnect() {}
  };
}

export interface RenderOpts {
  portal: BusinessPortal;
  orgId?: string | null;
  scenario?: string;
  /** Per-name answers that replace the scenario's. */
  override?: Scenario;
  /** Held cells (FixedAccess). */
  cells?: readonly string[];
  selection?: Readonly<Record<string, unknown>>;
  config?: Record<string, unknown>;
  /** The grid tier of the widget's cell (WIDGET_SIZE): 'md' (laptop) unless given. */
  tier?: Tier;
}

export interface RenderedWidget {
  readonly fixture: ComponentFixture<unknown>;
  /** The <mp-widget-host> element. */
  readonly host: HTMLElement;
  readonly access: FixedAccess;
  readonly selection: PageSelection;
  /** Recorded port calls, in order. */
  calls(): readonly PortCall[];
  /** [name, params] of each call - compact for expectations. */
  callList(): Array<[string, Readonly<Record<string, unknown>>]>;
  lastCommand(): PortCall | undefined;
  /** data-widget-state of the host: loading | ready | empty | error | forbidden | denied | unsupported. */
  state(): string | null;
  hidden(): boolean;
  query<E extends Element = HTMLElement>(sel: string): E | null;
  queryAll<E extends Element = HTMLElement>(sel: string): E[];
  text(sel?: string): string;
  click(sel: string): Promise<void>;
  grant(...cells: string[]): Promise<void>;
  revoke(...cells: string[]): Promise<void>;
  select(key: string, value: unknown): Promise<void>;
  settle(): Promise<void>;
}

/**
 * Renders one widget the way a portal page does (WidgetHost, its own injector, a scoped port),
 * but on fixtures and a chosen cell set: no gateway, no session, no HTTP.
 *
 *   const w = await renderWidget(def, fixtures, { portal: 'SUPPLIER', orgId: 'org-1', cells: [...] });
 *   expect(w.callList()).toEqual([['kyc.application.get', { orgId: 'org-1' }], ...]);
 */
export async function renderWidget(def: WidgetDef<any>, fixtures: FixtureSet, opts: RenderOpts): Promise<RenderedWidget> {
  TestBed.resetTestingModule(); // a test may render more than once (e.g. phone, then laptop)
  G.__wbTestWidth = TIER_TEST_WIDTH[opts.tier ?? 'md'];
  const ctx = signal<WidgetContext>({ portal: opts.portal, orgId: opts.orgId ?? null, mode: 'fixture' });

  @Component({
    selector: 'mp-widget-test-host',
    imports: [WidgetHost],
    template: '<mp-widget-host [def]="def" [config]="config" />',
  })
  class TestHost {
    readonly def = def;
    readonly config = opts.config;
  }

  TestBed.configureTestingModule({
    imports: [TestHost],
    providers: [
      FixedAccess,
      { provide: AccessService, useExisting: FixedAccess },
      FixtureDataPort,
      { provide: WidgetDataPort, useExisting: FixtureDataPort },
      PortRecorder,
      PageSelection,
      { provide: WIDGET_CONTEXT, useValue: ctx },
      // tests see the same self-check panel as the harness
      { provide: WIDGET_DIAGNOSTICS, useValue: 'panel' },
    ],
  });
  const access = TestBed.inject(FixedAccess);
  access.set(opts.cells ?? []);
  TestBed.inject(FixtureDataPort).use(fixtures, opts.scenario ?? 'default', opts.override ?? {});
  const selection = TestBed.inject(PageSelection);
  selection.replace(opts.selection ?? {});
  const recorder = TestBed.inject(PortRecorder);

  const fixture = TestBed.createComponent(TestHost as Type<unknown>);
  const el = fixture.nativeElement as HTMLElement;
  const host = () => el.querySelector('mp-widget-host') as HTMLElement;

  /** Waits (max 4 s) until the widget is mounted, no call is pending and it left "loading". */
  const settle = async () => {
    const deadline = Date.now() + 4000;
    let quietSince = 0;
    while (Date.now() < deadline) {
      fixture.detectChanges();
      await fixture.whenStable();
      await new Promise((r) => setTimeout(r, 5));
      fixture.detectChanges();
      const h = host();
      const s = h?.getAttribute('data-widget-state');
      const mounted = !!h && (h.childElementCount > 0 || s === 'denied' || s === 'unsupported');
      if (mounted && recorder.pending() === 0 && s && s !== 'loading') {
        if (!quietSince) quietSince = Date.now();
        else if (Date.now() - quietSince > 30) return;
      } else quietSince = 0;
    }
  };
  await settle();

  return {
    fixture, access, selection,
    get host() { return host(); },
    calls: () => recorder.calls(),
    callList: () => recorder.calls().map((c) => [c.name, c.params] as [string, Readonly<Record<string, unknown>>]),
    lastCommand: () => [...recorder.calls()].reverse().find((c) => c.kind === 'command'),
    state: () => host()?.getAttribute('data-widget-state') ?? null,
    hidden: () => host()?.classList.contains('hidden') ?? true,
    query: <E extends Element>(sel: string) => el.querySelector<E>(sel) as E | null,
    queryAll: <E extends Element>(sel: string) => Array.from(el.querySelectorAll<E>(sel)) as E[],
    text: (sel?: string) => ((sel ? el.querySelector(sel) : el)?.textContent ?? '').replace(/\s+/g, ' ').trim(),
    click: async (sel: string) => {
      const b = el.querySelector<HTMLElement>(sel);
      if (!b) throw new Error(`nothing matches ${sel}`);
      b.click();
      await settle();
    },
    grant: async (...cells: string[]) => { access.grant(...cells); await settle(); },
    revoke: async (...cells: string[]) => { access.revoke(...cells); await settle(); },
    select: async (key: string, value: unknown) => { selection.set(key, value); await settle(); },
    settle,
  };
}
