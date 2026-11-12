# RBAC role pack for compliance sign-off

Generated 2026-11-12 from the live RBAC service (environment `dev`). Source of truth: `trustplane/config/rbac/grid.yaml`; the endpoints come from the code's own registration.

Version 1 of the grid reproduces today's access one-to-one (decision D9), so shadow mode can prove the two agree. Tightening happens afterwards through the quarterly access review.

**Verbs:** read · create · modify · delete · submit · authorize · execute · export · administer · simulate. A cell `row:verb` exists only if an endpoint uses it.

## Summary

| Role | Kind | Inherits | Cells (with inherited) | Approval to grant | Holders |
|---|---|---|---|---|---|
| `kyc_applicant` | ORG | — | 5 | none | 4 |
| `lender_officer` | PLATFORM | — | 8 | platform | 0 |
| `org_access_admin` | ORG | `org_staff` | 52 | org_admin | 0 |
| `org_admin` | ORG | `org_access_admin`, `org_onboarder`, `org_staff`, `org_user_admin` | 77 | org_admin | 12 |
| `org_onboarder` | ORG | `org_staff` | 53 | org_admin | 0 |
| `org_staff` | ORG | `org_viewer` | 48 | none | 2 |
| `org_user_admin` | ORG | `org_staff` | 54 | org_admin | 0 |
| `org_viewer` | ORG | — | 26 | none | 0 |
| `platform_auditor` | PLATFORM | — | 54 | platform | 0 |
| `platform_break_glass` | PLATFORM | — | 62 | break_glass | 0 |
| `platform_compliance_officer` | PLATFORM | `platform_auditor` | 59 | compliance | 0 |
| `platform_engineer` | PLATFORM | — | 11 | platform | 0 |
| `platform_mlro` | PLATFORM | `platform_auditor` | 58 | compliance | 1 |
| `platform_operations` | PLATFORM | `platform_auditor` | 66 | platform | 0 |
| `platform_ops_reviewer` | PLATFORM | `platform_auditor` | 59 | platform | 1 |
| `platform_senior_approver` | PLATFORM | `platform_auditor` | 56 | compliance | 0 |
| `platform_user_admin` | PLATFORM | — | 26 | platform | 2 |
| `sandbox_tester` | SANDBOX | — | 4 | none | 0 |

## Separation of duty

| Rule | Kind | Applies to | Why |
|---|---|---|---|
| dsd-one-review-stage-per-session | DYNAMIC | `platform_ops_reviewer` × `platform_compliance_officer` | The same login never clears two review stages in one sitting |
| obj-maker-not-checker | OBJECT | `kyc.application:submit` × `kyc.task:authorize` | The person who submitted an application never decides its tasks (KYC workflow engine) |
| obj-no-self-approval-of-money | OBJECT | `merchant.withdrawal:execute` × `ops.bank_account_approval:authorize` | Whoever moves money can't approve the account it goes to |
| ssd-access-admin-not-reviewer | STATIC | `platform_user_admin` × `platform_ops_reviewer`, `platform_compliance_officer`, `platform_mlro`, `platform_senior_approver` | Whoever grants access must not also approve customers |
| ssd-break-glass-separate | STATIC | `platform_break_glass` × `platform_auditor`, `platform_operations`, `platform_ops_reviewer`, `platform_compliance_officer`, `platform_mlro`, `platform_senior_approver`, `platform_user_admin`, `platform_engineer`, `lender_officer` | Emergency access is a separate, monitored identity |
| ssd-mlro-independent | STATIC | `platform_mlro` × `platform_user_admin` | The MLRO stays independent of access administration |
| ssd-two-review-stages | STATIC | `platform_ops_reviewer` × `platform_senior_approver` | Senior approval of high-risk / PIP cases (FIA s.23) must be a different person from the first-line reviewer |

Grid rules checked on every save: no role ticks both `submit` and `authorize` on one row (exemptions below); no platform role both administers and authorizes on customer rows (approving an access request, `rbac.assignment:authorize`, is access administration and is not counted); the `simulate` column is refused in production.

**Same-row exemptions:**

- `merchant.advance`: The merchant requests an advance (submit) and later accepts the lender's offer (authorize); the checker is the lender (lender.advance:authorize)

---

## `kyc_applicant`

KYC applicant admin of a PENDING org: the KYC application screens of that org only (D3)

- **Kind:** ORG (assigned on an org, scope own or subtree)
- **Inherits:** nothing
- **Granting needs:** No second approval (granted by an org_admin of the org, within its ceiling; access reviews catch drift)
- **SoD sets:** none

| Cell | From | Endpoints it covers |
|---|---|---|
| `kyc.application:delete` | own | (declared; endpoints not registered yet) |
| `kyc.application:modify` | own | (declared; endpoints not registered yet) |
| `kyc.application:read` | own | (declared; endpoints not registered yet) |
| `kyc.application:submit` | own | (declared; endpoints not registered yet) |
| `kyc.form:read` | own | (declared; endpoints not registered yet) |

Approved for use: ______________________ (Compliance)    Date: __________

---

## `lender_officer`

MiPay staff operating the lender side for partner lenders

- **Kind:** PLATFORM (scope all)
- **Inherits:** nothing
- **Granting needs:** A second platform_user_admin
- **SoD sets:** ssd-break-glass-separate (STATIC)

| Cell | From | Endpoints it covers |
|---|---|---|
| `lender.advance:authorize` | own | (declared; endpoints not registered yet) |
| `lender.advance:read` | own | (declared; endpoints not registered yet) |
| `lender.assessment:execute` | own | (declared; endpoints not registered yet) |
| `lender.assessment:read` | own | (declared; endpoints not registered yet) |
| `lender.collection:execute` | own | (declared; endpoints not registered yet) |
| `lender.collection:read` | own | (declared; endpoints not registered yet) |
| `lender.portfolio:execute` | own | (declared; endpoints not registered yet) |
| `lender.portfolio:read` | own | (declared; endpoints not registered yet) |

Approved for use: ______________________ (Compliance)    Date: __________

---

## `org_access_admin`

Capability role (D-6): org-defined roles within the org's ceiling (extended-rbac X8-X10)

- **Kind:** ORG (assigned on an org, scope own or subtree)
- **Inherits:** org_staff
- **Granting needs:** A second org_admin of the same org; if none, an org_admin of the parent org; if none, a platform_user_admin
- **SoD sets:** none

