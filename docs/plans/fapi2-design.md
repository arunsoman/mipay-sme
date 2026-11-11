# Identity, FAPI 2.0 and RBAC: the IdP owns every login, the gateway checks every request, RBAC decides every permission

Status: **draft for review** · 2026-11-11

> **Revised 2026-11-11:** login is a credential **plugin**, and the MiPay core stays the owner of users.
> - Onboarding/KYC creates every user inside an org.
> - The IdP only proves who is at the keyboard.
> - The core links the two through `identity_links`; tokens carry the core user id as `uid`.
>
> Decisions I5, I6 and I9 below, §6 and §8 reflect this. The build order and the full model are in [`idp-rbac-cutover-plan.md`](idp-rbac-cutover-plan.md) (§1). Where this document writes `Check(sub, …)`, the subject is the **core user id** (the `uid` claim), not the IdP's own `sub`.

## 1. Where we are, and where we are going

**Today (two identity systems side by side):**

```
browser ─▶ nginx ─▶ gateway (pass-through, auth: none) ─▶ MiPay backend
                                                          ├─ users + passwords (users table)
                                                          ├─ issues its own HS256 tokens (12 h)
                                                          └─ checks them (JwtAuthFilter)
partner API ─▶ gateway (FAPI 2.0: DPoP/mTLS, introspection, RBAC) ─▶ upstreams
                     ▲
trustplane IdP ──────┘  (FAPI 2.0 authorization server: its own users, used only by API clients)
```

So portal users log in to the MiPay backend, and only partner API clients use the trustplane IdP. That means two user stores, two password systems, two places for lockout, 2FA and recovery, and two audit trails.

**Target (one identity system):**

```
                     ┌──────────────────────────── trustplane ────────────────────────────┐
every request ─▶ nginx ─▶ GATEWAY ── login / auth routes ───────────────▶ IdP (login, 2FA, recovery)
   (TLS edge)         │      │                                               ▲
                      │      ├── valid credential? ── yes ──▶ upstream (MiPay backend / portal app)
                      │      │                                    with signed identity context
                      │      └── no ──▶ FAPI 2.0 flow (PAR → authorize → token, DPoP-bound) ─┘
                      │                    then the original request is let through
                      └── RBAC (platform roles)                          
```

| # | Decision |
|---|---|
| I1 | **The trustplane IdP is the only place anyone logs in**: portal users, MiPay staff and partner systems. The MiPay backend stops storing passwords and issuing tokens. |
| I2 | **Every request lands on the gateway first.** It routes login/auth requests to the IdP, checks the credential on every other request, starts the FAPI 2.0 flow when there is no valid credential, and only then lets the request through. |
| I3 | **The system follows the FAPI 2.0 Security Profile.** PAR, PKCE S256 and confidential clients only (`private_key_jwt` or mTLS); access tokens sender-constrained with DPoP or mTLS; short code lifetimes; `iss` in the authorization response; TLS 1.2+ with FAPI cipher suites. |
| I4 | **Browsers are not FAPI clients themselves.** A browser app cannot keep a client secret or a long-lived private key, and FAPI 2.0 forbids public clients. So the **gateway acts as a Backend-for-Frontend (BFF)**: it is the confidential FAPI client for the four portals, holds their tokens server-side, and gives the browser only an HttpOnly session cookie. |
| I5 | **Three owners.**<br>• **Who is legitimate** (users, their org, their status) is owned by the **MiPay core**: onboarding, using the KYC plugin, creates every user.<br>• **Who is at the keyboard** (password, 2FA, recovery, sessions) is the **IdP**, a credential plugin behind an `AuthProvider` contract, like the KYC providers. It holds credential accounts, never users, orgs or roles.<br>• **What you may do** is decided by **trustplane RBAC** (§9).<br>The core also keeps the business data (the org tree, KYC applications, workflow state) and the object-level rules that depend on it (e.g. maker ≠ checker on one application). |
| I6 | **The core links credentials to users.** `identity_links (user_id, provider, issuer, subject, status)` maps the IdP's `sub` to the core user; one user may have several links (password today; later eID, staff SSO, partner client). At enrolment the core gives the IdP the user id, and **tokens carry it as `uid`**. The gateway uses `uid` for RBAC; the core re-checks the link on every request. The migration keeps every core user id. |
| I9 | **Every user belongs to exactly one org, and the org governs the user.** No user exists outside the org tree; platform staff belong to the Platform Root org. A user can act only while they **and** their org (and every ancestor) are operational, KYC included, so deactivating an org or failing its KYC deactivates its users by default. User management is always org-scoped. |
| I7 | **Upstreams trust identity only through the gateway's signed context headers** (the existing `ctxauth` HMAC). They never see or parse user tokens. |
| I8 | **Full RBAC (NIST / ANSI INCITS 359: Core, Hierarchical, Static and Dynamic SoD) in trustplane RBAC is the single source of every permission** for people and systems. Code checks permissions for a tenant (an org), never role names. MiPay's org roles, platform review functions and onboarding rights all become RBAC roles, assignments and delegated grants (§9). |

## 2. What trustplane already does (checked in code)

| FAPI 2.0 requirement | Where | Status |
|---|---|---|
| Pushed Authorization Requests required | IdP `/par`; discovery `require_pushed_authorization_requests: true`; `/authorize` accepts only a `request_uri` | ✅ |
| Authorization code flow only | discovery `grant_types_supported: [authorization_code, refresh_token]` | ✅ |
| PKCE with S256 | IdP refuses requests without `code_challenge` or with any method other than S256 | ✅ |
| Confidential clients: `private_key_jwt` or mTLS | `token_endpoint_auth_methods_supported: [private_key_jwt, tls_client_auth]` | ✅ |
| Client assertion replay protection | IdP reserves each assertion `jti` for 10 min | ✅ |
| Sender-constrained access tokens (DPoP or mTLS) | IdP binds `cnf.jkt` / `cnf.x5t#S256`; **gateway refuses unbound tokens**, verifies DPoP proof `htm`/`htu`/`ath` and `jti` replay | ✅ |
| Short-lived codes and PAR requests | code 30 s (≤ 60 s), PAR 120 s (≤ 600 s) | ✅ |
| `iss` in the authorization response (RFC 9207) | IdP adds `iss` to success and error redirects | ✅ |
| Signing algorithms PS256 / ES256 / EdDSA only | IdP keys and discovery | ✅ |
| Refresh tokens bound to the same key, no routine rotation | IdP `token` (comment: "FAPI 2.0 Final") | ✅ |
| Token revocation + introspection; resource server fails closed | IdP `/revoke`, `/introspect`; gateway introspection with cache and circuit breaker | ✅ |
| TLS 1.2+ with FAPI cipher suites | internal mesh (`mtlsx`) | ✅ internal · ⚠️ **public edge is the host nginx: must be restricted** (§10) |
| Login with 2FA, account recovery, lockout | IdP login is a plain username/password form with a per-IP limit | ❌ (§6, 2FA design) |
| Browser apps as clients | none | ❌ needs the gateway BFF (§4) |
| MiPay users in the IdP | none | ❌ needs migration (§8) |

The authorization server and the API-client side are largely done. The work is the browser side, the user move, and the hardening around them.

## 3. The two lanes through the gateway

| | Lane A: people in a browser (the four portals) | Lane B: systems calling APIs (partners, banks, a future mobile app) |
|---|---|---|
| Who is the FAPI client | the **gateway** (BFF), one registered client per portal host | the partner system itself |
| What the caller presents | `__Host-mp_sess` cookie (HttpOnly, Secure, SameSite=Strict) | `Authorization: DPoP <access token>` + `DPoP: <proof>` (or mTLS-bound `Bearer`) |
| Where tokens live | gateway session store (encrypted), never in the browser | the partner's own systems |
| How the gateway checks | session valid and not idle/expired; refreshes the access token when near expiry | signature (JWKS), `exp`, `aud`, scopes, sender constraint (DPoP proof / mTLS), revocation (introspection), RBAC permission |
| When the check fails | page request → **302 into the FAPI flow**; API (XHR) request → **401 `login_required`** with the login URL, and the portal navigates there | **401** with `WWW-Authenticate: DPoP error="invalid_token"` (the client runs its own FAPI flow) |
| What reaches MiPay | signed context: `sub`, `sid`, `acr`, `amr`, `auth_time`, `client_id`, `scope` | the same signed context |

## 4. Lane A: what happens to every browser request

### 4.1 Decision order inside the gateway

```
request (via nginx, Host = admin.afriqpay.com …)
 │
 ├─ 1. route lookup (deny by default; unknown host/path/method → 404)
 ├─ 2. hardening: header size, body limit, per-IP and per-client rate limits (real IP: limits.trusted_proxies)
 │
 ├─ 3. is it an auth route?            /auth/login · /auth/callback · /auth/logout · /auth/session
 │       └─ yes → BFF handler (§4.2) — talks to the IdP; never forwarded to MiPay
 │
 ├─ 4. is it a public route?           portal static files, /api/env, health
 │       └─ yes → forward (no identity)
 │
 ├─ 5. credential check                 __Host-mp_sess cookie → session row
 │       ├─ missing / expired / revoked ─┬─ navigation (Accept: text/html) → 302 /auth/login?return_to=<url>
 │       │                               └─ API call                       → 401 {"error":"login_required","login":"/auth/login"}
 │       ├─ access token near expiry → refresh at the IdP (DPoP-bound refresh token, gateway's key)
 │       ├─ CSRF: state-changing methods need X-CSRF = session.csrf (double-submit, from /auth/session)
 │       └─ step-up needed? (route policy acr / max_age) → 401 {"error":"step_up_required", acr, max_age}
 │
 ├─ 6. RBAC: route permission (e.g. platform functions) via trustplane RBAC (gRPC)
 │
 └─ 7. forward to upstream with signed context headers (ctxauth HMAC); strip any incoming ones
```

Every step above, with its positive and negative outcomes, is spelled out in **Appendix A**. The RBAC query is in **Appendix B**. Why nothing reaches a controller unless all of this passes is in **Appendix C**, and how the backend rejects anything that somehow does is in **Appendix D**.

### 4.2 The FAPI 2.0 login flow the gateway runs (BFF as confidential client)

```
browser                      gateway (client: portal-admin)                IdP
  │ GET /auth/login?return_to=/kyc
  │                          1. new DPoP key for this session (ES256, kept server-side)
  │                          2. state, nonce, PKCE verifier → short "login transaction" (5 min)
  │                          3. POST /par  ────────────────────────────────▶  checks client assertion
  │                               client_assertion (private_key_jwt, PS256)       (private_key_jwt), PKCE S256,
  │                               response_type=code, scope=openid …              redirect_uri exact match,
  │                               redirect_uri=https://admin.afriqpay.com/auth/callback
  │                               code_challenge(S256), state, nonce,
  │                               dpop_jkt=<thumbprint>, acr_values (if step-up)
  │                          ◀──────────────────────────── request_uri (120 s)
  │ ◀── 302 https://auth.afriqpay.com/authorize?client_id=portal-admin&request_uri=…
  │ ───────────────────────────────────────────────────────────────────────▶ login page: password, 2FA,
  │                                                                          recovery links (TPTS style)
  │ ◀──────────────────────── 302 /auth/callback?code=…&state=…&iss=https://auth.afriqpay.com
  │ GET /auth/callback ─────▶ 4. check state (transaction), iss (RFC 9207)
  │                          5. POST /token ───────────────────────────────▶ code (30 s, single use),
  │                               client_assertion, code_verifier,                PKCE, client auth,
  │                               DPoP proof (session key)                        DPoP → cnf.jkt
  │                          ◀──── access token (5 min, DPoP-bound), ID token, refresh token (bound)
  │                          6. verify ID token (sig, iss, aud, nonce, exp); read sub, acr, amr, auth_time
  │                          7. create session: tokens encrypted at rest, csrf, idle 30 min / absolute 12 h
  │ ◀── 302 /kyc   Set-Cookie: __Host-mp_sess=…; HttpOnly; Secure; SameSite=Strict; Path=/
```

- **A short-lived transaction cookie** (`__Host-mp_tx`, SameSite=Lax, 5 min) ties the callback to the browser that started the login. That stops login CSRF and code injection.
- **Logout** (`/auth/logout`):
  - revoke the refresh token at the IdP;
  - delete the session;
  - redirect to the IdP logout to end the IdP session.
- **IdP-initiated logout** (account disabled, password reset, "this wasn't me") uses **OIDC Back-Channel Logout** to the gateway. The gateway deletes every session of that `sub` at once.
- **Sessions live in the trustplane Postgres** (`gw_sessions`). Tokens in it are encrypted with a gateway key from `/pki`. The gateway stays horizontally scalable.

### 4.3 Step-up (sensitive actions)

Step-up uses the standard **RFC 9470 Step-Up Authentication Challenge**:
- When an action needs a stronger or fresher login, the gateway route policy (or MiPay, through a response header) asks for `acr_values=aal2` and/or `max_age=300`.
- The portal shows "Confirm it's you", and the gateway runs §4.2 again with those parameters.
- The IdP asks only for the second factor, because the session already has the password.
- The new session carries the higher `acr` and a fresh `auth_time`, and the original request is replayed.

For **money movement and KYC decisions**, use **Rich Authorization Requests (RFC 9396)** with `authorization_details`, e.g. `{"type":"payout","amount":"12400.00","currency":"NAD","payee":"…"}`:
- the IdP shows exactly what is being approved (on the push approval or the login page);
- the resulting access token is good **only for that transaction**.

This is the FAPI-native form of the "transaction binding" in the 2FA design.

## 5. Lane B: API clients

This lane is unchanged and already works:
1. The client registers with a JWKS (or certificate).
2. It runs PAR → authorize → token itself, with DPoP or mTLS.
3. It calls `https://api.afriqpay.com/...` with `Authorization: DPoP …` and a DPoP proof.
4. The gateway enforces signature, expiry, audience, scope, sender constraint, revocation and the RBAC permission.

