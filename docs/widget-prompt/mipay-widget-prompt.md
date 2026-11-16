# MiPay widget builder

**How to use this:** paste this whole document into any AI chat (ChatGPT, Claude, Gemini, …), then write your UI requirement at the end
under "My requirement". The answer is a complete widget. Save the answer as a file and run, in `frontend/`:

    npm run widgets:apply -- answer.md

That writes the files, runs every check and test, takes screenshots at every screen size and validates the widget against the
backend's permissions - ready to register. If anything fails it prints a repair message: paste it back into the same chat.

---

## Instructions for the AI

You are building ONE MiPay UI widget: an Angular 20 standalone component plus its manifest, contracts, fixtures and tests, that
plugs into MiPay's widget registry. MiPay is a FAPI 2.0 payment platform: the browser never holds tokens; a gateway (BFF) does
FAPI and the widget reaches the API ONLY through `this.data` (the widget port). A local tool applies your answer and checks it
strictly - follow the rules and the output format exactly.

### Step 1 - the operations sheet
List every operation the UI performs (each read and each write: load, save draft, submit, approve, …). For each:
endpoint (METHOD /api/path), permission cell, the shape it RECEIVES (response), the shape it SENDS (request body / params),
and what the UI does after success, a refusal (422 / 409) and 403, and its KIND - exactly one of View (every read), Create
(makes a new record, including submitting a new one), Modify (changes an existing record: edit, save draft, submit) or
Approve (every other action: execute, schedule, approve, reject, withdraw, cancel, …). Nothing else. The manifest's
`operations` is the set of kinds (one or more of 'view' | 'create' | 'modify' | 'approve') and `commandOperations` maps
every command to 'create' | 'modify' | 'approve'.
- Use the "Endpoints for this widget" section when present: it is read from the real backend.
- If an endpoint, a permission or a shape you need is missing or untyped, ASK the user once, showing your drafted sheet, and wait.
  Never invent an endpoint or a permission. (The user can generate a prompt with the endpoints resolved:
  `npm run widgets:prompt -- GET /api/... POST /api/...`.)
- **Reuse first.** Look for each operation in the "API catalogue" (every endpoint the backend serves, with its permission and
  shapes). Mark it EXISTING and use that endpoint, its cell and its exact shapes when one fits.
- **New when nothing fits.** Mark it NEW and propose: an endpoint (`/api/<area>/…`), a permission cell - reuse an existing
  row and verb when the meaning matches, else a new row `<area>.<resource>` with one of the ten verbs below - and the request
  and response shapes this UI needs. You then also deliver a backend controller stub (see "Backend stub").
- Complex UIs (draft → submit, approve / reject…) are several operations: each has its own command, request contract and
  response contract; chained writes (save then submit) run in order and a later failure says the earlier step succeeded.

### Step 2 - the files
When the sheet is complete, print it briefly, then output the files in the Output format, then the self-check list. Nothing else.

### Hard rules (the checker enforces every one)
1. Five files in `frontend/projects/shared/widgets/<area>/<name>/`: `<name>.widget.ts`, `<name>.contract.ts`,
   `<name>.component.ts`, `<name>.fixtures.ts`, `<name>.spec.ts` - plus one DATA-SOURCES block. `<area>`/`<name>` are
   lower case (name may contain dashes); the widget id is `<area>.<name>`.
2. Reuse, never invent: only the UI elements listed below (import them by the paths shown, relative from the widget folder:
   `frontend/` is five levels up, e.g. `../../../../../src/app/design-system/organisms/data-table`). Never define a component,
   directive or pipe other than the widget itself. Never use raw `<table>`, `<button>`, `<input>`, `<select>`, `<textarea>`.
3. One network path: never HttpClient, fetch, XMLHttpRequest, localStorage/sessionStorage/indexedDB. Only `this.data.query(name, params)`
   and `this.data.command(name, { ...pathParams, ...queryParams, body })`.
4. Every source and command has a response contract; every command that sends a body (POST/PUT/PATCH) has a request contract.
   Contracts use the `s.*` builders; export the type with `Infer`. Build request bodies typed by their request type.
5. Every gated action: `*mpPerm="'row:verb'"` (or an organism's `perm` input) AND `data-action="<action id>"` on the element.
6. Mobile first: `size` for xs, sm, md, lg with `span.*` (xs max 4 columns, sm 8, md/lg 12). Style with `@container`, never `@media`.
   For structural phone changes (fewer table columns) use `this.size().tier === 'xs'`.
7. Fixtures: scenarios `default`, `empty`, `error`, plus `longText` when the widget shows user text, `forbidden` (fail(403)) when it
   hides on 403, and one per refusal you handle. Every value must match the response contract exactly (types, nulls, every field present).
   `default` answers every source and command. Realistic Namibian data (organisations, people, NAD amounts, ISO dates).
8. Tests (Vitest globals `describe`/`it`/`expect`) with `renderWidget`: at least 4: the right sources and params, the data rendered,
   actions hidden without their cell and shown after `await w.grant(cell)`, each write's params/body (`w.lastCommand()`), no
   `[data-diagnostics]` with the fixtures, and a drifted answer diagnosed in `[data-diagnostics]`.
9. TypeScript strict, Angular strict templates: no `any` leaks in templates, signals for state, `ChangeDetectionStrategy.OnPush`.
10. No `TODO` left anywhere.

### Verbs (the only ten) and tenant sources

| verb | meaning |  | tenantSource | the org the call acts on |
|---|---|---|---|---|
| read | view / list |  | PATH | named by a path variable: `tenant = "#id"` |
| create | create / add |  | SELF | the caller's own org: `tenant = "null"` |
| modify | change / update |  | LOOKUP | derived from an object (task, request): `lookup = "<resolver>"` |
| delete | delete / retire |  | NONE | platform-wide (admin / ops): `tenant = "null"` |
| submit | submit (maker) |  | | |
| authorize | approve (checker) - never in the same role as submit on the same row |  | | |
| execute | run / act with effect |  | | |
| export | download / export |  | | |
| administer | administer / control |  | | |
| simulate | simulate (sandbox only) |  | | |

### Backend stub (only for NEW operations)

One controller per widget, in package `com.mipay.sme.web`, file `backend/src/main/java/com/mipay/sme/web/<Area><Thing>Controller.java`
(a NEW file - never edit an existing controller). Request and response types are records nested in the controller, named
exactly as in your contracts. Every handler has `@Perm` (never `@PublicEndpoint`) and answers 501 until implemented:

    package com.mipay.sme.web;

    import com.mipay.sme.security.rbac.Perm;
    import com.mipay.sme.security.rbac.TenantSource;
    import com.mipay.sme.security.rbac.Verb;
    import jakarta.validation.Valid;
    import jakarta.validation.constraints.NotBlank;
    import java.time.Instant;
    import java.util.List;
    import org.springframework.http.HttpStatus;
    import org.springframework.web.bind.annotation.*;
    import org.springframework.web.server.ResponseStatusException;

    /** <What these endpoints are for>. STUB generated for widget <area.name>: implement the service calls. */
    @RestController
    public class KycNotesController {

        public record KycNoteRow(String id, String orgId, String text, String author, Instant createdAt) { }
        public record KycNoteRequest(@NotBlank String text) { }

        @Perm(resource = "kyc.note", verb = Verb.READ, tenantSource = TenantSource.PATH, tenant = "#orgId",
              description = "List the review notes of an organization's KYC application")
        @GetMapping("/api/kyc/orgs/{orgId}/notes")
        public List<KycNoteRow> list(@PathVariable String orgId) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "kyc notes: not implemented yet");
        }

        @Perm(resource = "kyc.note", verb = Verb.CREATE, tenantSource = TenantSource.PATH, tenant = "#orgId",
              description = "Add a review note to an organization's KYC application")
        @PostMapping("/api/kyc/orgs/{orgId}/notes")
        public KycNoteRow add(@PathVariable String orgId, @Valid @RequestBody KycNoteRequest body) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "kyc notes: not implemented yet");
        }
    }

Rules for stubs: `@NotBlank`/`@NotNull` on required request fields (the contract generator reads them); primitives
(`int`, `long`, `boolean`) for never-null numbers and flags, boxed or `String`/`Instant` otherwise (those may be null);
the widget's contracts must describe exactly these records (the checker compares them). Add a BACKEND-NOTES block saying
what each stub must do (service, rules, which existing data), so a backend developer can implement it.

### Output format (machine-applied - exactly this, paths from the repository root)

    === FILE: frontend/projects/shared/widgets/<area>/<name>/<name>.widget.ts ===
    …the whole file…
    === END FILE ===
    (the same for .contract.ts, .component.ts, .fixtures.ts, .spec.ts)
    === DATA-SOURCES ===
      '<area.thing.verb>': { '*': { method: 'GET', path: '/api/…', query: ['…'] } },
    === END DATA-SOURCES ===
    (only when an operation is NEW:)
    === BACKEND: backend/src/main/java/com/mipay/sme/web/<Area><Thing>Controller.java ===
    …the whole stub controller…
    === END BACKEND ===
    === BACKEND-NOTES ===
    …what each stub endpoint must do…
    === END BACKEND-NOTES ===

When answering a REPAIR REPORT, output only the changed files (and the DATA-SOURCES block if it changed), same format.

### Self-check (print it, ticked, after the files)
- [ ] every operation is marked EXISTING (endpoint from the catalogue) or NEW (stub provided, same shapes as the contracts)
- [ ] every operation in the sheet has a source/command, a response contract, a request contract (writes) and a fixture answer
- [ ] only listed UI elements; no raw controls; no own components
- [ ] every action: exact cell + data-action
- [ ] fixtures match the contracts field by field
- [ ] size for all four tiers; no @media
- [ ] tests as in rule 8

---

## Endpoints for this widget (read from the MiPay backend)

_None resolved for this prompt._ Ask the user for each endpoint (METHOD /api/path), its permission cell, an example response and an example request body - or ask them to run `npm run widgets:prompt -- GET /api/... POST /api/...` and paste the new prompt.

## API catalogue (every endpoint the backend serves - reuse these first)

252 endpoints, 55 permission rows. A row's cells are `<row>:<verb>`. Types: `string|null` may be null; `integer`, `number`, `boolean`, `date-time` (ISO string), `date` (yyyy-mm-dd), `X[]` list, `map<string, X>`, `any` (free-form JSON: ask the user for its shape).

#### admin.access

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/admin/access` | `read` | View access users | platform | - | - → AccessUserRow[] |
| `GET /api/admin/access/{userId}` | `read` | View a user's credential links and sessions | platform | {userId} | - → AccessDetail |
| `POST /api/admin/access/{userId}/revoke` | `administer` | Revoke user access | platform | {userId} | - → AccessRevokeResult |
| `POST /api/admin/access/links/{linkId}/disable` | `administer` | Disable one credential link | platform | {linkId} | - → AccessDisableResult |
| `GET /api/admin/migration/identity-backfill` | `read` | Preview the identity backfill | platform | - | - → Report |
| `POST /api/admin/migration/identity-backfill` | `administer` | Run the identity backfill | platform | - | - → Report |
| `GET /api/admin/migration/org-binding` | `read` | Report the org-binding backfill | platform | - | - → Report |
| `POST /api/admin/migration/org-binding` | `administer` | Run the org-binding backfill | platform | - | - → Report |
| `GET /api/admin/migration/rbac-backfill` | `read` | Preview the RBAC backfill | platform | - | - → Report |
| `POST /api/admin/migration/rbac-backfill` | `administer` | Run the RBAC backfill | platform | - | - → Report |
| `GET /api/ui/widgets` | `read` | View the UI widget registry | platform | portal | - → map<string, any>[] |

#### admin.aml_alert

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/admin/compliance/aml-alerts` | `read` | View AML alerts | platform | - | - → AmlAlertRow[] |

#### admin.api_client

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/admin/api-clients` | `read` | View API clients | platform | - | - → ApiClientView[] |
| `POST /api/admin/api-clients` | `create` | Create an API client | platform | - | ApiClientCreate → ApiClientView |
| `POST /api/admin/api-clients/{id}/revoke` | `administer` | Revoke an API client | platform | {id} | - → ApiClientView |

#### admin.bootstrap

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `POST /api/admin/bootstrap/first-admin` | `administer` | Temporary installer creates the first administrator maker and reviewer checker | platform | - | TptsBootstrapRequest → FirstAdminResult |
| `POST /api/admin/bootstrap/tpts` | `administer` | One-time TPTS operating-root bootstrap | platform | - | TptsBootstrapRequest → TptsBootstrapResult |

#### admin.config

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/admin/limits` | `read` | View limits | platform | - | - → LimitView[] |
| `POST /api/admin/limits` | `modify` | Create or update a limit | platform | - | LimitUpsert → LimitView |
| `GET /api/admin/rules` | `read` | View rules | platform | - | - → RuleRow[] |
| `POST /api/admin/rules/{code}` | `modify` | Toggle a rule | platform | {code} | map<string, any> → RuleRow |
| `GET /api/admin/settings` | `read` | View settings | platform | - | - → SettingRow[] |
| `POST /api/admin/settings` | `modify` | Create or update a setting | platform | - | SettingUpsert → SettingRow |
| `GET /api/admin/tariffs` | `read` | View tariffs | platform | - | - → TariffRow[] |
| `POST /api/admin/tariffs` | `modify` | Create or update a tariff | platform | - | TariffUpsert → TariffRow |
| `GET /api/admin/templates` | `read` | View templates | platform | - | - → TemplateRow[] |
| `POST /api/admin/templates` | `modify` | Create or update a template | platform | - | TemplateUpsert → TemplateRow |

#### admin.connector

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/admin/connectors` | `read` | View connectors | platform | - | - → ConnectorView[] |
| `POST /api/admin/connectors` | `modify` | Create or update a connector | platform | - | ConnectorUpsert → ConnectorView |
| `POST /api/admin/connectors/{id}/rotate` | `administer` | Rotate connector credentials | platform | {id} | - → ConnectorTestResult |
| `POST /api/admin/connectors/{id}/test` | `execute` | Test a connector | platform | {id} | - → ConnectorTestResult |

#### admin.dsar

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/admin/compliance/dsars` | `read` | View DSARs | platform | - | - → DsarRow[] |
| `POST /api/admin/compliance/dsars` | `create` | Create a DSAR | platform | - | DsarCreateRequest → DsarRow |
| `POST /api/admin/compliance/dsars/{id}/export` | `export` | Export a DSAR | platform | {id} | - → DsarExport |

#### admin.org_directory

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/admin/organizations` | `read` | View the platform-wide organization directory | platform | type? status? page? size? | - → any /* Page */ |

#### admin.platform_ops

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/admin/backups` | `read` | View backups | platform | - | - → BackupRow[] |
| `POST /api/admin/backups` | `execute` | Run a backup | platform | - | - → BackupRow |
| `POST /api/admin/backups/{id}/restore-test` | `execute` | Run a restore test | platform | {id} | - → RestoreTestRow |
| `GET /api/admin/deployments` | `read` | View deployments | platform | - | - → DeploymentRow[] |
| `POST /api/admin/deployments` | `execute` | Deploy a version | platform | - | DeployRequest → DeploymentRow |
| `POST /api/admin/deployments/rollback` | `administer` | Rollback a deployment | platform | - | map<string, string> → DeploymentRow |
| `GET /api/admin/health` | `read` | View health status | platform | - | - → HealthView |
| `GET /api/admin/restore-tests` | `read` | View restore tests | platform | - | - → RestoreTestRow[] |
| `GET /api/admin/sim/outbox` | `read` | View simulated activation outbox | platform | - | - → OutboxRow[] |

#### admin.sar

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/admin/compliance/sars` | `read` | View SARs | platform | - | - → SarRow[] |
| `POST /api/admin/compliance/sars` | `create` | Create a SAR | platform | - | map<string, string> → SarRow |
| `POST /api/admin/compliance/sars/{id}/submit` | `submit` | Submit a SAR | platform | {id} | - → SarRow |

#### admin.simulation

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/admin/scenarios` | `read` | View simulation scenarios | platform | - | - → ScenarioRow[] |
| `POST /api/admin/scenes/run` | `simulate` | Run a simulation scene | platform | - | map<string, string> → SceneRunResult |
| `POST /api/admin/sim/advance` | `simulate` | Simulate an advance | platform | - | SimAdvanceRequest → AdvanceDetailView |
| `POST /api/admin/sim/clear-breaks` | `simulate` | Clear reconciliation breaks | platform | - | - → SimResult |
| `GET /api/admin/sim/controls` | `read` | View simulation controls | platform | - | - → SimControlsView |
| `POST /api/admin/sim/controls` | `simulate` | Set simulation controls | platform | - | SimControlsRequest → SimControlsView |
| `POST /api/admin/sim/inject-break` | `simulate` | Inject a reconciliation break | platform | - | InjectBreakRequest → SimResult |
| `POST /api/admin/sim/invoice-payment` | `simulate` | Simulate an invoice payment | platform | - | SimInvoiceRequest → any /* InvoicePayResult */ |
| `POST /api/admin/sim/payment` | `simulate` | Simulate a payment | platform | - | SimPaymentRequest → any /* PaymentOpResult */ |
| `POST /api/admin/sim/synthetic-history` | `simulate` | Simulate synthetic history | platform | - | SyntheticHistoryRequest → SimResult |

