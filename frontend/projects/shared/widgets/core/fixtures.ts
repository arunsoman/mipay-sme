/**
 * Fixture scenarios: canned answers for a widget's sources and commands, so it runs standalone
 * (workbench, unit tests, builder canvas) with no gateway and no session.
 *
 * Every widget ships at least `default`, `empty` and `error` (decision 9) - the type requires
 * them. A value can be data, `fail(status, problem)`, or a function of the call's params.
 *
 * Pure TypeScript: the registry tool reads the scenario names for the layout test matrix.
 */
import type { WidgetState } from './manifest';

export interface FixtureFail {
  readonly __fail: number;
  readonly problem?: {
    code?: string;
    title?: string;
    detail?: string;
    fields?: Record<string, string>;
  };
}
export type FixtureAnswer = object | string | number | boolean | null;
export type FixtureFn = (params: Readonly<Record<string, unknown>>) => FixtureAnswer;
/** Data, fail(…), or a function of the call's params (path params, query params and `body`). */
export type FixtureValue = FixtureAnswer | FixtureFn;
/** Source / command name → answer. */
export type Scenario = Readonly<Record<string, FixtureValue>>;

export interface Scenarios {
  readonly default: Scenario;
  readonly empty: Scenario;
  readonly error: Scenario;
  readonly [name: string]: Scenario;
}

export interface FixtureSet {
  readonly scenarios: Scenarios;
  /** The state each scenario must settle in (layout tests check it). Defaults below. */
  readonly expect: Readonly<Record<string, WidgetState>>;
}

const DEFAULT_EXPECT: Record<string, WidgetState> = { default: 'ready', empty: 'empty', error: 'error' };

export function defineFixtures(scenarios: Scenarios, opts: { expect?: Record<string, WidgetState> } = {}): FixtureSet {
  const expect: Record<string, WidgetState> = {};
  for (const name of Object.keys(scenarios)) expect[name] = opts.expect?.[name] ?? DEFAULT_EXPECT[name] ?? 'ready';
  return { scenarios, expect };
}

export function fail(status: number, problem?: FixtureFail['problem']): FixtureFail {
  return { __fail: status, problem };
}

export function isFail(v: unknown): v is FixtureFail {
  return !!v && typeof v === 'object' && '__fail' in (v as object);
}
