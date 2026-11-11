import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { KycWorkspace } from '../../../../../shared/kyc/kyc-workspace';

/**
 * KYC page of the admin portal: the shared workspace over the whole tree from
 * the platform root — any org's application can be started, edited and
 * submitted (the server allows MiPay staff), and platform review tasks
 * (ops / compliance / senior approval) are decided here.
 * `?org=<id>` opens that organization first.
 */
@Component({
  selector: 'mpa-kyc',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [KycWorkspace],
  template: `<mp-kyc-workspace [initialOrgId]="org" />`,
})
export class Kyc {
  readonly org = inject(ActivatedRoute).snapshot.queryParamMap.get('org');
}