| Cell | From | Endpoints it covers |
|---|---|---|
| `kyc.application:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `kyc.form:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `lender.advance:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `lender.assessment:execute` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `lender.assessment:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `lender.collection:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `lender.portfolio:execute` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `lender.portfolio:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.account:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.advance:create` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.advance:delete` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.advance:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.advance:submit` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.inventory:create` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.inventory:execute` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.inventory:modify` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.inventory:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.invoice:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.khata:create` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.khata:execute` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.khata:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.order:create` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.order:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.payment:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.statement:execute` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.statement:export` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.statement:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.tab:create` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.tab:execute` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.tab:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.withdrawal:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `org.onboarding:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `org.rights:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `org.tree:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `org.user:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `rbac.role:create` | own | `POST /rbac/admin/roles` |
| `rbac.role:delete` | own | `DELETE /rbac/admin/roles/{name}` |
| `rbac.role:modify` | own | `PUT /rbac/admin/roles/{name}` |
| `rbac.role:read` | own | `GET /rbac/admin/export/role-pack`<br>`GET /rbac/admin/resources`<br>`GET /rbac/admin/roles`<br>`GET /rbac/admin/roles/{name}` |
| `supplier.account:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `supplier.bank_account:delete` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `supplier.bank_account:modify` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `supplier.bank_account:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `supplier.catalogue:create` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `supplier.catalogue:delete` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `supplier.catalogue:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `supplier.invoice:create` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `supplier.invoice:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `supplier.order:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `supplier.statement:execute` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `supplier.statement:export` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `supplier.statement:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |

Approved for use: ______________________ (Compliance)    Date: __________

---

## `org_admin`

Org administrator (today's ADMIN): money movement, KYC, users, onboarding and the orgs below; the union of the D-6 capability roles

- **Kind:** ORG (assigned on an org, scope own or subtree)
- **Inherits:** org_access_admin, org_onboarder, org_staff, org_user_admin
- **Granting needs:** A second org_admin of the same org; if none, an org_admin of the parent org; if none, a platform_user_admin
- **SoD sets:** none

| Cell | From | Endpoints it covers |
|---|---|---|
| `kyc.application:delete` | own | (declared; endpoints not registered yet) |
| `kyc.application:modify` | own | (declared; endpoints not registered yet) |
| `kyc.application:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `kyc.application:submit` | own | (declared; endpoints not registered yet) |
| `kyc.form:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `lender.advance:authorize` | own | (declared; endpoints not registered yet) |
| `lender.advance:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `lender.assessment:execute` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `lender.assessment:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `lender.collection:execute` | own | (declared; endpoints not registered yet) |
| `lender.collection:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `lender.portfolio:execute` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `lender.portfolio:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.account:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.advance:authorize` | own | (declared; endpoints not registered yet) |
| `merchant.advance:create` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.advance:delete` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.advance:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.advance:submit` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.inventory:create` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.inventory:execute` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.inventory:modify` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.inventory:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.invoice:execute` | own | (declared; endpoints not registered yet) |
| `merchant.invoice:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.khata:create` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.khata:execute` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.khata:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.order:create` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.order:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.payment:execute` | own | (declared; endpoints not registered yet) |
| `merchant.payment:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.statement:execute` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.statement:export` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.statement:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.tab:create` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.tab:execute` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.tab:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.withdrawal:execute` | own | (declared; endpoints not registered yet) |
| `merchant.withdrawal:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `org.onboarding:authorize` | own | (declared; endpoints not registered yet) |
| `org.onboarding:create` | own | (declared; endpoints not registered yet) |
| `org.onboarding:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `org.rights:administer` | own | (declared; endpoints not registered yet) |
| `org.rights:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `org.suspension:administer` | own | (declared; endpoints not registered yet) |
| `org.tree:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `org.user:administer` | own | (declared; endpoints not registered yet) |
| `org.user:create` | own | (declared; endpoints not registered yet) |
| `org.user:delete` | own | (declared; endpoints not registered yet) |
| `org.user:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `rbac.assignment:administer` | own | `DELETE /rbac/admin/assignments/{id}` |
| `rbac.assignment:authorize` | own | `GET /rbac/admin/approvals`<br>`POST /rbac/admin/approvals/{id}/approve`<br>`POST /rbac/admin/approvals/{id}/reject` |
| `rbac.assignment:create` | own | `POST /rbac/admin/assignments` |
| `rbac.assignment:read` | own | `GET /rbac/admin/assignments`<br>`GET /rbac/admin/assignments/{id}`<br>`GET /rbac/admin/holders` |
| `rbac.grant:administer` | own | `DELETE /rbac/admin/grants/{id}` |
| `rbac.grant:create` | own | `POST /rbac/admin/grants` |
| `rbac.grant:read` | own | `GET /rbac/admin/grants` |
| `rbac.review:administer` | own | `POST /rbac/admin/reviews/{id}/items/{assignmentId}` |
| `rbac.review:read` | own | `GET /rbac/admin/reviews/{id}` |
| `rbac.role:create` | own | `POST /rbac/admin/roles` |
| `rbac.role:delete` | own | `DELETE /rbac/admin/roles/{name}` |
| `rbac.role:modify` | own | `PUT /rbac/admin/roles/{name}` |
| `rbac.role:read` | own | `GET /rbac/admin/export/role-pack`<br>`GET /rbac/admin/resources`<br>`GET /rbac/admin/roles`<br>`GET /rbac/admin/roles/{name}` |
| `supplier.account:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `supplier.bank_account:delete` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `supplier.bank_account:modify` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `supplier.bank_account:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `supplier.catalogue:create` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `supplier.catalogue:delete` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `supplier.catalogue:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `supplier.invoice:create` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `supplier.invoice:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `supplier.order:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `supplier.statement:execute` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `supplier.statement:export` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `supplier.statement:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |

Approved for use: ______________________ (Compliance)    Date: __________

---

## `org_onboarder`

Capability role (D-6): onboards orgs below and sets the per-type onboarding rights (OA3)

- **Kind:** ORG (assigned on an org, scope own or subtree)
- **Inherits:** org_staff
- **Granting needs:** A second org_admin of the same org; if none, an org_admin of the parent org; if none, a platform_user_admin
- **SoD sets:** none

