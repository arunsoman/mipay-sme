---
name: mipay-aruns-ui
description: Builds a complete, registerable MiPay UI widget end to end from a UI requirement - agreeing with the user, operation by operation (load, save draft, submit, approve…), which endpoint each uses, the data shape it receives and the data shape it sends; generating request and response contracts from the backend's Java records; mapping the data sources; composing the UI only from the existing design-system atoms / molecules / organisms; writing fixtures and tests - so the widget opens in the test harness out of the box, connects to the real FAPI-protected API through the gateway with one click, and diagnoses any mismatch between what it expects and what the server sends (or what it sends and what the server expects) right in the harness. Use whenever the user asks for a new MiPay screen, widget, panel, card, table, list, queue, form or editor for any portal, gives an /api endpoint to build a UI for, or says "mipay-aruns-ui".
---

# mipay-aruns-ui: from a UI requirement to a finished, self-checking MiPay widget

You turn "show X and let the user do Y" into a widget under
`frontend/projects/shared/widgets/<area>/<name>/` that:

- is **registerable**: it's in the generated registry, and the registry server accepts it because
  each endpoint's @Perm cell is declared;
- **works in the test harness out of the box** on fixtures, at all four grid tiers:
  `npm run workbench` → `http://localhost:4300/w/<id>`;
- **connects to the real API** with no FAPI work in the widget: the harness signs in through the
  gateway (BFF), which runs FAPI 2.0, and the widget only ever calls `this.data`;
- **diagnoses itself**:
  - every answer is checked against its **response contract**;
  - every body it sends is checked against its **request contract**;
  - mismatches show inside the widget in the harness, and in the console in portals;
- **reuses the design system**: it never defines a component, directive or pipe, and never uses a
  raw `<table>`, `<button>`, `<input>` or `<select>`.

The finished references to imitate:
- `projects/shared/widgets/kyc/application/`: a multi-step editor (load, then save draft, submit
  and withdraw). It reuses a shared component, and has hand-written contracts where the server is
  untyped.
- `projects/shared/widgets/kyc/tasks/`: a queue with a decision. Built entirely from catalogue
  organisms, with every contract generated.

The design is in `docs/plans/portal-builder.md`. All commands run in `frontend/`
(`/home/arun/sme/frontend`).

## 1. Understand the requirement as operations

Break the UI into **operations**: everything it reads and everything it writes. A simple list has
one read. A complex UI has several. For example, a KYC-like editor has:

| # | operation | kind | trigger | then |
|---|---|---|---|---|
| 1 | load the record | read | open | show the form |
| 2 | load the form definition | read | open | build the fields |
| 3 | load a dependent list | read | pick a parent value | fill the child list |
| 4 | save as draft | write | "Save draft" (and on every "Continue") | keep editing; send the version back |
| 5 | submit | write | "Submit" | save first (op 4), then submit; the form locks |
| 6 | withdraw | write | "Withdraw" | back to draft or closed |

For each operation you need:
- **the endpoint**: `METHOD /api/path`. **Never invent one.**
- **the response shape**: what the UI expects back.
- **the request shape**: what the UI sends (writes, and reads with query params).
- **the permission cell**, which comes from the backend.
- **what happens next**: the UI state after success, after refusal (422, 409…), and after 403.

Pay attention to these in complex UIs:
- **Draft vs submitted**: which copy the form shows (for example `application` vs
  `submittedApplication`), who may still edit (`canEdit`), and when it locks.
- **Optimistic concurrency**: the version the save sends (`expectedVersion`) and the 409 answer
  (`KYC_VERSION_CONFLICT`). It needs a scenario and a message.
- **Chained writes**: "submit" may be "save then submit". These are two operations, two commands
  and two contracts, run in order, and a failure in the second must say the first succeeded.
- **Field-level refusals**: 422 with paths (`ubos[0].dateOfBirth: …`) shown at the field.

## 2. Draft the operations sheet from the backend, then confirm it with the user

For every endpoint the user named, or that you found under
`backend/src/main/java/com/mipay/sme/web/` (confirm candidates; never invent):

```bash
npm run widgets:endpoint -- GET  /api/kyc/tasks
npm run widgets:endpoint -- POST /api/kyc/tasks/{taskId}/decision
```

