# PLAN — Trust Plane: FAPI 2.0 Gateway + IdP + Delegated RBAC

Four containers, each its own image, wired through private Docker networks.
Target: OpenID Foundation **FAPI 2.0 Security Profile (Final)** enforcement end-to-end,
with every administrative action flowing through a root-backed RBAC delegation model.

## Containers

| Container | Language | What it owns |
|--------------------|----------|--------------|
| `gateway` | Go | Only public entry (`:8443`, TLS 1.2+). Token validation, sender binding (DPoP/mTLS), deny-by-default routing, per-route rate/concurrency budgets, request hardening, RBAC PEP, circuit breakers, header-strip/proxying. |
| `idp` | Go | FAPI 2.0 authorization server: PAR, auth-code+PKCE S256 (60s/one-time), `iss` response param, mTLS/private_key_jwt client auth (aud = issuer), sender-constrained tokens (cnf jkt / x5t#S256), no-rotation refresh tokens, OIDC id_token, discovery + JWKS, introspection/revocation. PS256/ES256/EdDSA only. |
| `rbac` | **Java 21 / Spring Boot 4.1 (Spring Security)** | Root RBAC of the delegated model: permissions, roles, suppliers, groups, grants, assignments, audit. Decision plane: gRPC over mTLS (`Check`, `ListPermissions`, `Watch` invalidation stream). Admin plane: TLS+mTLS REST where **every controller declares CRUD+approve permissions via `@CrudResource` — the catalog is auto-picked-up at boot** (endpoint audit fails the build/startup if any mapped endpoint lacks its `@Permission` binding). |
| `pg` | Postgres | Isolated DBs (idp/rbac) on a data-only network. No host port. |

Plus `pki-init` (one-shot PKI generator) and a `fapi-client` e2e test client.

## Delegated RBAC model (the trust core)

- **Root RBAC owns roles, permission catalogs and delegation grants**
  (supplier/role/tenant/scope/expiry). Suppliers never self-grant.
- A supplier can assign roles **only within an active root grant boundary**
  (`no_matching_grant`, `scope_exceeds_grant`, `membership`).
- Every delegated assignment carries `grant_id` — **traceability from permission
  to the root-granting decision**.
- Platform-staff access to anything requires a root-backed assignment —
  a valid token without an assignment is powerless (`insufficient_permission`).
- Revocation cascades within ≤10 s despite gateway caches (`Watch` stream),
  including assignments materialized through groups.
- Supplier isolation: supplier admins operate only their own org records.

## Protocol design

- **External edge**: HTTP/2 + TLS 1.3/1.2 (`h2` ALPN). Browser flows are HTTP; partner clients use mTLS client certs (optional, verified if presented).
- **Internal service calls**: HTTP/2 over mutually-authenticated TLS (mesh CA) with pooled keep-alive connections (gateway→idp, gateway→rbac admin).
- **Gateway↔RBAC**: **gRPC over HTTP/2** (protobuf, keepalives) — incl. a **server-streaming permission-invalidation channel** so revocations propagate sub-second instead of waiting for cache TTL.
- **Gateway→IdP token status**: local JWT verification + short-TTL introspection cache with circuit breaker (fail-closed).
- Databases on a dedicated `data` network; services on `internal`; only the gateway on `edge`. Origin ingress restricted (no other service publishes ports).

## Security control mapping

| Requirement | Where implemented |
|---|---|
| Cryptographic client auth only (mTLS or private_key_jwt, aud = issuer) | idp `/par`, `/token`, `/introspect`, `/revoke` → `clientAuth()`; HS-assertions and bearer-style client secrets rejected. |
| Sender-constrained tokens (binding independent of client auth) | idp mint: `cnf.jkt` (DPoP, RFC 9449) or `cnf.x5t#S256` (RFC 8705). A client authenticating with cert may still bind tokens with DPoP. Token request without binding → rejected. |
| PAR mandatory, < 600s | idp `/par` (120 s TTL), `/authorize` rejects requests without `request_uri` and any extra params. |
| Code flow + PKCE S256, code ≤ 60 s, single use, `iss` in response | idp `/authorize` → 30 s single-use code; S256 only; redirect carries `iss`. |
| TLS 1.2+, PS256/ES256/EdDSA only | gateway + all listeners MinVersion TLS 1.2; JWT algs allow-listed; HS256/RS256/etc. rejected. |
| Deny-by-default routing, policy-bound routes | gateway config validation refuses routes without a policy; unmatched requests → 404. Malformed/suspicious requests rejected (traversal, encoded slashes, framing conflicts, header+body caps, identity-header stripping). |
| 401 / 403 / 429 / 503 semantics | gateway authz pipeline: missing/invalid/bad-binding → 401 (+ `WWW-Authenticate`); scope or RBAC deny → 403; budgets → 429; saturated/unavailable dep → 503. |
| Verify sender binding on the *external* request before rewriting | gateway validates DPoP `htu/htm` against the original URL/method before proxying; mTLS thumbprint checked at gateway TLS termination; certificate evidence forwarded to upstreams **only** over the internal mTLS hop as signed gateway context headers. |
| Separate traffic budgets per class | per-route ip/client buckets + per-route concurrency caps + global/upstream pools. |
| Revocation delay bounded; fresh decisions for sensitive writes | gateway permission cache TTL 5 s + RBAC `Watch` invalidation; `fresh_decision: true` routes bypass cache; RBAC unavailable → 503, confirmed deny → 403. |
| CRUD+approve permission matrix, auto-registered | `@CrudResource` on every RBAC controller → `PermissionRegistrar.scan()` → catalog registration at boot; `EndpointPermissionAuditor` fails startup if any endpoint lacks a binding. |
| Refresh tokens: no routine rotation | refresh grant returns the **same** refresh token; bound to the original DPoP key / mTLS cert. |

## Out of scope (documented)
JAR/JARM/message signing (FAPI 2.0 Message Signing), MFA/passkeys, HSM key protection, multi-instance shared rate counters (use Redis in prod), horizontal scaling of IdP beyond a single AZ, a browser SPA for RBAC admin (CLI-driven admin API included instead).

## Repo layout

```
trustplane/
  cmd/{gateway,idp,fapi-client}/main.go
  internal/{jwtx,dpop,mtlsx,ratelimit,breaker,ctxauth,dbx,clients,pb}
  internal/{idpserver,gateway}
  java/
    shared-lib   (GatewayContext HMAC filter, sealed Failure taxonomy,
                  @CrudResource + PermissionRegistrar/auditor, PemKeyStore)
    rbac-service (Spring Boot 4.1: gRPC decision plane + admin controllers
                  with CRUD+approve matrix + seed + watch hub)
  config/{gateway/routes.yaml, idp/clients.yaml, rbac/seed.yaml}
  deploy/pki/init.sh, deploy/sql/init.sql
  docker/{Dockerfile,Dockerfile.java}, docker-compose.yml, Makefile
```

## Build & verify

1. `make up` — pki-init generates PKI; pg + services boot healthy (gateway is the only published port).
2. `make e2e` — `fapi-client suite` runs **89 checks** end-to-end through the gateway only (FAPI flows, adversarial cases, delegated RBAC CRUD + cascading revocation), exiting non-zero on failure.
3. Host-side smoke: `make smoke` (curl discovery, deny-by-default, TLS-floor checks).

## Milestones
1. Shared crypto/libs (JWS/JWK/PKCE/DPoP/mTLS/limiters) ✅
2. IdP FAPI endpoints + stores ✅
3. Gateway (routes, authn, DPoP, RBAC PEP, proxy) ✅
4. RBAC decision plane (Go gateway ↔ Java gRPC, watch invalidation) ✅
5. RBAC admin plane in Java 21/Spring Boot (CRUD+approve auto-matrix) ✅
6. Compose + PKI + e2e suite (89 checks) green ✅
