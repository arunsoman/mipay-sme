import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { MeView } from './dto';

/**
 * Auth store for FAPI 2.0 browser flow.
 *
 * FAPI 2.0 browsers never handle tokens directly.
 * The gateway (BFF) holds tokens server-side and provides
 * identity through the __Host-mp_sess session cookie.
 *
 * Login flow:
 *   1. Call startLogin(returnTo) → browser navigates to GET /auth/login?return_to=…
 *   2. Gateway performs PAR → redirects browser to IdP /authorize
 *   3. User authenticates at IdP (OTP, password, …)
 *   4. IdP redirects to /auth/callback → gateway exchanges code, creates session
 *   5. Gateway redirects browser back to returnTo
 *   6. Portal calls hydrate() to populate the me signal
 *
 * Portal signals:
 * - me: current user info from /api/me
 * - loggedIn: whether a session exists
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);

  /** Current user profile from /api/me */
  readonly me = signal<MeView | null>(null);

  /** Whether a valid session exists (cookie present and session active) */
  readonly loggedIn = computed(() => !!this.me());

  /** User's role, derived from the me signal */
  readonly role = computed(() => this.me()?.role ?? '');

  /** Merchant ID, if the user is a merchant */
  readonly merchantId = computed(() => this.me()?.merchantId ?? null);

  /** Supplier ID, if the user is a supplier */
  readonly supplierId = computed(() => this.me()?.supplierId ?? null);

  /** Lender ID, if the user is a lender */
  readonly lenderId = computed(() => this.me()?.lenderId ?? null);

  /**
   * Start the FAPI 2.0 login flow.
   * Navigates the browser to GET /auth/login so the gateway can initiate
   * PAR and redirect to the IdP. The page will be replaced by the IdP's
   * authentication UI; control returns via /auth/callback.
   *
   * @param returnTo - same-origin path to redirect to after login (default '/')
   */
  startLogin(returnTo: string = '/'): void {
    // Full-page navigation so the gateway can set the __Host-mp_tx cookie
    // and issue a proper 302 redirect to the IdP's /authorize endpoint.
    // Using window.location rather than Router.navigate keeps the request
    // a top-level document navigation (required by Sec-Fetch-Dest: document).
    const safeReturn = returnTo.startsWith('/') ? returnTo : '/';
    window.location.href = '/auth/login?return_to=' + encodeURIComponent(safeReturn);
  }

  /**
   * Log out.
   * Gateway handles logout via POST /auth/logout (revokes the refresh token
   * and clears the session cookie), then back-channel-notifies the IdP.
   * Portal clears local state regardless of the gateway response.
   */
  logout(): Promise<void> {
    return firstValueFrom(this.http.post('/auth/logout', {}))
      .then(() => { this.me.set(null); })
      .catch(() => { this.me.set(null); });
  }

  /**
   * Hydrate user info from /api/me.
   * Called once the gateway has established a session (after /auth/callback
   * redirects back to the portal) to populate the me signal.
   * Returns null and clears the signal when no valid session exists.
   */
  async hydrate(): Promise<MeView | null> {
    try {
      const me = await firstValueFrom(this.http.get<MeView>('/api/me'));
      this.me.set(me);
      return me;
    } catch {
      // /api/me returns 401 login_required when no valid session exists
      this.me.set(null);
      return null;
    }
  }
}