From each result take the handler, the **permission**, the path and query params, the **request
body type and its shape** (bean validation makes fields required: `@NotNull`, `@NotBlank`,
`@NotEmpty`), the **response type and its shape**, and the untyped parts.

When no endpoint serves an operation, **propose one** instead of stopping:
- a cell: reuse an existing row and verb when the meaning matches, else a new row `<area>.<resource>` with one of the ten verbs;
- the request and response shapes this UI needs (confirmed with the user in the sheet);
- a NEW controller stub `backend/src/main/java/com/mipay/sme/web/<Area><Thing>Controller.java`, `@Perm` on every handler,
  records nested and named as in the contracts, answering 501, plus `BACKEND-NOTES` on what it must do.

The simplest way is the portable answer format (see "Without Claude Code"): write the answer and run
`npm run widgets:apply -- answer.md` - it keeps the stub as a proposal, checks the contracts against it field by field and
marks registration pending; `--with-backend` installs and compiles it, **only when the user asks**.

Stop and report instead of guessing when:
- the permission is `NONE (unannotated)`.

Then **show the user the operations sheet**, one block per operation:

```
2. Approve or reject a task          write · POST /api/kyc/tasks/{taskId}/decision · kyc.task:authorize
   sends    KycDecisionRequestV2 { outcome: string (required), note: string | null }
   receives KycApplicationView   { id, orgId, orgName, status, …, version: integer, canEdit: boolean, … }
   then     reload the queue; 422 → show the refusal; 403 → hide the decision
```

**Ask the user, in one AskUserQuestion round,** to confirm or correct:
1. **The shapes the UI expects to receive.** Where the server is untyped (`Map<String,Object>`,
   `JsonNode`), ask for the shape: fields, types, which may be null, examples. Offer the shape you
   inferred from the frontend's existing types as the recommended option.
2. **The shapes the UI must send**, including bodies the server takes as untyped maps (the KYC
   document on save) and fixed values (`outcome`: which values the UI offers).
3. **The steps of a complex UI**: what is a draft, what submit does (save first?), what locks,
   what each refusal shows.
4. Only if unknown: which portals, and where the org comes from (default
   `selection.orgId` → `context.orgId`).

The user can reply "as drafted". Record the confirmed sheet. It drives everything below and
goes in the final report.

## 3. Choose the UI elements from the catalogue (reuse, never invent)

```bash
npm run widgets:ui
```

| need | element |
|---|---|
| list / table | `mp-data-table` (+ `ng-template mpCell`); on phones show fewer columns via `this.size().tier` |
| a decision on a row | `approval-actions` (pass the authorize cell as `perm`) |
| a form, a field | `mp-entity-form`, `mp-form-field` (`mp-input`, `mp-select` inside) |
| a whole existing KYC / RBAC / users screen | its shared component (`mp-kyc-application`, …) |
| numbers / KPIs | `kpi-row`, `stat-tile`, `currency-amount` |
| status | `status-badge` |
| messages, refusals | `mp-alert` |
| nothing to show | `empty-state` |
| loading | `mp-spinner` (or `rows = null` on `mp-data-table`) |
| buttons | `mp-button` |
| popup editor | `mp-modal` |
| tabs / steps | `mp-tabs` |
| details | `kv-pair`, `mp-card` |

If nothing fits a part of the requirement, **stop and tell the user** which element is missing.
A new atom is a design-system change, not part of a widget.

## 4. Scaffold

```bash
npm run widget:new -- <area>/<name> --label "<Label>" --portals SUPPLIER,PLATFORM
```

Every `TODO(widget)` in the four files must be resolved.

## 5. Contracts: what it receives and what it sends

Name sources and commands `<area>.<thing>.<verb>`. For each operation in the sheet:

```bash
F=projects/shared/widgets/<area>/<name>/<name>.contract.ts
npm run widgets:contract -- GET  /api/kyc/tasks                     --name kyc.tasks.list   --out $F            # receives
npm run widgets:contract -- POST /api/kyc/tasks/{taskId}/decision   --name kyc.tasks.decide --out $F            # receives
npm run widgets:contract -- POST /api/kyc/tasks/{taskId}/decision   --name kyc.tasks.decide --out $F --request  # sends
```

- **Generated blocks** sit between `// <contract …>` / `// <request …>` markers. Re-running the
  command refreshes them; never edit inside the markers.
