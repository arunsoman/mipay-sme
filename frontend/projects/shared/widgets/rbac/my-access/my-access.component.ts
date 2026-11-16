import { ChangeDetectionStrategy, Component, computed, signal } from '@angular/core';
import { MiPayWidget } from '../../core/mipay-widget';
import { roleName, words } from '../../core/text';
import { KvPair } from '../../../../../src/app/design-system/molecules/kv-pair';
import { MpAlert } from '../../../../../src/app/design-system/molecules/alert';
import { OperationsGrid } from '../../../../../src/app/design-system/organisms/operations-grid';
import { refusal } from '../../../kyc/kyc-api';
import { RBAC_HINTS, roleLabel } from '../../../rbac/rbac-api';
import { buildOperationsTree, heldOnly } from '../../../rbac/role-operations-model';
import type { BusinessPortal } from '../../../web-core/portal-permission-catalogue';
import type { RbacMyAccessConfig } from './my-access.widget';
import type { RbacMyAccessGet, RbacMyAccessWidgets } from './my-access.contract';

/**
 * My access, read-only. One method per operation of the sheet:
 *  1. loadAccess   (View)  the person's org, roles and held cells
 *  2. loadWidgets  (View)  the portal's widgets → the grid, keeping only those with a tick
 */
@Component({
  selector: 'mpw-rbac-my-access',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [KvPair, MpAlert, OperationsGrid],
  template: `
    @if (view(); as v) {
      <div class="kv">
        <kv-pair k="Organization">{{ v.orgName ?? '—' }} ({{ type(v.orgType) }})</kv-pair>
        <kv-pair k="Roles"><span data-roles>{{ roles() }}</span></kv-pair>
      </div>
    }
    @if (flash(); as f) {<mp-alert kind="bad">{{ f }}</mp-alert>}
    <mp-operations-grid readonly [menus]="tree()" [selected]="held()"
                        [empty]="widgets() === null ? 'Loading your access…' : 'Your roles give you nothing on this portal.'" />
  `,
  styles: [`
    :host { display: grid; gap: var(--sp-3, 12px); min-width: 0; }
    .kv { display: grid; gap: var(--sp-1, 4px); overflow-wrap: anywhere; }
  `],
})
export class RbacMyAccessWidget extends MiPayWidget<RbacMyAccessConfig> {
  readonly view = signal<RbacMyAccessGet | null>(null);
  readonly widgets = signal<RbacMyAccessWidgets | null>(null);
  readonly flash = signal<string | null>(null);
  readonly roles = computed(() => (this.view()?.roles ?? []).map((r) => roleName(roleLabel(r))).join(', ') || 'none');
  readonly type = words;

  /** The cells the person holds ("row:verb"). */
  readonly held = computed(() => new Set((this.view()?.permissions ?? []).map((p) => p.id).filter((c): c is string => !!c)));
  readonly tree = computed(() =>
    heldOnly(buildOperationsTree(this.context().portal as BusinessPortal, this.widgets() ?? []), this.held()));

  constructor() {
    super();
    this.loadAccess();
  }

  /** 1. The person's access (View). */
  loadAccess() {
    this.data.query<RbacMyAccessGet>('rbac.my-access.get').subscribe({
      next: (v) => { this.view.set(v); this.loadWidgets(v.orgId); },
      error: () => undefined,
    });
  }

  /** 2. The portal's widgets (View); the grid keeps the ones the person may use. */
  loadWidgets(org: string | null) {
    const portal = this.context().portal;
    const params = portal === 'PLATFORM' ? { portal } : { id: org, portal };
    this.data.query<RbacMyAccessWidgets>('rbac.my-access.widgets', params).subscribe({
      next: (w) => { this.widgets.set(w); if (!this.tree().length) this.state.set('empty'); },
      error: (e: unknown) => { this.widgets.set([]); this.flash.set(refusal(e, RBAC_HINTS)); },
    });
  }
}
