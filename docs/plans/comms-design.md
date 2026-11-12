# Comms Plane: email, SMS, push, WhatsApp and in-app as a service

> **Implementation status (2026-11-12):** The development Compose service now builds
> `trustplane/java/comms-service` (Java 21 / Spring Boot 4). Its B0 mesh API accepts
> messages and core-owned contacts, encrypts contact addresses and queued data,
> processes a leased Postgres job, reports channel capabilities and writes/prints
> simulated SMS/email deliveries. It uses the same mTLS identity and data-key format
> as the former Go service. No live SMS/email provider is configured; `production`
> refuses sandbox providers. The broader APIs and providers in this design remain
> planned. The old Go source remains temporarily for comparison and tests.

> **Tests without a simulator:** Unit tests must use explicit provider fakes and
> never require a simulator process. Integration tests must cover absent providers,
> timeouts, expiry and unavailable channels as well as sandbox success; an unavailable
> channel reports not live and must not silently complete a challenge. Tests of real
> 2FA must exercise the IdP's challenge verification rather than infer authentication
> from a delivery receipt. Sandbox end-to-end tests are a separate, explicitly enabled
> suite, and a skipped database or simulator suite must be reported as skipped.

> Status: **PARTIALLY IMPLEMENTED**. Date: 2026-11-11; implementation inventory updated 2026-11-12.
> Scope: one internal service that every MiPay component (SME backend, IdP/2FA, KYC,
> payments, inventory, portals) uses to send messages to people, plus the partner-facing
> APIs a tenant needs to send its own messages.

---

## 0. Where we are today

| What exists | Where | Problem |
|---|---|---|
| `notification_messages` outbox (EMAIL / SMS, `QUEUED` → `SENT`) | `domain/org/NotificationMessage.java`, written by `ActivationService` | Nothing dispatches it. There are no providers, retries, delivery receipts or status beyond SENT. |
| `notification_templates` (SMS, WHATSAPP, SPEAKER, EMAIL, PUSH; `{token}` bodies; locale) | `domain/admin/NotificationTemplate.java` | Templates have no versions, no per-tenant override, no preview and no validation of variables. |
| `sim_outbox` (sandbox only, raw token) | `domain/org/SimOutbox.java` | This works and must keep working: sandbox/UAT reads codes from it (H8/M8). |
| 2FA plan (§3.4): `SmsGateway` adapter "queues in the existing outbox" | `docs/plans/2fa-design.md` | It needs a real, prioritised SMS path with an expiry (5-minute OTP). |
| Khata reminders, speaker payment announcements (PRD UC-KHATA, UC-ACC-5) | PRD | These are marked "SIMULATED" and have no real channel. |

The rule that an outbox row **never stores a secret** (the H8 rule: links and codes are
built at dispatch, not stored) carries over to the new design as a hard requirement (§9).

---

## 1. Goals, non-goals and service targets

**Goals**
1. One API for every channel: `EMAIL`, `SMS`, `PUSH` (FCM, APNs, Web Push), `WHATSAPP`,
   `IN_APP` (inbox and real-time), `VOICE`/`SPEAKER` (soundbox announcements), `WEBHOOK`
   (machine recipients).
2. The caller says **what** happened and **who** it is for. The service decides **how**:
   which channel, template, locale, provider and fallback, based on preferences and policy.
3. Nothing accepted is ever lost. Delivery is at-least-once, the caller's idempotency key
   prevents duplicates, and every state change can be audited.
4. One tenant's bulk send can never delay another tenant's OTP.
5. Providers are pluggable and fail over automatically. No provider is a single point of failure.
6. It runs on today's single box (afriqpay docker-compose) and scales out without a rewrite.

**Non-goals (v1)**
- A full marketing suite (journey builder, segmentation UI). We provide **broadcasts to
  audiences** and the hooks for these features, not the product.
- Generating or verifying OTPs. That belongs to the IdP/2FA. Comms only *delivers* a
  secret it is handed and never stores it (§9).
- Running our own SMTP MTA or SMSC. We use providers, possibly a local SMS aggregator.

**Service targets** (proposed; confirm with business)

| Lane | Accept (API p99) | Handed to provider (p95) | Availability of accept |
|---|---|---|---|
| `CRITICAL` (OTP, security alerts, login push) | < 50 ms | < 3 s | 99.95 % |
| `TRANSACTIONAL` (receipts, KYC status, activation) | < 100 ms | < 30 s | 99.9 % |
| `BULK` (campaigns, digests, reminders) | < 200 ms (per batch) | best effort, within the send window | 99.5 % |

---

## 2. Use-case catalogue

This is the full list of cases the design must cover. **Bold** items are v1. The rest must
be possible without changing the schema.

