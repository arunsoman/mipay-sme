import { ChangeDetectionStrategy, Component, Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient, HttpResponse } from '@angular/common/http';
import { ActivatedRoute, CanActivateFn, Router } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { firstValueFrom } from 'rxjs';
import { WidgetHost } from '../widgets/core/widget-host';
import { PageSelection, WIDGET_CONTEXT, WIDGET_PORTAL, WidgetContext } from '../widgets/core/context';
import { WIDGETS } from '../widgets/registry.generated';
import { requiresMet, type WidgetDef } from '../widgets/core/manifest';
import { COLS, spanFor, tierOf, type Tier } from '../widgets/core/grid';
import {
  LIVE_SCREEN_ROUTES, PORTAL_PERMISSION_CATALOGUE, navAllows, type BusinessPortal, type LiveNavMenu,
} from './portal-permission-catalogue';
import { AccessService } from './access';
import { defaultLayout } from '../widgets/core/default-layouts';
import { PageHeader } from '../../../src/app/design-system/organisms/page-header';
import { EmptyState } from '../../../src/app/design-system/molecules/empty-state';

/**
 * Published portal layouts (docs/plans/portal-builder.md steps 5 + 7). A portal with a published
 * layout draws its sidebar from it; without one it draws its built-in layout
 * (widgets/core/default-layouts.ts: Onboarding, Users, Access), which replaced the coded menus.
 *
 *  - a screen with widgets opens at /p/<menu>/<screen> and shows only to users who meet one of
 *    its widgets' `requires` (each widget still hides itself without its own permission);
 *  - a screen without widgets that was seeded from today's menus keeps linking to today's coded
 *    page, with today's rule for who sees it - so publishing a layout never loses a screen.
 */

interface Text { key: string; messages: Record<string, string> }
export interface PublishedItem { instanceId: string; widgetId: string; major: number; config: Record<string, unknown>; span?: Partial<Record<Tier, number>> }
export interface PublishedScreen { uid: string; legacy?: boolean; id: string; label: Text; items: PublishedItem[] }
export interface PublishedMenu { id: string; icon: string; label: Text; screens: PublishedScreen[] }
export interface PublishedLayout { format: 1; portal: BusinessPortal; version: number; locales: string[]; menus: PublishedMenu[] }

const DEFS = new Map(WIDGETS.map((w) => [w.def.id, w.def]));

/** The label in the browser's language when the layout has it, else English. */
export function labelOf(t: Text): string {
  const lang = (globalThis.navigator?.language ?? 'en');
  return t.messages[lang]?.trim() || t.messages[lang.split('-')[0]]?.trim() || t.messages['en'] || t.key;
}

@Injectable({ providedIn: 'root' })
export class PortalLayoutService {
  private readonly http = inject(HttpClient);
  /** undefined: not asked yet · null: none (coded menus) · else the published or else the built-in layout */
  readonly layout = signal<PublishedLayout | null | undefined>(undefined);
  private pending: Promise<PublishedLayout | null> | null = null;

  load(portal: BusinessPortal): Promise<PublishedLayout | null> {
    if (this.layout() !== undefined) return Promise.resolve(this.layout() ?? null);
    this.pending ??= firstValueFrom(this.http.get<PublishedLayout>(`/api/ui/layouts/${portal}`, { observe: 'response' }))
      .then((r: HttpResponse<PublishedLayout>) => (r.status === 200 && r.body?.format === 1 ? r.body : null))
      // no layout service (older backend) or no session yet: the built-in layout
      .catch(() => null)
      .then((published) => {
        const l = published ?? (defaultLayout(portal) as PublishedLayout | null);
        this.layout.set(l);
        this.pending = null;
        return l;
      });
    return this.pending;
  }
}

/** Does this user see a widget screen? (one of its widgets' requires met) */
export function screenVisible(s: PublishedScreen, can: (rows: string[]) => boolean): boolean {
  return s.items.some((it) => { const d = DEFS.get(it.widgetId); return !!d && requiresMet(d, can); });
}

