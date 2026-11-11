# KYC Entity — Model & Persistence Plan

The payload to persist is a **merchant onboarding application** for the payments
platform (FAPI/MiPay context). It is a *nested aggregate*:

```
KYC Application (root: lifecycle + identity + tenancy)
├── businessProfile      (1:1, scalar fields)
├── operationalDetails   (1:1, scalar fields)
├── addresses[]          (1:N, typed addresses)
├── ubos[]               (1:N, PII-heavy: DOB, ID numbers, PEP flag)
└── bankAccounts[]       (1:N, financial account data)
```

---

## Decision: single table vs multiple tables

| Option | Verdict | Why |
|---|---|---|
| **One table, everything as JSONB** | ❌ rejected (for now) | Loses DB-enforced integrity (exactly-one REGISTERED address, `ownership_percentage` ∈ [0,100], UBO sum ≤ 100), per-column PII encryption/masking, cheap cross-merchant queries ("all PEP UBOs", "merchants onboarded in country X"), and regulator-friendly typed columns. Draft-stage flexibility it buys can be had at the snapshot table instead. |
| **One table per section incl. 1:1 sections** | ❌ overkill | `businessProfile` / `operationalDetails` are always read and written together with the root; separate tables add joins and code for zero integrity benefit. |
| **Root table (1:1 sections as typed columns) + one table per collection + history + submission snapshot** | ✅ **recommended** | Typed where data is queried/constrained/masked; JSONB where immutability is the point (the frozen snapshot). 4 core tables + 2 supporting tables — still a small, legible schema. |

**Bottom line: not a single table.** The three collections force either JSONB or
child tables; for a KYC *trust* dataset the constraints, masking and auditability
all argue for typed child tables. The 1:1 sections stay as columns on the root.

---

## Java entity (records, trustplane conventions)

```java
public enum KycStatus   { DRAFT, SUBMITTED, APPROVED, REJECTED }
public enum EntityType  { SOLE_PROPRIETORSHIP, PARTNERSHIP, PRIVATE_LIMITED, PUBLIC_LIMITED }
public enum AddressType { REGISTERED, OPERATING, POSTAL }
public enum IdentificationType { PASSPORT, NATIONAL_ID, DRIVING_LICENSE }
public enum AccountType { CHECKING, SAVINGS }

// ---- 1:1 sections (root columns, kept as records at the API boundary) ----
public record KycBusinessProfile(
        String legalBusinessName, String tradingName, EntityType entityType,
        String registrationNumber, LocalDate incorporationDate, String taxIdentificationNumber) {}

public record KycOperationalDetails(
        String mcc, String industryVertical, String websiteUrl, String mobileAppLink,
        String storefrontDetails, BigDecimal expectedMonthlyVolume, BigDecimal averageTicketSize) {}

// ---- 1:N collections ----
public record KycAddress(
        AddressType addressType, String streetLine, String city,
        String state, String postalCode, String country) {}

public record KycUbo(
        String fullLegalName, LocalDate dateOfBirth, String nationality,
        String residentialAddress, IdentificationType identificationType,
        String identificationNumber, LocalDate identificationExpiryDate,
        BigDecimal ownershipPercentage, boolean politicallyExposedPerson) {}

public record KycBankAccount(
        String beneficiaryName, String bankName, String branchName,
        String institutionCode, String accountNumber, String iban, AccountType accountType) {}

// ---- aggregate root ----
public record KycApplication(
        String id, String ref, String tenantId, String supplierRef, KycStatus status,
        KycBusinessProfile businessProfile, KycOperationalDetails operationalDetails,
        List<KycAddress> addresses, List<KycUbo> ubos, List<KycBankAccount> bankAccounts,
        String createdBy, String decidedBy, long version,
        Instant createdAt, Instant updatedAt) {}
```

`@CrudResource("kyc.case", extra={"submit","approve","reject"})` +
`@CrudResource("kyc.field.pii", extra={"read"})` carry straight over from the
trustplane permission matrix.

---

## Schema (Postgres DDL)

