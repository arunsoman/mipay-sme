import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { MeView } from './api';
import { BrowserSessionService } from '../../../../shared/web-core/session';
import { AccessService } from '../../../../shared/web-core/access';

/**
 * IdP-backed session for the admin portal (org spec [H6/M9]).
 *
 * The MiPay identity provider owns credentials (bcrypt hashes, activation,
 * single-use tokens) and issues the signed JWT; the portal never sees a
 * password twice and only carries the bearer token. Login accepts a USERNAME
 * (or a phone number - both are unique identifiers in users table).
 *
 * Portal eligibility: platform staff (TPTS_OPS / MINDLABS_ADMIN) or any
 * platform/org user with orgRole = ADMIN. STAFF accounts are refused with a
 * clear message instead of a broken shell.
 */
@Injectable({ providedIn: 'root' })
export class AdminAuthService {
  private readonly http = inject(HttpClient);

  readonly me = signal<MeView | null>(null);
  private readonly session = inject(BrowserSessionService);
  private readonly access = inject(AccessService);

  readonly role = computed(() => this.me()?.role ?? '');
  readonly orgId = computed(() => this.me()?.orgId ?? null);
  readonly orgType = computed(() => this.me()?.orgType ?? null);
  readonly orgRole = computed(() => this.me()?.orgRole ?? null);
  readonly name = computed(() => this.me()?.name ?? '');

  readonly isPlatformStaff = computed(() =>
    this.role() === 'TPTS_OPS' || this.role() === 'MINDLABS_ADMIN');
  readonly isOrgAdmin = computed(() => this.orgRole() === 'ADMIN');
  readonly isAdmin = computed(() => this.orgType() === 'PLATFORM' || this.isPlatformStaff() || this.isOrgAdmin());
  readonly loggedIn = computed(() => !!this.session.current() && !!this.me());

  /** Start the gateway-owned FAPI authorization flow. */
  login() { this.access.clear(); this.session.login('/onboarding'); }

  /** Hydrate /api/me after verifying the BFF session. */
  async hydrate(): Promise<MeView | null> {
    if (!await this.session.load()) return null;
    try {
      const me = await firstValueFrom(this.http.get<MeView>('/api/me'));
      this.me.set(me);
      return me;
    } catch {
      this.clear();
      return null;
    }
  }

  logout() {
    this.clear();
    this.session.logout();
  }

  private clear() {
    this.access.clear();
    this.me.set(null);
  }
}

/** Thrown when the credentials are valid but the account may not use this portal. */
export class PortalRefusedError extends Error {
  constructor() {
    super('PORTAL_REFUSED');
  }
}
