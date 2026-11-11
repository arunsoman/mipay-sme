# Two-factor authentication: push with number matching, authenticator app, passkeys and step-up

Status: **draft for review** · 2026-11-11 · **revised 2026-11-11 by `fapi2-design.md`**

> **Revision: the trustplane IdP owns every login.** This design was first written for 2FA inside the
> MiPay backend's `AuthService`. Per `fapi2-design.md` (I1–I8), every login, second factor, recovery and
> account lifecycle now lives in the **trustplane IdP**, browsers reach it through the **gateway acting as a
> FAPI 2.0 BFF**, and permissions come from **trustplane RBAC**. The factors, policies, recovery rules and
> invariants below are unchanged; only *where* they run moves:
>
> | Part of this design | Now implemented in |
> |---|---|
> | Factors (§3), policy master (§4), sign-in second step (§5), approver app and push (§6), enrolment and recovery (§8), merchant OTP (§9) | **IdP**: its login and account pages at `auth.afriqpay.com` |
> | `mfaToken` / `/api/auth/mfa/*` (§5, §10) | IdP-internal login steps; the browser only ever talks to `/authorize` and the IdP's pages |
> | Step-up (§7) | **RFC 9470** step-up through the gateway (`acr_values`, `max_age`); transaction binding through **RAR** (`authorization_details`), see `fapi2-design.md` §4.3 |
> | Token claims `amr` / `acr` / `auth_time` | IdP ID and access tokens; forwarded to upstreams in the gateway's signed context |
> | "Who decides" recovery and platform functions (§8.4) | RBAC roles and permissions (`fapi2-design.md` §9): e.g. deciding an `ACCOUNT_RECOVERY` needs `org.user.recover` on the user's org |
> | Phases (§16) | run after migration step 5 of `fapi2-design.md` §8, as its step 6 |

## 1. Context and proposed decisions

**How sign-in works today:**
- **Portal users** sign in with username or phone plus a password at `POST /api/auth/login`, served by the core backend (`AuthService.loginWithPassword`). The trustplane gateway forwards `/api/auth/**` to the backend unchanged, and the backend issues its own HS256 token (12 h, `JwtService.TTL`). Each token is registered in `devices_sessions`, which already has an unused `mfa_method` column.
- **Merchants on the legacy path** sign in with phone and OTP (`/api/auth/otp`, `/api/auth/otp/verify`). The OTP is kept in an in-memory map: it has no expiry and no attempt limit, and it is lost on restart. In the sandbox it is the fixed dev code.
- **API clients** use the trustplane IdP, which implements FAPI 2.0 (OAuth with DPoP or mTLS) and is separate from the portal login. It puts `amr: ["pwd"]` in its tokens.
- **No second factor exists.** Privileged actions (KYC decisions, rights changes, user administration) need only a valid token.

**Proposed decisions.** These are drafted from the discussion and still need the product owner's confirmation; §16 lists what is still open.

| # | Decision |
|---|---|
| D1 | ~~Portal 2FA is enforced in core's `AuthService`.~~ **Revised:** 2FA is enforced in the **trustplane IdP**, the only place anyone logs in (`fapi2-design.md` I1). Portals reach it through the gateway BFF; API clients reach it directly. |
| D2 | **Factors, in order of preference:** push approval with number matching, passkey (WebAuthn), authenticator app (TOTP), SMS OTP (fallback only), recovery codes (recovery only). |
| D3 | **Mandatory for MiPay staff** (TPTS_OPS, MINDLABS_ADMIN and every holder of a platform function) **and for every org ADMIN.** Optional for other org members unless their org switches it on. Legacy OTP merchants keep phone OTP as their login factor (§9). |
| D4 | **Push is delivered through an installable web app (PWA) first**, using Web Push, with a WebAuthn passkey as the device key. A native app (Firebase / Apple push, hardware-backed key) comes later and uses the same server API (§6.6). |
| D5 | **Step-up:** sensitive actions need a second factor less than 5 minutes old. For money movement and KYC decisions, the approval is bound to the exact action (§7). |
| D6 | **Policy lives in a master table** maintained by migrations, like the KYC master: which factors each role may use, whether 2FA is required, how long a device may be remembered, and the step-up rules. The system behaves from it. |
| D7 | **Recovery covers passwords and 2FA, and never lets one lost secret unlock everything.** Forgot password → self-service by email or SMS link, but the second factor is still required. Lost 2FA → recovery code, else assisted recovery. Lost both → assisted recovery only, decided by a second person after verifying the user's identity, followed by a cooling-off period (§8). |
| D8 | **No personal data in push payloads.** A push carries only a challenge id and a fixed message; the phone fetches the details over an authenticated call. |

## 2. Building blocks

```
                   ┌──────────────── mfa master (developer-maintained) ────────────────┐
                   │ mfa_policies (role/org-role → required, methods, remember, step-up)│
                   └──────────────────────────────┬─────────────────────────────────────┘
 browser ── password ──▶ IdP login ───▶ MfaPolicyResolver ──▶ needs MFA?     (was: MiPay AuthService)
                              │                                   │ no → token (amr=pwd)
                              ▼                                   ▼ yes
                         MfaService ── challenge ──▶ ┌──────────── factor plugins ───────────┐
                              ▲                      │ PushFactor   (number match, signed)   │
                              │                      │ WebAuthnFactor (passkey assertion)    │
                     verify / poll                   │ TotpFactor    (RFC 6238)              │
                              │                      │ SmsOtpFactor  (hashed, 5 min, 5 tries)│
                              │                      │ RecoveryCodeFactor (single use)       │
                              │                      └───────────────────────────────────────┘
                              ▼
                    token (amr=pwd+push|hwk|otp|sms, acr, auth_time) + devices_sessions.mfa_method

 any API call ──▶ StepUpGuard (@RequiresStepUp) ── fresh enough + bound to this action? ──▶ handler
                                                  └─ no → 401 STEP_UP_REQUIRED + challenge
```

