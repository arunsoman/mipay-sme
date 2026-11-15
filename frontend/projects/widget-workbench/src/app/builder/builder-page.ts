import { CdkDrag, CdkDragDrop, CdkDropList, moveItemInArray, transferArrayItem } from '@angular/cdk/drag-drop';
import { JsonPipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import type { BusinessPortal } from '../../../../shared/web-core/portal-permission-catalogue';
import {
  ConfigField, Finding, LayoutMenu, LayoutScreen, PlacedWidget, PortalLayout, RTL_LOCALES, SOURCE_LOCALE, SUGGESTED_LOCALES,
  TIERS, Tier, WidgetDef, cellsOf, configWithDefaults, exportLayout, newUid, placedSpan, rekey, requiredList,
  screenNavCell, screenResources, screenRoute, seedLayout, validateLayout,
} from '../../../../shared/widgets/core';
import { WIDGETS } from '../../../../shared/widgets/registry.generated';
import { DesignCanvas, PaletteDrag } from './design-canvas';

type Sel = { kind: 'menu'; m: number } | { kind: 'screen'; m: number; s: number } | { kind: 'widget'; m: number; s: number; id: string } | null;
type View = 'canvas' | 'translations' | 'sidebar' | 'export' | 'publish';

/** One layout's record on the server (PortalLayoutService.Summary). */
interface LayoutSummary { id: string; status: string; version: number | null; savedBy: string; savedAt: string;
  submittedBy: string | null; submittedAt: string | null; decidedBy: string | null; decidedAt: string | null; note: string | null }
interface ServerSide { summary: LayoutSummary; layout: PortalLayout }
interface ServerState { portal: string; draft: ServerSide | null; pending: ServerSide | null; published: ServerSide | null; history: LayoutSummary[] }

/** The builder API on the hosted builder (portalbuilder.afriqpay.com): nginx adds the user and key. */
const API = '/internal/ui/builder';

const PORTALS: BusinessPortal[] = ['PLATFORM', 'MANUFACTURER', 'DISTRIBUTOR', 'SUPPLIER', 'MERCHANT', 'LENDER', 'CUSTOMER'];
const DRAFT = (p: string) => `wb.builder.${p}`;

/**
 * The portal builder (docs/plans/portal-builder.md §9). A developer:
 *  1. builds the portal's menus and submenus - every label a message key with a translation per
 *     enabled locale (i18n checked live: missing translations, placeholders, markup, length);
 *  2. opens a submenu's empty screen and drops widgets from the palette (the registry: the
 *     widgets this build carries, and what the registry server holds for the portal);
 *  3. sizes them per tier, phone first, and checks the screen at every tier;
 *  4. exports layouts/<portal>.json (keys only) + i18n/<portal>.<locale>.json bundles.
 * Drafts stay in this browser (localStorage). On the hosted builder (portalbuilder.afriqpay.com)
 *  5. Publish saves the draft on the server, submits it, and another builder user approves it:
 *     the portal then draws its menus from the published layout (maker/checker, steps 5 + 7).
 */
@Component({
  selector: 'wb-builder-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CdkDropList, CdkDrag, DesignCanvas, JsonPipe],
  template: `
    <section class="bd-top">
      <h1>Portal builder</h1>
      <label>Portal
        <select [value]="portal()" (change)="switchPortal($any($event.target).value)">
          @for (p of portals; track p) {<option [value]="p" [selected]="p === portal()">{{ p }}</option>}
        </select>
      </label>
      <div class="locs">
        <span class="dim">Locales</span>
        @for (l of layout().locales; track l) {
          <span class="chip" [class.src]="l === source">{{ l }}{{ l === source ? ' (source)' : '' }}
            @if (l !== source) {<button type="button" class="x" (click)="removeLocale(l)" [attr.aria-label]="'Remove ' + l">✕</button>}
          </span>
        }
        <select (change)="addLocale($any($event.target).value); $any($event.target).value = ''">
          <option value="">+ locale</option>
          @for (l of suggested(); track l) {<option [value]="l" [selected]="l === previewLocale()">{{ l }}</option>}
        </select>
        <input class="short" placeholder="other (pt-BR)" (change)="addLocale($any($event.target).value); $any($event.target).value = ''" />
      </div>
      <label>Preview in
        <select [value]="previewLocale()" (change)="previewLocale.set($any($event.target).value)">
          @for (l of layout().locales; track l) {<option [value]="l" [selected]="l === previewLocale()">{{ l }}</option>}
        </select>
      </label>
      <span class="grow"></span>
      <span class="badge" [class.bad]="errors().length" [class.ok]="!errors().length">
        {{ errors().length }} error(s) · {{ warnings().length }} warning(s)</span>
      @if (who()) {<span class="dim small">signed in as <b>{{ who() }}</b></span>}
      <button type="button" (click)="view.set('publish')">Publish</button>
      <button type="button" (click)="view.set('export')">Export</button>
      <button type="button" class="ghost" (click)="reset()">Reset to today's menus</button>
    </section>

    <div class="bd">
      <!-- ============ left: menu tree + palette -->
      <aside class="bd-left">
        <div class="pane-h"><b>Menus</b><span class="grow"></span><button type="button" (click)="addMenu()">+ menu</button></div>
        <div cdkDropList [cdkDropListData]="layout().menus" (cdkDropListDropped)="moveMenu($event)" class="tree">
          @for (m of layout().menus; track m.label.key; let mi = $index) {
            <div class="tm" cdkDrag [cdkDragData]="m">
              <div class="tm-row" [class.sel]="isSel('menu', mi)" (click)="sel.set({ kind: 'menu', m: mi })">
                <span class="handle" title="Drag to reorder">⠿</span>
                <span class="lbl" [dir]="dir()">{{ text(m.label) }}</span>
                @if (missing(m.label)) {<span class="warn" title="translation missing">●</span>}
                <span class="grow"></span>
                <button type="button" class="lnk" (click)="$event.stopPropagation(); addScreen(mi)">+ sub</button>
              </div>
              <div cdkDropList [id]="'menu-' + mi" [cdkDropListData]="m.screens" [cdkDropListConnectedTo]="screenLists()"
                   (cdkDropListDropped)="moveScreen($event)" class="subs">
                @for (s of m.screens; track s.uid; let si = $index) {
                  <div class="ts" cdkDrag [cdkDragData]="s" [class.sel]="isSel('screen', mi, si)" (click)="openScreen(mi, si)">
                    <span class="handle">⠿</span>
                    <span class="lbl" [dir]="dir()">{{ text(s.label) }}</span>
                    @if (missing(s.label)) {<span class="warn">●</span>}
                    <span class="grow"></span>
                    <span class="dim small">{{ s.items.length }}</span>
                  </div>
                } @empty {<div class="dim small pad-s">No submenus: the menu will not show.</div>}
              </div>
            </div>
          }
        </div>

        <div class="pane-h"><b>Widgets</b><span class="dim small">{{ portal() }}</span></div>
        <div cdkDropList id="palette" [cdkDropListConnectedTo]="['canvas']" cdkDropListSortingDisabled class="palette">
          @for (w of palette(); track w.id) {
            <div class="pal" cdkDrag [cdkDragData]="paletteDrag(w.id)" title="Drag onto the screen">
              <b>{{ w.label }}</b><span class="dim small">{{ w.category }} · {{ w.id }}&#64;{{ w.version }}</span>
              <span class="mono small dim">{{ requiresText(w) }}</span>
            </div>
          } @empty {<div class="dim small pad-s">No registered widget works in {{ portal() }} yet.</div>}
        </div>
        <button type="button" class="lnk" (click)="askServer()">{{ serverBusy() ? 'Asking…' : 'Check the registry server' }}</button>
        @if (serverNote(); as n) {<div class="small dim">{{ n }}</div>}
      </aside>

      <!-- ============ centre -->
      <main class="bd-mid">
        <nav class="tabs">
          @for (v of views; track v.id) {
            <button type="button" [class.on]="view() === v.id" (click)="view.set(v.id)">{{ v.label }}</button>
          }
          <span class="grow"></span>
          @if (view() === 'canvas') {
            @for (t of tiers; track t) {
              <button type="button" [class.on]="tier() === t" (click)="tier.set(t)">{{ tierLabel[t] }}</button>
            }
          }
        </nav>

        @switch (view()) {
          @case ('canvas') {
            @if (screen(); as sc) {
              <div class="crumb">
                <span [dir]="dir()">{{ text(menu()!.label) }} › {{ text(sc.label) }}</span>
                <span class="mono dim small">{{ route() }} · {{ navCell() }}</span>
              </div>
              <wb-design-canvas [portal]="portal()" [tier]="tier()" [items]="sc.items" [selected]="selWidget()"
                                (select)="selectWidget($event)" (remove)="removeWidget($event)" (resize)="resizeWidget($event)"
                                (reorder)="reorderWidget($event)" (add)="addWidget($event)" />
            } @else {
              <div class="empty">Pick a submenu on the left to open its screen, or add one with “+ sub”.</div>
            }
          }
          @case ('translations') {
            <table class="tr">
              <thead><tr><th>Key</th>@for (l of layout().locales; track l) {<th>{{ l }}</th>}</tr></thead>
              <tbody>
                @for (row of texts(); track row.t.key) {
                  <tr>
                    <td class="mono small">{{ row.t.key }}</td>
                    @for (l of layout().locales; track l) {
                      <td [class.miss]="!row.t.messages[l]?.trim()">
                        <input [value]="row.t.messages[l] || ''" [dir]="isRtl(l) ? 'rtl' : 'ltr'" [attr.lang]="l"
                               (change)="setText(row.path, l, $any($event.target).value)" [attr.aria-label]="row.t.key + ' ' + l" />
                      </td>
                    }
                  </tr>
                }
              </tbody>
            </table>
          }
          @case ('sidebar') {
            <div class="sb-prev" [dir]="dir()" [attr.lang]="previewLocale()">
              <div class="dim small">Sidebar as a {{ portal() }} user sees it in “{{ previewLocale() }}”
                ({{ dir() === 'rtl' ? 'right to left' : 'left to right' }}). Missing texts show their key.</div>
              @for (m of layout().menus; track m.label.key) {
                @if (m.screens.length) {
                  <div class="sb-m">{{ text(m.label) }}</div>
                  @for (s of m.screens; track s.uid) {<div class="sb-s">{{ text(s.label) }}</div>}
                }
              }
            </div>
          }
          @case ('publish') {
            @if (!who()) {
              <p class="dim">Publishing works on the hosted builder (portalbuilder.afriqpay.com), where you sign in. Here, use Export.</p>
            } @else {
              @if (pubNote(); as n) {<p [class.bad]="n.bad" [class.ok]="!n.bad">{{ n.text }}</p>}
              <div class="pane-h"><b>{{ portal() }}: your editor</b><span class="grow"></span>
                <button type="button" [disabled]="errors().length || busy()" (click)="saveToServer()">Save draft to server</button></div>
              @if (errors().length) {<p class="bad small">Fix the {{ errors().length }} error(s) first.</p>}

              @for (row of serverRows(); track row.label) {
                <div class="pane-h"><b>{{ row.label }}</b><span class="grow"></span>
                  @if (row.side) {<button type="button" class="lnk" (click)="loadFromServer(row.side)">Open in editor</button>}
                </div>
                @if (row.side; as x) {
                  <div class="small">
                    @if (x.summary.version) {<span>Version {{ x.summary.version }} · </span>}
                    saved by {{ x.summary.savedBy }} {{ when(x.summary.savedAt) }}
                    @if (x.summary.submittedBy) {<span> · submitted by {{ x.summary.submittedBy }} {{ when(x.summary.submittedAt) }}</span>}
                    @if (x.summary.decidedBy) {<span> · approved by {{ x.summary.decidedBy }} {{ when(x.summary.decidedAt) }}</span>}
                    @if (x.summary.note) {<div class="dim">“{{ x.summary.note }}”</div>}
                  </div>
                } @else {<div class="dim small">none</div>}
              }

              <div class="pane-h"><b>Actions</b></div>
              <label class="small">Note <input [value]="pubText()" (input)="pubText.set($any($event.target).value)" placeholder="what changed (optional)" /></label>
              <div class="row-b">
                <button type="button" [disabled]="!state()?.draft || busy()" (click)="submitForApproval()">Submit draft for approval</button>
                @if (state()?.pending; as p) {
                  @if (p.summary.submittedBy !== who()) {
                    <button type="button" [disabled]="busy()" (click)="decide(true)">Approve and publish</button>
                    <button type="button" class="ghost" [disabled]="busy()" (click)="decide(false)">Reject</button>
                  } @else {<span class="dim small">You submitted it: another builder user approves it.</span>}
                }
                @if (state()?.published) {
                  <button type="button" class="ghost bad" [disabled]="busy()" (click)="unpublish()">
                    {{ armUnpublish() ? 'Click again: back to the coded menus' : 'Unpublish' }}</button>
                }
              </div>
              @if (state()?.history?.length) {
                <div class="pane-h"><b>History</b></div>
                @for (h of state()!.history; track h.id) {
                  <div class="small">{{ h.status === 'RETIRED' ? 'v' + h.version + ' retired' : 'rejected' }} · by {{ h.decidedBy }} {{ when(h.decidedAt) }}
                    @if (h.note) {<span class="dim"> · {{ h.note }}</span>}</div>
                }
              }
            }
          }
          @case ('export') {
            @if (errors().length) {<p class="bad">Fix the {{ errors().length }} error(s) first - the export would not pass CI.</p>}
            <div class="exp">
              <div class="pane-h"><b>layouts/{{ portal().toLowerCase() }}.json</b><span class="grow"></span>
                <button type="button" [disabled]="errors().length" (click)="download('layouts/' + portal().toLowerCase() + '.json', exported().layout)">Download</button></div>
              <pre>{{ exported().layout | json }}</pre>
              @for (l of layout().locales; track l) {
                <div class="pane-h"><b>i18n/{{ portal().toLowerCase() }}.{{ l }}.json</b><span class="grow"></span>
                  <button type="button" [disabled]="errors().length" (click)="download('i18n/' + portal().toLowerCase() + '.' + l + '.json', exported().bundles[l])">Download</button></div>
                <pre>{{ exported().bundles[l] | json }}</pre>
              }
            </div>
          }
        }
      </main>

      <!-- ============ right: inspector + findings -->
      <aside class="bd-right">
        @switch (sel()?.kind) {
          @case ('menu') {
            @if (menu(); as m) {
              <div class="pane-h"><b>Menu</b><span class="grow"></span>
                <button type="button" class="lnk" (click)="moveSel(-1)">↑</button><button type="button" class="lnk" (click)="moveSel(1)">↓</button>
                <button type="button" class="lnk bad" (click)="deleteSel()">Delete</button></div>
              <label>Id <input [value]="m.id" (change)="setMenuId($any($event.target).value)" /></label>
              <label>Icon <input [value]="m.icon" (change)="setIcon($any($event.target).value)" placeholder="optional" /></label>
              <div class="mono small dim">key {{ m.label.key }}</div>
              @for (l of layout().locales; track l) {
                <label [class.miss]="!m.label.messages[l]?.trim()">{{ l }}
                  <input [value]="m.label.messages[l] || ''" [dir]="isRtl(l) ? 'rtl' : 'ltr'" [attr.lang]="l"
                         (change)="setText({ m: mi() }, l, $any($event.target).value)" /></label>
              }
            }
          }
          @case ('screen') {
            @if (screen(); as s) {
              <div class="pane-h"><b>Submenu</b><span class="grow"></span>
                <button type="button" class="lnk" (click)="moveSel(-1)">↑</button><button type="button" class="lnk" (click)="moveSel(1)">↓</button>
                <button type="button" class="lnk bad" (click)="deleteSel()">Delete</button></div>
              <label>Id <input [value]="s.id" (change)="setScreenId($any($event.target).value)" /></label>
              <div class="mono small dim">key {{ s.label.key }}</div>
              @for (l of layout().locales; track l) {
                <label [class.miss]="!s.label.messages[l]?.trim()">{{ l }}
                  <input [value]="s.label.messages[l] || ''" [dir]="isRtl(l) ? 'rtl' : 'ltr'" [attr.lang]="l"
                         (change)="setText({ m: mi(), s: si() }, l, $any($event.target).value)" /></label>
              }
              <dl class="facts">
                <dt>uid</dt><dd class="mono">{{ s.uid }}{{ s.legacy ? ' (from today’s catalogue)' : '' }}</dd>
                <dt>route</dt><dd class="mono">{{ route() }}</dd>
                <dt>menu cell</dt><dd class="mono">{{ navCell() }}</dd>
                <dt>shows with</dt><dd class="mono">{{ resources().join(', ') || 'nothing placed yet' }}</dd>
              </dl>
            }
          }
          @case ('widget') {
            @if (widget(); as w) {
              <div class="pane-h"><b>{{ w.def.label }}</b><span class="grow"></span>
                <button type="button" class="lnk bad" (click)="removeWidget(w.it.instanceId)">Remove</button></div>
              <div class="mono small dim">{{ w.def.id }}&#64;{{ w.def.version }} · placed at major {{ w.it.major }}</div>
              <b class="small">Size per tier</b>
              @for (t of tiers; track t) {
                @if (w.def.size[t].kind === 'span') {
                  <label class="row">{{ tierLabel[t] }}
                    <input type="number" [min]="$any(w.def.size[t]).min" [max]="$any(w.def.size[t]).max"
                           [value]="spanAt(w.def, w.it, t)" (change)="setSpan(w.it.instanceId, t, +$any($event.target).value)" />
                    <span class="dim small">{{ $any(w.def.size[t]).min }}–{{ $any(w.def.size[t]).max }}</span></label>
                } @else {<div class="small dim">{{ tierLabel[t] }}: hidden ({{ $any(w.def.size[t]).reason }})</div>}
              }
              @if (configFields(w.def).length) {<b class="small">Settings</b>}
              @for (f of configFields(w.def); track f.key) {
                <label>{{ f.field.label ?? f.key }}
                  @switch (f.field.type) {
                    @case ('enum') {
                      <select [value]="w.it.config[f.key]" (change)="setConfig(w.it.instanceId, f.key, $any($event.target).value)">
                        @for (o of $any(f.field).options; track o) {<option [value]="o" [selected]="o === w.it.config[f.key]">{{ o }}</option>}
                      </select>
                    }
                    @case ('boolean') {<input type="checkbox" [checked]="w.it.config[f.key]" (change)="setConfig(w.it.instanceId, f.key, $any($event.target).checked)" />}
                    @case ('number') {<input type="number" [value]="w.it.config[f.key]" (change)="setConfig(w.it.instanceId, f.key, +$any($event.target).value)" />}
                    @default {<input [value]="w.it.config[f.key]" (change)="setConfig(w.it.instanceId, f.key, $any($event.target).value)" />}
                  }
                </label>
              }
              <b class="small">Permissions it names</b>
              <div class="mono small">@for (c of cellsOfDef(w.def); track c) {<div>{{ c }}</div>}</div>
            }
          }
          @default {<div class="dim small">Select a menu, a submenu or a placed widget.</div>}
        }

        <div class="pane-h"><b>Checks</b></div>
        <div class="findings">
          @for (f of findings(); track $index) {
            <div [class.bad]="f.level === 'error'" [class.warn]="f.level === 'warning'">{{ f.level === 'error' ? '✗' : '!' }} {{ f.where }}: {{ f.text }}</div>
          } @empty {<div class="ok">✓ i18n complete, ids valid, every widget fits its tier.</div>}
        </div>
      </aside>
    </div>
  `,
})
export class BuilderPage {
  private readonly http = inject(HttpClient);
  readonly portals = PORTALS;
  readonly tiers = TIERS;
  readonly source = SOURCE_LOCALE;
  readonly tierLabel: Record<Tier, string> = { xs: 'Phone', sm: 'Tablet', md: 'Laptop', lg: 'Desktop' };
  readonly views: { id: View; label: string }[] = [
    { id: 'canvas', label: 'Screen' }, { id: 'translations', label: 'Translations' },
    { id: 'sidebar', label: 'Sidebar preview' }, { id: 'export', label: 'Export' },
  ];
  private readonly defs = new Map(WIDGETS.map((w) => [w.def.id, w.def]));

  readonly portal = signal<BusinessPortal>('SUPPLIER');
  readonly layout = signal<PortalLayout>(this.loadDraft('SUPPLIER'));
  readonly sel = signal<Sel>(null);
  readonly view = signal<View>('canvas');
  readonly tier = signal<Tier>('xs');
  readonly previewLocale = signal(SOURCE_LOCALE);
  readonly serverBusy = signal(false);
  readonly serverNote = signal('');
  /** the builder user (hosted builder only; null = publishing is off here) */
  readonly who = signal<string | null>(null);
  readonly state = signal<ServerState | null>(null);
  readonly busy = signal(false);
  readonly pubText = signal('');
  readonly pubNote = signal<{ bad: boolean; text: string } | null>(null);
  readonly armUnpublish = signal(false);
  readonly serverRows = computed(() => [
    { label: 'Draft on the server', side: this.state()?.draft ?? null },
    { label: 'Waiting for approval', side: this.state()?.pending ?? null },
    { label: 'Published (what the portal draws)', side: this.state()?.published ?? null },
  ]);

  readonly palette = computed(() => WIDGETS.map((w) => w.def).filter((d) => d.contexts.includes(this.portal())));
  readonly suggested = computed(() => SUGGESTED_LOCALES.filter((l) => !this.layout().locales.includes(l)));
  readonly findings = computed<Finding[]>(() => validateLayout(this.layout(), this.defs));
  readonly errors = computed(() => this.findings().filter((f) => f.level === 'error'));
  readonly warnings = computed(() => this.findings().filter((f) => f.level === 'warning'));
  readonly exported = computed(() => exportLayout(this.layout()));
  readonly dir = computed(() => (this.isRtl(this.previewLocale()) ? 'rtl' : 'ltr'));
  readonly screenLists = computed(() => this.layout().menus.map((_, i) => 'menu-' + i));

  readonly mi = computed(() => { const s = this.sel(); return s ? s.m : -1; });
  readonly si = computed(() => { const s = this.sel(); return s && s.kind !== 'menu' ? s.s : -1; });
  readonly menu = computed<LayoutMenu | null>(() => this.layout().menus[this.mi()] ?? null);
  readonly screen = computed<LayoutScreen | null>(() => this.menu()?.screens[this.si()] ?? null);
  readonly selWidget = computed(() => { const s = this.sel(); return s?.kind === 'widget' ? s.id : null; });
  readonly widget = computed(() => {
    const id = this.selWidget();
    const it = this.screen()?.items.find((i) => i.instanceId === id);
    const def = it ? this.defs.get(it.widgetId) : undefined;
    return it && def ? { it, def } : null;
  });
  readonly route = computed(() => (this.menu() && this.screen() ? screenRoute(this.menu()!.id, this.screen()!.id) : ''));
  readonly navCell = computed(() => (this.screen() ? screenNavCell(this.portal(), this.screen()!) : ''));
  readonly resources = computed(() => (this.screen() ? screenResources(this.screen()!, this.defs) : []));
  readonly texts = computed(() => this.layout().menus.flatMap((m, mi) => [
    { t: m.label, path: { m: mi } as { m: number; s?: number } },
    ...m.screens.map((s, si) => ({ t: s.label, path: { m: mi, s: si } as { m: number; s?: number } })),
  ]));

  constructor() {
    // drafts are a per-browser convenience; the export is the artifact
    effect(() => {
      const l = this.layout();
      untracked(() => { try { localStorage.setItem(DRAFT(l.portal), JSON.stringify(l)); } catch { /* private mode */ } });
    });
    // the hosted builder answers whoami (nginx adds the user and key); elsewhere publishing stays off
    this.http.get<{ user: string }>(`${API}/whoami`).subscribe({
      next: (r) => { this.who.set(r.user); void this.refreshServer(); },
      error: () => this.who.set(null),
    });
    // open the first screen that has widgets (or the first screen)
    const first = this.layout().menus.findIndex((m) => m.screens.length);
    if (first >= 0) this.sel.set({ kind: 'screen', m: first, s: 0 });
  }

  // ------------------------------------------------------------------ helpers for the template

  isRtl(l: string) { return RTL_LOCALES.includes(l.split('-')[0]); }
  text(t: { key: string; messages: Record<string, string> }) { return t.messages[this.previewLocale()]?.trim() || `⚠ ${t.key}`; }
  missing(t: { messages: Record<string, string> }) { return this.layout().locales.some((l) => !t.messages[l]?.trim()); }
  isSel(kind: 'menu' | 'screen', m: number, s?: number) {
    const x = this.sel();
    return !!x && x.kind === kind && x.m === m && (kind === 'menu' || (x.kind !== 'menu' && x.s === s));
  }
  paletteDrag(widgetId: string): PaletteDrag { return { kind: 'palette', widgetId }; }
  requiresText(d: WidgetDef<any>) { return (Array.isArray(d.requires) ? 'any: ' : 'all: ') + requiredList(d).join(', '); }
  cellsOfDef(d: WidgetDef<any>) { return cellsOf(d); }
  configFields(d: WidgetDef<any>) { return Object.entries(d.config).map(([key, field]) => ({ key, field: field as ConfigField })); }
  spanAt(d: WidgetDef<any>, it: PlacedWidget, t: Tier) { return placedSpan(d, it, t); }

  // ------------------------------------------------------------------ edits

  edit(fn: (l: PortalLayout) => void) {
    const next = structuredClone(this.layout());
    fn(next);
    this.layout.set(rekey(next));
  }

  switchPortal(p: BusinessPortal) {
    this.portal.set(p);
    this.layout.set(this.loadDraft(p));
    this.sel.set(null);
    this.view.set('canvas');
    if (this.who()) void this.refreshServer();
  }

  reset() {
    if (!confirm(`Replace the ${this.portal()} draft with today's menus? Placed widgets and translations are lost.`)) return;
    this.layout.set(seedLayout(this.portal()));
    this.sel.set(null);
  }

  addLocale(raw: string) {
    const l = raw.trim();
    if (!l || this.layout().locales.includes(l)) return;
    this.edit((x) => x.locales.push(l));
    this.previewLocale.set(l);
  }
  removeLocale(l: string) {
    this.edit((x) => {
      x.locales = x.locales.filter((y) => y !== l);
      for (const m of x.menus) { delete m.label.messages[l]; for (const s of m.screens) delete s.label.messages[l]; }
    });
    if (this.previewLocale() === l) this.previewLocale.set(SOURCE_LOCALE);
  }

  setText(path: { m: number; s?: number }, locale: string, value: string) {
    this.edit((x) => {
      const t = path.s === undefined ? x.menus[path.m].label : x.menus[path.m].screens[path.s].label;
      t.messages[locale] = value;
    });
  }

  private uniqueId(base: string, taken: string[]) {
    let id = base; let n = 2;
    while (taken.includes(id)) id = `${base}-${n++}`;
    return id;
  }

  addMenu() {
    this.edit((x) => {
      const id = this.uniqueId('new-menu', x.menus.map((m) => m.id));
      x.menus.push({ id, icon: '', label: { key: '', messages: { [SOURCE_LOCALE]: 'New menu' } }, screens: [] });
    });
    this.sel.set({ kind: 'menu', m: this.layout().menus.length - 1 });
  }

  addScreen(mi: number) {
    this.edit((x) => {
      const m = x.menus[mi];
      const id = this.uniqueId('new-screen', m.screens.map((s) => s.id));
      m.screens.push({ uid: newUid(), id, label: { key: '', messages: { [SOURCE_LOCALE]: 'New screen' } }, items: [] });
    });
    this.openScreen(mi, this.layout().menus[mi].screens.length - 1);
  }

  openScreen(m: number, s: number) { this.sel.set({ kind: 'screen', m, s }); this.view.set('canvas'); }

  setIcon(icon: string) { this.edit((x) => { x.menus[this.mi()].icon = icon.trim(); }); }

  setMenuId(raw: string) {
    const id = raw.trim().toLowerCase();
    this.edit((x) => { x.menus[this.mi()].id = id; });
  }
  setScreenId(raw: string) {
    const id = raw.trim().toLowerCase();
    this.edit((x) => { x.menus[this.mi()].screens[this.si()].id = id; });
  }

  moveSel(by: number) {
    const s = this.sel();
    if (!s) return;
    this.edit((x) => {
      if (s.kind === 'menu') {
        const to = Math.max(0, Math.min(x.menus.length - 1, s.m + by));
        moveItemInArray(x.menus, s.m, to);
        this.sel.set({ kind: 'menu', m: to });
      } else if (s.kind === 'screen') {
        const list = x.menus[s.m].screens;
        const to = Math.max(0, Math.min(list.length - 1, s.s + by));
        moveItemInArray(list, s.s, to);
        this.sel.set({ kind: 'screen', m: s.m, s: to });
      }
    });
  }

  deleteSel() {
    const s = this.sel();
    if (!s) return;
    const what = s.kind === 'menu' ? `menu “${this.text(this.menu()!.label)}” and its submenus` : `submenu “${this.text(this.screen()!.label)}”`;
    if (!confirm(`Delete the ${what}? Roles holding its menu cell keep the cell until it is retired.`)) return;
    this.edit((x) => {
      if (s.kind === 'menu') x.menus.splice(s.m, 1);
      else if (s.kind === 'screen') x.menus[s.m].screens.splice(s.s, 1);
    });
    this.sel.set(null);
  }

  moveMenu(e: CdkDragDrop<LayoutMenu[]>) {
    this.edit((x) => moveItemInArray(x.menus, e.previousIndex, e.currentIndex));
    this.sel.set({ kind: 'menu', m: e.currentIndex });
  }

  moveScreen(e: CdkDragDrop<LayoutScreen[]>) {
    const from = Number(e.previousContainer.id.replace('menu-', ''));
    const to = Number(e.container.id.replace('menu-', ''));
    this.edit((x) => {
      if (from === to) moveItemInArray(x.menus[to].screens, e.previousIndex, e.currentIndex);
      else transferArrayItem(x.menus[from].screens, x.menus[to].screens, e.previousIndex, e.currentIndex);
    });
    this.openScreen(to, e.currentIndex);
  }

  // ------------------------------------------------------------------ widgets on the screen

  private editScreen(fn: (s: LayoutScreen) => void) {
    const mi = this.mi(); const si = this.si();
    if (mi < 0 || si < 0) return;
    this.edit((x) => fn(x.menus[mi].screens[si]));
  }

  addWidget(e: { widgetId: string; at: number }) {
    const def = this.defs.get(e.widgetId);
    if (!def) return;
    const it: PlacedWidget = {
      instanceId: 'w' + newUid().slice(1), widgetId: def.id, major: Number(def.version.split('.')[0]),
      config: configWithDefaults(def) as Record<string, unknown>,
    };
    this.editScreen((s) => s.items.splice(e.at, 0, it));
    this.selectWidget(it.instanceId);
  }
  selectWidget(id: string) { this.sel.set({ kind: 'widget', m: this.mi(), s: this.si(), id }); }
  removeWidget(id: string) {
    this.editScreen((s) => { s.items = s.items.filter((i) => i.instanceId !== id); });
    this.sel.set({ kind: 'screen', m: this.mi(), s: this.si() });
  }
  reorderWidget(e: { from: number; to: number }) { this.editScreen((s) => moveItemInArray(s.items, e.from, e.to)); }
  resizeWidget(e: { id: string; by: number }) {
    const t = this.tier();
    this.editScreen((s) => {
      const it = s.items.find((i) => i.instanceId === e.id);
      const d = it && this.defs.get(it.widgetId);
      const now = it && d ? placedSpan(d, it, t) : null;
      if (!it || !d || now === null) return;
      const z = d.size[t];
      if (z.kind !== 'span') return;
      it.span = { ...(it.span ?? {}), [t]: Math.max(z.min, Math.min(z.max, now + e.by)) };
    });
  }
  setSpan(id: string, t: Tier, v: number) {
    this.editScreen((s) => { const it = s.items.find((i) => i.instanceId === id); if (it) it.span = { ...(it.span ?? {}), [t]: v }; });
  }
  setConfig(id: string, key: string, v: unknown) {
    this.editScreen((s) => { const it = s.items.find((i) => i.instanceId === id); if (it) it.config = { ...it.config, [key]: v }; });
  }

  // ------------------------------------------------------------------ registry server, export

  async askServer() {
    this.serverBusy.set(true);
    try {
      const list = await firstValueFrom(this.http.get<{ id: string; version: string; bundle: string }[]>('/api/ui/widgets', { params: { portal: this.portal() } }));
      const local = new Set(this.palette().map((d) => d.id));
      const extra = list.filter((w) => !local.has(w.id));
      this.serverNote.set(`Registry server: ${list.length} widget(s) deployed for ${this.portal()}`
        + (extra.length ? `; not in this build: ${extra.map((w) => w.id).join(', ')}` : '; all in this build.'));
    } catch {
      this.serverNote.set('Registry server not reachable here (needs the dev proxy and a signed-in session with admin.access:read). The palette shows this build’s registry.');
    } finally {
      this.serverBusy.set(false);
    }
  }

  // ------------------------------------------------------------------ publish (hosted builder)

  when(iso: string | null) { return iso ? new Date(iso).toLocaleString() : ''; }

  async refreshServer() {
    try {
      this.state.set(await firstValueFrom(this.http.get<ServerState>(`${API}/layouts/${this.portal()}`)));
    } catch (e) { this.fail(e); }
  }

  loadFromServer(x: ServerSide) {
    this.layout.set(rekey(structuredClone(x.layout)));
    this.sel.set(null);
    this.pubNote.set({ bad: false, text: 'Opened in the editor.' });
  }

  async saveToServer() { await this.act(() => this.http.put(`${API}/layouts/${this.portal()}/draft`, this.layout()), 'Draft saved on the server.'); }
  async submitForApproval() {
    await this.act(() => this.http.post(`${API}/layouts/${this.portal()}/submit`, { note: this.pubText() || null }),
      'Submitted. Another builder user approves it to publish.');
  }
  async decide(approve: boolean) {
    await this.act(() => this.http.post(`${API}/layouts/${this.portal()}/decide`, { approve, note: this.pubText() || null }),
      approve ? `Published: the ${this.portal()} portal draws these menus on its next load.` : 'Rejected.');
  }
  async unpublish() {
    if (!this.armUnpublish()) { this.armUnpublish.set(true); setTimeout(() => this.armUnpublish.set(false), 4000); return; }
    this.armUnpublish.set(false);
    await this.act(() => this.http.post(`${API}/layouts/${this.portal()}/unpublish`, {}), `Unpublished: ${this.portal()} is back on its coded menus.`);
  }

  private async act(call: () => import('rxjs').Observable<unknown>, ok: string) {
    this.busy.set(true);
    try {
      await firstValueFrom(call());
      this.pubNote.set({ bad: false, text: ok });
      this.pubText.set('');
      await this.refreshServer();
    } catch (e) { this.fail(e); } finally { this.busy.set(false); }
  }

  private fail(e: unknown) {
    const err = (e as { error?: { detail?: string; message?: string; title?: string }; status?: number });
    this.pubNote.set({ bad: true, text: err?.error?.detail ?? err?.error?.message ?? err?.error?.title ?? `The server said ${err?.status ?? 'no'}.` });
  }

  download(name: string, data: unknown) {
    const blob = new Blob([JSON.stringify(data, null, 2) + '\n'], { type: 'application/json' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = name.replace(/\//g, '__');
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  }

  private loadDraft(p: BusinessPortal): PortalLayout {
    try {
      const raw = localStorage.getItem(DRAFT(p));
      if (raw) { const l = JSON.parse(raw) as PortalLayout; if (l.format === 1 && l.portal === p) return l; }
    } catch { /* unreadable or blocked: start fresh */ }
    return seedLayout(p);
  }
}
