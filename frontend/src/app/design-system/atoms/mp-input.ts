import { ChangeDetectionStrategy, Component, computed, input, model } from '@angular/core';

/**
 * THE text input. Every form goes through mp-input (or mp-select), so a11y,
 * invalid styling and placeholder conventions exist exactly once.
 */
@Component({
  selector: 'mp-input',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <input
      [type]="type()"
      [placeholder]="placeholder()"
      [disabled]="disabled()"
      [value]="value()"
      (input)="onInput($event)"
      (keydown.enter)="enter($event)"
    />
  `,
  styles: [`
    input {
      width: 100%; padding: 8px 10px; font: inherit; color: inherit;
      border: 1px solid var(--mp-border); border-radius: var(--radius-1);
      background: var(--mp-surface); outline: none;
    }
    input:focus { border-color: var(--mp-accent); box-shadow: 0 0 0 2px rgb(240 161 14 / 0.25); }
    input:disabled { background: var(--mp-bg); }
  `],
})
export class MpInput {
  readonly value = model<string>('');
  readonly type = input('text');
  readonly placeholder = input('');
  readonly disabled = input(false);
  readonly emits = input<'text' | 'number'>('text');

  onInput(e: Event) {
    const el = e.target as HTMLInputElement;
    this.value.set(this.emits() === 'number' && el.value !== '' ? String(Number(el.value)) : el.value);
  }
  enter(e: Event) { e.preventDefault(); }
}
