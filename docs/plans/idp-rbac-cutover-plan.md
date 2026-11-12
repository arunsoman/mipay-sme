# Plan: login as a plugin, users owned by the core, permissions in RBAC

Status: **draft for review** · 2026-11-11

Implementation note (2026-11-12): the development comms service now runs in
Java (`trustplane/java/comms-service`). The IdP has a guarded `enforce` login mode
for TOTP and password-plus-SMS; the broader 2FA and P3 work remains open. Existing
test users and orgs are disposable, so `_goal.md` replaces the historical P3
backfill requirements for designated test environments.
Design background: [`fapi2-design.md`](fapi2-design.md) (§3–5 gateway and FAPI flows, §9 and Appendices F–G RBAC). That document has been revised to match this plan: decisions I5, I6, I9, §6 "The IdP becomes a credential plugin", and §8 steps 1–2.

**Goal:** the MiPay backend stops storing passwords, issuing tokens and hard-coding roles, but it **stays the owner of who is a legitimate user**, because onboarding and KYC decide that.

| Concern | Owner | Output |
|---|---|---|
| **Who is legitimate** (party, org, status) | **Core**: onboarding, using the KYC plugin | users, created PENDING and made ACTIVE through onboarding/KYC |
| **Who is at the keyboard** (credentials, 2FA, sessions) | **Auth plugin**: trustplane IdP + gateway BFF (FAPI 2.0) | a signed claim: issuer, subject, `uid`, `acr`, `amr` |
| **Linking the two** | **Core**: `identity_links` + `IdentityResolver` | the claim resolved to an ACTIVE user, or refused |
| **What they may do** | **Trustplane RBAC** | allow/deny for `(user_id, org, resource:verb)` |

---

## 1. The model

```
 Onboarding (core) ──uses──▶ KYC plugin (collection, checks, risk, workflow)
        │                          │ outcome: verified org + people
        ▼                          ▼
   ┌──────────── core: user master (who is legitimate) ───────────────────┐
   │ users           (id, org_id, status, name, username, phone, email…)   │
   │ identity_links  (user_id, provider, issuer, subject, status, …)       │
   └───────────────────────────────────────────────────────────────────────┘
        ▲ resolve (iss, sub, uid) → user        │ enrol / disable / revoke / reset   (AuthProvider contract)
        │                                       ▼
 Auth plugin: trustplane IdP + gateway BFF ── proves control of a credential; tokens carry uid = user_id
 RBAC ── decides what user_id may do in org X (the gateway checks before routing, the backend checks again)
```

### 1.1 Rules

1. **Only the core creates users.** That happens during onboarding (org admin at KYC go-live, team members added by an org admin, platform staff added by a platform admin). The auth plugin never creates a user. It creates a *credential account* only when the core asks it to enrol an existing user.
2. **The core owns user status.** `UserStatus` in MiPay is the authority. Suspending, archiving or deactivating a user is a core decision, and the core then *commands* the auth plugin (disable the account, revoke sessions). The auth plugin's own state (locked after failed attempts, credential reset) is reported back as events, but never changes who is legitimate.
3. **A user can have several credentials** (rows in `identity_links`): a portal password today, and later passkeys, a bank or national eID, Google Workspace for staff, or a mobile-app key. A partner API client is a link too: a *service user* of the onboarded partner org, with `subject = client_id`.
4. **Tokens carry `uid` (decision (a)).** At enrolment the core tells the IdP the `user_id`. The IdP stores it on the credential account and puts it in every ID and access token as `uid`. The gateway uses `uid` for RBAC with no extra call. The core still checks the link on every request (defence in depth), so a forged or stale `uid` is refused.
5. **There are two kinds of assurance.** KYC gives *identity assurance* (this person is who they say; stored on the user). The auth plugin gives *authentication assurance* (`acr`/`amr`: they just proved it with these factors). Policy can require both, e.g. a payout needs a KYC-verified user **and** `acr=aal2`.
6. **Login is a platform provider slot, not a per-org plugin.** It sits in front of everything and fails closed, so it is not turned on with `SELF`/`SUBTREE` grants like Inventory. It follows the KYC pattern instead: a contract in the core, with swappable implementations. Trustplane is the first.

### 1.2 The contract (core side, shaped like `KycPluginContracts`)

```java
public final class AuthPluginContracts {

    /** One credential provider (trustplane IdP first). Registered in AuthProviderRegistry by key(). */
    public interface AuthProvider {
        String key();                                   // "trustplane"
        String issuer();                                // "https://auth.afriqpay.com"

        /** Create a credential account for an existing core user and return a one-time set-up link. */
        Enrolment enrol(EnrolRequest req);
        /** Re-issue a set-up link (LINK), or wipe factors (MFA) / factors + password (FULL) and issue a recovery link. */
        Enrolment resetCredential(String subject, ResetScope scope);
        void disable(String subject, String reason);     // login refused, sessions revoked
        void enable(String subject);
        void revokeSessions(String subject);             // back-channel logout; also clears remembered devices
        void updateLoginName(String subject, String loginName, String phone);
        /** 2FA profile decided by the core from RBAC roles and the org (2fa-design.md §5.1). */
        void setMfaProfile(String subject, MfaProfile profile, boolean orgRequiresMfa);
        void unlink(String subject);                     // account deleted at the provider

        /** Map the provider's acr/amr onto the platform's assurance levels. */
        Assurance assuranceOf(String acr, List<String> amr);

        record EnrolRequest(String userId, String loginName, String phone, String displayName, Channel channel) { }
        record Enrolment(String subject, String oneTimeLink, Instant expiresAt) { }
        enum Channel { EMAIL, SMS }
        enum ResetScope { LINK, MFA, FULL }
        enum MfaProfile { PLATFORM_STAFF, ORG_ADMIN, ORG_MEMBER, MERCHANT_OTP }
        enum Assurance { AAL1, AAL2, AAL3 }
    }

    /** Events the provider reports back (mTLS, POST /internal/auth/events). */
    public record AuthEvent(String provider, String subject, String userId, Type type, Instant at, Map<String, Object> detail) {
        public enum Type { CREDENTIAL_SET, CREDENTIAL_RESET, LOCKED, UNLOCKED, ASSISTED_RECOVERY_REQUESTED,
                           MFA_ENROLLED, MFA_REMOVED, RECOVERY_CODE_USED, SUSPICIOUS_LOGIN }
    }
}
```

- **Every message goes through the comms plane, addressed by the core user id** (`user:<uid>`; [`2fa-design.md`](2fa-design.md) D10):
  - the core sends what it starts (set-up links it asked the IdP for, onboarding, KYC, assisted-recovery links);
  - the IdP sends what happens inside a login (login OTP, 2FA codes, push challenges, self-service reset links, security notices);
  - **the core is the only writer of contact details** in comms, so the IdP never holds an email address or a delivery phone;
  - raw links and codes are built at dispatch and never stored (the H8 rule);
  - until the comms plane is live, both use the sandbox `CommsClient` (`sim_outbox`).
- **The IdP never sees KYC data.** It gets only what it needs for login: `uid`, login name, phone (for OTP), and a display name.

### 1.3 `identity_links` (core table)

| Column | Meaning |
|---|---|
| `id` | link id |
| `user_id` | core user (FK `users.id`) |
| `provider` | `trustplane`, later `bank-eid`, `google-staff`, `partner-client` … |
| `issuer`, `subject` | the provider's `iss` and `sub`; unique together |
| `status` | `PENDING` (enrolled, no credential yet) · `ACTIVE` · `DISABLED` (by an admin, for this link only) · `REVOKED` |
| `idp_enabled` | whether the IdP account is currently enabled; kept in step by `AuthSyncService.reconcile` (§1.5) |
| `max_aal` | the strongest factor this link has enrolled (from events) |
| `created_by`, `created_at`, `activated_at`, `last_login_at` | audit |

### 1.4 Flows

**Enrolment (replaces activation):**
1. KYC go-live or "add team member" leaves a core user in PENDING.
2. `UserLifecycleService.enrol(user)` calls `AuthProvider.enrol`. The IdP creates the account with `uid=user.id` and returns `subject` and a one-time link.
3. The core stores `identity_links(PENDING)` and sends the link through the comms plane.
4. The user sets a password (and later 2FA) **at the IdP**.
5. The IdP posts `CREDENTIAL_SET`. The core sets the link ACTIVE and the user ACTIVE, with `activated_at`.

**Every request:**
1. The gateway checks the session or token and does RBAC on `uid`.
2. It forwards a signed context with `iss`, `sub`, `uid`, `sid`, `acr`, `amr`, `auth_time`.
3. The core `GatewayIdentityFilter` verifies the HMAC. `IdentityResolver` finds the link by `(iss, sub)` and requires the link ACTIVE, `link.user_id == uid`, the user ACTIVE and the org operational.
4. The core builds `Current.Principal`. Otherwise it returns 401/403.

**Suspend / deactivate / archive:**
1. The core changes `users.status`.
2. For each link, `AuthProvider.disable(subject)`; the IdP back-channel-logs-out the gateway sessions.
3. Commands go through an outbox, so a provider outage delays the logout but never loses it. Meanwhile the per-request resolver check refuses the user immediately.

