/** 
 * FAPI 2.0 HTTP interceptor.
 * 
 * The gateway (BFF) handles authentication via the __Host-mp_sess session cookie.
 * This interceptor no longer adds Bearer tokens - the gateway validates the
 * session cookie and attaches signed context headers server-side.
 * 
 * For state-changing calls, an X-CSRF header is required (double-submit pattern).
 * The CSRF token is obtained from the /api/auth/session endpoint or from a meta tag.
 */
import { Injectable } from '@angular/core';
import { HttpRequest, HttpHandler, HttpEvent, HttpErrorResponse } from '@angular/common/http';
import { catchError } from 'rxjs/operators';
import { Observable, of } from 'rxjs';

/** Normalise errors (ProblemDetail + machine-readable code field). */
export function readProblem(err: unknown): { title: string; detail: string; code?: string; fields?: Record<string, string> } {
  if (err instanceof HttpErrorResponse) {
    const body = err.error as Record<string, unknown>;
    if (body) {
      const code = typeof body['code'] === 'string' ? body['code'] : undefined;
      const fieldsRaw = body['fields'];
      const fields = fieldsRaw && typeof fieldsRaw === 'object'
        ? Object.fromEntries(Object.entries(fieldsRaw).map(([k, v]) => [k, String(v)]))
        : undefined;
      return {
        title: String(body['title'] ?? err.statusText ?? 'Error'),
        detail: String(body['detail'] ?? err.message ?? ''),
        ...(code ? { code } : {}),
        ...(fields ? { fields } : {}),
      };
    }
    return { title: err.statusText, detail: err.message };
  }
  return { title: 'Error', detail: String(err) };
}

/** rxjs helper to map errors through readProblem in service layer */
export function problemTap<T>(onProblem: (p: { title: string; detail: string; code?: string }) => void) {
  return (source: Observable<T>): Observable<T> =>
    source.pipe(
      catchError((e) => {
        onProblem(readProblem(e));
        // Re-throw the error so the caller can decide how to handle it
        throw e;
      })
    );
}
@Injectable()
export class HttpInterceptor {
  /**
   * Intercept HTTP requests.
   * 
   * FAPI 2.0 flow:
   * - __Host-mp_sess cookie is sent automatically by the browser (SameSite=Strict)
   * - Gateway validates session and attaches signed context headers
   * - No Bearer token needed from the client
   * - X-CSRF header required for POST/PUT/DELETE calls
   */
  intercept(req: HttpRequest<unknown>, next: HttpHandler): Observable<HttpEvent<unknown>> {
    // FAPI 2.0: No Bearer token added here - gateway handles via session cookie
    
    // Add X-CSRF header for state-changing methods
    // The CSRF token should come from the session or a meta tag in the page
    const csrfToken = this.getCsrfToken();
    const isStateChanging = req.method !== 'GET' && req.method !== 'HEAD';
    
    if (isStateChanging && csrfToken) {
      req = req.clone({ setHeaders: { 'X-CSRF': csrfToken } });
    }
    
    return next.handle(req);
  }

  /** Get CSRF token from the page meta tag or session. */
  private getCsrfToken(): string | null {
    // Try to get from meta tag set by the portal layout
    const metaTag = document.querySelector('meta[name="csrf-token"]');
    if (metaTag && metaTag.getAttribute('content')) {
      return metaTag.getAttribute('content') as string;
    }
    
    // Try to get from the session API
    // Note: This is a best-effort; if no CSRF token is available,
    // the gateway may still accept the request or return 401
    try {
      // We could call /api/auth/session here, but that would create a circular dependency
      // for now, rely on the meta tag approach
      return null;
    } catch {
      return null;
    }
  }
}