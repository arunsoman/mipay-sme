import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/** Molecule: universal empty state (tables, lists, lookups). */
@Component({
  selector: 'empty-state',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="es">
      <div class="ico" aria-hidden="true">{{ icon() }}</div>
      <div><b>{{ title() }}</b></div>
      @if (hint()) {<div class="dim">{{ hint() }}</div>}
      <ng-content />
    </div>
  `,
  styles: [`
    .es { text-align: center; padding: var(--sp-6) var(--sp-4); color: var(--mp-text-dim);
          display: grid; justify-items: center; gap: 6px; }
    .ico { font-size: 26px; opacity: .6; }
  `],
})
export class EmptyState {
  readonly title = input('Nothing here yet');
  readonly hint = input('');
  readonly icon = input('⌀');
}