The only additions are a public host and the route table for the APIs we decide to open to partners.

**One fix is needed for it to work behind nginx.** The gateway computes the DPoP `htu` from `r.Host`. Behind nginx, that host is rewritten to the internal vhost, so proofs signed for the public URL would fail. The gateway must build `htu` from `X-Forwarded-Host`, and only when the peer is a trusted proxy (the same rule as `limits.trusted_proxies`).

## 6. The IdP becomes a credential plugin

The IdP holds **credential accounts** for users the core created. It has no org, role, KYC or business status. The contract (`AuthProvider`), the `identity_links` table and the flows are in [`idp-rbac-cutover-plan.md`](idp-rbac-cutover-plan.md) §1.

| Capability | Today | Target in the IdP |
|---|---|---|
| Accounts | `users(user_id, password_hash, scopes, active)` | `accounts(subject, uid, login_name, phone, display_name, password_hash, state ENROLLING/ACTIVE/DISABLED, failed_attempts, locked_until, password_changed_at)`. `uid` is the core user id; the IdP never decides who is legitimate |
| Login page | plain HTML form | TPTS-styled login: username or phone + password; "Forgot password?"; "Can't get in?" |
| Lockout | per-IP rate limit on the form | + per-account back-off, then a temporary lock; audited |
| 2FA, recovery | — | everything in `2fa-design.md`, now implemented here (push number-matching, passkeys, TOTP, SMS fallback, recovery codes, assisted recovery, cooling-off) |
| Activation (first password + 2FA enrolment) | MiPay `ActivationService` | IdP "set up your account" link (72 h, single use, stored hashed) |
| Merchant phone login | MiPay OTP (in-memory) | IdP passwordless phone-OTP method (`acr` aal1), plus a transaction PIN or push for step-up |
| Enrolment API | — | **internal API** over mTLS, for the MiPay core only: enrol a credential account for an existing core user (returns the subject and a one-time set-up link); reset link; disable / enable; revoke sessions; update login name or phone; delete |
| Events to the core | — | `CREDENTIAL_SET`, `CREDENTIAL_RESET`, `LOCKED`, `UNLOCKED` (later factor changes), signed, retried, de-duplicated |
| Notifications | — | **Through the comms plane only, addressed by the core user id** (`user:<uid>`). The IdP sends what happens inside a login (OTP, 2FA codes, push, self-service reset links, security notices); the core sends what it starts (set-up and recovery links, onboarding). The core is the only writer of contact details, so the IdP holds no email address or delivery phone (`2fa-design.md` D10) |
| 2FA switch | — | `mfa.mode = off \| shadow \| enforce`, shipped `off`; factors that need a comms channel are offered only while it is live (`2fa-design.md` §4) |
| Token claims | `sub`, scopes | + **`uid`** (core user id), `acr`, `amr`, `auth_time`, `sid`. No org or role claims: the core and RBAC are the authority |

**How the MiPay core uses the IdP afterwards:**
- **Users are created only by the core, inside an org.** That happens at KYC go-live (the org admin), when an org admin adds a team member, or when a platform admin adds staff to the Platform Root org. The core then calls `enrol` with the user id and delivers the set-up link. When the IdP reports `CREDENTIAL_SET`, the core makes the link and the user ACTIVE. This replaces `ActivationService.issue` and fixes today's wrong-portal link.
- **The KYC applicant admin** of a PENDING org is enrolled when the application starts, and is limited to the KYC application screens until go-live.
- **Suspend, deactivate, archive, or "this wasn't me":** the core changes its own state, then a single reconcile computes which IdP accounts should be enabled. It calls `disable` / `enable` / `revoke-sessions` to match, and the IdP back-channel-logs-out the gateway. Org suspensions, KYC lapses and KYC rejections reach every user of the org's subtree this way.
- **Every request:** the core reads `iss`, `sub` and `uid` from the signed context, finds the link, and refuses the request unless the link is active, `uid` matches, the user is ACTIVE and their org (and every ancestor) is operational.

## 7. What changes in each component

| Component | Change |
|---|---|
| **nginx (host)** | Send **all** traffic for the portal hosts to the gateway, not only `/api`. Add `auth.afriqpay.com` → gateway (IdP vhost) and `api.afriqpay.com` → gateway (partner APIs). FAPI TLS settings (§10). |
| **Gateway** | BFF module (§4.2) with `/auth/*` handlers, session store, CSRF, refresh, back-channel logout; portal static upstreams; step-up challenges; `htu` from a trusted `X-Forwarded-Host`; route policies get `session: required`, `acr`, `max_age` and `permission`. |
| **IdP** | Public issuer `https://auth.afriqpay.com`; credential accounts with `uid`; TPTS login UI; lockout; 2FA and recovery; enrolment API and events to the core; `uid` claim; RAR (`authorization_details`); RFC 9470 step-up; back-channel logout. Remove the dev seed users in production. |
| **RBAC** | The full model in §9: org tree as tenants with `subtree` scope; role hierarchy; SSD/DSD sets; session role activation; onboarding rights as delegated grants; four-eyes for privileged assignments; access reviews; decision ids; extra review APIs. |
| **MiPay backend** | Remove `/api/auth/login`, `/otp`, `/activate`, `JwtService` and `JwtAuthFilter`. Add the `AuthProvider` contract, `identity_links`, `UserLifecycleService` (the only place users are created or changed, always inside an org), `AuthSyncService.reconcile` and `GatewayIdentityFilter` + `IdentityResolver` (verify the `ctxauth` HMAC, then resolve `iss`/`sub`/`uid` through the link). Replace hard-coded role checks (`isOrgAdmin`, `platformStaff`, `user_platform_functions`) with RBAC `Check(uid, org, permission)`, recording the decision id in audit rows. Org and user changes sync to RBAC (tenant, holds, assignments, grants). The `users` table stays the user master: profile, org (`NOT NULL`), status; minus `password_hash` and role columns. |
| **Portals (Angular)** | No token handling. The login page becomes a "Sign in" button to `/auth/login`. `/auth/session` returns who is signed in plus the CSRF token. Send `X-CSRF` on POST/PUT/DELETE. Handle 401 `login_required` / `step_up_required` by navigating or showing the step-up dialog. Logout goes to `/auth/logout`. |

## 8. Migration plan

Each step can be deployed and rolled back on its own. The old login keeps working until step 5.

| Step | What | Rollback | Rough effort |
|---|---|---|---|
| **0. Harden the edge** | FAPI TLS settings on nginx (§10); `htu` from a trusted `X-Forwarded-Host`; IdP public issuer `auth.afriqpay.com` with its own certificate; remove the IdP dev seed users on the server | revert config | 0.5 day |
| **1. IdP as credential plugin** | Credential accounts with `uid`; enrolment API (mTLS); events to the core; `uid` claim; TPTS login page; per-account lockout; set-up/reset links returned to the core | IdP only, not yet used by MiPay | 4–5 days |
| **2. Bind users to orgs, then enrol them** | First, bind every user with no org to its org (or Platform Root), list the ones that match nothing for a manual decision, and make `users.org_id NOT NULL`. Then, for every MiPay user, enrol a credential account with `uid` = the user id, copy the bcrypt hash (strip Spring's `{bcrypt}` prefix; Go's bcrypt reads `$2a$`), and write the `identity_links` row. PENDING users get a fresh set-up link. From then on, the core drives the IdP through `UserLifecycleService` and the reconcile. | IdP accounts unused until step 4 | 2 days |
| **2b. RBAC model** | The permission grid (Appendix G: rows, fixed verb columns, bitmask role grids, cell registry, SoD as cell pairs). Add `subtree` scope, `role_inheritance`, `ssd_sets` / `dsd_sets`, session activation, delegation ceilings and review APIs to trustplane RBAC; seed the MiPay permission catalogue and roles (§9.2–9.4). | RBAC only, not yet consulted | 4–5 days |
| **2c. Copy authorizations into RBAC** | One-off job: each org → tenant (with closure for `subtree`); each user's ADMIN/STAFF → `org_admin` / `org_staff` assignment; `user_platform_functions` → platform role assignments; `org_rights` DIRECT/CEILING → delegated grants. Run the SSD check over the result and resolve conflicts before go-live (e.g. today `ops-admin-mindlabs` must hold no review role). MiPay then writes every change to RBAC. | RBAC unused until step 3 | 1–2 days |
| **3. Gateway BFF + dual-stack** | Build §4 behind a per-host flag (`bff: true`). MiPay accepts **either** its old token **or** the gateway's signed context (`GatewayContextFilter` first). Switch `admin.` on the server; test; then the three org portals. | flip the flag back per host | 4–5 days |
| **4. Portals on the IdP** | Portals drop token storage and use `/auth/*`, `X-CSRF` and step-up handling; release per portal. | previous portal image | 2 days |
| **4b. Enforce RBAC** | Add `@RequiresPermission` to every handler and the endpoint manifest publisher; RBAC `RegisterEndpoints` / `ListEndpoints`; gateway compiles route policies from RBAC (Appendix F); MiPay services call RBAC `Check` instead of hard-coded role checks. Run in **shadow mode** first (decide both ways, log mismatches, enforce the old rule) for a week, then enforce RBAC. | switch back to shadow mode | 4–5 days |
| **4d. Object-level audit + authorization matrix** | Audit the 71 id-taking endpoints (Appendix E, F3); add the generated authorization matrix to CI (E.3); fix every unexpected allow | — | 4–5 days |
| **4c. Roles & access screens** | Admin portal **Roles & access** (catalogue, holders, SoD conflicts, four-eyes approvals, break-glass); role picker in org-portal Users; the first quarterly access review. | hide the screens | 3–4 days |
| **5. Cut over** | Gateway refuses legacy tokens. MiPay removes the login, OTP and activation endpoints, `JwtService` and password hashes (after a backup). Onboarding and users call the IdP admin API. | redeploy the previous backend and gateway (password hashes restored from the backup) | 1–2 days |
| **6. 2FA and recovery in the IdP** | Phases 1–5 of `2fa-design.md`, now built in the IdP; RFC 9470 step-up wired through the gateway. | per factor, behind policy flags | 2–3 weeks |
| **7. Partner API lane** | `api.afriqpay.com`; register the first partner client; publish the API route table | remove the route | 1–2 days per API set |
| **8. Conformance** | Run the **OpenID Foundation FAPI 2.0 Security Profile conformance suite** (authorization server and resource server test plans) against `auth.` and `api.afriqpay.com`; fix findings; keep the suite in CI. Optionally certify. | — | 2–3 days, plus fixes |

**Order of risk:**
- Steps 0–2 change nothing users see.
- Step 3 changes the login screen for one portal at a time.
- Step 4b runs in shadow mode first, so RBAC is proven to agree with today's rules before it enforces anything.
- Step 5 is the point of no return, so do it only after a week or more of steps 3–4 running cleanly.

## 9. Authorization: full RBAC (NIST / ANSI INCITS 359)

**Goal:** every permission decision for people and systems comes from **one RBAC model in trustplane RBAC** (the policy decision point). The gateway and the MiPay backend are the enforcement points. The model implements all four parts of the standard: Core, Hierarchical, Static separation of duty (SSD) and Dynamic separation of duty (DSD).

### 9.1 What exists, and what's missing