Each factor is a Spring bean behind `MfaFactor`, named by key in the policy table:

```java
public interface MfaFactor {
    String key();                                          // PUSH | WEBAUTHN | TOTP | SMS | RECOVERY
    boolean enrolled(User u);
    EnrollmentStart startEnrollment(User u, EnrollContext c);
    void finishEnrollment(User u, EnrollmentProof proof);  // e.g. first TOTP code, WebAuthn attestation
    ChallengeView challenge(MfaChallenge ch);              // e.g. sends the push, sends the SMS
    VerifyResult verify(MfaChallenge ch, FactorResponse r);
    Amr amr();                                             // the value written into the token
}
```

## 3. Factors

### 3.1 Push approval with number matching (preferred)

1. The browser shows a 2-digit number (10–99) in large type. The phone shows three choices: that number and two random decoys, in random order.
2. The user unlocks the approver app with fingerprint, face or PIN and taps the matching number. The phone signs `(challenge_id, chosen_number, decision, action_hash, nonce)` with its device key.
3. The server checks, in this order:
   - the signature against the registered public key;
   - that the device is active and belongs to the user;
   - that the challenge is pending and not expired;
   - that the chosen number is correct.

**What the phone shows:** the portal name, the browser and OS, an approximate location from the IP address (city level), the time, and the action for step-up ("Approve KYC for Okahandja Fresh Market", "Pay N$12,400 to Nam Wholesale CC").

**Protections against prompt flooding ("MFA fatigue"):**
- A challenge lives for 60 seconds and is single-use.
- **A wrong number** fails the challenge immediately. The browser has to start over, and the event is audited.
- At most **3 push challenges per user per 10 minutes**.
- **3 denials or wrong numbers in 30 minutes** lock push for that user for 30 minutes (other factors still work) and raise an ops alert.
- **"This wasn't me"** on the phone denies the challenge and revokes all of that user's sessions. It then requires a password reset at the next login and notifies their org's ADMINs (or MiPay ops, for staff).

**How the waiting browser learns the result:** it polls `GET /api/auth/mfa/challenges/{id}` every 2 seconds. At about 80 orgs a month this costs nothing, so there is no need for server-sent events or WebSockets yet. The page offers "Use another method" at any time.

### 3.2 Passkey / security key (WebAuthn)

- Platform authenticators (Windows Hello, Touch ID, Android) and roaming keys (YubiKey) both work, with user verification required.
- The relying-party id is the portal host (`portal.fapi.local` in the sandbox, the production host later).
- **A passkey is also the device key for PWA push** (§6.6), so enrolling a phone for push is the same ceremony as enrolling a passkey.
- The server stores the credential id, public key (COSE), signature counter, transports and attestation format. We don't require attestation (`none`), because we don't restrict hardware models.

### 3.3 Authenticator app (TOTP)

- RFC 6238: SHA-1, 6 digits, 30-second step, ±1 step of clock drift. This is compatible with Google Authenticator, Microsoft Authenticator, 1Password and Authy.
- The secret is 160 bits, shown once as a QR code (`otpauth://totp/MiPay:<username>?issuer=MiPay`), and stored encrypted with AES-GCM under `app.mfa.encryption-key`. It never shares a key with KYC.
- **Replay protection:** the last accepted time step is stored, so a code cannot be used twice.
- Enrolment is confirmed only after the user enters one valid code.

### 3.4 SMS OTP (fallback only)

- 6 digits, a 5-minute life, at most 5 attempts, then a new code is needed.
- The code is stored **hashed in the database** (`mfa_challenges`), replacing today's in-memory map.
- It is sent through an `SmsGateway` adapter that queues messages in the existing notification outbox. The sandbox adapter shows the code on screen, as today.
- **Not allowed for MiPay staff.** For org users it is allowed only when the policy permits it, because SIM swap makes it weak.

### 3.5 Recovery codes

- 10 codes of 10 characters (Crockford base32, grouped `XXXXX-XXXXX`), shown once at enrolment and stored bcrypt-hashed. Each code works once.
- Regenerating them invalidates the old set. Using one is audited and emails the user.
- A recovery code signs the user in, but **cannot satisfy a step-up** for money movement or KYC decisions.

## 4. Policy master

`mfa_policies` is created in `V12__mfa.sql`, and rows are maintained only through migrations.

| column | notes |
|---|---|
| `id`, `code` | e.g. `PLATFORM-STAFF`, `ORG-ADMIN`, `ORG-MEMBER`, `MERCHANT-OTP` |
| `match_role`, `match_org_role`, `match_platform_function` | nullable means any. Resolution: the most specific match wins, and a tie is a boot failure (as for KYC profiles) |
| `required` | `ALWAYS` \| `IF_ORG_REQUIRES` \| `NEVER` |
| `allowed_methods` (jsonb) | e.g. `["PUSH","WEBAUTHN","TOTP"]` |
| `fallback_methods` (jsonb) | e.g. `["SMS","RECOVERY"]` |
| `remember_device_days` | `0` for staff, `30` for org users |
| `step_up` (jsonb) | rule keys and their maximum ages (§7) |
| `enroll_grace_logins` | logins allowed before enrolment is forced: `0` for staff, `3` for existing org ADMINs at rollout |

