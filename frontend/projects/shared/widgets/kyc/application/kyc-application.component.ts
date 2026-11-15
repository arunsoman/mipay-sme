import { ChangeDetectionStrategy, Component, effect, untracked } from '@angular/core';
import { MiPayWidget } from '../../core/mipay-widget';
import { KycApplication } from '../../../kyc/kyc-application';
import type { KycApplicationConfig } from './kyc-application.widget';

/**
 * The widget wrapper around the shared KYC editor (shared/kyc/kyc-application.ts). The editor
 * itself is a normal component used by the existing KYC pages too; it reaches the API only
 * through the WidgetDataPort, so here it gets this instance's scoped port (gateway or fixtures).
 */
@Component({
  selector: 'mpw-kyc-application',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [KycApplication],
  template: `
    @if (orgId(); as id) {
      <mp-kyc-application [orgId]="id" [wizard]="config.mode === 'wizard'" [readOnly]="config.mode === 'review'"
                          [allowEdit]="false" [startStep]="config.startStep" />
    } @else {
      <div class="pick" data-empty>Pick an organization to see its KYC application.</div>
    }
  `,
  styles: [`
    :host { display: block; min-width: 0; --kyc-bleed: 0px; }
    .pick { padding: 18px; border: 1px dashed var(--a-border, #2b2b2b); border-radius: 12px;
            color: var(--a-dim, #a3a3a3); font-size: var(--fs-sm, 13px); }
  `],
})
export class KycApplicationWidget extends MiPayWidget<KycApplicationConfig> {
  readonly orgId = this.input<string>('orgId');

  constructor() {
    super();
    // no org yet (an admin page before a directory pick): an empty widget, not an error
    // (with an org, the port tracks loading → ready / error / forbidden by itself)
    let wasEmpty = false;
    effect(() => {
      const id = this.orgId();
      untracked(() => {
        if (!id) { this.state.set('empty'); wasEmpty = true; }
        else if (wasEmpty) { this.state.set('loading'); wasEmpty = false; }
      });
    });
  }
}