```sql
-- ============ ROOT: lifecycle + 1:1 scalar sections ========================
CREATE TABLE kyc_applications (
  id              text PRIMARY KEY,                       -- e.g. 'ky-7f3a…'
  ref             text NOT NULL,                          -- human-facing case ref
  tenant_id       text NOT NULL REFERENCES tenants(id),
  supplier_id     text REFERENCES suppliers(id),          -- supplier isolation applies
  status          text NOT NULL DEFAULT 'draft'
                  CHECK (status IN ('draft','submitted','approved','rejected')),

  -- businessProfile (1:1)
  legal_business_name    text NOT NULL,
  trading_name           text,
  entity_type            text NOT NULL CHECK (entity_type IN
                         ('SOLE_PROPRIETORSHIP','PARTNERSHIP','PRIVATE_LIMITED','PUBLIC_LIMITED')),
  registration_number    text NOT NULL,
  incorporation_date     date NOT NULL,
  tax_identification_number text,                          -- PII -> encrypt at rest

  -- operationalDetails (1:1)
  mcc                    text,
  industry_vertical      text,
  website_url            text,
  mobile_app_link        text,
  storefront_details     text,
  expected_monthly_volume numeric(18,2) NOT NULL DEFAULT 0 CHECK (expected_monthly_volume >= 0),
  average_ticket_size     numeric(18,2) NOT NULL DEFAULT 0 CHECK (average_ticket_size   >= 0),

  -- lifecycle / concurrency
  created_by        text NOT NULL,
  submitted_by      text,
  decided_by        text,
  version           bigint NOT NULL DEFAULT 1,            -- optimistic CAS for PUT

  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, supplier_id, ref)
);
CREATE INDEX kyc_applications_worklist ON kyc_applications (tenant_id, status);

-- ============ ADDRESSES (1:N) ==============================================
CREATE TABLE kyc_addresses (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  application_id text NOT NULL REFERENCES kyc_applications(id) ON DELETE CASCADE,
  address_type   text NOT NULL CHECK (address_type IN ('REGISTERED','OPERATING','POSTAL')),
  street_line    text NOT NULL, city text NOT NULL, state text, 
  postal_code    text NOT NULL, country text NOT NULL
);
-- exactly ONE registered address per application — DB-enforced
CREATE UNIQUE INDEX kyc_addresses_one_registered
  ON kyc_addresses (application_id) WHERE address_type = 'REGISTERED';

-- ============ UBOs (1:N, PII) ==============================================
CREATE TABLE kyc_ubos (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  application_id text NOT NULL REFERENCES kyc_applications(id) ON DELETE CASCADE,
  full_legal_name     text NOT NULL,
  date_of_birth       date NOT NULL,
  nationality         text,
  residential_address text,
  identification_type text NOT NULL CHECK (identification_type IN
                      ('PASSPORT','NATIONAL_ID','DRIVING_LICENSE')),
  identification_number_enc    bytea NOT NULL,           -- AES-256-GCM, app-level
  identification_expiry_date   date,
  ownership_percentage         numeric(5,2) NOT NULL
                               CHECK (ownership_percentage BETWEEN 0 AND 100),
  politically_exposed_person   boolean NOT NULL DEFAULT false
);
CREATE INDEX kyc_ubos_pep ON kyc_ubos (application_id) WHERE politically_exposed_person;

-- ============ BANK ACCOUNTS (1:N, financial PII) ===========================
CREATE TABLE kyc_bank_accounts (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  application_id text NOT NULL REFERENCES kyc_applications(id) ON DELETE CASCADE,
  beneficiary_name  text NOT NULL,
  bank_name text, branch_name text, institution_code text,
  account_number_enc bytea NOT NULL,                      -- AES-256-GCM, app-level
  iban_enc           bytea,                               -- AES-256-GCM, app-level
  account_type       text NOT NULL CHECK (account_type IN ('CHECKING','SAVINGS'))
);

-- ============ CASE HISTORY (append-only audit of transitions) ==============
CREATE TABLE kyc_case_history (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  application_id text NOT NULL REFERENCES kyc_applications(id) ON DELETE CASCADE,
  actor       text NOT NULL,
  action      text NOT NULL,        -- create|update|submit|approve|reject|grant_revoke…
  from_status text, to_status text,
  details     jsonb,                -- diff notes, rejection reason, etc.
  ts          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX kyc_history_by_app ON kyc_case_history (application_id, ts DESC);

-- ============ SUBMITTED SNAPSHOT (immutability for maker/checker) ==========
-- What the checker approves is EXACTLY what the maker submitted: freeze the
-- whole document (JSONB) at submit time. Draft edits never touch this row.
CREATE TABLE kyc_submitted_snapshots (
  application_id text PRIMARY KEY REFERENCES kyc_applications(id) ON DELETE CASCADE,
  document       jsonb NOT NULL,      -- canonical payload as submitted
  submitted_by   text   NOT NULL,
  submitted_at   timestamptz NOT NULL DEFAULT now()
);
```