| Cell | From | Endpoints it covers |
|---|---|---|
| `kyc.application:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `kyc.form:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `lender.advance:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `lender.assessment:execute` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `lender.assessment:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `lender.collection:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `lender.portfolio:execute` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `lender.portfolio:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.account:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.advance:create` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.advance:delete` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.advance:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.advance:submit` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.inventory:create` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.inventory:execute` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.inventory:modify` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.inventory:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.invoice:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.khata:create` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.khata:execute` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.khata:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.order:create` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.order:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.payment:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.statement:execute` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.statement:export` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.statement:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.tab:create` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.tab:execute` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.tab:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.withdrawal:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `org.onboarding:authorize` | own | (declared; endpoints not registered yet) |
| `org.onboarding:create` | own | (declared; endpoints not registered yet) |
| `org.onboarding:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `org.rights:administer` | own | (declared; endpoints not registered yet) |
| `org.rights:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `org.tree:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `org.user:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `rbac.grant:create` | own | `POST /rbac/admin/grants` |
| `rbac.grant:read` | own | `GET /rbac/admin/grants` |
| `supplier.account:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `supplier.bank_account:delete` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `supplier.bank_account:modify` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `supplier.bank_account:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `supplier.catalogue:create` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `supplier.catalogue:delete` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `supplier.catalogue:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `supplier.invoice:create` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `supplier.invoice:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `supplier.order:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `supplier.statement:execute` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `supplier.statement:export` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `supplier.statement:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |

Approved for use: ______________________ (Compliance)    Date: __________

---

## `org_staff`

Day-to-day member (today's STAFF): everything under their org type's API except money movement and administration

- **Kind:** ORG (assigned on an org, scope own or subtree)
- **Inherits:** org_viewer
- **Granting needs:** No second approval (granted by an org_admin of the org, within its ceiling; access reviews catch drift)
- **SoD sets:** none

| Cell | From | Endpoints it covers |
|---|---|---|
| `kyc.application:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `kyc.form:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `lender.advance:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `lender.assessment:execute` | own | (declared; endpoints not registered yet) |
| `lender.assessment:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `lender.collection:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `lender.portfolio:execute` | own | (declared; endpoints not registered yet) |
| `lender.portfolio:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.account:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.advance:create` | own | (declared; endpoints not registered yet) |
| `merchant.advance:delete` | own | (declared; endpoints not registered yet) |
| `merchant.advance:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.advance:submit` | own | (declared; endpoints not registered yet) |
| `merchant.inventory:create` | own | (declared; endpoints not registered yet) |
| `merchant.inventory:execute` | own | (declared; endpoints not registered yet) |
| `merchant.inventory:modify` | own | (declared; endpoints not registered yet) |
| `merchant.inventory:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.invoice:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.khata:create` | own | (declared; endpoints not registered yet) |
| `merchant.khata:execute` | own | (declared; endpoints not registered yet) |
| `merchant.khata:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.order:create` | own | (declared; endpoints not registered yet) |
| `merchant.order:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.payment:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.statement:execute` | own | (declared; endpoints not registered yet) |
| `merchant.statement:export` | own | (declared; endpoints not registered yet) |
| `merchant.statement:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.tab:create` | own | (declared; endpoints not registered yet) |
| `merchant.tab:execute` | own | (declared; endpoints not registered yet) |
| `merchant.tab:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.withdrawal:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `org.onboarding:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `org.rights:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `org.tree:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `org.user:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `supplier.account:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `supplier.bank_account:delete` | own | (declared; endpoints not registered yet) |
| `supplier.bank_account:modify` | own | (declared; endpoints not registered yet) |
| `supplier.bank_account:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `supplier.catalogue:create` | own | (declared; endpoints not registered yet) |
| `supplier.catalogue:delete` | own | (declared; endpoints not registered yet) |
| `supplier.catalogue:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `supplier.invoice:create` | own | (declared; endpoints not registered yet) |
| `supplier.invoice:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `supplier.order:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `supplier.statement:execute` | own | (declared; endpoints not registered yet) |
| `supplier.statement:export` | own | (declared; endpoints not registered yet) |
| `supplier.statement:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |

Approved for use: ______________________ (Compliance)    Date: __________

---

## `org_user_admin`

Capability role (D-6): manages the org's people — user lifecycle and role assignments within the org ceiling

- **Kind:** ORG (assigned on an org, scope own or subtree)
- **Inherits:** org_staff
- **Granting needs:** A second org_admin of the same org; if none, an org_admin of the parent org; if none, a platform_user_admin
- **SoD sets:** none

| Cell | From | Endpoints it covers |
|---|---|---|
| `kyc.application:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `kyc.form:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `lender.advance:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `lender.assessment:execute` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `lender.assessment:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `lender.collection:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `lender.portfolio:execute` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `lender.portfolio:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.account:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.advance:create` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.advance:delete` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.advance:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.advance:submit` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.inventory:create` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.inventory:execute` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.inventory:modify` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.inventory:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.invoice:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.khata:create` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.khata:execute` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.khata:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.order:create` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.order:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.payment:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.statement:execute` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.statement:export` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.statement:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.tab:create` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.tab:execute` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `merchant.tab:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `merchant.withdrawal:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `org.onboarding:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `org.rights:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `org.tree:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `org.user:administer` | own | (declared; endpoints not registered yet) |
| `org.user:create` | own | (declared; endpoints not registered yet) |
| `org.user:delete` | own | (declared; endpoints not registered yet) |
| `org.user:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `rbac.assignment:administer` | own | `DELETE /rbac/admin/assignments/{id}` |
| `rbac.assignment:create` | own | `POST /rbac/admin/assignments` |
| `rbac.assignment:read` | own | `GET /rbac/admin/assignments`<br>`GET /rbac/admin/assignments/{id}`<br>`GET /rbac/admin/holders` |
| `supplier.account:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `supplier.bank_account:delete` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `supplier.bank_account:modify` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `supplier.bank_account:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `supplier.catalogue:create` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `supplier.catalogue:delete` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `supplier.catalogue:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `supplier.invoice:create` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `supplier.invoice:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `supplier.order:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |
| `supplier.statement:execute` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `supplier.statement:export` | inherited from `org_staff` | (declared; endpoints not registered yet) |
| `supplier.statement:read` | inherited from `org_viewer` | (declared; endpoints not registered yet) |

Approved for use: ______________________ (Compliance)    Date: __________

---

## `org_viewer`

Read-only member of an org

- **Kind:** ORG (assigned on an org, scope own or subtree)
- **Inherits:** nothing
- **Granting needs:** No second approval (granted by an org_admin of the org, within its ceiling; access reviews catch drift)
- **SoD sets:** none

| Cell | From | Endpoints it covers |
|---|---|---|
| `kyc.application:read` | own | (declared; endpoints not registered yet) |
| `kyc.form:read` | own | (declared; endpoints not registered yet) |
| `lender.advance:read` | own | (declared; endpoints not registered yet) |
| `lender.assessment:read` | own | (declared; endpoints not registered yet) |
| `lender.collection:read` | own | (declared; endpoints not registered yet) |
| `lender.portfolio:read` | own | (declared; endpoints not registered yet) |
| `merchant.account:read` | own | (declared; endpoints not registered yet) |
| `merchant.advance:read` | own | (declared; endpoints not registered yet) |
| `merchant.inventory:read` | own | (declared; endpoints not registered yet) |
| `merchant.invoice:read` | own | (declared; endpoints not registered yet) |
| `merchant.khata:read` | own | (declared; endpoints not registered yet) |
| `merchant.order:read` | own | (declared; endpoints not registered yet) |
| `merchant.payment:read` | own | (declared; endpoints not registered yet) |
| `merchant.statement:read` | own | (declared; endpoints not registered yet) |
| `merchant.tab:read` | own | (declared; endpoints not registered yet) |
| `merchant.withdrawal:read` | own | (declared; endpoints not registered yet) |
| `org.onboarding:read` | own | (declared; endpoints not registered yet) |
| `org.rights:read` | own | (declared; endpoints not registered yet) |
| `org.tree:read` | own | (declared; endpoints not registered yet) |
| `org.user:read` | own | (declared; endpoints not registered yet) |
| `supplier.account:read` | own | (declared; endpoints not registered yet) |
| `supplier.bank_account:read` | own | (declared; endpoints not registered yet) |
| `supplier.catalogue:read` | own | (declared; endpoints not registered yet) |
| `supplier.invoice:read` | own | (declared; endpoints not registered yet) |
| `supplier.order:read` | own | (declared; endpoints not registered yet) |
| `supplier.statement:read` | own | (declared; endpoints not registered yet) |

Approved for use: ______________________ (Compliance)    Date: __________

---

## `platform_auditor`

Read-only over the whole platform (audit, access reviews)

- **Kind:** PLATFORM (scope all)
- **Inherits:** nothing
- **Granting needs:** A second platform_user_admin
- **SoD sets:** ssd-break-glass-separate (STATIC)

| Cell | From | Endpoints it covers |
|---|---|---|
| `admin.access:read` | all rows | (declared; endpoints not registered yet) |
| `admin.aml_alert:read` | all rows | (declared; endpoints not registered yet) |
| `admin.api_client:read` | all rows | (declared; endpoints not registered yet) |
| `admin.config:read` | all rows | (declared; endpoints not registered yet) |
| `admin.connector:read` | all rows | (declared; endpoints not registered yet) |
| `admin.dsar:read` | all rows | (declared; endpoints not registered yet) |
| `admin.platform_ops:read` | all rows | (declared; endpoints not registered yet) |
| `admin.sar:read` | all rows | (declared; endpoints not registered yet) |
| `admin.simulation:read` | all rows | (declared; endpoints not registered yet) |
| `kyc.application:read` | all rows | (declared; endpoints not registered yet) |
| `kyc.form:read` | all rows | (declared; endpoints not registered yet) |
| `kyc.task:read` | all rows | (declared; endpoints not registered yet) |
| `lender.advance:read` | all rows | (declared; endpoints not registered yet) |
| `lender.assessment:read` | all rows | (declared; endpoints not registered yet) |
| `lender.collection:read` | all rows | (declared; endpoints not registered yet) |
| `lender.portfolio:read` | all rows | (declared; endpoints not registered yet) |
| `merchant.account:read` | all rows | (declared; endpoints not registered yet) |
| `merchant.advance:read` | all rows | (declared; endpoints not registered yet) |
| `merchant.inventory:read` | all rows | (declared; endpoints not registered yet) |
| `merchant.invoice:read` | all rows | (declared; endpoints not registered yet) |
| `merchant.khata:read` | all rows | (declared; endpoints not registered yet) |
| `merchant.order:read` | all rows | (declared; endpoints not registered yet) |
| `merchant.payment:read` | all rows | (declared; endpoints not registered yet) |
| `merchant.statement:read` | all rows | (declared; endpoints not registered yet) |
| `merchant.tab:read` | all rows | (declared; endpoints not registered yet) |
| `merchant.withdrawal:read` | all rows | (declared; endpoints not registered yet) |
| `ops.audit:read` | all rows | (declared; endpoints not registered yet) |
| `ops.bank_account_approval:read` | all rows | (declared; endpoints not registered yet) |
| `ops.complaint:read` | all rows | (declared; endpoints not registered yet) |
| `ops.dispute:read` | all rows | (declared; endpoints not registered yet) |
| `ops.incident:read` | all rows | (declared; endpoints not registered yet) |
| `ops.kyc_check:read` | all rows | (declared; endpoints not registered yet) |
| `ops.merchant_approval:read` | all rows | (declared; endpoints not registered yet) |
| `ops.onboarding_request:read` | all rows | (declared; endpoints not registered yet) |
| `ops.recon:read` | all rows | (declared; endpoints not registered yet) |
| `ops.report:read` | all rows | (declared; endpoints not registered yet) |
| `ops.supplier_approval:read` | all rows | (declared; endpoints not registered yet) |
| `ops.suspension:read` | all rows | (declared; endpoints not registered yet) |
| `org.onboarding:read` | all rows | (declared; endpoints not registered yet) |
| `org.rights:read` | all rows | (declared; endpoints not registered yet) |
| `org.tree:read` | all rows | (declared; endpoints not registered yet) |
| `org.user:read` | all rows | (declared; endpoints not registered yet) |
| `rbac.assignment:read` | all rows | `GET /rbac/admin/assignments`<br>`GET /rbac/admin/assignments/{id}`<br>`GET /rbac/admin/holders` |
| `rbac.audit:read` | own | `GET /rbac/admin/audit`<br>`GET /rbac/admin/decisions/{id}` |
| `rbac.grant:read` | all rows | `GET /rbac/admin/grants` |
| `rbac.review:read` | all rows | `GET /rbac/admin/reviews/{id}` |
| `rbac.role:read` | all rows | `GET /rbac/admin/export/role-pack`<br>`GET /rbac/admin/resources`<br>`GET /rbac/admin/roles`<br>`GET /rbac/admin/roles/{name}` |
| `rbac.user:read` | all rows | (declared; endpoints not registered yet) |
| `supplier.account:read` | all rows | (declared; endpoints not registered yet) |
| `supplier.bank_account:read` | all rows | (declared; endpoints not registered yet) |
| `supplier.catalogue:read` | all rows | (declared; endpoints not registered yet) |
| `supplier.invoice:read` | all rows | (declared; endpoints not registered yet) |
| `supplier.order:read` | all rows | (declared; endpoints not registered yet) |
| `supplier.statement:read` | all rows | (declared; endpoints not registered yet) |

Approved for use: ______________________ (Compliance)    Date: __________

---

## `platform_break_glass`

Emergency access to restore access and lift holds; time-boxed (8 h), approved, alerted

- **Kind:** PLATFORM (scope all)
- **Inherits:** nothing
- **Granting needs:** Requested by the person; approved by a platform_user_admin at AAL2; at most 8 h; the security contact is alerted
- **Lasts at most:** 8 h
- **SoD sets:** ssd-break-glass-separate (STATIC)

| Cell | From | Endpoints it covers |
|---|---|---|
| `admin.access:administer` | own | (declared; endpoints not registered yet) |
| `admin.access:read` | all rows | (declared; endpoints not registered yet) |
| `admin.aml_alert:read` | all rows | (declared; endpoints not registered yet) |
| `admin.api_client:read` | all rows | (declared; endpoints not registered yet) |
| `admin.config:read` | all rows | (declared; endpoints not registered yet) |
| `admin.connector:read` | all rows | (declared; endpoints not registered yet) |
| `admin.dsar:read` | all rows | (declared; endpoints not registered yet) |
| `admin.platform_ops:read` | all rows | (declared; endpoints not registered yet) |
| `admin.sar:read` | all rows | (declared; endpoints not registered yet) |
| `admin.simulation:read` | all rows | (declared; endpoints not registered yet) |
| `kyc.application:read` | all rows | (declared; endpoints not registered yet) |
| `kyc.form:read` | all rows | (declared; endpoints not registered yet) |
| `kyc.task:read` | all rows | (declared; endpoints not registered yet) |
| `lender.advance:read` | all rows | (declared; endpoints not registered yet) |
| `lender.assessment:read` | all rows | (declared; endpoints not registered yet) |
| `lender.collection:read` | all rows | (declared; endpoints not registered yet) |
| `lender.portfolio:read` | all rows | (declared; endpoints not registered yet) |
| `merchant.account:read` | all rows | (declared; endpoints not registered yet) |
| `merchant.advance:read` | all rows | (declared; endpoints not registered yet) |
| `merchant.inventory:read` | all rows | (declared; endpoints not registered yet) |
| `merchant.invoice:read` | all rows | (declared; endpoints not registered yet) |
| `merchant.khata:read` | all rows | (declared; endpoints not registered yet) |
| `merchant.order:read` | all rows | (declared; endpoints not registered yet) |
| `merchant.payment:read` | all rows | (declared; endpoints not registered yet) |
| `merchant.statement:read` | all rows | (declared; endpoints not registered yet) |
| `merchant.tab:read` | all rows | (declared; endpoints not registered yet) |
| `merchant.withdrawal:read` | all rows | (declared; endpoints not registered yet) |
| `ops.audit:read` | all rows | (declared; endpoints not registered yet) |
| `ops.bank_account_approval:read` | all rows | (declared; endpoints not registered yet) |
| `ops.complaint:read` | all rows | (declared; endpoints not registered yet) |
| `ops.dispute:read` | all rows | (declared; endpoints not registered yet) |
| `ops.incident:read` | all rows | (declared; endpoints not registered yet) |
| `ops.kyc_check:read` | all rows | (declared; endpoints not registered yet) |
| `ops.merchant_approval:read` | all rows | (declared; endpoints not registered yet) |
| `ops.onboarding_request:read` | all rows | (declared; endpoints not registered yet) |
| `ops.recon:read` | all rows | (declared; endpoints not registered yet) |
| `ops.report:read` | all rows | (declared; endpoints not registered yet) |
| `ops.supplier_approval:read` | all rows | (declared; endpoints not registered yet) |
| `ops.suspension:administer` | own | (declared; endpoints not registered yet) |
| `ops.suspension:read` | all rows | (declared; endpoints not registered yet) |
| `org.onboarding:read` | all rows | (declared; endpoints not registered yet) |
| `org.rights:read` | all rows | (declared; endpoints not registered yet) |
| `org.suspension:administer` | own | (declared; endpoints not registered yet) |
| `org.tree:read` | all rows | (declared; endpoints not registered yet) |
| `org.user:administer` | own | (declared; endpoints not registered yet) |
| `org.user:create` | own | (declared; endpoints not registered yet) |
| `org.user:delete` | own | (declared; endpoints not registered yet) |
| `org.user:read` | all rows | (declared; endpoints not registered yet) |
| `rbac.assignment:administer` | own | `DELETE /rbac/admin/assignments/{id}` |
| `rbac.assignment:create` | own | `POST /rbac/admin/assignments` |
| `rbac.assignment:read` | all rows | `GET /rbac/admin/assignments`<br>`GET /rbac/admin/assignments/{id}`<br>`GET /rbac/admin/holders` |
| `rbac.audit:read` | all rows | `GET /rbac/admin/audit`<br>`GET /rbac/admin/decisions/{id}` |
| `rbac.grant:read` | all rows | `GET /rbac/admin/grants` |
| `rbac.review:read` | all rows | `GET /rbac/admin/reviews/{id}` |
| `rbac.role:read` | all rows | `GET /rbac/admin/export/role-pack`<br>`GET /rbac/admin/resources`<br>`GET /rbac/admin/roles`<br>`GET /rbac/admin/roles/{name}` |
| `rbac.user:read` | all rows | (declared; endpoints not registered yet) |
| `supplier.account:read` | all rows | (declared; endpoints not registered yet) |
| `supplier.bank_account:read` | all rows | (declared; endpoints not registered yet) |
| `supplier.catalogue:read` | all rows | (declared; endpoints not registered yet) |
| `supplier.invoice:read` | all rows | (declared; endpoints not registered yet) |
| `supplier.order:read` | all rows | (declared; endpoints not registered yet) |
| `supplier.statement:read` | all rows | (declared; endpoints not registered yet) |

Approved for use: ______________________ (Compliance)    Date: __________

---

## `platform_compliance_officer`

Compliance review stage, SARs, AML alerts, DSARs

- **Kind:** PLATFORM (scope all)
- **Inherits:** platform_auditor
- **Granting needs:** The platform_mlro (when granting platform_mlro itself: a platform_senior_approver)
- **SoD sets:** dsd-one-review-stage-per-session (DYNAMIC), ssd-access-admin-not-reviewer (STATIC), ssd-break-glass-separate (STATIC)

| Cell | From | Endpoints it covers |
|---|---|---|
| `admin.access:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.aml_alert:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.api_client:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.config:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.connector:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.dsar:create` | own | (declared; endpoints not registered yet) |
| `admin.dsar:export` | own | (declared; endpoints not registered yet) |
| `admin.dsar:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.platform_ops:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.sar:create` | own | (declared; endpoints not registered yet) |
| `admin.sar:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.sar:submit` | own | (declared; endpoints not registered yet) |
| `admin.simulation:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `kyc.application:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `kyc.form:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `kyc.task:authorize` | own | (declared; endpoints not registered yet) |
| `kyc.task:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `lender.advance:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `lender.assessment:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `lender.collection:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `lender.portfolio:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.account:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.advance:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.inventory:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.invoice:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.khata:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.order:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.payment:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.statement:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.tab:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.withdrawal:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.audit:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.bank_account_approval:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.complaint:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.dispute:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.incident:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.kyc_check:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.merchant_approval:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.onboarding_request:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.recon:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.report:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.supplier_approval:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.suspension:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `org.onboarding:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `org.rights:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `org.tree:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `org.user:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `rbac.assignment:read` | inherited from `platform_auditor` | `GET /rbac/admin/assignments`<br>`GET /rbac/admin/assignments/{id}`<br>`GET /rbac/admin/holders` |
| `rbac.audit:read` | inherited from `platform_auditor` | `GET /rbac/admin/audit`<br>`GET /rbac/admin/decisions/{id}` |
| `rbac.grant:read` | inherited from `platform_auditor` | `GET /rbac/admin/grants` |
| `rbac.review:read` | inherited from `platform_auditor` | `GET /rbac/admin/reviews/{id}` |
| `rbac.role:read` | inherited from `platform_auditor` | `GET /rbac/admin/export/role-pack`<br>`GET /rbac/admin/resources`<br>`GET /rbac/admin/roles`<br>`GET /rbac/admin/roles/{name}` |
| `rbac.user:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `supplier.account:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `supplier.bank_account:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `supplier.catalogue:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `supplier.invoice:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `supplier.order:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `supplier.statement:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |

