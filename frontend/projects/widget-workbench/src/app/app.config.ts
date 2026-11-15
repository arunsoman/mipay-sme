import { ApplicationConfig, provideBrowserGlobalErrorListeners, provideZoneChangeDetection } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { fapiBffInterceptor } from '../../../shared/web-core/bff-interceptor';
import { provideWidgetHost } from '../../../shared/widgets/core/provide';
import { WIDGET_DIAGNOSTICS } from '../../../shared/widgets/core/context';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes, withComponentInputBinding()),
    // the "dev gateway" data mode: the same live port and interceptor the portals use
    provideHttpClient(withInterceptors([fapiBffInterceptor])),
    provideWidgetHost({ portal: 'PLATFORM' }),
    // the harness shows each widget's self-check (contract mismatches, setup errors) in its cell
    { provide: WIDGET_DIAGNOSTICS, useValue: 'panel' },
  ],
};