| Standard component | trustplane RBAC today | Needed |
|---|---|---|
| Core: users, roles, permissions, assignment, role-permission mapping | ✅ `users`, `roles`, `permissions`, `role_permissions`, `assignments` (per tenant, scope `own` / `all`), groups | add `subtree` scope over the MiPay org tree |
| Core: sessions (the roles active in a session) | ❌ every assigned role is always active | session role activation (§9.5) |
| Core: review functions ("who has X", "what can Y do") | partial (`ListPermissions`) | `UserPermissions`, `RoleMembers`, `PermissionRoles`, `ExplainDecision` |
| Hierarchical RBAC (senior roles inherit junior ones) | ❌ | `role_inheritance` with cycle check (§9.3) |
| Static SoD (roles nobody may hold together) | ❌ | `ssd_sets` checked on every assignment (§9.4) |
| Dynamic SoD (roles nobody may use together in one session or one case) | ❌ (only the KYC engine's own maker ≠ checker rules) | `dsd_sets` checked at session activation, plus per-object history rules (§9.4) |
| Delegation with limits | ✅ `grants.can_delegate`, `expires_at` | used for MiPay onboarding rights (§9.6) |
| Audit of every change and decision | ✅ `audit_log`, `decision_id` | decision ids written into MiPay audit rows |
| Access reviews / recertification | ❌ | quarterly review campaigns (§9.7) |

### 9.2 The model, mapped onto MiPay

| RBAC concept | MiPay meaning |
|---|---|
| **User** | the core user id (the `uid` claim; the IdP's own `sub` is mapped to it through `identity_links`). Every user has one home org (I9) |
| **Tenant** | an organization in the tree (Platform Root, manufacturers, distributors, suppliers, merchants). A scope is `own` (that org), `subtree` (that org and everything below it, from `org_closures`) or `all` (platform staff) |
| **Permission** | one cell of the **2-D grid** (Appendix G): `<service>.<resource>.<verb>`, with the verb from a fixed list of 10 (`read · create · modify · delete · submit · authorize · execute · export · administer · simulate`). It is the only thing code checks. For example: `kyc.application.view`, `kyc.application.edit`, `kyc.application.submit`, `kyc.task.decide.ops_review`, `kyc.task.decide.compliance_review`, `kyc.task.decide.senior_approval`, `org.child.onboard`, `org.rights.narrow`, `org.suspend`, `org.user.create`, `org.user.deactivate`, `rbac.role.assign`, `payout.initiate`, `payout.approve`, `audit.read` |
| **Role** | a named bundle of permissions. Org roles: `org_admin`, `org_staff`, `org_viewer`. Platform roles: `platform_ops_reviewer`, `platform_compliance_officer`, `platform_mlro`, `platform_senior_approver`, `platform_user_admin`, `platform_auditor`, `platform_break_glass` |
| **Assignment** | user + role + tenant + scope, e.g. `selma` = `org_admin` on *Okahandja Fresh Market* (own); `ops-admin-ops` = `platform_ops_reviewer` on Platform Root (all) |

**Code never checks a role name.** It checks a permission, always for a tenant: `Check(sub, tenant=<org id>, operation="kyc.application.edit")`. Today's hard-coded rules map onto it like this:

| Today (in code) | Becomes |
|---|---|
| `caller.isOrgAdmin()`, "the org's ADMIN or its parent's ADMIN may edit KYC" | `kyc.application.edit` granted to `org_admin` with scope `own`; the parent's `org_admin` assignment has scope `subtree` |
| `platformStaff(caller)` | the specific platform permission each route needs |
| `user_platform_functions` (OPS_REVIEWER …) | platform role assignments in RBAC (the table is retired) |
| `MINDLABS_ADMIN has no review function` | SSD constraint (§9.4) |

### 9.3 Role hierarchy

```
org_admin ─▶ org_staff ─▶ org_viewer                        (senior inherits junior's permissions)
platform_user_admin ─▶ (none: deliberately no review permissions)
platform_compliance_officer ─▶ platform_auditor
platform_mlro ─▶ platform_auditor
platform_ops_reviewer ─▶ platform_auditor
platform_senior_approver ─▶ platform_auditor
```

- Review roles inherit only read access, never each other's decisions. Separate stages need separate people.
- Inheritance is acyclic: a cycle is refused at write time, and the boot validator refuses a cyclic seed.

### 9.4 Separation of duty

**Static SoD (no user may *hold* these together):**

| Set | Why |
|---|---|
| {`platform_user_admin`, any `platform_*_reviewer/officer/mlro/senior_approver`} | whoever grants access must not also approve customers (today's "MINDLABS_ADMIN gets no review function") |
| {`platform_ops_reviewer`, `platform_senior_approver`} | the senior-management approval for high-risk / PIP cases (FIA s.23) must be a different person from the first-line reviewer |
| {`platform_mlro`, `platform_user_admin`} | the money-laundering reporting officer must stay independent of access administration |
| {`platform_break_glass`, any other platform role} | emergency access is a separate, monitored identity |

**Dynamic SoD (no user may *use* these together):**
- **In one session:** {`platform_ops_reviewer`, `platform_compliance_officer`}. Someone who legitimately holds both (a small team) activates one per session, so the same login never clears two stages in one sitting.
- **Per object (history-based), enforced by the KYC workflow engine and declared in RBAC as policy:**
  - the maker of an application never decides any task on it;
  - one person acts on at most one task per submission;
  - the onboarder never approves the org they onboarded;
  - for payouts, the initiator never approves.

  RBAC exposes these as constraints (`object_sod_rules`), and the engine reports each decision to RBAC so they are audited centrally.

**Every assignment is checked against SSD,** whether made through the admin portal, the API or a migration. A violation returns `409 SOD_CONFLICT` naming the conflicting set.

### 9.5 Sessions and role activation

- The gateway session (§4.2) carries the **active roles**. By default, a user's assignments are active for the tenant they are working in.
- Roles in a DSD set, and `platform_break_glass`, must be **activated explicitly**: "Act as Compliance officer". The IdP is asked for `acr=aal2`, the activation is audited, and it lasts at most 8 h.
- The signed context sent to upstreams includes `active_roles`.

### 9.6 Onboarding rights as delegated grants

MiPay's DIRECT / CEILING onboarding rights are delegation, and they map onto the existing `grants` model:

| MiPay today | RBAC |
|---|---|
| Org X may onboard MERCHANT directly | grant on tenant X: permission `org.child.onboard`, constraint `child_type=MERCHANT`, scope `own` |
| CEILING: X's whole subtree may at most onboard {MERCHANT, SUPPLIER} | the same grant with `can_delegate=true` and a delegation ceiling. A child's grants must be a subset of the parent's delegable grants (checked at write) |
| Narrow only, never widen | revoking a grant cascades down the subtree; re-granting more than the parent holds is refused |

**A user may onboard an org of type T only if both are true:**
1. they have `org.child.onboard` through an assignment on their org;
2. the org has a grant for type T.

Both are checked in one RBAC `Check` (permission plus tenant entitlement).

### 9.7 Administration, review and audit

- **Granting roles:**
  - org ADMINs grant `org_staff` / `org_admin` within their own org;
  - MiPay staff with `platform_user_admin` grant platform roles;
  - **privileged platform roles need a second approver** (four-eyes) before the assignment becomes active.
- **Time-bound roles:** an assignment may carry `expires_at`; break-glass always does (max 4 h), and its use alerts compliance.
- **Access reviews (recertification):**
  - each quarter, every org ADMIN confirms or removes each member's role;
  - platform roles are reviewed by compliance;
  - unconfirmed assignments expire after 14 days.
- **Audit:**
  - every assignment, revocation, activation and decision is in the RBAC `audit_log`;
  - MiPay audit rows carry the RBAC `decision_id`, so "who did X, and which rule allowed it" is answerable end to end.
- **Screens:**
  - admin portal → **Roles & access**: a role catalogue with permissions, who holds what, SoD conflicts, pending approvals and review campaigns;
  - org portals → **Users**: the role picker shows only roles the caller may grant.

### 9.8 Where enforcement happens

| Layer | Enforces |
|---|---|
| Gateway (PEP) | each route's `permission` with the tenant taken from the path (e.g. `/api/kyc/orgs/{orgId}` → tenant `orgId`), before the request reaches MiPay; deny by default |
| MiPay backend (PEP) | checks that depend on the request body or on object state (e.g. the stage of a KYC task, which org a body field names): RBAC `Check` from the service; plus object-level SoD and suspension |
| RBAC (PDP) | the single policy: roles, hierarchy, SSD/DSD, grants, decisions with ids |

Decisions are cached by the gateway for at most 3 s per (subject, tenant, operation, tenant version). RBAC `Watch` streams invalidate the cache immediately on change, and the cache fails closed if RBAC is unreachable.

## 10. Edge TLS for FAPI 2.0 (host nginx)

```
ssl_protocols TLSv1.2 TLSv1.3;
ssl_prefer_server_ciphers on;
ssl_ciphers ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384;
ssl_ecdh_curve X25519:prime256v1:secp384r1;
add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
```

- **Scope:** these four TLS 1.2 suites are the ones FAPI 2.0 allows (TLS 1.3 suites are all allowed). Set them **in the `mipay-sme` server blocks only**, so the other sites on the shared host are unaffected.
- **HSTS:** add it only once every `afriqpay.com` subdomain is HTTPS-only.

## 11. Security invariants

1. Nothing but the IdP ever sees a password or a second factor. The MiPay backend never receives them, and the browser never holds an access or refresh token.
2. Every token accepted on an API is sender-constrained (DPoP or mTLS). Bearer-only tokens are refused.
3. Upstreams trust identity only through the gateway's HMAC-signed context. The gateway strips any context headers that arrive from outside.
4. Every route has a policy; unknown routes get 404. Auth routes are the only ones that reach the IdP.
5. The session cookie is `__Host-` prefixed, HttpOnly, Secure and SameSite=Strict. State-changing calls also need the CSRF header.
6. Disabling a user at the IdP ends every session within one request, through back-channel logout and introspection. Suspending an org still blocks everyone below it within one request, through MiPay's per-request check.
7. Client keys (gateway BFF, partners) are asymmetric, rotated through JWKS, and never shared between portals.
8. Every permission decision comes from RBAC, for a specific tenant, deny by default; code never checks role names. If RBAC is unreachable, requests are refused (fail closed).
9. No assignment can violate a static SoD set, from any path (screen, API, migration). Dynamic SoD sets and per-object SoD rules are enforced at activation and at decision time.
10. Privileged platform roles need two people to grant; every grant can expire; every assignment is re-certified each quarter or lapses.

## 12. Tests

- **Gateway BFF:**
  - state, nonce and `iss` mismatch are refused;
  - a replayed callback is refused;
  - a missing or expired session gets a 302 for pages and a 401 for API calls;
  - a missing or wrong CSRF header is refused;
  - an incoming forged context header is stripped;
  - refresh happens near expiry;
  - back-channel logout ends the sessions;
  - step-up returns `step_up_required` and succeeds after an `aal2` login.
- **IdP:**
  - the user copy keeps ids and passwords working;
  - lockout works;
  - activation and reset links are single-use and expire;
  - the admin API refuses non-mTLS callers;
  - RAR shows the exact amount and payee, and the token cannot be used for another payout.
- **MiPay:**
  - a request without valid context is refused;
  - a valid context for a suspended org or user is refused;
  - onboarding provisions the IdP user and the KYC go-live triggers the IdP activation link.
- **RBAC:**
  - core: an assignment grants exactly its role's permissions on its tenant; `subtree` covers descendants and not siblings;
  - hierarchy: `org_admin` inherits `org_staff`; a cycle is refused;
  - SSD: assigning a conflicting role returns `SOD_CONFLICT`, through the screen, the API and the migration job;
  - DSD: activating both roles of a DSD set in one session is refused; maker ≠ checker and initiator ≠ approver hold per object;
  - delegation: a child's grant above the parent's ceiling is refused; revoking cascades down the subtree;
  - four-eyes: a privileged assignment stays pending until a second admin approves it; expiry and break-glass alerting work;
  - shadow mode: zero mismatches against today's rules over the existing UAT packs before enforcing;
  - fail-closed: with RBAC down, protected routes return 503 and nothing is allowed from a stale cache.
- **Pen-test resistance:** the authorization matrix and object-swap / body-tampering suites of Appendix E.3 in CI; authenticated ZAP + replay-as-lower-privilege scans; a third-party penetration test before real customers.
- **FAPI conformance:** the OpenID Foundation suite (step 8), plus the existing `fapi-client` end-to-end suite against the server.
- **End to end (headless):** log in to each portal via the IdP; onboard → KYC → approve → activation email → set password and 2FA → log in to the right portal.

## 13. Open questions

1. **Public names:** `auth.afriqpay.com` for the IdP (`idp.afriqpay.com` is taken by the old build — reuse it after decommissioning?) and `api.afriqpay.com` for partner APIs.
2. **Email/SMS provider** for activation and recovery messages (Amazon SES on the same AWS account? SMS provider for Namibia?). This is a prerequisite for step 1.
3. **Session lengths:** idle 30 min and absolute 12 h for org users; shorter for MiPay staff (e.g. idle 15 min, absolute 8 h)?
4. **Certification:** do we want formal OpenID FAPI 2.0 certification (fee, published results), or conformance testing only?
5. **Old afriq-platform build:** retire its IdP and API services once this is live?
6. **SoD sets and the permission catalogue:** compliance to confirm the SSD/DSD sets in §9.4 and the role-to-permission table before seeding (small teams may need the DSD session approach rather than SSD for some pairs).
7. **Access-review cadence:** quarterly for everyone, or monthly for privileged platform roles?

---

# Appendices: the detailed behaviour

## Appendix A. Request flows, positive and negative

Notation: **GW** = trustplane gateway, **IdP** = trustplane IdP, **PDP** = trustplane RBAC, **API** = MiPay backend.

**"Never forwarded"** means the request is answered by the gateway and no MiPay controller code runs.

### A1. First visit and login (Lane A)

**Positive path:**

| # | Step | Who | Check | Result |
|---|---|---|---|---|
| 1 | Browser opens `https://admin.afriqpay.com/kyc` | GW | route `/` (portal app, public) | portal app served |
| 2 | Portal calls `GET /auth/session` | GW | no `__Host-mp_sess` cookie | `401 {"error":"login_required","login":"/auth/login?return_to=/kyc"}` (never forwarded) |
| 3 | Portal navigates to `/auth/login?return_to=/kyc` | GW | `return_to` is a same-host relative path (open-redirect guard) | creates a login transaction (state, nonce, PKCE verifier, new DPoP key), sets `__Host-mp_tx` |
| 4 | GW → IdP `POST /par` | IdP | `private_key_jwt` assertion valid (signature, `aud`, `exp`, unused `jti`); `redirect_uri` registered for `portal-admin`; PKCE S256; `dpop_jkt` present | `201 request_uri` (120 s) |
| 5 | Browser → IdP `/authorize?client_id=portal-admin&request_uri=…` | IdP | `request_uri` exists, not expired, belongs to `portal-admin` | login page |
| 6 | User enters username and password | IdP | user exists and is ACTIVE; bcrypt matches; per-account and per-IP limits not exceeded | 2FA step (per policy) |
| 7 | User approves the push (taps 47) | IdP | device signature, number, challenge not expired | session at IdP; `302 /auth/callback?code&state&iss` |
| 8 | Browser → GW `/auth/callback` | GW | `state` = transaction; `iss` = expected issuer; transaction cookie matches | — |
| 9 | GW → IdP `POST /token` | IdP | code unused and < 30 s old; PKCE verifier; client assertion; DPoP proof → `cnf.jkt` | access token (5 min, DPoP-bound), ID token, refresh token |
| 10 | GW verifies the ID token | GW | signature (JWKS), `iss`, `aud=portal-admin`, `nonce`, `exp`, `acr` meets the portal minimum | session row created; `Set-Cookie: __Host-mp_sess`; `302 /kyc` |
| 11 | Portal calls `GET /auth/session` again | GW | valid session | `200 {sub, name, acr, csrf, orgs:[…], permissions:{orgId:[…]}}` (permissions for UI hints, from the PDP) |

**Negative cases:**

| Where it fails | What happens | User sees |
|---|---|---|
| 3: `return_to` points to another host | GW ignores it and uses `/` | normal login, lands on home |
| 4: client assertion invalid (key rotated wrongly, clock skew) | IdP `401 invalid_client`; GW logs an alert | "Sign-in is temporarily unavailable" (no detail) |
| 5: `request_uri` expired (user idled > 120 s) | IdP `400 invalid_request_uri` | "Your sign-in timed out" → retry link restarts at step 3 |
| 6: wrong password / unknown user | IdP counts the failure; same message for both | "Unknown user or wrong password" |
| 6: 5 failures in 15 min for the account | account back-off (1, 2, 4 … min), audited, email to the user | "Too many attempts, try again in N minutes" |
| 6: user PENDING / SUSPENDED / ARCHIVED | refused like a wrong password (no account enumeration); audit records the real reason | same generic message |
| 7: push denied / wrong number / expired | challenge failed; prompt-flood rules (2FA design §3.1) | "Sign-in not approved"; can use another factor |
| 8: `state` unknown or used, transaction cookie missing | GW `400 invalid_state`; no token request is made | "Sign-in could not be completed" → restart |
| 8: `iss` ≠ expected (mix-up attack) | GW `400 invalid_issuer` | same |
| 8: IdP returned `error=access_denied` | GW drops the transaction | "Sign-in cancelled" |
| 9: code expired, replayed or PKCE mismatch | IdP `400 invalid_grant` (a replayed code also revokes tokens already issued for it) | "Sign-in expired, try again" |
| 10: ID token `nonce` / `aud` / signature wrong | GW refuses; no session | "Sign-in could not be completed"; alert |
| 10: `acr` below the portal minimum (e.g. admin portal needs `aal2`) | GW re-runs from step 3 with `acr_values=aal2` | asked for the second factor |

### A2. An API call by a signed-in user (Lane A)

**Positive path** (`PUT /api/kyc/orgs/{orgId}` save draft):

| # | Who | Check | Pass |
|---|---|---|---|
| 1 | GW | route exists for host + path + method; policy `session: required, permission: kyc.application.edit, tenant: {orgId}` | continue |
| 2 | GW | hardening; IP rate limit; body ≤ limit | continue |
| 3 | GW | session cookie → session row: not idle (> 30 min), not past absolute (12 h), not revoked | continue |
| 4 | GW | `X-CSRF` header = session CSRF (unsafe method) | continue |
| 5 | GW | access token expires in < 60 s → refresh at IdP (DPoP-bound refresh token) | new access token in the session |
| 6 | GW | client rate limit (per `sub`) | continue |
| 7 | GW → PDP | `Check(sub, tenant=orgId, op=kyc.application.edit, session, active_roles)` | `allowed`, `decision_id` |
| 8 | GW | route obligations (`acr`, `max_age`) satisfied by the session | continue |
| 9 | GW | signs context v2 (Appendix D.2), strips inbound `X-SME-*`, forwards | — |
| 10 | API (Spring Security) | context signature, expiry, method/path binding, replay (Appendix D) | authenticated principal |
| 11 | API (`@PreAuthorize`) | `@authz.can('kyc.application.edit', #orgId)`: gateway decision for this tenant, else PDP | allowed |
| 12 | API (service) | object rules: application is DRAFT, org not suspended, optimistic version | `200`; audit row with both `decision_id`s |

**Negative cases:**

| Fails at | Status (who answers) | Body / header | Forwarded? |
|---|---|---|---|
| 1: no such route / method | `404` / `405` (GW) | `{"error":"not_found"}` | no |
| 2: too many requests from this IP | `429` (GW) | `Retry-After: 10` | no |
| 2: body too large | `413` (GW) | `request_too_large` | no |
| 3: no / unknown / expired / revoked session | `401` (GW) | `{"error":"login_required"}` | no |
| 4: CSRF header missing or wrong | `403` (GW) | `{"error":"csrf_failed"}` | no |
| 5: refresh refused (user disabled, token revoked) | `401` (GW), session deleted | `login_required` | no |
| 7: PDP says no | `403` (GW) | `insufficient_permission: subject lacks "kyc.application.edit" in tenant "<orgId>"` | no |
| 7: PDP unreachable, no valid cached decision | `503` (GW) | `authorization service unreachable` (fail closed) | no |
| 8: needs fresher or stronger login | `401` (GW) | `WWW-Authenticate: Bearer error="insufficient_user_authentication", acr_values="aal2", max_age=300` → portal opens step-up (A3) | no |
| 10: context missing / forged / stale / replayed (should never happen through GW) | `401` (API filter) | `GATEWAY_CONTEXT_INVALID`; security alert | reached the backend, rejected **before any controller** |
| 11: permission not present for this tenant (e.g. the body names another org) | `403` (API, Spring) | `ACCESS_DENIED` | controller method not executed |
| 12: org suspended / application locked / version conflict | `403` / `409` (API service) | `ORG_SUSPENDED` / `KYC_LOCKED` / `KYC_VERSION_CONFLICT` | handled in the service |

### A3. Step-up (fresh second factor before a sensitive action)

| # | Positive path |
|---|---|
| 1 | Reviewer clicks **Approve** on a KYC task → `POST /api/kyc/tasks/{id}/decision` |
| 2 | GW: route policy `acr: aal2, max_age: 300, rar: kyc_decision`; session `auth_time` is 40 min old → `401 insufficient_user_authentication` + `{"step_up":"/auth/login?step_up=1&return_to=…","authorization_details":[…]}` |
| 3 | Portal opens the step-up popup → GW runs PAR with `acr_values=aal2`, `max_age=0` and `authorization_details=[{"type":"kyc_decision","task":"…","org":"…","outcome":"APPROVE"}]` |
| 4 | IdP already has the password; asks only for the second factor; push shows "Approve KYC for Okahandja Fresh Market"; user approves |
| 5 | Callback → new access token carrying the `authorization_details`; session `acr=aal2`, `auth_time=now` |
| 6 | Portal replays the request; GW checks the token's `authorization_details` match this task and outcome; forwards |
| 7 | API records the decision with the step-up `decision_id` |

| Negative | Result |
|---|---|
| User cancels or denies the push | `access_denied` → popup shows "Not approved", nothing is sent to MiPay |
| Replayed request for a **different** task or outcome with the same token | GW `403 authorization_details_mismatch` |
| Step-up older than `max_age` | `401` again |

### A4. Logout and forced logout

| Case | Flow |
|---|---|
| User clicks **Sign out** | `POST /auth/logout` (CSRF) → GW revokes the refresh token at the IdP, deletes the session, `302` to IdP `/logout` → IdP ends its session → back to the portal sign-in page |
| Admin deactivates a user / org suspended / password reset / "This wasn't me" | MiPay or IdP → IdP disables the user and revokes tokens → **back-channel logout** (signed `logout_token`) to GW → GW deletes every session of that `sub` → the next request gets `401 login_required` |
| Back-channel logout lost (GW down) | the next refresh (≤ 5 min) fails at the IdP → `401`; introspection on sensitive routes (`fresh: true`) also catches it immediately |

### A5. Partner API call (Lane B)

**Positive path:** the client sends `Authorization: DPoP <at>` plus `DPoP: <proof>`.
1. GW checks the route.
2. GW verifies the token: signature (JWKS), `iss`, `aud`, `exp`, `nbf`.
3. GW checks the sender constraint: the `cnf.jkt` thumbprint equals the proof key; the proof's `htm`, `htu` (public URL) and `ath` match; the proof `iat` is within ±60 s; its `jti` is unused.
4. GW checks revocation by introspection (cached 3 s, fail closed).
5. GW checks scopes, then the RBAC permission.
6. GW signs the context and forwards.

| Negative | Status | `WWW-Authenticate` / body | Forwarded? |
|---|---|---|---|
| No token | 401 | `DPoP error="invalid_token", error_description="missing bearer/DPoP token"` | no |
| Token not sender-constrained | 401 | `token is not sender-constrained (FAPI 2.0 requires DPoP or mTLS binding)` | no |
| DPoP-bound token sent as `Bearer` | 401 | `invalid_dpop_request` | no |
| Proof missing / bad signature / wrong `htu` / wrong `ath` | 401 | `use_dpop_header` / `invalid_dpop_proof` | no |
| Proof `jti` replayed | 401 | `invalid_dpop_proof: replay` | no |
| Token expired | 401 | `invalid_token: expired` | no |
| Token revoked (introspection `active=false`) | 401 | `invalid_token: revoked` | no |
| Introspection unreachable | 503 | fail closed | no |
| Scope missing | 403 | `insufficient_scope` | no |
| RBAC says no | 403 | `insufficient_permission` | no |

### A6. Bypass and tampering attempts

| Attempt | Stopped by |
|---|---|
| Sending a request straight to the backend container (another container on the network, a misrouted proxy, SSRF) | Spring Security filter: no valid gateway context → `401` before any controller (Appendix D) |
| Client sends its own `X-SME-Context` header through the gateway | GW strips all inbound `X-SME-*` headers before signing its own |
| Capturing a valid context header and replaying it to the backend | context v2 is bound to method, path and body hash, expires in 30 s, and its `rid` is single-use → `401` |
| Changing the path after the gateway decided (e.g. a proxy rewrite) | path binding mismatch → `401` |
| Forging context with a stolen service secret | v2 context is **signed by the gateway's private key** (ES256); upstreams hold only the public key and cannot mint contexts |
| Calling an endpoint that has no gateway route | `404` at the gateway; and the backend's build-time rule (D.4) forbids controllers without an authorization annotation |

## Appendix B. How RBAC is queried

### B.1 The API (extends the existing `rbac.proto`)

```proto
service Rbac {
  rpc Check(CheckRequest) returns (CheckResponse);                 // one decision
  rpc CheckBatch(CheckBatchRequest) returns (CheckBatchResponse);  // many (UI, lists)
  rpc CheckAny(CheckAnyRequest) returns (CheckResponse);           // holds op in ANY tenant? (gateway, lookup-tenant routes)
  rpc RegisterEndpoints(EndpointManifest) returns (RegisterResult); // services publish annotated endpoints (Appendix F)
  rpc ListEndpoints(ListEndpointsRequest) returns (EndpointManifest); // gateway compiles route policies (Appendix F)
  rpc ListPermissions(ListPermissionsRequest) returns (ListPermissionsResponse); // what can sub do in tenant
  rpc ListTenants(ListTenantsRequest) returns (ListTenantsResponse);// which orgs may sub do <op> in
  rpc ExplainDecision(ExplainRequest) returns (ExplainResponse);   // audit: why was decision X made
  rpc Watch(WatchRequest) returns (stream WatchEvent);             // cache invalidation
}
message CheckRequest {
  string subject = 1;             // IdP sub
  string tenant = 2;              // org id the action is on
  string operation = 3;           // "kyc.application.edit"
  bool fresh = 4;                 // bypass caches (sensitive routes)
  string session_id = 5;          // NEW: for DSD / session-activated roles
  repeated string active_roles = 6;      // NEW
  map<string,string> attributes = 7;     // NEW: e.g. child_type=MERCHANT, amount=12400.00
}
message CheckResponse {
  bool allowed = 1;
  string reason = 2;              // "granted via org_admin@own" / "no assignment" / "SoD: …"
  uint64 tenant_version = 3;
  string decision_id = 4;         // audit correlation
  string supplier_id = 5;
  repeated Obligation obligations = 6;   // NEW: e.g. {acr:"aal2", max_age:300}
}
```

### B.2 How the PDP decides `Check(sub, tenant, op)`

```
1. subject status          user ACTIVE in RBAC?                                  no → DENY "subject inactive"
2. tenant chain            ancestors(tenant) from the org closure (root … tenant); unknown tenant → DENY
3. candidate assignments   assignments of sub (direct, or through groups) where
                             (a.tenant = tenant AND a.scope = own)
                          OR (a.tenant ∈ ancestors(tenant) AND a.scope = subtree)
                          OR (a.scope = all)
                           and status = active, not expired, approval complete (four-eyes)
4. session activation      drop roles that belong to a DSD set or need activation unless in active_roles
5. hierarchy               expand each role to itself + all inherited (junior) roles
6. permission              op ∈ ⋃ role_permissions(expanded roles)?               no → DENY "no permission"
7. entitlement             if op needs a tenant grant (e.g. org.child.onboard):
                             active grant on the tenant whose constraints match the attributes
                             (child_type=MERCHANT) and within the parent's delegation ceiling
                                                                                 no → DENY "no grant"
8. obligations             permission metadata (min_acr, max_age, rar type) → obligations
9. record                  decision_id, inputs, matched assignment/role/grant, reason → decision log
                           result: ALLOW + obligations, otherwise DENY (deny by default)
```

**Worked example.** Selma is `org_admin` (scope `own`) on *Okahandja Fresh Market*. Her parent org's admin Jonas is `org_admin` (scope `subtree`) on *Namib Distribution*.

| Query | Result | Why |
|---|---|---|
| `Check(selma, Okahandja, kyc.application.edit)` | ALLOW | org_admin@own on the tenant |
| `Check(selma, <sibling merchant>, kyc.application.view)` | DENY | no assignment covers the sibling |
| `Check(jonas, Okahandja, kyc.application.edit)` | ALLOW | org_admin@subtree on an ancestor |
| `Check(jonas, Okahandja, org.child.onboard, {child_type:SUPPLIER})` | DENY | Okahandja (a merchant) has no onboarding grant |
| `Check(ops-admin-ops, Okahandja, kyc.task.decide.ops_review)` | ALLOW, obligation `acr=aal2, max_age=300` | platform_ops_reviewer@all |
| `Check(ops-admin-mindlabs, Okahandja, kyc.task.decide.ops_review)` | DENY | user_admin has no review permission (and SSD forbids granting it) |

### B.3 Who calls what, and when

| Caller | When | Call | Caching |
|---|---|---|---|
| GW | every routed request with a `permission` | `Check(sub, tenant from path, op, session, active_roles)` | 3 s per (sub, tenant, op, tenant_version); `fresh: true` routes skip the cache; invalidated instantly by `Watch`; on PDP error → 503 (never "allow from stale") |
| GW | `GET /auth/session` | `ListPermissions(sub, org)` for each org in the user's tree view | 30 s; UI hints only, never enforcement |
| API | `@PreAuthorize` when the tenant is not the gateway's (e.g. taken from the body) | `Check(…)` through `RbacClient` (gRPC, mTLS) | same 3 s cache + Watch |
| API | list screens ("orgs I may view") | `ListTenants(sub, kyc.application.view)` then a SQL `IN` filter | 30 s |
| Admin portal | Roles & access screen | `ExplainDecision`, review APIs | none |

## Appendix C. How the gateway answers 401/403 without ever routing to a controller

### C.1 The pipeline (everything before `forward()`)

The order follows `trustplane/internal/gateway/server.go` (handler) and `authn.go`. Steps marked **new** are added by this design.

| # | Stage | Rejects with |
|---|---|---|
| 1 | Host + path + method match in the deny-by-default route table | `404` / `405` |
| 2 | Hardening: header size, request smuggling (CL+TE), path normalisation (`..`, `//`, encoded slashes) | `400` |
| 3 | **Strip all inbound `X-SME-*` headers** | — |
| 4 | Per-IP rate limit (real IP via `trusted_proxies`) | `429` |
| 5 | Global / upstream concurrency | `503` |
| 6 | Body read with limit | `413` |
| 7 | **Authentication**: Lane A session (**new**) or Lane B token + DPoP/mTLS + introspection | `401` + `WWW-Authenticate` |
| 8 | Per-client rate limit | `429` |
| 9 | **CSRF** for unsafe methods (Lane A, **new**) | `403` |
| 10 | Scopes | `403 insufficient_scope` |
| 11 | **RBAC** `Check(sub, tenant, op)` | `403 insufficient_permission` / `503` |
| 12 | **Obligations** (`acr`, `max_age`, RAR match, **new**) | `401 insufficient_user_authentication` / `403` |
| 13 | Sign context v2, forward to the upstream | — |

The upstream, and so any MiPay controller, is contacted **only** at step 13.

### C.2 Route policies for MiPay (examples)

> These policies are **not hand-written**: they are compiled by the gateway from the endpoint manifest the backend registers with RBAC, generated from `@RequiresPermission` annotations (**Appendix F**). The YAML below shows the compiled form. `routes.yaml` keeps only infrastructure (hosts, upstreams, lanes, default limits, IdP/BFF routes).

```yaml
- host: admin.afriqpay.com
  path: /api/kyc/orgs/{tenant}
  methods: [PUT]
  upstream: mipay
  auth: session                 # Lane A (new); "token" = Lane B
  csrf: true
  permission: kyc.application.edit
  rate: {ip_per_min: 120, client_per_min: 60}
  body_limit: 262144

- host: admin.afriqpay.com
  path: /api/kyc/tasks/{task}/decision
  methods: [POST]
  upstream: mipay
  auth: session
  csrf: true
  permission: kyc.task.decide        # PDP narrows by stage via attributes from the task
  tenant_from: lookup:kyc_task_org   # gateway asks MiPay's tiny resolver (or the API re-checks, D.3)
  obligations: {acr: aal2, max_age: 300, rar: kyc_decision}
  fresh: true

- host: "*.afriqpay.com"
  path: /api/**                      # anything not listed above
  upstream: mipay
  auth: session
  permission: deny                   # NEW: catch-all denies instead of passing through
```

**Configuration rule (checked at gateway boot):** every route must name `auth` and, unless it is a public route, a `permission`. The catch-all is `deny`. A new MiPay endpoint is therefore **unreachable until someone writes its route policy**.

### C.3 Error format

```http
HTTP/1.1 401 Unauthorized
WWW-Authenticate: DPoP error="invalid_token", error_description="token expired", algs="ES256 EdDSA"
Content-Type: application/problem+json
X-SME-Request-Id: req-1731322333-8f2c

{"type":"about:blank","title":"Unauthorized","status":401,"code":"invalid_token","detail":"token expired"}
```

Lane A answers `401 {"code":"login_required"}` without `WWW-Authenticate: DPoP`, because the browser holds no token.

## Appendix D. Defence in depth: Spring Security in the MiPay backend

The gateway should stop everything bad. The backend still assumes it might not: through a misconfiguration, a compromised container on the same network, a request sent straight to its port, or an SSRF from another service. So the backend rejects anything that didn't come from the gateway, **in the Spring Security filter chain, before the `DispatcherServlet` picks a controller**. It then checks the permission again on the method itself.

### D.1 Network first

- **Ports:** the backend publishes no host port (the `127.0.0.1:19110` mapping is removed; health checks run inside the container). It is reachable only on `sme-mesh`, where the gateway is.
- **mTLS (optional):** Tomcat requires a client certificate whose SAN is `gateway`, issued by the trustplane platform CA (the mesh certificates `pki-init` already creates).

### D.2 Gateway context v2 (what the backend verifies)

The gateway signs a compact JWS (ES256, `kid` = gateway key) in `X-SME-Context`:

```json
{ "iss":"trustplane-gateway", "aud":"mipay", "sub":"4a35…", "sid":"…", "client_id":"portal-admin",
  "acr":"aal2", "amr":["pwd","mfa","swk"], "auth_time":1731322000,
  "tenant":"b626…", "perms":["kyc.application.edit","kyc.application.view"], "active_roles":["org_admin"],
  "decision_id":"d-…", "htm":"PUT", "htp":"/api/kyc/orgs/b626…", "bh":"<sha256 of body>",
  "rid":"req-…", "iat":1731322333, "exp":1731322363 }
```

**Changes from today's `ctxauth`:**
- **Asymmetric signature:** ES256 with the gateway's key, instead of a shared HMAC. Upstreams hold only the public key, so they can verify but can't mint contexts.
- **Bound to the request:** method, path and body hash.
- **Short expiry:** 30 s.
- **Single-use request id** (`rid`).

### D.3 The filter chain

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity                        // @PreAuthorize on every handler
class SecurityConfig {
  @Bean SecurityFilterChain api(HttpSecurity http, GatewayContextFilter ctx,
                                ProblemEntryPoint entry, ProblemAccessDenied denied) throws Exception {
    return http
      .csrf(c -> c.disable())                // CSRF is enforced by the gateway BFF; backend is stateless
      .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
      .httpBasic(b -> b.disable()).formLogin(f -> f.disable())
      .addFilterBefore(ctx, AnonymousAuthenticationFilter.class)
      .authorizeHttpRequests(a -> a
          .requestMatchers("/actuator/health").permitAll()      // container-internal only
          .anyRequest().authenticated())                         // everything else needs a gateway context
      .exceptionHandling(e -> e.authenticationEntryPoint(entry)   // 401 problem+json
                               .accessDeniedHandler(denied))      // 403 problem+json
      .build();
  }
}
```

**`GatewayContextFilter` (a `OncePerRequestFilter`)** takes these steps; any failure leaves the request unauthenticated, and Spring's `ExceptionTranslationFilter` returns **401 before any controller is resolved**:
1. `X-SME-Context` is present and exactly one.
2. The JWS verifies with the gateway public key (by `kid`, from `/pki`, cached).
3. `iss` = `trustplane-gateway`, `aud` = `mipay`, `exp` not passed (±5 s skew).
4. `htm` = `request.getMethod()`, `htp` = `request.getRequestURI()`, and `bh` = SHA-256 of the cached body (for requests with a body).
5. `rid` not seen before (Caffeine cache, 60 s) → then remember it.
6. Build a `GatewayAuthentication` (principal = `sub`; authorities = `perms` for `tenant`; details = `acr`, `amr`, `sid`, `decision_id`) and put it in the `SecurityContext`.
7. Load the MiPay user by `sub`; if the user or their org is suspended, return 403 `ORG_SUSPENDED` here as well (still before the controller).

### D.4 Method security (if a request somehow got this far)

```java
@PutMapping("/api/kyc/orgs/{orgId}")
@RequiresPermission(value = "kyc.application.edit", tenant = "#orgId", tenantParam = "orgId")
// a meta-annotation for @PreAuthorize("@authz.can('kyc.application.edit', #orgId)"), and also
// published to RBAC so the gateway enforces the same permission (Appendix F)
public KycApplicationView save(@PathVariable String orgId, …) { … }
```

`@authz.can(op, tenant)` decides like this:
1. **Fast path:** the context's tenant equals `tenant` and `perms` contains `op` → allowed (the gateway's decision, already logged).
2. **Otherwise** (the tenant comes from the body or the task, or differs) → PDP `Check(sub, tenant, op, attributes)`, cached 3 s; a PDP error → deny.
3. A denial → `AccessDeniedException` → 403 `ACCESS_DENIED`. The method body never runs.

**Build-time guard:** an **ArchUnit test** fails the build if any `@RequestMapping` / `@GetMapping` / … handler lacks `@PreAuthorize` (or an explicit `@PublicEndpoint` that also must exist in the gateway's public routes). Nothing can ship unprotected by accident.

**Domain rules stay in the services:** maker ≠ checker, one actor per submission, KYC stage, optimistic version, and suspension. They return 403 or 409 with specific codes.

### D.5 What the backend returns

| Situation | Status | Code | Controller ran? |
|---|---|---|---|
| No / bad / expired / replayed / mis-bound gateway context | 401 | `GATEWAY_CONTEXT_INVALID` (+ security alert) | no |
| Valid context, user or org suspended | 403 | `ORG_SUSPENDED` | no |
| Valid context, permission missing for this tenant | 403 | `ACCESS_DENIED` | no (method interceptor) |
| Allowed, but a domain rule fails | 403 / 409 | `MAKER_CANNOT_ACT`, `KYC_LOCKED`, … | yes (service throws) |

### D.6 Tests for this layer

- A request with no context reaches the backend directly → 401, and a test controller's invocation counter stays at 0.
- Tampered signature, wrong `kid`, expired, wrong `aud` → 401.
- A valid context replayed (same `rid`) → 401; replayed to another path or method, or with a different body → 401.
- A context signed with the old shared HMAC after cut-over → 401.
- A valid context without the permission → 403; the service method is never invoked (verified with a spy).
- The ArchUnit rule fails on an unannotated handler (a negative fixture).
- An end-to-end bypass test: `curl` from another container on `sme-mesh` straight to `backend:8080` → 401.

## Appendix E. Passing a classic penetration test: nobody reaches what they shouldn't

**The goal:** a tester working with valid low-privilege accounts (an org STAFF user, a merchant, a partner API client), or with none, must not read or change anything outside what RBAC grants them.

Below are the classic attacks, the control that stops each one, and where it is enforced. "GW" = gateway (Appendix C), "SS" = Spring Security in the backend (Appendix D), "PDP" = RBAC (Appendix B).

### E.1 Attacks and controls (OWASP API Security Top 10, 2023)

| # | Classic attack | Example the tester tries | Control | Where |
|---|---|---|---|---|
| API1 | **Broken object-level authorization (IDOR / BOLA)** | change `/api/kyc/orgs/{A}` to another org's id; change a task id, invoice id or user id | every object is loaded **first** and authorized against **its own tenant** (never a tenant the client sends); outside the caller's subtree → **uniform 404** (no existence leak); repositories take the caller's tenant set (`ListTenants`) as a mandatory filter | GW (path tenant) + SS `@authz.can` on the loaded object's org + service |
| API2 | **Broken authentication** | password spraying, token reuse, forged or `alg:none` JWTs, stolen cookie | IdP-only login with lockout and 2FA; DPoP/mTLS-bound tokens; ID-token and context signatures verified with pinned algorithms (no `none`, no HS/RS confusion); `__Host-` HttpOnly SameSite=Strict cookie; sessions revocable | IdP, GW, SS |
| API3 | **Broken object-property-level authorization (mass assignment / over-exposure)** | add `"status":"APPROVED"`, `"orgRole":"ADMIN"` or `"riskRating":"LOW"` to a body; read masked fields | request DTOs list only writable fields (unknown keys rejected, as the KYC document already does); server-owned fields (status, roles, risk, ids, versions) never bound from input; responses built per permission (masking for ancestors: PII/SECRET) | SS + DTOs |
| API4 | **Unrestricted resource consumption** | flood login, OTP, push, uploads, big bodies, deep pagination | per-IP and per-client rate limits (real client IP), body limits, page-size caps, push/SMS limits per user, concurrency caps | GW, IdP, API |
| API5 | **Broken function-level authorization** | a STAFF user calls `PUT /api/orgs/{id}/rights`, `/api/ops/**`, `/api/admin/**`, a task decision | every route names a `permission` (catch-all = deny); `@PreAuthorize` on every handler (ArchUnit-enforced); privileged roles need four-eyes and SoD | GW + SS + PDP |
| API6 | **Unrestricted access to sensitive business flows** | approve own KYC; onboard beyond granted types; repeat payouts | domain rules: maker ≠ checker, one actor per submission, delegation ceilings, step-up + RAR transaction binding | API + PDP + IdP |
| API7 | **SSRF** | make the server fetch an internal URL (provider callbacks, URLs in KYC fields) | the backend never fetches user-supplied URLs; outbound calls only to allow-listed provider hosts through an egress proxy; KYC URL fields are stored, never fetched | API, network |
| API8 | **Security misconfiguration** | Swagger UI, actuator, verbose errors, missing TLS settings, permissive CORS | deny-by-default routes (no route = 404); `/actuator`, `/swagger-ui`, `/v3/api-docs` not routed publicly and **disabled in the production profile**; problem+json errors without stack traces; FAPI TLS (§10); CORS limited to the four portal origins | GW, nginx, API config |
| API9 | **Improper inventory** | old or undocumented endpoints (`/api/orgs/{id}/kyc` legacy aliases, test endpoints) | the gateway route table **is** the inventory (anything not listed is unreachable); legacy aliases removed at cut-over (§8 step 5); a CI check diffs controllers vs routes | GW + CI |
| API10 | **Unsafe consumption of APIs** | malicious provider webhook data | provider events signature-checked, timestamped, de-duplicated (KYC design §7); provider data validated against the KYC schema before use | API |
| — | **Header spoofing / gateway bypass** | send `X-SME-Context`, `X-Real-IP`, `X-Forwarded-*`; hit the backend directly | GW strips inbound `X-SME-*`; `X-Real-IP` trusted only from listed proxies; backend not published and verifies the signed, request-bound context (D.2–D.3) | GW + SS + network |
| — | **CSRF / clickjacking** | cross-site form post; framing the portal | SameSite=Strict + `X-CSRF` double-submit on unsafe methods; `X-Frame-Options: DENY` (already set) + CSP `frame-ancestors 'none'` | GW, nginx |
| — | **Injection / XSS** | SQL in filters; script in KYC names | JPA parameter binding only (no string-built SQL); Angular's default escaping; a strict CSP; KYC values validated by the schema | API, portals |

### E.2 Findings in today's code (from this review)

| # | Finding | Severity | Status |
|---|---|---|---|
| F1 | `GET /api/kyc/schema/{orgId}` didn't check that the caller may see `orgId`. Any signed-in user could probe org ids (404 vs 200) and read an org's type and KYC profile. | Medium (information disclosure / enumeration) | **Fixed:** `KycController` now calls `OrgTreeService.visible(...)` first (uniform 404); KYC and onboarding tests pass |
| F2 | `/swagger-ui/**` and `/v3/api-docs/**` are `permitAll` in `SecurityConfig` | Low today (nginx doesn't route them publicly) / High if routed | Disable in the production profile; never add a route for them |
| F3 | Object ownership inside role-gated areas (`/api/merchant/**`, `/api/supplier/**`, `/api/ops/**`, `/api/orgs/**`) is checked case by case in services. **71 endpoints take an id in the path** across 8 controllers. | Unknown until audited | **Audit every one** against E.1 API1/API5 (migration step 4b); the authorization matrix (E.3) proves it |
| F4 | Sandbox merchant OTP: the code is returned in the response | High if a sandbox is public with real merchants | Sandbox only; must be off before real merchants (the IdP phone-OTP replaces it) |
| F5 | Gateway catch-all `/api/**` is `auth: none` and passes through | Medium (relies only on the backend) | Becomes `permission: deny` with per-route policies (C.2) |
| F6 | Context header uses a shared HMAC and isn't bound to the request | Medium (replay / forging inside the mesh) | Context v2 (D.2) |

### E.3 How we prove it: the authorization matrix

1. **Personas:**
   - anonymous;
   - merchant user;
   - org STAFF;
   - org ADMIN of org A;
   - org ADMIN of A's *sibling* B;
   - org ADMIN of A's *parent* (subtree);
   - ADMIN of a *suspended* org;
   - each platform role;
   - a partner API client with only scope X.
2. **For every route in the gateway table,** the matrix states the expected result for each persona: `allow`, `404` (outside the tree), `403` or `401`.
   - Route + persona → expected is **generated from the RBAC seed**, so the policy and the test can't drift apart.
3. **Run it in CI** against the docker-compose stack:
   - through the gateway, which checks everything is blocked at the gateway;
   - **and directly at the backend with a valid context for the wrong persona**, which checks the backend blocks it too.
   - Every cell must match, and a new route without matrix rows fails the build.
4. **Object swaps:** for every route with an id, persona B uses an id belonging to A, and must get `404` or `403`, never data.
5. **Body tampering:** each write endpoint is replayed with every server-owned field added (status, role, ids, risk); the stored row must not change.
6. **Automated scanners:**
   - **OWASP ZAP** (authenticated, one session per persona);
   - an **"Autorize"-style replay**: every request captured as ADMIN is replayed as STAFF and as another org's ADMIN;
   - **Schemathesis** fuzzing from the OpenAPI spec (in CI only, never against production).
7. **Before go-live with real customers:** an **independent third-party penetration test** against the staging stack (same images, same gateway policies), with re-tests of every finding. Then repeat it yearly and after major changes.

**Exit criterion:** zero unexpected `allow` cells. Scanners raise no high or medium findings, and every finding has a regression test in the matrix.

## Appendix F. One declaration, two enforcement points: the annotation publishes the permission

**Goal:** the permission a backend function needs is written **once**, on the function itself. From that single line:
- **Spring Security** enforces it inside the backend (Appendix D);
- the backend **registers** it with the RBAC server at startup;
- the **gateway** learns it from RBAC and enforces the same permission *before* routing (Appendix C).

Nobody hand-maintains a second list in `routes.yaml`, so the two can't drift apart.

```
@RequiresPermission on a controller method
        │ (startup)
        ▼
EndpointManifestPublisher ──gRPC/mTLS──▶ RBAC server ──Watch: ENDPOINTS_CHANGED──▶ gateway
  scans every handler:                    stores the manifest,                       compiles route policies
  method, path, permission,               adds new permission ids                    (path template → tenant param,
  tenant source, obligations              to the catalogue (unassigned),             permission, obligations);
                                          audits the change                          unknown route → 404
        │
        ▼ (every request)
Spring: @RequiresPermission → @authz.can(permission, tenant)        gateway: Check(sub, tenant, permission)
```

### F.1 The annotation

```java
@Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME) @Documented
@PreAuthorize("@authz.can('{value}', {tenant})")      // Spring Security 6.3+ annotation templates
public @interface RequiresPermission {
    String value();                          // "kyc.application.edit"
    String tenant() default "null";          // SpEL for Spring: "#orgId", "@tenants.ofTask(#taskId)"
    TenantSource tenantSource() default TenantSource.PATH;   // PATH | LOOKUP | NONE (platform-wide)
    String tenantParam() default "";         // PATH: the path variable name, e.g. "orgId"
    String lookup() default "";              // LOOKUP: resolver id, e.g. "kyc_task_org"
    String acr() default "";                 // obligation: "aal2"
    int maxAgeSeconds() default -1;          // obligation: freshness of the login
    String rar() default "";                 // obligation: RAR type, e.g. "kyc_decision"
    boolean fresh() default false;           // bypass decision caches
    String description() default "";         // shown in the RBAC catalogue / Roles & access
}

/** Explicitly public (no login). Allowed only for paths on the approved public list (F.4). */
@Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME)
public @interface PublicEndpoint { String reason(); }
```

**Usage in a controller:**

```java
@PutMapping("/api/kyc/orgs/{orgId}")
@RequiresPermission(value = "kyc.application.edit", tenant = "#orgId", tenantParam = "orgId",
                    description = "Save a KYC draft for an organization")
public KycApplicationView save(@PathVariable String orgId, @RequestBody Map<String,Object> body) { … }

@PostMapping("/api/kyc/tasks/{taskId}/decision")
@RequiresPermission(value = "kyc.task.decide", tenant = "@tenants.ofTask(#taskId)",
                    tenantSource = TenantSource.LOOKUP, lookup = "kyc_task_org",
                    acr = "aal2", maxAgeSeconds = 300, rar = "kyc_decision", fresh = true,
                    description = "Approve / return / reject / block a KYC review task")
public KycApplicationView decide(@PathVariable String taskId, @RequestBody DecisionRequest req) { … }
```

### F.2 The manifest the backend publishes

`EndpointManifestPublisher` runs on `ApplicationReadyEvent`:
1. It reads every handler from Spring's `RequestMappingHandlerMapping`.
2. It **refuses to start** if any handler has neither `@RequiresPermission` nor `@PublicEndpoint`. That's a runtime twin of the ArchUnit rule in D.4.
3. It builds the manifest:

```json
{
  "service": "mipay",
  "upstream": "mipay",
  "version": "35160fd",
  "endpoints": [
    { "method": "PUT",  "path": "/api/kyc/orgs/{orgId}",
      "permission": "kyc.application.edit",
      "tenant": { "source": "path", "param": "orgId" },
      "csrf": true },
    { "method": "POST", "path": "/api/kyc/tasks/{taskId}/decision",
      "permission": "kyc.task.decide",
      "tenant": { "source": "lookup", "resolver": "kyc_task_org", "param": "taskId" },
      "obligations": { "acr": "aal2", "max_age": 300, "rar": "kyc_decision" }, "fresh": true,
      "csrf": true },
    { "method": "GET",  "path": "/api/env", "public": true, "reason": "portal bootstrap" }
  ],
  "permissions": [
    { "id": "kyc.application.edit", "description": "Save a KYC draft for an organization" },
    { "id": "kyc.task.decide", "description": "Approve / return / reject / block a KYC review task",
      "min_acr": "aal2" }
  ]
}
```

It then calls RBAC **`RegisterEndpoints(manifest)`** over the mesh (mTLS; the service's certificate SAN must be `mipay`). It retries until accepted, and the backend reports itself healthy only after registration succeeds.

### F.3 What the RBAC server does with it

```proto
rpc RegisterEndpoints(EndpointManifest) returns (RegisterResult);   // from services
rpc ListEndpoints(ListEndpointsRequest) returns (EndpointManifest); // for the gateway
// Watch() additionally emits ENDPOINTS_CHANGED{service, version}
```

1. **Authenticate the publisher:** the mTLS SAN must equal `service`. A service may register only for its own upstream and path prefix (`mipay` → `/api/**`, not `/authorize` or `/admin/**`).
2. **Validate:**
   - every non-public endpoint has a permission id matching `^[a-z]+(\.[a-z_]+)+$`;
   - LOOKUP tenants name a resolver the service has declared;
   - no two endpoints claim the same method + path.
3. **Catalogue:** new permission ids are **added to `permissions` but given to no role.** A new endpoint is therefore **denied to everyone** until an administrator maps the permission to roles in Roles & access (with four-eyes for privileged roles). Code can never grant itself access.
4. **Public endpoints** are accepted only if the method + path is already on the **approved public list**, which is kept in RBAC and changed only with four-eyes. A compromised or buggy service can't open a hole by marking something `public`.
5. **Store** the manifest as `service_endpoints` (versioned). Endpoints missing from a new version are marked *retired* and stop routing. Write an audit entry, and emit `ENDPOINTS_CHANGED`.

### F.4 What the gateway does with it

- **Load and compile:** on boot, and on every `ENDPOINTS_CHANGED`, the gateway calls `ListEndpoints(service)` and compiles route policies, using the same structure as the YAML in C.2:
  - the path template becomes the matcher, and `{orgId}` becomes the tenant parameter;
  - `permission`, `obligations`, `fresh` and `csrf` copy over;
  - the upstream comes from the manifest.
- **Merge with `routes.yaml`:** the YAML now holds only infrastructure: hosts, upstream addresses, lanes (`session` / `token`), default rate and body limits, and the IdP and BFF routes. **Policy for service endpoints comes only from RBAC.** If both define the same method + path, the gateway refuses to load (no silent override).
- **Tenant resolution:**
  - `path`: the tenant is the path parameter, and the gateway does the full `Check(sub, tenant, permission)`.
  - `lookup` (the tenant comes from the object, e.g. a task's org): the gateway checks the permission at **function level** with `CheckAny(sub, permission)` ("does this user hold the permission in *any* tenant?"), which rejects a STAFF user immediately. The **object-level** check against the real tenant is done by Spring (`@tenants.ofTask(#taskId)` → `@authz.can`).
  - `none` (platform-wide operations): `Check(sub, tenant = platform-root, permission)`.
- **Fail closed:**
  - if RBAC is unreachable at boot, there are no service routes, so every request gets 404 or 503;
  - if it's unreachable later, the gateway keeps the last accepted route set (it never adds routes it hasn't fetched);
  - an endpoint not (yet) registered is simply unrouted, so it gets **404**.
- **Deploy order is safe either way:**
  - a new backend version with a new endpoint: the endpoint 404s until the backend registers it, then stays denied until an administrator maps its permission;
  - a removed endpoint stops routing as soon as the new manifest arrives.

### F.5 Why both layers still check

| Situation | Gateway | Spring |
|---|---|---|
| STAFF user calls a decision endpoint | 403 at `CheckAny` (no `kyc.task.decide` anywhere) | — (never forwarded) |
| Reviewer of org A sends a task id belonging to org B | function-level passes (they do hold the permission somewhere) | `@tenants.ofTask` → B → `@authz.can` → 403; method body never runs |
| A request reaches the backend without the gateway | — | 401 in the filter chain (D.3) |
| A developer forgets the annotation | endpoint never registered → 404 | backend refuses to start; ArchUnit fails the build first |
| A developer annotates but no role has the permission yet | 403 for everyone | 403 for everyone |

### F.6 Tests

- **Publisher:**
  - the manifest contains every handler with the right method, path, permission and tenant source;
  - startup fails on an unannotated handler;
  - `@PublicEndpoint` on a path not on the approved list is refused by RBAC.
- **RBAC:**
  - a manifest from the wrong mTLS identity, or outside its path prefix, is refused;
  - a new permission is unassigned (deny-all) until mapped;
  - removed endpoints are retired;
  - every change is audited and emits `ENDPOINTS_CHANGED`.
- **Gateway:**
  - a route appears within seconds of registration, and disappears when retired;
  - a duplicate YAML/RBAC definition refuses to load;
  - with RBAC down at boot, every service route returns 404/503;
  - `lookup` endpoints run `CheckAny` and the backend still enforces the object tenant.
- **Matrix (Appendix E.3):** generated from the manifest + RBAC seed, so every annotated endpoint is tested for every persona automatically.

## Appendix G. The RBAC plan: a two-dimensional permission grid

### G.1 The idea in one picture

```
                         read create modify delete submit authorize execute export administer simulate
kyc.application           ●            ●      ●      ●
kyc.task                  ●                                 ●
merchant.withdrawal       ●                                           ●
org.user                  ●     ●             ●                       ●
org.rights                ●                                                          ●
…  (50 rows)
```

- **Rows** = resources (`<service>.<resource>`). They are **registered by the code** (G.3); there are 50 today (G.10).
- **Columns** = a **fixed list of 10 verbs**: `read · create · modify · delete · submit · authorize · execute · export · administer · simulate`.
- **Cell** = one permission, `<row>.<verb>`, e.g. `kyc.application.submit`. A cell **exists only if at least one endpoint uses it** (● above). There are 119 today; every other cell is greyed out and can't be granted.
- **Role** = a set of ticked cells: a grid with ticks.
- **Assignment** = user + role + **org** + scope (`own` / `subtree` / `all`). The grid says **what**, the assignment says **where**.

So "may Selma do X?" becomes: **find her roles on that org (assignment), then look up the cell (row, column) in those roles' grids.**

### G.1a Why read / create / edit / approve is not enough

Four verbs look simpler, but they force **about 50 of the 102 non-read handlers** in this codebase into a bucket that means something else. Each misfit is a real permission someone gets without anyone deciding to give it. Every extra column exists because a regulator, an auditor or a pen tester would treat that action differently:

| Extra verb | If it were folded into… | What goes wrong, with a real handler from this code | What the separate column lets us enforce |
|---|---|---|---|
| **submit** (maker) | edit | KYC `save` (fix a typo in a draft) and KYC `submit` (send it to review, attesting it is complete and true) become the same permission. Every clerk who can correct a draft can also **send it to the regulator's review**, and the maker-checker pair can't be expressed. | "Staff draft, only an admin submits"; SoD `submit × authorize` (the maker never checks) |
| **delete** | edit | Fixing a supplier's catalogue text and `retireItem`, user profile edits and `deactivate` (lock someone out), changing and `revokeBankAccount`. Anyone allowed to correct data could **remove records or lock out the last admin**. Deletion is irreversible and erases evidence. | Destructive actions only for admins; separate audit; a confirmation step |
| **execute** (do it now / move money) | create or edit | `withdraw`, `refund`, `payInvoice`, `sweep`, `fund` *move money*. `runRecon`, `remind`, `resend` activation, `rotate` keys, `deploy`, `backupNow` *trigger actions* without creating anything a user would call a record. Under "create", **creating a stock item and withdrawing the shop's money look like the same kind of right**, and "every money movement needs a fresh second factor" can't be written as one rule. | Step-up (`aal2`) and transaction binding (RAR) on the whole column; SoD `execute × authorize` (initiator ≠ approver); tighter rate limits |
| **export** | read | Opening **one** invoice and downloading a **statement**, or `export` on a data-subject request: the latter takes **bulk personal and financial data out of the system**. Under "read", every viewer can download everything, and data-protection audit can't tell a glance from a bulk extract. | Export only for named roles; every export audited and rate-limited; step-up for bulk personal data |
| **administer** | edit | `suspend` / `lift` (block a whole subtree and sign everyone out), `narrowRights`, `revokeAccess`, `revokeClient`: **power over other people's access**. Under "edit", whoever may edit an org's profile could suspend it or strip its rights. | SoD "access administrators never approve customers"; four-eyes on administer cells |
| **simulate** | create | `/sim/payment`, `simulate-fund`, `injectBreak`, `runScene` create **fake payments, fake funding and fake faults**. Under "create", a real merchant's "create" could inject a fake payment in production. | The whole column switched off in the production profile; RBAC refuses ticks there |
| **authorize** (= approve) | — | Kept as is. | — |

**In short:** a permission model is only as fine as the questions an auditor asks:
- "Who can **send** a KYC to review?" (submit)
- "Who can **move money**?" (execute)
- "Who can **take data out**?" (export)
- "Who can **lock other people out**?" (administer)
- "Who can **destroy** records?" (delete)

With four verbs, each of these answers becomes "everyone who can edit" or "everyone who can read". With ten, each is a single column of the grid that can be read off and restricted directly.

**Rows matter for the same reason.** A service-wide `merchant.create` would bundle `merchant.inventory.create` (a clerk's job) with every other create in the merchant service. Keeping the resource as the row lets "stock clerk" and "owner" differ without inventing special roles.

### G.2 Storage (trustplane RBAC)

```sql
-- columns: fixed, never edited at runtime
--   read=1 create=2 modify=4 delete=8 submit=16 authorize=32 execute=64 export=128 administer=256 simulate=512

create table rbac_resources (               -- rows, registered by services (G.3)
  id          text primary key,             -- 'kyc.application'
  service     text not null,                -- 'mipay'
  description text not null,
  used_verbs  integer not null,             -- bitmask of cells that have endpoints (greys out the rest)
  retired     boolean not null default false
);

create table rbac_role_grid (               -- a role = one bitmask per row
  role     text    not null references roles(name),
  resource text    not null references rbac_resources(id),
  verbs    integer not null,                -- ticked cells; must be a subset of used_verbs
  primary key (role, resource),
  check (verbs >= 0 and verbs < 1024)
);

create table rbac_cell_endpoints (          -- which endpoints each cell protects (shown on click)
  resource text not null, verb integer not null,
  method text not null, path text not null, service text not null,
  primary key (method, path)
);

create table rbac_sod_rules (               -- separation of duty, written as cell pairs (G.5)
  id text primary key, kind text check (kind in ('STATIC','DYNAMIC','OBJECT')),
  cell_a text not null, cell_b text not null, reason text not null
);
-- assignments(user, role, tenant, scope, expires_at, approval) already exist (§9.1)
```

### G.3 The code chooses a cell

```java
public enum Verb { READ, CREATE, MODIFY, DELETE, SUBMIT, AUTHORIZE, EXECUTE, EXPORT, ADMINISTER, SIMULATE }

@Target(METHOD) @Retention(RUNTIME)
@PreAuthorize("@authz.can('{resource}', T(com.mipay.sme.security.Verb).{verb}, {tenant})")
public @interface Perm {
    String resource();                         // the row: "kyc.application"
    Verb verb();                               // the column
    String tenant() default "null";            // SpEL: "#orgId", "@tenants.ofTask(#taskId)", or SELF
    TenantSource tenantSource() default TenantSource.PATH;  // PATH | LOOKUP | SELF | NONE
    String tenantParam() default "";
    String acr() default ""; int maxAgeSeconds() default -1; String rar() default ""; boolean fresh() default false;
    String description() default "";
}

@PutMapping("/api/kyc/orgs/{orgId}")
@Perm(resource = "kyc.application", verb = Verb.MODIFY, tenant = "#orgId", tenantParam = "orgId")
public KycApplicationView save(@PathVariable String orgId, @RequestBody Map<String, Object> body) { … }
```

At startup the manifest (Appendix F) sends RBAC:
- every **row** (resource + description);
- every **used cell** with its endpoints.

RBAC updates `rbac_resources.used_verbs` and `rbac_cell_endpoints`. The gateway gets each route's cell from RBAC (`ListEndpoints`).

**Rules enforced at build time and at startup:**
- every handler has `@Perm` or `@PublicEndpoint`;
- the `resource` matches `^[a-z]+\.[a-z_]+$`;
- **one handler = one cell**: a handler that would need two cells is split, or takes the higher-risk cell.

### G.4 How a check is answered

```
Check(sub, tenant, "kyc.application.modify")
  1. split the permission → row = kyc.application, column = modify (bit 4)
  2. assignments of sub that cover tenant (own / subtree via the org closure / all), active, approved,
     not expired; drop roles that need session activation unless they are active
  3. mask = OR of rbac_role_grid.verbs for (each role and its inherited roles, row)
  4. allowed = (mask & 4) != 0           ← one bit test
  5. apply grants (onboarding entitlements) and obligations (acr, max_age, rar) for the cell
  6. log decision_id with: matched assignment, role, row, column → ALLOW / DENY (deny by default)
```

- **Caching:** the masks are cached per (sub, tenant, row) for 3 s and invalidated by `Watch` on any grid or assignment change. A whole page of buttons is decided from one cached mask per row.
- **For the UI,** `ListPermissions(sub, org)` returns `{row: mask}`. The portal hides buttons whose bit is 0, which is a hint only; enforcement stays at the gateway and the backend.

### G.5 Separation of duty as grid rules

| Rule | Kind | Cells | Reason |
|---|---|---|---|
| Maker ≠ checker on KYC | OBJECT | `kyc.application.submit` × `kyc.task.authorize` | the person who submitted an application never decides its tasks (also enforced by the workflow engine) |
| No self-approval of money | STATIC | `merchant.withdrawal.execute` × `ops.bank_account_approval.authorize` | whoever moves money can't approve the account it goes to |
| Admins don't approve customers | STATIC | any `*.administer` × any `*.authorize` held by **platform** roles | access administrators are not reviewers |
| Two review stages, two people | DYNAMIC | `kyc.task.authorize` (stage ops-review) × `kyc.task.authorize` (stage compliance) | per submission, one person acts on one stage (stage carried as an attribute) |
| No simulation in production | STATIC | the whole `simulate` column | disabled by the production profile; RBAC refuses ticks in it |

**Same-row shorthand:** for any row, no single role may tick both `submit` and `authorize`. Across a user's roles, holding both on the same org is a static violation unless an OBJECT rule covers it instead.

**When the rules apply:**
- **STATIC** rules are checked when a grid is saved and when a role is assigned → `409 SOD_CONFLICT`;
- **DYNAMIC** rules at session activation;
- **OBJECT** rules at decision time, by the domain service.

### G.6 Starting roles (proposal for compliance)

| Role | Ticks (summary) |
|---|---|
| `org_viewer` | `read` on every org-scoped row (kyc, org, merchant.*, supplier.*) |
| `org_staff` | `org_viewer` + day-to-day: `merchant.inventory` create/modify, `merchant.khata` / `merchant.tab` create/execute, `merchant.order` create, `merchant.payment` execute (take payments), `supplier.catalogue` create/delete, `supplier.invoice` create, `kyc.application` modify (draft) |
| `org_admin` | `org_staff` + `kyc.application` submit/delete, `org.user` create/delete/execute, `org.onboarding` create/submit, `org.rights` / `org.suspension` administer (for orgs below), `merchant.withdrawal` execute, `merchant.advance` submit/authorize, `merchant.payment` execute (refund, aal2), `supplier.bank_account` modify/delete, `*.statement` export |
| `platform_ops_reviewer` | `kyc.task` authorize (ops stage), `ops.merchant_approval` / `ops.supplier_approval` / `ops.bank_account_approval` / `ops.onboarding_request` authorize, read on ops rows |
| `platform_compliance_officer` | `kyc.task` authorize (compliance stage), `admin.sar` create/submit, `admin.aml_alert` read, `admin.dsar` create/export |
| `platform_operations` | `ops.incident` / `ops.complaint` / `ops.dispute` create/modify, `ops.recon` execute/modify, `ops.report` read/execute, `ops.kyc_check` create, `ops.suspension` read |
| `platform_user_admin` | `org.user` *, `admin.access` administer, `admin.api_client` create/administer (no `authorize` anywhere: SoD) |
| `platform_engineer` | `admin.platform_ops` execute/administer, `admin.connector` modify/execute/administer, `admin.config` modify |
| `platform_auditor` | `read` on every row + `ops.audit` read |
| `lender_officer` | `lender.assessment` execute, `lender.advance` authorize, `lender.collection` execute, `lender.portfolio` read/execute |
| `sandbox_tester` | the `simulate` column (sandbox only) |

**Example stored grid (`org_admin`, three rows):**

| row | ticked | mask |
|---|---|---|
| `kyc.application` | read + modify + delete + submit | 1+4+8+16 = **29** |
| `merchant.withdrawal` | read + execute | 1+64 = **65** |
| `org.rights` | read + administer | 1+256 = **257** |

### G.7 Worked example, end to end

**The setup:**
- Okahandja Fresh Market (merchant) sits under Namib Distribution.
- **Selma** is `org_admin` (own) on Okahandja.
- **Tomas** is `org_staff` (own) on Okahandja.
- **Jonas** is `org_admin` (subtree) on Namib Distribution.
- **Ops-Ana** is `platform_ops_reviewer` (all).

**1. Selma saves the KYC draft:** `PUT /api/kyc/orgs/{okahandja}`
1. Annotation: `@Perm(resource="kyc.application", verb=MODIFY, tenant="#orgId")` → cell **kyc.application × modify** (bit 4).
2. Gateway: route from the manifest → `Check(selma, okahandja, "kyc.application.modify")`.
3. PDP:
   - assignment `org_admin@own(okahandja)` covers the tenant;
   - grid `org_admin` row `kyc.application` = **29** → `29 & 4 = 4` → **ALLOW**, `decision_id = d-7f1`.
4. Gateway signs the context (`perms` for okahandja) → backend `@Perm` fast path (same tenant, permission present) → the service saves → audit row carries `d-7f1`.

**2. Tomas tries to withdraw money:** `POST /api/merchant/withdrawals`
1. Cell **merchant.withdrawal × execute** (bit 64), tenant = SELF (Okahandja).
2. PDP: `org_staff` row `merchant.withdrawal` = **1** (read only) → `1 & 64 = 0` → **DENY**.
3. Gateway: `403 insufficient_permission: subject lacks "merchant.withdrawal.execute" in tenant "okahandja"`. **Never forwarded.**

**3. Jonas edits his child org's draft:** the same request as step 1, made by Jonas.
- `org_admin@subtree(namib)`, and Namib is an ancestor of Okahandja in the closure → covered.
- Row mask 29 → **ALLOW**.

The **same Jonas** on a sibling merchant under another distributor → no covering assignment → **DENY** (`404` at the backend's visibility check, so the org's existence isn't revealed).

**4. Selma submits; Ops-Ana decides:** `POST /api/kyc/tasks/{t1}/decision`
1. Cell **kyc.task × authorize**, tenant via LOOKUP (task → Okahandja), obligation `acr=aal2, max_age=300`.
2. Gateway: `CheckAny(ops-ana, "kyc.task.authorize")` → allowed (platform role, scope all).
3. The session is 40 min old → **401 step-up** → push approval → retried.
4. Backend: `@tenants.ofTask(t1)` = Okahandja → `@authz.can` → ALLOW. The workflow engine's OBJECT rule checks that Ops-Ana didn't submit it → allowed → decision recorded.

**5. Selma tries to decide her own application's task:**
- `org_admin` has **no** tick in `kyc.task × authorize` → the gateway refuses with **403**.
- If a misconfigured role ever gave her the tick, the OBJECT SoD rule (`kyc.application.submit` × `kyc.task.authorize`) and the engine's maker ≠ checker check would still refuse it with **403 MAKER_CANNOT_ACT**.

**6. An administrator tries to give Ops-Ana `platform_user_admin` as well:** STATIC SoD (`*.administer` × `*.authorize` for platform roles) → **409 SOD_CONFLICT**: "platform_user_admin conflicts with platform_ops_reviewer (admins don't approve customers)".

### G.8 The screen: Roles & access

The admin portal gets one grid per role:
- rows grouped by service (collapsible);
- 10 columns;
- cells: ☑ granted, ☐ not granted, · no endpoint (greyed), ⊘ blocked by SoD (hover for the rule).

**Clicking a cell** shows the endpoints it protects (`rbac_cell_endpoints`) and who holds it.

**Bulk shortcuts:** a group row or a column header ticks the visible cells. They are stored cell by cell, so a newly registered row never inherits access automatically.

**Saving** shows a diff ("+3 cells, −1 cell"). For privileged roles it waits for a second approver (four-eyes), and every change is audited.

**Per-user view (read-only):** "Selma in Okahandja Fresh Market" is the OR of her roles' grids on that org. That's the effective-permission view for audits and access reviews.

### G.9 Rollout

| Step | Work | Effort |
|---|---|---|
| 1 | `Verb` enum, `@Perm`, `@PublicEndpoint`, manifest publisher (rows + cells), ArchUnit and startup checks in **report-only** mode | 1 day |
| 2 | RBAC: `rbac_resources`, `rbac_role_grid`, `rbac_cell_endpoints`, `rbac_sod_rules`; bit-test `Check`, `CheckAny`, `ListPermissions`; `RegisterEndpoints` / `ListEndpoints`; SoD on save and assign | 2 days |
| 3 | Annotate the 205 handlers from the draft mapping (G.10); compliance reviews rows, cells and starting roles (G.6) | 1–2 days |
| 4 | Copy today's authorizations into grids and assignments (ADMIN/STAFF, platform functions, TPTS_OPS / MINDLABS_ADMIN → the new platform roles); resolve SoD conflicts | 1 day |
| 5 | **Shadow mode** per controller (old rule enforced, grid decision logged); fix mismatches; then enforce, with Merchant/Supplier/Lender first, then Ops/Admin, then Org/KYC | 2–3 days + 1 week of observation |
| 6 | Roles & access grid screen (edit, diff, four-eyes, per-user view) | 2–3 days |
| 7 | Generated authorization-matrix tests (persona × endpoint, from the grids); checks switch to **enforcing** | 1 day |

That's about 10–13 working days plus the shadow week. The work slots into migration steps 2b, 2c, 4b, 4c and 4d (§8).

### G.10 Draft catalogue generated from the code (for review)

Generated from the 205 handlers by name and HTTP method, then curated: 50 rows, 119 used cells, and 11 endpoints with no cell (public or service identity). **Every assignment below needs a human review** before step 3; misreadings are likely in borderline names.

**The grid (● = a used cell):**

| Resource (row) | read | create | modify | delete | submit | authorize | execute | export | administer | simulate |
|---|---|---|---|---|---|---|---|---|---|---|
| `admin.access` | ● |  |  |  |  |  |  |  | ● |  |
| `admin.aml_alert` | ● |  |  |  |  |  |  |  |  |  |
| `admin.api_client` | ● | ● |  |  |  |  |  |  | ● |  |
| `admin.config` | ● |  | ● |  |  |  |  |  |  |  |
| `admin.connector` | ● |  | ● |  |  |  | ● |  | ● |  |
| `admin.dsar` | ● | ● |  |  |  |  |  | ● |  |  |
| `admin.platform_ops` | ● |  |  |  |  |  | ● |  | ● |  |
| `admin.sar` | ● | ● |  |  | ● |  |  |  |  |  |
| `admin.simulation` | ● |  |  |  |  |  |  |  |  | ● |
| `kyc.application` | ● |  | ● | ● | ● |  |  |  |  |  |
| `kyc.form` | ● |  |  |  |  |  |  |  |  |  |
| `kyc.task` | ● |  |  |  |  | ● |  |  |  |  |
| `lender.advance` | ● |  |  |  |  | ● |  |  |  |  |
| `lender.assessment` | ● |  |  |  |  |  | ● |  |  |  |
| `lender.collection` | ● |  |  |  |  |  | ● |  |  |  |
| `lender.portfolio` | ● |  |  |  |  |  | ● |  |  |  |
| `merchant.account` | ● |  |  |  |  |  |  |  |  |  |
| `merchant.advance` | ● | ● |  | ● | ● | ● |  |  |  | ● |
| `merchant.inventory` | ● | ● | ● |  |  |  |  |  |  |  |
| `merchant.invoice` | ● |  |  |  |  |  | ● |  |  |  |
| `merchant.khata` | ● | ● |  |  |  |  | ● |  |  |  |
| `merchant.order` | ● | ● |  |  |  |  |  |  |  |  |
| `merchant.payment` | ● |  |  |  |  |  | ● |  |  | ● |
| `merchant.statement` | ● |  |  |  |  |  | ● | ● |  |  |
| `merchant.tab` | ● | ● |  |  |  |  | ● |  |  |  |
| `merchant.withdrawal` | ● |  |  |  |  |  | ● |  |  |  |
| `ops.audit` | ● |  |  |  |  |  |  |  |  |  |
| `ops.bank_account_approval` | ● |  |  |  |  | ● |  |  |  |  |
| `ops.complaint` | ● | ● | ● |  |  |  |  |  |  |  |
| `ops.dispute` | ● | ● | ● |  |  |  |  |  |  |  |
| `ops.incident` | ● | ● | ● |  |  |  |  |  |  |  |
| `ops.kyc_check` | ● | ● |  |  |  |  |  |  |  |  |
| `ops.merchant_approval` | ● |  |  |  |  | ● |  |  |  |  |
| `ops.monitoring` |  |  |  |  |  |  | ● |  |  |  |
| `ops.onboarding_request` | ● |  |  |  |  | ● |  |  |  |  |
| `ops.recon` | ● |  | ● |  |  |  | ● |  |  |  |
| `ops.report` | ● |  |  |  |  |  | ● |  |  |  |
| `ops.supplier_approval` | ● |  |  |  |  | ● |  |  |  |  |
| `ops.suspension` | ● |  |  |  |  |  |  |  | ● |  |
| `org.onboarding` | ● | ● |  |  | ● | ● |  |  |  |  |
| `org.rights` | ● |  |  |  |  |  |  |  | ● |  |
| `org.suspension` |  |  |  |  |  |  |  |  | ● |  |
| `org.tree` | ● |  |  |  |  |  |  |  |  |  |
| `org.user` | ● | ● |  | ● |  |  | ● |  |  |  |
| `supplier.account` | ● |  |  |  |  |  |  |  |  |  |
| `supplier.bank_account` | ● |  | ● | ● |  |  |  |  |  |  |
| `supplier.catalogue` | ● | ● |  | ● |  |  |  |  |  |  |
| `supplier.invoice` | ● | ● |  |  |  |  |  |  |  |  |
| `supplier.order` | ● |  |  |  |  |  |  |  |  |  |
| `supplier.statement` | ● |  |  |  |  |  | ● | ● |  |  |

**Every handler and its cell:**

| Row | Verb | Handlers (controller method) |
|---|---|---|
| `(public)` | — | activate, env, login, lookup, pay, registerMerchant, registerSupplier, requestOtp, verifyOtp |
| `(service identity)` | — | providerEvent |
| `(signed-in)` | — | me |
| `admin.access` | read | access |
| `admin.access` | administer | revokeAccess |
| `admin.aml_alert` | read | amlAlerts |
| `admin.api_client` | read | apiClients |
| `admin.api_client` | create | createClient |
| `admin.api_client` | administer | revokeClient |
| `admin.config` | read | limits, rules, settings, tariffs, templates |
| `admin.config` | modify | toggleRule, upsertLimit, upsertSetting, upsertTariff, upsertTemplate |
| `admin.connector` | read | connectors |
| `admin.connector` | modify | upsertConnector |
| `admin.connector` | execute | testConnector |
| `admin.connector` | administer | rotate |
| `admin.dsar` | read | dsars |
| `admin.dsar` | create | createDsar |
| `admin.dsar` | export | export |
| `admin.platform_ops` | read | backups, deployments, health, restoreTests |
| `admin.platform_ops` | execute | backupNow, restoreTest |
| `admin.platform_ops` | administer | deploy, rollback |
| `admin.sar` | read | sars |
| `admin.sar` | create | createSar |
| `admin.sar` | submit | submitSar |
| `admin.simulation` | read | scenarios, simControls |
| `admin.simulation` | simulate | clearBreaks, injectBreak, runScene, setSimControls, simulateAdvance, simulateInvoicePayment, simulatePayment, synthetic |
| `kyc.application` | read | get, list, listV2, mine, mineV2 |
| `kyc.application` | modify | save, upgrade |
| `kyc.application` | delete | withdraw |
| `kyc.application` | submit | submit |
| `kyc.form` | read | list, schemaForOrg, schemaForOwnOrg |
| `kyc.task` | read | inbox |
| `kyc.task` | authorize | decide |
| `lender.advance` | read | advances, consents |
| `lender.advance` | authorize | declineOffer, fund |
| `lender.assessment` | read | assessments |
| `lender.assessment` | execute | assess |
| `lender.collection` | read | collections |
| `lender.collection` | execute | sweep |
| `lender.portfolio` | read | portfolio, records |
| `lender.portfolio` | execute | snapshot |
| `merchant.account` | read | balance, billingPreview, dashboard, devices, ledger, settings, speakerLog |
| `merchant.advance` | read | consents, currentAdvance, eligibility, lenders |
| `merchant.advance` | create | consent |
| `merchant.advance` | delete | withdrawConsent |
| `merchant.advance` | submit | requestAdvance |
| `merchant.advance` | authorize | acceptAdvance |
| `merchant.advance` | simulate | simulateFund |
| `merchant.inventory` | read | alerts, inventory, reorderRules |
| `merchant.inventory` | create | addItem |
| `merchant.inventory` | modify | adjust, reorder |
| `merchant.invoice` | read | invoiceDetail, invoices |
| `merchant.invoice` | execute | payInvoice, retryTimeout |
| `merchant.khata` | read | khata, khataEntries |
| `merchant.khata` | create | addKhataEntry, createKhata |
| `merchant.khata` | execute | remindKhata |
| `merchant.order` | read | catalogue, orderDetail, orders |
| `merchant.order` | create | placeOrder |
| `merchant.payment` | read | payInfo, paymentDetail, payments |
| `merchant.payment` | execute | accept, refund |
| `merchant.payment` | simulate | simulate |
| `merchant.statement` | read | statements |
| `merchant.statement` | execute | generateStatement |
| `merchant.statement` | export | downloadStatement |
| `merchant.tab` | read | tabEntries, tabs |
| `merchant.tab` | create | addTabEntry, createTab |
| `merchant.tab` | execute | remindTab, settleTab |
| `merchant.withdrawal` | read | withdrawals |
| `merchant.withdrawal` | execute | withdraw |
| `ops.audit` | read | audit |
| `ops.bank_account_approval` | read | bankQueue |
| `ops.bank_account_approval` | authorize | approveBankAccount |
| `ops.complaint` | read | complaints |
| `ops.complaint` | create | createComplaint |
| `ops.complaint` | modify | assign, resolveComplaint |
| `ops.dispute` | read | disputes |
| `ops.dispute` | create | createDispute |
| `ops.dispute` | modify | resolveDispute |
| `ops.incident` | read | incidents |
| `ops.incident` | create | createIncident |
| `ops.incident` | modify | ack, resolveIncident |
| `ops.kyc_check` | read | kycChecks |
| `ops.kyc_check` | create | recordKycCheck |
| `ops.merchant_approval` | read | merchantQueue, merchantsAll |
| `ops.merchant_approval` | authorize | approveMerchant |
| `ops.monitoring` | execute | touch |
| `ops.onboarding_request` | read | onboardingRequests |
| `ops.onboarding_request` | authorize | decideOnboarding |
| `ops.recon` | read | breaks, reconRuns |
| `ops.recon` | modify | resolveBreak |
| `ops.recon` | execute | runRecon |
| `ops.report` | read | feeReports, payments, settlement |
| `ops.report` | execute | generateStatement |
| `ops.supplier_approval` | read | supplierQueue, suppliersAll |
| `ops.supplier_approval` | authorize | approveSupplier |
| `ops.suspension` | read | suspensions |
| `ops.suspension` | administer | lift, suspend |
| `org.onboarding` | read | queue |
| `org.onboarding` | create | submitChild |
| `org.onboarding` | submit | requestAdminRecovery |
| `org.onboarding` | authorize | decide |
| `org.rights` | read | grantable, rights |
| `org.rights` | administer | narrowRights |
| `org.suspension` | administer | lift, suspend |
| `org.tree` | read | descendants, me, meDescendants, mePath, meTree, org, outbox, path |
| `org.user` | read | meUsers |
| `org.user` | create | addStaff |
| `org.user` | delete | deactivate |
| `org.user` | execute | resend |
| `supplier.account` | read | paymentsReceived, profile |
| `supplier.bank_account` | read | bankAccounts |
| `supplier.bank_account` | modify | changeBankAccount |
| `supplier.bank_account` | delete | revokeBankAccount |
| `supplier.catalogue` | read | catalogue |
| `supplier.catalogue` | create | addToCatalogue |
| `supplier.catalogue` | delete | retireItem |
| `supplier.invoice` | read | invoiceDetail, invoices |
| `supplier.invoice` | create | issueInvoice |
| `supplier.order` | read | orderDetail, orders |
| `supplier.statement` | read | statements |
| `supplier.statement` | execute | generateStatement |
| `supplier.statement` | export | download |