---

## Save / load plan (transaction boundaries)

1. **CREATE** (`kyc.case:create`, status starts `draft`)
   Single transaction: `INSERT` root + delete-and-insert children (empty→initial).
2. **UPDATE (draft-only)** (`kyc.case:update`)
   - `WHERE id=? AND status='draft'` — non-draft edits → `409 state_conflict`.
   - Optimistic CAS: `WHERE version=?` + `version = version+1` → mismatch = `409`.
   - Children saved as **whole-aggregate replace** (`DELETE … WHERE application_id=?`
     then bulk insert) inside the same tx — no diffing code, idempotent, and the
     DB constraints re-validate every save.
   - `UPDATE kyc_applications … updated_at=now()`.
3. **SUBMIT** (`kyc.case:submit`)
   - Validate invariants (below), then in one tx: `status → submitted`,
     `submitted_by = actor`, and **freeze `kyc_submitted_snapshots`** (root + children
     serialized to canonical JSONB). Draft may no longer be edited.
4. **APPROVE / REJECT** (`kyc.case:approve|reject`) — maker/checker:
   `decided_by ≠ submitted_by` (`403 maker_checker`); decision reasons appended to
   `kyc_case_history`; status moves to `approved|rejected`.
5. **READ** (`kyc.case:read`)
   Root + 3 child queries in one tx (or one `jsonb_agg` query). PII columns
   (`identification_number_enc`, `account_number_enc`, `iban_enc`, DOB, tax id)
   are **masked by default** (`••••1234`); unmasked only for callers holding
   `kyc.field.pii:read` (same gateway ctx permission used today).

### Invariants enforced (DB where possible, service where cross-entity)

| Invariant | Where |
|---|---|
| `ownership_percentage` ∈ [0,100] per UBO | `CHECK` on `kyc_ubos` |
| `SUM(ubo.ownership_percentage) ≤ 100` per application | service (single query after replace); optional deferred trigger |
| exactly one `REGISTERED` address | partial `UNIQUE INDEX` |
| non-draft is immutable | service (`status='draft'` guard) + snapshot table |
| volume/ticket ≥ 0 | `CHECK` on root |
| maker ≠ checker | service (`maker_checker`) |

---

## Why this shape fits the trust plane

- **Tenancy & isolation**: `tenant_id`/`supplier_id` on the root only — child rows
  are reachable exclusively through the root, so the existing supplier-isolation
  and tenant checks stay single-point.
- **Point-of-use RBAC unchanged**: case operations map to the existing
  `kyc.case:*` permissions; PII reveal is gated by `kyc.field.pii:read`.
- **Auditability**: append-only `kyc_case_history` + immutable `kyc_submitted_snapshots`
  give a regulator a complete "who saw/submitted/decided what" trail without
  touching the live tables.
- **Schema evolution**: new scalar sections → add nullable root columns (migrations);
  new *collection* sections → new child table; the snapshot table absorbs
  form-variant experiments without touching the core.

## What would change the decision

- If merchant segments will carry **radically different form layouts** per country/
  segment with weekly schema churn, move draft storage to JSONB (root +
  `draft_document jsonb`) and keep only the **typed child tables for submitted
  applications** — drafts stay schemaless, decisions stay typed.
- If UBO screening (PEP/sanctions) becomes a per-UBO *workflow*, promote `kyc_ubos`
  to its own root-ish entity with its own status column — the current schema
  already isolates it, so that extension is additive.
