import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { MiPayWidget } from '../../core/mipay-widget';
import { EmptyState } from '../../../../../src/app/design-system/molecules/empty-state';
import { MpPerm } from '../../../web-core/perm-directive';
import { OnboardChild, type Onboarded } from '../../../kyc/onboard-child';
import type { GrantableView } from '../../../kyc/kyc-api';
import type { OrgOnboardChildConfig } from './onboard-child.widget';

/**
 * The widget wrapper around the shared one-step onboarding form (shared/kyc/onboard-child.ts,
 * also used by the portals' onboarding pages). Inline here: the five KYC tabs are the widget.
 * The form reaches the API only through this instance's scoped port.
 */
@Component({
  selector: 'mpw-org-onboard-child',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [OnboardChild, EmptyState, MpPerm],
  template: `
    @if (nothing()) {
      <empty-state title="Nothing to onboard" hint="Your organization may not onboard other organizations." />
    } @else {
      <mp-onboard-child *mpPerm="'org.onboarding:create'" [inline]="true"
                        (rights)="onRights($event)" (onboarded)="onOnboarded($event)" />
    }
  `,
  styles: [`:host { display: grid; gap: var(--sp-3, 12px); min-width: 0; }`],
})
export class OrgOnboardChildWidget extends MiPayWidget<OrgOnboardChildConfig> {
  readonly nothing = signal(false);

  onRights(g: GrantableView) {
    this.nothing.set(!g.children?.length);
    if (this.nothing()) this.state.set('empty');
  }

  onOnboarded(r: Pick<Onboarded, 'orgId'>) {
    if (r.orgId) this.publish('orgId', r.orgId);
  }
}
