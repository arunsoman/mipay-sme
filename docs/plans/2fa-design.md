# Two-factor authentication: push with number matching, authenticator app, passkeys and step-up

> **Implementation status (2026-11-12):** `IDP_MFA_MODE=off|shadow|enforce` is
> available. In `enforce`, a password or merchant SMS login creates a five-minute
> challenge and no session. A TOTP authenticator can be enrolled and verified;
> password users with an eligible phone may instead use a separate SMS code through
> Java comms. Tokens issued after verification carry `acr=aal2` and both methods in
> `amr`. Existing AAL1 sessions, codes and refresh tokens cannot be reused in
> enforce mode. The IdP publishes `mfa_mode` and `step_up_acr` in discovery and a
> core-only `/mfa/readiness` endpoint. This is the login MFA slice of the design.
> WebAuthn, native push, recovery codes, remembered devices, RAR-bound step-up,
> cooling-off and the core/portal workflows below remain to be implemented.
> Comms sandbox delivery by itself never approves a challenge.

## Current implementation by method (2026-11-12)

This table describes the code and development configuration today. It is not a
claim that the full design in later sections is complete.

| Method or capability | State today | Simulation and practical effect |
|---|---|---|
| Password | Implemented first factor | The IdP verifies bcrypt credentials. With `IDP_MFA_MODE=enforce`, a correct password creates a pending MFA challenge, not a session. |
| Authenticator app (TOTP) | Implemented second factor for login | A user with no enrolled factor can add a 160-bit secret at their next enforced login and confirm a six-digit code. The secret is AES-GCM encrypted; verification allows ±1 time step, blocks replay and creates an `aal2` session. Works without comms or a simulator. |
| SMS after password | Implemented second factor for login where SMS is available | The IdP stores a hashed, five-minute challenge and verifies the user's code before creating an `aal2` session. Java comms currently delivers it only to the development sandbox outbox and prints it in development logs. A real person or test must submit the code; a delivery receipt never approves it. If SMS delivery is unavailable, the login remains blocked and the user can switch to authenticator setup. |
| Merchant phone OTP login | Implemented first factor | It proves control of the registered phone and is `aal1` by itself. Under `enforce`, the user must also complete TOTP. The IdP's fixed `IDP_DEV_OTP=123456` is available only in development; otherwise delivery uses comms. |
| Java comms SMS/email sandbox | Implemented delivery simulator, not an MFA factor | It accepts messages over mTLS, keeps queued secrets encrypted, reports capabilities and writes/prints rendered messages in development. SMS can carry an MFA code; email currently carries reset and setup links or notices, not an email MFA factor. `production` refuses sandbox providers. With no provider, the channel reports unavailable and messages are suppressed; the IdP does not skip MFA. |
| Shadow mode | Implemented basic logging | It logs that a successful first-factor login would need MFA, while issuing the ordinary `aal1` session. Full per-profile rollout analytics are still planned. |
| Passkey / WebAuthn | Design only | No enrolment, assertion verification or simulated passkey is wired into the IdP. |
| Native push with number matching | Design only | No device pairing, signed approval or push transport exists. The comms plane reports push unavailable. |
| Recovery codes and remembered devices | Design only | No recovery-code sign-in or trusted-device exemption is wired in. The core-only MFA reset removes a TOTP factor and revokes sessions. |
| Transaction PIN and action step-up | Design only | No PIN verifier or RAR-bound payout/KYC step-up is wired through the gateway, backend and portals. `aal2` currently describes a verified login, not authorisation for a specific sensitive action. |

Development run mode: `cd trustplane && make up-mfa`, or set
`IDP_MFA_MODE=enforce` when starting Compose. The normal `make up` defaults to
`off` for existing FAPI tests. The IdP refuses enforced startup without its
`IDP_MFA_KEY_FILE`. The Java comms sandbox can be absent: TOTP enrolment and
login continue, and SMS-dependent paths fail closed.

Status: **draft for review** · 2026-11-11 · **revised 2026-11-12** to record the current implementation and match [`idp-rbac-cutover-plan.md`](idp-rbac-cutover-plan.md) and [`fapi2-design.md`](fapi2-design.md)

> **Where this runs, in one paragraph.**
> - 2FA is built in the **trustplane IdP**, the credential plugin. Browsers reach it through the gateway acting as a FAPI 2.0 BFF.
> - The **MiPay core** owns users, their org, their status and their contact details. It decides which 2FA *profile* each user needs, from their RBAC roles and their org.
> - **Trustplane RBAC** decides permissions, and each permission cell says whether it needs a step-up.
> - Every message (OTP, push, links, notices) goes through the **comms plane** ([`comms-design.md`](comms-design.md)), addressed by the core user id.
> - The whole feature ships **switched off** (`mfa.mode=off`). Each factor turns itself on when the comms channel it needs is live (§4).

| Part of this design | Implemented in |
|---|---|
| Factors (§3), policy (§5), login second step (§6), approver app and push (§7), self-service password reset (§9.3), merchant OTP (§10) | **IdP**: its login and account pages at `auth.afriqpay.com`, and its own database |
| Which profile a user needs (§5.1), assisted recovery (§9.4), contact changes (§9.7), cooling-off (§9.5) | **Core**, through the `AuthProvider` contract (`idp-rbac-cutover-plan.md` §1.2) |
| Step-up (§8) | Each RBAC permission cell declares its step-up rule. The gateway or core answers with an **RFC 9470** challenge; transaction binding uses **RAR** (`authorization_details`) |
| Sending OTPs, pushes, links and notices | **Comms plane**, through the IdP's and core's `CommsClient`; a sandbox implementation is used until it is live |
| Token claims `amr` / `acr` / `auth_time` | IdP ID and access tokens (with `uid`), forwarded to upstreams in the gateway's signed context |

## 1. Context and decisions

**Today:**
- Portal users sign in with username or phone plus a password at the core's `POST /api/auth/login`, which issues its own HS256 token. This is being replaced by the IdP login (`idp-rbac-cutover-plan.md` P1–P5).
- Merchants sign in with phone and OTP. The OTP is kept in memory, with no expiry and no attempt limit, and in production it is never actually sent.
- **No second factor exists.** Privileged actions need only a valid token.
- **No message is actually delivered:** `notification_messages` has nothing that sends it (`comms-design.md` §0).

**Decisions:**