#### kyc.application

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/kyc/orgs` | `read` | List KYC applications (v2, caller's org) | own org | status? suspended? | - → KycSummaryRow[] |
| `GET /api/kyc/orgs/{id}` | `read` | View a specific org's KYC application (PATH tenant) | org in path | {id} | - → KycApplicationView |
| `PUT /api/kyc/orgs/{id}` | `modify` | Save KYC draft for an org (PATH tenant) | org in path | {id} expectedVersion? | map<string, any> → KycApplicationView |
| `POST /api/kyc/orgs/{id}/submit` | `submit` | Submit KYC application for review (PATH tenant) | org in path | {id} | map<string, any>? → KycApplicationView |
| `POST /api/kyc/orgs/{id}/upgrade` | `modify` | Upgrade KYC application schema version (PATH tenant) | org in path | {id} | - → KycApplicationView |
| `POST /api/kyc/orgs/{id}/withdraw` | `delete` | Withdraw a KYC application (PATH tenant) | org in path | {id} | - → KycApplicationView |
| `GET /api/kyc/orgs/me` | `read` | View caller's own org KYC application (v2) | own org | - | - → KycApplicationView |
| `GET /api/orgs/{id}/kyc` | `read` | View a specific org's KYC application (PATH tenant) | org in path | {id} | - → KycApplicationView |
| `PUT /api/orgs/{id}/kyc` | `modify` | Save KYC draft for an org (PATH tenant) | org in path | {id} expectedVersion? | map<string, any> → KycApplicationView |
| `POST /api/orgs/{id}/kyc/submit` | `submit` | Submit KYC application for review (PATH tenant) | org in path | {id} | map<string, any>? → KycApplicationView |
| `GET /api/orgs/kyc` | `read` | List KYC applications for caller's org | own org | status? suspended? | - → KycSummaryRow[] |
| `GET /api/orgs/me/kyc` | `read` | View caller's own org KYC application | own org | - | - → KycApplicationView |

#### kyc.form

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/kyc/lists/{code}` | `read` | Read KYC reference lists (MCC, branches, etc.) | platform | {code} parent? q? page? size? | - → map<string, any> |
| `GET /api/kyc/schema` | `read` | Get KYC schema for caller's org | own org | entityType? | - → map<string, any> |
| `GET /api/kyc/schema/{orgId}` | `read` | Get KYC schema for a specific org (PATH tenant) | org in path | {orgId} entityType? | - → map<string, any> |
| `GET /api/kyc/schema/types/{orgType}` | `read` | Get the KYC schema an org type fills in | platform | {orgType} entityType? | - → map<string, any> |

#### kyc.task

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/kyc/tasks` | `read` | List open KYC review tasks for caller | own org | - | - → KycTaskRow[] |
| `POST /api/kyc/tasks/{taskId}/decision` | `authorize` | Decide a KYC review task (platform staff) | platform | {taskId} | KycDecisionRequestV2 → KycApplicationView |

#### lender.advance

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/lender/advances` | `read` | List advances for this lender | own org | - | - → LenderAdvanceRow[] |
| `POST /api/lender/advances/{id}/decline` | `authorize` | Decline an offered advance | own org | {id} | - → Message |
| `POST /api/lender/advances/{id}/fund` | `authorize` | Fund (disburse) an accepted advance | own org | {id} | - → Message |
| `GET /api/lender/consents` | `read` | List active consents for this lender | own org | - | - → ConsentRow[] |

#### lender.assessment

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `POST /api/lender/assess` | `execute` | Run a credit assessment for a merchant | own org | - | AssessRequest → AssessmentView |
| `GET /api/lender/assessments` | `read` | List credit assessments for this lender | own org | - | - → any /* CreditAssessment */[] |
| `GET /api/lender/records/{merchantId}` | `read` | View consent-gated merchant records for this lender | own org | {merchantId} | - → MerchantRecordsView |

#### lender.collection

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/lender/collections` | `read` | List collections for this lender | own org | - | - → CollectionRow[] |
| `POST /api/lender/sweep` | `execute` | Run the daily collections sweep | own org | - | SweepRequest? → StepResult[] |

#### lender.portfolio

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/lender/portfolio` | `read` | View lender's portfolio with merchant names, outstanding and arrears | own org | - | - → PortfolioView |
| `POST /api/lender/portfolio/snapshot` | `execute` | Take a portfolio snapshot | own org | - | - → SnapshotRow |

#### merchant.account

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/merchant/accept-info` | `read` | View payment accept info | own org | - | - → PayInfo |
| `GET /api/merchant/balance` | `read` | View merchant balance | own org | - | - → BalanceView |
| `GET /api/merchant/balance/entries` | `read` | View merchant ledger entries | own org | page? size? | - → LedgerRow[] |
| `GET /api/merchant/billing-preview` | `read` | View billing preview | own org | amount? | - → FeePreview |
| `GET /api/merchant/dashboard` | `read` | View merchant dashboard | own org | - | - → DashboardView |
| `GET /api/merchant/devices` | `read` | View merchant devices | own org | - | - → DeviceRow[] |
| `GET /api/merchant/settings` | `read` | View merchant settings | own org | - | - → SettingsView |
| `GET /api/merchant/speaker-log` | `read` | View speaker log | own org | - | - → SpeakerLogRow[] |

#### merchant.advance

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/merchant/advance` | `read` | View current advance | own org | - | - → AdvanceDetailView |
| `POST /api/merchant/advance/{id}/accept` | `authorize` | Accept an advance offer | own org | {id} | - → Message |
| `POST /api/merchant/advance/{id}/simulate-fund` | `simulate` | Simulate lender disbursement | own org | {id} | - → Message |
| `POST /api/merchant/advance/consent` | `create` | Grant consent to a lender | own org | - | ConsentRequest → ConsentRow |
| `GET /api/merchant/advance/consents` | `read` | List advance consents | own org | - | - → ConsentRow[] |
| `POST /api/merchant/advance/consents/{id}/withdraw` | `delete` | Withdraw an advance consent | own org | {id} | - → Message |
| `GET /api/merchant/advance/eligibility` | `read` | View advance eligibility | own org | - | - → EligibilityView |
| `POST /api/merchant/advance/request` | `submit` | Request an advance assessment | own org | - | RequestAdvanceRequest → AssessmentView |
| `GET /api/merchant/lenders` | `read` | List available lenders | own org | - | - → LenderRow[] |

#### merchant.inventory

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/merchant/catalogue` | `read` | View supplier catalogue for merchants | own org | - | - → CatalogueRow[] |
| `GET /api/merchant/inventory` | `read` | View merchant inventory items | own org | - | - → InventoryRow[] |
| `POST /api/merchant/inventory` | `create` | Add an inventory item | own org | - | map<string, any> → InventoryRow |
| `POST /api/merchant/inventory/{itemId}/reorder` | `execute` | Place a one-tap reorder | own org | {itemId} | - → Message |
| `POST /api/merchant/inventory/adjust` | `modify` | Adjust inventory stock | own org | - | StockAdjustRequest → InventoryRow |
| `GET /api/merchant/inventory/alerts` | `read` | View inventory stock alerts | own org | - | - → StockAlertRow[] |
| `GET /api/merchant/inventory/rules` | `read` | View inventory reorder rules | own org | - | - → ReorderRow[] |

#### merchant.invoice

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/merchant/invoices` | `read` | List merchant invoices | own org | - | - → InvoiceListRow[] |
| `GET /api/merchant/invoices/{id}` | `read` | View an invoice detail | own org | {id} | - → InvoiceDetailRow |
| `POST /api/merchant/invoices/{id}/pay` | `execute` | Pay an invoice | own org | {id} | PayInvoiceRequest? → any /* InvoicePayResult */ |
| `POST /api/merchant/invoices/{id}/retry-timeout` | `execute` | Retry a timed-out invoice payment | own org | {id} | PayInvoiceRequest? → any /* InvoicePayResult */ |

#### merchant.khata

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/merchant/khata` | `read` | View merchant khata accounts | own org | - | - → KhataRow[] |
| `POST /api/merchant/khata` | `create` | Create a khata account | own org | - | KhataAccountRequest → KhataRow |
| `GET /api/merchant/khata/{id}/entries` | `read` | View khata entries | own org | {id} | - → KhataEntryRow[] |
| `POST /api/merchant/khata/{id}/entries` | `create` | Add a khata entry | own org | {id} | KhataEntryRequest → KhataEntryRow |
| `POST /api/merchant/khata/{id}/remind` | `execute` | Remind a khata customer | own org | {id} | - → RemindResult |

#### merchant.order

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/merchant/orders` | `read` | List merchant orders | own org | - | - → OrderRow[] |
| `POST /api/merchant/orders` | `create` | Place an order with a supplier | own org | - | CreateOrderRequest → OrderDetailRow |
| `GET /api/merchant/orders/{id}` | `read` | View an order detail | own org | {id} | - → OrderDetailRow |

#### merchant.payment

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/merchant/payments` | `read` | List merchant payments | own org | page? size? | - → PaymentRow[] |
| `POST /api/merchant/payments` | `execute` | Accept a customer payment | own org | - | AcceptPaymentRequest → PaymentPending |
| `GET /api/merchant/payments/{id}` | `read` | View a payment detail | own org | {id} | - → PaymentDetailRow |
| `POST /api/merchant/payments/{id}/refund` | `execute` | Refund a payment | own org | {id} | RefundRequest → RefundRow |
| `POST /api/merchant/payments/{id}/simulate` | `simulate` | Simulate a payment outcome | own org | {id} | SimTrigger → any /* PaymentOpResult */ |

#### merchant.statement

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/merchant/statements` | `read` | View merchant statements | own org | - | - → StatementRow[] |
| `POST /api/merchant/statements` | `execute` | Generate a merchant statement | own org | - | GenerateStatementRequest → StatementRow |
| `GET /api/merchant/statements/{id}/download` | `export` | Download a statement | own org | {id} | - → string |

#### merchant.tab

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/merchant/tabs` | `read` | View merchant tabs | own org | - | - → TabRow[] |
| `POST /api/merchant/tabs` | `create` | Create a tab | own org | - | TabCreateRequest → TabRow |
| `GET /api/merchant/tabs/{id}/entries` | `read` | View tab entries | own org | {id} | - → TabEntryRow[] |
| `POST /api/merchant/tabs/{id}/entries` | `create` | Add a tab entry | own org | {id} | TabAddEntryRequest → TabEntryRow |
| `POST /api/merchant/tabs/{id}/remind` | `execute` | Remind a tab customer | own org | {id} | - → RemindResult |
| `POST /api/merchant/tabs/{id}/settle` | `execute` | Settle a tab | own org | {id} | - → TabRow |

#### merchant.withdrawal

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/merchant/withdrawals` | `read` | List merchant withdrawals | own org | - | - → WithdrawalRow[] |
| `POST /api/merchant/withdrawals` | `execute` | Execute a withdrawal | own org | - | WithdrawalRequest → WithdrawalRow |

#### ops.audit

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/ops/audit` | `read` | View audit log | platform | simulated? page? size? | - → AuditPage |

#### ops.bank_account_approval

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/ops/approvals/bank-accounts` | `read` | View bank account approval queue | platform | - | - → BankApprovalRow[] |
| `POST /api/ops/approvals/bank-accounts/{id}` | `authorize` | Approve or reject a bank account | platform | {id} | BankApprovalRequest → ApprovalResult |

#### ops.complaint

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/ops/complaints` | `read` | View complaints | platform | - | - → ComplaintRow[] |
| `POST /api/ops/complaints` | `create` | Create a complaint | platform | - | CreateComplaintRequest → ComplaintRow |
| `POST /api/ops/complaints/{id}/assign` | `modify` | Assign a complaint | platform | {id} assignee? | - → Message |
| `POST /api/ops/complaints/{id}/resolve` | `modify` | Resolve a complaint | platform | {id} resolution? | - → Message |

#### ops.dispute

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/ops/disputes` | `read` | View disputes | platform | - | - → DisputeRow[] |
| `POST /api/ops/disputes` | `create` | Create a dispute | platform | - | CreateDisputeRequest → Message |
| `POST /api/ops/disputes/{id}/resolve` | `modify` | Resolve a dispute | platform | {id} won | - → Message |

#### ops.incident

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/ops/incidents` | `read` | View incidents | platform | - | - → IncidentRow[] |
| `POST /api/ops/incidents` | `create` | Create an incident | platform | - | CreateIncidentRequest → IncidentRow |
| `POST /api/ops/incidents/{id}/ack` | `modify` | Acknowledge an incident | platform | {id} | - → IncidentRow |
| `POST /api/ops/incidents/{id}/resolve` | `modify` | Resolve an incident | platform | {id} | - → IncidentRow |

#### ops.kyc_check

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/ops/kyc/{entityType}/{entityId}` | `read` | View KYC checks for an entity | platform | {entityType} {entityId} | - → KycCheckRow[] |
| `POST /api/ops/kyc/{entityType}/{entityId}` | `create` | Record a KYC check | platform | {entityType} {entityId} | KycCheckRequest → KycCheckRow |

#### ops.merchant_approval

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/ops/approvals/merchants` | `read` | View merchant approval queue | platform | - | - → MerchantApprovalRow[] |
| `POST /api/ops/approvals/merchants/{id}` | `authorize` | Approve or reject a merchant | platform | {id} | ApprovalRequest → ApprovalResult |
| `GET /api/ops/approvals/merchants/all` | `read` | View all merchant approval rows | platform | - | - → MerchantApprovalRow[] |

#### ops.monitoring

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `POST /api/ops/monitoring/touch` | `execute` | Record monitoring touchpoint | platform | - | - → Message |

#### ops.onboarding_request

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/ops/onboarding-requests` | `read` | View onboarding request queue | platform | status? | - → OnboardingRequestRow[] |
| `POST /api/ops/onboarding-requests/{id}/decision` | `authorize` | Decide an onboarding request | platform | {id} | OrgDecideRequest → OrgDecisionResult |

#### ops.recon

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `POST /api/ops/recon/breaks/{id}/resolve` | `modify` | Resolve a reconciliation break | platform | {id} | - → BreakRow |
| `POST /api/ops/recon/run` | `execute` | Run reconciliation | platform | - | - → any /* RunResult */ |
| `GET /api/ops/recon/runs` | `read` | View reconciliation runs | platform | - | - → ReconRunRow[] |
| `GET /api/ops/recon/runs/{id}/breaks` | `read` | View reconciliation breaks | platform | {id} | - → BreakRow[] |

#### ops.report

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/ops/payments` | `read` | View payments | platform | - | - → PaymentOpsRow[] |
| `GET /api/ops/reports/fees` | `read` | View fee reports | platform | date? | - → FeeReportRow[] |
| `GET /api/ops/reports/settlement` | `read` | View settlement report | platform | date? cycle? | - → SettlementRow |
| `POST /api/ops/statements/generate` | `execute` | Generate a statement | platform | - | GenerateStatementRequest → StatementRow |

#### ops.supplier_approval

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/ops/approvals/suppliers` | `read` | View supplier approval queue | platform | - | - → SupplierApprovalRow[] |
| `POST /api/ops/approvals/suppliers/{id}` | `authorize` | Approve or reject a supplier | platform | {id} | ApprovalRequest → ApprovalResult |
| `GET /api/ops/approvals/suppliers/all` | `read` | View all supplier approval rows | platform | - | - → SupplierApprovalRow[] |

#### ops.suspension

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `POST /api/ops/suspend` | `administer` | Suspend an org | platform | - | SuspendRequest → SuspensionRow |
| `GET /api/ops/suspensions` | `read` | View suspensions | platform | - | - → SuspensionRow[] |
| `POST /api/ops/suspensions/{id}/lift` | `administer` | Lift a suspension | platform | {id} | - → SuspensionRow[] |

#### org.onboarding

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `POST /api/orgs/{id}/admins` | `authorize` | Request admin recovery for an org (PATH tenant) | org in path | {id} | AdminRecoveryRequest → OrgDecisionResult |
| `POST /api/orgs/me/children/with-kyc` | `create` | Onboard a child organization with its complete KYC (all or nothing) | own org | - | OnboardWithKycRequest → map<string, any> |
| `GET /api/orgs/onboarding` | `read` | View onboarding request queue for caller's org | own org | status? | - → OnboardingRequestRow[] |
| `POST /api/orgs/onboarding/{id}/decide` | `authorize` | Decide an onboarding request (PATH tenant) | org in path | {id} | OrgDecideRequest → OrgDecisionResult |

#### org.rights

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/orgs/{id}/rights` | `read` | View an org's rights (PATH tenant) | org in path | {id} | - → OrgRightsView |
| `PUT /api/orgs/{id}/rights` | `administer` | Narrow an org's rights (PATH tenant) | org in path | {id} | RightsPutRequest → OrgRightsView |
| `GET /api/orgs/{id}/role-ceiling` | `read` | View an org's role ceiling (PATH tenant) | org in path | {id} | - → map<string, any> |
| `PUT /api/orgs/{id}/role-ceiling` | `administer` | Set the roles an org below may use and hand down (PATH tenant) | org in path | {id} | RoleCeilingRequest → map<string, any> |
| `GET /api/orgs/me/grantable` | `read` | View grantable child types for caller's org | own org | childType? | - → GrantableView |

#### org.suspension

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `POST /api/orgs/{id}/suspend` | `administer` | Suspend an org (PATH tenant) | org in path | {id} | HoldRequest? → SuspensionRow |
| `POST /api/orgs/suspensions/{sid}/lift` | `administer` | Lift a suspension (PATH tenant) | org in path | {sid} | - → SuspensionRow[] |

#### org.tree

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/orgs/{id}` | `read` | View an org (PATH tenant) | org in path | {id} | - → OrgView |
| `GET /api/orgs/{id}/descendants` | `read` | View an org's descendants (PATH tenant) | org in path | {id} type? status? page? size? | - → any /* Page */ |
| `GET /api/orgs/{id}/path` | `read` | View an org's ancestor path (PATH tenant) | org in path | {id} | - → OrgPathRow[] |
| `GET /api/orgs/me` | `read` | View caller's own org | own org | - | - → OrgView |
| `GET /api/orgs/me/descendants` | `read` | View caller's org descendants | own org | type? status? page? size? | - → any /* Page */ |
| `GET /api/orgs/me/path` | `read` | View caller's org ancestor path | own org | - | - → OrgPathRow[] |
| `GET /api/orgs/me/tree` | `read` | View caller's org subtree | own org | maxDepth? status? | - → OrgNodeRow |