**Default rows:**

| policy | required | allowed | fallback | remember | step-up |
|---|---|---|---|---|---|
| `PLATFORM-STAFF` | ALWAYS | PUSH, WEBAUTHN, TOTP | RECOVERY | 0 days | every KYC decision, rights, users, platform functions |
| `ORG-ADMIN` | ALWAYS | PUSH, WEBAUTHN, TOTP | SMS, RECOVERY | 30 days | onboarding, KYC submit, rights, users, bank accounts, payouts |
| `ORG-MEMBER` | IF_ORG_REQUIRES | PUSH, WEBAUTHN, TOTP, SMS | RECOVERY | 30 days | payouts, withdrawals |
| `MERCHANT-OTP` | (OTP is the login) | SMS | — | 30 days | withdrawals, account changes |

**Org override:** an org ADMIN can set `organizations.mfa_required = true` to require 2FA for all of its members. An org can only make the policy stricter, never looser.

**At boot,** `MfaPolicyValidator` checks that every method key resolves to a factor bean, every step-up rule key resolves to a guard rule, and policy matching is unambiguous. If anything fails, the app refuses to start.

## 5. Sign-in flow

```
POST /api/auth/login {identifier, password, deviceId?}
  ├─ password wrong ─────────────────────────────▶ 403 (unchanged)
  ├─ policy: 2FA not needed, or device remembered ─▶ 200 TokenView (amr=["pwd"] or remembered amr)
  ├─ policy: 2FA needed, user enrolled ───────────▶ 200 {mfaRequired:true, mfaToken, methods:[...],
  │                                                     preferred:"PUSH", challenge:{id, number}}
  └─ policy: 2FA needed, user not enrolled
        ├─ grace logins left ─▶ 200 TokenView + {enrollmentDue: n}
        └─ none left ─────────▶ 200 {enrollmentRequired:true, enrollToken, methods:[...]}

POST /api/auth/mfa/challenges {mfaToken, method}            → start or switch method (sends push / SMS)
GET  /api/auth/mfa/challenges/{id}  (Authorization: mfaToken) → PENDING | APPROVED | DENIED | EXPIRED
POST /api/auth/mfa/verify {mfaToken, challengeId, method, code | assertion, rememberDevice?}
                                                             → 200 TokenView (amr=["pwd","<factor>"])
```

**The `mfaToken`:**
- a short-lived JWT (5 minutes) with `typ=mfa`, the user id, the session nonce and the methods offered;
- it is accepted **only** by `/api/auth/mfa/**`, and `JwtAuthFilter` rejects `typ=mfa` everywhere else;
- at most 5 failed verifications per `mfaToken`, after which the user starts over from the password.

**Claims on the final token:**
- `amr`: RFC 8176 values, e.g. `["pwd","mfa","hwk"]` for a passkey, `["pwd","mfa","otp"]` for TOTP or SMS, `["pwd","mfa","swk"]` for push;
- `acr`: `aal1` (password only) or `aal2` (password plus a second factor);
- `auth_time` and `mfa_time`;
- `devices_sessions.mfa_method` records the factor used.

**Remember this device:**
- The browser keeps a random 256-bit `deviceId` in localStorage.
- The server stores `mfa_trusted_devices(user_id, device_id_hash, ua_hash, expires_at, last_ip)`.
- A remembered device skips the second factor at login, never at step-up, and never for `PLATFORM-STAFF` (whose `remember_device_days` is 0).
- Revoking all sessions also clears remembered devices.

## 6. Push delivery and the approver app

### 6.1 Enrolling a phone

1. Signed in on the portal, the user opens **Security → Add a phone**. The portal shows a QR code that holds a one-time pairing URL: `https://<portal>/approve/pair?t=<pairing token, 10 min, single use>`.
2. The phone opens the URL. The PWA installs (the browser offers "Add to Home Screen") and asks permission for notifications.
3. The phone creates a WebAuthn credential bound to the user, with user verification required. It also subscribes to Web Push, which gives a subscription endpoint and keys.
4. The server stores the device: name, credential, push subscription and platform. The browser that showed the QR code confirms "Phone added", and the next login offers push.

### 6.2 What a push carries

```json
{ "t": "mfa", "cid": "6f1c…", "title": "MiPay sign-in request", "body": "Open to approve or deny" }
```

There is no name, amount or IP address in it. Opening the notification makes the app call `GET /api/approver/challenges/{cid}` (authenticated by a device-bound session; §6.3) to fetch the details and the three number choices.

### 6.3 What the phone signs

```
clientData.challenge = SHA-256( "mipay-push-v1" | cid | chosenNumber | decision | actionHash | serverNonce )
```

This is sent as a WebAuthn assertion (PWA) or an ES256 signature (native) with `POST /api/approver/challenges/{cid}/respond`.

- **The server recomputes the hash** from its own copy of the challenge, so a tampered number, decision or action fails signature verification.
- **The approver app's own session** is the WebAuthn credential: every respond call carries an assertion, so a stolen push subscription gives an attacker nothing.

### 6.4 Sending pushes

