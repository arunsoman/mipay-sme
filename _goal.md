# Goal: complete P3 on a clean test installation

## Outcome

Complete P3 of [the IdP/RBAC cutover plan](docs/plans/idp-rbac-cutover-plan.md): the core owns users and their organisation membership and status; the IdP owns credentials and authentication; the core resolves every gateway identity to an authorised core user; and lifecycle changes reach the IdP and RBAC reliably. Provide a simulated communications and positive-action flow so the whole P3 journey can be exercised without real SMS, email or a real second factor.

This document replaces the migration assumptions and P3 acceptance gate in the cutover plan. The rest of the plan remains in force unless explicitly changed below. This is an implementation goal, not a claim that these features already exist.

## Clean-start decision

Existing users, organisations, credentials, test sessions, RBAC tenants, assignments, grants and dependent business records are disposable **in the designated test environments**. Recreate them instead of migrating them. Do not delete data in an environment until it is identified as disposable and the reset scope and dependencies are understood. Provide an explicit, repeatable reset/reseed operation; do not put destructive resets in ordinary application startup or Flyway migrations.

- Do not implement P3.10, P3.10a or P3.11 historical backfills, password-hash import, orphan resolution or legacy role/right conversion. Apply the org FK and `NOT NULL` constraint on the clean database.
- Recreate the platform root, initial platform administrator, representative organisations, users and RBAC data through supported bootstrap/lifecycle interfaces. Bootstrap must be repeatable and scoped to non-production; it must not depend on an existing administrator or an IdP demo user masquerading as a core user.
- Reset or reseed dependent KYC, onboarding, sessions, closures, notifications and business fixtures consistently; no dangling references or leftover IdP/RBAC data. Existing non-test data and environments are outside this reset.
- Retire lazy org binding and stop relying on preserved legacy credentials. Existing tests and developer workflows must be updated to work with the fresh bootstrap.

## Implementation checklist

### Contracts, storage and lifecycle

- [ ] Implement `AuthPluginContracts`, provider registry and Trustplane provider with mesh-authenticated operations: enrol, reset scopes, enable, disable, revoke sessions, update login details, set MFA profile, unlink and map assurance. Reuse/refactor the mesh TLS client so it is accessible from the auth package. Add the planned identity, provider and RBAC switches, with safe defaults and startup validation.
- [ ] Add `identity_links`, a durable retryable provider-command outbox and deduplicated event storage; add `ARCHIVED` to user status. Enforce unique `(issuer, subject)`, one Trustplane link per user, valid state transitions and an immutable, non-null home organisation FK. Support all planned audit timestamps and `idp_enabled`/assurance state.
- [ ] Implement `UserLifecycleService` as the only path for creation, user status and login/contact changes. Require an existing eligible org; reject org moves. Route onboarding, team management, platform staff, recovery, provisioning, KYC and all other user writers through it. Make user-management actions org-scoped and check the relevant org permission.
- [ ] Persist core changes and outbox commands atomically; process commands with bounded retries, idempotency, observability and recovery after restart. Enrolment and link delivery must tolerate lost responses and retries without silently invalidating a delivered link or persisting raw links in durable core records. Define the protocol with the IdP and comms, and test the failure windows.
- [ ] Replace activation on onboarding/go-live with provider enrolment and setup-link delivery. Enrol a KYC applicant when the application starts; on go-live replace `kyc_applicant` with `org_admin`, without creating a duplicate account. Implement resend/reset and reviewed replacement-admin recovery. Expose the P3 admin access view and link/session actions.
- [ ] Provision and update comms contacts as the core's responsibility. Reconcile MFA profile from RBAC roles and org policy, and synchronize fresh assignments and rights with RBAC. Define the new DIRECT/CEILING representation and delegation behavior against the actual RBAC grant API; no historical conversion is required.

### Identity, organisation and KYC

- [ ] Implement `IdentityResolver`: verify `(issuer, subject)` link, signed `uid`, link and user status and home org eligibility. Fail closed for missing users, links or orgs. Prevent any stale allow after a lifecycle change on the next request, including across backend instances; use an authoritative check or reliable cross-instance invalidation rather than relying solely on a five-second cache.
- [ ] Resolve the pending-applicant exception consistently across resolver, RBAC, org access and reconciliation: after credential setup the applicant can use only their own application's KYC endpoints while the org is pending, provided no applicable hold blocks them. Other users/actions require an active operational org. A pending org must not cause reconcile to disable its eligible applicant.
- [ ] Make operational checks account for the relevant org and ancestor lifecycle and holds, including explicit `KYC_LAPSED` system holds and their lift on successful review. Do not copy temporary org suspension onto users. Platform staff remain governed by their own home org when reviewing another org.
- [ ] Implement `AuthSyncService` and its nightly repair. Reconcile on user changes, org lifecycle and hold changes, and KYC outcomes over the affected subtree. Disable and revoke sessions for ineligible links; re-enable only when all user, link and org conditions permit. Permanent rejection, block or closure archives all affected users, revokes links and removes the relevant RBAC assignments and grants.
- [ ] Implement the authenticated IdP event endpoint: validate mTLS caller, JWT signature, issuer, audience and time; deduplicate event IDs transactionally. Apply `CREDENTIAL_SET` to activate the matching link and pending user, handle credential reset and lock/unlock audit/notification, and handle retries and out-of-order or stale events safely. Reconcile missed events and pending enrolments.
- [ ] Replace role-based audit identity with actor kind and include link, authentication assurance and RBAC decision ID where available; preserve object-level audit and maker/checker rules.

