import {
  ChangeDetectionStrategy, Component, ElementRef, Injectable, OnDestroy, computed, effect, inject, input, signal, untracked,
} from '@angular/core';
import { AccessService } from '../../../shared/web-core/access';
import type { BusinessPortal } from '../../../shared/web-core/portal-permission-catalogue';
import {
  COLS, FixedAccess, FixtureDataPort, FixtureSet, GatewayDataPort, PageSelection, PortRecorder, Tier, TIER_TEST_WIDTH,
  PortalAwarePort, WIDGET_CONTEXT, WidgetContext, WidgetDataPort, WidgetDef, WidgetHost, spanFor,
} from '../../../shared/widgets/core';
import { Geometry, measure } from './measure';

/** One frame's live context (WIDGET_CONTEXT reads it). */
@Injectable()
export class FrameContext {
  readonly ctx = signal<WidgetContext>({ portal: 'PLATFORM', orgId: null, mode: 'fixture' });
}

/** Fixtures or the dev gateway, switchable without rebuilding the frame's providers. */
@Injectable()
export class SwitchPort extends WidgetDataPort implements PortalAwarePort {
  readonly fixtures = inject(FixtureDataPort);
  private readonly gateway = inject(GatewayDataPort);
  live = false;
  forPortal(portal: string): WidgetDataPort { return this.live ? this.gateway.forPortal(portal) : this.fixtures; }
  describe(name: string, params: Readonly<Record<string, unknown>>, portal: string) {
    return this.live ? this.gateway.describe(name, params, portal) : 'fixture';
  }
  query<T>(s: string, p?: Readonly<Record<string, unknown>>) { return this.live ? this.gateway.query<T>(s, p) : this.fixtures.query<T>(s, p); }
  command<T>(c: string, p?: Readonly<Record<string, unknown>>) { return this.live ? this.gateway.command<T>(c, p) : this.fixtures.command<T>(c, p); }
}

export interface FrameSetup {
  def: WidgetDef<any>;
  fixtures: FixtureSet | null;
  portal: BusinessPortal;
  orgId: string | null;
  selection: Record<string, unknown>;
  cells: string[];
  scenario: string;
  latency: number;
  live: boolean;
  config: Record<string, unknown>;
  grid: boolean;
  outline: boolean;
}

/**
 * One grid tier: a content area exactly TIER_TEST_WIDTH wide, the tier's column grid, and the
 * widget in a cell spanning its default span for that tier. Every frame has its own access,
 * port, recorder and page selection, so tiers never share state.
 */
@Component({
  selector: 'wb-frame',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [WidgetHost],
  providers: [
    FrameContext, FixedAccess, { provide: AccessService, useExisting: FixedAccess },
    FixtureDataPort, SwitchPort, { provide: WidgetDataPort, useExisting: SwitchPort },
    PortRecorder, PageSelection,
    { provide: WIDGET_CONTEXT, useFactory: () => inject(FrameContext).ctx.asReadonly() },
  ],
  host: { '[attr.data-tier]': 'tier()', '[class.single]': 'single()' },
  template: `
    <header class="fh">
      <b>{{ label() }}</b>
      <span class="dim">{{ width() }}px · {{ tier() }} · {{ spanText() }}</span>
      <span class="grow"></span>
      <span class="st st-{{ state() }}" data-frame-state>{{ state() }}</span>
    </header>
    <div class="page" [style.width.px]="width()" [style.--cols]="cols()">
      @if (setup().grid) {
        <div class="cols" aria-hidden="true">@for (c of colList(); track c) {<i></i>}</div>
      }
      @if (!setup().fixtures && !setup().live) {
        <div class="hiddentier">Loading fixtures…</div>
      } @else if (span(); as s) {
        @for (k of [renderKey()]; track k) {
          <div class="cell" [style.grid-column]="'span ' + s" data-cell>
            <mp-widget-host [def]="setup().def" [config]="setup().config" (stateChange)="state.set($event)" />
          </div>
        }
      } @else {
        <div class="hiddentier">Hidden on this tier: {{ hiddenReason() }}</div>
      }
    </div>
    @if (geometry(); as g) {
      <footer class="geo">
        @if (!g.overflowX.length && !g.outsideCell.length) {<span class="ok">✓ no overflow · nothing outside the cell</span>}
        @for (x of g.overflowX; track $index) {<div class="bad">overflow-x: {{ x }}</div>}
        @for (x of g.outsideCell; track $index) {<div class="bad">outside cell: {{ x }}</div>}
        @if (g.smallTargets.length) {<div class="warn">{{ g.smallTargets.length }} tap target(s) under 44px</div>}
      </footer>
    }
  `,
})
export class WbFrame implements OnDestroy {
  readonly tier = input.required<Tier>();
  readonly setup = input.required<FrameSetup>();
  readonly single = input(false);