- Sends go through a `PushSender` adapter. `WebPushSender` signs with VAPID, using a key from the environment.
- **Delivery is queued** in the notification outbox (`notification_messages` with channel `PUSH`), with retries. A device whose subscription returns 404 or 410 is marked stale.
- **If delivery fails, or nothing arrives within 15 seconds,** the browser shows "Didn't get it? Use your authenticator app / passkey / text message".

### 6.5 Limits of the PWA route

| Platform | Web Push to an installed PWA | Notes |
|---|---|---|
| Android (Chrome, Edge, Samsung Internet) | Yes | Reliable; works with the browser closed |
| iOS / iPadOS 16.4+ | Yes, **only when added to the Home Screen** | The pairing page guides the user through Share → Add to Home Screen |
| Desktop browsers | Yes | Useful for staff with a second laptop; never pair the computer you sign in on |

These limits are why push is never the only factor (§3).

### 6.6 Native app later

- A Flutter or React Native app replaces the PWA on the phone side only: it uses Firebase / Apple push and an ES256 key in Android Keystore or iOS Secure Enclave, gated by biometrics.
- The server API (§10) is the same: `device.kind = NATIVE` adds `public_key` and `push_token` instead of a WebAuthn credential.
- **Do it** when merchants get a mobile app, or when iOS home-screen installation proves to be too much friction.

## 7. Step-up for sensitive actions

> **Revised:** the rules below (which actions, how fresh, which are bound to the action) still hold.
> The mechanism is standard: the gateway or MiPay answers with an **RFC 9470** challenge
> (`acr_values=aal2`, `max_age`), the gateway BFF re-runs the FAPI flow, and for bound actions the PAR
> carries **RAR `authorization_details`** describing the action, so the token is good only for it
> (`fapi2-design.md` §4.3). The `X-Step-Up` replay header below is superseded by that flow.

A step-up guard runs on the endpoint before the handler. Each step-up rule names the actions it covers, the maximum age of the user's last second factor, and whether the approval must be bound to the exact request:

| rule key | covers | max age | bound to the action |
|---|---|---|---|
| `KYC_DECISION` | `POST /api/kyc/tasks/{id}/decision` | 5 min | **yes**: task id + outcome |
| `RIGHTS_CHANGE` | `PUT /api/orgs/{id}/rights`, platform functions | 5 min | yes: org id + new rights |
| `USER_ADMIN` | create, deactivate or reset users and admins; MFA reset approvals | 5 min | no |
| `ONBOARD` | `POST /api/orgs/me/children` | 15 min | no |
| `BANK_ACCOUNT` | adding or changing a payout bank account | 5 min | yes: account digest |
| `PAYOUT` | supplier payments, withdrawals, disbursements | 0 (every time) | **yes**: amount + currency + payee |

**What the user sees when a step-up is needed:**
1. The endpoint returns `401 STEP_UP_REQUIRED {challengeId, number?, methods, actionSummary}`.
2. The portals' HTTP interceptor opens a step-up dialog (the same component as the login step), waits for approval, and **replays the original request** with `X-Step-Up: <challengeId>`.
3. The guard accepts the replay only if:
   - the challenge is APPROVED, less than 2 minutes old and not yet used;
   - it belongs to this session;
   - its `actionHash` equals `SHA-256(method | path | canonical body)` of the replayed request.
4. **Bound challenges are used up by one request.** Unbound ones also set `devices_sessions.step_up_at`, which then satisfies later unbound rules within their maximum age.

**Push gives transaction signing for free:** for `PAYOUT` and `KYC_DECISION`, the phone shows exactly what is being approved, and the signature covers the action hash. Phishing an approval for a different amount or payee fails.

**KYC as a separate service:** step-up for KYC decisions has to work across services. Core's `Authorize` reply gets `amr`, `mfa_time` and the matched step-up challenge, and kyc-service passes `X-Step-Up` through to core with `CheckActor`. Core stays the single place that verifies factors.

## 8. Enrolment, and recovering passwords and 2FA

### 8.1 Enrolment

**Rollout:**
- Users who must have 2FA get `enroll_grace_logins` sign-ins (0 for staff, 3 for org ADMINs). After that, login returns `enrollmentRequired` and the portal shows the enrolment wizard: choose a method, confirm it, then save the recovery codes (the user has to tick "I saved them").
- **New users** enrol straight after activation (`/api/auth/activate`), before their first token is issued.

**Managing factors:** the Security page lists the enrolled methods, phones and passkeys (name, last used, Remove), remembered devices (Forget), and recovery codes (Regenerate). Removing the **last** factor of a user who needs 2FA is refused.

**Today:** no self-service password recovery exists. The existing `ADMIN_RECOVERY` request only creates a *replacement* admin for an org with no active admin (`OrgOnboardingService.requestAdminRecovery`). It doesn't reset an existing user, and it refuses Platform Root staff. The 72-hour single-use activation link (`ActivationService`) is the reusable piece.

### 8.2 What can be lost, and the route for each

