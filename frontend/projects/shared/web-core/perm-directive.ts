import { Directive, Input, OnDestroy, TemplateRef, ViewContainerRef, effect, inject } from '@angular/core';
import { AccessService } from './access';

/**
 * The self-hiding permission element (RBAC grid, fapi2-design App G) - the one
 * pattern every gated piece of UI follows. The element declares the permission
 * it needs and the structural directive removes it from the DOM unless the
 * signed-in user holds it. Nothing renders "disabled"; it simply is not there.
 *
 * Usage - one verb or an OR list; a string with `:` is an exact grid cell
 * ("row:verb"), a string without is a row ("any verb on the row"):
 *
 *   <mp-button *mpPerm="'merchant.payment:execute'" ...>Pay now</mp-button>
 *   <mp-button *mpPerm="['merchant.inventory:create']" ...>Add stock</mp-button>
 *   <section *mpPerm="'supplier.statement'"> ... </section>   <!-- any verb -->
 *
 * Verbs come from the backend @Perm catalogue (Verbs.read create modify delete
 * submit authorize execute export administer simulate). Visibility mirrors what
 * the backend (RbacAuthz) actually enforces per call: grant "manage inventory"
 * (merchant.inventory:create) and the Add stock button is there on the next
 * rights load; revoke it and the button is gone.
 */
@Directive({ selector: '[mpPerm]' })
export class MpPerm implements OnDestroy {
  private readonly access = inject(AccessService);
  private readonly vcr = inject(ViewContainerRef);
  private readonly tpl = inject(TemplateRef<unknown>);
  private perm: string | string[] | undefined;

  constructor() {
    // one probe per session; re-render whenever the grant set changes
    void this.access.load();
    effect(() => {
      this.access.ready(); // rerun when the rights load completes
      this.access.cells(); // rerun when rights are granted / revoked
      this.render();
    });
  }

  /** *mpPerm="'merchant.payment:execute'" or an OR array of rows / cells. */
  @Input() set mpPerm(value: string | string[] | undefined) {
    this.perm = value;
    this.render();
  }

  private render() {
    this.vcr.clear();
    if (this.perm && this.perm.length && this.access.can(this.perm)) {
      this.vcr.createEmbeddedView(this.tpl);
    }
  }

  ngOnDestroy() {
    this.vcr.clear();
  }
}
