import { JsonPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, inject, signal, untracked, viewChildren } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { map } from 'rxjs';
import type { BusinessPortal } from '../../../shared/web-core/portal-permission-catalogue';
import { WIDGETS } from '../../../shared/widgets/registry.generated';
import { ConfigField, FixtureSet, TIERS, Tier, cellsOf, configWithDefaults } from '../../../shared/widgets/core';
import { FrameSetup, WbFrame } from './wb-frame';
import { Connection } from './connection';

const PORTALS: BusinessPortal[] = ['PLATFORM', 'MANUFACTURER', 'DISTRIBUTOR', 'SUPPLIER', 'MERCHANT', 'LENDER', 'CUSTOMER'];

/**
 * One widget, every tier side by side, on fixtures. Everything on the page is in the URL, so a
 * failing layout test prints a link that reproduces it here:
 *   /w/<id>?portal=SUPPLIER&org=org-sup-1&scenario=longText&cells=all&tier=xs&single=1
 */
@Component({
  selector: 'wb-widget-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [WbFrame, RouterLink, JsonPipe],
  template: `
    @if (entry(); as e) {
      <section class="bar">
        <div class="title">
          <h1>{{ e.def.label }} <span class="mono dim">{{ e.def.id }}&#64;{{ e.def.version }}</span></h1>
          <p class="dim">{{ e.def.description }}</p>
        </div>

        <div class="controls">
          <label>Portal
            <select [value]="portal()" (change)="set('portal', $any($event.target).value)">
              @for (p of portals; track p) {<option [value]="p" [selected]="p === portal()">{{ p }}{{ e.def.contexts.includes(p) ? '' : ' (not in contexts)' }}</option>}
            </select>
          </label>
          <label>Own org (context.orgId)
            <input [value]="orgId() ?? ''" (change)="set('org', $any($event.target).value)" placeholder="none" />
          </label>
          @for (k of selectionKeys(); track k) {
            <label>Page selection: {{ k }}
              <input [value]="selection()[k] ?? ''" (change)="set('sel_' + k, $any($event.target).value)" placeholder="none" />
            </label>
          }
          <label>Scenario
            <select [value]="scenario()" (change)="set('scenario', $any($event.target).value)">
              @for (s of scenarios(); track s) {<option [value]="s" [selected]="s === scenario()">{{ s }} → {{ expectOf(s) }}</option>}
            </select>
          </label>
          <label>Data
            <select [value]="live() ? 'live' : 'fixtures'" (change)="set('data', $any($event.target).value)">
              <option value="fixtures" [selected]="!live()">fixtures</option>
              <option value="live" [selected]="live()">live API{{ conn.service() ? ' - ' + conn.service()!.label : '' }}</option>
            </select>
          </label>
          @if (live() && !conn.connected()) {<span class="small bad">Live needs a connection: see the bar above.</span>}
          @if (live() && conn.connected() && conn.useRealAccess()) {<span class="small ok">Using your real permissions and org.</span>}
          <label>Latency
            <select [value]="latency()" (change)="set('latency', $any($event.target).value)">
              @for (l of [0, 400, 2000]; track l) {<option [value]="l" [selected]="l === latency()">{{ l }} ms</option>}
            </select>
          </label>
          <label class="chk"><input type="checkbox" [checked]="grid()" (change)="toggle('grid')" /> column grid</label>
          <label class="chk"><input type="checkbox" [checked]="outline()" (change)="toggle('outline')" /> outline overflow</label>
        </div>

        <div class="cells">
          <b>Cells held</b>
          <button type="button" class="lnk" (click)="set('cells', 'all')">all</button>
          <button type="button" class="lnk" (click)="set('cells', 'none')">none</button>
          @for (c of allCells(); track c) {
            <label class="chk mono"><input type="checkbox" [checked]="cells().includes(c)" (change)="toggleCell(c)" /> {{ c }}
              <span class="dim">{{ roleOf(c) }}</span></label>
          }
        </div>

        @if (configFields().length) {
          <div class="cells">
            <b>Config</b>
            @for (f of configFields(); track f.key) {
              <label>{{ f.field.label ?? f.key }}
                @switch (f.field.type) {
                  @case ('enum') {
                    <select [value]="config()[f.key]" (change)="set('cfg_' + f.key, $any($event.target).value)">
                      @for (o of $any(f.field).options; track o) {<option [value]="o" [selected]="o === config()[f.key]">{{ o }}</option>}
                    </select>
                  }
                  @case ('boolean') {
                    <input type="checkbox" [checked]="config()[f.key]" (change)="set('cfg_' + f.key, $any($event.target).checked ? 'true' : 'false')" />
                  }
                  @default {
                    <input [value]="config()[f.key]" (change)="set('cfg_' + f.key, $any($event.target).value)" />
                  }
                }
              </label>
            }
          </div>
        }
      </section>

      <section class="frames" [class.one]="single()">
        @for (t of tiers(); track t) {
          <wb-frame [tier]="t" [setup]="setup()" [single]="single()" (click)="focus.set(t)" [class.focus]="focus() === t" />
        }
      </section>

      @if (!single()) {
        <section class="net">
          <header>
            <b>Network ({{ focusLabel() }} frame)</b>
            <span class="dim">every call goes through the widget's port: source → resolved request → answer</span>
          </header>
          <table>
            <thead><tr><th>#</th><th>kind</th><th>name</th><th>params</th><th>target</th><th>status</th><th>ms</th><th>contract</th><th>answer</th></tr></thead>
            <tbody>
              @for (c of calls(); track c.seq) {
                <tr [class.err]="c.status !== 'pending' && c.status !== 200">
                  <td>{{ c.seq }}</td><td>{{ c.kind }}</td><td class="mono">{{ c.name }}</td>
                  <td class="mono small">{{ c.params | json }}</td><td class="mono small">{{ c.target }}</td>
                  <td>{{ c.status }}</td><td>{{ c.ms ?? '' }}</td>
                  <td [class.bad]="contractErrors(c) > 0" [class.ok]="c.contract && !contractErrors(c)" data-contract-cell>
                    @if (c.contract === undefined || c.status === 'pending') {} @else if (c.contract === null) {<span class="dim">none</span>}
                    @else if (!c.contract.length) {✓ matches} @else {✗ {{ contractErrors(c) }} · ! {{ c.contract.length - contractErrors(c) }}}
                  </td>
                  <td class="mono small ans">@if (c.problem) { {{ c.problem.code ?? '' }} {{ c.problem.detail }} } @else { {{ preview(c.response) }} }</td>
                </tr>
              } @empty {
                <tr><td colspan="9" class="dim">No calls (not permitted, not in this portal, or no organization yet).</td></tr>
              }
            </tbody>
          </table>
        </section>
      }
    } @else {
      <p class="pad">Unknown widget "{{ id() }}". <a routerLink="/registry">See the registry</a>.</p>
    }
  `,
})
export class WidgetPage {
  private readonly route = inject(ActivatedRoute);
  readonly conn = inject(Connection);
  private readonly router = inject(Router);
  readonly portals = PORTALS;

