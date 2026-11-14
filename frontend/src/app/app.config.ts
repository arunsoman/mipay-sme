import { ApplicationConfig, provideBrowserGlobalErrorListeners, provideZoneChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
    // FAPI 2.0: Gateway handles authentication via __Host-mp_sess cookie.
    // No auth interceptor is needed – the BFF manages tokens server-side –
    // but HttpClient itself must still be provided so AuthService can inject it.
    provideHttpClient(),
  ],
};