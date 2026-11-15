/**
 * The mobile-first page grid (docs/plans/portal-builder.md, decision 8).
 *
 * Tiers are picked by the width of the page's CONTENT AREA (CSS container queries), not the
 * viewport: the same page lays out the same whether the sidebar is open, in a builder frame or
 * in the RBAC "preview as role" pane.
 *
 * Every widget declares its span for EVERY tier with `span.*`. The types make mistakes build
 * errors: a missing tier, a span wider than the tier's grid, a default below its min or a max
 * below its default.
 *
 * Pure TypeScript (no Angular): read by the Node registry tool and the RBAC model too.
 */
export type Tier = 'xs' | 'sm' | 'md' | 'lg';
export const TIERS: readonly Tier[] = ['xs', 'sm', 'md', 'lg'];

/** Columns per tier. */
export const COLS = { xs: 4, sm: 8, md: 12, lg: 12 } as const;

/** Smallest content width (px) of each tier; `xs` starts at 0. */
export const TIER_MIN_WIDTH: Record<Tier, number> = { xs: 0, sm: 600, md: 960, lg: 1280 };

/** The narrowest content width each tier is checked at (layout tests, workbench frames). */
export const TIER_TEST_WIDTH: Record<Tier, number> = { xs: 360, sm: 600, md: 960, lg: 1280 };

/** 1..N as a union of number literals. */
export type Nums<N extends number, A extends unknown[] = [unknown]> =
  A['length'] extends N ? N : A['length'] | Nums<N, [...A, unknown]>;

/** Lo..Hi as a union of number literals. */
export type Between<Lo extends number, Hi extends number> = Exclude<Nums<Hi>, Exclude<Nums<Lo>, Lo>>;

export interface Span { readonly kind: 'span'; readonly min: number; readonly default: number; readonly max: number }
export interface Hidden { readonly kind: 'hidden'; readonly reason: string }
export type TierSize = Span | Hidden;

/** span.<tier>(min) · (min, default) · (min, default, max) - each bound checked against the last. */
export interface TierFn<C extends number> {
  <Min extends Nums<C>>(min: Min): Span;
  <Min extends Nums<C>, Def extends Between<Min, C>>(min: Min, def: Def): Span;
  <Min extends Nums<C>, Def extends Between<Min, C>, Max extends Between<Def, C>>(min: Min, def: Def, max: Max): Span;
}

function tier<C extends number>(cols: C): TierFn<C> {
  return ((min: number, def?: number, max?: number): Span =>
    ({ kind: 'span', min, default: def ?? min, max: max ?? cols })) as TierFn<C>;
}

/**
 *   size: { xs: span.xs(4), sm: span.sm(4, 8), md: span.md(6, 8, 12), lg: span.lg(4, 6) }
 *
 * `span.<tier>(min, default = min, max = all columns)`. `span.hidden(reason)` takes the widget
 * off that tier; the reason is shown in the builder (a layout choice, never a permission).
 */
export const span = {
  xs: tier(COLS.xs),
  sm: tier(COLS.sm),
  md: tier(COLS.md),
  lg: tier(COLS.lg),
  hidden: (reason: string): Hidden => ({ kind: 'hidden', reason }),
};

/** The tier a content width falls into. */
export function tierOf(widthPx: number): Tier {
  if (widthPx >= TIER_MIN_WIDTH.lg) return 'lg';
  if (widthPx >= TIER_MIN_WIDTH.md) return 'md';
  if (widthPx >= TIER_MIN_WIDTH.sm) return 'sm';
  return 'xs';
}

/** A layout's span for a tier, clamped to what the widget allows; null when hidden there. */
export function spanFor(size: TierSize, override?: number): number | null {
  if (size.kind === 'hidden') return null;
  const want = override ?? size.default;
  return Math.max(size.min, Math.min(size.max, want));
}
