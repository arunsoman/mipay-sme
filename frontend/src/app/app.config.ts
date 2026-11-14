import { ApplicationConfig, provideBrowserGlobalErrorListeners, provideZoneChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
    // FAPI 2.0: Gateway handles authentication via __Host-mp_sess cookie
    // No client-side auth interceptor needed - the BFF manages tokens server-side
    // provideHttpClient(withInterceptors([authInterceptor])) removed
  ],
};