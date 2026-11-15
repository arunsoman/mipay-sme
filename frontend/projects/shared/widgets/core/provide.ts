import { EnvironmentProviders, Provider, makeEnvironmentProviders } from '@angular/core';
import type { BusinessPortal } from '../../web-core/portal-permission-catalogue';
import { WIDGET_PORTAL } from './context';
import { WidgetDataPort } from './port';
import { GatewayDataPort } from './ports';

/**
 * A portal app's widget wiring: every widget (and every shared component that uses the port,
 * like the KYC editor) reaches the API through the gateway BFF for this portal.
 *
 *   providers: [provideHttpClient(withInterceptors([fapiBffInterceptor])),
 *               provideWidgetHost({ portal: 'SUPPLIER' })]
 */
export function provideWidgetHost(opts: { portal: BusinessPortal }): EnvironmentProviders {
  const providers: Provider[] = [
    { provide: WIDGET_PORTAL, useValue: opts.portal },
    GatewayDataPort,
    { provide: WidgetDataPort, useExisting: GatewayDataPort },
  ];
  return makeEnvironmentProviders(providers);
}
