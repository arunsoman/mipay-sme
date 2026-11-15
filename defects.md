# Defects Introduced After db849f9d

> `git diff db849f9d..HEAD` — 10 commits, c2afac6..153c79d
> These are the defects that broke previously-working features after the
> kyc-v2-portals-afriqpay-deploy cut-over.

## P0 — Breaks previously working features

### 1. Identity mode defaults to `legacy` while edge uses gateway sessions
- **File**: `deploy/afriqpay/docker-compose.yml:43`
- **Issue**: `APP_IDENTITY_MODE: ${APP_IDENTITY_MODE:-legacy}` overrides the new gateway-session flow
  (BFF `auth: session`, signed `X-SME-Context` header, DPoP-bound tokens on API calls).
  The new nginx + `trustplane/config/gateway/routes.yaml` route all `/api/**` through
  gateway `auth: session` routes. In `legacy` mode `GatewayIdentityFilter` no-ops and
  `JwtAuthFilter` sees no Bearer token → **every authenticated API call returns 401**.
- **Fix**: Set `APP_IDENTITY_MODE=gateway` in the host `.env`, or align routes for legacy mode.

### 2. Role creation/editing fails with any menu switched on
- **File**: `RbacStore.java:396`, `EndpointManifestPublisher.java:92-101`
- **Issue**: New role UI adds `nav.*` cells on every submenu switch. `EndpointManifestPublisher`
  registers only `service/permissions/rows/endpoints` — **never** the `nav.*` rows from
  `rbac/nav-catalogue.json`. RBAC's `validateGrid` rejects unregistered rows with
  `400 unknown_row`.
- **Fix**: Wire `navCatalogue()` output into the registration payload's `entitlements`
  field, or skip `nav.*` validation in `validateGrid` (already done in
  `OrgAccessMeshController.holdsAll` exemption — just needs the same exemption in
  `validateGrid`).

