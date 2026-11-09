# Merge notes — reference zip → sme (2024 session)

Source studied: `mipay-sme-ecosystem.zip` (Spring Boot 3.5.16 / Angular 21 reference).
This repo keeps its own architecture (Spring Boot 3.5.4 / Java 21 / Angular 20,
strict atomic design, DTO-only API). Only proven patterns were ported.

## Ported (all verified green)

| # | Item | Where | Notes |
|---|------|-------|-------|
| 1 | jti-based session revocation | `JwtService` (`IssuedToken`), `JwtAuthFilter`, `AuthService`, `DeviceSessionRepository` | DB stores SHA-256(jti) only; every request re-checks the session; pre-jti tokens refused |
| 2 | SHA-256 util | `common/HashUtil.java` | replaces the old `token.hashCode()` hack |
| 3 | Async bank simulator | `service/BankSimulator.java`, `AppProperties$Sim` | virtual threads, scenarios `MANUAL/CONFIRM/DUPLICATE/TIMEOUT/LATE_CONFIRM/FAIL`; **MANUAL preserves the original inline behavior**; per-request `X-Sim-Scenario` header on merchant payments + invoice pay |
| 4 | Access console (X04) | `AdminService.accessUsers/revokeUserAccess`, `web/AdminController`, `admin-access` UI | revoke → kills all device sessions + raises CRITICAL incident (15-min ack SLA) |
| 5 | Bank-sim controls | `AdminService.simControls/setSimControls`, simulator UI card | default scenario, bank-reference seq, enabled flag |
| 6 | DB CHECK constraint approver≠requester | `db/migration/V2__constraints.sql` (`chk_bank_approver_not_requester`) | belt-and-braces under the ops approval lock |
| 7 | Public self-onboarding screens M02/S02 | `features/onboarding/onboarding.ts` on `mp-entity-form` | backend `/api/onboarding/**` already permitAll; PENDING → TPTS ops approve |
| 8 | Per-class UAT isolation | `UatSupport.cleanSharedDatabase()` | wipes PUBLIC tables + reseeds via `DataSeeder` (mirrors the reference's `flyway clean+migrate` per class); killed the cross-class H2 flakes |
| 9 | `docker-compose.yml` env interpolation + `.env.example` + `Makefile` | root | `${POSTGRES_PASSWORD:-mipay}`, `APP_ENVIRONMENT`, `APP_JWT_SECRET`; make targets up/down/rebuild/logs/backend-test/jar/frontend-build/reset-db/clean |
| 10 | Load test script | `docs/load-test.py` | adapted to our endpoints; verified 10 TPS, 0 errors, p50 ≈ 31 ms |

## Deliberately NOT ported (ours is better / conflicts)

- Their schema/seed SQL and hand-built Angular screens — no design system; ours
  is strictly tokens → atoms → molecules → organisms.
- Their `HealthEvent`/metrics table — ours covers health via `AdminService.health`.
- Their 55-test UAT pack — ours is 136 tests (2.5×), fully green (3 skipped by design).

## Concurrency hardening done alongside (was in the plan)

All contested money paths now take DB-level pessimistic row locks
(`PESSIMISTIC_WRITE`) + unique constraints as backstop — see
`docs/UAT-BACKEND-TEST-PACK.md` resolution notes (BUG-R1..R13 all fixed;
full pack 136/136 green, 3 skipped by design).

## Post-merge fixes found by browser verification

- `MerchantController.paymentDetail` mapped a lazy `merchant` proxy with
  `open-in-view: false` → 500. Fixed with a fetch-join query
  (`PaymentRepository.findDetailWithMerchant`), one query.
- `TokenView` was missing `role` → login screen's `homeForRole()` routed
  everyone back to /login. Restored `role` in the token DTO.
- `mp-table.rowId` now falls back to `userId` so the directory table rows are
  selectable.