| User has lost… | Route | Who decides | After it |
|---|---|---|---|
| **Password** (still has 2FA) | Self-service **Forgot password** (§8.3) | Nobody; the second factor proves it is the user | All sessions and remembered devices revoked; notice to every channel |
| **2FA** (knows the password) | Sign in with a **recovery code** → enrol a new factor (§3.5) | Nobody | Notice; remaining codes kept; prompt to regenerate |
| **2FA, no recovery code** | **Assisted recovery**, scope `MFA` (§8.4) | A second person (table in §8.4) | Factors wiped; enrolment link; cooling-off (§8.5) |
| **Password and 2FA** | **Assisted recovery**, scope `FULL` (§8.4) | A second person, with an identity check | New password and 2FA via a single-use link; cooling-off |
| **Phone number** (SIM lost, number changed) | **Assisted recovery**, scope `CONTACT` | A second person | New number verified by OTP; cooling-off |
| **The org's only ADMIN left** | Existing `ADMIN_RECOVERY` (replacement admin), unchanged | Parent org ADMIN or MiPay | The new admin activates and enrols 2FA |
| **Merchant transaction PIN** | OTP to the registered phone + new PIN | Nobody | 24-hour hold on withdrawals |
| **Every MiPay staff account** (no admin left to help) | **Break-glass** (§8.6) | Two named officers, offline | Full audit; staff accounts re-enrolled |

### 8.3 Self-service "Forgot password"

```
login page ─ "Forgot password?" ─▶ POST /api/auth/password/forgot {identifier}
   always: 202 "If an account matches, we sent a reset link to its registered email / phone."
   (same response and timing whether or not the account exists)

user opens link /reset?token=…  ─▶ GET  /api/auth/recovery/{token}
   → {step: "MFA", methods:[...]}  (users with 2FA)  |  {step: "PASSWORD"}  (users without)
   2FA step: the normal challenge (push / passkey / TOTP / SMS) from §5, purpose RECOVERY
   ─▶ POST /api/auth/recovery/{token}/password {newPassword}
   → password set; every session, remembered device and pending challenge revoked;
     notice to email + phone: "Your password was changed. Not you? Report it here."
   → the user signs in normally (password + 2FA)
```

- **Reset link:** 256-bit random, **stored hashed**, single-use, valid 30 minutes, bound to user and purpose. It goes to the email on file, or to the phone by SMS if there is no email. It reuses the activation-token store with `purpose = PASSWORD_RESET`.
- **No account enumeration:** identical responses and timing. Limits are 3 requests an hour per identifier and 10 an hour per IP; beyond them requests are silently dropped.
- **Recovery code:** it can stand in for the 2FA step only if the user's policy allows it. For MiPay staff it can't, so a staff member who has lost both goes to §8.4.
- **Password rules:**
  - at least 12 characters;
  - not one of the last 5 passwords (`password_history`, bcrypt);
  - checked against a bundled list of common and breached passwords (offline, no external call);
  - no forced periodic expiry.
- **Changing the password while signed in:** `POST /api/me/password` needs the current password **and** a step-up (rule `USER_ADMIN`). The same revoke-and-notify follows.

### 8.4 Assisted recovery (`ACCOUNT_RECOVERY` request)

A new kind of onboarding request, `ACCOUNT_RECOVERY`, with `scope` `MFA` | `FULL` | `CONTACT`. It reuses the request queue, inboxes and second-person rule the platform already has.

**Who can raise it:**
- **The user,** from the login page ("Can't get in?"): they give their identifier, a contact number to call back on, and a note. The request is created **unverified** and nothing is revealed to them.
- **An ADMIN of the user's org, or of its parent org,** from **Users → Recover access**.
- **MiPay staff,** for org users.

**Who decides:**

| Account being recovered | Decided by (never the requester, never the user) |
|---|---|
| Org member | An ADMIN of the same org, or of the parent org, or MiPay staff |
| Org ADMIN | An ADMIN of the parent org, or MiPay staff |
| MiPay staff (TPTS_OPS) | A different MiPay staff member holding OPS_REVIEWER, with step-up |
| MINDLABS_ADMIN, or holders of COMPLIANCE_OFFICER / MLRO / SENIOR_APPROVER | **Two** different MiPay staff, one of them COMPLIANCE_OFFICER or MLRO |

**Identity check before deciding:**
- The decider must complete and record a checklist on the request, and it is kept with the audit:
  - called back on the number on file, **not** the number given in the request;
  - confirmed name, date of birth and the last 4 digits of the ID number against the user's record (for org ADMINs, against the KYC owners or controllers where the admin is one);
  - saw the user's ID document on a video call (optional, but required for scope `FULL` on staff accounts).
- The decision needs the decider's own **step-up** (`USER_ADMIN`).

**When it is approved:**
1. Revoke, according to the scope:
   - `MFA`: all factors;
   - `FULL`: all factors, the password and the recovery codes;
   - `CONTACT`: the old phone number.
   In every case, all sessions and remembered devices are revoked too.
2. Issue a **single-use recovery link**: 24 hours, stored hashed, sent **only to the verified contact**. It may go to a new number only for scope `CONTACT`, and only after an OTP has proved the new number. The link sets a new password (for `FULL`) and then forces 2FA enrolment before any token is issued.
3. Start the **cooling-off period** (§8.5).
4. Notify the user on **every** known channel, old and new: "Access to your MiPay account was recovered by <decider org>. Not you? Call …".

**When it is rejected, or not decided within 7 days:** nothing changes, the request is closed, and the user is told through the original channels.

### 8.5 Cooling-off after recovery

For **72 hours** after any assisted recovery, and for 24 hours after a self-service password reset, the account is marked `recovered_until`. During that time:
- payouts, withdrawals, bank-account changes, rights changes, adding users and changes to contact details are **blocked**. The guard returns `409 RECOVERY_COOLING_OFF`;
- KYC decisions by a recovered **staff** account are blocked;
- browsing, saving drafts and submitting KYC still work.

