import { ApplicationConfig, provideBrowserGlobalErrorListeners, provideZoneChangeDetection } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { portalAuthInterceptor } from './core/api';
import { routes } from './app.routes';
import { provideWidgetHost } from '../../../shared/widgets/core/provide';
import { PORTAL } from './portal.config';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
    provideHttpClient(withInterceptors([portalAuthInterceptor])),
    // the KYC editor and every widget reach the API through the gateway BFF for this portal
    provideWidgetHost({ portal: PORTAL.orgType }),
  ],
};
