import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/** Atom: surface card. Used by every screen panel; nothing else draws boxes. */
@Component({
  selector: 'mp-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="card">
      @if (title()) {
        <div class="head"><b>{{ title() }}</b><span class="dim">{{ subtitle() }}</span></div>
      }
      <ng-content />
    </div>
  `,
  styles: [`
    .card {
      background: var(--mp-surface); border: 1px solid var(--mp-border);
      border-radius: var(--radius-2); box-shadow: var(--shadow-1);
      padding: var(--sp-4); overflow: hidden;
    }
    .head { display: flex; align-items: baseline; justify-content: space-between; gap: var(--sp-2); margin-bottom: var(--sp-3); }
  `],
})
export class MpCard {
  readonly title = input('');
  readonly subtitle = input('');
}
