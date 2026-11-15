import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { BrowserSessionService } from './session';

/**
 * The one HTTP interceptor of every portal (docs/plans/portal-builder.md step 0).
 *
 * The gateway is the FAPI 2.0 client (BFF, fapi2-design.md I4): it holds the DPoP-bound tokens
 * and the browser holds only the HttpOnly session cookie. So the browser adds NO
 * Authorization header, ever - it adds the session's CSRF proof to state-changing /api calls.
 */
export const fapiBffInterceptor: HttpInterceptorFn = (req, next) => {
  const session = inject(BrowserSessionService).current();
  if (req.url.startsWith('/api/') && !['GET', 'HEAD', 'OPTIONS'].includes(req.method)) {
    if (session?.csrf) req = req.clone({ setHeaders: { 'X-CSRF': session.csrf } });
  }
  return next(req);
};