**"Can't get in":**
- A user who forgot their password uses the IdP's "Forgot password?". The IdP asks the core to send a reset link to the phone or email the core holds (P1.9).
- The core's `ADMIN_RECOVERY` request (an org with no working admin, `OrgOnboardingService.requestAdminRecovery`) stays a reviewed request. On approval, the core creates the *replacement* admin in that org with `createInOrg` and enrols them, as today's flow does with activation.

### 1.5 Every user belongs to an org, and the org's state governs the user

Today `users.org_id` can be null (legacy users are bound lazily in `OrgProvisioningService.ensureEntityOrg`). A KYC reject or block (`KycGoLiveService.terminate`) archives only *pending* users. These rules close both gaps.

**Invariants**

| # | Rule |
|---|---|
| U1 | **Every user has exactly one home org** (`users.org_id NOT NULL`, FK to `organizations`). Platform staff belong to the `PLATFORM` root org; a partner API service user belongs to the partner's org. No user exists outside the org tree |
| U2 | **A user is created only inside an org that onboarding/KYC knows about**, through `UserLifecycleService.createInOrg(orgId, …)`:<br>• the KYC applicant admin may be created while the org is `PENDING` (they need to fill in the application), with a link limited to the KYC screens until go-live;<br>• everyone else needs the org `ACTIVE` and operational |
| U3 | **A user can act only if the user *and* their org are fine:** `user.status = ACTIVE` **and** org lifecycle `ACTIVE` **and** no active hold on the org or any ancestor (`OrgAccess.isOperational`) **and** the org's KYC is not lapsed. This is checked by `IdentityResolver` on every request, so deactivating an org deactivates its users and its whole subtree **at once, by default**, with no per-user step that can be forgotten |
| U4 | **Org state is derived, not copied onto users.** Suspending an org does *not* write SUSPENDED onto each user row. If it did, lifting the hold could not tell "suspended because of the org" from "suspended individually", and would either wrongly reinstate or wrongly keep people locked out. Only permanent org outcomes (rejected, blocked, closed) write a terminal user status |
| U5 | **A user never moves between orgs.** Someone who changes company gets a new user in the new org. Audit, RBAC assignments and KYC evidence stay with the old one |
| U7 | **User management cannot happen without the org and its KYC state.**<br>• There is no org-less "create / edit / disable user" anywhere: not in the core API, not in RBAC, not in the IdP;<br>• every user-management endpoint is org-scoped (`/api/orgs/{orgId}/users/…`, or `/me/users` for the caller's own org), checks the RBAC cell `org.user:<verb>` **on that org's tenant**, and goes through `UserLifecycleService`, which loads the org's lifecycle, holds and KYC status first;<br>• the IdP enrolment API accepts only the core's mTLS client, so nothing can create a credential for a user the core did not create in an org;<br>• the admin portal's cross-org Users list is a read view; every action on it resolves the user's org first |
| U6 | **Platform staff are not affected by the orgs they act on.** A platform reviewer's home org is `PLATFORM`; suspending a merchant blocks that merchant's users, not the reviewer. The same holds for a parent-org admin acting on a child org through an RBAC subtree assignment |

**What each org or KYC event does to its users**

| Event (core) | Users of the org **and its subtree** | Credential links / IdP | RBAC |
|---|---|---|---|
| Org suspended (`OrgSuspensionService.suspend`, a manual hold) | Refused on the next request (U3); rows unchanged | `reconcile(subtree)` → `disable` + `revokeSessions` for every link | Tenant marked held (org-tree sync); `Check` denies in a held tenant or below it |
| Hold lifted (`lift`) | Allowed again automatically, unless their own status, another hold or an ancestor hold still blocks them | `reconcile(subtree)` → `enable` only for links whose user is now allowed | Tenant hold cleared |
| KYC periodic review lapsed / KYC expired (`kyc-review` plugin) | A system hold of kind `KYC_LAPSED` on the org; same effect as a suspension | Same as suspended | Same as suspended |
| KYC rejected / blocked (`KycGoLiveService.terminate`; org → `REJECTED` / `BLOCKED`) | **All** users of the org (not only pending ones) → `ARCHIVED`; subtree orgs follow the same rule | Links → `REVOKED`; accounts deleted at the IdP (`unlink`) | Assignments of the org's tenants removed; grants revoked |
| Org closed / archived | All users → `ARCHIVED` | Links → `REVOKED`, `unlink` | Assignments and grants removed |
| User deactivated / suspended individually | That user only | `disable` + `revokeSessions` for that user's links | Assignments kept (removed on archive) |

**How the IdP is kept in step: one reconcile, not many cascades.** `AuthSyncService.reconcile(userIds)` computes, for each link:

```
should_be_enabled = link.status = ACTIVE
                    AND user.status = ACTIVE
                    AND orgAccess.isOperational(user.orgId)   // own org + ancestors, lifecycle + holds + KYC
```

It compares this with `identity_links.idp_enabled` and queues `enable` / `disable` (+ `revokeSessions`) only where they differ.
- **When it runs:** after every org lifecycle change, suspension, lift, KYC outcome and user change (over the affected subtree), and nightly over everyone.
- **Why one function:** a lift can never re-enable someone who is blocked for another reason, and a missed event is repaired by the nightly run.
- **The IdP's role is UX, not security:** `IdentityResolver` (U3) still refuses on every request, so the IdP being briefly out of step changes only whether a blocked user can *start* a login, never what they can do.

---

## 2. Starting point (from the code)

| Area | Today | Files |
|---|---|---|
| Login | `/api/auth/login`, `/otp`, `/otp/verify`, `/activate`; bcrypt; OTPs held in memory; HS256 tokens valid for 12 h | `web/AuthController`, `service/AuthService`, `security/JwtService` |
| Token check | Bearer token, then a lookup of its id in `devices_sessions`, then a check that the org is not suspended | `security/JwtAuthFilter`, `domain/core/DeviceSession` (used in 10 files) |
| Users | `users.password_hash`, `role` (7-value `UserRole`), `org_role` (ADMIN/STAFF), `status` | `domain/core/User`; written by `OrgOnboardingService`, `OnboardingService`, `ActivationService`, `OrgGoLiveBridge`, `OrgProvisioningService` |
| Activation | Activation token in the backend; 72 h, single use; KYC go-live calls `activation.issue(admin)` | `service/ActivationService`, `web/ActivationController`, `service/kyc/KycGoLiveService:89` |
| Authorization | `hasRole` per path; `MoneyGuard.requireAdmin`; `Current.isOrgAdmin`; `OrgCallerService.platformStaff` (7 files); `UserPlatformFunction` (`KycWorkflowEngine`); `OrgRight` (11 files) | `security/SecurityConfig` and the services listed |
| RBAC in the backend | 201 `@Perm` + 13 `@PublicEndpoint` annotations; manifest published; org tree synced; **`RbacAuthz.check()` always returns `true`** | `security/rbac/*` |
| Gateway | Passes `/api` through with `forward_authorization: true`. The BFF (`/auth/*`) and `gw_sessions` exist, but **routes can only be `auth: none\|token`** | `trustplane/internal/gateway/{bff,session,config}.go`, `config/gateway/routes.yaml` |
| IdP | `users(user_id, password_hash, scopes, active)` and dev seed users. No enrolment API, lockout, `uid` claim or back-channel logout | `trustplane/internal/idpserver/server.go` |
| RBAC service | A generic supplier-delegation demo model (§4) | `trustplane/java/rbac-service` |
| Portals | Token in `localStorage`, sent as `Authorization: Bearer`; each has its own login and activate screens | `admin-portal/core/{auth,api}.ts`, `org-portal/core/{auth,api}.ts`, `src/app/core/{auth.service,http}.ts` |

Reusable parts already in the repo:
- `trustplane/java/shared-lib` has `GatewayContextFilter`, `GatewayContext` and `GatewayContextAuthentication`, which verify the gateway's `X-SME-Context` HMAC header.
- `SessionStore.RevokeBySub` revokes all sessions of one user.
- RBAC's `org_closure` already answers subtree questions.
- The KYC plugin registry (`KycPluginRegistry`) is the pattern for `AuthProviderRegistry`.

---

## 3. Switches

| Switch | Values | Owner |
|---|---|---|
| `app.identity.mode` | `legacy` → `dual` → `gateway` | backend. In `dual`, it accepts its own token **or** the gateway context; in `gateway`, only the gateway context |
| `app.auth.provider` | `none` / `trustplane` | backend: when set, lifecycle changes call the provider through the outbox; enrolment replaces activation |
| `app.rbac.sync` | `off` / `on` | backend: role and org changes are also written to RBAC assignments and grants |
| `app.rbac.mode` | `off` / `shadow` / `enforce` | backend `RbacAuthz` (today `enforce` is refused at startup) |
| IdP `mfa.mode` | `off` → `shadow` → `enforce` | IdP: 2FA ([`2fa-design.md`](2fa-design.md) §4). Ships `off`; factors that need a comms channel appear on their own when it is live |
| gateway `hosts.<host>.bff` | `false` / `true` | gateway: the portal host requires a BFF session |
| gateway route `auth` | `none` / `token` / **`session`** (new) | gateway |

---

## 4. Deleting the existing RBAC

The trustplane RBAC that exists today was built for a generic "supplier delegation" demo (tenants `acme`/`globex`, suppliers A/B, users `alice`/`bob`/…). None of it matches MiPay's org tree, and nothing enforces with it yet. **Delete it and rebuild it as P2.** The org-tree sync and the `@Perm` catalogue are the only parts that carry over.

### 4.1 Delete

| What | Where | Note |
|---|---|---|
| Demo seed: tenants `acme`, `globex`; suppliers `supplier-a`, `supplier-b`; users `rootadmin`, `alice`, `bob`, `carol`, `dave`, `saadmin`, `priya`, `sbadmin`; roles `platform_admin`, `supplier_admin`, `supplier_operator`, `tenant_operator`; their assignments | `trustplane/config/rbac/seed.yaml` (deleted; the grid seed is `grid.yaml`) | Replaced by the MiPay seed (P2.5): the grid catalogue and starting roles only. **No users and no tenants in the seed**: tenants come from the org-tree sync, assignments from `RbacBackfill` |
| The supplier delegation model: tables `suppliers`, `supplier_groups`, `group_roles`, `group_members`; columns `users.supplier_id`, `users.kind`, `grants.supplier_id`, `assignments.supplier_id`, `assignments.group_id`; `scope IN ('own','all')` | `rbac-service/core/SchemaAndSeed.java`, `RbacStore.java` | MiPay delegates by **org** (the subtree), not by supplier. Grants become `(grantor_org, grantee_org, role ceiling, can_delegate, expiry)`; scope becomes `own \| subtree \| all` |
| Controllers for that model: `SuppliersController`, `GroupsController`, and the supplier paths in `GrantsController` / `AssignmentsController` | `rbac-service/web/` | Rewritten in P2.6–2.7 |
| The RBAC service's own `users` table | `SchemaAndSeed.java` | The core owns users. RBAC keys assignments by the core `user_id` (the token's `uid`); display names come from the core when a screen needs them |
| Flat `permissions` + `role_permissions` tables, and the CRUD+approve permissions made by `@CrudResource` (`rbac.supplier:read` …) | `SchemaAndSeed.java`, `shared-lib/CrudResource`, `PermissionRegistrar`, `PermissionMatrix` | Replaced by the grid (`resources` × verbs, bitmask per role). RBAC's own admin endpoints get grid rows (`rbac.role`, `rbac.assignment`, `rbac.grant`, `rbac.review`) |
| `org_tenant_mapping` | `SchemaAndSeed.java`, `RbacStore.replaceOrgTenantMapping` | Tenant id is the org id (`OrgTreeSyncService:83` already sends them 1:1), so the mapping table adds nothing. `tenants` is filled by the sync |
| Gateway routes `rbac.supplier:*` and `rbac.user:*` | `trustplane/config/gateway/routes.yaml` (about lines 145–260) | Replaced by routes for the new admin API |
| e2e checks built on the demo seed (delegated supplier CRUD, cascading revocation through groups) | `trustplane/cmd/fapi-client/suite.go` | Rewritten against the MiPay seed: org admin in A cannot assign in B; a subtree grant covers a child org; revocation reaches the gateway in ≤ 10 s; SoD conflicts are refused |
| Demo data in `audit_log` | rbac DB | Truncated by the reset in 4.3 |
| **Backend:** `Tenant` entity, `TenantRepository`, the `tenants` table, `TenantType`, `TenantStatus` | `domain/core/Tenant.java`, `repository/TenantRepository.java`, `Enums` | Unused today. Delete in P0 (`V14__drop_tenants.sql`) |
| **Backend:** `RbacClientService` calls to the old endpoints (`/roles`, `/users`, `/assignments`), and the `Dtos.Rbac*View` shapes | `security/rbac/RbacClientService.java`, `web/Dtos.java` | Rewritten for the grid API in P5.5 (with the Roles & access screen). `AdminController` `/api/admin/rbac/*` keeps its paths and changes its bodies. *(P2 kept `GET /rbac/admin/roles` returning `{name, description, permissions:[…]}` so the shape stays readable)* |
| **Admin portal:** the role/permission-list editor | `admin-portal/features/permission-matrix`, `features/permissions` | Rebuilt as the Roles & access screen (grid editor, holders, SoD conflicts, approvals) |