The decider can shorten the period only with a second approver, and the reason is recorded.

**Why:** an attacker who talks their way through a recovery can't move money or grant rights before the real user sees the notices and reports it.

### 8.6 Break-glass (every MiPay staff account locked out)

- A documented, offline procedure (`docs/runbooks/break-glass.md`), run on the server by two named officers who both have database access. It uses `make break-glass USER=<username>`, which runs a one-off command that:
  - issues a recovery link for one staff account;
  - writes an audit row `break_glass_used` naming both officers;
  - sets the 72-hour cooling-off.
- It refuses to run while any active staff account with an enrolled factor exists, so it can't become a shortcut around §8.4.
- It sends an alert email to every compliance contact on file.

### 8.7 Changing contact details

The email and phone on file are what recovery trusts, so changing them is itself protected:
- the change needs a **step-up**;
- the **new** contact must be confirmed (link or OTP);
- a notice goes to the **old** contact, with a 24-hour window to block the change;
- the new contact can't receive a recovery link until those 24 hours have passed.

## 9. Merchants on the legacy OTP path

The phone OTP *is* their possession factor, so their login stays one step, with these fixes:
- the OTP moves from the in-memory map to hashed `mfa_challenges` rows (5-minute expiry, 5 attempts, rate-limited per phone and per IP);
- the delivery adapter queues through the outbox (§3.4);
- **step-up** for withdrawals and account changes requires a second factor that isn't the login SMS: a 4–6-digit **transaction PIN** (bcrypt-hashed, 5 attempts then locked), or push once a merchant app exists.

## 10. API

