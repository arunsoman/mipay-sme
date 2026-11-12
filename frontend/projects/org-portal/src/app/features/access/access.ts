import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { OrgAccess } from '../../../../../shared/rbac/org-access';
import { PortalAuthService } from '../../core/auth';

/**
 * "Roles & access" for the caller's own organization (docs/plans/extended-rbac.md ER5):
 * the roles it may use (set by the organization above it), its own roles, and who holds
 * what. The ceiling of organizations BELOW it is edited from "My network".
 */
@Component({
  selector: 'mpo-access',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [OrgAccess],
  template: `
    <div class="head"><h2>Roles &amp; access</h2></div>
    @if (auth.orgId(); as id) {
      <mp-org-access [orgId]="id" [manage]="auth.me()?.orgRole === 'ADMIN'" [canEditCeiling]="false" />
    } @else {
      <div class="muted">Loading…</div>
    }
  `,
  styles: [`.head { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }`],
})
export class Access {
  readonly auth = inject(PortalAuthService);
}
