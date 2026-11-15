/**
 * The widget manifest (docs/plans/portal-builder.md §1): what a widget is, what it needs, what
 * it may do and how big it is. Pure data - no Angular at runtime - so the registry tool, the
 * registry server, the builder palette and the RBAC model all read it without loading the
 * component. The component itself is behind `load` (a lazy chunk).
 *
 * `defineWidget` is the only way to make one; its types turn a missing size, an unknown config
 * type or a malformed action into a build error, and it checks the rest when the module loads.
 */
import type { Type } from '@angular/core';
import type { BusinessPortal } from '../../web-core/portal-permission-catalogue';
import type { Tier, TierSize } from './grid';
import type { MiPayWidget } from './mipay-widget';
import type { Shape } from './shape';

export { span, TIERS, COLS } from './grid';
export type { Tier, TierSize } from './grid';

/** Rows (any verb) or exact cells ("row:verb"), OR semantics like menu.ts - or ALL of a list. */
export type Requires = readonly string[] | { readonly all: readonly string[] };

/** A named, gated action inside the widget (shown by the RBAC screen as "<label>: allow"). */
export interface WidgetAction {
  /** Stable id; the element carrying it has `data-action="<id>"`. */
  readonly id: string;
  readonly label: string;
  /** The exact cell that shows the action ("row:verb"). */
  readonly cell: string;
  /** The logical command it sends, when it sends one. */
  readonly command?: string;
}

interface FieldBase { readonly label?: string; readonly help?: string }
export interface NumberField extends FieldBase { readonly type: 'number'; readonly default: number; readonly min?: number; readonly max?: number }
export interface BooleanField extends FieldBase { readonly type: 'boolean'; readonly default: boolean }
export interface StringField extends FieldBase { readonly type: 'string'; readonly default: string }
export interface EnumField<T extends string> extends FieldBase { readonly type: 'enum'; readonly options: readonly T[]; readonly default: T }
export type ConfigField = NumberField | BooleanField | StringField | EnumField<string>;

type FieldFor<T> =
  [T] extends [boolean] ? BooleanField
    : [T] extends [number] ? NumberField
      : [T] extends [string] ? (string extends T ? StringField : EnumField<T>)
        : never;

/** One field per config key, typed by the key's value type (a union of strings = an enum).
 *  `WidgetDef<any>` (the registry's erased form) accepts any field per key. */
export type ConfigSchema<C> = 0 extends (1 & C)
  ? Readonly<Record<string, ConfigField>>
  : { readonly [K in keyof C]-?: FieldFor<C[K]> };

/** Where a widget input comes from: the first non-empty of `selection.<key>` / `context.<key>`. */
export interface WidgetInput { readonly from: readonly string[]; readonly label?: string }

export type WidgetState = 'loading' | 'ready' | 'empty' | 'error' | 'forbidden';

export interface WidgetDef<C = Record<string, unknown>> {
  /** "<area>.<name>", lower case - stable forever (layouts and RBAC refer to it). */
  readonly id: string;
  /** semver; layouts pin the major. */
  readonly version: string;
  readonly label: string;
  readonly description: string;
  readonly category: string;
  /** The portals the widget works in. */
  readonly contexts: readonly BusinessPortal[];
  readonly requires: Requires;
  readonly actions: readonly WidgetAction[];
  /** Logical read sources (shared/widgets/data-sources.ts) - never URLs. */
  readonly sources: readonly string[];
  /** Logical writes. */
  readonly commands: readonly string[];
  /**
   * What the server must send for each source / command (shape.ts). Every answer is checked
   * against it at runtime and mismatches are shown in the test harness. Required for every
   * source (widgets:check); generate them with `npm run widgets:contract`.
   */
  readonly contracts?: Readonly<Record<string, Shape>>;
  /**
   * What each command SENDS (its request body). The port checks every outgoing body against it
   * and the harness shows a mismatch before the server refuses it. Required for every command
   * that sends a body (POST / PUT / PATCH); generate with `npm run widgets:contract -- --request`.
   */
  readonly requests?: Readonly<Record<string, Shape>>;
  readonly inputs?: Readonly<Record<string, WidgetInput>>;
  /** Page-selection keys this widget writes (e.g. 'orgId' for a directory). */
  readonly publishes?: readonly string[];
  readonly config: ConfigSchema<C>;
  /** Required for EVERY tier (decision 8). */
  readonly size: Readonly<Record<Tier, TierSize>>;
  /** Optional minimum height (rem) for charts and tiles. */
  readonly minHeight?: number;
  readonly hideOn?: readonly ('forbidden' | 'empty')[];
  /** The component: it MUST extend MiPayWidget<C> (a plain component does not compile here). */
  readonly load: () => Promise<Type<MiPayWidget<C>>>;
}

