import { Injectable, InjectionToken, Signal, computed, signal } from '@angular/core';
import type { BusinessPortal } from '../../web-core/portal-permission-catalogue';
import type { WidgetDef, WidgetState } from './manifest';
import type { ApiProblem, Diagnostic, PortCall } from './port';
import type { Tier } from './grid';
import { SOURCES } from '../data-sources';
import type { SourceMap } from './sources';

/** Where a widget is placed. */
export interface WidgetContext {
  readonly portal: BusinessPortal;
  /** The signed-in user's own org. */
  readonly orgId: string | null;
  readonly orgType?: string | null;
  /** "<portal>/<menu>/<screen>" when on a portal page. */
  readonly screenKey?: string | null;
  readonly routeParams?: Readonly<Record<string, string>>;
  readonly mode: 'live' | 'design' | 'fixture';
}

/** Provided by the page (or workbench frame) around the widget hosts. */
export const WIDGET_CONTEXT = new InjectionToken<Signal<WidgetContext>>('WIDGET_CONTEXT');

/** The portal an app-level GatewayDataPort resolves sources for. */
export const WIDGET_PORTAL = new InjectionToken<BusinessPortal>('WIDGET_PORTAL');

/** The source map (tests may swap it). */
export const WIDGET_SOURCES = new InjectionToken<SourceMap>('WIDGET_SOURCES', { providedIn: 'root', factory: () => SOURCES });

/**
 * How a widget reports what its port found wrong (contract mismatches, unmapped or undeclared
 * names, missing fixtures, server errors): 'panel' draws it inside the widget's cell (the test
 * harness), 'console' logs it (portals), 'off' stays silent.
 */
export const WIDGET_DIAGNOSTICS = new InjectionToken<'panel' | 'console' | 'off'>('WIDGET_DIAGNOSTICS', {
  providedIn: 'root', factory: () => 'console',
});

/** Per widget instance. */
export const WIDGET_DEF = new InjectionToken<WidgetDef<any>>('WIDGET_DEF');
export const WIDGET_CONFIG = new InjectionToken<Readonly<Record<string, unknown>>>('WIDGET_CONFIG');
export const WIDGET_INPUTS = new InjectionToken<Readonly<Record<string, Signal<unknown>>>>('WIDGET_INPUTS');
export const WIDGET_SIZE = new InjectionToken<Signal<{ tier: Tier; widthPx: number }>>('WIDGET_SIZE');

/**
 * Page-level selection shared by the widgets of one page: a directory widget publishes
 * `orgId`, the KYC widget reads it (its `inputs.orgId.from` names `selection.orgId`).
 */
@Injectable()
export class PageSelection {
  private readonly values = signal<Readonly<Record<string, unknown>>>({});
  readonly all = this.values.asReadonly();
  get(key: string): Signal<unknown> { return computed(() => this.values()[key] ?? null); }
  set(key: string, value: unknown) { this.values.update((v) => ({ ...v, [key]: value })); }
  replace(values: Readonly<Record<string, unknown>>) { this.values.set({ ...values }); }
}

/** A widget instance's state: the host shows / hides by it; tests and the workbench read it. */
@Injectable()
export class WidgetStateRef {
  private readonly s = signal<WidgetState>('loading');
  private readonly d = signal<readonly Diagnostic[]>([]);
  /** Latest finding per source / command (a later clean answer clears it). */
  readonly diagnostics = this.d.asReadonly();
  diagnose(name: string, found: Diagnostic | null) {
    this.d.update((list) => [...list.filter((x) => x.name !== name), ...(found ? [found] : [])]);
  }
  private readonly p = signal<ApiProblem | null>(null);
  readonly state = this.s.asReadonly();
  readonly problem = this.p.asReadonly();
  /** Set by the widget itself; automatic tracking stops overriding it to "ready". */
  private claimed = false;

  set(state: WidgetState, problem: ApiProblem | null = null) {
    // a 403 is final for this load: a widget's own catch-all `state.set('error')` must not un-hide it
    if (this.s() === 'forbidden' && state === 'error') return;
    this.claimed = state !== 'loading';
    this.s.set(state);
    this.p.set(problem);
  }
  /** Port tracking: a call started. */
  busy() { if (!this.claimed && this.s() !== 'forbidden') this.s.set('loading'); }
  /** Port tracking: every call settled fine. */
  settled() { if (!this.claimed && this.s() === 'loading') this.s.set('ready'); }
  /** Port tracking: a call failed. 403 is final; other errors unless the widget claimed a state. */
  failed(problem: ApiProblem) {
    if (problem.status === 403) { this.s.set('forbidden'); this.p.set(problem); return; }
    if (!this.claimed) { this.s.set('error'); this.p.set(problem); }
  }
}

/** Collects the calls of the widgets under it (workbench network panel, unit tests). */
@Injectable()
export class PortRecorder {
  private seq = 0;
  private readonly list = signal<PortCall[]>([]);
  readonly calls = this.list.asReadonly();
  readonly pending = computed(() => this.list().filter((c) => c.status === 'pending').length);

  start(c: Omit<PortCall, 'seq' | 'status' | 'request'> & { request?: PortCall['request'] }): PortCall {
    const call: PortCall = { ...c, seq: ++this.seq, status: 'pending' };
    this.list.update((l) => [...l, call]);
    return call;
  }
  finish(call: PortCall, status: number, response?: unknown, problem?: ApiProblem, ms?: number,
         contract?: PortCall['contract']) {
    this.list.update((l) => l.map((c) => (c.seq === call.seq ? { ...c, status, response, problem, ms, contract } : c)));
  }
  clear() { this.list.set([]); }
}
