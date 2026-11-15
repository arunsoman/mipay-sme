import { Injectable, computed, signal } from '@angular/core';

/**
 * A stand-in for AccessService with a cell set chosen by the caller: the workbench's
 * permission switches, unit tests and the RBAC "preview as role" pane. Provided as
 * `{ provide: AccessService, useExisting: FixedAccess }` in the subtree it governs, so
 * *mpPerm, permGuard and widget hosts read it instead of /api/orgs/me/access.
 */
@Injectable()
export class FixedAccess {
  private readonly held = signal<ReadonlySet<string>>(new Set());
  readonly ready = signal(true);
  readonly orgId = signal('');
  readonly orgName = signal('Fixture org');
  readonly orgType = signal('');
  readonly roles = signal<string[]>([]);
  readonly cells = computed(() => this.held() as Set<string>);
  readonly rows = computed(() => new Set([...this.held()].map((c) => c.split(':')[0])));
  readonly empty = computed(() => this.held().size === 0);

  set(cells: Iterable<string>) { this.held.set(new Set(cells)); }
  grant(...cells: string[]) { this.held.update((h) => new Set([...h, ...cells])); }
  revoke(...cells: string[]) { this.held.update((h) => new Set([...h].filter((c) => !cells.includes(c)))); }

  load(): Promise<void> { return Promise.resolve(); }
  clear() { this.held.set(new Set()); }

  can(...perm: Array<string | string[]>): boolean {
    const flat = perm.flat();
    const c = this.cells();
    const r = this.rows();
    return flat.some((p) => (p.includes(':') ? c.has(p) : r.has(p)));
  }
}