### 4.2 Keep

| What | Why |
|---|---|
| `@Perm`, `Verb`, `TenantSource`, `@PublicEndpoint`, `EndpointManifestPublisher` (backend) | They are the catalogue the grid is built from |
| `OrgTreeSyncService`, `org_closure`, the `/org-tree/sync` endpoint | Subtree checks depend on them |
| `MeshTls`, the gRPC `Check` / `Watch` / `Ping` and the gateway PEP with its watch-invalidated cache | The decision plane stays; only the data model behind it changes |
| `RbacAuthz` (backend) | Rewritten in P6 to call `Check` |
| The `grants` and `assignments` tables, by name | Their columns change as listed in 4.1 |

### 4.3 How to reset it

1. On every environment (local, staging, afriqpay), take a dump of the `rbac` database for reference.
2. Then drop and recreate it. Nothing reads its contents, so no data migration is needed. *(As built, the new RBAC does this itself at its first boot: it detects the demo schema and drops it before creating the grid schema. The dump in step 1 is still an ops step.)*
3. `SchemaAndSeed` creates the new schema at boot; the backend's org-tree sync refills `tenants` and `org_closure`; `RbacBackfill` (P3) creates the assignments and grants.
4. Ship P2.0 as one change, so the demo model is never half-removed: schema, seed, controllers, routes and e2e suite together.

---

## 5. Phases

Effort is in developer-days. P1 and P2 can run in parallel.

```
P0 edge ─┬─ P1 IdP as credential plugin ─┬─ P3 core: links, resolver, contract ─ P4 gateway sessions ─ P5 portals ─┐
         └─ P2 RBAC rebuild ─────────────┘                                                                         ├─ P7 cut-over
                                              P6 RBAC shadow → enforce (after P2 + P3) ────────────────────────────┘
```

### P0. Edge and preparation (1 day)

| # | Task | Where |
|---|---|---|
| 0.1 | FAPI TLS settings on the host nginx; `auth.afriqpay.com` vhost pointing at the gateway | `deploy/afriqpay/nginx-mipay-sme.conf` |
| 0.2 | Gateway builds the DPoP `htu` from `X-Forwarded-Host`, but only when the request comes from a `trusted_proxies` peer | `gateway/authn.go` |
| 0.3 | Public IdP issuer `https://auth.afriqpay.com`; turn off `SeedUsers` outside dev | `idpserver/config.go`, `server.go:1135` |
| 0.4 | Add the switches from §3 (no behaviour change yet) | `config/AppProperties` |
| 0.5 | Delete the unused `Tenant` entity, `TenantRepository`, `TenantType`, `TenantStatus`; migration `V14__drop_tenants.sql` (§4.1) | `domain/core/Tenant.java`, `Enums` |

**Done when:** `make e2e` is green; a DPoP proof made for the public URL is accepted through nginx.

### P1. IdP becomes a credential plugin (4–5 days)

The IdP holds **credential accounts**, not users. It has no org, role, KYC or business status.

