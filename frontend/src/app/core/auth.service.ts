import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { MeView, OtpResult, TokenView } from './dto';

/**
 * Auth store for FAPI 2.0 browser flow.
 * 
 * FAPI 2.0 browsers never handle tokens directly.
 * The gateway (BFF) holds tokens server-side and provides
 * identity through the __Host-mp_sess session cookie.
 * 
 * Portal signals:
 * - me: current user info from /api/me
 * - loggedIn: whether a session exists
 * - Handles 401 login_required by coordinating with gateway
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
   * Initiate login with phone number.
   * Gateway sets __Host-mp_sess cookie and redirects to IdP.
   * Portal should then check /auth/session or wait for 401 handling.
   */
  async requestOtp(phone: string, authDetails: string | null = null): Promise<void> {
    await firstValueFrom(this.http.post('/auth/login?return_to=/', { phone, auth_details: authDetails }));
  }

  /**
   * Verify OTP and complete login.
   * Called after IdP redirects back with authorization code.
   * Gateway creates session and sets __Host-mp_sess cookie.
   */
  async verifyOtp(phone: string, code: string): Promise<void> {
    await firstValueFrom(this.http.post('/auth/otp/verify', { phone, code }));
    // After verifyOtp, hydrate user info
    await this.hydrate();
  }

  /**
   * Log out.
   * Gateway handles logout via /auth/logout and back-channel logout.
   * Portal just clears local state.
   */
  logout(): Promise<void> {
    // Inform the gateway to end the session
    return firstValueFrom(this.http.post('/auth/logout', {})).then(() => {
      // Clear local state regardless of gateway response
      this.me.set(null);
    }).catch(() => {
      // Even if gateway call fails, clear local state
      this.me.set(null);
    });
  }

  /**
   * Hydrate user info from /api/me.
   * Called after login to populate the me signal.
   * The gateway validates the session cookie and returns user info.
   */
  async hydrate(): Promise<MeView | null> {
    try {
      const me = await firstValueFrom(this.http.get<MeView>('/api/me'));
      this.me.set(me);
      return me;
    } catch {
      // /api/me returns 401 if no valid session
      this.me.set(null);
      return null;
    }
  }
}