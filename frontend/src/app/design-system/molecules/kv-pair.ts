import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/** Molecule: label/value pair for detail pages (invoice detail, device rows...). */
@Component({
  selector: 'kv-pair',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="kv">
      <span class="k dim">{{ k() }}</span>
      <span class="v"><ng-content /></span>
    </div>
  `,
  styles: [`
    .kv { display: flex; gap: var(--sp-3); align-items: baseline; }
    .k { flex: 0 0 150px; }
    .v { min-width: 0; word-break: break-word; }
  `],
})
export class KvPair {
  readonly k = input.required<string>();
}