### 3. Fresh-install grants/onboarding rights silently fail
- **File**: `RbacStore.java:513` (`AccessService.grant`), `RbacStore.registerCatalog` new `entitlements` param
- **Issue**: `entitlements` parameter added ("seed retired: entitlements declare cells that carry
  tenant-grants, e.g. `org.onboarding:create` with attribute `child_type`"), but **no producer
  sends them**. `AccessService.grant:513` throws `"<cell> is not an entitlement cell"` when
  `grant_verbs=0`, so `org.onboarding:*` and `org.rights` grants fail on any fresh database.
- **Fix**: Either populate `entitlements` from the nav-catalogue at registration time, or
  make `grant` accept zero-verb cells as legacy.

### 4. New test failure: `InstallerServiceTest.createsPendingAdminWithRbacRoleAndSetupLink`
- **File**: `InstallerService.java:47-53`, `InstallerServiceTest.java:143`
- **Issue**: Test still constructs the 4-arg `TptsBootstrapRequest`; `create()` now requires
  `reviewerName/Username/Phone` → `SimException: maker and checker name, username and phone are required`.
  (The install UI does send the fields; the suite is stale.)
- **Fix**: Add the missing fields to the test request, or revert the installer change.

## P1 — Functional bugs in the new code

### 5. Root `sme-ui` app auth is broken end-to-end
- **File**: `frontend/src/app/core/auth.service.ts`, `frontend/src/app/core/http.ts`
- **Issues**:
  - Login does `window.location.href='/auth/login'` but container nginx has **no `/auth` location**
    → SPA serves `index.html` → login silently reloads the page.
  - `AuthService.logout()` POSTs `/auth/logout` without X-CSRF → gateway answers
    `403 csrf_required`; local state clears but session survives.
  - `index.html` `<meta name="csrf-token" content="{{ csrfToken }}">` is literal text
    (Angular doesn't interpolate `index.html`).
  - `problemTap` no longer rethrows — returns `of(null)` — downstream NPEs/silent data loss.
  - `requestOtp`/`verifyOtp` POST to `/auth/otp/verify`, an endpoint that doesn't exist.
- **Fix**: Restore Bearer interceptor or fully switch to gateway session cookies; fix CSRF
  form-post pattern; remove or fix `problemTap`.

### 6. Logout dumps every portal onto `mipay.afriqpay.com`
- **File**: `trustplane/internal/gateway/bff.go:logout`
- **Issue**: `post_logout_redirect_uri=https://mipay.afriqpay.com/` hardcoded — users of
  manufacturer/distributor/supplier/admin portals land on the admin portal after logout;
  on local `*.fapi.local` hosts that URL doesn't exist.
- **Fix**: Per-portal redirect or `returnTo` from the login initiation flow.

### 7. Deploy invalidates all existing sessions
- **File**: `trustplane/internal/gateway/session.go`, `bff.go:currentSession`
- **Issue**: New `host` column added to `gw_sessions` (default `''`). `currentSession` rejects
  any session whose `host` doesn't match the request host. All pre-deploy sessions have
  `host=''` → everyone force-logged out.
- **Fix**: Migrate existing sessions (`host = externalHost(r)`) or accept `host=''` as legacy.

### 8. `LoginAttemptFilter` locks out successful logins
- **File**: `backend/src/main/java/com/mipay/sme/security/auth/LoginAttemptFilter.java`
- **Issue**: Counts **every** POST to `/api/auth/login` (successful or not). 5 logins from one IP
  within 15 min → 30-min 429 lockout. `attemptMap.clear()` wipes **all** IPs. Lockout path
  returns 401 + `WWW-Authenticate: Bearer` (misleading — should be 429 with Retry-After).
- **Fix**: Only count failed attempts; fix status code; per-IP clearing instead of global.

### 9. `DpopAuthFilter` is non-functional per RFC 9449
- **File**: `backend/src/main/java/com/mipay/sme/security/auth/DpopAuthFilter.java` (new file)
- **Issues**: Parses DPoP proof as raw JSON (proofs are JWTs → Jackson error → 500);
  looks for `cnf` inside the proof (spec: `cnf` lives in the **token**); builds `htu` as
  `"<METHOD> <uri?query>"` (spec: URI only, no query, no method); jti cache never evicted
  (leak). Currently latent (session routes strip Authorization; token routes send
  `Authorization: DPoP` which bypasses its Bearer check), but any direct Bearer API
  client in gateway mode now gets 401/500.
- **Fix**: Parse proof from the JWT's `cnf` claim; build `htu` as `"<METHOD> <uri>"` only;
  evict jti cache entries older than `JTI_TTL_SECONDS`.

### 10. `IntrospectionController` NPEs on its main use case
- **File**: `backend/src/main/java/com/mipay/sme/security/auth/IntrospectionController.java` (new file)
- **Issue**: `Map.of("sub", payload != null ? ... : null, ...)` throws NPE for any
  opaque/expired/malformed token — exactly what introspection is for → 500. Also decodes
  JWTs without verifying signatures.
- **Fix**: Guard nulls, or return `active: false` for unparseable tokens.

### 11. Gateway refuses to start on fresh afriqpay deploys
- **File**: `trustplane/internal/gateway/server.go`, `trustplane.override.yml`
- **Issue**: `server.go` errors when issuer isn't `*.local` and `GATEWAY_SESSION_KEY` is unset.
  `trustplane.override.yml` sets `GATEWAY_ISSUER=https://auth.afriqpay.com` but neither it,
  `preflight.sh`, `RELEASE.md`, nor the release note mention `GATEWAY_SESSION_KEY`. Same
  class: empty IdP accounts table requires `IDP_BOOTSTRAP_PASSWORD`.
- **Fix**: Add `GATEWAY_SESSION_KEY` to compose env / docs; or allow issuer without session key.

## P2 — Hygiene / cleanup

- `CustomerController` logs `shortCode` and full `qrPayload` at INFO (PII-adjacent, spam).
- `OpsController` logs every queue view at INFO.
- Dead code: `checkStepUpRequired`/`WWWAuthenticate`/step-up constants (unused in bff.go);
  root-app `HttpInterceptor`, `requestOtp`/`verifyOtp`, `navCatalogue()`, `fixtures.e2e.yaml
  users:` block (ignored by SchemaAndSeed and contains plaintext passwords).
- GET `/auth/logout` removed (now 405) — old bookmarks/links break.
- Comment refs to "Bug #3"/"Bug #6" reference tickets that don't exist in the repo.

---
*Generated by git diff db849f9d..HEAD with baseline at db849f9d4bf592522f836a5666de94923ded2046.
Backend compiles, Go builds, Angular compiles. 19 env-dependent `*RealIT` failures are shared
with the baseline; only `InstallerServiceTest` is new. 2026-11-14.*