### Gateway integration needed for P3

- [ ] Pull gateway context v2 forward: carry verified `iss`, `sub`, `uid`, `sid`, `acr`, `amr`, `auth_time` and `client_id`; strip incoming `X-SME-*`; sign and forward context for every relevant authenticated MiPay route, not only routes with an RBAC permission. Reject missing `uid` for these routes.
- [ ] Implement the core gateway identity filter, verify HMAC and freshness, resolve the link and build the principal. A missing, invalid or forged context must fail closed in gateway mode. Provide a test gateway-context helper and an end-to-end route for P3 validation.
- [ ] Choose and document whether a temporary dual mode is useful for development. It is not a migration requirement; if implemented, fall back to legacy authentication only when the gateway header is absent, never when it is present but invalid. Full portal BFF routing and portal conversion remain P4/P5 unless pulled forward separately.

### Simulated comms and positive user actions

The development comms plane is now the Java `comms-service`, with a sandbox provider,
`sim_outbox`, message APIs, capabilities and a sandbox outbox API. Extend these
interfaces for any further simulator work. Message delivery is simulated; a user
action or second-factor approval still requires the IdP's verification step.

- [ ] Expose the comms APIs to authorised test clients over the existing gateway/mesh, with clear environment configuration and access control. Continue to support core-owned contact registration, message submission/status and capabilities. In the simulator, print rendered messages to a clearly labelled development console and expose them through the sandbox outbox; report simulated delivery as successful. Keep codes and links confined to the test-only output and avoid logging them in normal environments.
- [ ] Add an explicit, configurable **auto-positive-action** simulator for supported actions: consume a setup/reset link with a test credential, submit a received OTP or approve a simulated challenge using the owning IdP's normal test interfaces and state transitions. A successful delivery alone must not mark a user activated or claim a factor was verified. Record which action was simulated and expose its result for tests. Define unsupported actions as unsupported, not successful.
- [ ] Run automatic actions asynchronously with bounded retries and idempotency; handle expiry, replay, invalid links/codes and provider failure. Offer a manual mode and failure injection so negative and retry paths remain testable. Do not auto-approve real financial, KYC or administrative decisions merely because a message was sent.
- [ ] The simulator and any test credential/approval hooks must be explicitly enabled only in designated non-production environments, unavailable and refused at startup in production, authenticated, and unreachable from public production routes. Label simulated assurance and do not assert real AAL2 or a real enrolled factor. Full 2FA implementation, real channel delivery and real assurance remain the separate 2FA/comms tracks.

## Acceptance: P3 is done

- [ ] A clean reset and reseed runs twice reproducibly in a disposable environment. The platform admin and org fixtures have valid org membership, links and RBAC assignments; no legacy user/password/org migration is needed.
- [ ] On a real scratch database and mesh, run: create pending org and applicant → enrol → simulated delivery and positive setup action → authenticated, KYC-only applicant → KYC go-live → org admin. Verify non-KYC access is denied before go-live and the normal event, link and user states are correct.
- [ ] Verify unknown/mismatched/disabled links and users, absent orgs, forged or stale contexts, duplicate and invalid events, wrong and expired OTPs/links, and repeat actions are refused; verify retry, restart, missed-event and reconciliation recovery.
- [ ] Verify parent holds, nested holds, lift, individual suspension, KYC lapse, rejection/closure, subtree archive, platform reviewer independence and immediate refusal across backend instances. Verify no forbidden user creation or org move.
- [ ] Verify provider/RBAC/comms outages: core state remains authoritative, queued operations recover and the user is refused when appropriate. Verify link delivery cannot be silently invalidated by a retry and the simulator can exercise both success and failure.
- [ ] Run the relevant backend, IdP, gateway, RBAC and comms tests, including Postgres-backed integration tests (not skipped), and the applicable UAT workflows using gateway contexts. Record actual results and any remaining issues; no P3 requirement above is left as a stub.

P3 completion does not by itself mean portal cutover or RBAC enforcement is complete. P4–P7 and their applicable release gates still require their own implementation and verification; there is no claim of a seven-day production shadow period where no real traffic exists.
