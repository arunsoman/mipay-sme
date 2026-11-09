import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { MpInput } from '../atoms/mp-input';
import { MpSelect } from '../atoms/mp-select';

/**
 * Molecule: label + control + hint/error around the atomic control.
 * The declarative <mp-entity-form> organism renders one per schema field;
 * screens never hand-write labels/errors.
 */
@Component({
  selector: 'mp-form-field',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpInput, MpSelect],
  template: `
    <div class="field">
      <label for="ff-{{ key() }}">{{ label() }}@if (required()) {<span class="req">*</span>}</label>
      @if (kind() === 'select') {
        <mp-select [value]="value()" (valueChange)="changed.emit($event)"
                   [options]="options() || []" [placeholder]="placeholder() || hint()"
                   [disabled]="disabled()" />
      } @else {
        <mp-input [value]="value()" (valueChange)="changed.emit($event)"
                  [type]="typeValue()" [placeholder]="placeholder() || hint()"
                  [disabled]="disabled()" [emits]="emitsValue()" />
      }
      @if (error()) {<div class="err">{{ error() }}</div>}
      @else if (hint()) {<div class="hint">{{ hint() }}</div>}
    </div>
  `,
  styles: [`
    .field { display: grid; gap: 4px; }
    label { font-size: var(--fs-sm); font-weight: 600; color: var(--mp-text-dim); }
    .req { color: #dc2626; margin-left: 3px; }
    .err { color: #dc2626; font-size: var(--fs-sm); }
    .hint { color: var(--mp-text-dim); font-size: var(--fs-xs); }
  `],
})
export class FormField {
  readonly key = input.required<string>();
  readonly label = input.required<string>();
  readonly kind = input<'text' | 'number' | 'select'>('text');
  readonly value = input('');
  readonly options = input<{ value: string; label?: string }[]>([]);
  readonly placeholder = input('');
  readonly hint = input('');
  readonly error = input('');
  readonly required = input(false);
  readonly disabled = input(false);

  readonly changed = output<string>();

  typeValue(): 'text' | 'number' { return this.kind() === 'number' ? 'number' : 'text'; }
  emitsValue(): 'text' | 'number' { return this.kind() === 'number' ? 'number' : 'text'; }
}