- **Untyped on the server, or confirmed differently by the user**: write the shape **below** the
  generated blocks with the `s.*` builders, using the shape the user confirmed in step 2.
  `kyc/application/kyc-application.contract.ts` shows both directions (`KYC_SCHEMA_GET`,
  `KYC_APPLICATION_SAVE_REQUEST`).
- A write that reads no body still gets a request contract for what the UI sends
  (`s.object('NoBody', {})`).
- Use the exported types (`KycTasksList`, `KycTasksDecideRequest`, …) in the component. Never
  write a second interface.
- Never loosen a contract to make a test pass. It describes the server.

## 6. Map the data sources

Add one entry per source and command to `projects/shared/widgets/data-sources.ts`, from the
suggestion `widgets:endpoint` printed:
- `'*'` when the URL is the same in every portal;
- path parameter names as the component passes them;
- `query`: every query parameter the component may send (`expectedVersion`, `parent`, …).

## 7. Write the manifest (`<name>.widget.ts`)

Fill in everything:
- `id`, `version: '1.0.0'`, `label`, `description`, `category`, `contexts`;
- `requires`: the read cells. A list when any one is enough, `{ all: [...] }` when the widget
  needs every one;
- `actions`: one per write in the sheet, with its exact cell and command. A chained write
  ("submit" = save + submit) is **one action**, `submit`, whose button sends both commands;
- `sources`, `commands`;
- `contracts`: every source and every command;
- `requests`: every command that sends a body;
- `inputs`, `publishes`;
- `config`: only what a page designer would change;
- `size`: phone first, `xs: span.xs(4)` for tables and forms;
- `hideOn: ['forbidden']`;
- `load`.

## 8. Write the component (`<name>.component.ts`)

- `extends MiPayWidget<Config>`. Read with `this.data.query(name, params)` and write with
  `this.data.command(name, { ...pathParams, ...queryParams, body })`, nothing else.
- Build every body as the request type (`const body: KycTasksDecideRequest = { … }`). The
  compiler then checks it, and the port re-checks it at runtime.
- **Complex UIs**: one method per operation in the sheet, in the sheet's order:
  - chained writes run in sequence; a failure in a later step says the earlier ones succeeded
    ("Saved as a draft, but not ready to submit: …");
  - drafts send the version back (`expectedVersion`), and 409 shows a reload hint;
  - after a write, show the server's answer (it's the new truth: status, `canEdit`, version).
- Gated actions: `*mpPerm="'<cell>'"`, or the organism's `perm` input, **and**
  `data-action="<action id>"`.
- Org: `readonly orgId = this.input<string>('orgId')`. With no org, `this.state.set('empty')`.
- Refusals go to `mp-alert` via `refusal(e, hints)` / `readProblem(e)` from
  `projects/shared/kyc/kyc-api` or the area's equivalent. 422 field paths go next to the field
  when the element supports it.
- Phones: structural switches through `this.size().tier`; styling through `@container`.
- Styles: a few lines, tokens only (`var(--a-…)`, `var(--sp-…)`), never `@media`.

## 9. Fixtures (`<name>.fixtures.ts`)

- Scenarios:
  - required: `default`, `empty`, `error`;
  - `longText` when it shows user-entered text;
  - `forbidden` (`fail(403)`) when it hides on 403;
  - **one per refusal in the sheet**: `invalid` (422 with field paths), `conflict` (409), and a
    locked state (`inReview`).
- Realistic Namibian data (organisations, people, NAD, ISO dates). Every value must **match the
  response contract**; `widgets:check` verifies it.
- Answer every source and every command in `default`. Write answers derive from the request:
  `(p) => ({ ...SAVED, version: v + 1, application: p['body'] })`.
- Pass `expect` for any scenario whose settled state isn't the default.

## 10. Tests (`<name>.spec.ts`)

Use `renderWidget(def, fixtures, { portal, orgId, scenario, cells, selection, override, tier })`.
Write at least:
1. each read calls the right source with the right params (`w.callList()`);
2. it renders the data;
3. each action is hidden without its cell and appears after `await w.grant(cell)`;
4. **each write sends the right params and body** (`w.lastCommand().params`), and for chained
   writes, the order (`w.calls().filter(c => c.kind === 'command')`);
