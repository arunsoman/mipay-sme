import {
  AfterViewInit, ChangeDetectionStrategy, Component, ComponentRef, ElementRef, Injector, OnDestroy, Signal, ViewChild,
  ViewContainerRef, computed, effect, inject, input, output, signal, untracked,
} from '@angular/core';
import { AccessService } from '../../web-core/access';
import {
  PageSelection, PortRecorder, WIDGET_CONFIG, WIDGET_CONTEXT, WIDGET_DEF, WIDGET_DIAGNOSTICS, WIDGET_INPUTS, WIDGET_SIZE,
  WidgetStateRef,
} from './context';
import { WidgetDef, WidgetState, configWithDefaults, requiresMet } from './manifest';
import { PortalAwarePort, WidgetDataPort } from './port';
import { ScopedDataPort } from './ports';
import { tierOf } from './grid';

/**
 * Places one widget (docs/plans/portal-builder.md §4). In order:
 *   1. context   - the page's portal must be in the widget's `contexts`
 *   2. access    - `requires` must hold (AccessService, or FixedAccess in a preview)
 *   3. load      - the component chunk, created with its own injector: config, inputs,
 *                  state and a ScopedDataPort over whatever port the page provides
 *   4. runtime   - a 403 (or empty, per `hideOn`) collapses the cell
 * Re-evaluated live: revoke the right and the widget is destroyed; grant it and it appears.
 *
 * The widget renders INSIDE the host (into #slot), so hiding the host hides the widget.
 * The host element carries data-widget / data-widget-state for tests and the workbench.
 */
