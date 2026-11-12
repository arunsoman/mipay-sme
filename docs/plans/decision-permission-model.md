# D-9 · Why "authorize" needs a new permission set (the frozen-proposal model)

Draft 2026-11-12 · extends `org-access-model.md` (OA6 will bind the amount bands)
Companion question pack: audit scenarios S1–S15 (`uat-evidence`), the S15 collapse:
*an approval that carries an edit is a modify, a create and a destroy wearing one verb.*

---

## 1 · The cast — who holds exactly what

The workflow is a **supplier advance offer → review → funding**, the standard
maker-checker loop behind `merchant.advance` / `lender.advance:authorize`.

| Player | Role in the workflow | Cells held today |
|---|---|---|
| **P1 · Maker** (distributor admin) | creates and owns the offer | `read, create, modify, delete, submit` on `merchant.advance` |
| **P2 · Checker** (lender officer / TPTS ops) | reviews and decides | `read, authorize` on `lender.advance` |
| **P3 · Auditor** (compliance) | watches, proves | `read, export` on `ops.audit` |

Nobody else exists. P1 may never decide; P2 may never author. That is the whole design.

## 2 · The workflow, step by step

1. **09:00** P1 creates the offer: **N$120,000 @ 11.5 %, tenor 60 days**. (`create`)
2. **09:02** P1 submits it for review. (`submit`) — from here the offer is *awaiting one decision*.
3. **11:00** P2 opens the decision screen. **The tenor should be 90 days** — a typo by P1.
4. P2 must act. What *can* P2 do with the cells they hold?

Every step before 4 is clean. Everything after 4 is broken, and the next section shows it
is **unfixable by any redistribution of the current verbs**.

## 3 · Why the present permissions cannot resolve it

Try to repair step 4 with each verb that exists today:

