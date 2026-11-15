import { CdkDrag, CdkDragDrop, CdkDragHandle, CdkDropList } from '@angular/cdk/drag-drop';
import {
  ChangeDetectionStrategy, Component, ElementRef, OnDestroy, computed, effect, inject, input, output, signal, untracked,
} from '@angular/core';
import { AccessService } from '../../../../shared/web-core/access';
import type { BusinessPortal } from '../../../../shared/web-core/portal-permission-catalogue';
import {
  COLS, FixedAccess, FixtureDataPort, PageSelection, PlacedWidget, PortRecorder, Scenario, TIER_TEST_WIDTH, Tier,
  WIDGET_CONTEXT, WidgetContext, WidgetDataPort, WidgetDef, WidgetHost, cellsOf, placedSpan,
} from '../../../../shared/widgets/core';
import { WIDGETS } from '../../../../shared/widgets/registry.generated';

/** A palette entry dragged onto the canvas. */
export interface PaletteDrag { kind: 'palette'; widgetId: string }

/**
 * The empty screen of a submenu, at one grid tier, with the placed widgets rendered for real -
 * on their default fixtures, in design mode (never against the live API with the developer's
 * own session). Drop widgets from the palette; drag to reorder (the order is the phone order).
 */
@Component({
  selector: 'wb-design-canvas',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CdkDropList, CdkDrag, CdkDragHandle, WidgetHost],
  providers: [
    FixedAccess, { provide: AccessService, useExisting: FixedAccess },
    FixtureDataPort, { provide: WidgetDataPort, useExisting: FixtureDataPort },
    PortRecorder, PageSelection,
    { provide: WIDGET_CONTEXT, useFactory: () => inject(DesignCanvas).ctx.asReadonly() },
  ],
  template: `
    <div class="cv-bar">
      <span>{{ label() }} · {{ width() }}px · {{ cols() }} columns</span>
      <span class="grow"></span>
      <span class="dim">Drop widgets here · drag ⠿ to reorder (this order is the phone order)</span>
    </div>
    <div class="cv-fit">
      <div class="cv-page" [style.width.px]="width()" [style.zoom]="zoom()" [style.--cols]="cols()"
           cdkDropList id="canvas" cdkDropListOrientation="mixed" [cdkDropListData]="items()"
           (cdkDropListDropped)="dropped($event)" data-canvas>
        <div class="cols" aria-hidden="true">@for (c of colList(); track c) {<i></i>}</div>
        @for (it of items(); track it.instanceId; let i = $index) {
          @if (defOf(it); as d) {
            @if (placedSpanOf(d, it); as span) {
              <div class="cv-cell" cdkDrag [cdkDragData]="it" [style.grid-column]="'span ' + span"
                   [class.sel]="selected() === it.instanceId" (click)="select.emit(it.instanceId)">
                <div class="cv-head">
                  <span class="handle" cdkDragHandle title="Drag to reorder">⠿</span>
                  <b>{{ d.label }}</b><span class="dim mono">{{ span }}/{{ cols() }}</span>
                  <span class="grow"></span>
                  <button type="button" title="Narrower" (click)="$event.stopPropagation(); resize.emit({ id: it.instanceId, by: -1 })">−</button>
                  <button type="button" title="Wider" (click)="$event.stopPropagation(); resize.emit({ id: it.instanceId, by: 1 })">+</button>
                  <button type="button" title="Remove" (click)="$event.stopPropagation(); remove.emit(it.instanceId)">✕</button>
                </div>
                @if (loaded().has(d.id)) {<mp-widget-host [def]="d" [config]="it.config" />}
                @else {<div class="dim pad">Loading fixtures…</div>}
              </div>
            } @else {
              <div class="cv-cell hiddentier" style="grid-column: 1 / -1">{{ d.label }} is hidden on this tier</div>
            }
          } @else {
            <div class="cv-cell bad" style="grid-column: 1 / -1">{{ it.widgetId }}: not in the registry</div>
          }
        } @empty {
          <div class="cv-empty">Empty screen. Drag a widget from the palette and drop it here.</div>
        }
      </div>
    </div>
  `,
})
export class DesignCanvas implements OnDestroy {
  readonly portal = input.required<BusinessPortal>();
  readonly tier = input.required<Tier>();
  readonly items = input.required<readonly PlacedWidget[]>();
  readonly selected = input<string | null>(null);
  /** Preview-as-role: the cells held; null = every cell the placed widgets name. */
  readonly cells = input<readonly string[] | null>(null);