@Component({
  selector: 'mp-widget-host',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ng-container #slot />
    @if (panel && diags().length) {
      <aside class="mp-diag" data-diagnostics role="status">
        <b>Self-check · {{ def().id }}</b>
        @for (d of diags(); track d.name) {
          <div class="d d-{{ d.kind }}" [attr.data-diag-kind]="d.kind">
            <span class="n">{{ d.name }}</span> {{ d.message }}
            @if (d.issues?.length) {
              <ul>
                @for (i of d.issues!.slice(0, 8); track $index) {
                  <li [class.w]="i.level === 'warning'"><code>{{ i.path }}</code> expected {{ i.expected }}, got {{ i.got }}</li>
                }
                @if (d.issues!.length > 8) {<li>… {{ d.issues!.length - 8 }} more</li>}
              </ul>
            }
          </div>
        }
      </aside>
    }
  `,
  host: {
    '[attr.data-widget]': 'def().id',
    '[attr.data-widget-state]': 'shownState()',
    '[class.hidden]': 'hidden()',
    '[style.display]': 'hidden() ? "none" : null',
  },
  styles: [`
    :host { display: block; min-width: 0; container-type: inline-size; }
    .mp-diag { margin-top: 10px; padding: 10px 12px; border: 1px dashed var(--a-red, #ff6b5e); border-radius: 10px;
               font-size: 12px; display: grid; gap: 6px; color: var(--a-text, #eee); overflow-wrap: anywhere; }
    .mp-diag > b { color: var(--a-red, #ff6b5e); }
    .d .n { font-family: ui-monospace, Menlo, monospace; font-weight: 700; }
    .d-contract .n, li.w { color: var(--a-amber, #f7c66b); }
    ul { margin: 4px 0 0; padding-left: 16px; display: grid; gap: 2px; }
    code { font-family: ui-monospace, Menlo, monospace; }
  `],
})
export class WidgetHost implements AfterViewInit, OnDestroy {
  readonly def = input.required<WidgetDef<any>>();
  readonly config = input<Record<string, unknown> | undefined>(undefined);
  readonly stateChange = output<WidgetState | 'unsupported' | 'denied'>();

  private readonly context = inject(WIDGET_CONTEXT);
  private readonly access = inject(AccessService);
  private readonly basePort = inject(WidgetDataPort);
  private readonly selection = inject(PageSelection, { optional: true });
  private readonly recorder = inject(PortRecorder, { optional: true });
  /** Inside the host element (an injected ViewContainerRef would insert next to it). */
  @ViewChild('slot', { read: ViewContainerRef, static: true }) private slot!: ViewContainerRef;
  private resolveView!: () => void;
  private readonly viewReady = new Promise<void>((r) => (this.resolveView = r));
  private readonly injector = inject(Injector);
  private readonly el = inject(ElementRef<HTMLElement>);

  private ref: ComponentRef<unknown> | null = null;
  private readonly stateRef = signal<WidgetStateRef | null>(null);
  private readonly width = signal(0);
  private readonly ro = new ResizeObserver((e) => this.width.set(Math.round(e[0]?.contentRect.width ?? 0)));
  private loadSeq = 0;

  /** The harness draws the port's findings in the cell; portals log them. */
  readonly panel = inject(WIDGET_DIAGNOSTICS) === 'panel';
  private readonly logs = inject(WIDGET_DIAGNOSTICS) === 'console';
  readonly diags = computed(() => this.stateRef()?.diagnostics() ?? []);

  readonly supported = computed(() => this.def().contexts.includes(this.context().portal));
  readonly permitted = computed(() => {
    this.access.ready();
    this.access.cells();
    return requiresMet(this.def(), (p) => this.access.can(p));
  });
  readonly shownState = computed<WidgetState | 'unsupported' | 'denied'>(() =>
    !this.supported() ? 'unsupported' : !this.permitted() ? 'denied' : (this.stateRef()?.state() ?? 'loading'));
  readonly hidden = computed(() => {
    const s = this.shownState();
    const hideOn = this.def().hideOn ?? ['forbidden'];
    return s === 'unsupported' || s === 'denied' || (s === 'forbidden' && hideOn.includes('forbidden'))
      || (s === 'empty' && hideOn.includes('empty'));
  });

  constructor() {
    this.ro.observe(this.el.nativeElement);
    void this.access.load();
    effect(() => {
      const show = this.supported() && this.permitted();
      const def = this.def();
      const config = this.config();
      this.context(); // a context change rebuilds the widget for the new portal / org
      untracked(() => (show ? void this.mount(def, config) : this.unmount()));
    });
    effect(() => this.stateChange.emit(this.shownState()));
    effect(() => {
      const found = this.diags();
      if (!this.logs) return;
      for (const d of found) {
        console.warn(`[widget ${this.def().id}] ${d.name}: ${d.message}`,
          ...(d.issues ? [d.issues.map((i) => `${i.path}: expected ${i.expected}, got ${i.got}`)] : []));
      }
    });
  }

  private async mount(def: WidgetDef<any>, config: Record<string, unknown> | undefined) {
    const seq = ++this.loadSeq;
    this.unmount();
    const [cmp] = await Promise.all([def.load(), this.viewReady]);
    if (seq !== this.loadSeq) return;
    const state = new WidgetStateRef();
    const ctx = this.context;
    const aware = this.basePort as WidgetDataPort & Partial<PortalAwarePort>;
    const base = aware.forPortal ? aware.forPortal(ctx().portal) : this.basePort;
    const describe = (name: string, params: Readonly<Record<string, unknown>>) =>
      aware.describe ? aware.describe(name, params, ctx().portal) : 'fixture';
    const port = new ScopedDataPort(base, def, state, this.recorder, describe);
    const injector = Injector.create({
      parent: this.injector,
      providers: [
        { provide: WIDGET_DEF, useValue: def },
        { provide: WIDGET_CONFIG, useValue: Object.freeze(configWithDefaults(def, config)) },
        { provide: WIDGET_INPUTS, useValue: this.resolveInputs(def, ctx) },
        { provide: WIDGET_SIZE, useValue: computed(() => ({ tier: tierOf(this.width()), widthPx: this.width() })) },
        { provide: WidgetStateRef, useValue: state },
        { provide: WidgetDataPort, useValue: port },
      ],
    });
    this.stateRef.set(state);
    this.ref = this.slot.createComponent(cmp, { injector });
    this.ref.changeDetectorRef.detectChanges();
  }

  private resolveInputs(def: WidgetDef<any>, ctx: typeof this.context): Record<string, Signal<unknown>> {
    const out: Record<string, Signal<unknown>> = {};
    for (const [key, spec] of Object.entries(def.inputs ?? {})) {
      const reads = spec.from.map((path) => {
        const [where, name] = path.split('.');
        if (where === 'selection') return this.selection ? this.selection.get(name) : signal(null);
        return computed(() => (ctx() as unknown as Record<string, unknown>)[name] ?? null);
      });
      out[key] = computed(() => {
        for (const r of reads) { const v = r(); if (v !== null && v !== undefined && v !== '') return v; }
        return null;
      });
    }
    return out;
  }

  private unmount() {
    this.ref?.destroy();
    this.ref = null;
    this.slot?.clear();
    this.stateRef.set(null);
  }

  ngAfterViewInit() { this.resolveView(); }

  ngOnDestroy() {
    this.loadSeq++;
    this.ro.disconnect();
    this.unmount();
  }
}
