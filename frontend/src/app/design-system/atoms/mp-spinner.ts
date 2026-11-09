import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/** Atom: inline loading state for pages and tables. */
@Component({
  selector: 'mp-spinner',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (label()) {
      <div class="wrap"><span class="spin"></span><span class="dim">{{ label() }}</span></div>
    } @else {
      <span class="spin"></span>
    }
  `,
  styles: [`
    .wrap { display: inline-flex; align-items: center; gap: var(--sp-2); }
    .spin {
      display: inline-block; width: 18px; height: 18px;
      border: 3px solid var(--mp-border); border-top-color: var(--mp-teal);
      border-radius: 50%; animation: r .8s linear infinite;
    }
    @keyframes r { to { transform: rotate(360deg); } }
  `],
})
export class MpSpinner {
  readonly label = input('');
}