### 2.1 Identity and security (lane CRITICAL)
- **SMS / email OTP for login, step-up and transaction signing**. These have a 5-minute
  validity, after which the message is never sent late (`expiresAt`).
- **Push approval with number matching** (2FA §7): data push to the native MiPay app, plus a
  silent cancel push when the challenge is answered elsewhere.
- **Account activation and resend** (today's `ActivationService`).
- **Password reset link**, **"new sign-in from a new device"**, **"this wasn't me"
  notification to org ADMINs**, recovery-code used, 2FA changed, API key created or revoked.
- Email change: notify the **old** address too (anti-takeover).

### 2.2 Onboarding and KYC (TRANSACTIONAL)
- **KYC submitted, returned for correction (with field list), approved, rejected**, document
  expiring (ID or licence expiry reminders at T-30/T-7/T-0), periodic re-KYC due.
- **Invitation of a child org or user** (manufacturer → distributor → supplier tree).
- Maker-checker: "approval waiting for you", with escalation if nothing happens within N hours.

### 2.3 Payments and money (TRANSACTIONAL, some CRITICAL)
- **Payment received receipt** (merchant and payer), **speaker/soundbox announcement**
  ("N$ 250 received"), refund issued, chargeback opened, settlement credited, settlement or
  payout failed, low float/balance, fund advance offered or due, invoice due or overdue.
- **Khata credit reminders** to the merchant's customers over SMS/WhatsApp. These go to
  people who are **not** platform users, so consent and opt-out are mandatory (§6).
- Daily or weekly statement email with a PDF attachment (signed URL, not an inline blob).

### 2.4 Operations and supply chain (TRANSACTIONAL / BULK)
- Order placed, shipped or delivered between org-tree members, low stock, reorder suggestion.
- Plugin/connector failures, deploy result, certificate expiry (trustplane PKI), webhook
  endpoint disabled after failures.
- Ops paging for MiPay staff (in v1 this is an email/SMS escalation; a pager integration is
  only a provider).

### 2.5 Engagement (BULK)
- Broadcast to an audience (e.g. all suppliers under manufacturer X), scheduled campaigns,
  digests that fold many events into one message, product announcements, surveys.
- A/B template variants, with send-time windows per recipient's timezone.

### 2.6 Inbound and two-way
- **SMS STOP/HELP keywords** → suppression. WhatsApp replies → callback to the owning service.
- **Email bounces and complaints** → suppression. Replies to `reply+<msgId>@` addresses →
  routed to the owning service (e.g. support ticket).
- **Delivery receipts (DLR)** from every provider → message status.
- Push token invalidation from FCM/APNs → device record disabled.

### 2.7 Platform and tenant features
- **Multi-tenant white-label:** per-tenant sender ID, email domain (DKIM), app (push
  credentials), logo and colours in email layouts. afriqpay is the first tenant.
- **Bring your own provider:** a tenant may plug in its own SMS/email account. Credentials
  are encrypted and never readable back (§9).
- **Sandbox/UAT mode:** no real sends. Messages go to the sim outbox and can be inspected
  in the portal. **Test-recipient allow-list** for staging.
- **Cost and billing:** per-message cost (SMS segments, WhatsApp conversation category) is
  recorded per tenant, with quotas and invoicing export.
- Compliance evidence: who was sent what, when, through which provider, delivered or not,
  and the consent basis. This is exportable for audits (it fits the UAT evidence pack).
- Data rights: export or erase a person's message history (PII retention §9).

---

## 3. Architecture

```
 callers (SME backend, IdP, KYC, payments, partners via gateway)
        │  REST (mTLS mesh) / gRPC for internal, FAPI gateway for partners
        ▼
 ┌──────────────────────────── comms-api ────────────────────────────┐
 │ authn (mesh cert / gateway token) → RBAC @Perm → validate →        │
 │ idempotency check → INSERT message + outbox row (one tx) → 202     │
 └───────────────────────────────┬───────────────────────────────────┘
                                 │ outbox relay
                                 ▼
                     ┌─────── queue: comms.intake ───────┐
                     ▼                                   │
 ┌──────────────── planner ────────────────┐             │
 │ resolve recipient (userId → contacts,   │             │
 │   devices, locale, timezone)            │             │
 │ preferences, consent, suppression,      │             │
 │   quiet hours, frequency caps           │             │
 │ choose channel(s) + fallback chain      │             │
 │ render template (versioned, per tenant) │             │
 │ pick provider (routing rules, health)   │             │
 └───────────────┬─────────────────────────┘             │
                 ▼                                       │
  queues: comms.<channel>.<lane>  (e.g. sms.critical, email.bulk)
                 ▼
 ┌──────────── dispatchers (per channel, horizontally scaled) ───────────┐
 │ per-provider token bucket · per-tenant fair share · circuit breaker   │
 │ retry w/ backoff+jitter · expiry check · provider idempotency key     │
 │ SMS: GSM-7/UCS-2 segmenting · email: MIME, DKIM by provider           │
 └───────────────┬───────────────────────────────────────────────────────┘
                 ▼
     providers (SMS aggregator / Twilio-class, SES-class email, FCM, APNs,
                WhatsApp BSP, soundbox MQTT, web push VAPID)
                 │ DLR / bounce / inbound webhooks
                 ▼
 ┌──────────── comms-ingest ────────────┐       ┌──── comms-events ─────┐
 │ verify provider signature → normalise│──────▶│ status state machine  │
 │ → message_events, suppression,       │       │ → caller callbacks /  │
 │   device invalidation, inbound route │       │   event stream, inbox │
 └──────────────────────────────────────┘       └───────────────────────┘
```

**Components** (in v1 these are one deployable with role flags. They split into separate
deployments when the load needs it.)

| Component | Stateless? | Scales on |
|---|---|---|
| `comms-api` | yes | request rate |
| `planner` | yes | intake queue depth |
| `dispatcher-<channel>` | yes | channel queue depth and provider rate limits |
| `comms-ingest` (public webhook endpoint behind the gateway) | yes | DLR volume |
| `scheduler` (scheduled sends, digests, retries due, expiry sweeper) | leader-elected | — |
| `realtime` (in-app WebSocket/SSE fan-out) | sticky sessions | connected clients |

### 3.1 Language and placement
**As built for development (B0):** Java in `trustplane/java/comms-service`, using
Spring Boot 4, JDBC, Postgres, the existing mesh CA and `comms-data-key`. The
`comms` Compose service builds `Dockerfile.java`. The previous Go implementation
is retained as a reference until the remaining B0 parity tests and API callers
are moved. The Java service is allocated 256 MB in Compose; its actual memory and
throughput need measurement before sizing production.

### 3.2 Queue: start on Postgres, keep the interface
- **Phase 1:** a Postgres queue (`comms_jobs` with `FOR UPDATE SKIP LOCKED`, partitioned by
  `lane`, `available_at` index). There are no new moving parts on the single box, and it is
  transactional with the message insert, so the outbox relay is trivial. It comfortably
  handles hundreds of messages per second on modest hardware.
- **Phase 2 (trigger: sustained more than ~300 msg/s, or a second host):** move to **NATS
  JetStream** (light, a single binary, work-queue streams with ack/redelivery, per-subject
  limits). Kafka is only worth it if analytics wants a long-retention event log; it is too
  heavy for this footprint.
- Code depends only on a `Queue` interface (`Enqueue`, `Lease(lane, n, ttl)`, `Ack`,
  `Nack(delay)`, `DeadLetter`), so the swap is configuration.

### 3.3 Fairness and isolation (noisy neighbour)
- **Lanes are separate queues with separate worker pools.** `CRITICAL` workers never take
  from `BULK`.
- Within a lane, leasing is **weighted round-robin across tenants** (lease by
  `(lane, tenant_bucket)`), so one tenant's 100k broadcast is interleaved with everyone
  else's sends.
- There are three levels of rate limit: per API client (gateway), per tenant per channel
  (quota/plan), and per provider account (the provider's TPS). The last limit is shared
  across dispatcher replicas (token bucket in Postgres/NATS KV, or Redis later).
- Broadcasts are **expanded lazily** in chunks of 1000 recipients, so a campaign never puts
  millions of rows in the queue at once.

---

## 4. Reliability design

| Concern | Mechanism |
|---|---|
| Accepted = durable | The message and its outbox row are written in one transaction before `202`. A relay publishes to the queue and marks it done. A crash between the two steps leads to a re-publish, never to a loss. |
| Duplicate API calls | `Idempotency-Key` header (unique per tenant + client, 24 h). A retry returns the original `messageId` and status. |
| Duplicate worker execution | Lease with a visibility timeout. Before calling a provider, the dispatcher sets `attempt.state = SENDING` with a fencing token. Where the provider supports it, a provider idempotency key = `messageId:attempt`. Delivery is still at-least-once, and this is documented. |
| Late delivery is wrong | `expiresAt` on every message (OTPs are required to have it). Dispatchers drop expired messages as `EXPIRED`, never send them, and fall back if the policy says so. |
| Provider outage | A circuit breaker per provider account. When it is open, routing picks the next healthy provider for that channel, country and tenant. Half-open probes recover it. |
| Transient errors | Exponential backoff with full jitter (1 s, 2 s, 4 s … capped at 10 min), with max attempts per lane. `BULK` retries longer and `CRITICAL` retries faster but fewer times (stale OTPs are useless). |
| Permanent errors | Classified per provider (invalid number, unsubscribed, blocked content) → `FAILED`, no retry, and the suppression is updated if relevant. |
| Poison messages | After max attempts the message goes to the DLQ. There is an ops UI to inspect, fix and replay. An alert fires on DLQ growth. |
| Channel fallback | The policy chain per event, e.g. `PUSH (wait 60 s for delivered) → SMS`, `WHATSAPP → SMS`, `EMAIL` only. A fallback starts on failure or on a missing receipt by a deadline (scheduler). |
| Ordering | Not guaranteed in general. Where it matters (e.g. "order shipped" after "order placed"), the caller sets `orderingKey`, which serialises per key within a lane. |
| Lost DLRs | Reconciliation job: if a message is still `SENT` after the provider's DLR window, poll the provider status API where available, else mark it `UNKNOWN`. |
| Webhook to callers | Signed (HMAC-SHA256 + timestamp), retried with backoff for 24 h. The endpoint is auto-disabled after sustained failure, with an alert and a notification to the owner. |
| Backups / DR | Postgres PITR (already needed by the platform). Queue state can be rebuilt from `messages.status` (the relay can re-scan non-terminal messages). |

### 4.1 Message state machine
```
ACCEPTED → PLANNED → QUEUED → SENDING → SENT → DELIVERED → (READ / CLICKED)
     │         │         │        │        └──→ UNDELIVERED / BOUNCED / COMPLAINED
     │         │         │        └──→ RETRYING ──→ QUEUED   (transient)
     │         │         │                └──→ FAILED → (fallback child message)
     │         │         └──→ EXPIRED | CANCELLED
     │         └──→ SUPPRESSED (opt-out, quiet hours drop, cap, no contact)
     └──→ REJECTED (validation)
```
A message is "per recipient per channel". A **request** can fan out to many messages, and
each message has many **attempts** (one per provider call).

---

## 5. Data model (Postgres, `comms` schema)

| Table | Key columns | Notes |
|---|---|---|
| `tenants` | id, name, default_locale, sandbox, quotas jsonb | maps to the org tree root (afriqpay) |
| `requests` | id, tenant_id, client_id, idempotency_key, event_code, lane, audience jsonb, created_at | the unique key (tenant, client, idem key) |
| `messages` | id, request_id, tenant_id, recipient_ref, channel, template_version_id, locale, status, provider_id, expires_at, ordering_key, cost_minor, created_at | partitioned by month. The body is **not** stored for `sensitivity = SECRET` |
| `message_events` | message_id, at, status, provider_status, detail jsonb | append-only audit trail, monthly partitions |
| `attempts` | message_id, n, provider_id, provider_msg_id, started_at, result, error_class | `provider_msg_id` is indexed for DLR lookup |
| `jobs` (phase-1 queue) | id, lane, tenant_bucket, available_at, lease_until, attempts, payload | SKIP LOCKED |
| `outbox` | id, aggregate_id, published_at | relay |
| `templates` / `template_versions` | tenant_id (null = platform default), event_code, channel, locale, version, status (DRAFT/PUBLISHED/ARCHIVED), subject, body, schema jsonb (required variables), layout_id | published versions cannot be changed (same rule as the KYC master) |
| `layouts` | tenant_id, channel, html | branded email wrapper |
| `contacts` | tenant_id, subject_ref (userId or external), channel, address (encrypted), address_hash, verified_at, primary | `address_hash` is used for lookup and suppression without decrypting |
| `devices` | subject_ref, platform (FCM/APNS/WEBPUSH), token (encrypted), app_id, last_seen, disabled_at | |
| `preferences` | subject_ref, category, channel, enabled, quiet_hours, timezone | categories: SECURITY (cannot be turned off), TRANSACTIONAL, MARKETING… |
| `consents` | subject_ref, channel, purpose, basis, source, at, withdrawn_at | needed for Khata customers and marketing |
| `suppressions` | tenant_id, channel, address_hash, reason (STOP, BOUNCE, COMPLAINT, MANUAL), at | global per tenant |
| `providers` | id, tenant_id (null = platform), channel, kind, credentials_ref (KMS/encrypted), priority, countries, tps, status | |
| `routing_rules` | tenant_id, channel, match (country, lane, event), provider order | |
| `event_policies` | event_code, category, lane, channels + fallback chain, ttl, dedupe_window, digest | the "how" for each event code |
| `webhook_subscriptions` | tenant_id, url, secret_ref, events, status | |
| `inbound_messages` | channel, from_hash, to, body, routed_to, at | |

Retention: message bodies are kept 30 days (configurable per tenant). Metadata and events
are kept for 1 year, or longer where regulation needs it. Erasure jobs remove contacts and
bodies by subject.

---

## 6. Policy: deciding *how* to reach someone

Planner order for every recipient:
1. **Resolve** `recipient_ref` (`user:<id>`, `org:<id>:role:ADMIN`, `contact:<external>`,
   raw address for whitelisted callers only) into contacts, devices, locale and timezone.
2. **Category gate:** `SECURITY` ignores preferences (it cannot be opted out of) but still
   honours hard bounces. `MARKETING` requires explicit consent and an unsubscribe link or
   STOP text.
3. **Suppression and consent check** per channel address.
4. **Dedupe:** same `(event_code, recipient, dedupe_key)` within the window → drop or fold
   into a digest.
5. **Frequency caps** per recipient per category (e.g. at most 3 marketing per week).
6. **Quiet hours** by the recipient's timezone. BULK and TRANSACTIONAL are deferred and
   CRITICAL is never deferred.
7. **Channel selection** from `event_policies` intersected with what the recipient has,
   giving an ordered fallback chain.
8. **Render** the template (`locale` falls back to tenant default, then `en`). Missing
   variables → `REJECTED` at accept time (variables are validated against the template
   schema in the API, not later).
9. **Route** to a provider: tenant BYO provider first if present, then platform providers by
   rule and health.

---

## 7. Channel specifics

| Channel | Must handle |
|---|---|
| **SMS** | E.164 normalisation (libphonenumber); GSM-7 vs UCS-2 and segment count (cost); sender-ID registration per country (alphanumeric vs long or short code); DLR mapping; STOP/HELP inbound; country-specific content rules; a local Namibian aggregator for cost and deliverability, plus an international provider as failover. |
| **Email** | Provider API (SES-class / SendGrid-class) rather than raw SMTP; per-tenant domain with SPF/DKIM/DMARC set-up wizard and verification; List-Unsubscribe + one-click (RFC 8058) for bulk; bounce and complaint webhooks → suppression; HTML and text parts; attachments as short-lived signed links (large) or ≤ 5 MB inline; open/click tracking off for SECURITY mails; IP/domain warm-up plan for bulk. |
| **Push** | FCM HTTP v1 (Android and web), APNs token auth (.p8), Web Push VAPID; notification vs data (silent) messages; collapse keys (e.g. the cancel-approval push replaces the approval push); TTL = `expiresAt`; invalid token → disable the device; per-tenant app credentials for white-label apps. |
| **WhatsApp** | A BSP / Cloud API; only **pre-approved templates** outside the 24 h session window (template sync and approval status in our template table); conversation-category pricing; opt-in required; inbound replies → `inbound_messages`. |
| **In-app** | A per-user inbox (stored, read/unread, actions); real-time via SSE/WebSocket through the gateway; badge counts; the portals show it in the header. |
| **Speaker / voice** | Soundbox devices over MQTT (or the device vendor's API); a payment announcement must arrive in under 2 s or not at all (short TTL); TTS template per locale. |
| **Webhook** | Machine recipients (partner systems) use the same signing and retry rules as status callbacks. |

---

## 8. API surface (v1)

All under `/comms/v1`, behind the trustplane gateway for partners (FAPI-protected) and over
the mTLS mesh for internal callers. Every handler carries an `@Perm`-equivalent
registration so it lands in the RBAC catalogue (see `fapi2-design.md` RBAC grid).

**Sending**
| Method | Path | Purpose |
|---|---|---|
| POST | `/messages` | Send one event to one recipient or a small list (≤ 100). Body: `eventCode`, `to[]`, `data{}`, optional `channels[]` override, `lane`, `expiresAt`, `scheduleAt`, `dedupeKey`, `orderingKey`, `sensitivity`, `callbackUrl`. Header `Idempotency-Key`. → `202 {requestId, messages[]}` |
| POST | `/messages/batch` | Up to 1000 independent messages (each with its own data) |
| POST | `/broadcasts` | Audience (`org subtree`, `role`, `segment`, uploaded CSV ref) + event, schedule, send window, A/B variants → expanded lazily |
| GET | `/requests/{id}`, `/messages/{id}` (+ `/events`) | Status and timeline |
| POST | `/messages/{id}/cancel`, `/broadcasts/{id}/pause\|resume\|cancel` | Only while not yet sent |
| GET | `/messages?recipient=&event=&status=&from=&to=` | Search for support and ops (PII is masked unless the caller has the permission) |

**Templates:** `GET/POST /templates`, `POST /templates/{id}/versions`,
`POST /template-versions/{id}/publish`, `POST /template-versions/{id}/preview` (renders
sample data per channel; SMS segment count, email HTML), `POST /template-versions/{id}/test-send`.

**Recipients:** `PUT /subjects/{ref}/contacts`, `POST /subjects/{ref}/devices`,
`DELETE /devices/{id}`, `GET/PUT /subjects/{ref}/preferences`,
`GET /preferences/page?token=` (signed, public unsubscribe/manage page),
`POST /consents`, `GET/POST/DELETE /suppressions`.

**Inbox:** `GET /inbox`, `POST /inbox/{id}/read`, `GET /inbox/stream` (SSE).

**Admin:** `providers`, `routing-rules`, `event-policies`, `webhook-subscriptions` CRUD;
`GET /dlq`, `POST /dlq/{id}/replay`; `GET /usage?from=&to=` (counts and cost per tenant,
channel and event).

**Provider-facing:** `POST /ingest/{providerKind}/{providerId}` (DLR, bounce, inbound).
Every provider's signature is verified, then the payload is normalised. This is the only
unauthenticated route, and the gateway gives it a separate rate budget.

**Internal (gRPC, mesh):** `Send`, `SendBatch`, `GetStatus`, `WatchStatus` (stream) for
low-latency callers like the IdP's OTP and push-approval flow.

**Capabilities:** `GET /capabilities` (mesh only) returns, per channel (`sms`, `email`, `push`,
`whatsapp`, `in_app`), whether it is `live`: a provider is configured and healthy (breaker closed) and the
templates for the auth events (`auth.otp.sms`, `auth.push.challenge`, `auth.password.reset`, security
notices) are published. The IdP caches it for 60 s and offers a 2FA factor only while its channel is live
(`2fa-design.md` §4.2).

---

## 9. Security and privacy

- **Secrets in messages:** the caller marks `sensitivity: SECRET` (OTP, reset link). The
  rendered body lives only in memory in the dispatcher. `messages` stores the template
  version and the **names** of variables, never their values. Secret values travel
  encrypted in the job payload (AES-GCM, key separate from the KYC and MFA keys) and are
  wiped on a terminal state. This continues the H8 rule. Sandbox keeps `sim_outbox` as the
  only place a raw code is visible.
- **PII:** addresses are encrypted at rest with a blind-index hash for lookups. Logs and
  traces carry `address_hash` and masked forms (`+264 81 *** 4321`) only.
- **Provider credentials:** envelope-encrypted with a key held by the platform. They are
  write-only through the API (never returned) and rotation is supported.
- **AuthN/Z:** mesh mTLS identity for internal services. Partners use FAPI 2.0 tokens via
  the gateway. Tenant scoping is enforced in every query (the IDOR lesson from the schema
  fix). Callers may only use `event_codes` granted to them (the KYC service cannot send
  payment receipts).
- **Abuse:** phone/email enumeration via the OTP send is rate-limited per recipient and
  per IP (gateway). SMS pumping / toll fraud protection: country allow-list per tenant, a
  velocity anomaly alert, and a spend cap that pauses SMS for a tenant.
- **Content:** templates are HTML-escaped by default, URLs in bulk messages are allow-listed
  per tenant, and links are signed so they cannot be tampered with.
- **Audit:** every template publish, provider change, suppression removal and DLQ replay
  goes to the platform audit log with the actor.

---

## 10. Observability

- **Metrics** (Prometheus, through actuator-style `/metrics`): accepted/sent/delivered/failed
  per channel × lane × provider × tenant; queue depth and age of the oldest job per lane;
  provider latency and error class; breaker state; DLR lag; cost per hour.
- **Tracing:** an OpenTelemetry trace from the caller's request → accept → plan → dispatch →
  provider. `messageId` appears in every log line.
- **Alerts:** CRITICAL oldest-job age > 5 s; delivery rate per provider drops more than 20 %
  below its 7-day baseline; DLQ growth; breaker open; bounce rate > 2 % or complaint rate
  > 0.1 % (sender reputation); spend cap reached.
- **Ops UI** (admin portal): message search and timeline, DLQ, provider health, template
  editor with preview, suppression management, usage and cost.

---

## 11. Migration from what exists

1. Build `commsplane` with the Postgres queue and the sandbox provider. The sandbox provider
   writes to `sim_outbox`-equivalent storage, so UAT keeps working unchanged.
2. Add `CommsClient` to the Java backend. The core sends the IdP set-up links it requests (which replace
   `ActivationService`, `idp-rbac-cutover-plan.md` §1.4) with `CommsClient.send(ACCOUNT_SETUP, user:<uid>, …)`,
   and writes every user's contacts with `PUT /subjects/user:<uid>/contacts`. It is the only writer of
   contact details. Keep reading the old table for history.
3. Import `notification_templates` rows as platform-default `template_versions` (v1, PUBLISHED).
4. Add `CommsClient` (gRPC) to the trustplane IdP for login OTP, 2FA SMS, push challenges, reset links
   and security notices: lane CRITICAL, `expiresAt` = OTP or challenge expiry, `sensitivity` SECRET,
   addressed by `user:<uid>` (`2fa-design.md` D10). The IdP reads `GET /capabilities` to decide
   which factors to offer (`2fa-design.md` §4.2).
5. Freeze, then drop, `notification_messages` once nothing writes to it (one release later).

---

## 12. Phases

| Phase | Delivers | Exit test |
|---|---|---|
| **P0 – foundations** in progress | service skeleton in mesh, schema, PG queue, outbox relay, idempotency, state machine, sandbox provider, `POST /messages`, status API, metrics | activation and OTP flows pass the UAT pack through Java comms in sandbox |
| **P1 – real channels** | SMS (local aggregator + international failover), email (provider API, tenant domain + DKIM), DLR/bounce ingest, suppression, retries/DLQ, breakers, template versions + preview | chaos test: kill the primary SMS provider mid-load, with zero lost messages and failover in under 30 s |
| **P2 – push and in-app** | FCM/APNs/Web Push, device registry, 2FA push approval with cancel push, inbox + SSE in portals | push approval end-to-end in under 3 s at p95 |
| **P3 – policy** | preferences page, consents, quiet hours, frequency caps, fallback chains, digests, per-tenant BYO provider, usage and cost | Khata reminder respects consent, STOP, and quiet hours |
| **P4 – scale and engagement** | broadcasts with lazy expansion, A/B, WhatsApp, speaker/MQTT, NATS JetStream swap, split deployments, load test at 10× forecast | 100k-recipient broadcast while CRITICAL p95 stays within target |


**Java P0 inventory (2026-11-12):**
- **Working:** an mTLS mesh API with certificate-CN caller rules; `POST /comms/v1/messages` (up to 100 `user:<id>` recipients), `GET /requests/{id}`, `GET /messages/{id}`, `GET /messages/{id}/events`, core-only `PUT|GET /subjects/{ref}/contacts`, `GET /capabilities`, and development-only `GET /sandbox/outbox`. Event prefixes and templates come from `config/comms/comms.yaml`. The Java service encrypts queued variables and contact addresses, masks contacts in API responses, and handles idempotency keys.
- **Delivery:** a Postgres job row is committed with each message. A scheduled worker leases rows, checks expiry, picks the first configured channel with a contact and matching template, writes a sandbox delivery, and marks the message `DELIVERED`. A message with no usable channel becomes `SUPPRESSED`; transient errors retry up to three attempts and then become `FAILED`. Sandbox output is for development/UAT and is refused in production.
- **Still required for the full P0 exit gate:** Java end-to-end activation, merchant OTP and reset-link checks through the gateway; `/metrics`; 24-hour idempotency retention; versioned published templates; lane-specific workers and retry budgets; a durable dead-letter queue; provider health and breakers. The existing Go P0 suite covers much of the former implementation, but does not prove Java parity. The Java scratch-database tests cover sandbox delivery, encryption, idempotency, caller rules and absent-provider suppression.
- **Later phases:** real SMS/email providers, delivery receipts and failover (P1); push and in-app (P2); preferences and consent (P3); scale and engagement features (P4).
---

## 13. Sizing model (assumed — revise with real numbers)

Market facts used (checked November 2026):
- About **2.75 M active SIMs** (CRAN, Q1 2026). Mobile penetration is about 86 %, and about
  61–62 % of SIMs are used for internet, so roughly 4 in 10 users still need SMS rather
  than push.
- **MTC holds about 87–90 %** of mobile, TN Mobile about 8–10 %, and Paratus is a new
  data-only network.
- MTC made **VOX Solutions its exclusive gateway for international A2P SMS** (October 2025).
  An international CPaaS reaching MTC therefore pays international termination.
- A2P SMS costs about **N$ 0.50–1.00 per message** (vendor price lists). SMS is the
  expensive channel, so the policy is **push / in-app first, and SMS only where it is the
  only option or security needs it.**

Platform assumptions (afriqpay SME payments; these are guesses, not data):

| Driver | Launch (month 3) | Month 12 | Month 24 (3× growth) |
|---|---|---|---|
| Active merchants | 2,000 | 15,000 | 45,000 |
| Payments / merchant / day | 30 | 40 | 40 |
| Payments / day | 60 k | 600 k | 1.8 M |

Messages per day at month 12:

| Flow | Channel | Rule | Per day |
|---|---|---|---|
| Payment announcement | SPEAKER / push to merchant | 1 per payment | 600 k |
| Payer receipt | SMS | ~10 % of payments (payer opted in, no app) | 60 k |
| Login / step-up OTP | SMS | 15 k merchants × 1.5 logins × 30 % SMS (the rest push / TOTP) | 7 k |
| Push approvals (2FA) | push | the remaining 70 % of logins | 16 k |
| Khata reminders | SMS (WhatsApp later) | 20 % of merchants × 10 per week | 4 k |
| Statements, KYC, invites, ops | email | daily statement + events | 25 k |
| Org-tree broadcasts | email / in-app | a few per week | ≤ 15 k bursts |
| **Total** | | | **≈ 730 k / day** (≈ 70 k SMS, 25 k email, 630 k push/speaker) |

Peak rate: 730 k a day ≈ 8.5/s on average. Payments fall in roughly 12 business hours, a
lunchtime peak is about 5×, and month-end/payday is about 2×, which gives **≈ 170
messages/s at peak at month 12** and **≈ 500/s at month 24**.

Consequences for the design:
- **The Postgres queue (§3.2) is enough for the first 12 months.** The JetStream trigger
  stays at a sustained 300/s, which the 24-month forecast crosses, so P4 is scheduled before
  then rather than "if needed".
- Provider contracts: an **SMS throughput of 50 TPS** is enough (OTP bursts and receipts).
  Push (FCM) and email (SES-class) have far more headroom than we need.
- Cost: about 70 k SMS a day ≈ **N$ 35–70 k a day**, so per-tenant cost tracking and spend
  caps (§9) are a P1 requirement, not P3. Every message moved from SMS to push saves money.
- Design headroom: load test at **10× month 12 (≈ 1,700/s burst)** in P4.

---

## 14. Decisions (assumed for Namibia; revisit with business)

| # | Decision | Reasoning |
|---|---|---|
| D1 | **SMS primary: a Namibian-licensed aggregator with direct local binds to MTC and TN Mobile.** **Failover: one international CPaaS** (Twilio/Infobip-class), which reaches MTC via VOX at international rates. Choose the aggregator by RFP on DLR quality, TPS and price. | MTC is about 90 % of the market and international A2P into MTC is exclusive to VOX, so a local bind is both cheaper and more reliable. The failover only needs to carry OTPs during an outage, so its higher price is acceptable. |
| D2 | **Register the alphanumeric sender ID `AfriqPay`** (and per-tenant IDs later) with MTC and TN Mobile before launch, even though some providers say registration is optional. | The public sources conflict, and some routes overwrite unregistered IDs with a short code, which hurts trust in OTPs and invites phishing. |
| D3 | **Bulk and reminders only between 08:00 and 20:00 Africa/Windhoek.** CRITICAL and TRANSACTIONAL are sent any time. | This follows CRAN's recommended window as reported by providers. It is also the polite default. |
| D4 | **Email: Amazon SES in `af-south-1` (Cape Town)** as primary, with a second provider configured cold for failover. A dedicated sending subdomain `mail.afriqpay.com` with SPF, DKIM and DMARC (`p=quarantine` → `reject`). | About 25 k emails a day is small; SES is the cheapest and the region is close. A cold standby is enough at this volume. |
| D5 | **Push: FCM (Android and web) + APNs + Web Push (VAPID) for the portals.** The policy is push-first for merchants, with SMS as fallback after 60 s without a delivery receipt (CRITICAL) or never (receipts). | Android dominates the market, and push is about free while SMS costs N$ 0.50–1.00. |
| D6 | **WhatsApp is not in v1.** Evaluate it in P3 for Khata reminders and payer receipts: compare utility-template cost per message against SMS, and check opt-in rates. | It is widely used, but it needs a BSP contract, Meta template approval and opt-in flows. SMS covers v1. |
| D7 | **Service in Java (`trustplane/java/comms-service`)** for the active development stack. | Uses the existing mesh CA, Postgres and data key; Compose builds the Java service. Resource sizing and provider adapters still need validation. |
| D8 | **No tenant consumer marketing in v1.** Tenants may broadcast to their **own org tree** (merchants, distributors, suppliers) over email and in-app. Khata reminders are allowed as transactional, but need recorded consent and STOP. | Namibia's Data Protection Bill has not been enacted and there is no supervisory authority yet. Consumer marketing waits for the law plus our consent tooling (P3). Until then we build to GDPR/POPIA-style principles so we are not caught out when it passes. |
| D9 | **Retention:** secret values are never stored; non-secret bodies are kept 90 days; message metadata and events are kept **5 years** where they relate to a payment, KYC or account action, and 1 year otherwise; Khata consent records are kept for the life of the consent plus 5 years. | The Financial Intelligence Act 2012 (s. 27) requires records to be kept for at least 5 years per secondary sources. **Compliance must confirm this against the statute and Bank of Namibia determinations.** |
| D10 | **Data location:** primary in the existing afriqpay host. Use providers with African regions (SES `af-south-1`) where possible, and keep a provider-by-provider list of where data goes for the coming data-protection law. | This keeps a cross-border transfer register ready for the law. |

Open with business (these do not block P0/P1): the actual merchant and payment forecast,
the SMS aggregator shortlist, whether payer SMS receipts are paid by the merchant or the
platform, and the WhatsApp go/no-go in P3.
