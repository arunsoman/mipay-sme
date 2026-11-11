import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { KycWorkspace } from '../../../../../shared/kyc/kyc-workspace';

/**
 * KYC page of the org portals: the shared workspace (this org and everything
 * below it, the schema-driven editor, and the caller's review tasks).
 * `?org=<id>` opens that organization first.
 */
@Component({
  selector: 'mpo-kyc',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [KycWorkspace],
  template: `<mp-kyc-workspace [initialOrgId]="org" />`,
})
export class Kyc {
  readonly org = inject(ActivatedRoute).snapshot.queryParamMap.get('org');
}
