import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { MeView, OtpResult, TokenView } from './dto';

/**
 * Auth store: signals for me/token/role. Login paths:
 * - merchants: phone + OTP (dev OTP 123456 hinted when sandbox, p.16)
 * - everyone else: phone + password
 * Token persisted in localStorage ('mp.token') and attached by authInterceptor.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);

  readonly me = signal<MeView | null>(null);
  readonly token = signal<string | null>(readStoredToken());

  readonly role = computed(() => this.me()?.role ?? '');
  readonly merchantId = computed(() => this.me()?.merchantId ?? null);
  readonly supplierId = computed(() => this.me()?.supplierId ?? null);
  readonly lenderId = computed(() => this.me()?.lenderId ?? null);
  readonly loggedIn = computed(() => !!this.me() && !!this.token());

  async requestOtp(phone: string): Promise<OtpResult> {
    return firstValueFrom(this.http.post<OtpResult>('/api/auth/otp', { phone }));
  }

  async verifyOtp(phone: string, code: string): Promise<TokenView> {
    const tv = await firstValueFrom(this.http.post<TokenView>('/api/auth/otp/verify', { phone, code }));
    this.adopt(tv);
    return tv;
  }

  async loginWithPassword(phone: string, password: string): Promise<TokenView> {
    const tv = await firstValueFrom(this.http.post<TokenView>('/api/auth/login', { phone, password }));
    this.adopt(tv);
    return tv;
  }

  /** Hydrate /api/me after a stored token so reloads keep the session (X01). */
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
    localStorage.removeItem('mp.token');
    this.token.set(null);
    this.me.set(null);
  }

  private adopt(tv: TokenView) {
    localStorage.setItem('mp.token', JSON.stringify(tv));
    this.token.set(tv.token);
  }
}

function readStoredToken(): string | null {
  const raw = localStorage.getItem('mp.token');
  if (!raw) return null;
  try {
    return (JSON.parse(raw) as TokenView).token ?? null;
  } catch {
    return null;
  }
}
