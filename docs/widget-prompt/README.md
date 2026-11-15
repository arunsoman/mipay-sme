# Build a MiPay widget with any AI chat

`mipay-widget-prompt.md` (next to this file) is a self-contained prompt for **any** LLM (ChatGPT, Claude, Gemini, a local
model). It carries the rules, the exact widget API, every reusable UI element with its inputs and defaults, a complete
reference widget, a strict output format and the **API catalogue**: every endpoint the backend serves (≈240), with its
permission cell, what it does, which org it acts on, its parameters, and its request and response shapes.

The AI reuses an existing endpoint and permission when one fits. When none does, it proposes a new permission cell and
endpoint, defines the shapes this UI needs, and also returns a **backend controller stub** (`@Perm` on every handler,
answering 501) plus notes for the backend developer.

## 1. Get a prompt

The generic prompt is `docs/widget-prompt/mipay-widget-prompt.md`. Better: a prompt with your endpoints already read from
the backend (permission, parameters, request and response shapes), in `frontend/`:

    npm run widgets:prompt -- GET /api/kyc/orgs POST /api/kyc/tasks/{taskId}/decision --out my-prompt.md

## 2. Ask the AI

Paste the whole prompt into a chat and write your requirement under "My requirement" at the end. The AI answers with an
operations sheet (what each read and write receives and sends; it asks you if a shape is missing) and then all files in
`=== FILE: … ===` blocks. Save the answer as `answer.md`.

## 3. Apply and verify (in `frontend/`)

    npm run widgets:apply -- answer.md

It writes the files (only inside one widget folder, plus its block in `data-sources.ts`). A backend stub is saved as a
proposal in `frontend/widget-out/<id>/backend/` - it is never added to the backend unless you ask:

    npm run widgets:apply -- answer.md --with-backend   # also install the stub (a NEW controller only), compile, validate

Stubs are refused if they would overwrite existing backend code, declare a public endpoint, miss `@Perm`, sit outside
`com.mipay.sme.web` or re-serve an endpoint that already exists. Then it runs:

| step | proves |
|---|---|
| register in the build | the manifest loads; the widget is in the registry |
| checks | contracts both ways, fixtures match them, only existing UI elements, no raw controls, actions gated, every tier sized; every permission is enforced by the backend (or proposed by the stub) |
| contracts describe the backend stub exactly | (NEW endpoints) the widget's request and response contracts are the stub's records, field by field |
| backend stub compiles | (`--with-backend`) the backend builds with the stub |
| unit + FAPI conformance tests | the widget's own tests; only same-origin `/api`, `X-CSRF` on writes, never `Authorization`, 403 hides |
| layout at every tier × scenario | no overflow on phone, tablet, laptop, desktop, in every fixture scenario |
| screenshots | `frontend/widget-out/<id>/<id>.{xs,sm,md,lg,all-tiers}.png` |
| registry server would accept it | the backend's registry validation against the real `@Perm` endpoints; *pending* while the stub is only a proposal |

Green: `frontend/widget-out/<id>/REPORT.md` and `registration.<PORTAL>.json` (the exact body `widgets:register` posts);
with a stub also `backend/<Name>Controller.java` and `BACKEND-NOTES.md` (what the backend must implement). A widget whose
endpoints are still a proposal is built and tested on fixtures and registers once the backend is implemented.
Red: paste `frontend/widget-out/<id>/REPAIR.md` back into the same chat; the AI answers with only the changed files; apply
again. Repeat until green.

**Read an AI answer before applying it**: its code runs in your local tests.

## 4. Look at it, and against the real API

    npm run workbench                       # http://localhost:4300/w/<id> - fixtures, every tier
    WB_SERVICE=local-supplier npm run workbench:live   # the real API through the gateway (api-services.json)

## Keep the prompt current

Regenerate after changing the widget core, the design system or the reference widget:

    npm run widgets:prompt -- --out ../docs/widget-prompt/mipay-widget-prompt.md