| # | Decision |
|---|---|
| D1 | 2FA is enforced in the **trustplane IdP**, the only place anyone logs in. The core never sees a factor or a code. |
| D2 | **Factors, in order of preference:** push approval with number matching, passkey (WebAuthn), authenticator app (TOTP), SMS OTP (fallback only), recovery codes (recovery only). Merchants also get a transaction PIN for step-up (§10). |
| D3 | **Mandatory for platform staff** (any `platform_*` role) **and for every `org_admin`.** Optional for other org members unless their org switches it on. Merchants keep phone OTP as their login factor (§10). |
| D4 | **Push goes straight to the native MiPay mobile app** (decided 2026-11-11: a native app is on the roadmap). Delivery is Firebase / Apple push through the comms plane (accepted under MiPay's data rules, because only a challenge id and fixed text pass through them; D8). The device key is ES256 in Android Keystore / iOS Secure Enclave, gated by biometrics. **No PWA approver is built**, to avoid building push twice; until the app ships, users use TOTP or a passkey (§7). |
| D5 | **Step-up:** sensitive actions need a second factor less than 5 minutes old. For money movement and KYC decisions, the approval is bound to the exact action (§8). |
| D6 | **The login policy lives in an IdP master table** keyed by **2FA profile** (`PLATFORM-STAFF`, `ORG-ADMIN`, `ORG-MEMBER`, `MERCHANT-OTP`). The core sets each account's profile. **Step-up rules live on the RBAC permission cells**, not in the 2FA policy. |
| D7 | **Recovery covers passwords and 2FA, and never lets one lost secret unlock everything** (§9). |
| D8 | **No personal data in push payloads.** A push carries only a challenge id and a fixed message. |
| D9 | **Off until switched on.** One switch, `mfa.mode = off \| shadow \| enforce`. Factors that need a comms channel are available only while that channel is live, so turning the switch on, or a channel going live, needs no deployment (§4). |
| D10 | **All messages go through the comms plane, addressed by the core user id** (`user:<uid>`). The IdP holds no email address or delivery phone; the core is the only writer of contact details in comms. |

## 2. Building blocks

```
 core ── sets mfa_profile per account (from RBAC roles + org.mfa_required) ──▶ IdP accounts
                                                                                 │
 browser ─▶ gateway BFF ─▶ IdP /authorize ── password ──▶ MfaPolicyResolver(profile, mfa.mode)
                                                   │            │
                                                   │            ├─ not needed → token (amr=pwd, acr=aal1)
                                                   ▼            ▼ needed
                                              MfaService ── challenge ──▶ factor plugins (available ones only)
                                                   ▲                      PushFactor · WebAuthnFactor · TotpFactor
                                                   │                      SmsOtpFactor · RecoveryCodeFactor · PinFactor
                                             verify / poll                       │ needs comms?
                                                   │                             ▼
                                                   ▼                      FactorReadiness ◀── comms /capabilities
                                    token (uid, amr, acr=aal2, auth_time) ──▶ gateway session
 any request ─▶ gateway / core: permission cell needs step-up? ── no ─▶ handler
                                                               └─ yes, not fresh/bound → 401 step_up_required (RFC 9470)
```

Each factor implements one interface in the IdP and declares what it needs from the comms plane:

```go
type Factor interface {
    Key() string                       // PUSH | WEBAUTHN | TOTP | SMS | RECOVERY | PIN
    Requires() []Capability            // e.g. {CommsSMS}; nil for TOTP, WebAuthn, recovery codes, PIN
    Enrolled(acct Account) bool
    StartEnrollment(acct Account, c EnrollContext) (EnrollmentStart, error)
    FinishEnrollment(acct Account, proof EnrollmentProof) error
    Challenge(ch *Challenge) (ChallengeView, error)   // sends the push / SMS through CommsClient
    Verify(ch *Challenge, r FactorResponse) (VerifyResult, error)
    AMR() []string                     // RFC 8176 values written into the token
}
```

## 3. Factors

| Factor | Needs comms | Usable for login | Usable for step-up |
|---|---|---|---|
| Push with number matching | `comms.push` (comms P2) **and** the MiPay app installed | yes | yes, with transaction binding |
| Passkey / security key | no | yes | yes |
| Authenticator app (TOTP) | no | yes | yes |
| SMS OTP (fallback) | `comms.sms` (comms P1) | per policy | per policy, never for staff |
| Recovery codes | no | recovery only | **never** for `PAYOUT` / `KYC_DECISION` |
| Transaction PIN (merchants) | no | no | merchant step-up only |

### 3.1 Push approval with number matching (preferred)

1. The browser shows a 2-digit number (10–99) in large type. The phone shows three choices: that number and two random decoys, in random order.
2. The user opens the MiPay app (fingerprint, face or device PIN) and taps the matching number. The phone signs `(challenge_id, chosen_number, decision, action_hash, nonce)` with its device key.
3. The IdP checks, in this order:
   - the signature against the registered public key;
   - that the device is active and belongs to the account;
   - that the challenge is pending and not expired;
   - that the chosen number is correct.

**What the phone shows:** the portal name, the browser and OS, an approximate location from the IP address (city level), the time, and the action for step-up ("Approve KYC for Okahandja Fresh Market", "Pay N$12,400 to Nam Wholesale CC").

**Protections against prompt flooding ("MFA fatigue"):**
- A challenge lives for 60 seconds and is single-use.
- **A wrong number** fails the challenge immediately. The browser has to start over, and the event is audited.
- At most **3 push challenges per user per 10 minutes**.
- **3 denials or wrong numbers in 30 minutes** lock push for that user for 30 minutes (other factors still work) and raise an ops alert.
- **"This wasn't me"** on the phone:
  - denies the challenge and revokes all of the user's sessions (back-channel logout);
  - requires a password reset at the next login;
  - sends the core a `SUSPICIOUS_LOGIN` event, and the core notifies the org's admins (or platform security, for staff) through comms.

**How the waiting browser learns the result:** the IdP page polls its own challenge status every 2 seconds and offers "Use another method" at any time.

### 3.2 Passkey / security key (WebAuthn)

- Platform authenticators (Windows Hello, Touch ID, Android) and roaming keys (YubiKey) both work, with user verification required.
- **The relying-party id is the IdP host** (`auth.afriqpay.com`; `idp.fapi.local` in the sandbox), because every login happens there. Passkeys therefore work across all four portals and survive portal host changes.
- The IdP stores the credential id, public key (COSE), signature counter, transports and attestation format. Attestation is `none`.

### 3.3 Authenticator app (TOTP)

- RFC 6238: SHA-1, 6 digits, 30-second step, ±1 step of clock drift.
- The secret is 160 bits, shown once as a QR code (`otpauth://totp/MiPay:<login_name>?issuer=MiPay`), and stored encrypted with AES-GCM under the IdP's `mfa.encryption_key`.
- **Replay protection:** the last accepted time step is stored.
- Enrolment is confirmed only after the user enters one valid code.

### 3.4 SMS OTP (fallback only)

- 6 digits, a 5-minute life, at most 5 attempts, then a new code is needed. Stored **hashed** in `mfa_challenges`.
- Sent with `CommsClient.Send(event=auth.otp.sms, to=user:<uid>, lane=CRITICAL, expiresAt=+5 min)`. Comms looks up the phone the core registered and **never sends it late**.
- **Not allowed for platform staff.** For org users it is allowed only when the policy permits it, because SIM swap makes it weak.
- Available only while `comms.sms` is live (§4).

### 3.5 Recovery codes

- 10 codes of 10 characters (Crockford base32, grouped `XXXXX-XXXXX`), shown once at enrolment and stored bcrypt-hashed. Each works once.
- Regenerating them invalidates the old set. Using one is audited and triggers a notice through comms.
- A recovery code signs the user in, but **cannot satisfy a step-up** for money movement or KYC decisions.

## 4. Switching it on: `mfa.mode` and factor readiness

### 4.1 The switch

| `mfa.mode` | Login | Step-up | Enrolment |
|---|---|---|---|
| `off` (initial) | password only (`acr=aal1`) | **fresh re-authentication**: password re-entered within `max_age` (`acr=aal1`, `auth_time` fresh); audited as `step_up_without_mfa` | **optional**: users may enrol TOTP or a passkey from the Security page, so they are ready on day one |
| `shadow` | as `off`, but the IdP logs which users *would* have been asked, and for what | as `off` | optional; the Security page shows "2FA will soon be required" to users whose profile needs it |
| `enforce` | the policy (§5) applies, with grace logins | the second factor is required (`acr=aal2`), bound where the rule says so | forced after the grace logins |

- **The minimum strength for step-up comes from the IdP, not from code.** The IdP publishes `step_up_acr` (`aal1-fresh` while `off`/`shadow`, `aal2` in `enforce`). The gateway and core's `@Perm` checks (`idp-rbac-cutover-plan.md` P6.4) read it, so switching to `enforce` changes nothing in the portals or the backend.
- **Grace logins** (`enroll_grace_logins`: 0 for staff, 3 for existing org admins) mean switching to `enforce` takes everyone through the enrolment wizard at their next login and locks nobody out.

### 4.2 Factor readiness

The IdP computes, for each account at each login:

```
usable_methods = policy(profile).allowed_methods  ∩  { factor | every capability in factor.Requires() is live }
```

- **Where readiness comes from:** the comms plane publishes `GET /comms/v1/capabilities`. For each channel it reports `live` (a provider is configured, healthy, and the auth event templates are published). The IdP caches it for 60 s.
- **When a channel goes live, the factor appears.** When it fails, the factor disappears and the user is offered another method. **It never falls back to "no second factor".**
- **Before comms exists:** `CommsClient` uses the sandbox implementation (writes to `sim_outbox`) in the sandbox and CI, so every factor is tested. In production it reports every channel as not live.
- **Flows that need comms, not just factors:**
  - self-service "Forgot password" (§9.3) is offered only while email or SMS is live; until then the login page shows only "Can't get in?" (assisted recovery);
  - merchant phone-OTP login (§10) is offered only while `comms.sms` is live; until then merchants sign in with a password.

### 4.3 Safety check before enforcing

- **No lock-out:** `enforce` is refused for any profile whose policy is `ALWAYS` but whose `usable_methods` is empty. The profile stays in `shadow`, and an alert is raised. Profiles that allow TOTP or a passkey (`PLATFORM-STAFF`, `ORG-ADMIN`) can always be enforced, comms or not.
- **At boot,** `MfaPolicyValidator` checks that every method key names a factor, every profile is unique, and the step-up rule keys published by the RBAC catalogue are all known. If anything fails, the IdP refuses to start.
- **Readiness page** (admin portal → Security → 2FA readiness): for each profile, its mode, its usable methods, the methods waiting for a comms channel, how many users are enrolled, and how many grace logins are left.

### 4.4 Switch-on order

1. **Now:** `mfa.mode=off`; optional TOTP and passkey enrolment allowed; step-up by fresh password.
2. **`shadow`** for a week: see who would be prompted, and chase enrolment.
3. **`enforce`:** staff and admins use TOTP or a passkey straight away.
4. **Comms P1 live:** the SMS fallback, self-service reset by SMS or email, and merchant phone-OTP login appear on their own.
5. **Comms P2 live:** push appears on its own and becomes the preferred method.

## 5. Login policy master (IdP)

`mfa_policies` is part of the IdP schema (the IdP's DDL, not a core Flyway migration), and its rows are seeded from config.

| column | notes |
|---|---|
| `profile` | `PLATFORM-STAFF`, `ORG-ADMIN`, `ORG-MEMBER`, `MERCHANT-OTP` (primary key) |
| `required` | `ALWAYS` \| `IF_ORG_REQUIRES` \| `NEVER` |
| `allowed_methods` (jsonb) | e.g. `["PUSH","WEBAUTHN","TOTP"]` |
| `fallback_methods` (jsonb) | e.g. `["SMS","RECOVERY"]` |
| `remember_device_days` | `0` for staff, `30` for org users |
| `enroll_grace_logins` | `0` for staff, `3` for existing org admins at rollout |

**Default rows:**

| profile | required | allowed | fallback | remember |
|---|---|---|---|---|
| `PLATFORM-STAFF` | ALWAYS | PUSH, WEBAUTHN, TOTP | RECOVERY | 0 days |
| `ORG-ADMIN` | ALWAYS | PUSH, WEBAUTHN, TOTP | SMS, RECOVERY | 30 days |
| `ORG-MEMBER` | IF_ORG_REQUIRES | PUSH, WEBAUTHN, TOTP, SMS | RECOVERY | 30 days |
| `MERCHANT-OTP` | (OTP is the login) | SMS | — | 30 days |

### 5.1 How an account gets its profile (core)

The IdP knows no roles, so the **core decides the profile** and sets it on the account with `AuthProvider.setMfaProfile(subject, profile, orgRequires)`:

| The user… | Profile |
|---|---|
| holds any `platform_*` role (home org PLATFORM) | `PLATFORM-STAFF` |
| holds `org_admin` on their org | `ORG-ADMIN` |
| is a merchant user who signs in by phone OTP | `MERCHANT-OTP` |
| anyone else (`org_staff`, `org_viewer`, `kyc_applicant`) | `ORG-MEMBER`, with `orgRequires = organizations.mfa_required` |

- **Kept in step by the core's `AuthSyncService.reconcile`** (`idp-rbac-cutover-plan.md` §1.5). It runs whenever a role assignment or `mfa_required` changes, so granting someone `org_admin` makes 2FA required at their next login.
- **The strictest profile wins** when a user matches more than one.
- **Org override:** an org admin can set `organizations.mfa_required = true` (core) to require 2FA for all members. An org can only make the policy stricter.

## 6. Login flow (inside the IdP)

```
/authorize (from the gateway BFF, via PAR)
  └─ IdP login page: login name or phone + password
       ├─ wrong password ───────────────────────────▶ error, per-account lockout counts
       ├─ mfa.mode=off/shadow, or 2FA not needed,
       │  or remembered device ──────────────────────▶ code → token (amr=["pwd"], acr=aal1)
       ├─ 2FA needed, enrolled ──────────────────────▶ step 2: preferred usable method, "Use another method"
       │                                               → verify → code → token (amr=["pwd","mfa",<f>], acr=aal2)
       └─ 2FA needed, not enrolled
             ├─ grace logins left ─▶ token (aal1) + "Set up 2FA (n left)" banner
             └─ none left ─────────▶ enrolment wizard (choose method, confirm, save recovery codes), then token (aal2)
```

- **Step 2 lives inside the IdP's own login transaction** (bound to the PAR request and the IdP session cookie): at most 5 failed verifications, then back to the password. Nothing like an `mfaToken` reaches the portals.
- **Token claims:**
  - `amr`: RFC 8176 values, e.g. `["pwd","mfa","hwk"]` (passkey), `["pwd","mfa","otp"]` (TOTP), `["pwd","mfa","sms"]`, `["pwd","mfa","swk"]` (push);
  - `acr`: `aal1` or `aal2`;
  - `auth_time`, `mfa_time`, `uid`.

  The gateway session stores them, and they reach the core in the signed context.
- **Remember this device:**
  - a `__Host-mp_dev` cookie on the IdP host (HttpOnly, 256-bit random); the IdP stores `mfa_trusted_devices(subject, device_hash, ua_hash, expires_at, last_ip)`;
  - it skips the second factor at login only: never at step-up, and never for `PLATFORM-STAFF`;
  - revoking sessions (from the core or "This wasn't me") clears remembered devices.

## 7. Push delivery through the MiPay app (native)

### 7.1 Enrolling a phone

1. Signed in on a portal, the user opens **Security → Add a phone** (an IdP account page, linked from every portal's menu). It shows a QR code with a one-time pairing token (10 min, single use).
2. In the MiPay app, the user signs in (it uses the same IdP, as a native FAPI client) and scans the QR code; or, when they sign in to the app itself, it offers "Use this phone to approve sign-ins".
3. The app generates an **ES256 key in Android Keystore / iOS Secure Enclave**, marked non-exportable and usable only after biometric or device-PIN verification. Where the platform supports it, the app sends the key attestation (Play Integrity / App Attest), and the IdP records whether the key is hardware-backed.
4. The IdP stores the device (name, platform, public key, attestation result). The app registers its FCM / APNs token with comms as a device of `user:<uid>` (`POST /comms/v1/subjects/user:<uid>/devices`). The browser that showed the QR confirms "Phone added".

### 7.2 What a push carries

```json
{ "t": "mfa", "cid": "6f1c…", "title": "MiPay sign-in request", "body": "Open to approve or deny" }
```

No name, amount or IP address, so Google and Apple see nothing personal (D8). Opening it makes the app call `GET https://auth.afriqpay.com/approver/challenges/{cid}` for the details and the three numbers. The call is authenticated by a signature from the device key.

### 7.3 What the phone signs

```
signature = ES256( device_key, SHA-256( "mipay-push-v1" | cid | chosenNumber | decision | actionHash | serverNonce ) )
```

The app sends it to `POST /approver/challenges/{cid}/respond`. The IdP recomputes the hash from its own copy, so tampering fails, and a stolen push token gives an attacker nothing.

### 7.4 Sending pushes

- Sent with `CommsClient.Send(event=auth.push.challenge, to=user:<uid>, channel=PUSH, lane=CRITICAL, expiresAt=+60 s)`. Comms holds the FCM / APNs credentials, the retries and the stale-token handling (`comms-design.md` §7). When the challenge is answered elsewhere, the IdP asks comms for a silent cancel push.
- **If nothing arrives within 15 seconds,** the page shows "Didn't get it? Use your authenticator app / passkey / text message".

### 7.5 Why native and not a PWA

- **Reliable delivery on iOS:** Web Push to a PWA works on iOS only after "Add to Home Screen", which most users never do.
- **A hardware key gated by biometrics:** this is stronger than a browser credential, and attestation shows it is the genuine app.
- **One phone app:** merchants get the MiPay app anyway; adding a separate approver PWA would mean building and supporting push twice.

**Until the app ships,** push is simply not offered (§4.2), and users who need 2FA use TOTP or a passkey. Push is never the only factor.

## 8. Step-up for sensitive actions

**The rule belongs to the permission.** Each `@Perm` cell that needs it declares a step-up rule. It is published in the manifest (`fapi2-design.md` Appendix F), and both the gateway and the core enforce it:

| rule key | example cells | max age | bound to the action |
|---|---|---|---|
| `KYC_DECISION` | `kyc.task:authorize` | 5 min | **yes**: task id + outcome |
| `RIGHTS_CHANGE` | `org.rights:administer`, `rbac.assignment:*` | 5 min | yes: org id + new rights / role |
| `USER_ADMIN` | `org.user:create/delete/execute`, recovery decisions | 5 min | no |
| `ONBOARD` | `org.onboarding:create` | 15 min | no |
| `BANK_ACCOUNT` | `supplier.bank_account:modify/delete`, merchant payout accounts | 5 min | yes: account digest |
| `PAYOUT` | `merchant.withdrawal:execute`, supplier payments, disbursements | 0 (every time) | **yes**: amount + currency + payee |

**How it runs (RFC 9470 + RAR):**
1. The request lacks a fresh enough `auth_time` / `mfa_time`, or lacks a binding for this action. The gateway, or the core when the cell is checked there, answers `401 {"error":"step_up_required","acr_values":"<step_up_acr>","max_age":…,"authorization_details":…}`.
2. The portal opens the step-up dialog, and the gateway runs the FAPI flow again with those values. For bound rules, the PAR carries **RAR `authorization_details`** describing the action, e.g. `{"type":"payout","amount":"12400.00","currency":"NAD","payee":"…"}`.
3. The IdP asks for **only the second factor** (the session already has the password) and shows exactly what is being approved, on the page and on the push.
4. **A bound token is good for that one action only,** and the gateway replays the original request once.

**While `mfa.mode` is `off` or `shadow`,** the same flow runs with `step_up_acr = aal1-fresh` (password re-entry, §4.1). Binding still applies, so the token is still good only for that action.

**Push gives transaction signing for free:** for `PAYOUT` and `KYC_DECISION`, the signature covers the action hash, so phishing an approval for a different amount or payee fails.

## 9. Enrolment, and recovering passwords and 2FA

### 9.1 Enrolment

- **New users** set their password through the IdP set-up link the core sent them (`idp-rbac-cutover-plan.md` §1.4). If their profile needs 2FA and `mfa.mode=enforce`, the wizard follows immediately: choose a method, confirm it, then save the recovery codes (the user must tick "I saved them").
- **Existing users** get the grace logins when `enforce` is switched on (§4.1).
- **Managing factors:** the IdP Security page lists the methods, phones and passkeys (name, last used, Remove), remembered devices (Forget), and recovery codes (Regenerate). Removing the **last** factor of a user who needs 2FA is refused.

### 9.2 What can be lost, and the route for each

| User has lost… | Route | Who decides | After it |
|---|---|---|---|
| **Password** (still has 2FA) | Self-service **Forgot password** in the IdP (§9.3) | Nobody; the second factor proves it is the user | Sessions and remembered devices revoked; notices on every channel; 24 h cooling-off |
| **2FA** (knows the password) | Sign in with a **recovery code**, then enrol a new factor | Nobody | Notice; prompt to regenerate codes |
| **2FA, no recovery code** | **Assisted recovery** in the core, scope `MFA` (§9.4) | A second person (table in §9.4) | Factors wiped; set-up link; 72 h cooling-off |
| **Password and 2FA** | **Assisted recovery**, scope `FULL` | A second person, with an identity check | New password and 2FA through a single-use link; cooling-off |
| **Phone number** | **Assisted recovery**, scope `CONTACT` | A second person | New number verified by OTP; cooling-off |
| **The org's only admin left** | Core `ADMIN_RECOVERY` (a *replacement* admin is created with `createInOrg` and enrolled) | Parent org admin or platform | The new admin enrols 2FA |
| **Merchant transaction PIN** | OTP to the registered phone, then a new PIN (needs `comms.sms`) | Nobody | 24-hour hold on withdrawals |
| **Every platform staff account** | **Break-glass** (§9.6) | Two named officers, offline | Full audit; staff re-enrolled |

### 9.3 Self-service "Forgot password" (IdP; needs a live email or SMS channel)

```
IdP login page ─ "Forgot password?" ─▶ POST /account/password/forgot {identifier}
   always: 202 "If an account matches, we sent a reset link to its registered contact."
   (same response and timing whether or not the account exists)
   → IdP: CommsClient.Send(event=auth.password.reset, to=user:<uid>, data{link}, lane=CRITICAL, expiresAt=+30 min)

user opens /reset?token=…  → 2FA step (if enrolled: push / passkey / TOTP / SMS, purpose RECOVERY)
                           → new password → every session, remembered device and pending challenge revoked
                           → IdP event CREDENTIAL_RESET → core sets recovered_until = now + 24 h, and a notice goes out
```

- **Reset link:** 256-bit random, **stored hashed** in the IdP, single-use, valid 30 minutes, bound to the account and purpose. The raw link exists only inside the comms send request, and the comms plane builds it at dispatch, never storing it (H8 rule, `comms-design.md` §9).
- **No account enumeration:** identical responses and timing; 3 requests an hour per identifier and 10 an hour per IP, beyond which requests are silently dropped.
- **Recovery code** can stand in for the 2FA step only if the policy allows it. For staff it can't, so they go to §9.4.
- **Password rules:** at least 12 characters; not one of the last 5 (`password_history` in the IdP); checked against a bundled list of common and breached passwords; no forced periodic expiry.
- **Changing the password while signed in:** on the IdP Security page; needs the current password and a fresh second factor (or a fresh password while `off`). The same revoke-and-notify follows.

### 9.4 Assisted recovery (core `ACCOUNT_RECOVERY` request)

The core owns users, so assisted recovery is a core onboarding request, `ACCOUNT_RECOVERY`, with `scope` `MFA` | `FULL` | `CONTACT`. It reuses the request queue and the second-person rule.

**Who can raise it:**
- **the user,** from the IdP login page ("Can't get in?"). The IdP forwards `{identifier, callbackPhone, note}` to the core. The request is **unverified**, and nothing is revealed;
- **an admin of the user's org, or of its parent org,** from **Users → Recover access** (RBAC cell `org.user:execute` on that org);
- **platform staff,** for org users.

**Who decides** (RBAC permission `org.user.recovery:authorize`; never the requester, never the user):

| Account being recovered | Decided by |
|---|---|
| Org member | an `org_admin` of the same org or of the parent org, or a `platform_user_admin` |
| `org_admin` | an `org_admin` of the parent org, or a `platform_user_admin` |
| Platform staff with operational roles | a different `platform_user_admin`, with step-up |
| `platform_user_admin`, `platform_compliance_officer`, `platform_mlro`, `platform_senior_approver` | **two** different platform staff, one of them `platform_mlro` (or `platform_senior_approver` when recovering the MLRO) |

**Identity check before deciding:**
- The decider records a checklist, kept with the audit:
  - called back on the number on file (from the core), **not** the number given in the request;
  - confirmed name, date of birth and the last 4 digits of the ID number against the user's record (for org admins, against the KYC owners or controllers where the admin is one);
  - saw the ID document on a video call (required for scope `FULL` on staff accounts).
- The decision needs the decider's own step-up (`USER_ADMIN`).

**When it is approved:** the core calls `AuthProvider.resetCredential(subject, scope)`:
1. **The IdP revokes**, according to the scope: `MFA` → all factors; `FULL` → all factors, the password and the recovery codes; `CONTACT` → nothing in the IdP (the core changes the contact, §9.7). In every case it also revokes all sessions and remembered devices.
2. **The IdP returns a single-use recovery link** (24 h, stored hashed). The core sends it through comms **only to the verified contact**; a new number is used only for scope `CONTACT`, and only after an OTP has proved it.
3. **The core sets `recovered_until`** = now + 72 h (§9.5).
4. **The core notifies the user on every known channel,** old and new.

**When it is rejected, or not decided within 7 days:** nothing changes, and the user is told through the original channels.

### 9.5 Cooling-off after recovery (core)

`users.recovered_until` is set by the core. The length follows how much the recovery actually proved:

| Recovery | Cooling-off | Why |
|---|---|---|
| **Assisted recovery**, any scope (`MFA`, `FULL`, `CONTACT`) | **72 h** | This is the main account-takeover route: someone talks a helpdesk or an admin into it. 72 h spans a weekend, so a recovery done on Friday evening is still blocked when the real user sees the notices on Monday. One length for all scopes, so attackers don't pick the weakest |
| **Self-service reset**, second factor was a **passkey, TOTP or push** | **none**: notices only | The user proved a strong factor they still hold. A hold would add friction without adding security |
| **Self-service reset**, second factor was **SMS**, or 2FA not enrolled / `mfa.mode` not `enforce` | **24 h** | An emailed or texted link plus SMS can be taken over through the inbox or a SIM swap. 24 h gives the real user a day to see the notice and report it |
| **Platform staff**, any recovery | **72 h**, and no KYC decisions | Staff can approve customers and move platform state |
| **Contact change** (§9.7) | 24 h before the new contact can receive a recovery link | Unchanged |

The core works out the length from the IdP's `CREDENTIAL_RESET` event, which says which factor was used.

During that time:
- payouts, withdrawals, bank-account changes, rights changes, adding users and contact changes are **blocked** (`409 RECOVERY_COOLING_OFF`). This is a core guard on the same cells that carry step-up rules;
- KYC decisions by a recovered **staff** account are blocked;
- browsing, saving drafts and submitting KYC still work.

The decider can shorten the period only with a second approver, and the reason is recorded.

### 9.6 Break-glass (every platform staff account locked out)

**The two officers are roles, each with a named deputy** (four people in total, so one absence never blocks it):

| Officer | Who | Why this person |
|---|---|---|
| **A: authoriser** | the Head of Compliance (deputy: a `platform_compliance_officer`). **Not the MLRO** | Compliance owns who may access the platform. The MLRO must stay independent of access administration (the `{platform_mlro, platform_user_admin}` SoD set in `fapi2-design.md` §9.4) |
| **B: operator** | the engineering lead with production server access (deputy: the on-call platform engineer, `platform_engineer`) | Someone has to run the command on the server; they cannot authorise it alone |

**Assigned officers (placeholders until the names are confirmed):**

```yaml
break_glass:
  officers:
    authoriser:                     # Head of Compliance (not the MLRO)
      primary: { name: "<BG_AUTHORISER_PRIMARY>", key_id: "<BG_AUTH_PRIMARY_KEY_ID>", public_key: "<BG_AUTH_PRIMARY_PUBKEY>" }
      deputy:  { name: "<BG_AUTHORISER_DEPUTY>",  key_id: "<BG_AUTH_DEPUTY_KEY_ID>",  public_key: "<BG_AUTH_DEPUTY_PUBKEY>" }
    operator:                       # engineering lead with production server access
      primary: { name: "<BG_OPERATOR_PRIMARY>",   key_id: "<BG_OPER_PRIMARY_KEY_ID>", public_key: "<BG_OPER_PRIMARY_PUBKEY>" }
      deputy:  { name: "<BG_OPERATOR_DEPUTY>",    key_id: "<BG_OPER_DEPUTY_KEY_ID>",  public_key: "<BG_OPER_DEPUTY_PUBKEY>" }
  alert_contacts:
    - "<BG_ALERT_CEO>"
    - "<BG_ALERT_HEAD_OF_COMPLIANCE>"
    - "<BG_ALERT_MLRO>"
    - "<BG_ALERT_SECURITY_MAILBOX>"
  signature_max_age_seconds: 900
```

- **The values are placeholders.** The command refuses to run, and the IdP/core health check reports `break_glass: not configured`, while any `<BG_…>` value is still in place. So nobody mistakes a placeholder setup for a working one.
- **Only public keys go into config.** Private keys stay on the officers' hardware keys.

**Two-person control is enforced by code, not only by process:**
- `make break-glass USER=<username>` runs only with **two signatures** over `(username, timestamp, reason)`, one from an officer-A key and one from an officer-B key.
- The keys are hardware security keys registered in `break_glass.officers` (core config; public keys only), one per person.
- The command refuses a single signature, two signatures from the same side, or a timestamp older than 15 minutes.

**What the command does:**
- calls `resetCredential(subject, FULL)` for **one** staff account, and sets the 72 h cooling-off;
- writes `break_glass_used` with both officers, the reason and the target;
- refuses while any active staff account with an enrolled factor exists, so it can't become a shortcut around §9.4.

**Alerts:**
- go to the contacts in `break_glass.alert_contacts`: the CEO, the Head of Compliance, the MLRO and the security mailbox;
- are sent through comms (CRITICAL lane) **and** by a direct SMTP fallback, because break-glass may be needed exactly when the comms plane is degraded.

**Where things live:**
- the runbook is `docs/runbooks/break-glass.md` in the repo; it contains no secrets;
- a printed copy and the deputies' keys are kept in the office safe.

**Keeping it working:**
- a drill on staging every quarter, with the result recorded;
- the officer list is re-confirmed at the quarterly access review, and whenever someone in these roles leaves.

This is separate from the `platform_break_glass` *role* (`idp-rbac-cutover-plan.md` D10), which is emergency *access* by a working account.

### 9.7 Changing contact details (core)

The email and phone on file are what recovery trusts, and the core owns them, so the change is protected in the core:
- it needs a step-up (`USER_ADMIN` rule on `org.user.contact:modify` / self-service equivalent);
- the **new** contact must be confirmed (link or OTP through comms);
- a notice goes to the **old** contact, with a 24-hour window to block the change;
- the new contact can't receive a recovery link until those 24 hours have passed;
- on completion, the core updates the contact in comms and calls `updateLoginName` so the IdP's login phone matches.

## 10. Merchants on phone OTP

- **The login is the phone OTP** (`idp-rbac-cutover-plan.md` D8): in the IdP, hashed, 5 minutes, 5 attempts, rate-limited, sent through comms (`auth.otp.sms`). It is **offered only while `comms.sms` is live**; until then, merchants sign in with a password.
- **Step-up** for withdrawals and account changes needs a factor that isn't the login SMS: a 4–6-digit **transaction PIN** (`PinFactor`, bcrypt-hashed in the IdP, 5 attempts then locked), or push once a merchant app exists.

## 11. API

**IdP (`https://auth.afriqpay.com`, through the gateway's IdP vhost):**

| endpoint | purpose |
|---|---|
| `/authorize`, `/login` (+ internal step-2 and enrolment pages) | login with the policy of §5 and the flow of §6 |
| `GET /account/security` | factors, phones, remembered devices, recovery-code count, last 10 sign-ins |
| `POST /account/security/enroll/{method}/start` · `/finish` | TOTP QR, WebAuthn options/attestation, push pairing, PIN |
| `DELETE /account/security/factors/{id}` · `/devices/{id}` · `/trusted/{id}` | remove a factor, a phone or a remembered device |
| `POST /account/security/recovery-codes` | regenerate (fresh second factor) |
| `POST /account/password` | change password |
| `POST /account/password/forgot` · `GET/POST /reset?token=` | self-service reset (§9.3) |
| `POST /account/recovery/assist` | "Can't get in?": forwards to the core's `ACCOUNT_RECOVERY` |
| `GET /approver/challenges/{cid}` · `POST …/respond` · `POST /approver/pair` | phone side (device-bound) |
| `GET /mfa/readiness` (mTLS, admin) | per-profile mode, usable methods, waiting methods, enrolment counts (§4.3) |

**Contract additions** (core → IdP, mTLS; `idp-rbac-cutover-plan.md` §1.2): `setMfaProfile(subject, profile, orgRequires)`, `resetCredential(subject, scope)` (scopes `LINK`, `MFA`, `FULL`), `revokeSessions(subject)` (also clears remembered devices).

**Events** (IdP → core): `CREDENTIAL_RESET`, `MFA_ENROLLED`, `MFA_REMOVED`, `RECOVERY_CODE_USED`, `SUSPICIOUS_LOGIN`, `LOCKED`.

**Core:**

| endpoint | purpose |
|---|---|
| `POST /api/orgs/{id}/users/{userId}/recovery` `{scope}` | an admin raises `ACCOUNT_RECOVERY` |
| `POST /api/orgs/onboarding/{id}/decide` (existing) | decides `ACCOUNT_RECOVERY` too, with the checklist and step-up |
| `POST /api/me/contact` · `/confirm` | change email or phone, with the 24-hour hold (§9.7) |
| `PUT /api/orgs/{id}/mfa-required` | org override (stricter only) |

**Rate limits:** per IP, login 10/min, verify 10/min, challenge start 5/min; per user, push 3/10 min, SMS 3/10 min. The gateway gets matching per-IP limits for the IdP vhost.

## 12. Data model

**IdP database** (IdP DDL):

| table | purpose |
|---|---|
| `mfa_policies` | §5 |
| `accounts` | **add** `mfa_profile`, `org_requires_mfa`, `mfa_enrolled_at`, `mfa_grace_left` (from `idp-rbac-cutover-plan.md` P1.1) |
| `mfa_factors` | one row per enrolled factor: `subject`, `kind TOTP\|WEBAUTHN\|PUSH_NATIVE\|PIN`, `label`, encrypted `secret` (TOTP), `credential_id` / `public_key` / `sign_count` / `transports` (WebAuthn), `device_public_key` / `platform` / `attestation` (push), `pin_hash`, `last_step`, `created_at`, `last_used_at`, `revoked_at` (FCM / APNs tokens live in comms) |
| `mfa_recovery_codes` | `subject`, `code_hash`, `used_at` |
| `mfa_challenges` | `id`, `subject`, `login_tx`, `purpose LOGIN\|STEP_UP\|ENROLL\|OTP_LOGIN\|RECOVERY`, `method`, `number`, `decoys`, `code_hash`, `context` (jsonb), `action_hash` (from RAR), `status PENDING\|APPROVED\|DENIED\|WRONG_NUMBER\|EXPIRED\|CONSUMED`, `attempts`, `factor_id`, `expires_at`, `responded_at` |
| `mfa_trusted_devices` | §6 |
| `mfa_lockouts` | `subject`, `method`, `until`, `reason` |
| `password_history` | `subject`, `password_hash`, `created_at` (the last 5) |
| `credential_links` | one-time set-up / reset / recovery links: `subject`, `purpose SETUP\|PASSWORD_RESET\|RECOVERY`, `token_hash`, `expires_at`, `used_at` |

**Core database** (Flyway, numbered after the cut-over migrations):

| table | change |
|---|---|
| `users` | **add** `recovered_until`, `contact_hold_until` |
| `organizations` | **add** `mfa_required` |
| `onboarding_requests` | **add** kind `ACCOUNT_RECOVERY`, `target_user_id`, `scope MFA\|FULL\|CONTACT`, `verification` (jsonb checklist), `second_decider` |

Not used any more: `devices_sessions.mfa_method` (dropped with `devices_sessions`) and `activation_tokens` purposes (activation tokens are deleted; links live in the IdP's `credential_links`).

**Retention:** challenges are purged after 90 days; their audit rows are kept under normal audit retention.

## 13. Security invariants (no configuration can override)

1. **The second factor is never the same channel that proved the password.** For example, an email reset link can't satisfy 2FA.
2. **Every challenge is single-use, expires, and is bound to one login transaction or step-up request.**
3. **Push approvals count only when signed by the registered device key over the IdP's own challenge data.**
4. **Secrets are stored encrypted or hashed:** TOTP secrets with AES-GCM, recovery codes and PINs with bcrypt, SMS codes and links hashed. Nothing reversible is ever logged, and nothing secret is stored by comms.
5. **One lost secret never unlocks the account alone** (D7, §9).
6. **Policy can only make org users stricter.**
7. **Every MFA event is audited**, without codes or keys.
8. **Remembered devices never satisfy step-up,** and `PLATFORM-STAFF` never get remembered devices.
9. **Recovery links go only to contact details on file for more than 24 hours, or verified inside the recovery itself.** Every recovery, reset or contact change is notified on all known channels, old and new.
10. **Recovery never reveals whether an account exists.**
11. **An unavailable comms channel never weakens login.** It removes the factors that need it; it never removes the 2FA requirement. `enforce` is refused rather than allowing a profile with no usable method.
12. **The core's legitimacy check always wins.** A suspended user or org is refused on every request, whatever their factors (`idp-rbac-cutover-plan.md` §1.5 U3).

## 14. Screens

**Login, step 2** (IdP page, styled like the portals):

```
┌───────────────────────── Approve on your phone ─────────────────────────┐
│ We sent a request to “Selma’s Pixel 8”.                                  │
│                              ┌──────┐                                    │
│                              │  47  │   ← tap this number on your phone  │
│                              └──────┘                                    │
│ Waiting… 0:42                                                            │
│ [ ] Remember this browser for 30 days                                    │
│ Use another method:  Authenticator app · Passkey · Text message · Code   │
└──────────────────────────────────────────────────────────────────────────┘
```
Only usable methods are listed (§4.2).

- **Phone (MiPay app):** the request card, three large number buttons, **Deny**, and "This wasn't me".
- **Security page** (IdP, linked from every portal): factors, phones, remembered devices, recovery codes, last 10 sign-ins. While `off`/`shadow`, it shows "Set up 2FA now (optional)" or "2FA will soon be required".
- **Users** (admin and org portals, core): each user's 2FA profile and status (enrolled / due / not required, from IdP events), and **Recover access**.
- **Login page** (IdP): **Forgot password?** (only while email or SMS is live) and **Can't get in?**.
- **Recovery request review** (core onboarding queues): who, scope, checklist, second approver, cooling-off.
- **Cooling-off banner** on every portal page.
- **Step-up dialog** (portals): "Confirm it's you" with the action summary, then the gateway's step-up flow.
- **2FA readiness** (admin portal, from `GET /mfa/readiness`): §4.3.

## 15. Test plan (IdP suite + `TUat21MfaTests` + portal headless E2E)

**CI runs the whole suite with `mfa.mode=enforce` and every comms channel "live" in the sandbox,** plus the existing UAT packs with `mfa.mode=off`. Production runs the same code with the switch off.

- **Mode and readiness:**
  - `off`: no challenges; optional enrolment works; step-up asks for a fresh password and audits `step_up_without_mfa`;
  - `shadow` logs would-be prompts;
  - `enforce` applies grace logins, then forces enrolment;
  - with `comms.sms` not live, SMS is not offered, and merchant OTP login and Forgot password are hidden;
  - when a channel goes live, the factor appears within 60 s with no restart;
  - a profile with no usable method refuses `enforce`.
- **Profiles:** granting `org_admin` moves the account to `ORG-ADMIN` on the next reconcile; an org override makes the policy stricter, never looser; the strictest profile wins.
- **Login:**
  - password then push approve → `amr` with `mfa`, `acr=aal2`, `uid`;
  - deny → no token;
  - a wrong number, expiry or reuse is refused.
- **Push** (app test build with a software key standing in for Keystore / Secure Enclave): forged signature, another user's device, revoked device, tampered number or action hash, replayed respond and unpaired device are refused; flood limits and lockouts work; "This wasn't me" revokes all sessions and reaches the core as `SUSPICIOUS_LOGIN`.
- **Cooling-off lengths:** assisted recovery gives 72 h; a self-service reset with a passkey, TOTP or push gives none; one with SMS (or with 2FA off) gives 24 h.
- **Break-glass:** refused while any `<BG_…>` placeholder is configured, and the health check reports it; refused with one signature, two signatures from the same side, or a stale timestamp; refused while an active staff account with a factor exists; with valid signatures it is audited with both officers, and the alert goes out even with comms stopped.
- **TOTP:** RFC 6238 vectors, ±1 step drift, replay refused, secret encrypted.
- **WebAuthn:** virtual authenticator (headless Chrome DevTools protocol); a counter regression is refused; the RP id is the IdP host.
- **SMS:** hashed, 5 min, 5 attempts, rate-limited; staff can't use it; never sent after `expiresAt`.
- **Recovery codes:** single use; regeneration invalidates; can't satisfy `PAYOUT` or `KYC_DECISION`.
- **Step-up:**
  - a KYC decision without step-up gets `step_up_required`;
  - a bound token for task A can't approve task B;
  - a payout for N$100 can't approve N$1,000;
  - a bound token works once;
  - a remembered device doesn't help;
  - in `off` mode the same flows pass with a fresh password.
- **Forgot password:** identical responses and timing; limits; single-use 30-minute link; 2FA still required; revocation and notices; `CREDENTIAL_RESET` sets 24 h cooling-off in the core.
- **Assisted recovery:** requester or target can't decide; checklist and step-up required; privileged staff need two deciders; approval calls `resetCredential` with the right scope; the link goes only to the verified contact; the login-page request reveals nothing.
- **Cooling-off, contact change, break-glass:** as §9.5–9.7.

## 16. Implementation phases

These run in the IdP after `idp-rbac-cutover-plan.md` P1 (credential accounts) and alongside its later phases. **Everything ships with `mfa.mode=off`.**

1. **Foundations (no comms needed):**
   - IdP 2FA tables, policy master, boot validator, `mfa.mode`, `FactorReadiness` with `CommsClient` (sandbox + comms plane implementations);
   - login step 2 and the enrolment wizard;
   - **TOTP, WebAuthn passkeys, recovery codes, merchant PIN**;
   - Security page; core `setMfaProfile` and the reconcile hook;
   - readiness page; audit events.
2. **Step-up:**
   - step-up rules on `@Perm` cells and in the manifest; gateway RFC 9470 handling and RAR binding; the portals' step-up dialog;
   - `step_up_acr` published by the IdP (`aal1-fresh` until enforced).
3. **Recovery:**
   - core `ACCOUNT_RECOVERY` with checklist and dual approval; `resetCredential` scopes; cooling-off; protected contact changes; break-glass runbook;
   - self-service Forgot password (lights up when email or SMS is live).
4. **SMS factor and merchant phone-OTP login:** code-complete, tested in the sandbox; **light up when comms P1 is live.**
5. **Push in the MiPay app:** pairing, hardware-backed device key with attestation, number matching, flood protection, FCM / APNs registration with comms. **Lights up when comms P2 is live and the app is released.**

Switch-on follows §4.4.

## 17. Decisions on the former open items (2026-11-11)

| # | Item | Decision |
|---|---|---|
| 1 | Native app | **Yes, one is on the roadmap.** Push goes straight to the native MiPay app; no PWA approver is built (D4, §7) |
| 2 | Push through Google and Apple services | **Accepted.** Only a challenge id and a fixed text pass through them (D8) |
| 3 | Regulatory | **Accepted:** push, TOTP and passkeys all satisfy AAL2 for privileged and payment access, so a passkey is not mandatory for payouts. Password re-entry is acceptable as the interim step-up while `mfa.mode` is `off` or `shadow` (§4.1) |
| 4 | Cooling-off lengths | **Adopted (confirmed 2026-11-11):** 72 h after any assisted recovery and for staff; none after a self-service reset proved with a passkey, TOTP or push; 24 h after one with SMS or without 2FA (§9.5) |
| 5 | Break-glass officers | **Recommended and adopted:** two roles with a deputy each (Head of Compliance, not the MLRO; engineering lead with server access); two hardware-key signatures enforced by the command; alerts through comms and direct SMTP; runbook in the repo, printed copy and keys in the safe; quarterly drill (§9.6). Two people are assigned (plus deputies). They are recorded as **placeholders** (`<BG_…>`) in `break_glass.officers` until the names and key ids are confirmed, and the command refuses to run while any placeholder remains |

The SMS provider, WhatsApp OTP and email delivery are comms-plane decisions (`comms-design.md` §14), not 2FA ones.
