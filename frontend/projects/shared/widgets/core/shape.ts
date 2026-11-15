/**
 * Response contracts: what a widget expects the server to send for each source.
 *
 * The widget port checks every answer against its contract (ScopedDataPort) and reports the
 * difference - "$.items[0].stageSeq: expected integer, got "2" (string)" - in the test harness
 * (WIDGET_DIAGNOSTICS = 'panel') or the console (portals). The fixtures are checked against the
 * same contract by `widgets:check`, so canned data cannot drift from what the server says.
 *
 * Contracts are generated from the backend's Java records by
 *   npm run widgets:contract -- GET /api/kyc/tasks --name kyc.tasks.list --out <widget>.contract.ts
 * and `Infer<typeof SHAPE>` gives the TypeScript type, so there is one definition, not two.
 *
 * Pure TypeScript (no Angular): the registry tool runs it in Node.
 */

export type Shape =
  | { readonly kind: 'string'; readonly format?: 'date-time' | 'date' }
  | { readonly kind: 'number'; readonly integer?: boolean }
  | { readonly kind: 'boolean' }
  | { readonly kind: 'enum'; readonly values: readonly string[] }
  | { readonly kind: 'array'; readonly of: Shape }
  | { readonly kind: 'map'; readonly of: Shape }
  | { readonly kind: 'object'; readonly name?: string; readonly fields: Readonly<Record<string, Shape>> }
  | { readonly kind: 'nullable'; readonly of: Shape }
  | { readonly kind: 'any' };

interface StringS { readonly kind: 'string'; readonly format?: 'date-time' | 'date' }
interface NumberS { readonly kind: 'number'; readonly integer?: boolean }
interface BooleanS { readonly kind: 'boolean' }
interface EnumS<V extends string> { readonly kind: 'enum'; readonly values: readonly V[] }
interface ArrayS<T extends Shape> { readonly kind: 'array'; readonly of: T }
interface MapS<T extends Shape> { readonly kind: 'map'; readonly of: T }
interface ObjectS<F extends Record<string, Shape>> { readonly kind: 'object'; readonly name?: string; readonly fields: F }
interface NullableS<T extends Shape> { readonly kind: 'nullable'; readonly of: T }
interface AnyS { readonly kind: 'any' }

/** The TypeScript type a shape describes. */
export type Infer<S> =
  S extends NullableS<infer T> ? Infer<T> | null
    : S extends StringS ? string
      : S extends NumberS ? number
        : S extends BooleanS ? boolean
          : S extends EnumS<infer V> ? V
            : S extends ArrayS<infer T> ? Infer<T>[]
              : S extends MapS<infer T> ? Record<string, Infer<T>>
                : S extends ObjectS<infer F> ? { [K in keyof F]: Infer<F[K]> }
                  : unknown;

/** Shape builders. Generated contracts use these; hand-written ones should too. */
export const s = {
  string: (): StringS => ({ kind: 'string' }),
  dateTime: (): StringS => ({ kind: 'string', format: 'date-time' }),
  date: (): StringS => ({ kind: 'string', format: 'date' }),
  number: (): NumberS => ({ kind: 'number' }),
  integer: (): NumberS => ({ kind: 'number', integer: true }),
  boolean: (): BooleanS => ({ kind: 'boolean' }),
  enum: <V extends string>(...values: V[]): EnumS<V> => ({ kind: 'enum', values }),
  array: <T extends Shape>(of: T): ArrayS<T> => ({ kind: 'array', of }),
  map: <T extends Shape>(of: T): MapS<T> => ({ kind: 'map', of }),
  object: <F extends Record<string, Shape>>(name: string, fields: F): ObjectS<F> => ({ kind: 'object', name, fields }),
  nullable: <T extends Shape>(of: T): NullableS<T> => ({ kind: 'nullable', of }),
  /** Free-form JSON (a Map<String,Object> or JsonNode on the server): only "present" is checked. */
  any: (): AnyS => ({ kind: 'any' }),
};

export interface ContractIssue {
  /** JSON path, e.g. $[0].treePath[1].depth */
  readonly path: string;
  /** error: the widget cannot rely on the value; warning: the server sends more than the contract knows. */
  readonly level: 'error' | 'warning';
  readonly expected: string;
  readonly got: string;
}

const MAX_ISSUES = 40;

export function describe(sh: Shape): string {
  switch (sh.kind) {
    case 'nullable': return `${describe(sh.of)} | null`;
    case 'string': return sh.format ? `string (${sh.format})` : 'string';
    case 'number': return sh.integer ? 'integer' : 'number';
    case 'enum': return `one of ${sh.values.map((v) => JSON.stringify(v)).join(', ')}`;
    case 'array': return `array of ${describe(sh.of)}`;
    case 'map': return `map of ${describe(sh.of)}`;
    case 'object': return sh.name ? `object ${sh.name}` : 'object';
    default: return sh.kind;
  }
}

