import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * Organism: page header - title + subtitle + optional action slot. Every page
 * starts with this so headings/nav state look identical everywhere.
 */
@Component({
  selector: 'page-header',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ph">
      <div class="titles">
        <h1>{{ title() }}</h1>
        @if (subtitle()) {<div class="dim">{{ subtitle() }}</div>}
        <ng-content select="[lead]" />
      </div>
      <div class="acts"><ng-content select="[actions]" /></div>
    </div>
  `,
  styles: [`
    .ph { display: flex; align-items: flex-start; justify-content: space-between; flex-wrap: wrap; gap: var(--sp-3) var(--sp-4); margin-bottom: var(--sp-4); }
    .acts { display: flex; align-items: center; gap: var(--sp-2); flex-wrap: wrap; }
    .acts:empty { display: none; }
    h1 { font-size: var(--fs-lg); }
    .dim { color: var(--mp-text-dim); font-size: var(--fs-sm); margin-top: 2px; }
  `],
})
export class PageHeader {
  readonly title = input.required<string>();
  readonly subtitle = input('');
}