> **Revised:** the `/api/auth/*`, `/api/me/security*` and `/api/approver/*` endpoints below move to the IdP
> (`https://auth.afriqpay.com/...`, reached through the gateway's IdP vhost); `/api/orgs/.../recovery`
> stays in MiPay but checks RBAC and calls the IdP admin API. Paths are otherwise as listed.

| endpoint | purpose |
|---|---|
| `POST /api/auth/login` | as today, plus the MFA outcomes of §5 |
| `POST /api/auth/mfa/challenges` | start or switch method (sends push / SMS) |
| `GET /api/auth/mfa/challenges/{id}` | status poll (with `mfaToken`) |
| `POST /api/auth/mfa/verify` | finish login: code or assertion, optional `rememberDevice` |
| `POST /api/auth/mfa/enroll/{method}/start` · `/finish` | TOTP secret/QR, WebAuthn options/attestation, push pairing |
| `GET /api/me/security` | enrolled factors, devices, remembered devices, recovery-code count |
| `DELETE /api/me/security/factors/{id}` · `/devices/{id}` · `/trusted/{id}` | remove a factor, a phone or a remembered device |
| `POST /api/me/security/recovery-codes` | regenerate (step-up) |
| `POST /api/stepup/challenges` · `GET /api/stepup/challenges/{id}` | step-up for the current session (§7) |
| `GET /api/approver/challenges/{cid}` · `POST …/respond` | phone side (device-bound) |
| `POST /api/approver/pair` | phone completes pairing |
| `POST /api/auth/password/forgot` `{identifier}` | always 202; sends a reset link if the account exists (§8.3) |
| `GET /api/auth/recovery/{token}` | which step comes next: `MFA` / `PASSWORD` / `ENROLL` |
| `POST /api/auth/recovery/{token}/mfa` · `/password` · `/enroll` | complete each step of a reset or recovery link |
| `POST /api/me/password` | change password: current password + step-up |
| `POST /api/me/contact` · `POST /api/me/contact/confirm` | change email or phone, with the 24-hour hold (§8.7) |
| `POST /api/auth/recovery/assist` `{identifier, callbackPhone, note}` | "Can't get in?" from the login page: raises an unverified `ACCOUNT_RECOVERY` |
| `POST /api/orgs/{id}/users/{userId}/recovery` `{scope}` | an admin raises `ACCOUNT_RECOVERY` for a user |
| `POST /api/orgs/onboarding/{id}/decide` (existing) | decides `ACCOUNT_RECOVERY` too, with the identity checklist and step-up |

**Rate limits:**
- per IP: login 10/min, verify 10/min, challenge start 5/min;
- per user: push 3/10 min, SMS 3/10 min.

The gateway gets matching per-IP limits for `/api/auth/**` and `/api/approver/**`.

## 11. Data model (core DB)

| table | purpose |
|---|---|
| `mfa_policies` | §4 |
| `mfa_factors` | one row per enrolled factor: `user_id`, `kind TOTP\|WEBAUTHN\|PUSH_WEB\|PUSH_NATIVE\|SMS`, `label`, encrypted `secret` (TOTP), `credential_id` / `public_key` / `sign_count` / `transports` (WebAuthn), `push_endpoint` + `push_keys` or `push_token`, `last_step` (TOTP replay), `created_at`, `last_used_at`, `revoked_at` |
| `mfa_recovery_codes` | `user_id`, `code_hash`, `used_at` |
| `mfa_challenges` | `id`, `user_id`, `session_nonce`, `purpose LOGIN\|STEP_UP\|ENROLL\|OTP_LOGIN`, `method`, `number`, `decoys`, `code_hash`, `context` (jsonb: portal, ua, ip, city, action summary), `action_hash`, `status PENDING\|APPROVED\|DENIED\|WRONG_NUMBER\|EXPIRED\|CONSUMED`, `attempts`, `factor_id`, `expires_at`, `responded_at` |
| `mfa_trusted_devices` | §5 |
| `mfa_lockouts` | `user_id`, `method`, `until`, `reason` |
| `devices_sessions` | **add** `amr`, `acr`, `mfa_at`, `step_up_at` (the existing `mfa_method` becomes populated) |
| `users` | **add** `mfa_enrolled_at`, `mfa_grace_left`, `transaction_pin_hash` (merchants), `password_changed_at`, `recovered_until` (cooling-off), `contact_hold_until` |
| `password_history` | `user_id`, `password_hash`, `created_at` (the last 5 are kept) |
| `activation_tokens` | **add** `purpose ACTIVATION\|PASSWORD_RESET\|ACCOUNT_RECOVERY\|CONTACT_CONFIRM` and `scope`. The TTL depends on the purpose (72 h / 30 min / 24 h / 24 h). Tokens are stored hashed (the existing sandbox-only raw copy in `sim_outbox` stays sandbox-only) |
| `organizations` | **add** `mfa_required` |
| `onboarding_requests` | **add** kind `ACCOUNT_RECOVERY`, plus `target_user_id`, `scope MFA\|FULL\|CONTACT`, `verification` (jsonb checklist), `second_decider` (for dual approval) |

**Retention:** challenges are purged after 90 days, and their audit rows are kept under the normal audit retention.

## 12. Security invariants (no configuration can override)

1. **The second factor is never the same channel that proved the password.** For example, the email-reset path can't satisfy 2FA.
2. **Every challenge is single-use, expires, and is bound to one session nonce.** An approved login challenge can't be replayed into another browser.
3. **Push approvals count only when signed by the registered device key over the server's own challenge data.** The device key itself is protected by user verification on the device.
4. **Secrets are stored encrypted or hashed:** TOTP secrets with AES-GCM under a key from the environment, recovery codes and PINs with bcrypt, SMS codes hashed. Nothing reversible is ever logged.
5. **One lost secret never unlocks the account alone.** A password reset still needs the second factor. Losing the second factor needs a recovery code or a second person. Losing both always needs a second person **and** an identity check, followed by cooling-off (D7, §8).
6. **Policy can only make org users stricter.** An org override never removes a platform requirement.
7. **Every MFA event is audited**, without codes or keys: enrol, verify, fail, deny, wrong number, lockout, reset, remember, forget.
8. **Remembered devices never satisfy step-up,** and `PLATFORM-STAFF` never get remembered devices.
9. **Recovery links go only to contact details that have been on file for more than 24 hours, or that were verified inside the recovery itself.** Every recovery, reset or contact change is notified on **all** known channels, old and new.
10. **Recovery never reveals whether an account exists,** and every recovery endpoint is rate-limited per identifier and per IP.

## 13. Screens

**Login, step 2** (both portals):

```
┌───────────────────────── Approve on your phone ─────────────────────────┐
│ We sent a request to “Selma’s Pixel 8”.                                  │
│                                                                          │
│                              ┌──────┐                                    │
│                              │  47  │   ← tap this number on your phone  │
│                              └──────┘                                    │
│ Waiting… 0:42                                                            │
│ [ ] Remember this browser for 30 days                                    │
│ Use another method:  Authenticator app · Passkey · Text message · Code   │
└──────────────────────────────────────────────────────────────────────────┘
```

- **Phone (PWA)**: the request card (portal, browser, city, time, action), three large number buttons, **Deny**, and a small "This wasn't me". After a choice it shows a result tick or cross.
- **Security page** (both portals): factors, phones, remembered devices, recovery codes, and the last 10 sign-ins with method and city.
- **Admin portal and org portals → Users:** each user's MFA status (enrolled / due / not required) and a **Recover access** action (scope MFA / full / phone number), which creates `ACCOUNT_RECOVERY` for a second person.
- **Login page:** a **Forgot password?** link (§8.3) and a **Can't get in?** link, which raises an assisted-recovery request and shows "We'll call you on the number we have on file".
- **Reset / recovery page** (`/reset?token=…`): step 1 is 2FA when required, step 2 a new password with live strength rules, step 3 (recovery only) enrolment of new 2FA plus recovery codes.
- **Recovery request review** (in the existing onboarding queues): who is recovering whom, the scope, the identity checklist (call-back done, details matched, ID seen), the second approver when one is needed, and the cooling-off period to apply.
- **Cooling-off banner** on every page: "Access was recovered on 11 Nov at 10:42. Payouts and admin changes unlock on 14 Nov at 10:42."
- **Step-up dialog:** the same component as login step 2, showing the action summary ("Approve KYC: Okahandja Fresh Market").

## 14. Trustplane IdP

**Revised:** this is no longer a later add-on. The IdP is where all of the above is built (see the revision note at the top, and `fapi2-design.md`). Its tokens carry `amr` (RFC 8176), `acr` (`aal1` / `aal2`) and `auth_time`, and it advertises `acr_values_supported = ["aal1","aal2"]` so FAPI clients and the gateway BFF can ask for `aal2`.

## 15. Test plan (`TUat21MfaTests` + portal headless E2E)

- **Policy:**
  - resolution, and the boot failure on an unknown method or an ambiguous policy;
  - an org override can make the policy stricter, never looser;
  - grace logins count down, then enrolment is forced.
- **Login:**
  - password then push approve → a token with `amr` including `mfa` and `acr=aal2`;
  - deny → no token;
  - a wrong number, expiry or reuse of a challenge is refused;
  - an `mfaToken` is refused by every non-MFA endpoint.
- **Push:**
  - a forged signature, another user's device, a revoked device, a tampered number or action hash, a replayed respond and an unpaired device are all refused;
  - prompt-flood limits and lockouts work;
  - "This wasn't me" revokes all sessions.
- **TOTP:** RFC 6238 test vectors, ±1 step of drift, replay of the same step refused, the secret encrypted in the DB.
- **WebAuthn:** registration and assertion through a virtual authenticator (Chrome DevTools protocol, headless); a signature-counter regression is refused.
- **SMS:** the code is hashed, expires after 5 minutes, allows 5 attempts and is rate-limited; staff can't use SMS.
- **Recovery:** each code works once; regenerating invalidates the old set; a recovery code can't satisfy `PAYOUT` or `KYC_DECISION`.
- **Step-up:**
  - a KYC decision without step-up gets 401;
  - an approved challenge for task A can't approve task B;
  - a payout challenge for N$100 can't approve N$1,000;
  - a bound challenge works once;
  - a remembered device doesn't help.
- **Forgot password:**
  - same response and timing for known and unknown identifiers;
  - rate limits apply;
  - a link is single-use, expires after 30 minutes, and a used or expired link is refused;
  - a user with 2FA can't set a new password without passing the second factor;
  - a reset revokes every session and remembered device and notifies on every channel;
  - the last 5 passwords and passwords from the common/breached list are refused.
- **Assisted recovery:**
  - a request decided by its requester, or by the user being recovered, is refused;
  - a decision without the identity checklist, or without step-up, is refused;
  - a staff `FULL` recovery needs two different deciders;
  - approval wipes the factors and password for the scope and issues a 24-hour link to the verified contact only;
  - a request raised from the login page reveals nothing about the account.
- **Cooling-off:** after recovery, payouts, bank-account and rights changes get `RECOVERY_COOLING_OFF` until `recovered_until`; drafts and KYC submission still work.
- **Contact change:** needs step-up; the old contact is notified; a recovery link can't go to the new contact within 24 hours.
- **Break-glass:** refuses while any active staff account with 2FA exists; when it runs, it is audited with both officers named.
- **Regression:** existing UAT packs pass with policies switched off (`mfa.enforce=false` in the test profile), plus a run with them switched on.

## 16. Implementation phases

> **Revised:** these phases run **in the IdP**, after the identity migration in `fapi2-design.md` §8
> (they are its step 6). "Two-step login (`mfaToken`)" becomes the IdP's own login steps, and "the portal
> interceptor and dialog" for step-up becomes the gateway's RFC 9470 handling plus the portals' reaction to
> `step_up_required`.

1. **Foundations:**
   - the `V12__mfa.sql` tables;
   - policy master and boot validator;
   - two-step login (`mfaToken`) and the new token claims;
   - TOTP and recovery codes;
   - enrolment wizard and Security page in both portals;
   - OTP hardening (§9);
   - audit events.
2. **Step-up:** the `@RequiresStepUp` guard, the portal interceptor and dialog, the action-bound challenges and the default rules (§7).
3. **Push through the PWA:**
   - WebAuthn registration and assertion (a library such as `webauthn4j`);
   - pairing by QR code;
   - Web Push with VAPID;
   - the approver PWA;
   - number matching and prompt-flood protection.
4. **SMS fallback** through a real provider adapter, the transaction PIN for merchants, and remembered devices.
5. **Password and 2FA recovery** (§8):
   - self-service Forgot password, with 2FA still required;
   - password rules and history;
   - assisted `ACCOUNT_RECOVERY` through the onboarding-request machinery, with the identity checklist and dual approval for privileged staff;
   - cooling-off;
   - protected contact changes;
   - the break-glass runbook.
   Recovery codes ship in phase 1, so nobody is ever left with no way back in between phases.
6. **Later:** the native approver app (§6.6), and 2FA on the IdP for FAPI clients (§14).

## 17. Open items

1. **Who must use push first:** MiPay staff only, or org ADMINs from day one? D3 currently makes both require some 2FA, with push one of the choices.
2. **Native app:** is one on the roadmap (for merchants, say)? If so, phase 3 may go straight to native.
3. **Step-up for money movement:** in phase 2, or only after payouts move to the new rules?
4. **Push through Google and Apple services:** acceptable under MiPay's data rules? Only a challenge id and a fixed text pass through them (D8).
5. **SMS provider** for Namibia (MTC / TN Mobile, or an aggregator), and whether WhatsApp OTP is wanted as a channel.
6. **Regulatory:** compliance to confirm which factors meet the Bank of Namibia's expectations for privileged and payment access, and whether `aal2` from push or TOTP is enough for payouts or a passkey is required.
7. **Production relying-party id and portal hosts:** passkeys are tied to the host, so moving hosts later invalidates them.
8. **Email delivery:** the platform has no real email sender yet; notices and activation links go to the simulated outbox. Password-reset links need a real email provider (or SMS-only reset) before production.
9. **Cooling-off lengths:** 72 hours after assisted recovery and 24 hours after a self-service reset are proposals; compliance and operations should confirm what customers will tolerate.
10. **Break-glass officers:** who the two named officers are, and where the runbook and the alert contacts live.