Approved for use: ______________________ (Compliance)    Date: __________

---

## `platform_engineer`

Platform engineering: deployments, backups, connectors, configuration

- **Kind:** PLATFORM (scope all)
- **Inherits:** nothing
- **Granting needs:** A second platform_user_admin
- **SoD sets:** ssd-break-glass-separate (STATIC)

| Cell | From | Endpoints it covers |
|---|---|---|
| `admin.config:modify` | own | (declared; endpoints not registered yet) |
| `admin.config:read` | own | (declared; endpoints not registered yet) |
| `admin.connector:administer` | own | (declared; endpoints not registered yet) |
| `admin.connector:execute` | own | (declared; endpoints not registered yet) |
| `admin.connector:modify` | own | (declared; endpoints not registered yet) |
| `admin.connector:read` | own | (declared; endpoints not registered yet) |
| `admin.platform_ops:administer` | own | (declared; endpoints not registered yet) |
| `admin.platform_ops:execute` | own | (declared; endpoints not registered yet) |
| `admin.platform_ops:read` | own | (declared; endpoints not registered yet) |
| `admin.simulation:read` | own | (declared; endpoints not registered yet) |
| `ops.monitoring:execute` | own | (declared; endpoints not registered yet) |

Approved for use: ______________________ (Compliance)    Date: __________