/** The sidebar from a published layout, in the same shape as liveNavigation. */
export function layoutNavigation(l: PublishedLayout, can: (rows: string[]) => boolean,
                                 canRoute: (link: string) => boolean = () => true): LiveNavMenu[] {
  const catalogue = new Map<string, string[]>(PORTAL_PERMISSION_CATALOGUE[l.portal]?.flatMap((m) =>
    m.screens.map((s) => [`${l.portal}/${m.id}/${s.id}`, s.resources] as [string, string[]])) ?? []);
  return l.menus.map((m) => ({
    id: m.id, label: labelOf(m.label),
    screens: m.screens.flatMap((s) => {
      if (s.items.length) {
        return screenVisible(s, can) ? [{ id: s.id, label: labelOf(s.label), link: `/p/${m.id}/${s.id}` }] : [];
      }
      // an empty screen seeded from today's menus: today's page, today's rule
      const link = s.legacy ? LIVE_SCREEN_ROUTES[s.uid] : undefined;
      const rows = catalogue.get(s.uid) ?? [];
      return link && rows.length && can(rows) && canRoute(link) && navAllows(l.portal, link, can)
        ? [{ id: s.id, label: labelOf(s.label), link }] : [];
    }),
  })).filter((m) => m.screens.length);
}

export function findScreen(l: PublishedLayout | null | undefined, menuId: string, screenId: string): PublishedScreen | null {
  return l?.menus.find((m) => m.id === menuId)?.screens.find((s) => s.id === screenId) ?? null;
}

/** /p/:menu/:screen: the screen exists in the published layout and the user may see it. */
export const layoutScreenGuard: CanActivateFn = async (route) => {
  const layouts = inject(PortalLayoutService);
  const access = inject(AccessService);
  const router = inject(Router);
  const portal = inject(WIDGET_PORTAL);
  const [l] = await Promise.all([layouts.load(portal), access.load()]);
  const s = findScreen(l, route.paramMap.get('menu') ?? '', route.paramMap.get('screen') ?? '');
  return s && screenVisible(s, (rows) => access.can(...rows)) ? true : router.parseUrl('/no-access');
};

/**
 * One screen of the published layout: its widgets in order on the 12-column grid, each with the
 * span the builder gave it for the current width (within the widget's own bounds). Widgets share
 * one page selection, so a list widget's pick drives the others (as on the coded pages).
 */
@Component({
  selector: 'mp-layout-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [WidgetHost, PageHeader, EmptyState],
  providers: [
    PageSelection,
    {
      provide: WIDGET_CONTEXT,
      useFactory: () => {
        const access = inject(AccessService);
        const portal = inject(WIDGET_PORTAL);
        return computed<WidgetContext>(() => ({ portal, orgId: access.orgId() || null, mode: 'live' }));
      },
    },
  ],
  host: { '(window:resize)': 'width.set($any($event.target).innerWidth)', '[style.--cols]': 'cols()' },
  template: `
    @if (screen(); as s) {
      <page-header [title]="title()" />
      <div class="grid" [attr.data-layout-screen]="s.uid">
        @for (p of placed(); track p.it.instanceId) {
          <mp-widget-host [def]="p.def" [config]="p.it.config" [style.grid-column]="'span ' + p.span" />
        }
      </div>
    } @else {
      <empty-state title="This page is not in the published menu" hint="It may have been moved or unpublished." />
    }
  `,
  styles: [`
    :host { display: block; min-width: 0; }
    .grid { display: grid; grid-template-columns: repeat(var(--cols), minmax(0, 1fr)); gap: var(--sp-4, 16px); align-items: start; }
  `],
})
export class LayoutPage {
  private readonly route = inject(ActivatedRoute);
  private readonly layouts = inject(PortalLayoutService);
  private readonly params = toSignal(this.route.paramMap, { initialValue: this.route.snapshot.paramMap });
  readonly width = signal(typeof innerWidth === 'number' ? innerWidth : 1280);

  readonly menu = computed(() => this.layouts.layout()?.menus.find((m) => m.id === this.params().get('menu')) ?? null);
  readonly screen = computed(() => findScreen(this.layouts.layout(), this.params().get('menu') ?? '', this.params().get('screen') ?? ''));
  readonly title = computed(() => (this.screen() ? labelOf(this.screen()!.label) : ''));

  /** the grid tier for the window width, phone first */
  readonly tier = computed<Tier>(() => tierOf(this.width()));
  readonly cols = computed(() => COLS[this.tier()]);

  readonly placed = computed(() => (this.screen()?.items ?? []).flatMap((it) => {
    const def = DEFS.get(it.widgetId) as WidgetDef<any> | undefined;
    if (!def) return [];
    const span = spanFor(def.size[this.tier()], it.span?.[this.tier()]);
    return span === null ? [] : [{ it, def, span: Math.min(this.cols(), span) }];
  }));
}
