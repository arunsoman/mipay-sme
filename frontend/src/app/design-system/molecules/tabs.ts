import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

export interface TabOption<V extends string = string> {
  value: V;
  label: string;
  disabled?: boolean;
  /** leave the option out (e.g. the viewer lacks its permission) */
  hidden?: boolean;
}

/**
 * THE tab / segmented switch, the same in every portal: status tabs, view
 * switches, "create vs use existing". Change how tabs look or behave here only.
 *
 *   <mp-tabs [options]="tabOpts" [value]="tab()" (valueChange)="setTab($event)" label="Request status" />
 */
@Component({
  selector: 'mp-tabs',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="seg" role="tablist" [attr.aria-label]="label()">
      @for (o of options(); track o.value) {
        @if (!o.hidden) {
          <button type="button" role="tab" [class.on]="o.value === value()" [attr.aria-selected]="o.value === value()"
                  [disabled]="o.disabled" (click)="valueChange.emit(o.value)">{{ o.label }}</button>
        }
      }
    </div>
  `,
  styles: [`
    :host { display: inline-block; max-width: 100%; }
    .seg { display: inline-flex; flex-wrap: wrap; gap: 3px; padding: 3px; border-radius: 10px;
           border: 1px solid var(--mp-border); background: color-mix(in srgb, var(--mp-bg) 70%, #000); }
    button { border: 0; background: transparent; padding: 7px 14px; border-radius: 7px; font: inherit;
             font-size: 13.5px; font-weight: 500; color: var(--mp-text-dim); cursor: pointer; }
    button:hover:not(:disabled):not(.on) { color: var(--mp-text); }
    button.on { background: color-mix(in srgb, var(--mp-text) 12%, transparent); color: var(--mp-text); font-weight: 600; }
    button:disabled { opacity: .4; cursor: not-allowed; }
  `],
})
export class MpTabs {
  readonly options = input.required<readonly TabOption[]>();
  readonly value = input.required<string>();
  readonly label = input('');
  readonly valueChange = output<any>();
}