---

## `platform_mlro`

Money-laundering reporting officer: SAR filing, AML alerts, compliance decisions

- **Kind:** PLATFORM (scope all)
- **Inherits:** platform_auditor
- **Granting needs:** The platform_mlro (when granting platform_mlro itself: a platform_senior_approver)
- **SoD sets:** ssd-access-admin-not-reviewer (STATIC), ssd-break-glass-separate (STATIC), ssd-mlro-independent (STATIC)

| Cell | From | Endpoints it covers |
|---|---|---|
| `admin.access:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.aml_alert:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.api_client:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.config:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.connector:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.dsar:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.platform_ops:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.sar:create` | own | (declared; endpoints not registered yet) |
| `admin.sar:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.sar:submit` | own | (declared; endpoints not registered yet) |
| `admin.simulation:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `kyc.application:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `kyc.form:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `kyc.task:authorize` | own | (declared; endpoints not registered yet) |
| `kyc.task:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `lender.advance:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `lender.assessment:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `lender.collection:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `lender.portfolio:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.account:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.advance:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.inventory:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.invoice:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.khata:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.order:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.payment:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.statement:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.tab:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.withdrawal:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.audit:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.bank_account_approval:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.complaint:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.dispute:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.incident:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.kyc_check:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.merchant_approval:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.onboarding_request:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.recon:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.report:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.supplier_approval:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.suspension:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `org.onboarding:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `org.rights:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `org.tree:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `org.user:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `rbac.assignment:authorize` | own | `GET /rbac/admin/approvals`<br>`POST /rbac/admin/approvals/{id}/approve`<br>`POST /rbac/admin/approvals/{id}/reject` |
| `rbac.assignment:read` | own | `GET /rbac/admin/assignments`<br>`GET /rbac/admin/assignments/{id}`<br>`GET /rbac/admin/holders` |
| `rbac.audit:read` | inherited from `platform_auditor` | `GET /rbac/admin/audit`<br>`GET /rbac/admin/decisions/{id}` |
| `rbac.grant:read` | inherited from `platform_auditor` | `GET /rbac/admin/grants` |
| `rbac.review:read` | inherited from `platform_auditor` | `GET /rbac/admin/reviews/{id}` |
| `rbac.role:read` | inherited from `platform_auditor` | `GET /rbac/admin/export/role-pack`<br>`GET /rbac/admin/resources`<br>`GET /rbac/admin/roles`<br>`GET /rbac/admin/roles/{name}` |
| `rbac.user:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `supplier.account:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `supplier.bank_account:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `supplier.catalogue:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `supplier.invoice:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `supplier.order:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `supplier.statement:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |

Approved for use: ______________________ (Compliance)    Date: __________

---

## `platform_operations`

Operations desk (today's TPTS_OPS work that is not a customer approval)

- **Kind:** PLATFORM (scope all)
- **Inherits:** platform_auditor
- **Granting needs:** A second platform_user_admin
- **SoD sets:** ssd-break-glass-separate (STATIC)

| Cell | From | Endpoints it covers |
|---|---|---|
| `admin.access:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.aml_alert:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.api_client:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.config:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.connector:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.dsar:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.platform_ops:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.sar:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.simulation:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `kyc.application:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `kyc.form:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `kyc.task:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `lender.advance:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `lender.assessment:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `lender.collection:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `lender.portfolio:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.account:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.advance:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.inventory:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.invoice:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.khata:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.order:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.payment:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.statement:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.tab:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.withdrawal:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.audit:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.bank_account_approval:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.complaint:create` | own | (declared; endpoints not registered yet) |
| `ops.complaint:modify` | own | (declared; endpoints not registered yet) |
| `ops.complaint:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.dispute:create` | own | (declared; endpoints not registered yet) |
| `ops.dispute:modify` | own | (declared; endpoints not registered yet) |
| `ops.dispute:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.incident:create` | own | (declared; endpoints not registered yet) |
| `ops.incident:modify` | own | (declared; endpoints not registered yet) |
| `ops.incident:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.kyc_check:create` | own | (declared; endpoints not registered yet) |
| `ops.kyc_check:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.merchant_approval:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.monitoring:execute` | own | (declared; endpoints not registered yet) |
| `ops.onboarding_request:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.recon:execute` | own | (declared; endpoints not registered yet) |
| `ops.recon:modify` | own | (declared; endpoints not registered yet) |
| `ops.recon:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.report:execute` | own | (declared; endpoints not registered yet) |
| `ops.report:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.supplier_approval:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.suspension:administer` | own | (declared; endpoints not registered yet) |
| `ops.suspension:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `org.onboarding:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `org.rights:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `org.tree:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `org.user:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `rbac.assignment:read` | inherited from `platform_auditor` | `GET /rbac/admin/assignments`<br>`GET /rbac/admin/assignments/{id}`<br>`GET /rbac/admin/holders` |
| `rbac.audit:read` | inherited from `platform_auditor` | `GET /rbac/admin/audit`<br>`GET /rbac/admin/decisions/{id}` |
| `rbac.grant:read` | inherited from `platform_auditor` | `GET /rbac/admin/grants` |
| `rbac.review:read` | inherited from `platform_auditor` | `GET /rbac/admin/reviews/{id}` |
| `rbac.role:read` | inherited from `platform_auditor` | `GET /rbac/admin/export/role-pack`<br>`GET /rbac/admin/resources`<br>`GET /rbac/admin/roles`<br>`GET /rbac/admin/roles/{name}` |
| `rbac.user:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `supplier.account:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `supplier.bank_account:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `supplier.catalogue:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `supplier.invoice:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `supplier.order:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `supplier.statement:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |

Approved for use: ______________________ (Compliance)    Date: __________

---

## `platform_ops_reviewer`

First-line reviewer: customer, bank account and onboarding approvals; KYC ops-review stage

- **Kind:** PLATFORM (scope all)
- **Inherits:** platform_auditor
- **Granting needs:** A second platform_user_admin
- **SoD sets:** dsd-one-review-stage-per-session (DYNAMIC), ssd-access-admin-not-reviewer (STATIC), ssd-break-glass-separate (STATIC), ssd-two-review-stages (STATIC)

| Cell | From | Endpoints it covers |
|---|---|---|
| `admin.access:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.aml_alert:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.api_client:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.config:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.connector:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.dsar:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.platform_ops:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.sar:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.simulation:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `kyc.application:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `kyc.form:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `kyc.task:authorize` | own | (declared; endpoints not registered yet) |
| `kyc.task:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `lender.advance:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `lender.assessment:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `lender.collection:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `lender.portfolio:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.account:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.advance:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.inventory:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.invoice:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.khata:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.order:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.payment:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.statement:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.tab:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.withdrawal:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.audit:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.bank_account_approval:authorize` | own | (declared; endpoints not registered yet) |
| `ops.bank_account_approval:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.complaint:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.dispute:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.incident:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.kyc_check:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.merchant_approval:authorize` | own | (declared; endpoints not registered yet) |
| `ops.merchant_approval:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.onboarding_request:authorize` | own | (declared; endpoints not registered yet) |
| `ops.onboarding_request:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.recon:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.report:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.supplier_approval:authorize` | own | (declared; endpoints not registered yet) |
| `ops.supplier_approval:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.suspension:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `org.onboarding:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `org.rights:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `org.tree:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `org.user:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `rbac.assignment:read` | inherited from `platform_auditor` | `GET /rbac/admin/assignments`<br>`GET /rbac/admin/assignments/{id}`<br>`GET /rbac/admin/holders` |
| `rbac.audit:read` | inherited from `platform_auditor` | `GET /rbac/admin/audit`<br>`GET /rbac/admin/decisions/{id}` |
| `rbac.grant:read` | inherited from `platform_auditor` | `GET /rbac/admin/grants` |
| `rbac.review:read` | inherited from `platform_auditor` | `GET /rbac/admin/reviews/{id}` |
| `rbac.role:read` | inherited from `platform_auditor` | `GET /rbac/admin/export/role-pack`<br>`GET /rbac/admin/resources`<br>`GET /rbac/admin/roles`<br>`GET /rbac/admin/roles/{name}` |
| `rbac.user:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `supplier.account:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `supplier.bank_account:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `supplier.catalogue:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `supplier.invoice:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `supplier.order:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `supplier.statement:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |

Approved for use: ______________________ (Compliance)    Date: __________

---

## `platform_senior_approver`

Senior-management approval for high-risk / PIP cases (FIA s.23)

- **Kind:** PLATFORM (scope all)
- **Inherits:** platform_auditor
- **Granting needs:** The platform_mlro (when granting platform_mlro itself: a platform_senior_approver)
- **SoD sets:** ssd-access-admin-not-reviewer (STATIC), ssd-break-glass-separate (STATIC), ssd-two-review-stages (STATIC)

| Cell | From | Endpoints it covers |
|---|---|---|
| `admin.access:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.aml_alert:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.api_client:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.config:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.connector:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.dsar:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.platform_ops:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.sar:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `admin.simulation:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `kyc.application:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `kyc.form:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `kyc.task:authorize` | own | (declared; endpoints not registered yet) |
| `kyc.task:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `lender.advance:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `lender.assessment:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `lender.collection:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `lender.portfolio:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.account:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.advance:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.inventory:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.invoice:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.khata:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.order:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.payment:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.statement:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.tab:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `merchant.withdrawal:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.audit:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.bank_account_approval:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.complaint:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.dispute:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.incident:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.kyc_check:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.merchant_approval:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.onboarding_request:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.recon:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.report:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.supplier_approval:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `ops.suspension:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `org.onboarding:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `org.rights:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `org.tree:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `org.user:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `rbac.assignment:authorize` | own | `GET /rbac/admin/approvals`<br>`POST /rbac/admin/approvals/{id}/approve`<br>`POST /rbac/admin/approvals/{id}/reject` |
| `rbac.assignment:read` | own | `GET /rbac/admin/assignments`<br>`GET /rbac/admin/assignments/{id}`<br>`GET /rbac/admin/holders` |
| `rbac.audit:read` | inherited from `platform_auditor` | `GET /rbac/admin/audit`<br>`GET /rbac/admin/decisions/{id}` |
| `rbac.grant:read` | inherited from `platform_auditor` | `GET /rbac/admin/grants` |
| `rbac.review:read` | inherited from `platform_auditor` | `GET /rbac/admin/reviews/{id}` |
| `rbac.role:read` | inherited from `platform_auditor` | `GET /rbac/admin/export/role-pack`<br>`GET /rbac/admin/resources`<br>`GET /rbac/admin/roles`<br>`GET /rbac/admin/roles/{name}` |
| `rbac.user:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `supplier.account:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `supplier.bank_account:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `supplier.catalogue:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `supplier.invoice:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `supplier.order:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |
| `supplier.statement:read` | inherited from `platform_auditor` | (declared; endpoints not registered yet) |

