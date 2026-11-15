import { Observable } from 'rxjs';
import type { ContractIssue } from './shape';

/**
 * THE network path of a widget (docs/plans/portal-builder.md, decision 2). Widgets never touch
 * HttpClient, fetch or storage; they ask the port for a logical source or command:
 *
 *   GatewayDataPort  in a portal: same-origin /api through the gateway BFF (cookie + X-CSRF),
 *                    so a placed widget is FAPI 2.0-compliant by construction;
 *   FixtureDataPort  standalone (workbench, unit tests, builder canvas): fixture scenarios.
 *
 * The widget code is identical in both; only the host's providers differ.
 */
export abstract class WidgetDataPort {
  abstract query<T>(source: string, params?: Readonly<Record<string, unknown>>): Observable<T>;
  abstract command<T>(command: string, params?: Readonly<Record<string, unknown>>): Observable<T>;
}

/** A port that resolves names per portal (the gateway port, the workbench switch). */
export interface PortalAwarePort {
  forPortal(portal: string): WidgetDataPort;
  describe(name: string, params: Readonly<Record<string, unknown>>, portal: string): string;
}

/** A server problem (ProblemDetail + machine code), as every portal screen reads it. */
export interface ApiProblem { title: string; detail: string; code?: string; status: number }

/** What a port throws: always this, never a raw HttpErrorResponse. */
export class WidgetProblem extends Error {
  constructor(readonly problem: ApiProblem) {
    super(problem.detail || problem.title);
  }
  get status() { return this.problem.status; }
}

export function isForbidden(e: unknown): boolean {
  return e instanceof WidgetProblem && e.status === 403;
}

/** One recorded call (workbench network panel, unit tests). */
export interface PortCall {
  readonly seq: number;
  readonly kind: 'query' | 'command';
  readonly name: string;
  readonly params: Readonly<Record<string, unknown>>;
  /** Resolved request (gateway) or "fixture". */
  readonly target: string;
  status: 'pending' | number;
  ms?: number;
  response?: unknown;
  problem?: ApiProblem;
  /** Contract check of the answer: null = the widget declares no contract for this name. */
  contract?: readonly ContractIssue[] | null;
  /** Check of the body a command sent, against its request contract (null = none declared). */
  request?: readonly ContractIssue[] | null;
}

/** What the port found wrong, for the harness's diagnostics panel. */
export interface Diagnostic {
  readonly name: string;
  readonly kind: 'contract' | 'request' | 'setup' | 'server';
  readonly message: string;
  readonly issues?: readonly ContractIssue[];
}
