# Goal: complete P3 on a clean test installation

## Outcome

Complete P3 of [the IdP/RBAC cutover plan](docs/plans/idp-rbac-cutover-plan.md): the core owns users and their organisation membership and status; the IdP owns credentials and authentication; the core resolves every gateway identity to an authorised core user; and lifecycle changes reach the IdP and RBAC reliably. Use the existing Java comms sandbox to retrieve setup links and SMS codes in tests, and complete user actions through the normal IdP flow. TOTP provides a working second factor without comms.

This document replaces the migration assumptions and P3 acceptance gate in the cutover plan. The rest of the plan remains in force unless explicitly changed below. Unchecked items are still required; the comms and IdP MFA capabilities named below are already implemented.

## Clean-start decision

Existing users, organisations, credentials, test sessions, RBAC tenants, assignments, grants and dependent business records are disposable **in the designated test environments**. Recreate them instead of migrating them. Do not delete data in an environment until it is identified as disposable and the reset scope and dependencies are understood. Provide an explicit, repeatable reset/reseed operation; do not put destructive resets in ordinary application startup or Flyway migrations.

- Do not implement P3.10, P3.10a or P3.11 historical backfills, password-hash import, orphan resolution or legacy role/right conversion. Apply the org FK and `NOT NULL` constraint on the clean database.
- Recreate the platform root, initial platform administrator, representative organisations, users and RBAC data through supported bootstrap/lifecycle interfaces. Bootstrap must be repeatable and scoped to non-production; it must not depend on an existing administrator or an IdP demo user masquerading as a core user.
- Reset or reseed dependent KYC, onboarding, sessions, closures, notifications and business fixtures consistently; no dangling references or leftover IdP/RBAC data. Existing non-test data and environments are outside this reset.
- Retire lazy org binding and stop relying on preserved legacy credentials. Existing tests and developer workflows must be updated to work with the fresh bootstrap.

## Implementation checklist

### Contracts, storage and lifecycle

- [x] Implement and test `AuthPluginContracts`, the provider registry and the Trustplane mTLS client for enrol, reset scopes, enable/disable, session revocation, login changes, MFA profile, unlink and assurance mapping. Validate the identity/provider switches and HTTPS provider URLs at startup.
- [x] Add and verify the additive PostgreSQL identity foundation: `identity_links`, `auth_outbox`, `auth_events_seen`, link uniqueness, audit columns, and `ARCHIVED` user status. The V15 migration passed against a disposable copy of the current schema.
- [ ] Wire lifecycle changes into the durable provider-command outbox and event store, including leasing, retries, deduplication and state transitions. Enforce a non-null, immutable home-org FK after the clean reset and all user writers have moved to the lifecycle service.
- [ ] Implement `UserLifecycleService` as the only path for creation, user status and login/contact changes. Require an existing eligible org; reject org moves. Route onboarding, team management, platform staff, recovery, provisioning, KYC and all other user writers through it. Make user-management actions org-scoped and check the relevant org permission.
- [ ] Persist core changes and outbox commands atomically; process commands with bounded retries, idempotency, observability and recovery after restart. Enrolment and link delivery must tolerate lost responses and retries without silently invalidating a delivered link or persisting raw links in durable core records. Define the protocol with the IdP and comms, and test the failure windows.
- [ ] Replace activation on onboarding/go-live with provider enrolment and setup-link delivery. Enrol a KYC applicant when the application starts; on go-live replace `kyc_applicant` with `org_admin`, without creating a duplicate account. Implement setup-link resend and credential reset. Expose the P3 admin access view and link/session actions.
- [ ] Provision and update comms contacts as the core's responsibility. Reconcile MFA profile from RBAC roles and org policy, and synchronize fresh assignments and rights with RBAC. Define the new DIRECT/CEILING representation and delegation behavior against the actual RBAC grant API; no historical conversion is required.

### Identity, organisation and KYC