Approved for use: ______________________ (Compliance)    Date: __________

---

## `platform_user_admin`

Access administration: org users, platform access, API clients, role assignments. Never approves customers (SoD)

- **Kind:** PLATFORM (scope all)
- **Inherits:** nothing
- **Granting needs:** A second platform_user_admin
- **SoD sets:** ssd-access-admin-not-reviewer (STATIC), ssd-break-glass-separate (STATIC), ssd-mlro-independent (STATIC)

| Cell | From | Endpoints it covers |
|---|---|---|
| `admin.access:administer` | own | (declared; endpoints not registered yet) |
| `admin.access:read` | own | (declared; endpoints not registered yet) |
| `admin.api_client:administer` | own | (declared; endpoints not registered yet) |
| `admin.api_client:create` | own | (declared; endpoints not registered yet) |
| `admin.api_client:read` | own | (declared; endpoints not registered yet) |
| `org.tree:read` | own | (declared; endpoints not registered yet) |
| `org.user:administer` | own | (declared; endpoints not registered yet) |
| `org.user:create` | own | (declared; endpoints not registered yet) |
| `org.user:delete` | own | (declared; endpoints not registered yet) |
| `org.user:read` | own | (declared; endpoints not registered yet) |
| `rbac.assignment:administer` | own | `DELETE /rbac/admin/assignments/{id}` |
| `rbac.assignment:authorize` | own | `GET /rbac/admin/approvals`<br>`POST /rbac/admin/approvals/{id}/approve`<br>`POST /rbac/admin/approvals/{id}/reject` |
| `rbac.assignment:create` | own | `POST /rbac/admin/assignments` |
| `rbac.assignment:read` | own | `GET /rbac/admin/assignments`<br>`GET /rbac/admin/assignments/{id}`<br>`GET /rbac/admin/holders` |
| `rbac.audit:read` | own | `GET /rbac/admin/audit`<br>`GET /rbac/admin/decisions/{id}` |
| `rbac.grant:administer` | own | `DELETE /rbac/admin/grants/{id}` |
| `rbac.grant:create` | own | `POST /rbac/admin/grants` |
| `rbac.grant:read` | own | `GET /rbac/admin/grants` |
| `rbac.review:administer` | own | `POST /rbac/admin/reviews/{id}/items/{assignmentId}` |
| `rbac.review:create` | own | `POST /rbac/admin/reviews` |
| `rbac.review:read` | own | `GET /rbac/admin/reviews/{id}` |
| `rbac.role:create` | own | `POST /rbac/admin/roles` |
| `rbac.role:delete` | own | `DELETE /rbac/admin/roles/{name}` |
| `rbac.role:modify` | own | `PUT /rbac/admin/roles/{name}` |
| `rbac.role:read` | own | `GET /rbac/admin/export/role-pack`<br>`GET /rbac/admin/resources`<br>`GET /rbac/admin/roles`<br>`GET /rbac/admin/roles/{name}` |
| `rbac.user:read` | own | (declared; endpoints not registered yet) |

