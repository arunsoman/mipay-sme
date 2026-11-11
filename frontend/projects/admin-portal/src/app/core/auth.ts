import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { MeView, TokenView } from './api';

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
  readonly token = signal<string | null>(localStorage.getItem('mp.admin.token'));

  readonly role = computed(() => this.me()?.role ?? '');
  readonly orgId = computed(() => this.me()?.orgId ?? null);
  readonly orgType = computed(() => this.me()?.orgType ?? null);
  readonly orgRole = computed(() => this.me()?.orgRole ?? null);
  readonly name = computed(() => this.me()?.name ?? '');

  readonly isPlatformStaff = computed(() =>
    this.role() === 'TPTS_OPS' || this.role() === 'MINDLABS_ADMIN');
  readonly isOrgAdmin = computed(() => this.orgRole() === 'ADMIN');
  readonly isAdmin = computed(() => this.isPlatformStaff() || this.isOrgAdmin());
  readonly loggedIn = computed(() => !!this.token() && !!this.me());

  /** Username (+password) login against the IdP; phone numbers also accepted. */
  async login(identifier: string, password: string): Promise<void> {
    const trimmed = identifier.trim();
    const tv = await firstValueFrom(this.http.post<TokenView>('/api/auth/login', {
      phone: trimmed,      // @NotBlank contract; harmless when username wins
      password,
      username: trimmed,   // username (or phone-as-identifier) resolves via the IdP
    }));
    localStorage.setItem('mp.admin.token', tv.token);
    this.token.set(tv.token);
    const me = await this.hydrate();
    if (!me || !this.isAdmin()) {
      this.logout();
      throw new PortalRefusedError();
    }
  }

  /** Hydrate /api/me after a stored token so reloads keep the session. */
  async hydrate(): Promise<MeView | null> {
    if (!this.token()) return null;
    try {
      const me = await firstValueFrom(this.http.get<MeView>('/api/me'));
      this.me.set(me);
      return me;
    } catch {
      this.logout();
      return null;
    }
  }

  logout() {
    localStorage.removeItem('mp.admin.token');
    this.token.set(null);
    this.me.set(null);
  }
}

/** Thrown when the credentials are valid but the account may not use this portal. */
export class PortalRefusedError extends Error {
  constructor() {
    super('PORTAL_REFUSED');
  }
}
