import { Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

/** /api/env view (E01/E02). */
export interface EnvView {
  environment: string;
  simulated: boolean;
  banner: string;
}

/**
 * Environment state: loaded once at shell boot, exposed via signal. Every
 * simulated-flag in the UI derives from here - no screen queries /api/env.
 */
@Injectable({ providedIn: 'root' })
export class EnvironmentService {
  private readonly http: HttpClient;

  readonly env = signal<EnvView | null>(null);

  constructor(http: HttpClient) {
    this.http = http;
  }

  async bootstrap() {
    if (this.env()) return;
    try {
      this.env.set(await firstValueFrom(this.http.get<EnvView>('/api/env')));
    } catch {
      this.env.set({ environment: 'unknown', simulated: true, banner: 'SANDBOX' });
    }
  }

  readonly simulated = () => this.env()?.simulated ?? true;
}