Approved for use: ______________________ (Compliance)    Date: __________

---

## `sandbox_tester`

Simulation controls (sandbox and UAT only; refused in production)

- **Kind:** SANDBOX (sandbox and UAT only)
- **Inherits:** nothing
- **Granting needs:** No second approval (granted by an org_admin of the org, within its ceiling; access reviews catch drift)
- **SoD sets:** none

| Cell | From | Endpoints it covers |
|---|---|---|
| `admin.simulation:read` | own | (declared; endpoints not registered yet) |
| `admin.simulation:simulate` | own | (declared; endpoints not registered yet) |
| `merchant.advance:simulate` | own | (declared; endpoints not registered yet) |
| `merchant.payment:simulate` | own | (declared; endpoints not registered yet) |

Approved for use: ______________________ (Compliance)    Date: __________

---

## Role ceilings and org-defined roles

Every org uses only the roles inside its **role ceiling**, chosen by the org that onboarded it and never wider than that org's own hand-down set. Always inside every ceiling: `kyc_applicant`, `org_admin`.

Orgs may build their own roles from the roles in their ceiling. Each one's cells are a subset of its base roles' cells, checked on save and again on every decision, so these roles never grant anything outside the signed-off roles above. Base roles never span both sides of a separation-of-duty rule. They are listed here for the access review, not for separate sign-off.

No org-defined roles yet.
