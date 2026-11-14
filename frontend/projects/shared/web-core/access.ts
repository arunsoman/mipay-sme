import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/**
 * The one access engine behind every MiPay portal (RBAC grid, fapi2-design App G).
 *
 * Each portal shell loads GET /api/orgs/me/access once per session: the response lists the
 * signed-in user's held cells as `<row>:<verb>` ids (the same ids the backend's @Perm
 * annotations register with trustplane RBAC and RbacAuthz enforces per call). Nav, routes
 * and buttons derive from that set - visible the moment a right is granted, gone the
 * moment it is revoked. A user who holds no cells at all gets an empty shell
 * (EmptyAccessShell) with nothing to operate.
 *
 * Verb semantics follow the grid: verbs are flat columns (no implication ladder), so a
 * cell decides exactly one action and a ROW is visible when the user holds any verb on it.
 */
const ACCESS_URL = '/api/orgs/me/access';

/** One held grid cell, as issued by RBAC. */
export interface HeldCell { id: string; row: string; verb: string; rowDescription?: string }

/** The signed-in user's own access in their own org. */
export interface MyAccess {
  orgId: string;
  orgName: string;
  orgType: string | null;
  permissions: HeldCell[];
  roles: string[];
}

/** The cell that unlocks the user-management screens (RBAC or extended RBAC). */
export const USER_ADMIN_CELL = 'org.user:administer';

@Injectable({ providedIn: 'root' })
export class AccessService {
  private readonly http = inject(HttpClient);

  private readonly access = signal<MyAccess | null>(null);
  /** Rights loaded at least once (failed network probes don't count - see failed()). */
  readonly ready = signal(false);
  private readonly failed = signal(false);
  private inflight: Promise<void> | null = null;

  readonly orgName = computed(() => this.access()?.orgName ?? '');
  readonly orgType = computed(() => this.access()?.orgType ?? '');
  readonly roles = computed(() => this.access()?.roles ?? []);
  /** Held cells: exact `<row>:<verb>` matches. */
  readonly cells = computed(() => new Set((this.access()?.permissions ?? []).map((p) => p.id)));
  /** Rows with at least one held verb. */
  readonly rows = computed(() => {
    const out = new Set<string>();
    for (const p of this.access()?.permissions ?? []) out.add(p.row);
    return out;
  });
  /** Signed in and authenticated, but nothing granted -> the portal shell is empty. */
  readonly empty = computed(() => this.ready() && !this.failed() && this.cells().size === 0);

  /** Load (idempotent; concurrent calls share one request; force re-checks on demand). */
  load(force = false): Promise<void> {
    if (this.inflight) return this.inflight;
    if (this.ready() && !force) return Promise.resolve();
    this.inflight = firstValueFrom(this.http.get<MyAccess>(ACCESS_URL))
      .then((a) => {
        this.access.set(a);
        this.failed.set(false);
      })
      .catch(() => {
        if (this.ready()) return; // keep a previously loaded grant list during outages
        this.failed.set(true);
      })
      .finally(() => {
        this.ready.set(true);
        this.inflight = null;
      });
    return this.inflight;
  }

  /**
   * May I? Strings with `:` are exact cells ("supplier.catalogue:read"); strings without
   * are rows ("supplier.catalogue"), true when ANY verb on the row is held. Arrays are OR.
   */
  can(...perm: Array<string | string[]>): boolean {
    const flat = perm.flat();
    const c = this.cells();
    const r = this.rows();
    return flat.some((p) => (p.includes(':') ? c.has(p) : r.has(p)));
  }
}