  readonly id = toSignal(this.route.paramMap.pipe(map((p) => p.get('id') ?? '')), { initialValue: '' });
  private readonly q = toSignal(this.route.queryParamMap, { requireSync: true });
  readonly entry = computed(() => WIDGETS.find((w) => w.def.id === this.id()) ?? null);
  readonly fixtures = signal<FixtureSet | null>(null);

  readonly portal = computed(() => (this.q().get('portal') ?? this.entry()?.def.contexts[0] ?? 'PLATFORM') as BusinessPortal);
  /** Live + "use my real permissions": the signed-in user's org; else the URL's, else a fixture org. */
  private readonly realAccess = computed(() => this.live() && this.conn.connected() && this.conn.useRealAccess());
  readonly orgId = computed(() => this.q().has('org') ? (this.q().get('org') || null)
    : this.realAccess() ? this.conn.orgId() : 'org-sup-1');
  readonly selectionKeys = computed(() => [...new Set(Object.values(this.entry()?.def.inputs ?? {})
    .flatMap((i) => i.from).filter((f) => f.startsWith('selection.')).map((f) => f.slice(10)))]);
  readonly selection = computed(() => {
    const out: Record<string, unknown> = {};
    for (const k of this.selectionKeys()) { const v = this.q().get('sel_' + k); if (v) out[k] = v; }
    return out;
  });
  readonly allCells = computed(() => (this.entry() ? cellsOf(this.entry()!.def) : []));
  readonly cells = computed(() => {
    if (this.realAccess()) return this.conn.cells();
    const c = this.q().get('cells') ?? 'all';
    if (c === 'all') return this.allCells();
    if (c === 'none') return [];
    return c.split(',').filter(Boolean);
  });
  readonly scenarios = computed(() => Object.keys(this.fixtures()?.scenarios ?? { default: {} }));
  /**
   * The sidebar keeps the query string when switching widgets, so ?scenario= may name a scenario of
   * the PREVIOUS widget (kyc.application's `masked`, say). An unknown name would hand this widget an
   * empty answer set (NO_FIXTURE on every source) while the dropdown shows "default": fall back.
   */
  readonly scenario = computed(() => {
    const asked = this.q().get('scenario') ?? 'default';
    const known = this.fixtures()?.scenarios;
    return known && !(asked in known) ? 'default' : asked;
  });
  readonly latency = computed(() => Number(this.q().get('latency') ?? 0));
  readonly live = computed(() => this.q().get('data') === 'live');
  readonly grid = computed(() => this.q().get('grid') !== '0');
  readonly outline = computed(() => this.q().get('outline') !== '0');
  readonly single = computed(() => this.q().get('single') === '1');
  readonly tiers = computed<readonly Tier[]>(() => (this.single() ? [(this.q().get('tier') ?? 'xs') as Tier] : TIERS));
  readonly configFields = computed(() => Object.entries(this.entry()?.def.config ?? {})
    .map(([key, field]) => ({ key, field: field as ConfigField })));
  readonly config = computed<Record<string, unknown>>(() => {
    const e = this.entry();
    if (!e) return {};
    const given: Record<string, unknown> = {};
    for (const { key, field } of this.configFields()) {
      const v = this.q().get('cfg_' + key);
      if (v === null) continue;
      given[key] = field.type === 'number' ? Number(v) : field.type === 'boolean' ? v === 'true' : v;
    }
    return configWithDefaults(e.def, given) as Record<string, unknown>;
  });

