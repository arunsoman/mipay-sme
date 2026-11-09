import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { EnvironmentService } from '../../core/env.service';

/**
 * Molecule: the amber sandbox banner (E02). Shown globally on the shell; also
 * embeddable inside any screen for section-level context.
 */
@Component({
  selector: 'simulated-banner',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (env()?.simulated) {
      <div class="banner">
        {{ text() }}
      </div>
    }
  `,
  styles: [`
    .banner {
      background: color-mix(in srgb, var(--mp-amber) 16%, transparent);
      border: 1px solid color-mix(in srgb, var(--mp-amber) 55%, transparent);
      color: #7c4a03; border-radius: var(--radius-1);
      padding: 7px var(--sp-3); font-size: var(--fs-sm); font-weight: 600;
    }
  `],
})
export class SimulatedBanner {
  private readonly envSvc = inject(EnvironmentService);
  readonly text = input('SANDBOX/UAT - all bank and lender events are SIMULATED (UAT SB p.16)');

  readonly env = computed(() => this.envSvc.env());
}
