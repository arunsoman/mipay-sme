# KYC design: master-driven rules, pluggable workflow and providers

Status: **draft for review** · 2026-11-11

## 1. Context and confirmed decisions

KYC today (V7, `KycApplicationService`) stores one JSON document per org together with its tree details and `is_suspended`. Its weaknesses:
- The document shape is a fixed Java record (`KycPayload`).
- Only four fields have fixed choice lists.
- All the rules are hard-coded.
- Approval is a single platform decision that runs alongside the separate onboarding approval.

Confirmed with the product owner:

| # | Decision |
|---|---|
| D1 | **Several countries eventually; Namibia (NA) first.** |
| D2 | **Backend supports both PSP and EMI licence rules; the frontend shows only NA + PSP.** There are no e-wallets in the PSP model. |
| D3 | **Subjects are businesses (orgs in the tree) only.** Individual customers are a *master-table dimension* (`subject_type = INDIVIDUAL`) with no rows yet. |
| D4 | **No document uploads now.** The model has a documents section type, switched off for NA/PSP. |
| D5 | **One master table set**, maintained by developers through Flyway migrations, defines fields, choice lists, validation, sensitivity, workflow and providers. The system behaves from it. |
| D6 | **A pluggable workflow engine** decides three things per master entry: whether approval is needed; whether there is maker-checker at entry level; and which approval stages make the KYC live. This applies to both PSP and EMI. |
| D7 | **KYC is a plugin point inside onboarding.** Data collection and verification checks can come from third-party providers, and onboarding still works. |
| D8 | **The final approval in the KYC workflow is the single gate.** It makes the org ACTIVE and sends the admin's activation link. The onboarding request no longer has its own decision. |
| D9 | **Expected volume:** 50–80 orgs in month 1, then about 5% growth a month. An in-memory cache and a plain Postgres outbox are enough. There is no message broker. |
| D10 | **KYC is a separate service** (`kyc-service` + `kyc-db`). It talks to core over gRPC with mutual TLS, and personal data never enters core (§16). |

## 2. Building blocks

```
                ┌──────────────────────────── master (developer-maintained, versioned) ─────────┐
                │ kyc_profiles ──▶ kyc_schema_versions ──▶ sections ──▶ fields ──▶ lists/items  │
                │      │                                   rules                               │
                │      ├── workflow_key + workflow_config                                       │
                │      └── collection_provider, check_providers, risk_scorer                    │
                └───────────────────────────────────────────────────────────────────────────────┘
 onboarding ──▶ KycService ──▶ resolve profile ──▶ collect (native form | provider)
                                    │                  │
                                    │            validate vs schema (save: shape, submit: complete)
                                    ▼
                           WorkflowEngine ──plan──▶ entry check ─▶ verification ─▶ stage 1..n ─▶ GO-LIVE
                                    ▲                               │ (KycCheckProvider plugins)
                                    └── KycWorkflow / StageContributor plugins
```

Five plugin interfaces. Each built-in is a Spring bean, and the master table names it by key.

