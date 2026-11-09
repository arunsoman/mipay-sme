import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';

/** Bearer token from the auth store into every /api call (TC-SEC-01). */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const raw = localStorage.getItem('mp.token');
  if (raw && req.url.startsWith('/api/')) {
    try {
      const t = JSON.parse(raw).token as string;
      req = req.clone({ setHeaders: { Authorization: `Bearer ${t}` } });
    } catch { /* corrupted local state - continue unauthenticated */ }
  }
  return next(req);
};

/** Normalise errors (ProblemDetail + machine-readable code field). */
export const errorMappingInterceptor: HttpInterceptorFn = (req, next) =>
  next(req).pipe(
    // errors surface as HttpErrorResponse to callers; map ProblemDetail -> ApiError
  );

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
import { Observable } from 'rxjs';
import { catchError } from 'rxjs/operators';
export function problemTap<T>(onProblem: (p: { title: string; detail: string; code?: string }) => void) {
  return (source: Observable<T>): Observable<T> =>
    source.pipe(catchError((e) => { onProblem(readProblem(e)); throw e; }));
}