- [ ] Implement `IdentityResolver`: verify `(issuer, subject)` link, signed `uid`, link and user status and home org eligibility. Fail closed for missing users, links or orgs. Prevent any stale allow after a lifecycle change on the next request, including across backend instances; use an authoritative check or reliable cross-instance invalidation rather than relying solely on a five-second cache.
- [ ] Resolve the pending-applicant exception consistently across resolver, RBAC, org access and reconciliation: after credential setup the applicant can use only their own application's KYC endpoints while the org is pending, provided no applicable hold blocks them. Other users/actions require an active operational org. A pending org must not cause reconcile to disable its eligible applicant.
- [ ] Make operational checks account for the relevant org and ancestor lifecycle and holds, including explicit `KYC_LAPSED` system holds and their lift on successful review. Do not copy temporary org suspension onto users. Platform staff remain governed by their own home org when reviewing another org.
- [ ] Implement `AuthSyncService` and its nightly repair. Reconcile on user changes, org lifecycle and hold changes, and KYC outcomes over the affected subtree. Disable and revoke sessions for ineligible links; re-enable only when all user, link and org conditions permit. Permanent rejection, block or closure archives all affected users, revokes links and removes the relevant RBAC assignments and grants.
- [ ] Implement the authenticated IdP event endpoint: validate mTLS caller, JWT signature, issuer, audience and time; deduplicate event IDs transactionally. Apply `CREDENTIAL_SET` to activate the matching link and pending user, handle credential reset and lock/unlock audit/notification, and handle retries and out-of-order or stale events safely. Reconcile missed events and pending enrolments.
- [ ] Replace role-based audit identity with actor kind and include link, authentication assurance and RBAC decision ID where available; preserve object-level audit and maker/checker rules.

### Gateway integration needed for P3

- [x] Extend and test the gateway's signed context with `iss`, `sub`, `uid`, `sid`, `acr`, `amr`, `auth_time` and `client_id`, including token-authenticated MiPay routes without a gateway RBAC cell. Verify signature rejection and spoofed-header stripping.
- [ ] Complete gateway-to-core routing and end-to-end tests for every relevant authenticated MiPay route, including missing-`uid` refusal.
- [ ] Implement the core gateway identity filter, verify HMAC and freshness, resolve the link and build the principal. A missing, invalid or forged context must fail closed in gateway mode. Provide a test gateway-context helper and an end-to-end route for P3 validation.
- [ ] Run the P3 acceptance flow with gateway identities. Full portal BFF routing and portal conversion remain P4/P5.

### Comms and MFA used by P3 tests

The Java `comms-service` already accepts messages and core-owned contacts over
mTLS, reports capabilities, and writes rendered development SMS/email to
`sim_outbox` and a labelled log. `IDP_MFA_MODE=enforce` already requires a verified
TOTP or eligible SMS second factor before issuing a session. No automatic
approval service or separate MFA simulator is needed for P3.

- [ ] Have the core register and update each user's comms contacts as part of lifecycle changes. Use the existing mesh APIs and development outbox to retrieve setup/reset links and SMS codes in tests. Submit those links and codes through the ordinary IdP endpoints; delivery alone must never activate an account or verify MFA.
- [ ] Verify the P3 journey with `IDP_MFA_MODE=enforce`: TOTP setup and login work when comms is absent; SMS login works when the sandbox has a contact; missing contacts, unavailable channels, wrong/expired codes and replay fail closed. Keep sandbox output confined to non-production. Real SMS/email delivery and additional MFA methods remain separate comms/2FA work.

## Acceptance: P3 is done

- [ ] A clean reset and reseed runs twice reproducibly in a disposable environment. The platform admin and org fixtures have valid org membership, links and RBAC assignments; no legacy user/password/org migration is needed.
- [ ] On a real scratch database and mesh, run: create pending org and applicant → enrol → read the setup link from the Java sandbox outbox → complete setup through the IdP → sign in and verify TOTP → authenticated, KYC-only applicant → KYC go-live → org admin. Verify non-KYC access is denied before go-live and the normal event, link and user states are correct.
- [ ] Verify unknown/mismatched/disabled links and users, absent orgs, forged or stale contexts, duplicate and invalid events, wrong and expired OTPs/links, and repeat actions are refused; verify retry, restart, missed-event and reconciliation recovery.
- [ ] Verify parent holds, nested holds, lift, individual suspension, KYC lapse, rejection/closure, subtree archive, platform reviewer independence and immediate refusal across backend instances. Verify no forbidden user creation or org move.
- [ ] Verify provider/RBAC/comms outages: core state remains authoritative, queued operations recover and the user is refused when appropriate. Verify link delivery cannot be silently invalidated by a retry; TOTP remains available without comms.
- [ ] Run the relevant backend, IdP, gateway, RBAC and comms tests, including Postgres-backed integration tests (not skipped), and the applicable UAT workflows using gateway contexts. Record actual results and any remaining issues; no P3 requirement above is left as a stub.

P3 completion does not by itself mean portal cutover or RBAC enforcement is complete. P4–P7 and their applicable release gates still require their own implementation and verification; there is no claim of a seven-day production shadow period where no real traffic exists.