| Interface | Decides | Built-ins |
|---|---|---|
| `KycWorkflow` | approval needed? entry maker-checker? which stages? | `configurable` (reads `workflow_config`) |
| `KycStageContributor` | adds extra stages from the data, risk and check results | `high-risk-or-pep` |
| `KycCollectionProvider` | how the data is collected | `native-form` (our form) |
| `KycCheckProvider` | identity, sanctions, PEP, registry and bank-account checks | `simulated` (current behaviour), `manual` (ops records the result) |
| `KycRiskScorer` | risk rating and review cycle | `default` (today's `applyRiskRating` logic, extended with MCC, country and PEP factors) |

**Startup validation.** At boot, `KycMasterValidator` checks every published profile:
- each workflow, provider, scorer and rule key resolves to a bean;
- each `list_code` exists;
- each `visible_when` and `required_when` refers to a real field;
- profile matching is unambiguous.

**On any failure the app refuses to start.** A broken master is caught on deploy, not in front of a merchant.

## 3. Master tables

All tables are created in `V8__kyc_master.sql`. Rows are maintained only through later migrations (`V9__kyc_master_na_psp_v1.sql`, …).

### 3.1 Profiles: which rules apply

`kyc_profiles`

| column | notes |
|---|---|
| `id`, `code` | e.g. `NA-PSP-BUSINESS-MERCHANT` |
| `country` | ISO 3166-1 alpha-2, e.g. `NA` |
| `licence_type` | `PSP` \| `EMI` |
| `subject_type` | `BUSINESS` \| `INDIVIDUAL` (no INDIVIDUAL rows yet) |
| `entity_type` | nullable, meaning any |
| `org_type` | nullable, meaning any |
| `schema_version_id` | the form/rules version to use for **new** applications |
| `workflow_key`, `workflow_version`, `workflow_config` (jsonb) | see §6 |
| `collection_provider` | default `native-form` |
| `check_providers` (jsonb) | e.g. `{"IDENTITY":"simulated","SANCTIONS":"simulated","PEP":"simulated"}` |
| `risk_scorer` | default `default` |
| `params` (jsonb) | tunables used by rules and plugins, e.g. `{"uboThresholdPct":25,"minOwnerAge":18,"reviewMonths":{"LOW":36,"MEDIUM":24,"HIGH":12}}` |
| `ui_enabled` | whether the frontend may offer this profile. Only NA/PSP rows are true. |
| `active`, `effective_from` | |

**Resolution:** among the active rows matching `(country, licence_type, subject_type)`, the one with the most specific match on `entity_type` and `org_type` wins. A tie is a boot failure.

### 3.2 Schema versions: what the form looks like

`kyc_schema_versions(id, code, version, status DRAFT|PUBLISHED|RETIRED, published_at)`. A **published version is immutable.** A change means publishing a new version and pointing the profile at it.

Every application records its `schema_version_id`. Applications already saved, submitted or approved keep validating and rendering against the version they were filled under. A DRAFT may be moved to the new version with an explicit "upgrade draft" action; fields that no longer exist are dropped and reported to the user.

`kyc_sections`

| column | notes |
|---|---|
| `schema_version_id`, `key` | the JSON key in the document, e.g. `businessProfile`, `ubos` |
| `title`, `step` | the wizard step it appears on; several sections may share a step |
| `kind` | `OBJECT` \| `LIST` \| `DOCUMENTS` (`DOCUMENTS` is not used in NA/PSP v1) |
| `min_items`, `max_items` | for `LIST` |
| `visible_when` | condition (§3.5) |
| `sort` | |

`kyc_fields`

| column | notes |
|---|---|
| `section_id`, `key` | the JSON key inside the section |
| `label`, `help_text`, `placeholder`, `sort` | |
| `data_type` | `TEXT` `NUMBER` `MONEY` `PERCENT` `DATE` `BOOLEAN` `ENUM` `MULTI_ENUM` `COUNTRY` `EMAIL` `PHONE` `URL` |
| `list_code` | for `ENUM`/`MULTI_ENUM`/`COUNTRY` |
| `list_parent_field` | dependent dropdowns, e.g. the branch list filtered by `bankCode` |
| `required` | `ALWAYS` \| `NEVER` \| `WHEN` (with `required_when`) |
| `visible_when` | condition |
| `pattern`, `min_len`, `max_len`, `min_value`, `max_value` | single-field checks |
| `date_rule` | `PAST` \| `FUTURE` \| `ANY` |
| `derived_from` | e.g. `industryVertical` derived from `mcc` through a list-item attribute; the user can't edit it |
| `canonical_tag` | role the field plays, independent of country: `LEGAL_NAME`, `TRADING_NAME`, `REG_NUMBER`, `TAX_ID`, `UBO_NAME`, `UBO_DOB`, `UBO_NATIONALITY`, `UBO_ID_TYPE`, `UBO_ID_NUMBER`, `UBO_OWNERSHIP`, `UBO_PEP`, `BANK_ACCOUNT_NUMBER`, `BANK_CODE`, `MCC`, `COUNTRY_OF_REGISTRATION`, … |
| `sensitivity` | `NONE` \| `PII` (masked for ancestors) \| `SECRET` (also encrypted at rest; see §8) |
| `mask` | `LAST4` \| `HIDE` \| `YEAR_ONLY` |

Canonical tags let providers, risk scorers, screening and duplicate checks work in every country without knowing each country's field names.

### 3.3 Cross-field rules

`kyc_rules(schema_version_id, code, phase SAVE|SUBMIT, validator_key, params jsonb, message)`

`validator_key` names a Java validator from a fixed registry. There are no executable expressions in the database. Initial validators:

| key | what it checks |
|---|---|
| `LIST_COUNT` | e.g. exactly one REGISTERED address (params: section, field, value, min, max) |
| `SUM_MAX` | e.g. total ownership ≤ 100 |
| `ANY_GTE` | e.g. at least one owner ≥ `uboThresholdPct`, **or** a controlling person is given |
| `MIN_AGE` | owner age ≥ `minOwnerAge` |
| `ID_MATCHES_DOB` | NA national ID: first 6 digits = YYMMDD of the date of birth |
| `NAME_MATCH` | bank account holder name ≈ legal or trading name (warning only) |
| `NOT_BEFORE` | e.g. incorporation date ≤ today, ID expiry > today |
| `UNIQUE_IN_PLATFORM` | registration number, tax ID or owner ID number not already used by another active org. This is a warning or an error per rule; it uses the blind index (§8). |

`SAVE` rules are shape checks: types, lengths, list sizes, sums. A draft can't be saved if they fail. `SUBMIT` rules add completeness and the cross-field checks. Every failure is returned together, with the field paths.

### 3.4 Choice lists

- `kyc_lists(code, country nullable, description)`
- `kyc_list_items(list_code, value, label, parent_value, attributes jsonb, sort, active)`

Rules for lists:
- **Values are never deleted, only deactivated.** An old application always still renders its value.
- `attributes` carries data that plugins use, e.g. MCC `{"vertical":"RETAIL","risk":"HIGH"}`, country `{"fatfGreyList":true}`, branch `{"branchCode":"483872"}`.
- **Large lists (MCC codes, bank branches) are loaded from CSV seed files** inside the migration, not typed out as SQL.
- Lists are cached in memory per `(list_code, version of the seed)`. They change only on deploy, so the cache can't go stale.

### 3.5 Conditions (`visible_when`, `required_when`)

A small, safe JSON grammar that is evaluated identically in Java and TypeScript:

```json
{"field": "businessProfile.entityType", "in": ["PRIVATE_COMPANY","PUBLIC_COMPANY"]}
{"all": [ {...}, {...} ]}   {"any": [ ... ]}   {"not": {...}}
{"field": "ubos[].politicallyExposedPerson", "eq": true}      // relative to the current list item
{"context": "orgType", "in": ["MERCHANT"]}                    // context: orgType, licenceType, country
```

**Hidden fields are removed from the stored document on save.** A hidden value can't get through to the record.

## 4. The KYC document

- The document stays **one JSON object in `application_json`**. Its keys are the section keys and field keys from the schema version.
- The fixed `KycPayload` record is replaced by schema-driven validation of a `JsonNode`.
- **Unknown keys are rejected** with a list of them; today they are silently dropped.
- The NA/PSP v1 schema keeps today's section keys (`businessProfile`, `operationalDetails`, `addresses`, `ubos`, `bankAccounts`). Your sample payload therefore still maps one-to-one, with renamed enum values (§10).
- A typed **`KycFacts`** view is built from canonical tags (legal name, owners, accounts, MCC …). It is what workflow plugins, providers and the risk scorer receive, so they never parse country-specific JSON.

## 5. NA / PSP / BUSINESS v1 (frontend-enabled)

> Sources: `response1.md` (Financial Intelligence Act 13 of 2012 as amended by Act 6 of 2023, FIC Guidance Note 01 of 2025, BIPA, NamRA). Namibian law says *prominent influential person* (PIP) for what is generally called a PEP; the screens use PIP.

### 5.1 Lists

| list_code | values |
|---|---|
| `NA_ENTITY_TYPE` | `SOLE_PROPRIETOR`, `PARTNERSHIP`, `CLOSE_CORPORATION`, `PRIVATE_COMPANY` ((Pty) Ltd), `PUBLIC_COMPANY` (Ltd), `SECTION_21_COMPANY` (not for gain), `TRUST`, `STATE_OWNED_ENTERPRISE` |
| `COUNTRY` | ISO 3166-1 alpha-2, all countries; attributes `fatfGreyList`, `fatfBlackList`, `sanctioned` |
| `NA_REGION` | Erongo, Hardap, //Kharas, Kavango East, Kavango West, Khomas, Kunene, Ohangwena, Omaheke, Omusati, Oshana, Oshikoto, Otjozondjupa, Zambezi |
| `ADDRESS_TYPE` | `REGISTERED`, `OPERATING`, `POSTAL` |
| `ID_TYPE` | `NATIONAL_ID`, `PASSPORT`, `DRIVING_LICENCE`, `RESIDENCE_PERMIT`, `WORK_PERMIT` |
| `UBO_ROLE` (multi) | `SHAREHOLDER`, `MEMBER` (CC), `PARTNER`, `PROPRIETOR`, `DIRECTOR`, `SENIOR_MANAGER`, `SETTLOR`, `TRUSTEE`, `PROTECTOR`, `BENEFICIARY`, `NOMINEE`, `NOMINATOR`, `AUTHORISED_SIGNATORY`, `CONTROLLING_PERSON` |
| `CONTROL_BASIS` | used when nobody reaches 25%: `SENIOR_MANAGEMENT`, `APPOINTS_BOARD_MAJORITY`, `MATERIAL_INFLUENCE`, `ECONOMIC_BENEFIT`, `OTHER_CONTROL` |
| `PIP_CATEGORY` | FIA Schedule 6: `HEAD_OF_STATE_GOVERNMENT`, `MINISTER_DEPUTY`, `SENIOR_POLITICIAN`, `MEMBER_OF_PARLIAMENT`, `SENIOR_GOVERNMENT_OFFICIAL`, `POLITICAL_PARTY_GOVERNING_BODY`, `LOCAL_REGIONAL_COUNCILLOR`, `PUBLIC_ENTERPRISE_EXECUTIVE`, `JUDICIAL_OFFICER`, `AMBASSADOR`, `SENIOR_MILITARY_OFFICER`, `TRADITIONAL_LEADER`, `RELIGIOUS_LEADER`, `PRIVATE_SECTOR_SENIOR_EXECUTIVE`, `INTERNATIONAL_ORG_EXECUTIVE`, `FOREIGN_PIP`, `FAMILY_MEMBER`, `CLOSE_ASSOCIATE` |
| `NA_BANK` | Bank Windhoek, First National Bank Namibia, Standard Bank Namibia, Nedbank Namibia, Bank BIC Namibia, Letshego Bank Namibia, Banco Atlántico (foreign branch), plus `FOREIGN` |
| `NA_BANK_BRANCH` | per bank, with attribute `branchCode` (`^\d{5,6}$`), parent = bank. Seeded from the published code lists (e.g. Bank Windhoek Windhoek `481972`, FNB Windhoek `280172`, Nedbank Windhoek South `461629`, Standard Bank Windhoek `082372`). **The source list dates from 1 June 2016; each bank must confirm the current codes before production.** |
| `NA_ACCOUNT_TYPE` | `CURRENT` (cheque), `SAVINGS`, `TRANSMISSION` |
| `CURRENCY` | `NAD`, `ZAR`, `USD` |
| `MCC` | ISO 18245 codes, e.g. `5411 – Grocery stores`; attributes `vertical`, `risk` |
| `INDUSTRY_VERTICAL` | `RETAIL`, `FOOD_BEVERAGE`, `HOSPITALITY`, `TRANSPORT`, `HEALTH`, `EDUCATION`, `PROFESSIONAL_SERVICES`, `WHOLESALE_DISTRIBUTION`, `MANUFACTURING`, `AGRICULTURE`, `CONSTRUCTION`, `TELECOMS_UTILITIES`, `GOVERNMENT`, `NON_PROFIT`, `OTHER` |
| `NA_VOLUME_BAND` | `<50K`, `50K-250K`, `250K-1M`, `1M-5M`, `>5M` (N$/month). **These are internal risk tiers** recorded in the institution's risk assessment (FIA s.20A), not a legal requirement; the screen labels them that way. |
| `TRADING_ROLE` | `MANUFACTURER`, `IMPORTER`, `WHOLESALER`, `DISTRIBUTOR`, `RETAILER`, `SERVICE_PROVIDER` |
| `TURNOVER_BAND` | `<1M`, `1M-10M`, `10M-50M`, `>50M` (N$/year) |

### 5.2 Fields

**Step 1: Business** (`businessProfile`)

| field | type | required | rules |
|---|---|---|---|
| `entityType` | ENUM `NA_ENTITY_TYPE` | always | chosen first; it re-resolves the profile and drives the rest of the form |
| `legalBusinessName` | TEXT 2–200 | always | tag `LEGAL_NAME` |
| `tradingName` | TEXT ≤200 | no | |
| `registrationNumber` | TEXT | unless `SOLE_PROPRIETOR` | stored as typed (e.g. `CC/2020/06396`, `2017/1055`, `78/01799`); validated after normalising (strip `/ - .` and spaces, uppercase) against `^([A-Z]{2})?\d{3,10}$`. The normalised value feeds `UNIQUE_IN_PLATFORM`. |
| `incorporationDate` | DATE PAST | unless `SOLE_PROPRIETOR` | |
| `taxIdentificationNumber` | TEXT | always | NamRA TIN `^\d{8}$`. A 7-digit legacy reference is accepted and stored with a leading `0`. `SECRET`, `LAST4` |
| `vatNumber` | TEXT | no | optional; a help text notes VAT registration is compulsory from N$1,000,000 taxable supplies a year |
| `countryOfRegistration` | COUNTRY | always | defaults to `NA`; fixed for this profile |

**Step 2: Operations** (`operationalDetails`). Fields depend on org type.

| field | type | shown for | rules |
|---|---|---|---|
| `mcc` | ENUM `MCC` (searchable) | MERCHANT, SUPPLIER | required for MERCHANT |
| `industryVertical` | ENUM, derived from `mcc` | all | editable only when no MCC is shown |
| `tradingRole` | ENUM `TRADING_ROLE` | MANUFACTURER, DISTRIBUTOR, SUPPLIER | required |
| `expectedMonthlyVolume` | ENUM `NA_VOLUME_BAND` | MERCHANT, SUPPLIER | required |
| `averageTicketSize` | MONEY NAD | MERCHANT | ≥ 0 |
| `annualTurnover` | ENUM `TURNOVER_BAND` | MANUFACTURER, DISTRIBUTOR | required |
| `websiteUrl`, `mobileAppLink` | URL | all | https only |
| `storefrontDetails` | TEXT ≤500 | MERCHANT | |

**Step 3: Addresses** (`addresses`, LIST 1–5)

| field | type | rules |
|---|---|---|
| `addressType` | ENUM | `LIST_COUNT`: exactly one REGISTERED |
| `sameAsRegistered` | BOOLEAN | shown when not REGISTERED; when true, the other fields are hidden and copied on save |
| `streetLine` | TEXT ≤200 | required |
| `town` | TEXT ≤100 | required |
| `region` | ENUM `NA_REGION` | required when country = NA |
| `postalCode` | TEXT ≤10 | optional for NA |
| `country` | COUNTRY | default NA |

**Step 4: Owners and controllers** (`ubos`, LIST 1–20)

| field | type | rules |
|---|---|---|
| `fullLegalName` | TEXT | tag `UBO_NAME` |
| `roles` | MULTI_ENUM `UBO_ROLE` | choices filtered by entity type (CC → MEMBER, …) |
| `dateOfBirth` | DATE PAST | `MIN_AGE`; `PII`, `HIDE` |
| `nationality` | COUNTRY | |
| `residentialAddress` | TEXT | `PII`, `HIDE` |
| `identificationType` | ENUM `ID_TYPE` | `NATIONAL_ID` only when nationality = NA |
| `identificationNumber` | TEXT | NA national ID `^\d{11}$` + `ID_MATCHES_DOB`; `SECRET`, `LAST4` |
| `identificationExpiryDate` | DATE FUTURE | not for NA national ID |
| `ownershipType` | ENUM `DIRECT`/`INDIRECT` | required with ownership. For INDIRECT, `effectiveOwnershipPercentage` is the stake multiplied down the chain, and the 25% test uses it. |
| `ownershipPercentage` | PERCENT 0–100 | required when roles include SHAREHOLDER, MEMBER, PARTNER or PROPRIETOR; `SUM_MAX` 100 |
| `votingRightsPercentage` | PERCENT 0–100 | optional; the 25% test considers ownership **or** voting rights |
| `controlBasis` | ENUM `CONTROL_BASIS` | required for a CONTROLLING_PERSON or SENIOR_MANAGER |
| `politicallyExposedPerson` | BOOLEAN | labelled "Prominent influential person (PIP)"; required |
| `pipCategory`, `pipPosition` | ENUM `PIP_CATEGORY`, TEXT | required when PIP = true |

Rule `ANY_GTE`: at least one person with ownership or voting rights ≥ 25% (`uboThresholdPct`). If nobody reaches it, at least one person must carry a `controlBasis` (FIA Schedule 5 fallback). Trusts need the settlor, every trustee, any protector, and the beneficiaries.

Entity type drives this step:
- `SOLE_PROPRIETOR`: exactly one owner, PROPRIETOR at 100% (a `LIST_COUNT` rule plus an exact value).
- `TRUST`: at least one TRUSTEE.

**Step 5: Bank account** (`bankAccounts`, LIST 1–3)

| field | type | rules |
|---|---|---|
| `beneficiaryName` | TEXT | `NAME_MATCH` warning |
| `bankCode` | ENUM `NA_BANK` | |
| `branch` | ENUM `NA_BANK_BRANCH` (parent = bank) | the branch code is filled in from the item attribute. A "branch not listed" option allows a manual code matching `^\d{5,6}$`, flagged for ops. |
| `accountNumber` | TEXT `^\d{6,16}$` | required unless FOREIGN; `SECRET`, `LAST4` |
| `accountType` | ENUM `NA_ACCOUNT_TYPE` | |
| `currency` | ENUM `CURRENCY` | NAD unless FOREIGN |
| `iban`, `swiftBic`, `foreignBankName` | TEXT | **shown only when `bankCode = FOREIGN`** |

**Step 6: Review and submit.** A rendered summary. Each problem from the SUBMIT rules links back to its field.

### 5.3 NA/PSP workflow config (default)

```json
{
  "approvalRequired": true,
  "entryCheck": "OPTIONAL",
  "verification": ["IDENTITY", "SANCTIONS", "PEP"],
  "stages": [
    {"key": "ops-review", "actor": {"relation": "PLATFORM", "function": "OPS_REVIEWER"},
     "outcomes": ["APPROVE", "RETURN", "REJECT", "BLOCK"]}
  ],
  "contributors": ["high-risk-or-pep"]
}
```

The `high-risk-or-pep` contributor runs when the risk is HIGH, any person is a PIP, or any check returned a HIT. It adds a `compliance-review` stage (COMPLIANCE_OFFICER) and then a **`senior-management-approval` stage (SENIOR_APPROVER)**: FIA s.23(2)(a) and s.23A require senior management to approve the relationship for high-risk clients and PIPs. This applies to PSP as well as EMI.

## 6. Workflow engine

### 6.1 Plugin contract

```java
public interface KycWorkflow {
    String key();                         // "configurable"
    int version();
    WorkflowPlan plan(WorkflowContext ctx);   // called at submission
}

public interface KycStageContributor {
    String key();                          // "high-risk-or-pep"
    List<StageDef> extraStages(WorkflowContext ctx);   // called after verification and on each stage approval
}

record WorkflowPlan(boolean approvalRequired, EntryCheck entryCheck /* NONE|OPTIONAL|REQUIRED */,
                    List<CheckType> verification, List<StageDef> stages) {}
record StageDef(String key, ActorRule actor, Set<Outcome> outcomes, OnReturn onReturn /* DRAFT|PREVIOUS_STAGE */) {}
record ActorRule(Relation relation /* SELF_ORG|PARENT_ORG|ANCESTOR_ORG|PLATFORM */,
                 OrgMemberRole orgRole /* for org relations */, PlatformFunction function /* for PLATFORM */) {}
// WorkflowContext: KycFacts, org + tree, licence, country, risk rating, verification results, task history
```

### 6.2 The three questions

**1. Is approval needed?** If `approvalRequired = false`, the engine still requires:
- completeness;
- every configured verification returned **CLEAR**.

If both hold, the application goes live automatically. Any HIT, ERROR or timeout falls back to a manual `ops-review` stage. **"No approval" can only ever auto-approve clean cases.**

**2. Maker-checker at entry:**
- `REQUIRED`: on submit, the application goes to `ENTRY_CHECK`. Another user in the **maker's own org** with role ADMIN must confirm or send it back before anything leaves the org.
  - When a parent org is entering a child's KYC during onboarding, the maker's org is the parent. This replaces today's "awaiting another admin" onboarding decision.
  - If the maker's org has no other active ADMIN, submit is refused with `ENTRY_CHECKER_UNAVAILABLE`.
- `OPTIONAL`: the maker chooses "send to a colleague to check", or submits directly.
- `NONE`: no entry check.

**3. Who makes it live:** the ordered `stages`, with contributors inserting extra stages.
- **Plans only grow.** Contributors may add stages but never remove stages already in the plan.
- The final APPROVE of the last stage triggers go-live (§6.5).

### 6.3 Engine invariants (no plugin can override them)

1. The maker never acts on a check or approval task of their own application.
2. One user acts on at most one task per application: entry checker ≠ stage-1 approver ≠ stage-2 approver.
3. The actor's org must be operational, and the application's org must not be suspended. An org suspended mid-workflow freezes the application; lifting the hold resumes it.
4. Every transition writes an audit row and takes an optimistic lock (`version`).
5. A PLATFORM stage needs a user holding the named platform function. An org-relation stage resolves the relation against the closure table.
6. The plan is stored on the application (`workflow_plan` jsonb) when it is created, and extended only. A deploy that changes the workflow code doesn't rewrite plans in flight.
7. If a plugin throws, the application stays where it is, and an `ops-review` task with reason `WORKFLOW_ERROR` is opened. It never auto-approves.

### 6.4 States

```
DRAFT ──submit──▶ ENTRY_CHECK ──confirm──▶ VERIFYING ──all results──▶ IN_REVIEW(stage n) ──approve last──▶ APPROVED ─▶ go-live
  ▲                   │return                 │timeout/error → manual stage    │return
  └───────────────────┴───────────────────────┴────────────────────────────────┘
                                                                 IN_REVIEW ──reject──▶ REJECTED (final)
                                                                 IN_REVIEW ──block───▶ BLOCKED  (final, sanctions)
DRAFT/ENTRY_CHECK ──withdraw──▶ WITHDRAWN
```

What each outcome does:
- **RETURN** sends the application back to DRAFT, or to the previous stage, with a note. The application can be edited and resubmitted; the resubmission is a new submission version.
- **REJECT and BLOCK** are final for this application. For an org still being onboarded:
  - the org becomes REJECTED or BLOCKED;
  - its pending users are archived, as today ([M7]);
  - the business can be onboarded again later.

### 6.5 Go-live (single gate, D8)

`KycGoLiveHandler` runs **in core**, in one core transaction, when it receives the APPROVED decision from kyc-service. §16.5 has the cross-service sequence.

1. **For an org still in PENDING:**
   - re-check the onboarder's rights (`RIGHTS_STALE`) and that the parent is operational. This logic moves here from `OrgOnboardingService.decide`.
   - `lifecycle.transition(org, ACTIVE)`;
   - run provisioning (`provisioningApproved`);
   - `activation.issue(admin)`.
2. Write the KYC outcome onto the org: `kycStatus = VERIFIED`, plus the risk rating and next review date from the `KycRiskScorer`.
3. Mark the onboarding request `APPROVED`. It is now only a record of who started the onboarding.
4. Audit `kyc_approved_go_live`.

For an org that is already ACTIVE (legacy orgs, later re-KYC), only step 2 runs.

**Effect on onboarding requests:**
- `POST /api/orgs/onboarding/{id}/decide` and `POST /api/ops/onboarding-requests/{id}/decision` stay **only for `ADMIN_RECOVERY`**.
- For `ORG_ONBOARDING` requests they return 409 `USE_KYC_WORKFLOW`.
- Submitting a child org now *requires* a KYC document, or starts an empty DRAFT the parent completes later. The org stays PENDING until its KYC goes live.

### 6.6 Platform functions

There are only two platform roles today (`TPTS_OPS`, `MINDLABS_ADMIN`). Stages need finer-grained functions, so add `user_platform_functions(user_id, function)` with the functions `OPS_REVIEWER`, `COMPLIANCE_OFFICER`, `MLRO` and `SENIOR_APPROVER`.

Seeding:
- TPTS_OPS users get OPS_REVIEWER.
- One seeded compliance user gets COMPLIANCE_OFFICER and MLRO.
- **MINDLABS_ADMIN gets no review function.** `ops-admin-mindlabs` is a sandbox test account with no compliance role.

## 7. Providers (third-party KYC)

```java
public interface KycCollectionProvider {
    String key();                                      // "native-form", later e.g. a hosted provider
    CollectionStart start(KycApplicationRef app);       // IN_APP, or REDIRECT(url)
    NormalizedDocument onEvent(ProviderEvent e);        // maps provider data → schema JSON by canonical tags
}

public interface KycCheckProvider {
    String key();
    boolean supports(CheckType type, String country);
    CheckHandle request(CheckRequest req);              // sync result, or PENDING with provider reference
    CheckResult onEvent(ProviderEvent e);               // CLEAR | HIT | ERROR, with details
    Set<Sensitivity> allowedData();                     // most sensitive data this provider may receive
}
```

The core always does these, whichever provider is used:

- **Data minimisation.** The engine builds `CheckRequest` from `KycFacts`, holding only the fields the check needs and that `allowedData()` and the profile's data-sharing setting permit. A plugin never receives the raw document.
- **Inbound events** arrive at `POST /api/kyc/providers/{key}/events` (no user login). Before any plugin sees an event, the core:
  - verifies its signature using the provider's secret from the environment, not from the database;
  - rejects it if its timestamp is outside a 5-minute window;
  - de-duplicates it on `(provider, event_id)` in `kyc_provider_events`, which is unique.
- **Results are stored as our own record.** `kyc_verifications` holds one row per check:
  - application and subject (e.g. `ubos[1]`), provider, provider reference;
  - status;
  - the normalised result and the raw response, which is encrypted.
- **Timeouts.** A check still PENDING after `params.checkTimeoutHours` (default 24) is marked ERROR, and the manual-fallback rule (§6.2) applies.
- **Legacy compatibility.** The `simulated` provider also writes today's `kyc_checks` rows, so the existing ops screening-checks screen keeps working during the switch-over.

Third-party data **collection** for NA/PSP v1 is out of scope. The interface and the `native-form` built-in are in scope, and a fake redirect provider is used in tests only.

## 8. Sensitive data

The `sensitivity` set on each field in the master drives everything here.

**SECRET fields:**
- encrypted inside the JSON with AES-GCM before it is stored. The value becomes an `{"enc":"v1:…"}` object, and the key comes from environment or KMS configuration, with a key id for rotation.
- given an HMAC blind index row in `kyc_search_index(application_id, canonical_tag, hmac)`. Duplicate checks (`UNIQUE_IN_PLATFORM`) and ops search by exact value use it, without decrypting anything.

**Masking.** PII and SECRET fields are masked for ANCESTOR viewers by the field's `mask` rule. The access rules (SELF / PARENT / PLATFORM full, ANCESTOR masked, others 404) stay as they are today.

**Logs.** Provider raw responses are encrypted with the same key. Audit rows never contain SECRET values.

## 9. Data model changes

Everything in this section lives in **`kyc-db`**. The exceptions are `user_platform_functions` and the `organizations` columns, which stay in core's database; core also gets `pending_go_live`, the outbox and the stream-cursor tables (§16).

**`kyc_applications`:**
- **Added:** `country`, `licence_type`, `subject_type`, `entity_type`, `profile_id`, `schema_version_id`, `workflow_key`, `workflow_version`, `workflow_plan` (jsonb), `current_stage`, `risk_rating`, `submission_no`.
- **`status` values:** DRAFT, ENTRY_CHECK, VERIFYING, IN_REVIEW, APPROVED, REJECTED, BLOCKED, WITHDRAWN.
- **Uniqueness:** `org_id` is no longer unique. A partial unique index allows one *open* application per org, so re-KYC later creates a new row and history is kept.
- **Unchanged:** the tree columns and `is_suspended`.

**New tables:**

| table | purpose |
|---|---|
| `kyc_application_submissions` | a frozen copy of the document per submission (`submission_no`), replacing `submitted_json` |
| `kyc_workflow_tasks` | one row per entry check or stage: `application_id`, `submission_no`, `stage_key`, `seq`, `actor_rule`, `status OPEN/DONE/CANCELLED`, `outcome`, `acted_by`, `acted_at`, `note`. This is both the approval inbox and the history. **A DB CHECK plus a unique index on `(application_id, submission_no, acted_by)` enforce invariant 2.** |
| `kyc_verifications`, `kyc_provider_events`, `kyc_search_index` | §7, §8 |
| `user_platform_functions` | §6.6 |
| the master tables | §3 |

**`organizations`:** add `country` and `licence_type`, inherited from the parent at creation. The root is `NA`/`PSP`. Together with the entity type, they select the profile.

## 10. Migration of what exists

**`V8`** creates the tables.

**`V9`** seeds three things:
- **NA/PSP/BUSINESS v1:** one profile per org type, the lists, sections, fields and rules.
- **NA/EMI/BUSINESS v1:** backend only, `ui_enabled = false`, see §11.
- **Existing `kyc_applications` rows**, which become NA/PSP v1 with enum values renamed:

| old value | new value |
|---|---|
| `PRIVATE_LIMITED` | `PRIVATE_COMPANY` |
| `PUBLIC_LIMITED` | `PUBLIC_COMPANY` |
| `SOLE_PROPRIETORSHIP` | `SOLE_PROPRIETOR` |
| `CHECKING` | `CURRENT` |
| `DRIVING_LICENSE` | `DRIVING_LICENCE` |
| `city` | `town` |
| `state` | `region` |

The renamed values are matched against the region list where possible; values that can't be mapped are kept, and the application is flagged `needs_upgrade`. Status mapping:
- SUBMITTED becomes IN_REVIEW with a plan of the default ops-review stage;
- APPROVED and REJECTED are kept as history;
- `submitted_json` becomes submission 1.

**Pending `ORG_ONBOARDING` requests** become KYC applications in IN_REVIEW (or DRAFT if they have no KYC), with an ops-review task.

**Existing SECRET values** are encrypted by a one-off Java migration (`KycEncryptBackfill`) that runs at startup, because encryption needs the application key and so can't be done in SQL.

## 11. NA / EMI / BUSINESS v1 (backend only)

EMI uses the NA/PSP schema plus:

| section | fields |
|---|---|
| `relationship` | purpose of the relationship (list), expected products (multi), expected start date |
| `sourceOfFunds` | source of funds (multi list: TRADING_REVENUE, SHAREHOLDER_LOAN, INVESTMENT, GRANT, OTHER + text), source of wealth for each owner ≥ threshold |
| `expectedActivity` | monthly inflow and outflow bands, number of transactions band, counterparty countries (multi COUNTRY), cross-border yes/no |
| `directors` | a LIST separate from the owners: name, DOB, nationality, ID, PEP |
| `authorisedSignatories` | name, ID, signing limits |

Params: `uboThresholdPct` stays **25**, because Namibian law sets no lower threshold for e-money issuers. PIP details are mandatory, the `HIGH` review cycle is 12 months, and entry check is `REQUIRED`.

Workflow:

```json
{"approvalRequired": true, "entryCheck": "REQUIRED",
 "verification": ["IDENTITY","SANCTIONS","PEP","ADVERSE_MEDIA","COMPANY_REGISTRY","BANK_ACCOUNT"],
 "stages": [
   {"key":"ops-review","actor":{"relation":"PLATFORM","function":"OPS_REVIEWER"}},
   {"key":"compliance-review","actor":{"relation":"PLATFORM","function":"COMPLIANCE_OFFICER"}}],
 "contributors": ["high-risk-or-pep-mlro"]}
```

The `high-risk-or-pep-mlro` contributor adds an `MLRO` stage and then the `senior-management-approval` stage for HIGH risk or a PIP.

EMI is covered by backend tests only; no screen offers it.

## 12. API

All of these are served by `kyc-service` under `/api/kyc/**` (§16). The paths that used to be `/api/orgs/{id}/kyc` move to `/api/kyc/orgs/{id}`.

| endpoint | purpose |
|---|---|
| `GET /api/kyc/schema?orgId=&entityType=` | resolves the profile and returns the sections, fields, conditions, rules (for client hints) and small lists inline. Cacheable per schema version. |
| `GET /api/kyc/lists/{code}?parent=&q=` | large lists, searched and paged (MCC, branches) |
| `GET /api/kyc/orgs/{id}`, `GET /api/kyc/orgs/me`, `GET /api/kyc/orgs` | as today, plus profile, stage, tasks and verifications |
| `PUT /api/kyc/orgs/{id}?expectedVersion=` | save a draft; the body is the schema-shaped JSON |
| `POST /api/kyc/orgs/{id}/submit` `{sendToChecker?: bool}` | |
| `POST /api/kyc/orgs/{id}/withdraw` | |
| `POST /api/kyc/orgs/{id}/upgrade` | move a DRAFT to the profile's current schema version |
| `GET /api/kyc/tasks?scope=mine` | approval inbox: every open task the caller is entitled to act on, across the org portals and the admin portal |
| `POST /api/kyc/tasks/{taskId}/decision` `{outcome, note}` | APPROVE / RETURN / REJECT / BLOCK, limited to the stage's outcomes |
| `POST /api/kyc/providers/{key}/events` | provider callbacks (signature-verified) |
| ~~`POST /api/orgs/{id}/kyc/decision`~~, ~~`kyc` field on `POST /api/orgs/me/children`~~ | removed: decisions are tasks, and the document goes straight to kyc-service |

## 13. Screens (NA / PSP only)

**Org portals: KYC page.** A wizard built from `GET /api/kyc/schema`.

```
┌ Kalahari Devices · MANUFACTURER · IN REVIEW (ops-review) ───────────────────────────┐
│ Platform Root › Kalahari Devices · depth 1                                          │
│ ① Business ✓ ─ ② Operations ✓ ─ ③ Addresses ✓ ─ ④ Owners ⚠ ─ ⑤ Bank ✓ ─ ⑥ Review   │
├─────────────────────────────────────────────────────────────────────────────────────┤
│ Owners and controllers                                    Total ownership: 60% ⚠   │
│ ┌ Owner 1 ─────────────────────────────────────────────────────────────── Remove ┐ │
│ │ Full legal name [            ]  Roles [Member ▾][Director ▾]                    │ │
│ │ Date of birth [ yyyy-mm-dd ]    Nationality [Namibia ▾]                         │ │
│ │ ID type [National ID ▾]  ID number [###########]  ⓘ must match date of birth    │ │
│ │ Ownership [ 60 ]%   PEP ( ) Yes (•) No                                          │ │
│ └─────────────────────────────────────────────────────────────────────────────────┘ │
│ [+ Owner]                                ⚠ at least one owner must hold ≥ 25%       │
│                                                [Save draft]  [Back]  [Next ▸]      │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

- **Generic field components** are chosen by `data_type`. ENUM and COUNTRY use searchable selects, and dependent lists (bank → branch) reload when the parent changes. Conditions run with the shared TypeScript evaluator.
- **The step bar** shows a ✓ or ⚠ per step from SAVE and SUBMIT results. The **review step** lists every problem with a "fix" link to the field.
- **The timeline panel** shows submissions, the entry check, verification results, and each stage with who acted, when and the note.
- **Approval inbox** (new "Approvals" tab in the org portals) lists entry-check tasks, plus any stage whose actor relation is PARENT_ORG or ANCESTOR_ORG. The admin portal gets the same inbox for platform stages.
- **Read-only mode** is the same wizard, disabled, with masked values for ancestors.

## 14. Test plan (new `TUat20KycMasterWorkflowTests`)

- **Master:**
  - the boot validator fails on an unknown `workflow_key`, `list_code` or `validator_key`, and on an ambiguous profile;
  - a published schema version can't be edited.
- **Validation:**
  - every NA field rule, including CC versus company registration pattern, `ID_MATCHES_DOB`, threshold, sole-proprietor 100% and foreign-bank fields;
  - a hidden field is stripped;
  - an unknown key is rejected;
  - the original sample payload produces exactly the expected errors.
- **Workflow:**
  - entry check REQUIRED, OPTIONAL and NONE;
  - `ENTRY_CHECKER_UNAVAILABLE`;
  - the same user can't act twice;
  - the maker can't approve;
  - a contributor adds a compliance stage for a PEP;
  - plans only grow;
  - a plugin exception opens a `WORKFLOW_ERROR` task and doesn't approve;
  - a suspension freezes the workflow and lifting the hold resumes it;
  - `approvalRequired = false` approves automatically only when every check is CLEAR, and a HIT falls back to manual review.
- **Single gate:**
  - onboard a child; it is PENDING until the last stage approves;
  - then ACTIVE, provisioned and with its activation link queued;
  - REJECT archives its users;
  - `RIGHTS_STALE` is checked at go-live;
  - the old decide endpoint returns `USE_KYC_WORKFLOW` for org onboarding, and still works for admin recovery.
- **Providers:**
  - a bad signature, a stale timestamp and a replayed event are all rejected;
  - a check that times out falls back to manual review;
  - a provider never receives fields beyond its `allowedData`.
- **Sensitive data:**
  - SECRET values are encrypted in the DB (raw `select` shows no plaintext);
  - the blind-index duplicate check works;
  - masking for ancestors works.
- **EMI:** the stricter profile's validation and its three-stage workflow, through the API only.
- **Regression:**
  - TUat17 (hierarchical onboarding) is updated for the single gate;
  - TUat18 and TUat19 stay green;
  - the full pack and Postgres `ddl validate` pass.

## 15. Implementation phases

0. **Service split (§16.8 steps 1–2):**
   - the `proto/` module;
   - the `kyc-service` and `kyc-db` containers, PKI certificates, gateway routes;
   - the core gRPC server (OrgDirectory) and client;
   - the event outboxes and stream cursors;
   - moving the current KYC code across with its TUat19 tests.
1. **Master and validation engine** (in kyc-service):
   - V8 tables, master loader/cache, boot validator, condition evaluator, validator registry;
   - the NA/PSP v1 seed with the CSV lists;
   - schema-driven save and submit;
   - `GET /api/kyc/schema` and `/lists`.
2. **Sensitive data:** encryption, blind index, masking driven by the master, the legacy import (§16.8 step 3) and the encryption backfill.
3. **Workflow engine:** plan, tasks, entry check, stages, contributors, invariants, platform functions (core) and the inbox API.
4. **Single gate across services:** `PrepareGoLive`, the `DecisionEvent` stream, core's go-live handler with `pending_go_live` and retry, `REVOKED`, onboarding-request changes, TUat17 updates.
5. **Providers:** interfaces, the `simulated` and `manual` checks, the event endpoint with signature and replay protection, timeouts.
6. **EMI v1 profile** and its tests.
7. **Frontend:** the schema-driven wizard on `/api/kyc/**`, two-call onboarding, the inbox in the org portals and the admin portal, and the timeline. Then the docker-compose headless E2E (§16.9).

## 16. Deployment and protocol: KYC as a separate service

### 16.1 Why a separate service

The reason is **isolation of personal data, not throughput.** At about 80 orgs a month, call volume is trivial.

- **What gets isolated.** ID numbers, tax IDs, account numbers, owner details and provider responses live in the `kyc-service` container, in its own database. The encryption key is held only by that container.
- **What a core breach exposes.** A bug or breach in the core backend exposes only KYC *status* (approved/rejected, risk rating), never the documents.
- **Independent deploys.** Master-table releases (new rules, lists or workflows) ship without redeploying core.
- **Separate provider callbacks.** Provider webhooks and outbound calls to third parties are isolated from the payment backend.

### 16.2 Topology

```
                         ┌───────────────── edge ─────────────────┐
browser / portals ─HTTPS─▶ trustplane gateway (portal.fapi.local)  │
provider webhooks ─HTTPS─▶   /api/kyc/providers/** (auth: none)    │
                         └──────┬───────────────────────┬─────────┘
                    /api/** (rest)                  /api/kyc/**
                                ▼                       ▼
                     ┌──────────────────┐   gRPC/mTLS   ┌──────────────────────┐
                     │ backend (core)   │◀═════════════▶│ kyc-service          │
                     │ REST :8080       │  :9090 ⇄ :9091│ REST :8081           │
                     │ gRPC :9090       │               │ gRPC :9091           │
                     └────────┬─────────┘               └──────────┬───────────┘
                              ▼                                    ▼
                         db (mipay)                         kyc-db (kyc)   ← own network,
                                                                             own credentials
```

| Container | New/changed | Notes |
|---|---|---|
| `kyc-service` | new | Spring Boot 3.5, Java 21, same stack as core. Exposes REST on 8081 to the gateway only, and gRPC on 9091 to core only. No host ports, except 8081 in dev compose for the portals' proxy. |
| `kyc-db` | new | Postgres 16 on a `kyc-data` network that only `kyc-service` joins |
| `backend` | changed | adds a gRPC server on 9090 (OrgDirectory) and a gRPC client to `kyc-service` |
| `pki-init` | changed | also issues mesh certificates for `backend` and `kyc-service` from the existing platform CA |
| gateway `routes.yaml` | changed | adds `/api/kyc/providers/**` (auth none, body limit 256 KiB, per-IP rate limit) and `/api/kyc/**` (both on `portal.fapi.local`), with the upstream `kyc` over mTLS; both go **before** the `/api/**` wildcard |

### 16.3 Protocol choice

| Path | Protocol | Why |
|---|---|---|
| Browser → kyc-service | HTTPS via the gateway, then JSON REST | Browsers can't speak gRPC without a proxy, and the portals already use REST |
| core ⇄ kyc-service | **gRPC over HTTP/2 with mutual TLS**, protobuf, gzip, keepalives, deadlines (default 400 ms, 2 s for `PrepareGoLive`) | Same pattern and library versions as trustplane's gateway↔rbac (`grpc-java 1.66.0`, `protobuf 3.25.5`). It gives typed contracts that break the build when they drift, one long-lived connection, and server-streaming for events. |
| Events between the two | **gRPC server-streaming with a resumable cursor**, backed by an outbox table on the sending side | Delivered at least once, applied idempotently, and survives restarts. Needs no message broker, which is appropriate at this volume. |

**Identity.** Each side checks the peer certificate's SAN:
- core serves `OrgDirectory` only to `kyc-service`;
- kyc-service serves `Kyc` only to `backend`.

Certificates come from the trustplane platform CA (`/pki/platform-ca.pem`).

**User tokens.** `kyc-service` **never validates user JWTs itself.** It holds no `APP_JWT_SECRET`, because that HMAC secret would let it mint tokens. It forwards the bearer token to core's `Authorize` call, which also applies the session, suspension and PENDING checks that `JwtAuthFilter` does today.
- Results are cached for 5 s per (token hash, org, action).
- The cache is cleared by `WatchOrgEvents`.

### 16.4 Contract

The contract lives in a new top-level `proto/` module, built by both `backend` and `kyc-service`. `buf breaking` runs in CI against `main`.

```proto
// proto/core/v1/org_directory.proto: served by backend, called by kyc-service
syntax = "proto3";
package core.v1;
option java_package = "com.mipay.proto.core.v1";
option java_multiple_files = true;

service OrgDirectory {
  // Authenticates the end user's bearer token and returns their relation to org_id.
  // UNAUTHENTICATED for a bad/expired/revoked token or a suspended/pending user.
  rpc Authorize(AuthorizeRequest) returns (AuthorizeResponse);
  // Is this user allowed to act on a workflow stage with this actor rule for org_id?
  rpc CheckActor(CheckActorRequest) returns (CheckActorResponse);
  rpc GetOrgContext(GetOrgContextRequest) returns (OrgContext);
  // Called by kyc-service before it records the final approval (§16.5).
  rpc PrepareGoLive(PrepareGoLiveRequest) returns (PrepareGoLiveResponse);
  // Org created / renamed / lifecycle change / suspension change, from the given cursor.
  rpc WatchOrgEvents(WatchRequest) returns (stream OrgEvent);
  // Temporary, removed after cut-over: streams legacy V7 kyc_applications rows (§16.8).
  rpc ExportLegacyKyc(ExportLegacyKycRequest) returns (stream LegacyKycRow);
}

enum Relation { RELATION_UNSPECIFIED = 0; NONE = 1; SELF = 2; PARENT = 3; ANCESTOR = 4; PLATFORM = 5; }
enum Action   { ACTION_UNSPECIFIED = 0; VIEW = 1; EDIT = 2; SUBMIT = 3; ACT_ON_TASK = 4; LIST = 5; }

message Principal { string user_id = 1; string user_role = 2; string org_id = 3; string org_role = 4;
                    repeated string platform_functions = 5; }
message AuthorizeRequest  { string bearer_token = 1; string org_id = 2; Action action = 3; }
message AuthorizeResponse { Principal principal = 1; Relation relation = 2; bool actor_operational = 3;
                            bool org_operational = 4; }

message ActorRule { string relation = 1; string org_role = 2; string platform_function = 3; }
message CheckActorRequest  { string user_id = 1; string org_id = 2; ActorRule rule = 3; }
message CheckActorResponse { bool allowed = 1; string reason = 2; }

message GetOrgContextRequest { string org_id = 1; }
message OrgContext {
  string org_id = 1; string org_type = 2; string name = 3; string parent_org_id = 4;
  repeated string tree_path = 5;      // root-first ids, self included
  int32 depth = 6; string country = 7; string licence_type = 8;
  string lifecycle = 9; bool suspended = 10; uint64 version = 11;
}

message PrepareGoLiveRequest  { string org_id = 1; string application_id = 2; }
message PrepareGoLiveResponse { bool ok = 1; string reason = 2; }  // RIGHTS_STALE | PARENT_NOT_OPERATIONAL | ORG_SUSPENDED | ALREADY_LIVE

message WatchRequest { uint64 after_cursor = 1; string consumer = 2; }
message OrgEvent {
  uint64 cursor = 1; string event_id = 2;
  enum Type { TYPE_UNSPECIFIED = 0; ORG_CREATED = 1; ORG_UPDATED = 2; LIFECYCLE_CHANGED = 3; SUSPENSION_CHANGED = 4; }
  Type type = 3; OrgContext org = 4;
  map<string, bool> suspended_by_org = 5;   // SUSPENSION_CHANGED: new is_suspended for every org in the subtree
}
message ExportLegacyKycRequest { uint64 after_cursor = 1; }
message LegacyKycRow { string org_id = 1; string status = 2; bytes application_json = 3; bytes submitted_json = 4;
                       string submitted_by = 5; string decided_by = 6; string decision_note = 7;
                       int64 submitted_at_ms = 8; int64 decided_at_ms = 9; }
```

```proto
// proto/kyc/v1/kyc.proto: served by kyc-service, called by backend
syntax = "proto3";
package kyc.v1;
option java_package = "com.mipay.proto.kyc.v1";
option java_multiple_files = true;
import "core/v1/org_directory.proto";

service Kyc {
  // Creates the (empty DRAFT) application for a newly onboarded org. Idempotent on idempotency_key.
  rpc StartApplication(StartApplicationRequest) returns (KycStatus);
  rpc GetStatus(GetStatusRequest) returns (KycStatus);
  rpc BatchGetStatus(BatchGetStatusRequest) returns (BatchGetStatusResponse);   // tree / list views in core
  // Final decisions, from the given cursor. Core applies each one idempotently on event_id.
  rpc WatchDecisions(core.v1.WatchRequest) returns (stream DecisionEvent);
  // Core tells kyc-service what happened when it applied a decision (shown on the timeline).
  rpc ReportGoLive(ReportGoLiveRequest) returns (ReportGoLiveResponse);
  rpc Ping(PingRequest) returns (PingResponse);
}

message StartApplicationRequest { string idempotency_key = 1; core.v1.OrgContext org = 2; string started_by_user_id = 3; }
message GetStatusRequest { string org_id = 1; }
message BatchGetStatusRequest { repeated string org_ids = 1; }
message BatchGetStatusResponse { repeated KycStatus statuses = 1; }
message KycStatus {
  string org_id = 1; string application_id = 2;
  string status = 3;          // NOT_STARTED | DRAFT | ENTRY_CHECK | VERIFYING | IN_REVIEW | APPROVED | REJECTED | BLOCKED | WITHDRAWN
  string current_stage = 4; string risk_rating = 5; string next_review_date = 6; int64 decided_at_ms = 7;
}
message DecisionEvent {
  uint64 cursor = 1; string event_id = 2; string org_id = 3; string application_id = 4; int32 submission_no = 5;
  enum Outcome { OUTCOME_UNSPECIFIED = 0; APPROVED = 1; REJECTED = 2; BLOCKED = 3; REVOKED = 4; }
  Outcome outcome = 6; string risk_rating = 7; string next_review_date = 8;
  string decided_by = 9; int64 decided_at_ms = 10; string note = 11;   // no document data, ever
}
message ReportGoLiveRequest { string event_id = 1; string result = 2; string reason = 3; }  // APPLIED | REFUSED
message ReportGoLiveResponse {}
message PingRequest {} message PingResponse { string version = 1; }
```

**No personal data crosses into core.** `DecisionEvent` and `KycStatus` carry no document fields; a contract test asserts this. The only data that flows the other way is org name and tree data.

### 16.5 Go-live across two services (single gate, D8)

```
approver ─REST─▶ kyc-service: POST /api/kyc/tasks/{id}/decision {APPROVE} (last stage)
  1 kyc → core  Authorize(token, org, ACT_ON_TASK) + CheckActor(user, org, stage rule)   fail → 401/403
  2 kyc → core  PrepareGoLive(org, app)                     not ok → 409 <reason>, task stays OPEN
  3 kyc tx      task DONE · application APPROVED · outbox row DecisionEvent(event_id)    (one DB tx)
  4 core        receives the event on WatchDecisions(after_cursor)
  5 core tx     if event_id already processed → skip
                else re-run the PrepareGoLive checks:
                  ok   → lifecycle ACTIVE · provisioning · activation.issue(admin) · onboarding request APPROVED
                  fail → pending_go_live row (reason), org stays PENDING
                then record event_id + cursor                                             (one DB tx)
  6 core → kyc  ReportGoLive(event_id, APPLIED | REFUSED, reason)  → timeline entry; REFUSED opens an ops task
```

Steps 1–3 are on the approver's request. Steps 4–6 are asynchronous and normally finish in under a second. REJECTED and BLOCKED follow the same path: core moves the org to REJECTED or BLOCKED and archives the pending users.

**Why both steps 2 and 5 check go-live:**
- Step 2 gives the approver an immediate, clear refusal in the common case.
- Step 5 closes the race where something changes between step 3 and step 5, such as a suspension or a narrowed right.

**When go-live is refused at step 5:**
- A `pending_go_live` row is retried automatically when its blocking condition clears (a hold is lifted, or the parent becomes operational).
- `RIGHTS_STALE` can't clear by itself. Ops either restores the rights and retries, or **revokes** the approval in kyc-service, which emits `REVOKED`. Core then moves the org to REJECTED.

### 16.6 Keeping the tree details and `is_suspended` in the KYC database

`kyc_applications` keeps `org_type`, `org_name`, `parent_org_id`, `tree_path`, `depth` and `is_suspended`, as the original requirement asked. They are now a **replica**, maintained from `WatchOrgEvents`:
- A `SUSPENSION_CHANGED` event carries the new flag for every org in the affected subtree, so kyc-service doesn't need to recompute anything.
- The replica serves lists, filters and the approval inbox without a call per row. For example, an application is in the caller's subtree if the caller's org id is in its `tree_path`.
- **Every write and every decision still calls `Authorize` / `CheckActor` live**, so a replica that lags by a few seconds can never let a suspended org or user act.

### 16.7 Failure behaviour

| Failure | Behaviour |
|---|---|
| core unreachable from kyc-service | KYC REST calls return 503 and nothing is cached as allowed (fails closed). The event streams reconnect from their cursor with backoff. |
| kyc-service down | Payments, logins and every other core feature keep working. The KYC screens show "KYC service unavailable". Child onboarding still creates the PENDING org; core's outbox retries `StartApplication` until it succeeds. |
| Stream drops mid-event | Resumes from the last stored cursor. Duplicate events are skipped on `event_id`. |
| Certificate wrong or expired | The handshake fails, and no data is exchanged. Health checks report the reason. |
| kyc-db down | kyc-service is unhealthy (readiness probe), and the gateway returns 503 for `/api/kyc/**` |

### 16.8 Moving out of the monolith

1. **Contract and skeleton:** `proto/` module, `kyc-service` skeleton, `kyc-db`, PKI and gateway routes; the core gRPC server and client.
2. **Code moves:** `KycApplicationService`, the master, the workflow and providers move to `kyc-service`. Org-related logic stays in core: rights, tree, suspension, provisioning, activation and the go-live handler.
3. **Data:** kyc-service imports the V7 rows once through `ExportLegacyKyc`, then mapping from §10 applies. The `kyc_applications` table in the core DB is dropped in a later release, after the counts match. `ExportLegacyKyc` is then removed.
4. **Frontend:** the KYC calls move to `/api/kyc/...` (§12). Onboarding becomes two calls: create the child in core (which starts the DRAFT through `StartApplication`), then save and submit the KYC on kyc-service. **The KYC document no longer passes through core.** `kyc` is removed from `POST /api/orgs/me/children`.

### 16.9 Testing the split

- **core:** an in-process fake `Kyc` server (`grpc-inprocess`) drives go-live. Tests cover duplicate events, `REFUSED` → `pending_go_live` → automatic retry, and `REVOKED`.
- **kyc-service:** an in-process fake `OrgDirectory`. Tests cover fail-closed on 503, cache expiry on `SUSPENSION_CHANGED`, a lagging replica that still can't act, and no personal fields in `DecisionEvent` (reflection test on the message descriptors).
- **Contract:** `buf breaking` in CI; both builds compile the same proto revision.
- **mTLS:** a call with a wrong-SAN certificate is refused on both ports.
- **End to end:** docker-compose with the gateway, headless. Onboard a child, submit, approve through every stage, and see it go live with its activation link. Suspend mid-workflow and see it freeze. Lift the hold and it resumes. Kill `kyc-service` and see payments still work.

## 17. Retention

Under FIA s.27(1) and s.24(2)(d), CDD and EDD records are kept **at least 5 years** from the end of the business relationship. Longer applies if a competent authority asks before the period ends.
- Each application gets a `retain_until` date. While the org is active it stays null; it is set when the relationship ends (org deregistered or offboarded): end date + 5 years.
- **Rejected, blocked and withdrawn applications** are also kept 5 years from the final decision. This is conservative: they document why a relationship was refused.
- A later purge job (out of scope here) may only delete or anonymise rows past `retain_until`, and never while a legal hold flag is set.

## 18. Resolved and open items

Resolved (from `response1.md`):
- Registration number: normalised and checked against `^([A-Z]{2})?\d{3,10}$`.
- TIN: `^\d{8}$`.
- Branch code: `^\d{5,6}$`. Namibia doesn't use IBAN.
- Beneficial owner threshold: 25% for both PSP and EMI.
- Volume bands are internal risk tiers.
- PIP approval goes to senior management.
- MINDLABS_ADMIN has no review role.
- Retention is 5 years.
- KYC runs as a separate `kyc-service` container, talking to core over gRPC with mTLS (§16).

Still open:
1. Current branch-code lists from each bank. The 2016 list is used for the sandbox only.
2. Who in MiPay holds COMPLIANCE_OFFICER, MLRO and SENIOR_APPROVER in production.
3. Whether a parent org's ADMIN should ever be an approval stage (supported by `PARENT_ORG` relations, not used in the defaults).