  private readonly frame = inject(FrameContext);
  private readonly access = inject(FixedAccess);
  private readonly port = inject(SwitchPort);
  private readonly selection = inject(PageSelection);
  readonly recorder = inject(PortRecorder);
  private readonly el = inject(ElementRef<HTMLElement>);

  readonly state = signal<string>('loading');
  readonly geometry = signal<Geometry | null>(null);
  readonly renderKey = signal(0);
  private timer: ReturnType<typeof setTimeout> | null = null;

  readonly width = computed(() => TIER_TEST_WIDTH[this.tier()]);
  readonly cols = computed(() => COLS[this.tier()]);
  readonly colList = computed(() => Array.from({ length: this.cols() }, (_, i) => i));
  readonly span = computed(() => spanFor(this.setup().def.size[this.tier()]));
  readonly spanText = computed(() => { const s = this.span(); return s === null ? 'hidden' : `span ${s}/${this.cols()}`; });
  readonly hiddenReason = computed(() => { const z = this.setup().def.size[this.tier()]; return z.kind === 'hidden' ? z.reason : ''; });
  readonly label = computed(() => ({ xs: 'Phone', sm: 'Tablet', md: 'Laptop', lg: 'Desktop' })[this.tier()]);

  constructor() {
    // access changes live (grant / revoke without a rebuild): the host re-evaluates by itself
    effect(() => { const cells = this.setup().cells; untracked(() => this.access.set(cells)); });
    // everything else rebuilds the widget from scratch
    effect(() => {
      const s = this.setup();
      // the fixtures module loads asynchronously: rebuild once it is there
      const key = JSON.stringify([s.def.id, !!s.fixtures, s.portal, s.orgId, s.selection, s.scenario, s.latency, s.live, s.config]);
      untracked(() => {
        if (key === this.lastKey) return;
        this.lastKey = key;
        this.port.live = s.live;
        this.port.fixtures.use(s.fixtures, s.scenario, {}, s.latency);
        this.recorder.clear();
        this.selection.replace(s.selection);
        this.frame.ctx.set({ portal: s.portal, orgId: s.orgId, mode: s.live ? 'live' : 'fixture' });
        this.geometry.set(null);
        this.renderKey.update((k) => k + 1);
      });
    });
    // measure once the widget is quiet
    effect(() => {
      const st = this.state();
      this.recorder.pending();
      const outline = this.setup().outline;
      untracked(() => this.scheduleMeasure(st, outline));
    });
  }
  private lastKey = '';

  private scheduleMeasure(st: string, outline: boolean) {
    if (this.timer) clearTimeout(this.timer);
    if (st === 'loading') return;
    this.timer = setTimeout(() => this.geometry.set(this.measureNow(outline)), 250);
  }

  measureNow(outline = false): Geometry | null {
    const cell = (this.el.nativeElement as HTMLElement).querySelector<HTMLElement>('[data-cell]');
    return cell ? measure(cell, this.tier(), outline) : null;
  }

  ngOnDestroy() { if (this.timer) clearTimeout(this.timer); }
}