5. **what it sends matches the request contract** (`lastCommand().request` is `[]`);
6. the fixtures match the response contracts (no `[data-diagnostics]`);
7. a drifted answer (`override` with a wrong type) is diagnosed in `[data-diagnostics]`;
8. each refusal scenario shows its message;
9. the phone tier (`tier: 'xs'`) if the widget switches structure on phones.

The FAPI conformance (relative `/api` only, `X-CSRF` on every write, never `Authorization`,
403 hides, 401 → sign in) and the layout suite are generated for every registered widget.

## 11. Register and verify; fix until everything is green

```bash
npm run widgets:gen                      # registry + manifest for the registry server
npm run widgets:check -- <id>            # must end with "<id> - ready"
npm run test:widgets                     # unit + generated FAPI conformance (Vitest)
npm run test:layout -- -g <id>           # every tier × scenario, headless Chrome
```

- Fix the widget, never the checks. Don't weaken a contract, skip a scenario, lower a span
  minimum or delete a test to get green. If a check is wrong, say so.
- Browser tests are headless only. Don't start, stop or restart the user's running apps or the
  live stack. The workbench dev server that `test:layout` starts is fine.
- The contract is the server's truth. When fixtures disagree with it, fix the fixtures.

## 12. Show it against the real API (for the user; no FAPI work needed)

The API services are configured in `projects/widget-workbench/api-services.json`: each has a
portal host and its gateway. The developer adds one there if theirs is missing, and adds
`127.0.0.1 <portalHost>` to `/etc/hosts` once. Then:

```bash
WB_SERVICE=local-supplier npm run workbench:live    # serves https://<portalHost>:4300
```

Open `https://<portalHost>:4300/w/<id>?data=live` and press **Sign in** in the connection bar.
The gateway runs the FAPI 2.0 login; the harness shares the portal's session.
- The widget runs on the real API with the user's real permissions and org (switchable off to
  try other cell sets).
- Each call's ✓/✗ against its contracts shows in the network panel, and any mismatch, received
  or sent, is listed under the widget.

Don't start this yourself unless the user asks. It needs their live stack.

## 13. Report

Give the user:
- **The operations sheet as confirmed**, with what you derived and what the user supplied.
- **Where to see it**: fixtures at `npm run workbench` → `http://localhost:4300/w/<id>`; live as
  in step 12.
- **The final code**: the manifest, the contract file and the component in full; the rest by path.
- **What it needs**: the cells it shows with, each action's cell, and the portals.
- **How to publish**: PR (CI runs the four commands of step 11), then at deploy
  `WIDGET_REGISTRY_TOKEN=… npm run widgets:register -- --portal <P> --bundle <portal>@<sha> --url <backend>/internal/ui/widgets/register`.
- **Anything you stopped on**: a missing endpoint, an unannotated endpoint, a missing design
  element, shapes the user supplied that the server doesn't type.

## Without Claude Code: any AI chat

The same process works with any LLM: `npm run widgets:prompt -- <METHOD> <path> … --out prompt.md` writes a
self-contained prompt; the LLM's answer is applied and fully verified (checks, tests, layout, screenshots, registry
validation, repair loop) with `npm run widgets:apply -- answer.md`. See `docs/widget-prompt/README.md`. When the user
pastes such an answer here, apply it with that command rather than copying files by hand.

## Hard rules

1. **Reuse, never invent.** No new component, directive or pipe in a widget folder; only elements
   from `npm run widgets:ui`; no raw `<table>`, `<button>`, `<input>`, `<select>` or `<textarea>`.
2. **One network path.** Never `HttpClient`, `fetch`, `XMLHttpRequest` or browser storage in a
   widget: only `this.data`. FAPI 2.0 is the gateway's job; the widget never sees a token.
3. **The backend is the truth.** Endpoints, permissions and shapes come from the backend code via
   the tools, or from the user where the server is untyped. Never invent one.
4. **Both directions are contracted.** Every source and command has a response contract; every
   command that sends a body has a request contract; every fixture matches.
5. **Every operation in the sheet is built and tested**, including drafts, chained writes and
   every refusal the sheet names.
6. **Every gated action** has its exact cell and `data-action`.
7. **Mobile first.** A size for every tier, phone first; `@container`, never `@media`.
8. **Done means** `widgets:check` says ready and `test:widgets` and `test:layout -g <id>` pass,
   all run by you, with the outputs reported faithfully.
