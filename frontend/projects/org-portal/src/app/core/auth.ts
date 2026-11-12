import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { MeView, TokenView } from './api';
import { PORTAL } from '../portal.config';

function readToken(): string | null {
  try { return localStorage.getItem(PORTAL.storageKey); } catch { return null; }
}

function writeToken(t: string | null) {
  try {
    if (t) localStorage.setItem(PORTAL.storageKey, t);
    else localStorage.removeItem(PORTAL.storageKey);
  } catch { /* storage blocked: session lives in memory only */ }
}

/**
 * Session for one org portal. Eligibility: an ADMIN of an org whose type is
 * this portal's type (PORTAL.orgType). Platform staff use the admin portal;
 * STAFF and other org types are refused with a clear message.
 * The backend remains the authority - every /api/orgs/** call re-checks.
 */
@Injectable({ providedIn: 'root' })
export class PortalAuthService {
  private readonly http = inject(HttpClient);

  readonly me = signal<MeView | null>(null);
  readonly token = signal<string | null>(readToken());

  readonly orgId = computed(() => this.me()?.orgId ?? null);
  readonly name = computed(() => this.me()?.name ?? '');
  readonly eligible = computed(() => {
    const me = this.me();
    // any member of an org of this portal's type: what they may do is decided by their
    // permissions (menus, pages and buttons hide themselves), not by an admin flag
    return !!me && me.orgType === PORTAL.orgType;
  });

  async login(identifier: string, password: string): Promise<void> {
    const id = identifier.trim();
    const tv = await firstValueFrom(this.http.post<TokenView>('/api/auth/login', {
      phone: id, password, username: id,
    }));
    writeToken(tv.token);
    this.token.set(tv.token);
    const me = await this.hydrate();
    if (!me || !this.eligible()) {
      const why = !me ? 'NO_PROFILE'
        : 'WRONG_ORG_TYPE';
      const orgType = me?.orgType ?? null;
      this.logout();
      throw new PortalRefusedError(why, orgType);
    }
  }

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
    writeToken(null);
    this.token.set(null);
    this.me.set(null);
  }
}

export class PortalRefusedError extends Error {
  constructor(readonly reason: 'NO_PROFILE' | 'WRONG_ORG_TYPE' | 'NOT_ADMIN',
              readonly orgType: string | null) {
    super('PORTAL_REFUSED');
  }
}
