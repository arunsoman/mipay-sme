import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** The gateway owns OAuth tokens; the browser only sees this session summary. */
export interface BrowserSession {
  uid: string;
  sub: string;
  csrf: string;
  expires_at: number;
}

@Injectable({ providedIn: 'root' })
export class BrowserSessionService {
  private readonly http = inject(HttpClient);
  readonly current = signal<BrowserSession | null>(null);
  private inflight: Promise<BrowserSession | null> | null = null;
  private generation = 0;

  async load(force = false): Promise<BrowserSession | null> {
    if (this.inflight) return this.inflight;
    if (!force && this.current()) return this.current();
    const generation = this.generation;
    const pending = firstValueFrom(this.http.get<BrowserSession>('/auth/session'))
      .then((s) => { if (generation === this.generation) this.current.set(s); return generation === this.generation ? s : null; })
      .catch(() => { if (generation === this.generation) this.current.set(null); return null; })
      .finally(() => { if (this.inflight === pending) this.inflight = null; });
    this.inflight = pending;
    return pending;
  }

  clear() { this.generation++; this.current.set(null); this.inflight = null; }

  login(returnTo = '/') {
    const path = returnTo.startsWith('/') && !returnTo.startsWith('//') ? returnTo : '/';
    location.assign('/auth/login?return_to=' + encodeURIComponent(path));
  }

  /** A top-level POST lets the browser follow the IdP logout redirect. */
  logout() {
    const csrf = this.current()?.csrf;
    this.clear();
    if (!csrf) { location.assign('/login'); return; }
    const form = document.createElement('form');
    form.method = 'POST';
    form.action = '/auth/logout';
    const input = document.createElement('input');
    input.type = 'hidden'; input.name = 'csrf'; input.value = csrf;
    form.appendChild(input);
    document.body.appendChild(form);
    form.submit();
  }
}
