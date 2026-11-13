import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * THE inline message, the same in every portal and in sme-ui: "Saved", "Invoice
 * issued", a refusal from the server, a form error. Change how messages look here only.
 *
 *   <mp-alert kind="ok">Saved.</mp-alert>
 *   <mp-alert [kind]="f.kind">{{ f.text }}</mp-alert>
 */
@Component({
  selector: 'mp-alert',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[class]': 'kind()', role: 'status' },
  template: `<ng-content />`,
  styles: [`
    :host { display: block; font-size: var(--fs-sm); margin: 8px 0; padding: 8px 12px; border-radius: 8px;
            white-space: pre-line; overflow-wrap: anywhere; }
    :host(.ok) { color: var(--alert-ok, #16a34a); background: color-mix(in srgb, var(--alert-ok, #16a34a) 13%, transparent); }
    :host(.bad) { color: var(--alert-bad, #dc2626); background: color-mix(in srgb, var(--alert-bad, #dc2626) 12%, transparent); }
    :host(.info) { color: var(--mp-text-dim); background: color-mix(in srgb, var(--mp-text) 6%, transparent); }
  `],
})
export class MpAlert {
  readonly kind = input<'ok' | 'bad' | 'info' | string>('info');
}