#### org.user

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/orgs/{id}/auth/users` | `read` | View an org's users and their IdP credential state (PATH tenant) | org in path | {id} | - → map<string, any>[] |
| `POST /api/orgs/{id}/auth/users/{uid}/resend-link` | `administer` | Re-send a user's one-time set-up link (PATH tenant) | org in path | {id} {uid} | - → map<string, any> |
| `POST /api/orgs/{id}/auth/users/{uid}/reset-credential` | `administer` | Force a full credential reset for a user (invalidates current credentials) (PATH tenant) | org in path | {id} {uid} | - → map<string, any> |
| `POST /api/orgs/{id}/auth/users/{uid}/revoke-sessions` | `administer` | Revoke all IdP sessions for a user (PATH tenant) | org in path | {id} {uid} | - → map<string, any> |
| `GET /api/orgs/{id}/members` | `read` | The users of an org, for role assignment (PATH tenant) | org in path | {id} | - → map<string, any>[] |
| `GET /api/orgs/me/users` | `read` | View caller's org users | own org | - | - → OrgUserRow[] |
| `POST /api/orgs/me/users` | `create` | Add staff to caller's org | own org | - | StaffRequest → OrgStaffView |
| `POST /api/orgs/users/{id}/deactivate` | `delete` | Deactivate a user (PATH tenant) | org in path | {id} | - → Message |
| `POST /api/orgs/users/{id}/resend-activation` | `administer` | Issue a set-up link for a user in the manager's org subtree | own org | {id} | - → any |

#### rbac.assignment

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/admin/rbac/assignments` | `read` | Role assignments across all orgs | platform | status? | - → RbacAssignmentView[] |
| `POST /api/admin/rbac/assignments` | `create` | Request an RBAC role assignment | platform | - | RbacAssignRequest → RbacAssignmentView |
| `DELETE /api/admin/rbac/assignments/{id}` | `administer` | Revoke an RBAC role assignment | platform | {id} reason? | - → RbacAssignmentView |
| `GET /api/admin/rbac/reconciliation/{orgId}` | `read` | Compare one org's core admin/staff roles with active RBAC assignments | platform | {orgId} | - → Report |
| `GET /api/orgs/{id}/role-approvals` | `read` | Pending role requests in the org the caller may decide (PATH tenant) | org in path | {id} | - → map<string, any>[] |
| `POST /api/orgs/{id}/role-approvals/{assignmentId}` | `authorize` | Approve or reject a role request in the org (PATH tenant) | org in path | {id} {assignmentId} | DecideBody → map<string, any> |
| `GET /api/orgs/{id}/role-assignments` | `read` | Role assignments in an org, with the users' names (PATH tenant) | org in path | {id} | - → map<string, any>[] |
| `POST /api/orgs/{id}/role-assignments` | `create` | Give a user of the org a role (four-eyes by the role's risk) (PATH tenant) | org in path | {id} | AssignBody → map<string, any> |
| `POST /api/orgs/{id}/role-assignments/{assignmentId}/revoke` | `administer` | Revoke a role assignment in the org (PATH tenant) | org in path | {id} {assignmentId} | ReasonBody? → map<string, any> |

#### rbac.role

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/admin/rbac/mode` | `read` | Whether RBAC decides application access (shadow / enforce / off) | platform | - | - → map<string, any> |
| `GET /api/admin/rbac/resources` | `read` | The grid rows and the cells endpoints use | platform | - | - → RbacResourceView[] |
| `GET /api/admin/rbac/roles` | `read` | List the standard RBAC roles with their own and effective cells | platform | - | - → RbacRoleView[] |
| `POST /api/admin/rbac/roles` | `create` | Create a standard RBAC role | platform | - | RbacRoleCreate → RbacRoleView |
| `DELETE /api/admin/rbac/roles/{name}` | `delete` | Delete a standard RBAC role | platform | {name} | - → map<string, any> |
| `GET /api/admin/rbac/roles/{name}` | `read` | Get one standard RBAC role | platform | {name} | - → RbacRoleView |
| `PUT /api/admin/rbac/roles/{name}` | `modify` | Set a standard RBAC role's own cells | platform | {name} | RbacRoleUpdate → RbacRoleView |
| `GET /api/orgs/{id}/roles` | `read` | The roles an org may use, its ceiling and its own roles (PATH tenant) | org in path | {id} | - → map<string, any> |
| `POST /api/orgs/{id}/roles` | `create` | Create an org-defined role within the org's ceiling (PATH tenant) | org in path | {id} | OrgRoleBody → map<string, any> |
| `DELETE /api/orgs/{id}/roles/{name}` | `delete` | Delete an org-defined role with no assignments (PATH tenant) | org in path | {id} {name} | - → map<string, any> |
| `PUT /api/orgs/{id}/roles/{name}` | `modify` | Change an org-defined role (PATH tenant) | org in path | {id} {name} | OrgRoleBody → map<string, any> |
| `GET /api/orgs/{id}/ui/widgets` | `read` | View the UI widgets a portal carries, to build an org's roles (PATH tenant) | org in path | {id} portal | - → map<string, any>[] |

#### rbac.user

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/admin/rbac/users` | `read` | The core's users as RBAC subjects | platform | - | - → RbacUserView[] |

#### supplier.account

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/supplier/payments` | `read` | View payments received by supplier | own org | - | - → InvoicePaymentRow[] |
| `GET /api/supplier/profile` | `read` | View supplier profile | own org | - | - → SupplierProfile |

#### supplier.bank_account

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/supplier/bank-accounts` | `read` | View supplier bank accounts | own org | - | - → BankAccountRow[] |
| `POST /api/supplier/bank-accounts/{id}/revoke` | `delete` | Revoke a supplier bank account | own org | {id} | - → nothing |
| `POST /api/supplier/bank-accounts/change` | `modify` | Change supplier bank account | own org | - | BankAccountChangeRequest → ApprovalResult |

#### supplier.catalogue

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/supplier/catalogue` | `read` | View supplier catalogue items | own org | - | - → CatalogueRow[] |
| `POST /api/supplier/catalogue` | `create` | Add a catalogue item | own org | - | CatalogueUpsert → CatalogueRow |
| `POST /api/supplier/catalogue/{id}/retire` | `delete` | Retire a catalogue item | own org | {id} | - → nothing |

#### supplier.invoice

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/supplier/invoices` | `read` | View supplier invoices | own org | - | - → InvoiceListRow[] |
| `GET /api/supplier/invoices/{id}` | `read` | View an invoice detail | own org | {id} | - → InvoiceDetailRow |
| `POST /api/supplier/orders/{id}/invoice` | `create` | Issue an invoice for an order | own org | {id} | - → InvoiceDetailRow |

#### supplier.order

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/supplier/orders` | `read` | View supplier orders | own org | - | - → OrderRow[] |
| `GET /api/supplier/orders/{id}` | `read` | View an order detail | own org | {id} | - → OrderDetailRow |

#### supplier.statement

| endpoint | cell | what it does | acts on | params | body → returns |
|---|---|---|---|---|---|
| `GET /api/supplier/statements` | `read` | View supplier statements | own org | - | - → StatementRow[] |
| `POST /api/supplier/statements` | `execute` | Generate a supplier statement | own org | - | GenerateStatementRequest → StatementRow |
| `GET /api/supplier/statements/{id}/download` | `export` | Download a supplier statement | own org | {id} | - → string |

#### Types (every record and enum used above)

```ts
AcceptPaymentRequest = { amount: number; channel: PaymentChannel; payerRef: string|null; itemId: string|null; itemQuantity: number|null }
AccessDetail = { userId: string|null; phone: string|null; role: string|null; status: string|null; links: AccessLinkRow[]|null; sessions: AccessSessionRow[]|null }
AccessDisableResult = { linkId: string|null; linkStatus: string|null }
AccessLinkRow = { id: string|null; provider: string|null; subject: string|null; status: string|null; idpEnabled: boolean; activatedAt: date-time|null; lastLoginAt: date-time|null }
AccessRevokeResult = { revoked: integer; incidentId: string|null }
AccessSessionRow = { id: string|null; deviceFingerprint: string|null; createdAt: date-time|null; expiresAt: date-time|null; live: boolean }
AccessUserRow = { userId: string|null; phone: string|null; role: string|null; status: string|null; activeSessions: integer }
AdminRecoveryRequest = { adminName: string; phone: string; username: string|null; email: string; note: string|null }
AdvanceDetailView = { advance: AdvanceStatusRow|null; repayments: CollectionRow[]|null }
AdvanceStatus = 'OFFERED' | 'ACCEPTED' | 'FUNDED' | 'REPAYING' | 'CLOSED' | 'DEFAULTED'
AdvanceStatusRow = { id: string|null; status: AdvanceStatus|null; requestedAmount: number|null; principalAmount: number|null; totalRepayment: number|null; repaymentRate: number|null; remainingOwed: number|null; createdAt: date-time|null; fundedAt: date-time|null }
AmlAlertRow = { id: string|null; alertType: string|null; entityType: string|null; entityId: string|null; details: string|null; status: string|null; createdAt: date-time|null }
ApiClientCreate = { clientName: string; scopes: string[]|null; rateLimitTps: integer|null }
ApiClientView = { id: string|null; clientName: string|null; clientId: string|null; scopes: string|null; status: string|null; rateLimitTps: integer|null; lastRotatedAt: date-time|null; createdAt: date-time|null }
ApprovalRequest = { approve: boolean; note: string|null; sanctionsResult: string|null }
ApprovalResult = { id: string|null; status: string|null; message: string|null }
ApprovalStatus = 'PENDING' | 'APPROVED' | 'SUSPENDED' | 'REJECTED'
ArrearsRow = { caseId: string|null; advanceId: string|null; daysPastDue: integer|null; status: string|null }
AssessmentDecision = 'APPROVED' | 'DECLINED' | 'MANUAL_REVIEW'
AssessmentView = { assessmentId: string|null; merchantId: string|null; windowStart: date|null; windowEnd: date|null; eligibleTotal: number|null; tradingDays: integer|null; avgMonthlyNetReceipts: number|null; score: number|null; decision: AssessmentDecision|null; offerPrincipal: number|null; offerRepayment: number|null; repaymentRate: number|null; reasonCodes: string[]|null }
AssessRequest = { merchantId: string }
AssignBody = { userId: string|null; role: string|null; scope: string|null; expiresAt: string|null; reason: string|null }
AuditPage = { rows: AuditRow[]|null; totalElements: integer; page: integer }
AuditRow = { id: string|null; actorRole: string|null; action: string|null; entityType: string|null; entityId: string|null; meta: string|null; simulated: boolean|null; createdAt: date-time|null }
AuthType = 'OAUTH2' | 'API_KEY' | 'MTLS'
BackupRow = { id: string|null; backupType: string|null; startedAt: date-time|null; completedAt: date-time|null; status: string|null }
BalanceView = { available: number|null; pending: number|null; held: number|null; owed: number|null; issuerAccountRef: string|null; legalHolder: string|null }
BankAccountChangeRequest = { bankName: string; accountNumber: string; accountHolder: string; note: string|null }
BankAccountRow = { id: string|null; bankName: string|null; accountNumber: string|null; accountHolder: string|null; status: string|null; createdAt: date-time|null }
BankAccountStatus = 'PENDING_APPROVAL' | 'APPROVED' | 'REJECTED' | 'REVOKED'
BankApprovalRequest = { approve: boolean; note: string|null }
BankApprovalRow = { id: string|null; supplierName: string|null; bankName: string|null; accountNumber: string|null; accountHolder: string|null; status: BankAccountStatus|null; requestedBy: string|null; createdAt: date-time|null }
BreakRow = { id: string|null; breakType: string|null; amount: number|null; reference: string|null; description: string|null; status: string|null; resolvedAt: date-time|null }
CartLine = { catalogueItemId: string; quantity: number }
CatalogueRow = { id: string|null; supplierId: string|null; supplierName: string|null; productName: string|null; unit: string|null; unitPrice: number|null; status: ItemStatus|null }
CatalogueUpsert = { productName: string|null; unit: string|null; unitPrice: number }
CollectionRow = { id: string|null; merchantName: string|null; amount: number|null; eligibleSalesBase: number|null; collectionDate: date|null }
ComplaintRow = { id: string|null; merchantName: string|null; supplierName: string|null; category: string|null; description: string|null; status: ComplaintStatus|null; assignedTo: string|null; createdAt: date-time|null }
ComplaintStatus = 'OPEN' | 'IN_PROGRESS' | 'RESOLVED' | 'CLOSED'
ConnectorTestResult = { connectorId: string|null; name: string|null; ok: boolean; latencyMs: integer|null; message: string|null; label: string|null }
ConnectorType = 'BANK_ISSUER' | 'LENDER' | 'IPS' | 'CARD' | 'QR' | 'KYC' | 'NOTIFICATION'
ConnectorUpsert = { connectorType: ConnectorType; name: string; environment: PaymentEnvironment; baseUrl: string|null; authType: AuthType|null; credentialsRef: string|null; capabilities: string|null }
ConnectorView = { id: string|null; type: string|null; name: string|null; environment: string|null; baseUrl: string|null; authType: string|null; credentialsRef: string|null; capabilities: string|null; status: string|null; lastHealthCheck: date-time|null; healthLatencyMs: integer|null }
ConsentRequest = { lenderId: string; dataFields: string[]|null }
ConsentRow = { consentId: string|null; merchantId: string|null; merchantName: string|null; purpose: string|null; status: ConsentStatus|null; grantedAt: date-time|null }
ConsentStatus = 'ACTIVE' | 'WITHDRAWN'
CostBasis = 'CONTRACTED' | 'PROVISION' | 'UNQUOTED'
CreateComplaintRequest = { merchantId: string|null; supplierId: string|null; category: string; description: string }
CreateDisputeRequest = { paymentId: string|null; reason: string; details: string|null }
CreateIncidentRequest = { title: string; severity: IncidentSeverity; description: string|null }
CreateOrderRequest = { supplierId: string; items: CartLine[] }
DashboardView = { available: number|null; pending: number|null; held: number|null; owed: number|null; salesToday: number|null; costsToday: number|null; profitToday: number|null; recent: RecentRow[]|null }
DecideBody = { approve: boolean|null; reason: string|null }
DeploymentRow = { id: string|null; version: string|null; environment: string|null; status: string|null; notes: string|null; deployedAt: date-time|null }
DeployRequest = { version: string; environment: string; notes: string|null }
DeviceRow = { id: string|null; deviceFingerprint: string|null; mfaMethod: string|null; expiresAt: date-time|null; createdAt: date-time|null }
DisputeRow = { id: string|null; merchantId: string|null; merchantName: string|null; reason: string|null; details: string|null; status: DisputeStatus|null; createdAt: date-time|null }
DisputeStatus = 'OPEN' | 'WON' | 'LOST'
DsarCreateRequest = { merchantId: string }
DsarExport = { dsarId: string|null; merchantId: string|null; businessName: string|null; sections: map<string, any>|null }
DsarRow = { id: string|null; merchantId: string|null; status: string|null; requestedAt: date-time|null; completedAt: date-time|null }
EligibilityView = { windowStart: date|null; windowEnd: date|null; eligibleTotal: number|null; tradingDays: integer|null; avgMonthlyNetReceipts: number|null; rulesApplied: string[]|null }
EventRow = { eventType: string|null; payloadHash: string|null; meta: string|null; createdAt: date-time|null }
FeePreview = { amount: number|null; billed: number|null; externalCost: number|null; contribution: number|null; ipsCustomerFee: number|null; switchingProvision: number|null; sponsorUnquoted: number|null; labels: string[]|null }
FeeReportRow = { id: string|null; reportDate: date|null; feeCode: string|null; amountBilled: number|null; amountCollected: number|null }
FirstAdminResult = { userId: string|null; username: string|null; status: string|null; setupLink: string|null; reviewerUserId: string|null; reviewerUsername: string|null; reviewerStatus: string|null; reviewerSetupLink: string|null }
GenerateStatementRequest = { ownerType: string; ownerId: string; from: date; to: date }
GrantableChild = { type: string|null; allowedCeiling: string[]|null; defaultDirect: string[]|null }
GrantableRole = { name: string|null; description: string|null; baseline: boolean }
GrantableView = { effDirect: string[]|null; children: GrantableChild[]|null; roles: GrantableRole[]|null; baselineRoles: string[]|null }
HealthView = { environment: string|null; availabilityPct: number|null; p95LatencyMs: number|null; tps: number|null; openCriticalIncidents: integer|null; label: string|null }
HoldRequest = { reason: string }
IncidentRow = { id: string|null; title: string|null; severity: IncidentSeverity|null; description: string|null; status: IncidentStatus|null; createdAt: date-time|null; acknowledgedAt: date-time|null; acknowledgedLate: boolean|null; resolvedAt: date-time|null }
IncidentSeverity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW'
IncidentStatus = 'OPEN' | 'MITIGATED' | 'RESOLVED'
InjectBreakRequest = { amount: number; reference: string|null }
InventoryRow = { id: string|null; name: string|null; unit: string|null; currentStock: number|null; reorderLevel: number|null; alert: string|null }
InvoiceDetailRow = { id: string|null; invoiceNumber: string|null; uniqueReference: string|null; supplierName: string|null; merchantName: string|null; amount: number|null; currency: string|null; status: string|null; issuedAt: date-time|null; payments: InvoicePaymentRow[]|null }
InvoiceListRow = { id: string|null; invoiceNumber: string|null; uniqueReference: string|null; counterpartyName: string|null; amount: number|null; currency: string|null; status: InvoiceStatus|null; issuedAt: date-time|null }
InvoicePaymentRoute = 'BOOK_TRANSFER' | 'EFT'
InvoicePaymentRow = { id: string|null; amount: number|null; route: string|null; status: string|null; bankReference: string|null; createdAt: date-time|null; confirmedAt: date-time|null }
InvoiceStatus = 'ISSUED' | 'PARTIALLY_PAID' | 'PAID' | 'CANCELLED'
ItemStatus = 'PENDING_APPROVAL' | 'APPROVED' | 'RETIRED'
KhataAccountRequest = { customerName: string; customerPhone: string|null }
KhataEntryRequest = { entryType: KhataEntryType; amount: number; dueDate: date|null }
KhataEntryRow = { id: string|null; entryType: string|null; amount: number|null; dueDate: date|null; settledAt: date-time|null; createdAt: date-time|null }
KhataEntryType = 'CHARGE' | 'PAYMENT'
KhataRow = { id: string|null; customerName: string|null; customerPhone: string|null; outstanding: number|null; status: string|null }
KycApplicationView = { id: string|null; orgId: string|null; orgType: string|null; orgName: string|null; parentOrgId: string|null; treePath: OrgPathRow[]|null; depth: integer; isSuspended: boolean; status: string|null; application: any|null; submittedApplication: any|null; submittedBy: string|null; submittedAt: date-time|null; decidedBy: string|null; decidedAt: date-time|null; decisionNote: string|null; version: integer; masked: boolean; canEdit: boolean; canSubmit: boolean; canDecide: boolean; createdAt: date-time|null; updatedAt: date-time|null }
KycCheckRequest = { checkType: string|null; result: string|null; details: string|null }
KycCheckRow = { id: string|null; entityType: string|null; entityId: string|null; checkType: string|null; result: string|null; details: string|null; checkedAt: date-time|null }
KycDecisionRequestV2 = { outcome: string; note: string|null }
KycRiskRating = 'LOW' | 'MEDIUM' | 'HIGH'
KycStatus = 'PENDING' | 'VERIFIED' | 'REJECTED'
KycSummaryRow = { id: string|null; orgId: string|null; orgType: string|null; orgName: string|null; parentOrgId: string|null; treePath: string|null; depth: integer; isSuspended: boolean; status: string|null; submittedAt: date-time|null; decidedAt: date-time|null; updatedAt: date-time|null }
KycTaskRow = { id: string|null; orgId: string|null; orgName: string|null; kind: string|null; stageKey: string|null; stageSeq: integer; actorRule: string|null; applicationStatus: string|null; createdAt: date-time|null; openNote: string|null }
LedgerRow = { id: string|null; entryType: string|null; amount: number|null; currency: string|null; source: string|null; status: string|null; reference: string|null; createdAt: date-time|null }
LenderAdvanceRow = { id: string|null; merchantName: string|null; merchantId: string|null; principal: number|null; outstanding: number|null; status: string|null; fundedAt: date-time|null; cohort: string|null }
LenderRow = { id: string|null; name: string|null; licenceRef: string|null }
LimitUpsert = { merchantId: string|null; maxBalance: number|null; dailyDebitCap: number|null; withdrawalCap: number|null; advanceCap: number|null; arrearsTriggerPct: number|null }
LimitView = { id: string|null; merchantId: string|null; maxBalance: number|null; dailyDebitCap: number|null; withdrawalCap: number|null; advanceCap: number|null; arrearsTriggerPct: number|null }
MerchantApprovalRow = { id: string|null; businessName: string|null; ownerName: string|null; ownerPhone: string|null; businessType: string|null; registrationNumber: string|null; kycStatus: KycStatus|null; approvalStatus: ApprovalStatus|null; createdAt: date-time|null; kycRiskRating: KycRiskRating|null; kycNextReviewDate: date|null }
MerchantRecordsView = { merchantName: string|null; windowStart: date|null; windowEnd: date|null; eligibleTotal: number|null; tradingDays: integer|null; avgMonthlyNetReceipts: number|null; monthly: MonthTotal[]|null }
Message = { message: string|null; label: string|null }
Mismatch = { userId: string|null; username: string|null; status: string|null; coreRole: string|null; expectedRole: string|null; activeRbacRoles: string[]|null; reason: string|null }
MonthTotal = { month: string|null; netReceipts: number|null }
OnboardingRequestRow = { id: string|null; kind: string|null; status: string|null; orgId: string|null; orgName: string|null; orgType: string|null; parentOrgId: string|null; requestedBy: string|null; requestedByRole: string|null; requestedByOrgId: string|null; reviewedBy: string|null; decision: string|null; rightsDirect: string|null; rightsCeiling: string|null; note: string|null; meta: string|null; createdAt: date-time|null; reviewedAt: date-time|null }
OnboardWithKycRequest = { type: string|null; kyc: map<string, any>|null; licenceRef: string|null; rightsDirect: string[]|null; rightsCeiling: string[]|null; roleCeiling: string[]|null; roleDelegable: string[]|null }
OrderDetailRow = { id: string|null; merchantName: string|null; supplierName: string|null; status: string|null; createdAt: date-time|null; items: OrderItemRow[]|null; total: number|null }
OrderItemRow = { id: string|null; productName: string|null; unit: string|null; quantity: number|null; unitPrice: number|null; lineTotal: number|null }
OrderRow = { id: string|null; merchantName: string|null; supplierName: string|null; status: OrderStatus|null; createdAt: date-time|null }
OrderStatus = 'DRAFT' | 'SUBMITTED' | 'INVOICED' | 'FULFILLED' | 'CANCELLED'
OrgDecideRequest = { approve: boolean; note: string|null; sanctionsResult: string|null }
OrgDecisionResult = { requestId: string|null; orgId: string|null; status: string|null; message: string|null }
OrgHoldRow = { id: string|null; reason: string|null; createdAt: date-time|null; createdBy: string|null; releasedAt: date-time|null }
OrgMemberRole = 'ADMIN' | 'STAFF'
OrgNodeRow = { id: string|null; type: string|null; name: string|null; lifecycle: string|null; effectiveStatus: string|null; activeHolds: integer; holds: OrgHoldRow[]|null; onboardedBy: string|null; onboardedAt: date-time|null; effectiveRights: string[]|null; users: OrgUserRow[]|null; userCount: integer; subtree: OrgSubtreeCounts|null; rights: OrgRightsView|null; children: OrgNodeRow[]|null }
OrgPathRow = { id: string|null; type: string|null; name: string|null; depth: integer }
OrgRightsView = { direct: string|null; ceiling: string|null; effDirect: string|null; effCeiling: string|null }
OrgRoleBody = { name: string|null; description: string|null; baseRoles: string[]|null; permissions: string[]|null }
OrgStaffView = { id: string|null; name: string|null; username: string|null; phone: string|null; email: string|null; role: string|null; orgRole: string|null; status: string|null; activatedAt: date-time|null }
OrgSubtreeCounts = { descendants: integer; activeDescendants: integer; users: integer; activeUsers: integer }
OrgUserRow = { id: string|null; name: string|null; username: string|null; role: string|null; orgRole: string|null; status: string|null; phone: string|null; email: string|null }
OrgView = { id: string|null; type: string|null; name: string|null; status: string|null; effectiveStatus: string|null; parentOrgId: string|null; entityId: string|null; registrationNumber: string|null; createdAt: date-time|null; kycStatus: string|null; kycRiskRating: string|null; kycNextReviewDate: date-time|null }
OutboxRow = { id: string|null; eventCode: string|null; channel: string|null; recipient: string|null; status: string|null; createdAt: date-time|null; activationLink: string|null }
PayInfo = { qrPayload: string|null; shortCode: string|null; cardTerminalId: string|null }
PayInvoiceRequest = { route: InvoicePaymentRoute|null; outcome: string|null }
PaymentChannel = 'QR' | 'SHORT_CODE' | 'CARD'
PaymentDetailRow = { id: string|null; merchantName: string|null; channel: string|null; amount: number|null; status: string|null; payerRef: string|null; createdAt: date-time|null; confirmedAt: date-time|null; events: EventRow[]|null }
PaymentEnvironment = 'SANDBOX' | 'UAT' | 'PRODUCTION'
PaymentOpsRow = { id: string|null; merchantName: string|null; channel: PaymentChannel|null; amount: number|null; status: PaymentStatus|null; createdAt: date-time|null }
PaymentPending = { paymentId: string|null; amount: number|null; label: string|null }
PaymentRow = { id: string|null; channel: string|null; amount: number|null; currency: string|null; status: string|null; payerRef: string|null; createdAt: date-time|null; confirmedAt: date-time|null }
PaymentStatus = 'INITIATED' | 'PENDING' | 'CONFIRMED' | 'FAILED' | 'REFUNDED'
PortfolioView = { outstandingTotal: number|null; owedTotal: number|null; arrearsAmount: number|null; arrearsPct: number|null; snapshots: SnapshotRow[]|null; arrears: ArrearsRow[]|null }
RateType = 'PERCENTAGE' | 'FIXED'
RbacAssignmentView = { id: string|null; subject: string|null; role: string|null; tenant: string|null; scope: string|null; status: string|null }
RbacAssignRequest = { subject: string|null; role: string|null; tenant: string|null; scope: string|null; expiresAt: string|null; reason: string|null }
RbacEndpointView = { verb: string|null; method: string|null; path: string|null; handler: string|null }
RbacResourceView = { id: string|null; service: string|null; description: string|null; used: string[]|null; tenantTypes: string[]|null; endpoints: RbacEndpointView[]|null }
RbacRoleCreate = { name: string|null; description: string|null; kind: string|null; inherits: string[]|null; permissions: string[]|null }
RbacRoleUpdate = { permissions: string[]|null }
RbacRoleView = { name: string|null; description: string|null; permissions: string[]|null; kind: string|null; approval: string|null; seeded: boolean; locked: boolean; inherits: string[]|null; own: string[]|null; allRows: string[]|null }
RbacUserView = { id: string|null; displayName: string|null; orgId: string|null; kind: string|null; status: string|null }
ReasonBody = { reason: string|null }
RecentRow = { label: string|null; amount: number|null; at: date-time|null; status: string|null }
ReconRunRow = { id: string|null; runDate: date|null; issuerTotal: number|null; subledgerTotal: number|null; creditLedgerTotal: number|null; breaksCount: integer|null; status: string|null; createdAt: date-time|null }
RefundRequest = { amount: number; reason: string|null }
RefundRow = { id: string|null; paymentId: string|null; amount: number|null; reason: string|null; status: string|null; createdAt: date-time|null }
RemindResult = { channel: string|null; to: string|null; body: string|null; label: string|null }
ReorderRow = { id: string|null; itemName: string|null; reorderLevel: number|null; preferredSupplierId: string|null; preferredSupplierName: string|null; orderQuantity: number|null }
Report = { orgId: string|null; lifecycle: string|null; users: integer; activeCoreRoleAssignments: integer; mismatches: integer; rows: Mismatch[]|null }
RequestAdvanceRequest = { amount: number|null; purpose: string|null; lenderId: string }
RestoreTestRow = { id: string|null; backupId: string|null; testedAt: date-time|null; rtoAchievedMinutes: integer|null; rpoAchievedMinutes: integer|null; ok: boolean|null }
RightsPutRequest = { rightsDirect: string[]|null; rightsCeiling: string[]|null }
RoleCeilingRequest = { roles: string[]|null; delegableRoles: string[]|null; inherit: boolean|null }
RuleRow = { id: string|null; ruleCode: string|null; enabled: boolean|null; definition: string|null }
SarRow = { id: string|null; alertId: string|null; reference: string|null; status: string|null; submittedAt: date-time|null; createdAt: date-time|null }
ScenarioRow = { id: string|null; name: string|null; description: string|null; action: string|null; isLabelled: boolean|null; enabled: boolean|null }
SceneRunResult = { scene: string|null; steps: StepResult[]|null }
SettingRow = { key: string|null; value: string|null; updatedAt: string|null }
SettingsView = { profile: MerchantApprovalRow|null; devices: DeviceRow[]|null; shortCode: string|null; qrPayload: string|null }
SettingUpsert = { key: string; value: string }
SettlementRow = { id: string|null; settlementDate: date|null; cycle: string|null; totalAmount: number|null; status: string|null }
SimAdvanceRequest = { action: string|null; merchantId: string|null; lenderId: string|null; amount: string|null; date: string|null }
SimControlsRequest = { scenario: string }
SimControlsView = { defaultScenario: string|null; bankReferenceSeq: string|null; simulatorEnabled: boolean }
SimInvoiceRequest = { merchantId: string|null; invoicePaymentId: string|null; outcome: string|null }
SimPaymentRequest = { actorId: string|null; merchantId: string|null; paymentId: string|null; outcome: string|null }
SimResult = { action: string|null; detail: string|null; label: string|null }
SimTrigger = { action: string }
SnapshotRow = { snapshotId: string|null; snapshotDate: date|null; outstandingPrincipal: number|null; arrearsAmount: number|null }
SpeakerLogRow = { paymentId: string|null; amount: number|null; confirmedAt: date-time|null }
StaffRequest = { name: string; phone: string; username: string|null; email: string; orgRole: OrgMemberRole|null; password: string|null }
StatementRow = { id: string|null; ownerType: string|null; ownerId: string|null; periodStart: date|null; periodEnd: date|null; fileRef: string|null; generatedAt: date-time|null }
StepResult = { step: string|null; ok: boolean; detail: string|null }
StockAdjustRequest = { itemId: string; movementType: StockMovementType; quantity: number; reference: string|null }
StockAlertRow = { id: string|null; itemName: string|null; alertType: string|null; resolved: boolean|null; createdAt: date-time|null }
StockMovementType = 'SALE' | 'DELIVERY' | 'ADJUSTMENT' | 'WASTE'
SupplierApprovalRow = { id: string|null; businessName: string|null; ownerName: string|null; ownerPhone: string|null; registrationNumber: string|null; approvalStatus: ApprovalStatus|null; createdAt: date-time|null; kycRiskRating: KycRiskRating|null; kycNextReviewDate: date|null }
SupplierProfile = { id: string|null; businessName: string|null; ownerPhone: string|null; approvalStatus: ApprovalStatus|null }
SuspendRequest = { entityType: string; entityId: string; reason: string }
SuspensionRow = { id: string|null; entityType: string|null; entityId: string|null; reason: string|null; suspendedAt: date-time|null; liftedAt: date-time|null }
SweepRequest = { date: string|null }
SyntheticHistoryRequest = { merchantId: string; days: integer }
TabAddEntryRequest = { amount: number; description: string|null }
TabCreateRequest = { customerName: string; customerPhone: string|null; amount: number; description: string; dueDate: date|null }
TabEntryRow = { id: string|null; amount: number|null; description: string|null; createdAt: date-time|null }
TabRow = { id: string|null; customerName: string|null; customerPhone: string|null; totalAmount: number|null; status: string|null; dueDate: date|null }
TariffRow = { id: string|null; code: string|null; rateType: string|null; value: number|null; externalCost: number|null; costBasis: string|null; note: string|null; effectiveFrom: date|null; effectiveTo: date|null }
TariffUpsert = { code: string; rateType: RateType; value: number; externalCost: number|null; costBasis: CostBasis; note: string|null; effectiveFrom: date|null }
TemplateChannel = 'SMS' | 'WHATSAPP' | 'SPEAKER' | 'EMAIL' | 'PUSH'
TemplateRow = { id: string|null; channel: string|null; eventCode: string|null; body: string|null; locale: string|null; status: string|null }
TemplateUpsert = { channel: TemplateChannel; eventCode: string; body: string }
TptsBootstrapRequest = { name: string|null; username: string|null; phone: string|null; email: string|null; reviewerName: string|null; reviewerUsername: string|null; reviewerPhone: string|null; reviewerEmail: string|null }
TptsBootstrapResult = { orgId: string|null; firstAdminId: string|null; firstAdminStatus: string|null; staffMoved: integer; movedUserIds: string[]|null }
WithdrawalRequest = { amount: number }
WithdrawalRow = { id: string|null; amount: number|null; status: string|null; requestedAt: date-time|null; processedAt: date-time|null }
```

## The widget API (exact source)

### core/manifest.ts
```ts
/**
 * The widget manifest (docs/plans/portal-builder.md §1): what a widget is, what it needs, what
 * it may do and how big it is. Pure data - no Angular at runtime - so the registry tool, the
 * registry server, the builder palette and the RBAC model all read it without loading the
 * component. The component itself is behind `load` (a lazy chunk).
 *
 * `defineWidget` is the only way to make one; its types turn a missing size, an unknown config
 * type or a malformed action into a build error, and it checks the rest when the module loads.
 */
import type { Type } from '@angular/core';
import type { BusinessPortal } from '../../web-core/portal-permission-catalogue';
import type { Tier, TierSize } from './grid';
import type { MiPayWidget } from './mipay-widget';
import type { Shape } from './shape';

export { span, TIERS, COLS } from './grid';
export type { Tier, TierSize } from './grid';

/** Rows (any verb) or exact cells ("row:verb"), OR semantics like menu.ts - or ALL of a list. */
export type Requires = readonly string[] | { readonly all: readonly string[] };

/**
 * The only operations a widget may support (the RBAC model's vocabulary):
 *  - view:    every read (records, lists, form definitions, dependent lists)
 *  - create:  makes a new record (including submitting a new one)
 *  - modify:  changes an existing record (edit, save draft, submit an existing one)
 *  - approve: every other action - execute, schedule, approve, reject, withdraw, cancel, …
 */
export const OPERATIONS = ['view', 'create', 'modify', 'approve'] as const;
export type Operation = (typeof OPERATIONS)[number];
/** What a command (a write) does: any operation except view. */
export type WriteOperation = Exclude<Operation, 'view'>;

/** A named, gated action inside the widget (shown by the RBAC screen as "<label>: allow"). */
export interface WidgetAction {
  /** Stable id; the element carrying it has `data-action="<id>"`. */
  readonly id: string;
  readonly label: string;
  /** The exact cell that shows the action ("row:verb"). */
  readonly cell: string;
  /** The logical command it sends, when it sends one. */
  readonly command?: string;
}

interface FieldBase { readonly label?: string; readonly help?: string }
export interface NumberField extends FieldBase { readonly type: 'number'; readonly default: number; readonly min?: number; readonly max?: number }
export interface BooleanField extends FieldBase { readonly type: 'boolean'; readonly default: boolean }
export interface StringField extends FieldBase { readonly type: 'string'; readonly default: string }
export interface EnumField<T extends string> extends FieldBase { readonly type: 'enum'; readonly options: readonly T[]; readonly default: T }
export type ConfigField = NumberField | BooleanField | StringField | EnumField<string>;

type FieldFor<T> =
  [T] extends [boolean] ? BooleanField
    : [T] extends [number] ? NumberField
      : [T] extends [string] ? (string extends T ? StringField : EnumField<T>)
        : never;

/** One field per config key, typed by the key's value type (a union of strings = an enum).
 *  `WidgetDef<any>` (the registry's erased form) accepts any field per key. */
export type ConfigSchema<C> = 0 extends (1 & C)
  ? Readonly<Record<string, ConfigField>>
  : { readonly [K in keyof C]-?: FieldFor<C[K]> };

/** Where a widget input comes from: the first non-empty of `selection.<key>` / `context.<key>`. */
export interface WidgetInput { readonly from: readonly string[]; readonly label?: string }

export type WidgetState = 'loading' | 'ready' | 'empty' | 'error' | 'forbidden';

export interface WidgetDef<C = Record<string, unknown>> {
  /** "<area>.<name>", lower case - stable forever (layouts and RBAC refer to it). */
  readonly id: string;
  /** semver; layouts pin the major. */
  readonly version: string;
  readonly label: string;
  readonly description: string;
  readonly category: string;
  /** The portals the widget works in. */
  readonly contexts: readonly BusinessPortal[];
  readonly requires: Requires;
  readonly actions: readonly WidgetAction[];
  /** Logical read sources (shared/widgets/data-sources.ts) - never URLs. */
  readonly sources: readonly string[];
  /** Logical writes. */
  readonly commands: readonly string[];
  /**
   * The operations the widget supports: one or more of view / create / modify / approve and
   * nothing else. Must equal 'view' (when it has sources) plus the operation of every command.
   */
  readonly operations: readonly Operation[];
  /** The operation each command performs - one entry per command, no others. */
  readonly commandOperations: Readonly<Record<string, WriteOperation>>;
  /**
   * What the server must send for each source / command (shape.ts). Every answer is checked
   * against it at runtime and mismatches are shown in the test harness. Required for every
   * source (widgets:check); generate them with `npm run widgets:contract`.
   */
  readonly contracts?: Readonly<Record<string, Shape>>;
  /**
   * What each command SENDS (its request body). The port checks every outgoing body against it
   * and the harness shows a mismatch before the server refuses it. Required for every command
   * that sends a body (POST / PUT / PATCH); generate with `npm run widgets:contract -- --request`.
   */
  readonly requests?: Readonly<Record<string, Shape>>;
  readonly inputs?: Readonly<Record<string, WidgetInput>>;
  /** Page-selection keys this widget writes (e.g. 'orgId' for a directory). */
  readonly publishes?: readonly string[];
  readonly config: ConfigSchema<C>;
  /** Required for EVERY tier (decision 8). */
  readonly size: Readonly<Record<Tier, TierSize>>;
  /** Optional minimum height (rem) for charts and tiles. */
  readonly minHeight?: number;
  readonly hideOn?: readonly ('forbidden' | 'empty')[];
  /** The component: it MUST extend MiPayWidget<C> (a plain component does not compile here). */
  readonly load: () => Promise<Type<MiPayWidget<C>>>;
}

const ID = /^[a-z][a-z0-9]*(\.[a-z][a-z0-9-]*)+$/;
const SEMVER = /^\d+\.\d+\.\d+$/;
const CELL = /^[a-z]+\.[a-z_]+:[a-z]+$/;
const ROW = /^[a-z]+\.[a-z_]+$/;

/** Problems `defineWidget` finds beyond what the types catch (also run by the registry tool). */
export function manifestProblems(def: WidgetDef<any>): string[] {
  const p: string[] = [];
  if (!ID.test(def.id)) p.push(`id "${def.id}" must look like "area.name" (lower case)`);
  if (!SEMVER.test(def.version)) p.push(`version "${def.version}" must be semver (1.0.0)`);
  if (!def.label.trim()) p.push('label is empty');
  if (!def.contexts.length) p.push('contexts is empty: name at least one portal');
  const req = requiredList(def);
  if (!req.length) p.push('requires is empty: every widget is gated by at least one permission');
  for (const r of req) if (!CELL.test(r) && !ROW.test(r)) p.push(`requires "${r}" is neither a row nor a row:verb cell`);
  p.push(...operationProblems(def));
  const ids = new Set<string>();
  for (const a of def.actions) {
    if (ids.has(a.id)) p.push(`action id "${a.id}" is used twice`);
    ids.add(a.id);
    if (!CELL.test(a.cell)) p.push(`action "${a.id}" cell "${a.cell}" must be an exact row:verb cell`);
    if (a.command && !def.commands.includes(a.command)) p.push(`action "${a.id}" sends "${a.command}", which is not in commands`);
  }
  for (const [k, f] of Object.entries(def.config) as [string, ConfigField][]) {
    if (f.type === 'enum' && !f.options.includes(f.default)) p.push(`config.${k}: default "${f.default}" is not an option`);
    if (f.type === 'number' && ((f.min ?? -Infinity) > f.default || (f.max ?? Infinity) < f.default)) p.push(`config.${k}: default is outside min..max`);
  }
  for (const k of Object.keys(def.requests ?? {})) {
    if (!def.commands.includes(k)) p.push(`request contract "${k}" is not a command of the widget`);
  }
  for (const k of Object.keys(def.contracts ?? {})) {
    if (!def.sources.includes(k) && !def.commands.includes(k)) p.push(`contract "${k}" is not a source or command of the widget`);
  }
  for (const [k, i] of Object.entries(def.inputs ?? {})) {
    if (!i.from.length) p.push(`inputs.${k}: from is empty`);
    for (const f of i.from) if (!/^(selection|context)\.[a-zA-Z]+$/.test(f)) p.push(`inputs.${k}: "${f}" must be selection.<key> or context.<key>`);
  }
  return p;
}

/** The operations rule: only OPERATIONS, at least one, and exactly what the sources / commands do. */
export function operationProblems(def: Pick<WidgetDef<any>, 'operations' | 'commandOperations' | 'sources' | 'commands'>): string[] {
  const p: string[] = [];
  const ops = def.operations ?? [];
  const byCommand = def.commandOperations ?? {};
  if (!ops.length) p.push(`operations is empty: name one or more of ${OPERATIONS.join(', ')}`);
  for (const o of ops) if (!OPERATIONS.includes(o)) p.push(`operation "${o}" is not one of ${OPERATIONS.join(', ')}`);
  if (new Set(ops).size !== ops.length) p.push('operations lists an operation twice');
  for (const c of def.commands) {
    const o = byCommand[c];
    if (!o) p.push(`command "${c}" has no entry in commandOperations (create, modify or approve)`);
    else if (o === ('view' as Operation) || !OPERATIONS.includes(o)) p.push(`command "${c}" operation "${o}" must be create, modify or approve`);
  }
  for (const c of Object.keys(byCommand)) if (!def.commands.includes(c)) p.push(`commandOperations "${c}" is not a command of the widget`);
  const derived = new Set<string>([...(def.sources.length ? ['view'] : []), ...Object.values(byCommand)]);
  const missing = [...derived].filter((o) => !ops.includes(o as Operation));
  const extra = ops.filter((o) => OPERATIONS.includes(o) && !derived.has(o));
  if (missing.length) p.push(`operations is missing ${missing.join(', ')} (from its sources / commands)`);
  if (extra.length) p.push(`operations declares ${extra.join(', ')}, which no source or command performs`);
  return p;
}

/** The only constructor. Throws on load when the manifest is inconsistent. */
export function defineWidget<C>(def: WidgetDef<C>): WidgetDef<C> {
  const problems = manifestProblems(def as WidgetDef<any>);
  if (problems.length) throw new Error(`widget ${def.id}: ${problems.join('; ')}`);
  return Object.freeze(def);
}

/** The flat list of permissions in `requires`. */
export function requiredList(def: Pick<WidgetDef<any>, 'requires'>): readonly string[] {
  return Array.isArray(def.requires) ? def.requires : (def.requires as { all: readonly string[] }).all;
}

/** Is the widget allowed for someone whose `can` answers like AccessService.can? */
export function requiresMet(def: Pick<WidgetDef<any>, 'requires'>, can: (perm: string[]) => boolean): boolean {
  const r = def.requires;
  if (Array.isArray(r)) return r.length > 0 && can([...r]);
  const all = (r as { all: readonly string[] }).all;
  return all.length > 0 && all.every((p) => can([p]));
}

/** Every cell the widget names (requires + actions): what the RBAC screen lists for it. */
export function cellsOf(def: Pick<WidgetDef<any>, 'requires' | 'actions'>): string[] {
  return [...new Set([...requiredList(def), ...def.actions.map((a) => a.cell)])];
}

/** Config defaults, overridden by a layout's values. */
export function configWithDefaults<C>(def: WidgetDef<C>, given?: Partial<C>): C {
  const out: Record<string, unknown> = {};
  for (const [k, f] of Object.entries(def.config) as [string, ConfigField][]) out[k] = f.default;
  return { ...out, ...(given ?? {}) } as C;
}
```
### core/grid.ts
```ts
/**
 * The mobile-first page grid (docs/plans/portal-builder.md, decision 8).
 *
 * Tiers are picked by the width of the page's CONTENT AREA (CSS container queries), not the
 * viewport: the same page lays out the same whether the sidebar is open, in a builder frame or
 * in the RBAC "preview as role" pane.
 *
 * Every widget declares its span for EVERY tier with `span.*`. The types make mistakes build
 * errors: a missing tier, a span wider than the tier's grid, a default below its min or a max
 * below its default.
 *
 * Pure TypeScript (no Angular): read by the Node registry tool and the RBAC model too.
 */
export type Tier = 'xs' | 'sm' | 'md' | 'lg';
export const TIERS: readonly Tier[] = ['xs', 'sm', 'md', 'lg'];

/** Columns per tier. */
export const COLS = { xs: 4, sm: 8, md: 12, lg: 12 } as const;

/** Smallest content width (px) of each tier; `xs` starts at 0. */
export const TIER_MIN_WIDTH: Record<Tier, number> = { xs: 0, sm: 600, md: 960, lg: 1280 };

/** The narrowest content width each tier is checked at (layout tests, workbench frames). */
export const TIER_TEST_WIDTH: Record<Tier, number> = { xs: 360, sm: 600, md: 960, lg: 1280 };

/** 1..N as a union of number literals. */
export type Nums<N extends number, A extends unknown[] = [unknown]> =
  A['length'] extends N ? N : A['length'] | Nums<N, [...A, unknown]>;

/** Lo..Hi as a union of number literals. */
export type Between<Lo extends number, Hi extends number> = Exclude<Nums<Hi>, Exclude<Nums<Lo>, Lo>>;

export interface Span { readonly kind: 'span'; readonly min: number; readonly default: number; readonly max: number }
export interface Hidden { readonly kind: 'hidden'; readonly reason: string }
export type TierSize = Span | Hidden;

/** span.<tier>(min) · (min, default) · (min, default, max) - each bound checked against the last. */
export interface TierFn<C extends number> {
  <Min extends Nums<C>>(min: Min): Span;
  <Min extends Nums<C>, Def extends Between<Min, C>>(min: Min, def: Def): Span;
  <Min extends Nums<C>, Def extends Between<Min, C>, Max extends Between<Def, C>>(min: Min, def: Def, max: Max): Span;
}

function tier<C extends number>(cols: C): TierFn<C> {
  return ((min: number, def?: number, max?: number): Span =>
    ({ kind: 'span', min, default: def ?? min, max: max ?? cols })) as TierFn<C>;
}

/**
 *   size: { xs: span.xs(4), sm: span.sm(4, 8), md: span.md(6, 8, 12), lg: span.lg(4, 6) }
 *
 * `span.<tier>(min, default = min, max = all columns)`. `span.hidden(reason)` takes the widget
 * off that tier; the reason is shown in the builder (a layout choice, never a permission).
 */
export const span = {
  xs: tier(COLS.xs),
  sm: tier(COLS.sm),
  md: tier(COLS.md),
  lg: tier(COLS.lg),
  hidden: (reason: string): Hidden => ({ kind: 'hidden', reason }),
};

/** The tier a content width falls into. */
export function tierOf(widthPx: number): Tier {
  if (widthPx >= TIER_MIN_WIDTH.lg) return 'lg';
  if (widthPx >= TIER_MIN_WIDTH.md) return 'md';
  if (widthPx >= TIER_MIN_WIDTH.sm) return 'sm';
  return 'xs';
}

/** A layout's span for a tier, clamped to what the widget allows; null when hidden there. */
export function spanFor(size: TierSize, override?: number): number | null {
  if (size.kind === 'hidden') return null;
  const want = override ?? size.default;
  return Math.max(size.min, Math.min(size.max, want));
}
```
### core/shape.ts
```ts
/**
 * Response contracts: what a widget expects the server to send for each source.
 *
 * The widget port checks every answer against its contract (ScopedDataPort) and reports the
 * difference - "$.items[0].stageSeq: expected integer, got "2" (string)" - in the test harness
 * (WIDGET_DIAGNOSTICS = 'panel') or the console (portals). The fixtures are checked against the
 * same contract by `widgets:check`, so canned data cannot drift from what the server says.
 *
 * Contracts are generated from the backend's Java records by
 *   npm run widgets:contract -- GET /api/kyc/tasks --name kyc.tasks.list --out <widget>.contract.ts
 * and `Infer<typeof SHAPE>` gives the TypeScript type, so there is one definition, not two.
 *
 * Pure TypeScript (no Angular): the registry tool runs it in Node.
 */

export type Shape =
  | { readonly kind: 'string'; readonly format?: 'date-time' | 'date' }
  | { readonly kind: 'number'; readonly integer?: boolean }
  | { readonly kind: 'boolean' }
  | { readonly kind: 'enum'; readonly values: readonly string[] }
  | { readonly kind: 'array'; readonly of: Shape }
  | { readonly kind: 'map'; readonly of: Shape }
  | { readonly kind: 'object'; readonly name?: string; readonly fields: Readonly<Record<string, Shape>> }
  | { readonly kind: 'nullable'; readonly of: Shape }
  | { readonly kind: 'oneOf'; readonly options: readonly Shape[] }
  | { readonly kind: 'any' };

interface StringS { readonly kind: 'string'; readonly format?: 'date-time' | 'date' }
interface NumberS { readonly kind: 'number'; readonly integer?: boolean }
interface BooleanS { readonly kind: 'boolean' }
interface EnumS<V extends string> { readonly kind: 'enum'; readonly values: readonly V[] }
interface ArrayS<T extends Shape> { readonly kind: 'array'; readonly of: T }
interface MapS<T extends Shape> { readonly kind: 'map'; readonly of: T }
interface ObjectS<F extends Record<string, Shape>> { readonly kind: 'object'; readonly name?: string; readonly fields: F }
interface NullableS<T extends Shape> { readonly kind: 'nullable'; readonly of: T }
interface OneOfS<O extends readonly Shape[]> { readonly kind: 'oneOf'; readonly options: O }
interface AnyS { readonly kind: 'any' }

/** The TypeScript type a shape describes. */
export type Infer<S> =
  S extends OneOfS<infer O> ? Infer<O[number]>
    : S extends NullableS<infer T> ? Infer<T> | null
    : S extends StringS ? string
      : S extends NumberS ? number
        : S extends BooleanS ? boolean
          : S extends EnumS<infer V> ? V
            : S extends ArrayS<infer T> ? Infer<T>[]
              : S extends MapS<infer T> ? Record<string, Infer<T>>
                : S extends ObjectS<infer F> ? { [K in keyof F]: Infer<F[K]> }
                  : unknown;

/** Shape builders. Generated contracts use these; hand-written ones should too. */
export const s = {
  string: (): StringS => ({ kind: 'string' }),
  dateTime: (): StringS => ({ kind: 'string', format: 'date-time' }),
  date: (): StringS => ({ kind: 'string', format: 'date' }),
  number: (): NumberS => ({ kind: 'number' }),
  integer: (): NumberS => ({ kind: 'number', integer: true }),
  boolean: (): BooleanS => ({ kind: 'boolean' }),
  enum: <V extends string>(...values: V[]): EnumS<V> => ({ kind: 'enum', values }),
  array: <T extends Shape>(of: T): ArrayS<T> => ({ kind: 'array', of }),
  map: <T extends Shape>(of: T): MapS<T> => ({ kind: 'map', of }),
  object: <F extends Record<string, Shape>>(name: string, fields: F): ObjectS<F> => ({ kind: 'object', name, fields }),
  nullable: <T extends Shape>(of: T): NullableS<T> => ({ kind: 'nullable', of }),
  /** One of several server response shapes (for example legacy and gateway modes). */
  oneOf: <O extends readonly Shape[]>(...options: O): OneOfS<O> => ({ kind: 'oneOf', options }),
  /** Free-form JSON (a Map<String,Object> or JsonNode on the server): only "present" is checked. */
  any: (): AnyS => ({ kind: 'any' }),
};

export interface ContractIssue {
  /** JSON path, e.g. $[0].treePath[1].depth */
  readonly path: string;
  /** error: the widget cannot rely on the value; warning: the server sends more than the contract knows. */
  readonly level: 'error' | 'warning';
  readonly expected: string;
  readonly got: string;
}

const MAX_ISSUES = 40;

export function describe(sh: Shape): string {
  switch (sh.kind) {
    case 'nullable': return `${describe(sh.of)} | null`;
    case 'string': return sh.format ? `string (${sh.format})` : 'string';
    case 'number': return sh.integer ? 'integer' : 'number';
    case 'enum': return `one of ${sh.values.map((v) => JSON.stringify(v)).join(', ')}`;
    case 'array': return `array of ${describe(sh.of)}`;
    case 'map': return `map of ${describe(sh.of)}`;
    case 'object': return sh.name ? `object ${sh.name}` : 'object';
    case 'oneOf': return `one of ${sh.options.map(describe).join(' | ')}`;
    default: return sh.kind;
  }
}

function gotOf(v: unknown): string {
  if (v === undefined) return 'nothing (field missing)';
  if (v === null) return 'null';
  if (Array.isArray(v)) return `array (${v.length})`;
  if (typeof v === 'object') return 'object';
  const j = JSON.stringify(v);
  return `${j.length > 40 ? j.slice(0, 40) + '…' : j} (${typeof v})`;
}

/** Every difference between a value and its contract (empty = matches). */
export function check(sh: Shape, value: unknown, path = '$', out: ContractIssue[] = []): ContractIssue[] {
  if (out.length >= MAX_ISSUES) return out;
  const err = (expected: string) => out.push({ path, level: 'error', expected, got: gotOf(value) });
  if (sh.kind === 'nullable') {
    if (value === null) return out;
    if (value === undefined) { err(describe(sh)); return out; }
    const before = out.length;
    check(sh.of, value, path, out);
    // a wrong value here: say that null would also have been fine
    for (let i = before; i < out.length; i++) if (out[i].path === path) out[i] = { ...out[i], expected: `${out[i].expected} | null` };
    return out;
  }
  if (sh.kind === 'any') { if (value === undefined) err('a value'); return out; }
  if (sh.kind === 'oneOf') {
    const attempts = sh.options.map((option) => {
      const issues: ContractIssue[] = [];
      check(option, value, path, issues);
      return issues;
    });
    const match = attempts.find((issues) => !issues.some((issue) => issue.level === 'error'));
    if (match) {
      for (const issue of match) if (issue.level === 'warning') out.push(issue);
      return out;
    }
    err(describe(sh));
    return out;
  }
  if (value === null || value === undefined) { err(describe(sh)); return out; }
  switch (sh.kind) {
    case 'string':
      if (typeof value !== 'string') err(describe(sh));
      else if (sh.format === 'date-time' && Number.isNaN(Date.parse(value))) err('an ISO date-time');
      else if (sh.format === 'date' && !/^\d{4}-\d{2}-\d{2}$/.test(value)) err('a date (yyyy-mm-dd)');
      break;
    case 'number':
      if (typeof value !== 'number' || Number.isNaN(value)) err(describe(sh));
      else if (sh.integer && !Number.isInteger(value)) err('integer');
      break;
    case 'boolean':
      if (typeof value !== 'boolean') err('boolean');
      break;
    case 'enum':
      if (typeof value !== 'string' || !sh.values.includes(value)) err(describe(sh));
      break;
    case 'array':
      if (!Array.isArray(value)) { err(describe(sh)); break; }
      value.forEach((v, i) => check(sh.of, v, `${path}[${i}]`, out));
      break;
    case 'map':
      if (typeof value !== 'object' || Array.isArray(value)) { err(describe(sh)); break; }
      for (const [k, v] of Object.entries(value as object)) check(sh.of, v, `${path}.${k}`, out);
      break;
    case 'object': {
      if (typeof value !== 'object' || Array.isArray(value)) { err(describe(sh)); break; }
      const obj = value as Record<string, unknown>;
      for (const [k, f] of Object.entries(sh.fields)) check(f, obj[k], `${path}.${k}`, out);
      for (const k of Object.keys(obj)) {
        if (!(k in sh.fields) && out.length < MAX_ISSUES) {
          out.push({ path: `${path}.${k}`, level: 'warning', expected: `no field "${k}" in ${describe(sh)}`, got: gotOf(obj[k]) });
        }
      }
      break;
    }
  }
  return out;
}

/** A plausible value for a shape, from its field names (seed for fixtures; replace with real data). */
export function sample(sh: Shape, field = '', i = 1): unknown {
  switch (sh.kind) {
    case 'nullable': return sample(sh.of, field, i);
    case 'oneOf': return sample(sh.options[0], field, i);
    case 'any': return {};
    case 'boolean': return /^(is|can|has)/.test(field) ? i % 2 === 0 : false;
    case 'number': return /seq|depth|count|version/i.test(field) ? i : 100 * i;
    case 'enum': return sh.values[(i - 1) % sh.values.length];
    case 'array': return [sample(sh.of, field, 1), sample(sh.of, field, 2)];
    case 'map': return { key: sample(sh.of, field, i) };
    case 'object': {
      const o: Record<string, unknown> = {};
      for (const [k, f] of Object.entries(sh.fields)) o[k] = sample(f, k, i);
      return o;
    }
    case 'string':
      if (sh.format === 'date-time') return `2026-11-${String(10 + i).padStart(2, '0')}T09:00:00Z`;
      if (sh.format === 'date') return `2026-11-${String(10 + i).padStart(2, '0')}`;
      if (/^id$|Id$/.test(field)) return `${field.replace(/Id$/, '') || 'item'}-${i}`;
      if (/name/i.test(field)) return `Sample ${field.replace(/Name$/i, '') || 'name'} ${i}`;
      if (/status/i.test(field)) return 'PENDING';
      return `${field || 'text'} ${i}`;
  }
}
```
### core/fixtures.ts
```ts
/**
 * Fixture scenarios: canned answers for a widget's sources and commands, so it runs standalone
 * (workbench, unit tests, builder canvas) with no gateway and no session.
 *
 * Every widget ships at least `default`, `empty` and `error` (decision 9) - the type requires
 * them. A value can be data, `fail(status, problem)`, or a function of the call's params.
 *
 * Pure TypeScript: the registry tool reads the scenario names for the layout test matrix.
 */
import type { WidgetState } from './manifest';

export interface FixtureFail {
  readonly __fail: number;
  readonly problem?: {
    code?: string;
    title?: string;
    detail?: string;
    fields?: Record<string, string>;
  };
}
export type FixtureAnswer = object | string | number | boolean | null;
export type FixtureFn = (params: Readonly<Record<string, unknown>>) => FixtureAnswer;
/** Data, fail(…), or a function of the call's params (path params, query params and `body`). */
export type FixtureValue = FixtureAnswer | FixtureFn;
/** Source / command name → answer. */
export type Scenario = Readonly<Record<string, FixtureValue>>;

export interface Scenarios {
  readonly default: Scenario;
  readonly empty: Scenario;
  readonly error: Scenario;
  readonly [name: string]: Scenario;
}

export interface FixtureSet {
  readonly scenarios: Scenarios;
  /** The state each scenario must settle in (layout tests check it). Defaults below. */
  readonly expect: Readonly<Record<string, WidgetState>>;
}

const DEFAULT_EXPECT: Record<string, WidgetState> = { default: 'ready', empty: 'empty', error: 'error' };

export function defineFixtures(scenarios: Scenarios, opts: { expect?: Record<string, WidgetState> } = {}): FixtureSet {
  const expect: Record<string, WidgetState> = {};
  for (const name of Object.keys(scenarios)) expect[name] = opts.expect?.[name] ?? DEFAULT_EXPECT[name] ?? 'ready';
  return { scenarios, expect };
}

export function fail(status: number, problem?: FixtureFail['problem']): FixtureFail {
  return { __fail: status, problem };
}

export function isFail(v: unknown): v is FixtureFail {
  return !!v && typeof v === 'object' && '__fail' in (v as object);
}
```
### core/mipay-widget.ts
```ts
import { Directive, Signal, computed, inject } from '@angular/core';
import { PageSelection, WIDGET_CONFIG, WIDGET_CONTEXT, WIDGET_INPUTS, WIDGET_SIZE, WidgetStateRef } from './context';
import { WidgetDataPort } from './port';

/**
 * The base of every MiPay widget component. A manifest's `load` is typed
 * `Promise<Type<MiPayWidget<C>>>`, so registering a component that does not extend this class
 * does not compile (the protected members make the check nominal, not a look-alike shape).
 *
 * What a widget gets, already wired by the host for the context it is placed in:
 *   config   its settings from the layout (defaults filled in), typed C
 *   data     the port - the ONLY way to reach the network (never inject HttpClient)
 *   state    loading / ready / empty / error / forbidden (the port tracks most of it)
 *   context  portal, own org, screen, mode
 *   size     the cell's grid tier and width (structural switches: fewer columns on a phone;
 *            styling stays in @container)
 *   input()  a manifest input, resolved from page selection or context
 *   publish  write a page-selection key this widget declares in `publishes`
 */
@Directive()
export abstract class MiPayWidget<C = Record<string, unknown>> {
  protected readonly config = inject(WIDGET_CONFIG) as unknown as Readonly<C>;
  protected readonly data = inject(WidgetDataPort);
  protected readonly state = inject(WidgetStateRef);
  protected readonly context = inject(WIDGET_CONTEXT);
  protected readonly size = inject(WIDGET_SIZE);
  private readonly inputs = inject(WIDGET_INPUTS);
  private readonly selection = inject(PageSelection, { optional: true });

  /** A manifest input (null until something provides it). */
  protected input<T = unknown>(key: string): Signal<T | null> {
    const s = this.inputs[key];
    if (!s) throw new Error(`input "${key}" is not declared in the widget manifest`);
    return computed(() => (s() ?? null) as T | null);
  }

  protected publish(key: string, value: unknown) {
    this.selection?.set(key, value);
  }
}
```
### core/testing.ts (the test harness API; import `renderWidget` from `../../core/testing`)
```ts
export interface RenderOpts {
  portal: BusinessPortal;
  orgId?: string | null;
  scenario?: string;
  /** Per-name answers that replace the scenario's. */
  override?: Scenario;
  /** Held cells (FixedAccess). */
  cells?: readonly string[];
  selection?: Readonly<Record<string, unknown>>;
  config?: Record<string, unknown>;
  /** The grid tier of the widget's cell (WIDGET_SIZE): 'md' (laptop) unless given. */
  tier?: Tier;
}

export interface RenderedWidget {
  readonly fixture: ComponentFixture<unknown>;
  /** The <mp-widget-host> element. */
  readonly host: HTMLElement;
  readonly access: FixedAccess;
  readonly selection: PageSelection;
  /** Recorded port calls, in order. */
  calls(): readonly PortCall[];
  /** [name, params] of each call - compact for expectations. */
  callList(): Array<[string, Readonly<Record<string, unknown>>]>;
  lastCommand(): PortCall | undefined;
  /** data-widget-state of the host: loading | ready | empty | error | forbidden | denied | unsupported. */
  state(): string | null;
  hidden(): boolean;
  query<E extends Element = HTMLElement>(sel: string): E | null;
  queryAll<E extends Element = HTMLElement>(sel: string): E[];
  text(sel?: string): string;
  click(sel: string): Promise<void>;
  grant(...cells: string[]): Promise<void>;
  revoke(...cells: string[]): Promise<void>;
  select(key: string, value: unknown): Promise<void>;
  settle(): Promise<void>;
}
```

## UI elements you may use (the only ones)

| layer | selector | class | import (from frontend/) | inputs (* required) | outputs | what it is |
|---|---|---|---|---|---|---|
| atoms | `currency-amount` | CurrencyAmount | src/app/design-system/atoms/currency-amount | amount*: number \| string; dim = false; title = '' | - | Atom: NAD money rendering (server sends numeric strings/numbers). |
| atoms | `mp-button` | MpButton | src/app/design-system/atoms/mp-button | variant: 'primary' \| 'ghost' \| 'danger' = 'primary'; type: 'button' \| 'submit' \| 'reset' = 'button'; disabled = false; loading = false | onClick | Atom: the one button. variant + size typed; danger = destructive. |
| atoms | `mp-card` | MpCard | src/app/design-system/atoms/mp-card | title = ''; subtitle = '' | - | Atom: surface card. Used by every screen panel; nothing else draws boxes. |
| atoms | `mp-checkbox` | MpCheckbox | src/app/design-system/atoms/mp-checkbox | checked = false; disabled = false; locked = false; label = ''; ariaLabel = ''; title = '' | - | Atom: THE checkbox. A real native checkbox (keyboard, screen readers, forms) drawn the same in every portal: ticked, unticked, disabled (greyed, not clickable) and `locked` (ticked and not changeable here, e.g. a cell inherited from another role). Every tick box goes through mp-checkbox, never a bare <input type="ch… |
| atoms | `mp-input` | MpInput | src/app/design-system/atoms/mp-input | value: string = ''; type = 'text'; placeholder = ''; disabled = false; emits: 'text' \| 'number' = 'text' | - | THE text input. Every form goes through mp-input (or mp-select), so a11y, invalid styling and placeholder conventions exist exactly once. |
| atoms | `mp-select` | MpSelect | src/app/design-system/atoms/mp-select | value: string = ''; options*: { value: string; label?: string }[]; placeholder = ''; disabled = false | - | Atom: The select. options = [{value,label}]; values are wire strings only. |
| atoms | `mp-spinner` | MpSpinner | src/app/design-system/atoms/mp-spinner | label = '' | - | Atom: inline loading state for pages and tables. |
| atoms | `status-badge` | StatusBadge | src/app/design-system/atoms/status-badge | status*: string; simulated: boolean \| string = false | - | The single status chip. Color/label resolution lives in one shared map (core/status-maps.ts) used by this atom and nowhere else, so a new status needs exactly one line. If a payload is simulated/synthetic, an amber SIMULATED chip renders alongside (UAT SB p.16). |
| molecules | `empty-state` | EmptyState | src/app/design-system/molecules/empty-state | title = 'Nothing here yet'; hint = ''; icon = '⌀' | - | Molecule: universal empty state (tables, lists, lookups). |
| molecules | `kv-pair` | KvPair | src/app/design-system/molecules/kv-pair | k*: string | - | Molecule: label/value pair for detail pages (invoice detail, device rows...). |
| molecules | `mp-alert` | MpAlert | src/app/design-system/molecules/alert | kind: 'ok' \| 'bad' \| 'info' \| string = 'info' | - | THE inline message, the same in every portal and in sme-ui: "Saved", "Invoice issued", a refusal from the server, a form error. Change how messages look here only. <mp-alert kind="ok">Saved.</mp-alert> <mp-alert [kind]="f.kind">{{ f.text }}</mp-alert> |
| molecules | `mp-form-field` | FormField | src/app/design-system/molecules/form-field | key*: string; label*: string; kind: 'text' \| 'number' \| 'select' = 'text'; value = ''; options: { value: string; label?: string }[] = []; placeholder = ''; hint = ''; error = ''; required = false; disabled = false | changed | Molecule: label + control + hint/error around the atomic control. The declarative <mp-entity-form> organism renders one per schema field; screens never hand-write labels/errors. |
| molecules | `mp-pager` | MpPager | src/app/design-system/molecules/table-view | view*: TableView<unknown> | - | Search + paging state used inside mp-data-table (data-table.ts). Screens use mp-data-table, not this directly; only a layout data-table can't express (the permission matrix's role grid) drives a TableView + <mp-pager> itself. A TableView wraps the table's source rows; the template loops over `view.rows()` (the curre… |
| molecules | `mp-table-search` | MpTableSearch | src/app/design-system/molecules/table-view | view*: TableView<unknown>; placeholder = 'Search' | - | Search + paging state used inside mp-data-table (data-table.ts). Screens use mp-data-table, not this directly; only a layout data-table can't express (the permission matrix's role grid) drives a TableView + <mp-pager> itself. A TableView wraps the table's source rows; the template loops over `view.rows()` (the curre… |
| molecules | `mp-tabs` | MpTabs | src/app/design-system/molecules/tabs | options*: readonly TabOption[]; value*: string; label = '' | valueChange | leave the option out (e.g. the viewer lacks its permission) */ hidden?: boolean; } /** THE tab / segmented switch, the same in every portal: status tabs, view switches, "create vs use existing". Change how tabs look or behave here only. <mp-tabs [options]="tabOpts" [value]="tab()" (valueChange)="setTab($event)" labe… |
| molecules | `simulated-banner` | SimulatedBanner | src/app/design-system/molecules/simulated-banner | text = 'SANDBOX/UAT - all bank and lender events are SIMULATED (UAT SB p.16 | - | Molecule: the amber sandbox banner (E02). Shown globally on the shell; also embeddable inside any screen for section-level context. |
| molecules | `stat-tile` | StatTile | src/app/design-system/molecules/stat-tile | label*: string; amount*: number \| string; sub = ''; money = true | - | Molecule: one KPI number (M04 dashboard tiles). label + amount + sub text. Money renders with the single money atom so N$ formatting lives in one place. |
| organisms | `approval-actions` | ApprovalActions | src/app/design-system/organisms/approval-actions | status*: string; simulated: boolean \| string = false; approveLabel = 'Approve'; rejectLabel = 'Reject'; note = ''; sanctions = ''; perm = '' | decide | Organism: approve / reject decision strip used by ALL approval queues (merchant, supplier, bank-account; T02/T03/T04). Blocks on the same-state rules server-side; the UI just collects approve + optional note/sanctions. Callers pass the queue's AUTHORIZE cell (e.g. "ops.merchant_approval:authorize"): without it the d… |
| organisms | `event-timeline` | EventTimeline | src/app/design-system/organisms/event-timeline | events*: EventRow[] | - | Organism: vertical event trail (M10 payment detail, TC-ACC-05 evidence). |
| organisms | `kpi-row` | KpiRow | src/app/design-system/organisms/kpi-row | tiles*: { label: string; amount: number \| string; sub?: string; money?: boolean }[] | - | Organism: row of KPI tiles (dashboards M04/S08/L10). Amounts render as money (N$) unless a tile sets `money: false` (counts). Tiles share one row when there is room and wrap on narrow widths (phones: two per row), so a row never overflows its container. |
| organisms | `mp-data-table` | DataTable | src/app/design-system/organisms/data-table | cols*: DataCol<T>[]; rows*: readonly T[] \| T[] \| null \| undefined; empty = 'Nothing here yet.'; loading = 'Loading…'; noMatch = 'Nothing matches your search.'; searchable = true; placeholder = 'Search'; pageSize = 10; selectable = false | rowClick |  |
| organisms | `mp-entity-form` | MpEntityForm | src/app/design-system/organisms/mp-entity-form | schema*: MpFieldSchema[]; initial: Record<string, string>; submitLabel = 'Save' | - |  |
| organisms | `mp-modal` | Modal | src/app/design-system/organisms/modal | open = false; title = ''; eyebrow: string \| null = null; subtitle: string \| null = null; size: 'narrow' \| 'normal' \| 'wide' = 'normal'; dirty = false; discardText = 'Discard your changes? Nothing has been saved.' | closed | The popup every create / edit form in every portal uses, so they all look and behave the same: a blurred backdrop, a TPTS-styled card with an eyebrow, title and ✕, a scrolling body and an optional footer (`<div modal-footer>…</div>`). Esc, ✕ and a backdrop click all ask to close. When `dirty` is true the modal first… |
| organisms | `mp-operations-grid` | OperationsGrid | src/app/design-system/organisms/operations-grid | menus*: GridMenu[]; selected*: Set<string>; inherited: Set<string> = new Set(; disabled = false; maxHeight = 'min(70vh, 640px; empty = 'No widgets on this portal yet.' | - | Organism: what a role may do, widget by widget (shared/rbac/role-operations-model.ts). A portal's menus › submenus › widgets, each widget with the four operations View / Create / Modify / Approve as tick boxes. The four column titles stay visible no matter what: the header row is sticky while the grid scrolls, and e… |
| organisms | `mp-role-menu-builder` | RoleMenuBuilder | src/app/design-system/organisms/role-menu-builder | portal*: BusinessPortal; palette*: PaletteRow[]; selected*: Set<string>; inherited: Set<string> = new Set(; disabled = false; nav = false | - | Organism: the role builder (docs/plans/role-menu-builder.md). One portal's menus and submenus, drawn like its sidebar. Each menu and submenu has a visible switch; inside a visible submenu (a screen) the actions the role may take there. With menu cells (`nav`, 2026-11-14) every submenu has its own switch: on gives it… |
| organisms | `mp-table` | MpTable | src/app/design-system/organisms/mp-table | cols*: MpColumn[]; rows*: readonly unknown[] \| unknown[] \| null \| undefined; loading = 'Loading…'; empty = 'Nothing here yet (no rows match this view; selectable = false; searchable = true | rowClick |  |
| organisms | `nav-shell` | NavShell | src/app/design-system/organisms/nav-shell | - | - | Organism: application shell - sidebar nav (role-aware), topbar (user + logout) and the global SIMULATED banner. One shell serves all 5 portals; nav items differ only by role (no per-portal shells). |
| organisms | `ng-template[mpCell]` | MpCell | src/app/design-system/organisms/data-table | name*: string | - | THE table. Every table in every portal and in sme-ui renders through this one component: search, paging, empty / no-match messages, row clicks and the look all live here, so a change to tables is made here only. Never write a bare <table> in a feature screen. <mp-data-table [cols]="cols" [rows]="people()" placeholde… |
| organisms | `page-header` | PageHeader | src/app/design-system/organisms/page-header | title*: string; subtitle = '' | - | Organism: page header - title + subtitle + optional action slot. Every page starts with this so headings/nav state look identical everywhere. |
| shared/kyc | `mp-kyc-application` | KycApplication | projects/shared/kyc/kyc-application | orgId*: string; readOnly = false; wizard = false; allowEdit = true; startStep = 0 | changed, edit | One organization's KYC application: status, the schema-driven form, and Save / Submit / Withdraw. Who may edit is decided by the server (view.canEdit: the org's own ADMIN, its parent org's ADMIN, or MiPay staff); everyone else gets the same form read-only, masked for higher ancestors. |
| shared/kyc | `mp-kyc-progress` | KycProgress | projects/shared/kyc/kyc-progress | labels*: string[]; current = 0; done: boolean[] = []; bad: boolean[] = []; clickable = false | pick | "Business Profile & General Information" → "Business"; known KYC sections get a fixed short name. */ export function shortStepLabel(sec: { key: string; title: string } \| undefined): string { if (!sec) return ''; const known: Record<string, string> = { businessProfile: 'Business', operationalDetails: 'Operations', ad… |
| shared/kyc | `mp-kyc-schema-editor` | KycSchemaEditor | projects/shared/kyc/kyc-schema-editor | schema*: SchemaResponse; doc*: Doc; disabled = false; context: Doc; problems: string[] = []; wizard = false; startStep = 0; hideProgress = false; blurbs: Record<string, string> | entityTypeChange |  |
| shared/kyc | `mp-kyc-workspace` | KycWorkspace | projects/shared/kyc/kyc-workspace | initialOrgId: string \| null = null | - | The KYC page every portal shows: the caller's org tree with each org's KYC status, the selected org's application (editable where the server says the caller may edit) and the review tasks the caller may act on. Org portals see their own org and everything below it; MiPay staff see the whole tree from the platform root. |
| shared/kyc | `mp-onboard-child` | OnboardChild | projects/shared/kyc/onboard-child | compact = false | onboarded, kycChanged | "Onboard an organization", the same in every portal: one five-step KYC wizard. The org type is picked on step 1, then the KYC sections are filled in the browser; nothing exists on the server until "Submit for review", which creates the org, its administrator (UBO Person 1; login name = legal business name + MCC) and… |
| shared/rbac | `mp-org-access` | OrgAccess | projects/shared/rbac/org-access | orgId*: string; canEditCeiling = false; manage = false | - | "Roles & access" for one org (docs/plans/extended-rbac.md ER5): <ul> <li>its role ceiling (read-only for the org itself; editable from an org above it);</li> <li>the roles it may use, and its own roles carved out of them (never wider than their base roles, and switched off if the ceiling narrows);</li> <li>who holds… |
| shared/rbac | `mp-role-ceiling` | RoleCeiling | projects/shared/rbac/role-ceiling | orgId*: string; editable = false | changed | An org's role ceiling (docs/plans/extended-rbac.md X1-X5): the roles its users may hold and the ones it may hand down to organizations it onboards. Editable only by an org above it (or MiPay staff); the server refuses everyone else. |
| shared/users | `mp-user-management` | UserManagement | projects/shared/users/user-management | - | - | GET /api/orgs/me/access */ export interface MyAccess { orgId: string; orgName: string; orgType: string; permissions: { id: string; row: string; verb: string; rowDescription: string }[]; } interface Person { id: string; name: string; username: string; status: string; phone: string; email: string; } /** The permission… |
| shared/web-core | `[mpPerm]` | MpPerm | projects/shared/web-core/perm-directive | mpPerm | - | The self-hiding permission element (RBAC grid, fapi2-design App G) - the one pattern every gated piece of UI follows. The element declares the permission it needs and the structural directive removes it from the DOM unless the signed-in user holds it. Nothing renders "disabled"; it simply is not there. Usage - one v… |
| shared/web-core | `mpc-empty-shell` | EmptyAccessShell | projects/shared/web-core/empty-shell | - | - | The empty shell (RBAC grid, fapi2-design App G): an authenticated user whose org has granted them nothing. The portal chrome (brand, session, theme) still renders around it - this component takes the content area. Every granted right turns into a nav item the moment it exists; "Reload my rights" re-reads /api/orgs/m… |
| shared/web-core | `mpc-perm-inventory` | PermInventory | projects/shared/web-core/permission-inventory | - | - | Permission Inventory (platform QA surface, gated by admin.access:read). Exactly ONE live UI element per permission cell - 136 in total. Each element is the same self-hiding pattern the feature screens use (*mpPerm): it renders on the moment the signed-in principal holds the exact cell, and is removed from the DOM th… |

Types the elements' inputs use (exact source):
```ts
// src/app/design-system/molecules/tabs
export interface TabOption<V extends string = string> {
  value: V;
  label: string;
  disabled?: boolean;
  /** leave the option out (e.g. the viewer lacks its permission) */
  hidden?: boolean;
}

// src/app/design-system/organisms/data-table
export interface DataCol<T = any> {
  key: string;
  label: string;
  type?: string;
  align?: 'left' | 'right';
  width?: string;
  /** plain text to show (and search) instead of row[key] */
  text?: (row: T) => unknown;
  /** what search matches for this column (defaults to the shown text) */
  search?: (row: T) => unknown;
  /** extra class on the cells (e.g. 'small', 'mono', 'muted') */
  cls?: string;
}

/** A cell template: `<ng-template mpCell="key" let-row let-v="value">`. */
@Directive({ selector: 'ng-template[mpCell]' })
export class MpCell {
  readonly name = input.required<string>({ alias: 'mpCell' }

// src/app/design-system/organisms/mp-entity-form
export interface MpFieldSchema {
  key: string;
  label: string;
  kind: 'text' | 'number' | 'select';
  required?: boolean;
  hint?: string;
  placeholder?: string;
  options?: { value: string; label?: string }[];
}

// src/app/design-system/organisms/mp-table
export interface MpColumn {
  key: string;
  label: string;
  type?: 'text' | 'money' | 'status' | 'sim' | 'time' | 'date' | 'num' | 'link';
  align?: 'left' | 'right';
  width?: string;
}

// projects/shared/kyc/kyc-schema-editor
export interface SchemaResponse {
  profile: { code: string; country: string; licenceType: string };
  schema: { code: string; version: number };
  sections: SchemaSection[];
  rules: { code: string; phase: string; validator: string; message: string }[];
  submitRules: { code: string; phase: string; validator: string; message: string }[];
  lists: Record<string, ListItem[]>;
  /** Profile tunables, e.g. uboThresholdPct (beneficial-owner threshold). */
  params?: Record<string, unknown>;
}
```
Shared helpers: `refusal(e, KYC_HINTS)` / `readProblem(e)` from `../../../kyc/kyc-api` turn a refused call into a message; `MpPerm` (`*mpPerm`) from `../../../web-core/perm-directive`.

## Permission cells (choose only from these)

- **admin**: `admin.access:administer`, `admin.access:read`, `admin.aml_alert:read`, `admin.api_client:administer`, `admin.api_client:create`, `admin.api_client:read`, `admin.bootstrap:administer`, `admin.config:modify`, `admin.config:read`, `admin.connector:administer`, `admin.connector:execute`, `admin.connector:modify`, `admin.connector:read`, `admin.dsar:create`, `admin.dsar:export`, `admin.dsar:read`, `admin.platform_ops:administer`, `admin.platform_ops:execute`, `admin.platform_ops:read`, `admin.sar:create`, `admin.sar:read`, `admin.sar:submit`, `admin.simulation:read`, `admin.simulation:simulate`
- **kyc**: `kyc.application:delete`, `kyc.application:modify`, `kyc.application:read`, `kyc.application:submit`, `kyc.form:read`, `kyc.task:authorize`, `kyc.task:read`
- **lender**: `lender.advance:authorize`, `lender.advance:read`, `lender.assessment:execute`, `lender.assessment:read`, `lender.collection:execute`, `lender.collection:read`, `lender.portfolio:execute`, `lender.portfolio:read`
- **merchant**: `merchant.account:read`, `merchant.advance:authorize`, `merchant.advance:create`, `merchant.advance:delete`, `merchant.advance:read`, `merchant.advance:simulate`, `merchant.advance:submit`, `merchant.inventory:create`, `merchant.inventory:execute`, `merchant.inventory:modify`, `merchant.inventory:read`, `merchant.invoice:execute`, `merchant.invoice:read`, `merchant.khata:create`, `merchant.khata:execute`, `merchant.khata:read`, `merchant.order:create`, `merchant.order:read`, `merchant.payment:execute`, `merchant.payment:read`, `merchant.payment:simulate`, `merchant.statement:execute`, `merchant.statement:export`, `merchant.statement:read`, `merchant.tab:create`, `merchant.tab:execute`, `merchant.tab:read`, `merchant.withdrawal:execute`, `merchant.withdrawal:read`
- **ops**: `ops.audit:read`, `ops.bank_account_approval:authorize`, `ops.bank_account_approval:read`, `ops.complaint:create`, `ops.complaint:modify`, `ops.complaint:read`, `ops.dispute:create`, `ops.dispute:modify`, `ops.dispute:read`, `ops.incident:create`, `ops.incident:modify`, `ops.incident:read`, `ops.kyc_check:create`, `ops.kyc_check:read`, `ops.merchant_approval:authorize`, `ops.merchant_approval:read`, `ops.monitoring:execute`, `ops.onboarding_request:authorize`, `ops.onboarding_request:read`, `ops.recon:execute`, `ops.recon:modify`, `ops.recon:read`, `ops.report:execute`, `ops.report:read`, `ops.supplier_approval:authorize`, `ops.supplier_approval:read`, `ops.suspension:administer`, `ops.suspension:read`
- **org**: `org.onboarding:authorize`, `org.onboarding:create`, `org.onboarding:read`, `org.rights:administer`, `org.rights:read`, `org.suspension:administer`, `org.tree:read`, `org.user:administer`, `org.user:create`, `org.user:delete`, `org.user:read`
- **rbac**: `rbac.assignment:administer`, `rbac.assignment:authorize`, `rbac.assignment:create`, `rbac.assignment:read`, `rbac.audit:read`, `rbac.grant:administer`, `rbac.grant:create`, `rbac.grant:read`, `rbac.review:administer`, `rbac.review:create`, `rbac.review:read`, `rbac.role:create`, `rbac.role:delete`, `rbac.role:modify`, `rbac.role:read`, `rbac.user:read`
- **supplier**: `supplier.account:read`, `supplier.bank_account:delete`, `supplier.bank_account:modify`, `supplier.bank_account:read`, `supplier.catalogue:create`, `supplier.catalogue:delete`, `supplier.catalogue:read`, `supplier.invoice:create`, `supplier.invoice:read`, `supplier.order:read`, `supplier.statement:execute`, `supplier.statement:export`, `supplier.statement:read`

## Reference widget: kyc.tasks (complete, passing every check - imitate its structure)

=== FILE: frontend/projects/shared/widgets/kyc/tasks/tasks.widget.ts ===
/**
 * KYC review queue (kyc.tasks): the open KYC review tasks the signed-in user may act on, and an
 * approve / reject decision with a note. Picking a task publishes its org (page selection
 * `orgId`), so a KYC application widget on the same page shows that organization.
 * Built with the mipay-aruns-ui skill from GET /api/kyc/tasks and POST /api/kyc/tasks/{taskId}/decision.
 */
import { defineWidget, span } from '../../core/manifest';
import { KYC_TASKS_DECIDE, KYC_TASKS_DECIDE_REQUEST, KYC_TASKS_LIST } from './tasks.contract';

export interface KycTasksConfig {
  /** Rows per page in the queue. */
  pageSize: number;
}

export default defineWidget<KycTasksConfig>({
  id: 'kyc.tasks',
  version: '1.0.0',
  label: 'KYC review queue',
  description: 'Open KYC review tasks for the signed-in reviewer, with an approve / reject decision and a note.',
  category: 'Onboarding',
  contexts: ['PLATFORM', 'MANUFACTURER', 'DISTRIBUTOR', 'SUPPLIER'],
  requires: ['kyc.task:read'],
  actions: [
    { id: 'decide', label: 'Approve or reject', cell: 'kyc.task:authorize', command: 'kyc.tasks.decide' },
  ],
  sources: ['kyc.tasks.list'],
  commands: ['kyc.tasks.decide'],
  operations: ['view', 'approve'],
  commandOperations: { 'kyc.tasks.decide': 'approve' },
  contracts: {
    'kyc.tasks.list': KYC_TASKS_LIST,
    'kyc.tasks.decide': KYC_TASKS_DECIDE,
  },
  requests: { 'kyc.tasks.decide': KYC_TASKS_DECIDE_REQUEST },
  publishes: ['orgId'],
  config: {
    pageSize: { type: 'number', default: 10, min: 5, max: 50, label: 'Rows per page' },
  },
  size: { xs: span.xs(4), sm: span.sm(8), md: span.md(6, 12), lg: span.lg(4, 5, 12) },
  hideOn: ['forbidden'],
  load: () => import('./tasks.component').then((m) => m.KycTasksWidget),
});
=== END FILE ===
=== FILE: frontend/projects/shared/widgets/kyc/tasks/tasks.contract.ts ===
/**
 * Response contracts: what the server must send. The port checks every answer against these and
 * the harness shows any mismatch. Regenerate after a backend change (widgets:contract).
 */
import { s, type Infer } from '../../core/shape';

// <contract kyc.tasks.list>
/** GET /api/kyc/tasks → List<Dtos.KycTaskRow> (KycController#inbox, kyc.task:read). Generated by widgets:contract. */
export const KYC_TASKS_LIST = s.array(s.object('KycTaskRow', {
  id: s.nullable(s.string()),
  orgId: s.nullable(s.string()),
  orgName: s.nullable(s.string()),
  kind: s.nullable(s.string()),
  stageKey: s.nullable(s.string()),
  stageSeq: s.integer(),
  actorRule: s.nullable(s.string()),
  applicationStatus: s.nullable(s.string()),
  createdAt: s.nullable(s.dateTime()),
  openNote: s.nullable(s.string()),
}));
export type KycTasksList = Infer<typeof KYC_TASKS_LIST>;
// </contract kyc.tasks.list>

// <contract kyc.tasks.decide>
/** POST /api/kyc/tasks/{taskId}/decision → Dtos.KycApplicationView (KycController#decide, kyc.task:authorize). Generated by widgets:contract. */
export const KYC_TASKS_DECIDE = s.object('KycApplicationView', {
  id: s.nullable(s.string()),
  orgId: s.nullable(s.string()),
  orgType: s.nullable(s.string()),
  orgName: s.nullable(s.string()),
  parentOrgId: s.nullable(s.string()),
  treePath: s.nullable(s.array(s.object('OrgPathRow', {
    id: s.nullable(s.string()),
    type: s.nullable(s.string()),
    name: s.nullable(s.string()),
    depth: s.integer(),
  }))),
  depth: s.integer(),
  isSuspended: s.boolean(),
  status: s.nullable(s.string()),
  application: s.nullable(s.any()),
  submittedApplication: s.nullable(s.any()),
  submittedBy: s.nullable(s.string()),
  submittedAt: s.nullable(s.dateTime()),
  decidedBy: s.nullable(s.string()),
  decidedAt: s.nullable(s.dateTime()),
  decisionNote: s.nullable(s.string()),
  version: s.integer(),
  masked: s.boolean(),
  canEdit: s.boolean(),
  canSubmit: s.boolean(),
  canDecide: s.boolean(),
  createdAt: s.nullable(s.dateTime()),
  updatedAt: s.nullable(s.dateTime()),
});
export type KycTasksDecide = Infer<typeof KYC_TASKS_DECIDE>;
// </contract kyc.tasks.decide>

// <request kyc.tasks.decide>
/** POST /api/kyc/tasks/{taskId}/decision sends Dtos.KycDecisionRequestV2 (KycController#decide, kyc.task:authorize). Generated by widgets:contract. */
export const KYC_TASKS_DECIDE_REQUEST = s.object('KycDecisionRequestV2', {
  outcome: s.string(),
  note: s.nullable(s.string()),
});
export type KycTasksDecideRequest = Infer<typeof KYC_TASKS_DECIDE_REQUEST>;
// </request kyc.tasks.decide>
=== END FILE ===
=== FILE: frontend/projects/shared/widgets/kyc/tasks/tasks.component.ts ===
import { ChangeDetectionStrategy, Component, computed, signal } from '@angular/core';
import { MiPayWidget } from '../../core/mipay-widget';
import { DataTable, DataCol } from '../../../../../src/app/design-system/organisms/data-table';
import { ApprovalActions } from '../../../../../src/app/design-system/organisms/approval-actions';
import { FormField } from '../../../../../src/app/design-system/molecules/form-field';
import { MpAlert } from '../../../../../src/app/design-system/molecules/alert';
import { EmptyState } from '../../../../../src/app/design-system/molecules/empty-state';
import { KYC_HINTS, refusal } from '../../../kyc/kyc-api';
import type { KycTasksConfig } from './tasks.widget';
import type { KycTasksDecide, KycTasksDecideRequest, KycTasksList } from './tasks.contract';

type Task = KycTasksList[number];

const when = (iso: string | null) => (iso ? new Date(iso).toLocaleDateString('en-NA', { day: 'numeric', month: 'short', year: 'numeric' }) : '—');
const words = (s: string | null) => (s ? s.charAt(0) + s.slice(1).toLowerCase().replace(/_/g, ' ') : '—');

/**
 * The review queue. Rows come from the caller's inbox (the server decides which tasks the caller
 * may see); the decision strip shows only with kyc.task:authorize (approval-actions' perm).
 */
@Component({
  selector: 'mpw-kyc-tasks',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DataTable, ApprovalActions, FormField, MpAlert, EmptyState],
  template: `
    @if (tasks(); as rows) {
      @if (rows.length) {
        <mp-data-table [cols]="cols()" [rows]="rows" [pageSize]="config.pageSize" placeholder="Search reviews"
                       (rowClick)="pick($event)" />
      } @else {
        <empty-state title="No open reviews" hint="KYC applications waiting for you appear here." />
      }
    } @else {
      <mp-data-table [cols]="cols()" [rows]="null" loading="Loading reviews…" />
    }

    @if (selected(); as t) {
      <section class="decide" data-selected-task>
        <div class="who"><b>{{ t.orgName }}</b> <span class="dim">{{ stage(t) }} · opened {{ opened(t) }}</span></div>
        @if (t.openNote) {<p class="dim">{{ t.openNote }}</p>}
        <mp-form-field key="note" label="Note to the applicant" [value]="note()" (changed)="note.set($event)"
                       hint="Recorded with the decision; required by policy when you reject." />
        <approval-actions data-action="decide" perm="kyc.task:authorize" [status]="t.applicationStatus ?? 'PENDING'"
                          approveLabel="Approve" rejectLabel="Reject" [note]="note()" (decide)="decide(t, $event.approve)" />
      </section>
    }
    @if (flash(); as f) {<mp-alert [kind]="f.kind">{{ f.text }}</mp-alert>}
  `,
  styles: [`
    :host { display: grid; gap: var(--sp-3, 12px); min-width: 0; }
    .decide { display: grid; gap: var(--sp-2, 8px); padding-top: var(--sp-3, 12px); border-top: 1px solid var(--a-border, #2b2b2b); }
    .who { display: flex; flex-wrap: wrap; gap: 4px 10px; align-items: baseline; }
    .dim { color: var(--a-dim, #a3a3a3); font-size: var(--fs-sm, 12.5px); margin: 0; }
  `],
})
export class KycTasksWidget extends MiPayWidget<KycTasksConfig> {
  readonly tasks = signal<KycTasksList | null>(null);
  readonly selected = signal<Task | null>(null);
  readonly note = signal('');
  readonly flash = signal<{ kind: 'ok' | 'bad'; text: string } | null>(null);

  private readonly allCols: DataCol<Task>[] = [
    { key: 'orgName', label: 'Organization', text: (t) => t.orgName ?? '—' },
    { key: 'stage', label: 'Stage', text: (t) => this.stage(t) },
    { key: 'applicationStatus', label: 'Status', text: (t) => words(t.applicationStatus) },
    { key: 'createdAt', label: 'Opened', text: (t) => when(t.createdAt), search: (t) => t.createdAt ?? '' },
  ];
  /** Mobile first: a phone shows who and where it stands; the stage and date appear from tablet up. */
  readonly cols = computed(() => (this.size().tier === 'xs'
    ? this.allCols.filter((c) => c.key === 'orgName' || c.key === 'applicationStatus')
    : this.allCols));

  constructor() {
    super();
    this.load();
  }

  stage(t: Task) { return `${words(t.stageKey)} (step ${t.stageSeq})`; }
  opened(t: Task) { return when(t.createdAt); }

  load() {
    this.data.query<KycTasksList>('kyc.tasks.list').subscribe({
      next: (rows) => {
        this.tasks.set(rows);
        this.state.set(rows.length ? 'ready' : 'empty');
      },
      error: (e: unknown) => this.flash.set({ kind: 'bad', text: refusal(e, KYC_HINTS) }),
    });
  }

  /** Picking a task shows its decision strip and tells the page which org it is. */
  pick(t: Task) {
    this.selected.set(t);
    this.note.set('');
    this.flash.set(null);
    this.publish('orgId', t.orgId);
  }

  decide(t: Task, approve: boolean) {
    // the body is typed by the request contract (KycDecisionRequestV2); the port re-checks it at runtime
    const body: KycTasksDecideRequest = { outcome: approve ? 'APPROVE' : 'REJECT', note: this.note() || null };
    this.data.command<KycTasksDecide>('kyc.tasks.decide', { taskId: t.id, body }).subscribe({
      next: (v) => {
        this.flash.set({ kind: 'ok', text: `${approve ? 'Approved' : 'Rejected'}: ${v.orgName ?? t.orgName} is now ${words(v.status)}.` });
        this.selected.set(null);
        this.load();
      },
      error: (e: unknown) => this.flash.set({ kind: 'bad', text: refusal(e, KYC_HINTS) }),
    });
  }
}
=== END FILE ===
=== FILE: frontend/projects/shared/widgets/kyc/tasks/tasks.fixtures.ts ===
/**
 * Fixture scenarios for kyc.tasks (shapes: KycTaskRow, KycApplicationView - see tasks.contract.ts).
 */
import { defineFixtures, fail } from '../../core/fixtures';

const TASKS = [
  { id: 'task-101', orgId: 'org-sup-1', orgName: 'Kalahari Supplies', kind: 'KYC_REVIEW', stageKey: 'COMPLIANCE_REVIEW',
    stageSeq: 2, actorRule: 'PLATFORM_FUNCTION:kyc_reviewer', applicationStatus: 'IN_REVIEW',
    createdAt: '2026-11-14T08:12:00Z', openNote: 'Bank letter uploaded after the first return.' },
  { id: 'task-102', orgId: 'org-dist-7', orgName: 'Namib Distribution', kind: 'KYC_REVIEW', stageKey: 'ENTRY_CHECK',
    stageSeq: 1, actorRule: 'ORG_ADMIN:other', applicationStatus: 'ENTRY_CHECK',
    createdAt: '2026-11-13T15:40:00Z', openNote: null },
  { id: 'task-103', orgId: 'org-mfg-2', orgName: 'Swakop Foods Manufacturing', kind: 'KYC_REVIEW', stageKey: 'COMPLIANCE_REVIEW',
    stageSeq: 2, actorRule: 'PLATFORM_FUNCTION:kyc_reviewer', applicationStatus: 'IN_REVIEW',
    createdAt: '2026-11-12T10:05:00Z', openNote: 'UBO declared at 24% - check for a controller.' },
];

/** The application as the decision answers it (KycApplicationView). */
const decided = (outcome: string, orgId: string, orgName: string) => ({
  id: `kyc-${orgId}`, orgId, orgType: 'SUPPLIER', orgName, parentOrgId: 'org-dist-7',
  treePath: [{ id: 'org-root', type: 'PLATFORM', name: 'MiPay', depth: 0 }, { id: orgId, type: 'SUPPLIER', name: orgName, depth: 1 }],
  depth: 1, isSuspended: false, status: outcome === 'APPROVE' ? 'APPROVED' : 'REJECTED',
  application: null, submittedApplication: { businessProfile: { legalName: orgName } },
  submittedBy: 'user-ndapewa', submittedAt: '2026-11-14T08:00:00Z', decidedBy: 'user-reviewer',
  decidedAt: '2026-11-15T09:30:00Z', decisionNote: null, version: 5, masked: false,
  canEdit: false, canSubmit: false, canDecide: false, createdAt: '2026-11-10T08:00:00Z', updatedAt: '2026-11-15T09:30:00Z',
});

const LONG = 'Oshakati Regional Wholesale & Cold-Chain Distribution Cooperative (Proprietary) Limited';

export default defineFixtures({
  default: {
    'kyc.tasks.list': TASKS,
    'kyc.tasks.decide': (p) => {
      const t = TASKS.find((x) => x.id === p['taskId']) ?? TASKS[0];
      return decided(String((p['body'] as { outcome?: string } | undefined)?.outcome ?? 'APPROVE'), t.orgId, t.orgName);
    },
  },
  empty: { 'kyc.tasks.list': [] },
  error: { 'kyc.tasks.list': fail(500, { title: 'Server error', detail: 'KYC inbox unavailable' }) },
  longText: { 'kyc.tasks.list': TASKS.map((t) => ({ ...t, orgName: LONG, openNote: `${LONG} - ${t.openNote ?? ''}` })),
    'kyc.tasks.decide': decided('APPROVE', 'org-sup-1', LONG) },
  forbidden: { 'kyc.tasks.list': fail(403, { title: 'Forbidden', detail: 'kyc.task:read' }) },
}, { expect: { forbidden: 'forbidden' } });
=== END FILE ===
=== FILE: frontend/projects/shared/widgets/kyc/tasks/tasks.spec.ts ===
import { renderWidget } from '../../core/testing';
import def from './tasks.widget';
import fixtures from './tasks.fixtures';

const READ = ['kyc.task:read'];
const ALL = [...READ, 'kyc.task:authorize'];

/** Open a task by clicking its row (the table's rowClick). */
async function pick(w: Awaited<ReturnType<typeof renderWidget>>, name: string) {
  const row = w.queryAll('mp-data-table tbody tr').find((r) => (r.textContent ?? '').includes(name));
  if (!row) throw new Error(`no row for ${name}`);
  row.click();
  await w.settle();
}

describe('kyc.tasks', () => {
  it('reads the caller’s inbox (no params) and lists the tasks', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    expect(w.callList()).toEqual([['kyc.tasks.list', {}]]);
    expect(w.state()).toBe('ready');
    expect(w.text('mp-data-table')).toContain('Kalahari Supplies');
    expect(w.text('mp-data-table')).toContain('Compliance review (step 2)');
  });

  it('shows who and where it stands on a phone; stage and date from tablet up', async () => {
    const phone = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, tier: 'xs' });
    expect(phone.queryAll('mp-data-table th').map((h) => h.textContent!.trim())).toEqual(['Organization', 'Status']);
    const laptop = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ, tier: 'md' });
    expect(laptop.queryAll('mp-data-table th').length).toBe(4);
  });

  it('picking a task publishes its org to the page and opens the decision', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: ALL });
    await pick(w, 'Namib Distribution');
    expect(w.selection.all()['orgId']).toBe('org-dist-7');
    expect(w.text('[data-selected-task]')).toContain('Namib Distribution');
  });

  it('shows the decision only with kyc.task:authorize, and the moment it is granted', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ });
    await pick(w, 'Kalahari Supplies');
    expect(w.queryAll('[data-action=decide] mp-button').length).toBe(0);
    await w.grant('kyc.task:authorize');
    expect(w.queryAll('[data-action=decide] mp-button').length).toBe(2);
  });

  it('sends the decision with the task id, outcome and note, then reloads the queue', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: ALL });
    await pick(w, 'Kalahari Supplies');
    const note = w.query<HTMLInputElement>('[data-selected-task] input')!;
    note.value = 'Bank letter verified';
    note.dispatchEvent(new Event('input'));
    await w.settle();
    const approve = w.queryAll('[data-action=decide] mp-button button').pop()!;
    approve.click();
    await w.settle();
    const c = w.lastCommand()!;
    expect(c.name).toBe('kyc.tasks.decide');
    expect(c.params).toEqual({ taskId: 'task-101', body: { outcome: 'APPROVE', note: 'Bank letter verified' } });
    expect(w.text('mp-alert')).toContain('Approved: Kalahari Supplies is now Approved');
    expect(w.callList().filter(([n]) => n === 'kyc.tasks.list').length).toBe(2);
  });

  it('is empty, with the empty state, when nothing waits', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', scenario: 'empty', cells: READ });
    expect(w.state()).toBe('empty');
    expect(w.text('empty-state')).toContain('No open reviews');
  });

  it('matches its contract with the fixtures (no self-check findings)', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: ALL });
    await pick(w, 'Kalahari Supplies');
    w.queryAll('[data-action=decide] mp-button button').pop()!.click();
    await w.settle();
    expect(w.calls().every((c) => c.contract?.length === 0)).toBe(true);
    expect(w.query('[data-diagnostics]')).toBeNull();
  });

  it('self-diagnoses an inbox answer that breaks the contract', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: READ,
      override: { 'kyc.tasks.list': [{ id: 'task-9', orgName: 'Etosha Traders', stageSeq: 'two' }] } });
    const panel = w.text('[data-diagnostics]');
    expect(panel).toContain('kyc.tasks.list');
    expect(panel).toContain('$[0].stageSeq expected integer, got "two" (string)');
    expect(panel).toContain('$[0].orgId expected string | null, got nothing (field missing)');
  });

  it('hides itself on 403 and is not created without kyc.task:read', async () => {
    const forbidden = await renderWidget(def, fixtures, { portal: 'PLATFORM', scenario: 'forbidden', cells: READ });
    expect(forbidden.hidden()).toBe(true);
  });

  it('is not created without kyc.task:read', async () => {
    const none = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: [] });
    expect(none.state()).toBe('denied');
    expect(none.calls().length).toBe(0);
  });

  it('checks what it sends: the decision body matches the request contract', async () => {
    const w = await renderWidget(def, fixtures, { portal: 'PLATFORM', cells: ALL });
    await pick(w, 'Kalahari Supplies');
    w.queryAll('[data-action=decide] mp-button button').pop()!.click();
    await w.settle();
    expect(w.lastCommand()!.request).toEqual([]);
    expect(w.query('[data-diagnostics]')).toBeNull();
  });
});
=== END FILE ===
=== DATA-SOURCES ===
// review queue (KycController#inbox, #decide)
  'kyc.tasks.list':           { '*': { method: 'GET',  path: '/api/kyc/tasks' } },
  'kyc.tasks.decide':         { '*': { method: 'POST', path: '/api/kyc/tasks/{taskId}/decision' } },
  // <widget kyc.orgs>
  'kyc.orgs.list': { '*': { method: 'GET', path: '/api/kyc/orgs', query: ['status', 'suspended'] } },
  // </widget kyc.orgs>

  // ---------------- Organization users (OrgController / ActivationController) ----------------
  // <widget users.organization-users>
  'users.organization.list': { '*': { method: 'GET', path: '/api/orgs/me/users' } },
  'users.organization.create': { '*': { method: 'POST', path: '/api/orgs/me/users' } },
  'users.organization.setup-link': { '*': { method: 'POST', path: '/api/orgs/users/{id}/resend-activation' } },
  'users.organization.deactivate': { '*': { method: 'POST', path: '/api/orgs/users/{id}/deactivate' } },
  // </widget users.organization-users>

  // ---------------- New role by widget (UiWidgetController / PlatformRbacController / OrgAccessController)
  // PLATFORM builds standard roles; the org portals build org-defined roles for the org in context.
  // <widget rbac.new-role>
  'rbac.new-role.widgets': {
    PLATFORM: { method: 'GET', path: '/api/ui/widgets', query: ['portal'] },
    '*': { method: 'GET', path: '/api/orgs/{id}/ui/widgets', query: ['portal'] },
  },
  'rbac.new-role.roles': {
    PLATFORM: { method: 'GET', path: '/api/admin/rbac/roles' },
    '*': { method: 'GET', path: '/api/orgs/{id}/roles' },
  },
  'rbac.new-role.create': {
    PLATFORM: { method: 'POST', path: '/api/admin/rbac/roles' },
    '*': { method: 'POST', path: '/api/orgs/{id}/roles' },
  },
  // </widget rbac.new-role>
=== END DATA-SOURCES ===

---

## My requirement

(write what the widget should show and let the user do, and the endpoints if you know them)
