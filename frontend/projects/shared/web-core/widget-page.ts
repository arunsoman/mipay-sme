import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { map } from 'rxjs';
import { WidgetHost } from '../widgets/core/widget-host';
import { PageSelection, WIDGET_CONTEXT, WidgetContext } from '../widgets/core/context';
import { WIDGETS } from '../widgets/registry.generated';
import type { WidgetDef } from '../widgets/core/manifest';
import type { BusinessPortal } from './portal-permission-catalogue';
import { AccessService } from './access';
import { PageHeader } from '../../../src/app/design-system/organisms/page-header';
import { MpTabs, TabOption } from '../../../src/app/design-system/molecules/tabs';

/** One widget on a page, with its config. */
export interface PagePart { widget: string; config?: Record<string, unknown> }

/**
 * A portal page made of registered widgets (docs/plans/admin-console.md), until the portal
 * builder's layouts take over (portal-builder.md step 5). Route data:
 *
 *   { portal: 'PLATFORM', title: 'Organizations', subtitle: '…',
 *     main: { widget: 'admin.organizations' },
 *     detail: [{ label: 'KYC', widget: 'kyc.application' }, { label: 'People', widget: 'org.people' }] }
 *
 * The main widget publishes `orgId` (page selection) when a row is picked; the detail widgets
 * read it and appear below, as tabs when there are several. Every widget hides itself without
 * its permission, so a tab whose widget is denied simply shows nothing.
 */
@Component({
  selector: 'mp-widget-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [WidgetHost, PageHeader, MpTabs],
  providers: [
    PageSelection,
    {
      provide: WIDGET_CONTEXT,
      useFactory: () => {
        const access = inject(AccessService);
        const route = inject(ActivatedRoute);
        const portal = (route.snapshot.data['portal'] ?? 'PLATFORM') as BusinessPortal;
        return computed<WidgetContext>(() => ({ portal, orgId: access.orgId() || null, mode: 'live' }));
      },
    },
  ],
  template: `
    <page-header [title]="title()" [subtitle]="subtitle()" />
    @if (main(); as m) {<mp-widget-host [def]="m.def" [config]="m.config" />}
    @if (picked() && details().length) {
      <section class="detail" data-detail>
        <h2>{{ pickedName() }}</h2>
        @if (details().length > 1) {
          <mp-tabs [options]="tabs()" [value]="tab()" (valueChange)="tab.set($event)" label="Details" />
        }
        @for (d of details(); track d.label) {
          @if (d.label === tab() || details().length === 1) {
            <mp-widget-host [def]="d.def" [config]="d.config" />
          }
        }
      </section>
    }
  `,
  styles: [`
    :host { display: grid; gap: var(--sp-4, 16px); min-width: 0; }
    .detail { display: grid; gap: var(--sp-3, 12px); padding-top: var(--sp-4, 16px); border-top: 1px solid var(--mp-border); }
    h2 { margin: 0; font-size: var(--fs-md, 15px); }
  `],
})
export class WidgetPage {
  private readonly route = inject(ActivatedRoute);
  private readonly selection = inject(PageSelection);
  private readonly data = toSignal(this.route.data.pipe(map((d) => d)), { initialValue: this.route.snapshot.data });

  readonly title = computed(() => String(this.data()['title'] ?? ''));
  readonly subtitle = computed(() => String(this.data()['subtitle'] ?? ''));
  readonly main = computed(() => this.part(this.data()['main'] as PagePart | undefined));
  readonly details = computed(() => ((this.data()['detail'] ?? []) as (PagePart & { label: string })[])
    .flatMap((d) => { const p = this.part(d); return p ? [{ ...p, label: d.label }] : []; }));
  readonly tabs = computed<TabOption[]>(() => this.details().map((d) => ({ value: d.label, label: d.label })));
  readonly tab = signal('');

  readonly picked = computed(() => this.selection.all()['orgId'] as string | null | undefined);
  /** the main widget may publish the picked row's name too (falls back to a neutral heading) */
  readonly pickedName = computed(() => String(this.selection.all()['orgName'] ?? 'Selected organization'));

  constructor() {
    // the first tab is open by default (and again whenever the route changes)
    this.route.data.subscribe((d) => this.tab.set(((d['detail'] ?? []) as { label: string }[])[0]?.label ?? ''));
  }

  private part(p: PagePart | undefined): { def: WidgetDef<any>; config: Record<string, unknown> | undefined } | null {
    if (!p) return null;
    const entry = WIDGETS.find((w) => w.def.id === p.widget);
    if (!entry) { console.error(`[page] unknown widget ${p.widget}`); return null; }
    return { def: entry.def, config: p.config };
  }
}