function gotOf(v: unknown): string {
  if (v === undefined) return 'nothing (field missing)';
  if (v === null) return 'null';
  if (Array.isArray(v)) return `array (${v.length})`;
  if (typeof v === 'object') return 'object';
  const j = JSON.stringify(v);
  return `${j.length > 40 ? j.slice(0, 40) + '…' : j} (${typeof v})`;
}

/** Every difference between a value and its contract (empty = matches). */
export function check(sh: Shape, value: unknown, path = '$', out: ContractIssue[] = []): ContractIssue[] {
  if (out.length >= MAX_ISSUES) return out;
  const err = (expected: string) => out.push({ path, level: 'error', expected, got: gotOf(value) });
  if (sh.kind === 'nullable') {
    if (value === null) return out;
    if (value === undefined) { err(describe(sh)); return out; }
    const before = out.length;
    check(sh.of, value, path, out);
    // a wrong value here: say that null would also have been fine
    for (let i = before; i < out.length; i++) if (out[i].path === path) out[i] = { ...out[i], expected: `${out[i].expected} | null` };
    return out;
  }
  if (sh.kind === 'any') { if (value === undefined) err('a value'); return out; }
  if (value === null || value === undefined) { err(describe(sh)); return out; }
  switch (sh.kind) {
    case 'string':
      if (typeof value !== 'string') err(describe(sh));
      else if (sh.format === 'date-time' && Number.isNaN(Date.parse(value))) err('an ISO date-time');
      else if (sh.format === 'date' && !/^\d{4}-\d{2}-\d{2}$/.test(value)) err('a date (yyyy-mm-dd)');
      break;
    case 'number':
      if (typeof value !== 'number' || Number.isNaN(value)) err(describe(sh));
      else if (sh.integer && !Number.isInteger(value)) err('integer');
      break;
    case 'boolean':
      if (typeof value !== 'boolean') err('boolean');
      break;
    case 'enum':
      if (typeof value !== 'string' || !sh.values.includes(value)) err(describe(sh));
      break;
    case 'array':
      if (!Array.isArray(value)) { err(describe(sh)); break; }
      value.forEach((v, i) => check(sh.of, v, `${path}[${i}]`, out));
      break;
    case 'map':
      if (typeof value !== 'object' || Array.isArray(value)) { err(describe(sh)); break; }
      for (const [k, v] of Object.entries(value as object)) check(sh.of, v, `${path}.${k}`, out);
      break;
    case 'object': {
      if (typeof value !== 'object' || Array.isArray(value)) { err(describe(sh)); break; }
      const obj = value as Record<string, unknown>;
      for (const [k, f] of Object.entries(sh.fields)) check(f, obj[k], `${path}.${k}`, out);
      for (const k of Object.keys(obj)) {
        if (!(k in sh.fields) && out.length < MAX_ISSUES) {
          out.push({ path: `${path}.${k}`, level: 'warning', expected: `no field "${k}" in ${describe(sh)}`, got: gotOf(obj[k]) });
        }
      }
      break;
    }
  }
  return out;
}

/** A plausible value for a shape, from its field names (seed for fixtures; replace with real data). */
export function sample(sh: Shape, field = '', i = 1): unknown {
  switch (sh.kind) {
    case 'nullable': return sample(sh.of, field, i);
    case 'any': return {};
    case 'boolean': return /^(is|can|has)/.test(field) ? i % 2 === 0 : false;
    case 'number': return /seq|depth|count|version/i.test(field) ? i : 100 * i;
    case 'enum': return sh.values[(i - 1) % sh.values.length];
    case 'array': return [sample(sh.of, field, 1), sample(sh.of, field, 2)];
    case 'map': return { key: sample(sh.of, field, i) };
    case 'object': {
      const o: Record<string, unknown> = {};
      for (const [k, f] of Object.entries(sh.fields)) o[k] = sample(f, k, i);
      return o;
    }
    case 'string':
      if (sh.format === 'date-time') return `2026-11-${String(10 + i).padStart(2, '0')}T09:00:00Z`;
      if (sh.format === 'date') return `2026-11-${String(10 + i).padStart(2, '0')}`;
      if (/^id$|Id$/.test(field)) return `${field.replace(/Id$/, '') || 'item'}-${i}`;
      if (/name/i.test(field)) return `Sample ${field.replace(/Name$/i, '') || 'name'} ${i}`;
      if (/status/i.test(field)) return 'PENDING';
      return `${field || 'text'} ${i}`;
  }
}
