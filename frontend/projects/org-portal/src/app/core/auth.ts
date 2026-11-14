import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { MeView } from './api';
import { PORTAL } from '../portal.config';
import { BrowserSessionService } from '../../../../shared/web-core/session';
import { AccessService } from '../../../../shared/web-core/access';

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
  private readonly session = inject(BrowserSessionService);
  private readonly access = inject(AccessService);

  readonly orgId = computed(() => this.me()?.orgId ?? null);
  readonly name = computed(() => this.me()?.name ?? '');
  readonly eligible = computed(() => {
    const me = this.me();
    // any member of an org of this portal's type: what they may do is decided by their
    // permissions (menus, pages and buttons hide themselves), not by an admin flag
    return !!me && me.orgType === PORTAL.orgType;
  });

  login() { this.access.clear(); this.session.login('/onboarding'); }

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

export class PortalRefusedError extends Error {
  constructor(readonly reason: 'NO_PROFILE' | 'WRONG_ORG_TYPE' | 'NOT_ADMIN',
              readonly orgType: string | null) {
    super('PORTAL_REFUSED');
  }
}