  readonly select = output<string>();
  readonly remove = output<string>();
  readonly resize = output<{ id: string; by: number }>();
  readonly reorder = output<{ from: number; to: number }>();
  readonly add = output<{ widgetId: string; at: number }>();

  readonly ctx = signal<WidgetContext>({ portal: 'PLATFORM', orgId: 'org-preview', mode: 'design' });
  private readonly access = inject(FixedAccess);
  private readonly port = inject(FixtureDataPort);
  private readonly el = inject(ElementRef<HTMLElement>);
  private readonly avail = signal(900);
  private readonly ro = new ResizeObserver((e) => this.avail.set(Math.max(320, (e[0]?.contentRect.width ?? 900) - 4)));
  private readonly defs = new Map(WIDGETS.map((w) => [w.def.id, w.def]));
  private readonly merged = signal<Scenario>({});
  /** Widgets whose fixtures are in the port (hosts mount only then). */
  readonly loaded = signal<ReadonlySet<string>>(new Set());

  readonly width = computed(() => TIER_TEST_WIDTH[this.tier()]);
  readonly cols = computed(() => COLS[this.tier()]);
  readonly colList = computed(() => Array.from({ length: this.cols() }, (_, i) => i));
  readonly zoom = computed(() => Math.min(1, this.avail() / this.width()));
  readonly label = computed(() => ({ xs: 'Phone', sm: 'Tablet', md: 'Laptop', lg: 'Desktop' })[this.tier()]);

  constructor() {
    this.ro.observe(this.el.nativeElement);
    effect(() => { const p = this.portal(); untracked(() => this.ctx.set({ portal: p, orgId: 'org-preview', mode: 'design' })); });
    effect(() => {
      const held = this.cells();
      const all = this.items().flatMap((it) => { const d = this.defs.get(it.widgetId); return d ? cellsOf(d) : []; });
      untracked(() => this.access.set(held ?? all));
    });
    // every placed widget answers from its own default scenario
    effect(() => {
      const ids = [...new Set(this.items().map((i) => i.widgetId))];
      untracked(() => void Promise.all(ids.map((id) => WIDGETS.find((w) => w.def.id === id)?.fixtures()))
        .then((sets) => {
          const m: Record<string, unknown> = {};
          for (const s of sets) if (s) Object.assign(m, s.scenarios.default);
          this.merged.set(m as Scenario);
          this.port.use({ scenarios: { default: this.merged(), empty: {}, error: {} }, expect: {} }, 'default');
          this.loaded.set(new Set(ids));
        }));
    });
  }

  defOf(it: PlacedWidget): WidgetDef<any> | null { return this.defs.get(it.widgetId) ?? null; }
  placedSpanOf(d: WidgetDef<any>, it: PlacedWidget) { return placedSpan(d, it, this.tier()); }

  dropped(e: CdkDragDrop<readonly PlacedWidget[], unknown, PlacedWidget | PaletteDrag>) {
    const data = e.item.data as PaletteDrag | PlacedWidget;
    if ('kind' in data && data.kind === 'palette') this.add.emit({ widgetId: data.widgetId, at: e.currentIndex });
    else if (e.previousIndex !== e.currentIndex) this.reorder.emit({ from: e.previousIndex, to: e.currentIndex });
  }

  ngOnDestroy() { this.ro.disconnect(); }
}
