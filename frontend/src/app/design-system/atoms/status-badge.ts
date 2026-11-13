import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * The single status chip. Color/label resolution lives in one shared map
 * (core/status-maps.ts) used by this atom and nowhere else, so a new status
 * needs exactly one line. If a payload is simulated/synthetic, an amber
 * SIMULATED chip renders alongside (UAT SB p.16).
 */
@Component({
  selector: 'status-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="chip" [style.background]="color()">{{ label() }}</span>
    @if (simulated()) {
      <span class="chip sim">SIMULATED</span>
    }
  `,
  styles: [`
    :host { display: inline-flex; gap: var(--sp-1); }
    .chip {
      display: inline-block; padding: 2px 8px; border-radius: 999px;
      font-size: var(--fs-xs); font-weight: 700; color: #fff; letter-spacing: .02em;
      text-transform: uppercase; white-space: nowrap;
    }
    .sim { background: var(--mp-amber); color: #3b2f00; }
  `],
})
export class StatusBadge {
  /** Any wire status string (CONFIRMED, HELD, OPEN, BREAKS_FOUND...). */
  readonly status = input.required<string>();
  readonly simulated = input<boolean | string>(false);

  readonly label = () => pretty(this.status());
  readonly color = () => statusColor(this.status());
}

/** shared helpers (imported by status-badge only) */
import { statusColor, statusLabel } from '../status-maps';
function pretty(s: string) {
  return statusLabel(s) ?? s.replace(/_/g, ' ').toLowerCase();
}
