import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/** Atom: NAD money rendering (server sends numeric strings/numbers). */
@Component({
  selector: 'currency-amount',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span [class.dim]="dim()" [title]="title() || null" [textContent]="display()"></span>`,
  styles: [`:host { font-variant-numeric: tabular-nums; } .dim { color: var(--mp-text-dim); }`],
})
export class CurrencyAmount {
  readonly amount = input.required<number | string>();
  readonly dim = input(false);
  readonly title = input('');

  readonly display = computed(() => {
    const v = this.amount();
    const n = typeof v === 'string' ? parseFloat(v) : Number(v ?? 0);
    const f = isFinite(n) ? n.toLocaleString('en-NA', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) : '0.00';
    return 'N$ ' + f;
  });
}