| Repair attempt | Outcome | Why it fails |
|---|---|---|
| P2 edits inside the decision screen | S15 hole: one event, three acts | The decision endpoint accepts business fields; no cell and no SoD rule inspects the payload. RBAC checks `(resource, verb)` per endpoint — the edit rides inside `authorize`. **Invisible to RBAC, SoD and audit at once.** |
| Require P2 to hold `create`/`modify`, then let them edit | P2 becomes the author of the object they are about to decide | Two branches, both bad: (a) holder *also* has `authorize` → `obj-maker-not-checker` fires at their own decision → **permanent deadlock** for the legitimate fixer; (b) the exception "approvers may hold author verbs" is written → the checker boundary **dissolves systemically**. |
| P2 presses reject (their only other decision outcome) | `reject` is **terminal** (TC-04: reject terminates) | A one-character workflow correction kills the record: no iterate, no history trail, and the *hole survives* — on the recreated request the next checker can edit again. Frozen-ness by habit, not by permission. |
| P1 withdraws (`delete`) and re-creates | Trail lost; hole persists | Same as above, plus the audit now shows two unrelated offers instead of one corrected one. |
| Add a third player (P3') to whom P2 *hands the object* | Bureaucracy grows; hole survives | Whoever the eventual decider is, their endpoint still accepts edits. Moving the role does not move the property of the endpoint. |
| Rely on SoD | SoD compares `(resource, verb)` pairs across users | It was designed for two people holding two verbs. It has no notion of *version*, so merging author+decision inside one endpoint never alarms it. |
| Rely on audit | Audit logs the endpoint's verb | One event: `lender.advance:authorize (fund)`. Whether an edit happened is unreadable — by construction. |

**The structural verdict.** A cell is `(resource, verb)`. SoD compares `(resource, verb)`.
Audit logs `(resource, verb)`. **Nowhere does the permission model carry "the content the
decision refers to".** That binding does not have a slot — which is why *no combination of
the ten verbs can close the hole*: the missing thing is not a verb, it is a constraint the
vocabulary cannot carry: **a decision must reference the exact version it decided.**

## 4 · The impossible case (why new permissions are *required*, not just nice)

Define a successful workflow as one that admits all four properties **simultaneously**:

- **(F1)** a correction after submission is possible *without re-creating the offer from scratch*;
- **(F2)** the correcting person can still be the one who decides (no extra player);
- **(F3)** author ≠ decider on the version being decided (`obj-maker-not-checker` holds);
- **(F4)** the audit states on which content the decision was made.

Claim: with the current verb set there exists **no assignment** of verbs to P1/P2 that
satisfies F1–F4. Proof by exhaustion — the correction must be performed by *someone*:

| Who corrects | With which current verbs | F1 | F2 | F3 | F4 |
|---|---|---|---|---|---|
| P2 (checker) | edit payload inside `authorize` | ✗ (endpoint accepts it, but it *is* an edit) | ✓ | ✓ (verb log says so) | ✗ (no version in audit) |
| P2 (checker) | via newly-required `modify` | ✗ deadlocks their decision | ✓ | ✗ or dissolves | ✗ |
| P2 (checker) | via `return`… | — | — | — | **no such verb exists** |
| P1 (maker) | `modify` after submit | ✓ | — | ✓ | ✗ (decision endpoint still accepts edits — hole open for P2) |
| P2 | `reject` (terminal) + P1 re-creates | ✗ (no iterate, trail lost) | ✗ | ✓ | ✗ (two offers, one truth) |
| P3' (new decider) | any | hole moves to P3' | ✗ | — | ✗ |

Every row violates at least one property. The workflow "reviewer asks for a correction →
maker amends → **same trail**, second decision" is **impossible** under the current set —
not because a permission is missing to *allow* it, but because:

1. the only soft-correction instrument P2 holds is an `authorize` with free-form payload;
2. `reject` is terminal, so iteration does not exist;
3. `modify` by the checker detonates SoD at the decision;
4. and no verb, rule or log binds a decision to a content version.

## 5 · The new permission set that resolves it

Three additions — one genuinely new permission, two new *checkable properties* on the
existing `authorize` cell:

### D-9.1 · Decision-must-bind-snapshot *(rule, enforced like an SoD rule)*
At `submit` the proposal is frozen: the request row gains `snapshot_hash`
(`version` columns already exist). A decision endpoint accepts **only**
`{decision, note}` — any business field in the body is refused with
`APPROVE_BODY_FIELDS_REFUSED`. The decision audit row records `decided: <outcome>@hash`.
The grid attests the rule per `authorize` row, and the role pack shows it — like SoD sets.

### D-9.2 · New decision outcome: **`return`** *(the new permission)*
`authorize`'s effect set becomes **approve | reject | return**.

- `return` → state `RETURNED_TO_MAKER` + note; **no change to the object**; the pending
  decision dies. The maker amends (their own `modify`), resubmits → `submission_no++`,
  fresh snapshot, fresh pending decision.
- `return` rides the same `authorize` cell (the checker class decides it) but is a **distinct
  recorded effect** — the role pack can now say "returned 4 offers, amended 0, deactivated 2".
- Honest pressure outlet: the approver finally *has* a legitimate path for "fix this", so
  nobody needs to "just fix it in the screen".

### D-9.3 · Supersession — new conflict: **`ALREADY_SUPERSEDED`**
A decision arriving for a snapshot that a resubmit has replaced is refused (the
`RIGHTS_STALE` analogue for content, not just entitlements). Old pending decisions void;
audit shows which snapshot each attempt named.

### Why this satisfies F1–F4 (the workflow replayed)

> **11:00** P2 presses **return**: "tenor should be 90 d" → request `RETURNED_TO_MAKER`.
> **11:40** P1 amends tenor to 90 d (their `modify`), resubmits (`submit`) → snapshot v2.
> **12:10** P2 decides v2: **approve@v2** → funding offered per the frozen terms.

- **F1** ✓ correction happened, offer never re-created from scratch (trail continuous).
- **F2** ✓ P2 still the decider; no third player invented.
- **F3** ✓ author of v2 = P1; decider = P2 — object-SoD holds with *no exceptions*.
- **F4** ✓ audit says `returned@v1`, `modified: v1→v2 by P1`, `decided: approve@v2`.

The typo correction that was **impossible** in §4 is now a first-class workflow — and the
malicious variant (approver silently amending) is structurally impossible: the only edit
path is the maker's `modify`, always demandable by cell and always visible in audit.

## 6 · Where it lands in code

| Piece | Location |
|---|---|
| Snapshot binding | request rows gain `snapshot_hash`;
  `OrgOnboardingService.decide` / task decide / advance funding bodies shrink to `{decision, note}` |
| New outcome | `RETURNED_TO_MAKER` state + `return` branch in `decide`; `ALREADY_SUPERSEDED` conflict |
| Grid + role pack | rule `decision_binds_snapshot` attested per `authorize` row; effect set
  of `authorize` shown as approve/reject/return |
| Tests | the 6-case matrix: body-fields refusal, return→amend→v2, stale-decision supersession,
  maker cannot edit a pinned pending version, direct-approver modify → 403, effect-set audit |

Boundaries: D-9 fixes the *meaning* of a decision. Amount bands / countersign of large
money (OA6) stays separate; the cooling-off + `execute` separation (Option B) composes on top.