  readonly setup = computed<FrameSetup>(() => ({
    def: this.entry()!.def, fixtures: this.fixtures(), portal: this.portal(), orgId: this.orgId(),
    selection: this.selection(), cells: this.cells(), scenario: this.scenario(), latency: this.latency(),
    live: this.live(), config: this.config(), grid: this.grid(), outline: this.outline(),
  }));

  readonly frames = viewChildren(WbFrame);
  readonly focus = signal<Tier>('md');
  readonly focusLabel = computed(() => ({ xs: 'Phone', sm: 'Tablet', md: 'Laptop', lg: 'Desktop' })[this.focus()]);
  readonly calls = computed(() => this.frames().find((f) => f.tier() === this.focus())?.recorder.calls() ?? []);

  constructor() {
    effect(() => {
      const e = this.entry();
      untracked(() => {
        this.fixtures.set(null);
        if (e) void e.fixtures().then((f) => { if (this.entry() === e) this.fixtures.set(f); });
      });
    });
    // the Playwright layout suite reads the frames through this hook
    (window as unknown as { __wb: unknown }).__wb = {
      measure: (tier: Tier) => this.frames().find((f) => f.tier() === tier)?.measureNow() ?? null,
      state: (tier: Tier) => this.frames().find((f) => f.tier() === tier)?.state() ?? null,
      ready: () => this.fixtures() !== null,
    };
  }

  expectOf(s: string) { return this.fixtures()?.expect[s] ?? '?'; }
  roleOf(c: string) {
    const d = this.entry()?.def;
    if (!d) return '';
    const a = d.actions.find((x) => x.cell === c);
    return a ? `action: ${a.label}` : 'shows the widget';
  }
  contractErrors(c: { contract?: readonly { level: string }[] | null }) { return (c.contract ?? []).filter((x) => x.level === 'error').length; }
  preview(v: unknown) { const s = JSON.stringify(v ?? null); return s.length > 90 ? s.slice(0, 90) + '…' : s; }

  set(key: string, value: string) {
    void this.router.navigate([], { queryParams: { [key]: value }, queryParamsHandling: 'merge', replaceUrl: true });
  }
  toggle(key: 'grid' | 'outline') { this.set(key, this[key]() ? '0' : '1'); }
  toggleCell(c: string) {
    const now = this.cells();
    const next = now.includes(c) ? now.filter((x) => x !== c) : [...now, c];
    this.set('cells', next.length ? next.join(',') : 'none');
  }
}
