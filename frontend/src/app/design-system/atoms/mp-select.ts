import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';

/** Atom: The select. options = [{value,label}]; values are wire strings only. */
@Component({
  selector: 'mp-select',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <select [disabled]="disabled()" [value]="value()" (change)="onChange($event)">
      <option value="">{{ placeholder() }}</option>
      @for (o of options(); track o.value) {
        <option [value]="o.value" [attr.selected]="o.value === value() ? '' : null">{{ labelOf(o) }}</option>
      }
    </select>
  `,
  styles: [`
    select {
      width: 100%; padding: 8px 10px; font: inherit; color: inherit;
      border: 1px solid var(--mp-border); border-radius: var(--radius-1);
      background: var(--mp-surface); outline: none;
    }
    select:focus { border-color: var(--mp-teal); box-shadow: 0 0 0 2px rgb(20 108 148 / 0.15); }
  `],
})
export class MpSelect {
  readonly value = model<string>('');
  readonly options = input.required<{ value: string; label?: string }[]>();
  readonly placeholder = input('');
  readonly disabled = input(false);

  labelOf(o: { value: string; label?: string }) {
    return o.label ?? o.value;
  }
  onChange(e: Event) { this.value.set((e.target as HTMLSelectElement).value); }
}
