import { ChangeDetectionStrategy, Component, computed } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { WIDGETS } from '../../../shared/widgets/registry.generated';
import { ConnectionBar } from './connection';

/** The workbench shell: widgets by category on the left, the page on the right. */
@Component({
  selector: 'wb-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, ConnectionBar],
  template: `
    <aside class="side">
      <div class="brand">MiPay <b>widget workbench</b></div>
      <a routerLink="/builder" routerLinkActive="on" class="nav">Portal builder</a>
      <a routerLink="/registry" routerLinkActive="on" class="nav">Registry</a>
      @for (g of groups(); track g.category) {
        <div class="grp">{{ g.category }}</div>
        @for (w of g.items; track w.def.id) {
          <a [routerLink]="['/w', w.def.id]" routerLinkActive="on" queryParamsHandling="preserve" class="nav">{{ w.def.label }}</a>
        }
      }
      <p class="foot dim">New widget: <span class="mono">npm run widget:new -- area/name</span><br />
        Portal theme is dark only (no light tokens yet).</p>
    </aside>
    <main class="main"><wb-connection /><router-outlet /></main>
  `,
})
export class App {
  readonly groups = computed(() => {
    const by = new Map<string, (typeof WIDGETS)[number][]>();
    for (const w of WIDGETS) by.set(w.def.category, [...(by.get(w.def.category) ?? []), w]);
    return [...by.entries()].map(([category, items]) => ({ category, items }));
  });
}