| # | Task | Where |
|---|---|---|
| 1.1 | Replace the IdP `users` table with `accounts`:<br>• `subject` (IdP-generated, opaque, used as `sub`);<br>• `uid` (the core user id, unique);<br>• `login_name` (unique) and `phone` (unique, for OTP);<br>• `display_name`;<br>• `password_hash` (nullable until the credential is set);<br>• `state` (`ENROLLING` / `ACTIVE` / `DISABLED`);<br>• `failed_attempts`, `locked_until`, `password_changed_at`, `created_at` | `idpserver/server.go` DDL |
| 1.2 | Login by login name **or** phone. Refuse accounts that are `DISABLED`, still `ENROLLING`, or locked. Lockout per account (back-off, then a temporary lock), on top of the per-IP limit | `idpserver` `login()` |
| 1.3 | **Enrolment API** (mTLS, `client_id=mipay-core` only). Implements the `AuthProvider` contract (§1.2):<br>• `POST /accounts` with `{uid, login_name, phone, display_name}` returns `{subject, link, expires_at}`;<br>• `POST /accounts/{subject}/reset-link`, `/disable`, `/enable`, `/revoke-sessions`;<br>• `PATCH /accounts/{subject}` (login name or phone);<br>• `DELETE /accounts/{subject}`;<br>• `GET /accounts/{subject}` | new `idpserver/accounts.go` |
| 1.4 | One-time set-up and reset links: 72 h, single use, stored hashed; a set-password page in the IdP; password policy matching today's `ActivationService` rules. Set-up links are **returned to the core**, which sends them through comms; self-service reset links are sent by the IdP through comms (§1.2) | new `idpserver/account.go` |
| 1.4a | **`CommsClient`** in the IdP: `Send(event, to=user:<uid>, lane, expiresAt, data)`, plus `capabilities()` for factor readiness. A sandbox implementation (writes `sim_outbox`) is used until the comms plane is live | new `idpserver/comms.go` |
| 1.5 | **Events to the core:** `CREDENTIAL_SET`, `CREDENTIAL_RESET`, `LOCKED`, `UNLOCKED` (2FA adds `MFA_ENROLLED`, `MFA_REMOVED`, `RECOVERY_CODE_USED`, `SUSPICIOUS_LOGIN`), sent as `POST /internal/auth/events` over mTLS, signed, with retries and an event id for de-duplication | `idpserver` |
| 1.6 | Back-channel logout: `disable` and `revoke-sessions` make the IdP POST a logout token to the gateway, and the gateway calls `SessionStore.RevokeBySub` | `idpserver`, `gateway/bff.go` |
| 1.7 | Token claims: `sub` (the account subject), **`uid`** (the core user id), `acr`, `amr`, `auth_time`, `sid` | `grantAuthCode`, `grantRefresh` |
| 1.8 | **Merchant phone-OTP login as an IdP method** (D8), replacing `AuthService.requestOtp` / `verifyOtp` and its in-memory `otpStore`:<br>• sent with `CommsClient.Send(auth.otp.sms, user:<uid>, lane=CRITICAL, expiresAt=+5 min)`, so it goes only to the phone the core registered in comms and is never sent late;<br>• stored hashed, valid 5 min, single use, at most 5 wrong attempts (then the account's lockout applies), resends throttled (at most 1 per 60 s and 5 per hour);<br>• **offered only while `comms.sms` is live** (`2fa-design.md` §4.2); until then, merchants sign in with a password;<br>• the login counts as `acr=aal1`; payouts and bank-account changes need step-up (D7);<br>• the sandbox keeps a fixed dev OTP behind the same simulated-environment flag as today | `idpserver` |
| 1.9 | Login page styled to match the portals, with "Forgot password?" (`2fa-design.md` §9.3, sent by the IdP through comms, **shown only while email or SMS is live**) and "Can't get in?" (forwards an assisted-recovery request to the core) | `renderLogin` |

**Tests:** extend `fapi-client suite`:
- the enrolment API is rejected without mTLS or from another client;
- enrol, then set a credential, then log in; the token carries `uid`;
- a disabled account cannot log in, and its existing session dies;
- lockout after N failures;
- a set-up link cannot be reused;
- the `CREDENTIAL_SET` event is delivered once, even across retries.

**Done when:** the IdP can enrol and authenticate a credential account for a given `uid` without any user data of its own.

**Status: ✅ done (2026-11-12).** As built (`trustplane/internal/idpserver`), with the decisions taken while building it:

| Item | As built |
|---|---|
| 1.1 accounts | `accounts` table replaces `users` (dropped at boot). Seeded demo accounts (dev/sandbox only) get a stable subject `acc_seed_…`; `IDP_SEED_USERS` takes `name:pw:scopes[:uid[:phone[:mfa profile]]]` (scopes ignored: accounts carry no authority) |
| 1.2 login + lockout | Login name (case-insensitive) or phone. DISABLED, ENROLLING, unknown and locked all get the same message and the same bcrypt cost. After `IDP_LOCK_AFTER` (5) consecutive failures the account locks for `IDP_LOCK_BASE` (15 min), doubling per further lock up to `IDP_LOCK_MAX` (24 h); a locked account keeps counting failures. Emits `LOCKED` / `UNLOCKED` |
| 1.3 enrolment API | `/accounts…` on the IdP's mesh listener; caller must present the core's mesh certificate (`IDP_CORE_CN`, default `sme-mipay-mesh`); the gateway has **no route** to it (e2e checks both). `POST /accounts` is idempotent while ENROLLING (outbox retries get the same subject and a fresh link). Also `GET /accounts?uid=` for reconciliation and `PUT /accounts/{subject}/mfa-profile` (`setMfaProfile`) |
| 1.4 links | Stored as SHA-256 only; single use; a new link of the same purpose kills the previous one. Three purposes: `SETUP` (72 h), `RESET` (self-service, 1 h), `RECOVERY` (core FULL reset, 72 h). Completing a reset ends every session and emits `CREDENTIAL_RESET` with `cooling_off_until`. `reset-link` scope `MFA` now removes the TOTP factor and revokes sessions; wider recovery policy remains in the 2FA plan. |
| 1.4a CommsClient | Three implementations: the comms plane (`IDP_COMMS_URL`, mTLS, capabilities cached 60 s); the sandbox (`sim_outbox`, dev/sandbox only); and *none* (uat/production before comms is configured: nothing is live, so OTP and Forgot password are hidden). The IdP addresses `user:<uid>` only |
| 1.5 events | Written to an `outbound` table in the same transaction as the change; a worker POSTs each as a JWT signed by the IdP (`typ auth-event+jwt`, `aud mipay-core`, `jti` = event id for de-duplication) to `IDP_CORE_EVENTS_URL`, retrying with back-off (2 s doubling to 10 min). **Until the core's endpoint exists (P3.6) the URL is unset and events wait in the queue**; they are delivered once it is set. New event type `ASSISTED_RECOVERY_REQUESTED` ("Can't get in?") added to the contract (§1.2) |
| 1.6 back-channel logout | disable, revoke-sessions, FULL reset, password reset and delete end IdP sessions, revoke refresh tokens, make introspection refuse tokens issued before the change (`tokens_valid_after`), and queue an OIDC back-channel logout token for every client with a `backchannel_logout_uri` (the BFF: `https://gateway:8443/auth/backchannel-logout`). The gateway verifies signature, iss, aud, freshness, the event claim and jti single use, then revokes the subject's sessions |
| 1.7 claims | `sub` (account subject), `uid`, `acr` (`aal1`), `amr` (`pwd` or `sms`), `auth_time`, `sid` in access and ID tokens; introspection returns them too. Refresh re-checks the account (ACTIVE, not revoked since issue). Refresh tokens are now stored only as a SHA-256 hash |
| 1.8 phone OTP | Offered only while comms says SMS is live (or the dev OTP is set in dev/sandbox), and **only for accounts the core gave the `MERCHANT_OTP` profile**. 6 digits, hashed, 5 min, single use, 5 wrong tries kill the challenge (and count towards the account lockout), resend at most once per 60 s and 5 per hour. Unknown phones get the same page. `amr=sms`, `acr=aal1` |
| 1.9 pages | Sign-in, code, consent, set/reset password, Forgot password? (shown only while SMS or email is live), Can't get in? Styled for light and dark; `X-Frame-Options: DENY`, no-store, strict CSP. A first-party client with `require_consent: false` (the BFF) skips the consent page |
| Found while testing | **Fixed a token-binding bug:** behind the gateway the IdP took the gateway's own mesh certificate as the client certificate, so a token request with no DPoP proof and no client certificate was "bound" to the gateway instead of being refused. Only the certificate thumbprint the gateway forwards now counts (regression test added) |

**Pulled forward from P4 (needed to prove P1 end to end):** the gateway uses `uid` as the RBAC subject for user tokens and adds `uid` to the signed context (`X-SME-Context`); the BFF session stores `uid`; the BFF's token exchange signs its DPoP proof for the issuer's token URL (it used the mesh URL and could never complete a login), sends `client_id` on revoke, and guards its login-transaction map. `auth: session` routes, CSRF and portals remain P4/P5.

**Tests:** Go integration tests against Postgres (`TEST_IDP_DSN`, 12 tests) and the e2e suite (S2, S16, S21–S25: enrolment only by the core, set-up link through comms, policy, single use, disable kills login and refresh, lockout and unlock, back-channel logout ends the BFF session within 10 s, merchant OTP through comms, Forgot password through comms).

### P2. RBAC rebuild (4–5 days)

| # | Task | Where |
|---|---|---|
| 2.0 | **Delete the existing RBAC model and data** (§4) | see §4 |
| 2.1 | Grid storage: `resources` (rows) × 10 fixed verbs, with each role's permissions on a row stored as a bitmask | `rbac-service/core/SchemaAndSeed`, `RbacStore` |
| 2.2 | Assignment and grant scope `own \| subtree \| all`, using `org_closure`. Subject = core `user_id` | `SchemaAndSeed`, `RbacStore` Check query |
| 2.3 | `role_inheritance`; static SoD sets (blocked at assignment time) and dynamic SoD sets (blocked at session activation) | `RbacStore`, `AssignmentsController` |
| 2.4 | `Check` returns a `decision_id`; `CheckBatch` for list screens; `ListHolders(role, tenant)` | `rbac.proto` (Go and Java copies) |
| 2.5 | Seed the version-1 MiPay roles (D9), each with grid rows taken from the 201-cell catalogue:<br>• **org roles:** `kyc_applicant` (KYC application cells only, on the applicant's own PENDING org; D3), `org_viewer`, `org_staff`, `org_admin`;<br>• **platform roles:** `platform_ops_reviewer`, `platform_operations`, `platform_user_admin`, `platform_engineer`, `platform_compliance_officer`, `platform_mlro`, `platform_senior_approver`, `platform_auditor`, `platform_break_glass`;<br>• **lender and sandbox:** `lender_officer`, `sandbox_tester`.<br>**No `platform_admin` superuser.** The static SoD sets of `fapi2-design.md` §9.4 are seeded with them. Version 1 grants exactly today's access, so shadow mode (P6) can prove the two agree | `config/rbac/grid.yaml` |
| 2.5a | **Compliance sign-off pack:** generate one page per role from the seed (its ticked cells, the screens and endpoints each cell covers, its SoD sets) for compliance to approve before P6 enforce | new `rbac-service` export, `docs/` |
| 2.6 | Onboarding rights as delegated grants: DIRECT becomes a grant on the org; CEILING becomes a subtree grant with `can_delegate` and a role ceiling | `GrantsController` |
| 2.6a | Tenant holds: the org-tree sync also sends each org's operational state (lifecycle, active holds, KYC lapse). `Check` denies in a held tenant or any tenant below it, **except** for assignments held by platform users (U6), so the gateway refuses suspended orgs before routing | `OrgTreeSyncController`, `RbacStore`; backend `OrgTreeSyncService` |
| 2.7 | Minimum-holders rule (at least one active `org_admin` per org tenant); review API | `AssignmentsController`, new `ReviewsController` |
| 2.7a | **Four-eyes approval by the risk of the role** (D10):<br>• an assignment request is `PENDING_APPROVAL` until a different person approves it (never the requester, never the grantee);<br>• requests expire after 72 h;<br>• every approval is audited with its decision id;<br>• the approver rules follow the D10 table, including the fallback chain for `org_admin` (a second admin of the same org, then the parent org's admin, then `platform_user_admin`) | `AssignmentsController`, new `ApprovalsController`, `RbacStore` |

**Tests:** RBAC unit tests per grid cell; SoD conflicts refused; subtree assignment covers descendant orgs; removing the last `org_admin` refused; a revocation reaches the gateway within 10 s.
**Done when:** the seeded roles answer `Check` correctly for the worked example in G.7.

**Status: ✅ done (2026-11-12).** As built (`trustplane/java/rbac-service`, `trustplane/config/rbac/grid.yaml`), with the decisions taken while building it:

| Item | As built |
|---|---|
| 2.0 delete | At boot, if the demo schema is present (`suppliers`, `role_permissions`, or `assignments.supplier_id`), RBAC drops it in one step and creates the grid schema. The demo `seed.yaml` is gone; the grid seed is **`config/rbac/grid.yaml`** (a new name, so an older RBAC that still mounts the config directory finds no seed and keeps running on its own database). `CrudResource`/`PermissionMatrix` removed from `shared-lib`; endpoints bind one cell with `@Permission("row:verb")` and startup fails if any lacks it |
| 2.1 grid | `rbac_resources` (rows; `declared` cells from the seed, `used` cells from registered endpoints), `rbac_role_grid` (bitmask per role and row), `rbac_cell_endpoints`. A role may also tick a verb on *all* rows (`grid_all_rows`, used for read-only auditors). Rows can be limited to org types (`merchant.*` only in MERCHANT orgs, etc.), so a role assigned on a supplier never acts on merchant rows |
| 2.2 scope | `own`, `subtree` (org closure), `all`. Tenant `""` in `Check` means "in any org" (the gateway uses it for routes without an org in the path; the RBAC service re-checks the real org) |
| 2.3 hierarchy + SoD | `role_inheritance` with a cycle check on seed and on edit. STATIC role sets are refused at assignment (409 `SOD_CONFLICT`). DYNAMIC: a user who holds **both** sides uses neither until one is activated for the session, and never both; **while waiting, what the gated roles inherit from outside the set stays usable** (a reviewer keeps the auditor's reads). OBJECT rules are declared for the domain services and the role pack. Grid save rules: no same-row submit+authorize (exemption: `merchant.advance`, with its reason), no simulate column in production, and no platform role that both administers and authorizes **customer** rows (approving an access request is `rbac.assignment:authorize`, which is access administration and is not counted) |
| 2.4 API | `Check` (with `decision_id`, `active_roles`, `attributes`), `CheckBatch`, `ListPermissions` (flat list + per-row masks), `ListHolders`, `ValidateActivation`, `Watch`, `Ping`. Decisions are logged asynchronously (bounded queue, 90-day retention); `GET /rbac/admin/decisions/{id}` explains one |
| 2.5 roles | The 15 roles of D9 in `grid.yaml`, version 1 reproducing today's access (the merchant/supplier/lender APIs for `org_staff`; `MoneyGuard`, `isOrgAdmin` and ADMIN-only org actions for `org_admin`; TPTS_OPS and MINDLABS_ADMIN split into platform roles; KYC platform functions into the review roles). `lender_officer` is a **platform** role (MiPay staff operating the lender side); lender orgs' own users get lender cells through `org_staff`/`org_admin`. `platform_user_admin` holds `rbac.role` create/modify/delete; seeded roles change only through a reviewed `grid.yaml` change (re-applied at boot when its content changes; API edits survive otherwise) |
| 2.5a pack | `GET /rbac/admin/export/role-pack` (and `make role-pack`). The pack generated from the code's real registration is committed as [`docs/rbac/role-pack-v1.md`](../rbac/role-pack-v1.md): every ticked cell maps to endpoints, none orphaned |
| 2.6 grants | Entitlement cells (`org.onboarding:create`, attribute `child_type`) also need an active grant on the org or above. Root grants by a platform_user_admin or the core; delegation only to an org below, only narrower values, never longer than the parent; revocation cascades |
| 2.6a holds | The core's `OrgTreeSyncService` now sends every org's type, parent, lifecycle and own active holds (not just ids). It syncs at boot, right after a suspension or lift commits, and whenever a 15-second fingerprint of orgs, closure and holds changes. `Check` refuses in a held or closed org and everything below it, except platform assignments (scope `all` or on the PLATFORM org; U6). E2E fixture orgs (`source=fixture`) are never touched by the sync |
| 2.7 holders + reviews | Revoking the last active `org_admin` of an org is refused (409 `LAST_ADMIN`), and an overdue access review never expires it either. Reviews: opened by a platform_user_admin for one org or for platform roles; decided by that org's admins (or platform user admin/compliance); nobody reviews their own access; unconfirmed items expire after 14 days |
| 2.7a four-eyes | Exactly the D10 table: `org_viewer`/`org_staff`/`kyc_applicant` active at once; `org_admin` waits for a second org_admin of the org, else the parent org's admin, else a platform_user_admin; platform roles for a second platform_user_admin; compliance roles for the MLRO (the MLRO itself for a senior approver); break-glass is self-requested, approved by a platform_user_admin, at most 8 h, audited as `break_glass_activated` and logged as an alert. Never the requester, never the grantee; requests expire after 72 h; approvals carry a decision id |
| Mesh API for P3 | `/rbac/internal/assignments` (system writes with a named source; SoD still applies), `…/{id}/revoke`, `/rbac/internal/tenants/{id}/revoke-all` (3.3c), `/rbac/internal/grants` (onboarding rights), and `GET /rbac/internal/assignments?subject=` (reconcile, 2FA profile). Only the core's certificate |

**Tests:** 14 Java integration tests against Postgres (`TEST_RBAC_DSN`) covering the G.7 worked example, holds, entitlements, SoD, four-eyes, minimum holders, grid rules, break-glass, reviews, registry and role pack; e2e S3–S6, S17–S20. With the backend's real catalogue: 125 cells over 204 endpoints registered, none rejected; a hold written in the core reached RBAC in about 6 s.

**Not in P2 (unchanged, recorded so nobody assumes otherwise):** the admin portal's permission-matrix screen and the backend's `RbacClientService` still call the old RBAC endpoints without mTLS or a gateway context, which already failed before this change. They are rebuilt as the Roles & access screen in P5.5. Grid-change four-eyes for privileged roles (G.8) comes with that screen.

### P3. Core: identity links, resolver and the auth contract (5–6 days)

| # | Task | Where |
|---|---|---|
| 3.1 | `AuthPluginContracts` (§1.2), `AuthProviderRegistry`, and the `TrustplaneAuthProvider` implementation (mTLS client over `MeshTls`) | new `security/auth/` |
| 3.2 | Migration `V15`: `identity_links` (§1.3), `auth_outbox` (provider commands with retry), `auth_events_seen` (event de-duplication); add `ARCHIVED` to `UserStatus` | `db/migration` |
| 3.3 | **`UserLifecycleService`:** the only place that creates users, changes their status or changes login fields. It writes the core first, then queues provider commands in the outbox:<br>• `enrol` · `suspend` · `reinstate` · `archive` · `deactivate` · `changeLoginName` · `resetCredential`<br>• RBAC assignments when `app.rbac.sync=on` | new `service/UserLifecycleService` |
| 3.3a | **Org binding (U1, U2, U5):**<br>• `UserLifecycleService.createInOrg(orgId, …)` is the only constructor; it refuses a missing org, and refuses an org that is not ACTIVE except for the KYC applicant admin of a PENDING org;<br>• `User.orgId` has no setter after creation (no moves between orgs);<br>• the lazy binding in `OrgProvisioningService.ensureEntityOrg` (`u.getOrgId() == null`) is replaced by the backfill in 3.10a | `UserLifecycleService`, `User`, `OrgProvisioningService` |
| 3.3b | **`AuthSyncService.reconcile(userIds)`** (§1.5). Called from:<br>• `OrgSuspensionService.suspend` / `lift` (replacing the bulk `sessionRepo.deleteAll` at `suspend`);<br>• `OrgLifecycleService` transitions;<br>• `KycGoLiveService.goLive` / `terminate`;<br>• the KYC review lapse hold;<br>• every `UserLifecycleService` change;<br>• and a nightly job | new `security/auth/AuthSyncService` |
| 3.3c | **KYC termination archives everyone:** `KycGoLiveService.terminate` → `OrgGoLiveBridge` archives **all** users of the org and its subtree (today only `PENDING` ones, `archivePendingUsers`), revokes their links and removes their RBAC assignments. Org close/archive does the same | `KycGoLiveService:154`, `OrgGoLiveBridge:45` |
| 3.3d | **KYC lapse is a hold:** when periodic review lapses, the core places a system hold of kind `KYC_LAPSED` on the org (the same `suspensions` table, `suspended_by = system:kyc`), so U3 and the reconcile cover it with no extra path; a completed review lifts it | `OrgSuspensionService`, `kyc-review` plugin |
| 3.3e | **Contacts and 2FA profile from the core:**<br>• `UserLifecycleService` writes each user's email and phone to the comms plane (`PUT /comms/v1/subjects/user:<uid>/contacts`; the sandbox `CommsClient` until it is live);<br>• the reconcile also sets each account's 2FA profile with `setMfaProfile`, from RBAC roles and `organizations.mfa_required` (`2fa-design.md` §5.1) | `UserLifecycleService`, `AuthSyncService` |
| 3.4 | Route every user write through it: `OrgOnboardingService`, `OnboardingService`, `OrgProvisioningService`, `OrgGoLiveBridge`, `OrgSuspensionService`, `OrgRightsService`, `OrgController` `/me/users` and `/users/{id}/deactivate` | those services |
| 3.5 | **KYC go-live enrols the admin:** `KycGoLiveService:89` calls `userLifecycle.enrol(admin)` instead of `activation.issue(admin)`. `/api/orgs/users/{id}/resend-activation` calls `resetCredential` on the PENDING link | `KycGoLiveService`, `ActivationController` |
| 3.6 | **Event endpoint** `POST /internal/auth/events` (mTLS, `@PublicEndpoint` with a mesh-only guard):<br>• `CREDENTIAL_SET`: link ACTIVE; user PENDING → ACTIVE with `activated_at`;<br>• `LOCKED` / `UNLOCKED`: recorded in audit and shown in admin;<br>• `CREDENTIAL_RESET`: notify the user | new `web/AuthEventController` |
| 3.7 | **`IdentityResolver`:** `(iss, sub, uid)` → link → user. Refuse when the link is unknown or not ACTIVE, `link.user_id ≠ uid`, the user is not ACTIVE, or the org is not operational. Cache for 5 s, evicted on any lifecycle change | new `security/auth/IdentityResolver` |
| 3.8 | **`GatewayIdentityFilter`**, adapted from shared-lib `GatewayContextFilter`: verify the `X-SME-Context` HMAC and timestamp, resolve through `IdentityResolver`, and build `Current.Principal`. In `dual`, run it first and fall back to `JwtAuthFilter` if the header is absent | `security/`, `SecurityConfig` |
| 3.9 | Admin "access" screen (`AdminController:309-316`): list a user's links and sessions; "revoke" calls `revokeSessions`; "disable credential" calls `disable` on one link without suspending the user | `AdminController`, `AdminService` |
| 3.10 | **Backfill `IdentityBackfill`** (admin-only, safe to re-run). For every user:<br>• enrol at the IdP with `uid=user.id`, `login_name=username`, `phone`;<br>• copy the bcrypt hash with the `{bcrypt}` prefix stripped, so the account is ACTIVE straight away (users with `{noop}` passwords get a reset link instead);<br>• write the `identity_links` row.<br>PENDING users get a new set-up link | new `service/migration/` |
| 3.10a | **Org-binding backfill**, run before 3.10:<br>• every user with `org_id IS NULL` is bound to its entity's org (merchant / supplier / lender ownership, as `ensureEntityOrg` does), or to `PLATFORM` if it has a platform role;<br>• users that match nothing are listed for a manual decision, never guessed.<br>Then migration `V15b` makes `users.org_id NOT NULL` with an FK to `organizations` | new `service/migration/`, `db/migration` |
| 3.11 | **Backfill `RbacBackfill`** (safe to re-run):<br>• each `org_role` becomes an `org_admin` / `org_staff` assignment;<br>• each `user_platform_functions` row and the `TPTS_OPS` / `MINDLABS_ADMIN` roles become platform role assignments;<br>• each `org_rights` row becomes a grant;<br>• then report static SoD conflicts, and any user whose `role` disagrees with their org's type | new `service/migration/` |
| 3.12 | Audit: replace `caller.role()` in the `auditService.record(...)` calls (about 40) with `actorKind` (from the org type), plus `link_id`, `acr` and the RBAC `decision_id` | `AuditService` and its callers |

**Tests:**
- resolver: unknown link, disabled link, `uid` mismatch, suspended user, suspended org;
- lifecycle: suspend → provider `disable` queued → retried on failure → the user is refused immediately anyway;
- events: duplicate ignored, bad signature refused;
- KYC go-live: admin PENDING → enrol → `CREDENTIAL_SET` → ACTIVE;
- org cascade:
  - suspending a parent org refuses every user in its subtree on the next request, and disables their IdP accounts;
  - lifting it restores them, except a user suspended individually, and a child org under its own hold;
  - KYC reject archives the org's ACTIVE users too, not only PENDING ones;
  - KYC lapse blocks the org like a suspension;
  - a platform reviewer is unaffected by suspending the org they review;
  - creating a user with no org, or in a non-ACTIVE org, is refused;
- the nightly reconcile repairs an IdP account left enabled for a blocked user;
- both backfills, run twice, change nothing the second time.

**Done when:** in `dual` mode, all 20 UAT suites pass with legacy tokens **and** with a test gateway context; both backfills run clean on a copy of production data.

### P4. Gateway: sessions on portal routes (3–4 days)

| # | Task | Where |
|---|---|---|
| 4.1 | `auth: session` route policy. With no valid session: page navigation → 302 `/auth/login?return_to=`; API call → `401 {"error":"login_required"}` | `gateway/config.go:81,152`, `authn.go` |
| 4.2 | CSRF: POST, PUT and DELETE need `X-CSRF` equal to `session.csrf` | `authn.go` |
| 4.3 | Refresh the access token when it is close to expiry, using the session's DPoP key; on refresh failure, kill the session | `bff.go`, `session.go` |
| 4.4 | Context v2: `iss`, `sub`, **`uid`**, `sid`, `acr`, `amr`, `auth_time`, `client_id`, in `ctxauth.Context` and the Java `GatewayContext`; strip any incoming `X-SME-*` headers | `internal/ctxauth`, `shared-lib` |
| 4.5 | The gateway's RBAC check uses `uid` as the subject, and refuses a token without `uid` on MiPay routes | `gateway/rbac.go` |
| 4.6 | Per-host `bff` flag. Portal hosts route `/api/**` with `auth: session` and stop using `forward_authorization`; static files stay `auth: none` | `routes.yaml`, `deploy/afriqpay/trustplane.override.yml` |
| 4.7 | Register the portal hosts' redirect URIs on the `gateway-bff` client; nginx sends all portal traffic to the gateway | `config/idp/clients.yaml`, `deploy/afriqpay/nginx-mipay-sme.conf` |

**Tests:** fapi-client e2e for:
- a page with no session redirects to login;
- an API call with no session gets 401;
- a missing or wrong CSRF token gets 403;
- an idle session expires;
- back-channel logout kills the session;
- incoming `X-SME-Context` is stripped;
- a token without `uid` is refused.

**Done when:** `admin.` runs on the BFF on a staging host, with the backend in `dual`.

### P5. Portals (2–3 days)

| # | Task | Where |
|---|---|---|
| 5.1 | Shared `SessionService`. `GET /auth/session` is answered by the gateway with the core's `/api/me` merged in: `{uid, name, orgId, orgType, permissions:[resource:verb…], csrf}`. Sign in = `location.href = /auth/login?return_to=…`; sign out = `POST /auth/logout` | `frontend/projects/shared/`, `web/MeController` |
| 5.2 | Interceptor: drop the `Authorization` header; send `X-CSRF` on POST, PUT and DELETE; on `401 login_required`, go to login; on `step_up_required`, show the confirm dialog (stub until 2FA) | `admin-portal/core/api.ts:192`, `org-portal/core/api.ts:183`, `src/app/core/http.ts` |
| 5.3 | Remove the `localStorage` tokens; login pages become a single "Sign in" button | `*/core/auth.ts`, `*/features/login/login.ts`, `src/app/features/auth/login.ts` |
| 5.4 | Remove the org-portal activate screen (the set-up link goes to the IdP) | `org-portal/features/activate` |
| 5.5 | Route guards check permissions and `orgType`, not `role` (§6.3). The Users screens show each user's status, links and RBAC roles; the role pickers write RBAC roles | `src/app/app.routes.ts`, `core/guards.ts`, `org-portal/features/users`, `admin-portal/features/{users,permission-matrix,permissions,shell}` |

**Release:** one portal at a time (admin → org portals → merchant), switching that host's gateway `bff` flag on with it.
**Tests:** headless e2e per portal: sign in, CSRF-protected write, sign out, session expiry.

### P6. RBAC decides: shadow, then enforce (5–7 days, plus 1 week of shadow)

| # | Task | Where |
|---|---|---|
| 6.1 | `RbacAuthz.check` calls RBAC `Check(user_id, tenant, resource:verb)` over gRPC, with a 5 s cache invalidated by `Watch`. It works out the tenant for each `TenantSource`: `PATH` (SpEL value), `SELF` (the caller's org), `LOOKUP` (a resolver registry keyed by `@Perm.lookup`), `NONE` (platform root) | `security/rbac/RbacAuthz`, new `RbacDecisionClient`, `LookupResolvers` |
| 6.2 | **Shadow mode:** compute both the RBAC answer and the legacy answer, log every mismatch (`[rbac-mismatch]` with user, cell, tenant, both answers) and enforce the legacy answer. Allow `enforce` mode at startup | `RbacAuthz` |
| 6.3 | Replace the hard-coded checks with `@Perm` cells or service-level `rbac.check(...)` calls:<br>• `SecurityConfig` `hasRole` matchers;<br>• `MoneyGuard.requireAdmin`;<br>• `isOrgAdmin` in `KycApplicationService:510,517` and `OrgController`;<br>• `OrgCallerService.platformStaff` (7 files);<br>• `UserPlatformFunction` in `KycWorkflowEngine` (the KYC `ActorRule(relation, orgRole, platformFunction)` becomes `ActorRule(relation, permission)`);<br>• the `OrgRight` checks in `OrgTreeService`, `OrgTypeRules`, `OrgOnboardingService`, `KycGoLiveService`;<br>• role-name strings in `AdvanceService`, `B2BPaymentService`, `OpsService`, `OpsController`, `ComplianceService`, `ReconciliationService`, `ReportingService`, `LenderController`, `SupplierController` | listed files |
| 6.4 | Assurance policy:<br>• cells carry `@Perm(..., stepUp = PAYOUT, requiresKyc = true)`, using the step-up rule keys of `2fa-design.md` §8;<br>• the required strength is **not hard-coded**: it is the IdP's published `step_up_acr` (`aal1-fresh` while `mfa.mode` is `off` or `shadow`, `aal2` in `enforce`), so switching 2FA on needs no backend or portal change;<br>• checked against `Principal.acr` / `auth_time` / the RAR binding, and against the user's KYC assurance;<br>• a failure returns `401 step_up_required` or `403 kyc_required`;<br>• the core's cooling-off guard (`recovered_until`, `2fa-design.md` §9.5) sits on the same cells | `Perm`, `RbacAuthz` |
| 6.5 | Keep object-level rules in the backend (maker ≠ checker on one application, ownership of a record), and record the RBAC `decision_id` next to them in audit | `KycWorkflowEngine` |
| 6.6 | The gateway enforces the same cell before routing, compiled from the published manifest (Appendix F) | `gateway/rbac.go` |
| 6.7 | Authorization matrix test in CI: every role × every endpoint × own / other / child org → expected allow or deny | `backend/src/test/.../rbac/` |

**Gate to enforce:** zero unexplained mismatches over 7 days of real traffic; matrix test green; compliance sign-off of the role pack (P2.5a, D9).
**Rollback:** `app.rbac.mode=shadow`.

### P7. Cut-over and cleanup (2 days)

Do this only after P4–P6 have run cleanly for at least a week.

| # | Task |
|---|---|
| 7.1 | Set `app.identity.mode=gateway`; the gateway refuses legacy bearer tokens on portal hosts |
| 7.2 | Delete `AuthController`, `AuthService`, `JwtService`, `JwtAuthFilter`, the `PasswordEncoder` bean, `ActivationService` / `ActivationController` / the activation-token entity, `DeviceSession` / `DeviceSessionRepository` and their 10 callers, `Current.requireRole`, `Current.isOrgAdmin`, `MoneyGuard`, `UserPlatformFunction`, `UsernamePolicy` (moves to the IdP's login-name rules, with the core showing the IdP's error), and the `/api/auth/**` permits |
| 7.3 | Migration `V16`: back up, then drop `users.password_hash`, `users.role`, `users.org_role`, `devices_sessions`, `activation_tokens`, `user_platform_functions` (`org_rights` is kept read-only for one release, then dropped). Delete the enums `UserRole`, `OrgMemberRole`, `OrgRightKind` (§6.1) |
| 7.4 | `Current.runAs` and `SimulationService` impersonate by building a test gateway context and a test link |
| 7.5 | `DataSeeder` (dev only) creates demo users through `UserLifecycleService` and auto-sets their credentials through the provider's dev hook; remove the `{noop}` passwords |
| 7.6 | Update the 11 test files that log in through `/api/auth/login` to use a test gateway-context helper |
| 7.7 | Update the `PRD.md` / `UAT-BACKEND-TEST-PACK.md` sections that describe login and accounts (`fapi2-design.md` was already revised to this model) |
| 7.8 | At go-live of each applicant org: replace the applicant's `kyc_applicant` assignment with `org_admin` (part of `KycGoLiveService.goLive`) |

**Rollback:** redeploy the previous backend and gateway images; restore `password_hash` from the backup.

---

## 6. What happens to the enums and identity fields

Rule: **who is legitimate** stays in the core; **who is at the keyboard** is in the auth plugin; **what they may do** is in RBAC.

### 6.1 Enums

| Enum (in `domain/Enums.java`) | Fate | Replaced by | When |
|---|---|---|---|
| `UserRole { MERCHANT, SUPPLIER, LENDER, MANUFACTURER, DISTRIBUTOR, TPTS_OPS, MINDLABS_ADMIN }` | **Deleted** | It mixes two things:<br>• the five business values say *what kind of org the user belongs to*: that is `Organization.orgType` (kept), read through `users.org_id`;<br>• `TPTS_OPS` and `MINDLABS_ADMIN` say *what a platform user may do*: they become members of the `PLATFORM` org holding RBAC roles. `TPTS_OPS` becomes `platform_ops_reviewer` + `platform_operations`; `MINDLABS_ADMIN` becomes `platform_user_admin` + `platform_engineer` (no review roles; D9) | Writes stop in P3; column dropped in P7 |
| `UserStatus { PENDING, ACTIVE, SUSPENDED }` | **Kept; the core is the authority.** `ARCHIVED` added (P3) | It holds the user's *own* state only; the org's state is never copied onto it (§1.5 U4). Whether a user can act is `status = ACTIVE` **and** the org operational (U3). Changed only by `UserLifecycleService`. PENDING → ACTIVE when the auth plugin reports `CREDENTIAL_SET`. SUSPENDED / ARCHIVED are core decisions that *command* the plugin to disable the account. Credential-level state (enrolling, locked, disabled) lives on `identity_links.status` and the IdP account, not on the user | P3 |
| `OrgMemberRole { ADMIN, STAFF }` | **Deleted** | RBAC roles `org_admin` / `org_staff` with `tenant = org id`. "Keep at least one active admin" (`OrgUserService.countActiveAdmins`) becomes the RBAC minimum-holders rule (P2.7). KYC go-live finds the admin to enrol through `ListHolders(org_admin, org)` | Backfill P3.11; column dropped P7 |
| `OrgRightKind { DIRECT, CEILING }` and the `org_rights` table | **Deleted** | RBAC delegated grants: DIRECT becomes a grant to the org itself; CEILING becomes a subtree grant with `can_delegate` and the role ceiling. `OrgRightsService` becomes a thin client of the RBAC grant API | Backfill P3.11; table read-only for one release, then dropped |
| `TenantType`, `TenantStatus` | **Deleted** | Nothing (unused) | P0 |
| Platform function strings in `user_platform_functions` (`OPS_REVIEWER`, `COMPLIANCE_OFFICER`, `MLRO`, `SENIOR_APPROVER`) | **Deleted** (table and `UserPlatformFunction` entity) | RBAC platform roles `platform_ops_reviewer`, `platform_compliance_officer`, `platform_mlro`, `platform_senior_approver` (D9). Holding both `platform_ops_reviewer` and `platform_senior_approver` is a static SoD conflict (§9.4); using `platform_ops_reviewer` and `platform_compliance_officer` in one session is a dynamic SoD set; maker ≠ checker on one application stays an object-level check in `KycWorkflowEngine` | Backfill P3.11; dropped P7 |
| New: `LinkStatus { PENDING, ACTIVE, DISABLED, REVOKED }` | **Added** | Status of one credential link | P3 |
| `OrgType`, `OrgStatus`, `OnboardingKind`, `OnboardingStatus`, `OnboardingDecision`, KYC, ledger, payment, B2B and credit enums | **Kept** | Business state. `OnboardingKind.ADMIN_RECOVERY` stays a reviewed request; on approval the replacement admin is created with `createInOrg` and enrolled | — |

### 6.2 Fields on `User` (`domain/core/User.java`)

| Field | Fate |
|---|---|
| `id` | Kept: **the core user id**. It is the `uid` claim and the RBAC subject. The IdP's `sub` is a different, opaque value stored in `identity_links.subject` |
| `passwordHash` | Copied to the IdP account (P3.10), then dropped (P7) |
| `role` | Dropped (P7); see `UserRole` |
| `orgRole` | Dropped (P7); see `OrgMemberRole` |
| `status` | Kept, authoritative (see `UserStatus`) |
| `username`, `phone`, `email` | **Kept and owned by the core.** `username` and `phone` are pushed to the IdP as login name and OTP phone with `updateLoginName`; `email` stays core-only (the core sends all messages) |
| `name` | Kept; pushed to the IdP as `display_name` |
| `orgId` | Kept, **`NOT NULL` and fixed at creation** (§1.5 U1, U5) |
| `createdBy`, `createdAt` | Kept (business data) |
| `activatedAt` | Kept; set on the first `CREDENTIAL_SET` event |
| New: `kycAssurance` | Optional; from the KYC outcome for the people KYC verified (e.g. the org admin), used by assurance policy (P6.4) |

### 6.3 Other identity pieces

| Piece | Fate |
|---|---|
| `Current.Principal(userId, role, name, phone, merchantId, supplierId, lenderId, orgId, orgType, orgRole)` | Becomes `Principal(userId, orgId, orgType, linkId, sid, acr, amr)`, built by `GatewayIdentityFilter`. `merchantId` / `supplierId` / `lenderId` are resolved from the org (`Organization.entityId` for its type) by `Current.merchantId()` etc. `isOrgAdmin()` and `requireRole()` are deleted |
| Role-name strings in services (`"TPTS_OPS"`, `"SUPPLIER"` …) | Authorization uses become `@Perm` cells or `rbac.check(...)` (P6.3). Audit uses become `actorKind` + `decision_id` (P3.12). Uses that pick a ledger owner by type read `orgType` |
| `SecurityConfig` path gates `hasRole(...)` | Deleted in P6; every handler already has `@Perm` |
| JWT claims `role`, `orgRole`, `merchantId`, `supplierId`, `lenderId`, `orgId`, `orgType`, `name`, `phone` | Gone with `JwtService` (P7). The gateway context carries `iss`, `sub`, `uid`, `sid`, `acr`, `amr`, `auth_time`, `client_id` |
| `DeviceSession` / `devices_sessions` | Deleted (P7). Sessions live in the gateway (`gw_sessions`) and the IdP; revoking goes through `AuthProvider.revokeSessions` |
| `ActivationToken` / `activation_tokens` | Deleted (P7). Set-up links are issued by the IdP and delivered by the core |
| Frontend role checks (`src/app/app.routes.ts` 25 routes, `core/guards.ts`, admin `shell`/`users`, org `network`, `shared/kyc/onboard-child.ts`) | Check `permissions` and `orgType` from `/auth/session`. Hiding a button is only cosmetic: the gateway and backend still enforce |

---

## 7. Later: other credential providers

The contract lets these plug in without touching onboarding, KYC or RBAC:

| Provider | Link | Notes |
|---|---|---|
| Partner API clients | `provider=partner-client`, `subject=client_id`, user = a *service user* of the onboarded partner org | Onboarding a partner creates the service user; registering its JWKS at the IdP is `enrol` for a client |
| Staff SSO (e.g. Google Workspace / Entra) | `provider=staff-sso` | Only for PLATFORM-org users; linking needs a platform admin |
| Bank / national eID | `provider=<scheme>` | Can raise the user's KYC assurance as well as authenticate |
| Mobile app device key | `provider=trustplane`, factor on the same account | Arrives with the 2FA work (`2fa-design.md`) |

---

## 8. Timeline (one developer; two can overlap P1 and P2)

| Week | Work |
|---|---|
| 1 | P0, P1 (IdP as credential plugin) |
| 2 | P2 (RBAC rebuild), P3 begins (contract, links, lifecycle) |
| 3 | P3 (resolver, events, backfills), P4 |
| 4 | P4 on staging, P5 admin portal, P6.1–6.2 shadow mode on |
| 5 | P5 other portals; shadow week; P6.3 replacements |
| 6 | Enforce; P7 cut-over |

About 6–7 weeks to cut-over. 2FA and recovery (`2fa-design.md`) and further credential providers (§7) follow as separate tracks.

---

## 9. Risks

| Risk | Mitigation |
|---|---|
| The IdP or RBAC is down, so nobody can log in or anything is denied | Gateway fails closed with 503 (by design); health alerts; RBAC decision cache 5 s |
| A core change (suspend) does not reach the IdP | Outbox with retries; the resolver refuses the user on the very next request regardless, so the IdP only controls *new* logins; a nightly reconcile compares link states with IdP accounts and alerts |
| The IdP's `CREDENTIAL_SET` event is lost, so the user stays PENDING | Events retried with ids; the core also checks the IdP (`GET /accounts/{subject}`) when a PENDING user's login is refused; reconcile job |
| A forged or stale `uid` | `uid` is signed by the IdP and verified by the gateway; the core still requires `link.user_id == uid` |
| Password hash format | Go bcrypt reads `$2a$/$2b$`; strip `{bcrypt}`; users with `{noop}` passwords get a reset link; verify on a copy before P3 |
| The legacy and RBAC answers disagree | Shadow week with logged mismatches; enforce only at zero unexplained mismatches |
| Existing SoD conflicts (e.g. `ops-admin-mindlabs` holding review roles) | `RbacBackfill` reports conflicts; resolve before enforce |
| The old 12 h tokens outlive the cut-over | At cut-over, delete all `devices_sessions` rows; `dual` mode has already moved most users |

---

## 10. Decisions

### 10.1 Agreed (2026-11-11)

| # | Decision |
|---|---|
| D1 | Login is a credential plugin; the core owns users; tokens carry `uid` (option (a)); the core re-checks the link on every request (§1) |
| D2 | Every user belongs to exactly one org; org and KYC state govern the user; user management is always org-scoped (§1.5, U1–U7) |
| D3 | The KYC applicant admin of a PENDING org is enrolled when the application starts and may log in before go-live, **limited to the KYC application screens** (a PENDING-org restriction checked by `IdentityResolver`, plus RBAC role `kyc_applicant` on that org only) |
| D4 | Users with no org that the backfill (3.10a) cannot place are **listed for a manual decision** (bind or archive), never guessed; `NOT NULL` is applied only once the list is empty |
| D5 | Users whose `role` disagrees with their org's type are reported by `RbacBackfill` and resolved by hand before `UserRole` is dropped |
| D6 | One `trustplane` (password) link per user; several links only across different providers |
| D7 | Starting cells that need KYC assurance and step-up (P6.4): payouts, bank-account changes, KYC decisions, role assignments. The step-up is AAL2 once `mfa.mode=enforce`, and a fresh password until then (`2fa-design.md` §4.1) |
| D8 | **Merchant phone-OTP moves into the IdP now** (P1.8). Why:<br>• keeping it in the backend would keep `JwtService`, `JwtAuthFilter` and `devices_sessions` alive after cut-over;<br>• today's OTP is not production-grade (in-memory store, no expiry, no attempt limit, SMS not wired);<br>• the merchant app is a browser app the BFF already covers, and a mobile app later uses the same IdP.<br>OTP is AAL1 because of SIM-swap risk, so money movement steps up (D7) |
| D9 | **Version-1 roles map one-to-one onto today's access**, using the `platform_*` names of `fapi2-design.md` §9.2/G.6, with no `platform_admin` superuser (P2.5). Why: shadow mode can then prove equivalence before anything is tightened, and splitting the superuser is what makes the §9.4 SoD sets enforceable. Compliance signs off a generated pack per role (P2.5a) before enforce; tightening happens afterwards through the quarterly access review |
| D10 | **Four-eyes by the risk of the role being granted** (P2.7a); the approver is always a different person from the requester and the grantee. Why: `org_admin` can move money; small orgs often have one admin, so the fallback chain avoids deadlock; compliance (not access admin) controls who may review customers. See the table below |

**D10 approval rules**

| Role being granted | Requested by | Approved by |
|---|---|---|
| `org_staff`, `org_viewer`, `kyc_applicant` | an `org_admin` of that org | no second approval (within the org's ceiling; access reviews catch drift) |
| `org_admin` | an `org_admin` of that org or its parent | a second `org_admin` of the same org; if none, an `org_admin` of the parent org; if none, a `platform_user_admin` |
| `platform_ops_reviewer`, `platform_operations`, `platform_engineer`, `platform_auditor`, `lender_officer` | a `platform_user_admin` | a **second** `platform_user_admin` |
| `platform_compliance_officer`, `platform_mlro`, `platform_senior_approver` | a `platform_user_admin` | `platform_mlro`; when granting `platform_mlro` itself, a `platform_senior_approver` |
| `platform_break_glass` (activation) | the person themselves | a `platform_user_admin`, at AAL2; valid at most 8 h; the security contact is alerted |

### 10.2 Still open

None. **Gate:** compliance must sign off the role pack (P2.5a) before `app.rbac.mode=enforce` (P6).