const ID = /^[a-z][a-z0-9]*(\.[a-z][a-z0-9-]*)+$/;
const SEMVER = /^\d+\.\d+\.\d+$/;
const CELL = /^[a-z]+\.[a-z_]+:[a-z]+$/;
const ROW = /^[a-z]+\.[a-z_]+$/;

/** Problems `defineWidget` finds beyond what the types catch (also run by the registry tool). */
export function manifestProblems(def: WidgetDef<any>): string[] {
  const p: string[] = [];
  if (!ID.test(def.id)) p.push(`id "${def.id}" must look like "area.name" (lower case)`);
  if (!SEMVER.test(def.version)) p.push(`version "${def.version}" must be semver (1.0.0)`);
  if (!def.label.trim()) p.push('label is empty');
  if (!def.contexts.length) p.push('contexts is empty: name at least one portal');
  const req = requiredList(def);
  if (!req.length) p.push('requires is empty: every widget is gated by at least one permission');
  for (const r of req) if (!CELL.test(r) && !ROW.test(r)) p.push(`requires "${r}" is neither a row nor a row:verb cell`);
  const ids = new Set<string>();
  for (const a of def.actions) {
    if (ids.has(a.id)) p.push(`action id "${a.id}" is used twice`);
    ids.add(a.id);
    if (!CELL.test(a.cell)) p.push(`action "${a.id}" cell "${a.cell}" must be an exact row:verb cell`);
    if (a.command && !def.commands.includes(a.command)) p.push(`action "${a.id}" sends "${a.command}", which is not in commands`);
  }
  for (const [k, f] of Object.entries(def.config) as [string, ConfigField][]) {
    if (f.type === 'enum' && !f.options.includes(f.default)) p.push(`config.${k}: default "${f.default}" is not an option`);
    if (f.type === 'number' && ((f.min ?? -Infinity) > f.default || (f.max ?? Infinity) < f.default)) p.push(`config.${k}: default is outside min..max`);
  }
  for (const k of Object.keys(def.requests ?? {})) {
    if (!def.commands.includes(k)) p.push(`request contract "${k}" is not a command of the widget`);
  }
  for (const k of Object.keys(def.contracts ?? {})) {
    if (!def.sources.includes(k) && !def.commands.includes(k)) p.push(`contract "${k}" is not a source or command of the widget`);
  }
  for (const [k, i] of Object.entries(def.inputs ?? {})) {
    if (!i.from.length) p.push(`inputs.${k}: from is empty`);
    for (const f of i.from) if (!/^(selection|context)\.[a-zA-Z]+$/.test(f)) p.push(`inputs.${k}: "${f}" must be selection.<key> or context.<key>`);
  }
  return p;
}

/** The only constructor. Throws on load when the manifest is inconsistent. */
export function defineWidget<C>(def: WidgetDef<C>): WidgetDef<C> {
  const problems = manifestProblems(def as WidgetDef<any>);
  if (problems.length) throw new Error(`widget ${def.id}: ${problems.join('; ')}`);
  return Object.freeze(def);
}

/** The flat list of permissions in `requires`. */
export function requiredList(def: Pick<WidgetDef<any>, 'requires'>): readonly string[] {
  return Array.isArray(def.requires) ? def.requires : (def.requires as { all: readonly string[] }).all;
}

/** Is the widget allowed for someone whose `can` answers like AccessService.can? */
export function requiresMet(def: Pick<WidgetDef<any>, 'requires'>, can: (perm: string[]) => boolean): boolean {
  const r = def.requires;
  if (Array.isArray(r)) return r.length > 0 && can([...r]);
  const all = (r as { all: readonly string[] }).all;
  return all.length > 0 && all.every((p) => can([p]));
}

/** Every cell the widget names (requires + actions): what the RBAC screen lists for it. */
export function cellsOf(def: Pick<WidgetDef<any>, 'requires' | 'actions'>): string[] {
  return [...new Set([...requiredList(def), ...def.actions.map((a) => a.cell)])];
}

/** Config defaults, overridden by a layout's values. */
export function configWithDefaults<C>(def: WidgetDef<C>, given?: Partial<C>): C {
  const out: Record<string, unknown> = {};
  for (const [k, f] of Object.entries(def.config) as [string, ConfigField][]) out[k] = f.default;
  return { ...out, ...(given ?? {}) } as C;
}
