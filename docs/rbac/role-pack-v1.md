# RBAC role pack for compliance sign-off

Generated 2026-11-12 from the live RBAC service (environment `dev`). Source of truth: `trustplane/config/rbac/grid.yaml`; the endpoints come from the code's own registration.

Version 1 of the grid reproduces today's access one-to-one (decision D9), so shadow mode can prove the two agree. Tightening happens afterwards through the quarterly access review.

**Verbs:** read · create · modify · delete · submit · authorize · execute · export · administer · simulate. A cell `row:verb` exists only if an endpoint uses it.

## Summary

| Role | Kind | Inherits | Cells (with inherited) | Approval to grant | Holders |
|---|---|---|---|---|---|
| `kyc_applicant` | ORG | — | 5 | none | 0 |
| `lender_officer` | PLATFORM | — | 8 | platform | 0 |
| `org_admin` | ORG | `org_staff` | 77 | org_admin | 0 |
| `org_staff` | ORG | `org_viewer` | 48 | none | 0 |
| `org_viewer` | ORG | — | 26 | none | 0 |
| `platform_auditor` | PLATFORM | — | 54 | platform | 0 |
| `platform_break_glass` | PLATFORM | — | 62 | break_glass | 0 |
| `platform_compliance_officer` | PLATFORM | `platform_auditor` | 59 | compliance | 0 |
| `platform_engineer` | PLATFORM | — | 11 | platform | 0 |
| `platform_mlro` | PLATFORM | `platform_auditor` | 58 | compliance | 0 |
| `platform_operations` | PLATFORM | `platform_auditor` | 66 | platform | 0 |
| `platform_ops_reviewer` | PLATFORM | `platform_auditor` | 59 | platform | 0 |
| `platform_senior_approver` | PLATFORM | `platform_auditor` | 56 | compliance | 0 |
| `platform_user_admin` | PLATFORM | — | 26 | platform | 0 |
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
| `kyc.application:delete` | own | `POST /api/kyc/orgs/{id}/withdraw` |
| `kyc.application:modify` | own | `PUT /api/kyc/orgs/{id}`<br>`POST /api/kyc/orgs/{id}/upgrade`<br>`PUT /api/orgs/{id}/kyc` |
| `kyc.application:read` | own | `GET /api/kyc/orgs`<br>`GET /api/kyc/orgs/me`<br>`GET /api/kyc/orgs/{id}`<br>`GET /api/orgs/kyc`<br>`GET /api/orgs/me/kyc`<br>`GET /api/orgs/{id}/kyc` |
| `kyc.application:submit` | own | `POST /api/kyc/orgs/{id}/submit`<br>`POST /api/orgs/{id}/kyc/submit` |
| `kyc.form:read` | own | `GET /api/kyc/lists/{code}`<br>`GET /api/kyc/schema`<br>`GET /api/kyc/schema/{orgId}` |

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
| `lender.advance:authorize` | own | `POST /api/lender/advances/{id}/decline`<br>`POST /api/lender/advances/{id}/fund` |
| `lender.advance:read` | own | `GET /api/lender/advances`<br>`GET /api/lender/consents` |
| `lender.assessment:execute` | own | `POST /api/lender/assess` |
| `lender.assessment:read` | own | `GET /api/lender/assessments`<br>`GET /api/lender/records/{merchantId}` |
| `lender.collection:execute` | own | `POST /api/lender/sweep` |
| `lender.collection:read` | own | `GET /api/lender/collections` |
| `lender.portfolio:execute` | own | `POST /api/lender/portfolio/snapshot` |
| `lender.portfolio:read` | own | `GET /api/lender/portfolio` |

Approved for use: ______________________ (Compliance)    Date: __________

---

## `org_admin`

Org administrator (today's ADMIN): money movement, KYC, users, onboarding and the orgs below

- **Kind:** ORG (assigned on an org, scope own or subtree)
- **Inherits:** org_staff
- **Granting needs:** A second org_admin of the same org; if none, an org_admin of the parent org; if none, a platform_user_admin
- **SoD sets:** none

| Cell | From | Endpoints it covers |
|---|---|---|
| `kyc.application:delete` | own | `POST /api/kyc/orgs/{id}/withdraw` |
| `kyc.application:modify` | own | `PUT /api/kyc/orgs/{id}`<br>`POST /api/kyc/orgs/{id}/upgrade`<br>`PUT /api/orgs/{id}/kyc` |
| `kyc.application:read` | inherited from `org_viewer` | `GET /api/kyc/orgs`<br>`GET /api/kyc/orgs/me`<br>`GET /api/kyc/orgs/{id}`<br>`GET /api/orgs/kyc`<br>`GET /api/orgs/me/kyc`<br>`GET /api/orgs/{id}/kyc` |
| `kyc.application:submit` | own | `POST /api/kyc/orgs/{id}/submit`<br>`POST /api/orgs/{id}/kyc/submit` |
| `kyc.form:read` | inherited from `org_viewer` | `GET /api/kyc/lists/{code}`<br>`GET /api/kyc/schema`<br>`GET /api/kyc/schema/{orgId}` |
| `lender.advance:authorize` | own | `POST /api/lender/advances/{id}/decline`<br>`POST /api/lender/advances/{id}/fund` |
| `lender.advance:read` | inherited from `org_viewer` | `GET /api/lender/advances`<br>`GET /api/lender/consents` |
| `lender.assessment:execute` | inherited from `org_staff` | `POST /api/lender/assess` |
| `lender.assessment:read` | inherited from `org_viewer` | `GET /api/lender/assessments`<br>`GET /api/lender/records/{merchantId}` |
| `lender.collection:execute` | own | `POST /api/lender/sweep` |
| `lender.collection:read` | inherited from `org_viewer` | `GET /api/lender/collections` |
| `lender.portfolio:execute` | inherited from `org_staff` | `POST /api/lender/portfolio/snapshot` |
| `lender.portfolio:read` | inherited from `org_viewer` | `GET /api/lender/portfolio` |
| `merchant.account:read` | inherited from `org_viewer` | `GET /api/merchant/accept-info`<br>`GET /api/merchant/balance`<br>`GET /api/merchant/balance/entries`<br>`GET /api/merchant/billing-preview`<br>`GET /api/merchant/dashboard`<br>`GET /api/merchant/devices`<br>`GET /api/merchant/settings`<br>`GET /api/merchant/speaker-log` |
| `merchant.advance:authorize` | own | `POST /api/merchant/advance/{id}/accept` |
| `merchant.advance:create` | inherited from `org_staff` | `POST /api/merchant/advance/consent` |
| `merchant.advance:delete` | inherited from `org_staff` | `POST /api/merchant/advance/consents/{id}/withdraw` |
| `merchant.advance:read` | inherited from `org_viewer` | `GET /api/merchant/advance`<br>`GET /api/merchant/advance/consents`<br>`GET /api/merchant/advance/eligibility`<br>`GET /api/merchant/lenders` |
| `merchant.advance:submit` | inherited from `org_staff` | `POST /api/merchant/advance/request` |
| `merchant.inventory:create` | inherited from `org_staff` | `POST /api/merchant/inventory` |
| `merchant.inventory:execute` | inherited from `org_staff` | `POST /api/merchant/inventory/{itemId}/reorder` |
| `merchant.inventory:modify` | inherited from `org_staff` | `POST /api/merchant/inventory/adjust` |
| `merchant.inventory:read` | inherited from `org_viewer` | `GET /api/merchant/catalogue`<br>`GET /api/merchant/inventory`<br>`GET /api/merchant/inventory/alerts`<br>`GET /api/merchant/inventory/rules` |
| `merchant.invoice:execute` | own | `POST /api/merchant/invoices/{id}/pay`<br>`POST /api/merchant/invoices/{id}/retry-timeout` |
| `merchant.invoice:read` | inherited from `org_viewer` | `GET /api/merchant/invoices`<br>`GET /api/merchant/invoices/{id}` |
| `merchant.khata:create` | inherited from `org_staff` | `POST /api/merchant/khata`<br>`POST /api/merchant/khata/{id}/entries` |
| `merchant.khata:execute` | inherited from `org_staff` | `POST /api/merchant/khata/{id}/remind` |
| `merchant.khata:read` | inherited from `org_viewer` | `GET /api/merchant/khata`<br>`GET /api/merchant/khata/{id}/entries` |
| `merchant.order:create` | inherited from `org_staff` | `POST /api/merchant/orders` |
| `merchant.order:read` | inherited from `org_viewer` | `GET /api/merchant/orders`<br>`GET /api/merchant/orders/{id}` |
| `merchant.payment:execute` | own | `POST /api/merchant/payments`<br>`POST /api/merchant/payments/{id}/refund` |
| `merchant.payment:read` | inherited from `org_viewer` | `GET /api/merchant/payments`<br>`GET /api/merchant/payments/{id}` |
| `merchant.statement:execute` | inherited from `org_staff` | `POST /api/merchant/statements` |
| `merchant.statement:export` | inherited from `org_staff` | `GET /api/merchant/statements/{id}/download` |
| `merchant.statement:read` | inherited from `org_viewer` | `GET /api/merchant/statements` |
| `merchant.tab:create` | inherited from `org_staff` | `POST /api/merchant/tabs`<br>`POST /api/merchant/tabs/{id}/entries` |
| `merchant.tab:execute` | inherited from `org_staff` | `POST /api/merchant/tabs/{id}/remind`<br>`POST /api/merchant/tabs/{id}/settle` |
| `merchant.tab:read` | inherited from `org_viewer` | `GET /api/merchant/tabs`<br>`GET /api/merchant/tabs/{id}/entries` |
| `merchant.withdrawal:execute` | own | `POST /api/merchant/withdrawals` |
| `merchant.withdrawal:read` | inherited from `org_viewer` | `GET /api/merchant/withdrawals` |
| `org.onboarding:authorize` | own | `POST /api/orgs/onboarding/{id}/decide`<br>`POST /api/orgs/{id}/admins` |
| `org.onboarding:create` | own | `POST /api/orgs/me/children` |
| `org.onboarding:read` | inherited from `org_viewer` | `GET /api/orgs/onboarding` |
| `org.rights:administer` | own | `PUT /api/orgs/{id}/rights`<br>`PUT /api/orgs/{id}/role-ceiling` |
| `org.rights:read` | inherited from `org_viewer` | `GET /api/orgs/me/grantable`<br>`GET /api/orgs/{id}/rights`<br>`GET /api/orgs/{id}/role-ceiling` |
| `org.suspension:administer` | own | `POST /api/orgs/suspensions/{sid}/lift`<br>`POST /api/orgs/{id}/suspend` |
| `org.tree:read` | inherited from `org_viewer` | `GET /api/orgs/me`<br>`GET /api/orgs/me/descendants`<br>`GET /api/orgs/me/path`<br>`GET /api/orgs/me/tree`<br>`GET /api/orgs/{id}`<br>`GET /api/orgs/{id}/descendants`<br>`GET /api/orgs/{id}/path` |
| `org.user:administer` | own | `POST /api/orgs/users/{id}/resend-activation` |
| `org.user:create` | own | `POST /api/orgs/me/users` |
| `org.user:delete` | own | `POST /api/orgs/users/{id}/deactivate` |
| `org.user:read` | inherited from `org_viewer` | `GET /api/orgs/me/users`<br>`GET /api/orgs/{id}/members` |
| `rbac.assignment:administer` | own | `DELETE /api/admin/rbac/assignments/{id}`<br>`POST /api/orgs/{id}/role-assignments/{assignmentId}/revoke`<br>`DELETE /rbac/admin/assignments/{id}` |
| `rbac.assignment:authorize` | own | `POST /api/orgs/{id}/role-approvals/{assignmentId}`<br>`GET /rbac/admin/approvals`<br>`POST /rbac/admin/approvals/{id}/approve`<br>`POST /rbac/admin/approvals/{id}/reject` |
| `rbac.assignment:create` | own | `POST /api/admin/rbac/assignments`<br>`POST /api/orgs/{id}/role-assignments`<br>`POST /rbac/admin/assignments` |
| `rbac.assignment:read` | own | `GET /api/admin/rbac/assignments`<br>`GET /api/orgs/{id}/role-approvals`<br>`GET /api/orgs/{id}/role-assignments`<br>`GET /rbac/admin/assignments`<br>`GET /rbac/admin/assignments/{id}`<br>`GET /rbac/admin/holders` |
| `rbac.grant:administer` | own | `DELETE /rbac/admin/grants/{id}` |
| `rbac.grant:create` | own | `POST /rbac/admin/grants` |
| `rbac.grant:read` | own | `GET /rbac/admin/grants` |
| `rbac.review:administer` | own | `POST /rbac/admin/reviews/{id}/items/{assignmentId}` |
| `rbac.review:read` | own | `GET /rbac/admin/reviews/{id}` |
| `rbac.role:create` | own | `POST /api/orgs/{id}/roles`<br>`POST /rbac/admin/roles` |
| `rbac.role:delete` | own | `DELETE /api/orgs/{id}/roles/{name}`<br>`DELETE /rbac/admin/roles/{name}` |
| `rbac.role:modify` | own | `PUT /api/admin/rbac/roles/{name}`<br>`PUT /api/orgs/{id}/roles/{name}`<br>`PUT /rbac/admin/roles/{name}` |
| `rbac.role:read` | own | `GET /api/admin/rbac/roles`<br>`GET /api/admin/rbac/roles/{name}`<br>`GET /api/orgs/{id}/roles`<br>`GET /rbac/admin/export/role-pack`<br>`GET /rbac/admin/resources`<br>`GET /rbac/admin/roles`<br>`GET /rbac/admin/roles/{name}` |
| `supplier.account:read` | inherited from `org_viewer` | `GET /api/supplier/payments`<br>`GET /api/supplier/profile` |
| `supplier.bank_account:delete` | inherited from `org_staff` | `POST /api/supplier/bank-accounts/{id}/revoke` |
| `supplier.bank_account:modify` | inherited from `org_staff` | `POST /api/supplier/bank-accounts/change` |
| `supplier.bank_account:read` | inherited from `org_viewer` | `GET /api/supplier/bank-accounts` |
| `supplier.catalogue:create` | inherited from `org_staff` | `POST /api/supplier/catalogue` |
| `supplier.catalogue:delete` | inherited from `org_staff` | `POST /api/supplier/catalogue/{id}/retire` |
| `supplier.catalogue:read` | inherited from `org_viewer` | `GET /api/supplier/catalogue` |
| `supplier.invoice:create` | inherited from `org_staff` | `POST /api/supplier/orders/{id}/invoice` |
| `supplier.invoice:read` | inherited from `org_viewer` | `GET /api/supplier/invoices`<br>`GET /api/supplier/invoices/{id}` |
| `supplier.order:read` | inherited from `org_viewer` | `GET /api/supplier/orders`<br>`GET /api/supplier/orders/{id}` |
| `supplier.statement:execute` | inherited from `org_staff` | `POST /api/supplier/statements` |
| `supplier.statement:export` | inherited from `org_staff` | `GET /api/supplier/statements/{id}/download` |
| `supplier.statement:read` | inherited from `org_viewer` | `GET /api/supplier/statements` |

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
| `kyc.application:read` | inherited from `org_viewer` | `GET /api/kyc/orgs`<br>`GET /api/kyc/orgs/me`<br>`GET /api/kyc/orgs/{id}`<br>`GET /api/orgs/kyc`<br>`GET /api/orgs/me/kyc`<br>`GET /api/orgs/{id}/kyc` |
| `kyc.form:read` | inherited from `org_viewer` | `GET /api/kyc/lists/{code}`<br>`GET /api/kyc/schema`<br>`GET /api/kyc/schema/{orgId}` |
| `lender.advance:read` | inherited from `org_viewer` | `GET /api/lender/advances`<br>`GET /api/lender/consents` |
| `lender.assessment:execute` | own | `POST /api/lender/assess` |
| `lender.assessment:read` | inherited from `org_viewer` | `GET /api/lender/assessments`<br>`GET /api/lender/records/{merchantId}` |
| `lender.collection:read` | inherited from `org_viewer` | `GET /api/lender/collections` |
| `lender.portfolio:execute` | own | `POST /api/lender/portfolio/snapshot` |
| `lender.portfolio:read` | inherited from `org_viewer` | `GET /api/lender/portfolio` |
| `merchant.account:read` | inherited from `org_viewer` | `GET /api/merchant/accept-info`<br>`GET /api/merchant/balance`<br>`GET /api/merchant/balance/entries`<br>`GET /api/merchant/billing-preview`<br>`GET /api/merchant/dashboard`<br>`GET /api/merchant/devices`<br>`GET /api/merchant/settings`<br>`GET /api/merchant/speaker-log` |
| `merchant.advance:create` | own | `POST /api/merchant/advance/consent` |
| `merchant.advance:delete` | own | `POST /api/merchant/advance/consents/{id}/withdraw` |
| `merchant.advance:read` | inherited from `org_viewer` | `GET /api/merchant/advance`<br>`GET /api/merchant/advance/consents`<br>`GET /api/merchant/advance/eligibility`<br>`GET /api/merchant/lenders` |
| `merchant.advance:submit` | own | `POST /api/merchant/advance/request` |
| `merchant.inventory:create` | own | `POST /api/merchant/inventory` |
| `merchant.inventory:execute` | own | `POST /api/merchant/inventory/{itemId}/reorder` |
| `merchant.inventory:modify` | own | `POST /api/merchant/inventory/adjust` |
| `merchant.inventory:read` | inherited from `org_viewer` | `GET /api/merchant/catalogue`<br>`GET /api/merchant/inventory`<br>`GET /api/merchant/inventory/alerts`<br>`GET /api/merchant/inventory/rules` |
| `merchant.invoice:read` | inherited from `org_viewer` | `GET /api/merchant/invoices`<br>`GET /api/merchant/invoices/{id}` |
| `merchant.khata:create` | own | `POST /api/merchant/khata`<br>`POST /api/merchant/khata/{id}/entries` |
| `merchant.khata:execute` | own | `POST /api/merchant/khata/{id}/remind` |
| `merchant.khata:read` | inherited from `org_viewer` | `GET /api/merchant/khata`<br>`GET /api/merchant/khata/{id}/entries` |
| `merchant.order:create` | own | `POST /api/merchant/orders` |
| `merchant.order:read` | inherited from `org_viewer` | `GET /api/merchant/orders`<br>`GET /api/merchant/orders/{id}` |
| `merchant.payment:read` | inherited from `org_viewer` | `GET /api/merchant/payments`<br>`GET /api/merchant/payments/{id}` |
| `merchant.statement:execute` | own | `POST /api/merchant/statements` |
| `merchant.statement:export` | own | `GET /api/merchant/statements/{id}/download` |
| `merchant.statement:read` | inherited from `org_viewer` | `GET /api/merchant/statements` |
| `merchant.tab:create` | own | `POST /api/merchant/tabs`<br>`POST /api/merchant/tabs/{id}/entries` |
| `merchant.tab:execute` | own | `POST /api/merchant/tabs/{id}/remind`<br>`POST /api/merchant/tabs/{id}/settle` |
| `merchant.tab:read` | inherited from `org_viewer` | `GET /api/merchant/tabs`<br>`GET /api/merchant/tabs/{id}/entries` |
| `merchant.withdrawal:read` | inherited from `org_viewer` | `GET /api/merchant/withdrawals` |
| `org.onboarding:read` | inherited from `org_viewer` | `GET /api/orgs/onboarding` |
| `org.rights:read` | inherited from `org_viewer` | `GET /api/orgs/me/grantable`<br>`GET /api/orgs/{id}/rights`<br>`GET /api/orgs/{id}/role-ceiling` |
| `org.tree:read` | inherited from `org_viewer` | `GET /api/orgs/me`<br>`GET /api/orgs/me/descendants`<br>`GET /api/orgs/me/path`<br>`GET /api/orgs/me/tree`<br>`GET /api/orgs/{id}`<br>`GET /api/orgs/{id}/descendants`<br>`GET /api/orgs/{id}/path` |
| `org.user:read` | inherited from `org_viewer` | `GET /api/orgs/me/users`<br>`GET /api/orgs/{id}/members` |
| `supplier.account:read` | inherited from `org_viewer` | `GET /api/supplier/payments`<br>`GET /api/supplier/profile` |
| `supplier.bank_account:delete` | own | `POST /api/supplier/bank-accounts/{id}/revoke` |
| `supplier.bank_account:modify` | own | `POST /api/supplier/bank-accounts/change` |
| `supplier.bank_account:read` | inherited from `org_viewer` | `GET /api/supplier/bank-accounts` |
| `supplier.catalogue:create` | own | `POST /api/supplier/catalogue` |
| `supplier.catalogue:delete` | own | `POST /api/supplier/catalogue/{id}/retire` |
| `supplier.catalogue:read` | inherited from `org_viewer` | `GET /api/supplier/catalogue` |
| `supplier.invoice:create` | own | `POST /api/supplier/orders/{id}/invoice` |
| `supplier.invoice:read` | inherited from `org_viewer` | `GET /api/supplier/invoices`<br>`GET /api/supplier/invoices/{id}` |
| `supplier.order:read` | inherited from `org_viewer` | `GET /api/supplier/orders`<br>`GET /api/supplier/orders/{id}` |
| `supplier.statement:execute` | own | `POST /api/supplier/statements` |
| `supplier.statement:export` | own | `GET /api/supplier/statements/{id}/download` |
| `supplier.statement:read` | inherited from `org_viewer` | `GET /api/supplier/statements` |

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
| `kyc.application:read` | own | `GET /api/kyc/orgs`<br>`GET /api/kyc/orgs/me`<br>`GET /api/kyc/orgs/{id}`<br>`GET /api/orgs/kyc`<br>`GET /api/orgs/me/kyc`<br>`GET /api/orgs/{id}/kyc` |
| `kyc.form:read` | own | `GET /api/kyc/lists/{code}`<br>`GET /api/kyc/schema`<br>`GET /api/kyc/schema/{orgId}` |
| `lender.advance:read` | own | `GET /api/lender/advances`<br>`GET /api/lender/consents` |
| `lender.assessment:read` | own | `GET /api/lender/assessments`<br>`GET /api/lender/records/{merchantId}` |
| `lender.collection:read` | own | `GET /api/lender/collections` |
| `lender.portfolio:read` | own | `GET /api/lender/portfolio` |
| `merchant.account:read` | own | `GET /api/merchant/accept-info`<br>`GET /api/merchant/balance`<br>`GET /api/merchant/balance/entries`<br>`GET /api/merchant/billing-preview`<br>`GET /api/merchant/dashboard`<br>`GET /api/merchant/devices`<br>`GET /api/merchant/settings`<br>`GET /api/merchant/speaker-log` |
| `merchant.advance:read` | own | `GET /api/merchant/advance`<br>`GET /api/merchant/advance/consents`<br>`GET /api/merchant/advance/eligibility`<br>`GET /api/merchant/lenders` |
| `merchant.inventory:read` | own | `GET /api/merchant/catalogue`<br>`GET /api/merchant/inventory`<br>`GET /api/merchant/inventory/alerts`<br>`GET /api/merchant/inventory/rules` |
| `merchant.invoice:read` | own | `GET /api/merchant/invoices`<br>`GET /api/merchant/invoices/{id}` |
| `merchant.khata:read` | own | `GET /api/merchant/khata`<br>`GET /api/merchant/khata/{id}/entries` |
| `merchant.order:read` | own | `GET /api/merchant/orders`<br>`GET /api/merchant/orders/{id}` |
| `merchant.payment:read` | own | `GET /api/merchant/payments`<br>`GET /api/merchant/payments/{id}` |
| `merchant.statement:read` | own | `GET /api/merchant/statements` |
| `merchant.tab:read` | own | `GET /api/merchant/tabs`<br>`GET /api/merchant/tabs/{id}/entries` |
| `merchant.withdrawal:read` | own | `GET /api/merchant/withdrawals` |
| `org.onboarding:read` | own | `GET /api/orgs/onboarding` |
| `org.rights:read` | own | `GET /api/orgs/me/grantable`<br>`GET /api/orgs/{id}/rights`<br>`GET /api/orgs/{id}/role-ceiling` |
| `org.tree:read` | own | `GET /api/orgs/me`<br>`GET /api/orgs/me/descendants`<br>`GET /api/orgs/me/path`<br>`GET /api/orgs/me/tree`<br>`GET /api/orgs/{id}`<br>`GET /api/orgs/{id}/descendants`<br>`GET /api/orgs/{id}/path` |
| `org.user:read` | own | `GET /api/orgs/me/users`<br>`GET /api/orgs/{id}/members` |
| `supplier.account:read` | own | `GET /api/supplier/payments`<br>`GET /api/supplier/profile` |
| `supplier.bank_account:read` | own | `GET /api/supplier/bank-accounts` |
| `supplier.catalogue:read` | own | `GET /api/supplier/catalogue` |
| `supplier.invoice:read` | own | `GET /api/supplier/invoices`<br>`GET /api/supplier/invoices/{id}` |
| `supplier.order:read` | own | `GET /api/supplier/orders`<br>`GET /api/supplier/orders/{id}` |
| `supplier.statement:read` | own | `GET /api/supplier/statements` |

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
| `admin.access:read` | all rows | `GET /api/admin/access` |
| `admin.aml_alert:read` | all rows | `GET /api/admin/compliance/aml-alerts` |
| `admin.api_client:read` | all rows | `GET /api/admin/api-clients` |
| `admin.config:read` | all rows | `GET /api/admin/limits`<br>`GET /api/admin/rules`<br>`GET /api/admin/settings`<br>`GET /api/admin/tariffs`<br>`GET /api/admin/templates` |
| `admin.connector:read` | all rows | `GET /api/admin/connectors` |
| `admin.dsar:read` | all rows | `GET /api/admin/compliance/dsars` |
| `admin.platform_ops:read` | all rows | `GET /api/admin/backups`<br>`GET /api/admin/deployments`<br>`GET /api/admin/health`<br>`GET /api/admin/restore-tests`<br>`GET /api/admin/sim/outbox` |
| `admin.sar:read` | all rows | `GET /api/admin/compliance/sars` |
| `admin.simulation:read` | all rows | `GET /api/admin/scenarios`<br>`GET /api/admin/sim/controls` |
| `kyc.application:read` | all rows | `GET /api/kyc/orgs`<br>`GET /api/kyc/orgs/me`<br>`GET /api/kyc/orgs/{id}`<br>`GET /api/orgs/kyc`<br>`GET /api/orgs/me/kyc`<br>`GET /api/orgs/{id}/kyc` |
| `kyc.form:read` | all rows | `GET /api/kyc/lists/{code}`<br>`GET /api/kyc/schema`<br>`GET /api/kyc/schema/{orgId}` |
| `kyc.task:read` | all rows | `GET /api/kyc/tasks` |
| `lender.advance:read` | all rows | `GET /api/lender/advances`<br>`GET /api/lender/consents` |
| `lender.assessment:read` | all rows | `GET /api/lender/assessments`<br>`GET /api/lender/records/{merchantId}` |
| `lender.collection:read` | all rows | `GET /api/lender/collections` |
| `lender.portfolio:read` | all rows | `GET /api/lender/portfolio` |
| `merchant.account:read` | all rows | `GET /api/merchant/accept-info`<br>`GET /api/merchant/balance`<br>`GET /api/merchant/balance/entries`<br>`GET /api/merchant/billing-preview`<br>`GET /api/merchant/dashboard`<br>`GET /api/merchant/devices`<br>`GET /api/merchant/settings`<br>`GET /api/merchant/speaker-log` |
| `merchant.advance:read` | all rows | `GET /api/merchant/advance`<br>`GET /api/merchant/advance/consents`<br>`GET /api/merchant/advance/eligibility`<br>`GET /api/merchant/lenders` |
| `merchant.inventory:read` | all rows | `GET /api/merchant/catalogue`<br>`GET /api/merchant/inventory`<br>`GET /api/merchant/inventory/alerts`<br>`GET /api/merchant/inventory/rules` |
| `merchant.invoice:read` | all rows | `GET /api/merchant/invoices`<br>`GET /api/merchant/invoices/{id}` |
| `merchant.khata:read` | all rows | `GET /api/merchant/khata`<br>`GET /api/merchant/khata/{id}/entries` |
| `merchant.order:read` | all rows | `GET /api/merchant/orders`<br>`GET /api/merchant/orders/{id}` |
| `merchant.payment:read` | all rows | `GET /api/merchant/payments`<br>`GET /api/merchant/payments/{id}` |
| `merchant.statement:read` | all rows | `GET /api/merchant/statements` |
| `merchant.tab:read` | all rows | `GET /api/merchant/tabs`<br>`GET /api/merchant/tabs/{id}/entries` |
| `merchant.withdrawal:read` | all rows | `GET /api/merchant/withdrawals` |
| `ops.audit:read` | all rows | `GET /api/ops/audit` |
| `ops.bank_account_approval:read` | all rows | `GET /api/ops/approvals/bank-accounts` |
| `ops.complaint:read` | all rows | `GET /api/ops/complaints` |
| `ops.dispute:read` | all rows | `GET /api/ops/disputes` |
| `ops.incident:read` | all rows | `GET /api/ops/incidents` |
| `ops.kyc_check:read` | all rows | `GET /api/ops/kyc/{entityType}/{entityId}` |
| `ops.merchant_approval:read` | all rows | `GET /api/ops/approvals/merchants`<br>`GET /api/ops/approvals/merchants/all` |
| `ops.onboarding_request:read` | all rows | `GET /api/ops/onboarding-requests` |
| `ops.recon:read` | all rows | `GET /api/ops/recon/runs`<br>`GET /api/ops/recon/runs/{id}/breaks` |
| `ops.report:read` | all rows | `GET /api/ops/payments`<br>`GET /api/ops/reports/fees`<br>`GET /api/ops/reports/settlement` |
| `ops.supplier_approval:read` | all rows | `GET /api/ops/approvals/suppliers`<br>`GET /api/ops/approvals/suppliers/all` |
| `ops.suspension:read` | all rows | `GET /api/ops/suspensions` |
| `org.onboarding:read` | all rows | `GET /api/orgs/onboarding` |
| `org.rights:read` | all rows | `GET /api/orgs/me/grantable`<br>`GET /api/orgs/{id}/rights`<br>`GET /api/orgs/{id}/role-ceiling` |
| `org.tree:read` | all rows | `GET /api/orgs/me`<br>`GET /api/orgs/me/descendants`<br>`GET /api/orgs/me/path`<br>`GET /api/orgs/me/tree`<br>`GET /api/orgs/{id}`<br>`GET /api/orgs/{id}/descendants`<br>`GET /api/orgs/{id}/path` |
| `org.user:read` | all rows | `GET /api/orgs/me/users`<br>`GET /api/orgs/{id}/members` |
| `rbac.assignment:read` | all rows | `GET /api/admin/rbac/assignments`<br>`GET /api/orgs/{id}/role-approvals`<br>`GET /api/orgs/{id}/role-assignments`<br>`GET /rbac/admin/assignments`<br>`GET /rbac/admin/assignments/{id}`<br>`GET /rbac/admin/holders` |
| `rbac.audit:read` | own | `GET /rbac/admin/audit`<br>`GET /rbac/admin/decisions/{id}` |
| `rbac.grant:read` | all rows | `GET /rbac/admin/grants` |
| `rbac.review:read` | all rows | `GET /rbac/admin/reviews/{id}` |
| `rbac.role:read` | all rows | `GET /api/admin/rbac/roles`<br>`GET /api/admin/rbac/roles/{name}`<br>`GET /api/orgs/{id}/roles`<br>`GET /rbac/admin/export/role-pack`<br>`GET /rbac/admin/resources`<br>`GET /rbac/admin/roles`<br>`GET /rbac/admin/roles/{name}` |
| `rbac.user:read` | all rows | `GET /api/admin/rbac/users` |
| `supplier.account:read` | all rows | `GET /api/supplier/payments`<br>`GET /api/supplier/profile` |
| `supplier.bank_account:read` | all rows | `GET /api/supplier/bank-accounts` |
| `supplier.catalogue:read` | all rows | `GET /api/supplier/catalogue` |
| `supplier.invoice:read` | all rows | `GET /api/supplier/invoices`<br>`GET /api/supplier/invoices/{id}` |
| `supplier.order:read` | all rows | `GET /api/supplier/orders`<br>`GET /api/supplier/orders/{id}` |
| `supplier.statement:read` | all rows | `GET /api/supplier/statements` |

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
| `admin.access:administer` | own | `POST /api/admin/access/{userId}/revoke` |
| `admin.access:read` | all rows | `GET /api/admin/access` |
| `admin.aml_alert:read` | all rows | `GET /api/admin/compliance/aml-alerts` |
| `admin.api_client:read` | all rows | `GET /api/admin/api-clients` |
| `admin.config:read` | all rows | `GET /api/admin/limits`<br>`GET /api/admin/rules`<br>`GET /api/admin/settings`<br>`GET /api/admin/tariffs`<br>`GET /api/admin/templates` |
| `admin.connector:read` | all rows | `GET /api/admin/connectors` |
| `admin.dsar:read` | all rows | `GET /api/admin/compliance/dsars` |
| `admin.platform_ops:read` | all rows | `GET /api/admin/backups`<br>`GET /api/admin/deployments`<br>`GET /api/admin/health`<br>`GET /api/admin/restore-tests`<br>`GET /api/admin/sim/outbox` |
| `admin.sar:read` | all rows | `GET /api/admin/compliance/sars` |
| `admin.simulation:read` | all rows | `GET /api/admin/scenarios`<br>`GET /api/admin/sim/controls` |
| `kyc.application:read` | all rows | `GET /api/kyc/orgs`<br>`GET /api/kyc/orgs/me`<br>`GET /api/kyc/orgs/{id}`<br>`GET /api/orgs/kyc`<br>`GET /api/orgs/me/kyc`<br>`GET /api/orgs/{id}/kyc` |
| `kyc.form:read` | all rows | `GET /api/kyc/lists/{code}`<br>`GET /api/kyc/schema`<br>`GET /api/kyc/schema/{orgId}` |
| `kyc.task:read` | all rows | `GET /api/kyc/tasks` |
| `lender.advance:read` | all rows | `GET /api/lender/advances`<br>`GET /api/lender/consents` |
| `lender.assessment:read` | all rows | `GET /api/lender/assessments`<br>`GET /api/lender/records/{merchantId}` |
| `lender.collection:read` | all rows | `GET /api/lender/collections` |
| `lender.portfolio:read` | all rows | `GET /api/lender/portfolio` |
| `merchant.account:read` | all rows | `GET /api/merchant/accept-info`<br>`GET /api/merchant/balance`<br>`GET /api/merchant/balance/entries`<br>`GET /api/merchant/billing-preview`<br>`GET /api/merchant/dashboard`<br>`GET /api/merchant/devices`<br>`GET /api/merchant/settings`<br>`GET /api/merchant/speaker-log` |
| `merchant.advance:read` | all rows | `GET /api/merchant/advance`<br>`GET /api/merchant/advance/consents`<br>`GET /api/merchant/advance/eligibility`<br>`GET /api/merchant/lenders` |
| `merchant.inventory:read` | all rows | `GET /api/merchant/catalogue`<br>`GET /api/merchant/inventory`<br>`GET /api/merchant/inventory/alerts`<br>`GET /api/merchant/inventory/rules` |
| `merchant.invoice:read` | all rows | `GET /api/merchant/invoices`<br>`GET /api/merchant/invoices/{id}` |
| `merchant.khata:read` | all rows | `GET /api/merchant/khata`<br>`GET /api/merchant/khata/{id}/entries` |
| `merchant.order:read` | all rows | `GET /api/merchant/orders`<br>`GET /api/merchant/orders/{id}` |
| `merchant.payment:read` | all rows | `GET /api/merchant/payments`<br>`GET /api/merchant/payments/{id}` |
| `merchant.statement:read` | all rows | `GET /api/merchant/statements` |
| `merchant.tab:read` | all rows | `GET /api/merchant/tabs`<br>`GET /api/merchant/tabs/{id}/entries` |
| `merchant.withdrawal:read` | all rows | `GET /api/merchant/withdrawals` |
| `ops.audit:read` | all rows | `GET /api/ops/audit` |
| `ops.bank_account_approval:read` | all rows | `GET /api/ops/approvals/bank-accounts` |
| `ops.complaint:read` | all rows | `GET /api/ops/complaints` |
| `ops.dispute:read` | all rows | `GET /api/ops/disputes` |
| `ops.incident:read` | all rows | `GET /api/ops/incidents` |
| `ops.kyc_check:read` | all rows | `GET /api/ops/kyc/{entityType}/{entityId}` |
| `ops.merchant_approval:read` | all rows | `GET /api/ops/approvals/merchants`<br>`GET /api/ops/approvals/merchants/all` |
| `ops.onboarding_request:read` | all rows | `GET /api/ops/onboarding-requests` |
| `ops.recon:read` | all rows | `GET /api/ops/recon/runs`<br>`GET /api/ops/recon/runs/{id}/breaks` |
| `ops.report:read` | all rows | `GET /api/ops/payments`<br>`GET /api/ops/reports/fees`<br>`GET /api/ops/reports/settlement` |
| `ops.supplier_approval:read` | all rows | `GET /api/ops/approvals/suppliers`<br>`GET /api/ops/approvals/suppliers/all` |
| `ops.suspension:administer` | own | `POST /api/ops/suspend`<br>`POST /api/ops/suspensions/{id}/lift` |
| `ops.suspension:read` | all rows | `GET /api/ops/suspensions` |
| `org.onboarding:read` | all rows | `GET /api/orgs/onboarding` |
| `org.rights:read` | all rows | `GET /api/orgs/me/grantable`<br>`GET /api/orgs/{id}/rights`<br>`GET /api/orgs/{id}/role-ceiling` |
| `org.suspension:administer` | own | `POST /api/orgs/suspensions/{sid}/lift`<br>`POST /api/orgs/{id}/suspend` |
| `org.tree:read` | all rows | `GET /api/orgs/me`<br>`GET /api/orgs/me/descendants`<br>`GET /api/orgs/me/path`<br>`GET /api/orgs/me/tree`<br>`GET /api/orgs/{id}`<br>`GET /api/orgs/{id}/descendants`<br>`GET /api/orgs/{id}/path` |
| `org.user:administer` | own | `POST /api/orgs/users/{id}/resend-activation` |
| `org.user:create` | own | `POST /api/orgs/me/users` |
| `org.user:delete` | own | `POST /api/orgs/users/{id}/deactivate` |
| `org.user:read` | all rows | `GET /api/orgs/me/users`<br>`GET /api/orgs/{id}/members` |
| `rbac.assignment:administer` | own | `DELETE /api/admin/rbac/assignments/{id}`<br>`POST /api/orgs/{id}/role-assignments/{assignmentId}/revoke`<br>`DELETE /rbac/admin/assignments/{id}` |
| `rbac.assignment:create` | own | `POST /api/admin/rbac/assignments`<br>`POST /api/orgs/{id}/role-assignments`<br>`POST /rbac/admin/assignments` |
| `rbac.assignment:read` | all rows | `GET /api/admin/rbac/assignments`<br>`GET /api/orgs/{id}/role-approvals`<br>`GET /api/orgs/{id}/role-assignments`<br>`GET /rbac/admin/assignments`<br>`GET /rbac/admin/assignments/{id}`<br>`GET /rbac/admin/holders` |
| `rbac.audit:read` | all rows | `GET /rbac/admin/audit`<br>`GET /rbac/admin/decisions/{id}` |
| `rbac.grant:read` | all rows | `GET /rbac/admin/grants` |
| `rbac.review:read` | all rows | `GET /rbac/admin/reviews/{id}` |
| `rbac.role:read` | all rows | `GET /api/admin/rbac/roles`<br>`GET /api/admin/rbac/roles/{name}`<br>`GET /api/orgs/{id}/roles`<br>`GET /rbac/admin/export/role-pack`<br>`GET /rbac/admin/resources`<br>`GET /rbac/admin/roles`<br>`GET /rbac/admin/roles/{name}` |
| `rbac.user:read` | all rows | `GET /api/admin/rbac/users` |
| `supplier.account:read` | all rows | `GET /api/supplier/payments`<br>`GET /api/supplier/profile` |
| `supplier.bank_account:read` | all rows | `GET /api/supplier/bank-accounts` |
| `supplier.catalogue:read` | all rows | `GET /api/supplier/catalogue` |
| `supplier.invoice:read` | all rows | `GET /api/supplier/invoices`<br>`GET /api/supplier/invoices/{id}` |
| `supplier.order:read` | all rows | `GET /api/supplier/orders`<br>`GET /api/supplier/orders/{id}` |
| `supplier.statement:read` | all rows | `GET /api/supplier/statements` |

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
| `admin.access:read` | inherited from `platform_auditor` | `GET /api/admin/access` |
| `admin.aml_alert:read` | inherited from `platform_auditor` | `GET /api/admin/compliance/aml-alerts` |
| `admin.api_client:read` | inherited from `platform_auditor` | `GET /api/admin/api-clients` |
| `admin.config:read` | inherited from `platform_auditor` | `GET /api/admin/limits`<br>`GET /api/admin/rules`<br>`GET /api/admin/settings`<br>`GET /api/admin/tariffs`<br>`GET /api/admin/templates` |
| `admin.connector:read` | inherited from `platform_auditor` | `GET /api/admin/connectors` |
| `admin.dsar:create` | own | `POST /api/admin/compliance/dsars` |
| `admin.dsar:export` | own | `POST /api/admin/compliance/dsars/{id}/export` |
| `admin.dsar:read` | inherited from `platform_auditor` | `GET /api/admin/compliance/dsars` |
| `admin.platform_ops:read` | inherited from `platform_auditor` | `GET /api/admin/backups`<br>`GET /api/admin/deployments`<br>`GET /api/admin/health`<br>`GET /api/admin/restore-tests`<br>`GET /api/admin/sim/outbox` |
| `admin.sar:create` | own | `POST /api/admin/compliance/sars` |
| `admin.sar:read` | inherited from `platform_auditor` | `GET /api/admin/compliance/sars` |
| `admin.sar:submit` | own | `POST /api/admin/compliance/sars/{id}/submit` |
| `admin.simulation:read` | inherited from `platform_auditor` | `GET /api/admin/scenarios`<br>`GET /api/admin/sim/controls` |
| `kyc.application:read` | inherited from `platform_auditor` | `GET /api/kyc/orgs`<br>`GET /api/kyc/orgs/me`<br>`GET /api/kyc/orgs/{id}`<br>`GET /api/orgs/kyc`<br>`GET /api/orgs/me/kyc`<br>`GET /api/orgs/{id}/kyc` |
| `kyc.form:read` | inherited from `platform_auditor` | `GET /api/kyc/lists/{code}`<br>`GET /api/kyc/schema`<br>`GET /api/kyc/schema/{orgId}` |
| `kyc.task:authorize` | own | `POST /api/kyc/tasks/{taskId}/decision` |
| `kyc.task:read` | inherited from `platform_auditor` | `GET /api/kyc/tasks` |
| `lender.advance:read` | inherited from `platform_auditor` | `GET /api/lender/advances`<br>`GET /api/lender/consents` |
| `lender.assessment:read` | inherited from `platform_auditor` | `GET /api/lender/assessments`<br>`GET /api/lender/records/{merchantId}` |
| `lender.collection:read` | inherited from `platform_auditor` | `GET /api/lender/collections` |
| `lender.portfolio:read` | inherited from `platform_auditor` | `GET /api/lender/portfolio` |
| `merchant.account:read` | inherited from `platform_auditor` | `GET /api/merchant/accept-info`<br>`GET /api/merchant/balance`<br>`GET /api/merchant/balance/entries`<br>`GET /api/merchant/billing-preview`<br>`GET /api/merchant/dashboard`<br>`GET /api/merchant/devices`<br>`GET /api/merchant/settings`<br>`GET /api/merchant/speaker-log` |
| `merchant.advance:read` | inherited from `platform_auditor` | `GET /api/merchant/advance`<br>`GET /api/merchant/advance/consents`<br>`GET /api/merchant/advance/eligibility`<br>`GET /api/merchant/lenders` |
| `merchant.inventory:read` | inherited from `platform_auditor` | `GET /api/merchant/catalogue`<br>`GET /api/merchant/inventory`<br>`GET /api/merchant/inventory/alerts`<br>`GET /api/merchant/inventory/rules` |
| `merchant.invoice:read` | inherited from `platform_auditor` | `GET /api/merchant/invoices`<br>`GET /api/merchant/invoices/{id}` |
| `merchant.khata:read` | inherited from `platform_auditor` | `GET /api/merchant/khata`<br>`GET /api/merchant/khata/{id}/entries` |
| `merchant.order:read` | inherited from `platform_auditor` | `GET /api/merchant/orders`<br>`GET /api/merchant/orders/{id}` |
| `merchant.payment:read` | inherited from `platform_auditor` | `GET /api/merchant/payments`<br>`GET /api/merchant/payments/{id}` |
| `merchant.statement:read` | inherited from `platform_auditor` | `GET /api/merchant/statements` |
| `merchant.tab:read` | inherited from `platform_auditor` | `GET /api/merchant/tabs`<br>`GET /api/merchant/tabs/{id}/entries` |
| `merchant.withdrawal:read` | inherited from `platform_auditor` | `GET /api/merchant/withdrawals` |
| `ops.audit:read` | inherited from `platform_auditor` | `GET /api/ops/audit` |
| `ops.bank_account_approval:read` | inherited from `platform_auditor` | `GET /api/ops/approvals/bank-accounts` |
| `ops.complaint:read` | inherited from `platform_auditor` | `GET /api/ops/complaints` |
| `ops.dispute:read` | inherited from `platform_auditor` | `GET /api/ops/disputes` |
| `ops.incident:read` | inherited from `platform_auditor` | `GET /api/ops/incidents` |
| `ops.kyc_check:read` | inherited from `platform_auditor` | `GET /api/ops/kyc/{entityType}/{entityId}` |
| `ops.merchant_approval:read` | inherited from `platform_auditor` | `GET /api/ops/approvals/merchants`<br>`GET /api/ops/approvals/merchants/all` |
| `ops.onboarding_request:read` | inherited from `platform_auditor` | `GET /api/ops/onboarding-requests` |
| `ops.recon:read` | inherited from `platform_auditor` | `GET /api/ops/recon/runs`<br>`GET /api/ops/recon/runs/{id}/breaks` |
| `ops.report:read` | inherited from `platform_auditor` | `GET /api/ops/payments`<br>`GET /api/ops/reports/fees`<br>`GET /api/ops/reports/settlement` |
| `ops.supplier_approval:read` | inherited from `platform_auditor` | `GET /api/ops/approvals/suppliers`<br>`GET /api/ops/approvals/suppliers/all` |
| `ops.suspension:read` | inherited from `platform_auditor` | `GET /api/ops/suspensions` |
| `org.onboarding:read` | inherited from `platform_auditor` | `GET /api/orgs/onboarding` |
| `org.rights:read` | inherited from `platform_auditor` | `GET /api/orgs/me/grantable`<br>`GET /api/orgs/{id}/rights`<br>`GET /api/orgs/{id}/role-ceiling` |
| `org.tree:read` | inherited from `platform_auditor` | `GET /api/orgs/me`<br>`GET /api/orgs/me/descendants`<br>`GET /api/orgs/me/path`<br>`GET /api/orgs/me/tree`<br>`GET /api/orgs/{id}`<br>`GET /api/orgs/{id}/descendants`<br>`GET /api/orgs/{id}/path` |
| `org.user:read` | inherited from `platform_auditor` | `GET /api/orgs/me/users`<br>`GET /api/orgs/{id}/members` |
| `rbac.assignment:read` | inherited from `platform_auditor` | `GET /api/admin/rbac/assignments`<br>`GET /api/orgs/{id}/role-approvals`<br>`GET /api/orgs/{id}/role-assignments`<br>`GET /rbac/admin/assignments`<br>`GET /rbac/admin/assignments/{id}`<br>`GET /rbac/admin/holders` |
| `rbac.audit:read` | inherited from `platform_auditor` | `GET /rbac/admin/audit`<br>`GET /rbac/admin/decisions/{id}` |
| `rbac.grant:read` | inherited from `platform_auditor` | `GET /rbac/admin/grants` |
| `rbac.review:read` | inherited from `platform_auditor` | `GET /rbac/admin/reviews/{id}` |
| `rbac.role:read` | inherited from `platform_auditor` | `GET /api/admin/rbac/roles`<br>`GET /api/admin/rbac/roles/{name}`<br>`GET /api/orgs/{id}/roles`<br>`GET /rbac/admin/export/role-pack`<br>`GET /rbac/admin/resources`<br>`GET /rbac/admin/roles`<br>`GET /rbac/admin/roles/{name}` |
| `rbac.user:read` | inherited from `platform_auditor` | `GET /api/admin/rbac/users` |
| `supplier.account:read` | inherited from `platform_auditor` | `GET /api/supplier/payments`<br>`GET /api/supplier/profile` |
| `supplier.bank_account:read` | inherited from `platform_auditor` | `GET /api/supplier/bank-accounts` |
| `supplier.catalogue:read` | inherited from `platform_auditor` | `GET /api/supplier/catalogue` |
| `supplier.invoice:read` | inherited from `platform_auditor` | `GET /api/supplier/invoices`<br>`GET /api/supplier/invoices/{id}` |
| `supplier.order:read` | inherited from `platform_auditor` | `GET /api/supplier/orders`<br>`GET /api/supplier/orders/{id}` |
| `supplier.statement:read` | inherited from `platform_auditor` | `GET /api/supplier/statements` |

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
| `admin.config:modify` | own | `POST /api/admin/limits`<br>`POST /api/admin/rules/{code}`<br>`POST /api/admin/settings`<br>`POST /api/admin/tariffs`<br>`POST /api/admin/templates` |
| `admin.config:read` | own | `GET /api/admin/limits`<br>`GET /api/admin/rules`<br>`GET /api/admin/settings`<br>`GET /api/admin/tariffs`<br>`GET /api/admin/templates` |
| `admin.connector:administer` | own | `POST /api/admin/connectors/{id}/rotate` |
| `admin.connector:execute` | own | `POST /api/admin/connectors/{id}/test` |
| `admin.connector:modify` | own | `POST /api/admin/connectors` |
| `admin.connector:read` | own | `GET /api/admin/connectors` |
| `admin.platform_ops:administer` | own | `POST /api/admin/deployments/rollback` |
| `admin.platform_ops:execute` | own | `POST /api/admin/backups`<br>`POST /api/admin/backups/{id}/restore-test`<br>`POST /api/admin/deployments` |
| `admin.platform_ops:read` | own | `GET /api/admin/backups`<br>`GET /api/admin/deployments`<br>`GET /api/admin/health`<br>`GET /api/admin/restore-tests`<br>`GET /api/admin/sim/outbox` |
| `admin.simulation:read` | own | `GET /api/admin/scenarios`<br>`GET /api/admin/sim/controls` |
| `ops.monitoring:execute` | own | `POST /api/ops/monitoring/touch` |

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
| `admin.access:read` | inherited from `platform_auditor` | `GET /api/admin/access` |
| `admin.aml_alert:read` | inherited from `platform_auditor` | `GET /api/admin/compliance/aml-alerts` |
| `admin.api_client:read` | inherited from `platform_auditor` | `GET /api/admin/api-clients` |
| `admin.config:read` | inherited from `platform_auditor` | `GET /api/admin/limits`<br>`GET /api/admin/rules`<br>`GET /api/admin/settings`<br>`GET /api/admin/tariffs`<br>`GET /api/admin/templates` |
| `admin.connector:read` | inherited from `platform_auditor` | `GET /api/admin/connectors` |
| `admin.dsar:read` | inherited from `platform_auditor` | `GET /api/admin/compliance/dsars` |
| `admin.platform_ops:read` | inherited from `platform_auditor` | `GET /api/admin/backups`<br>`GET /api/admin/deployments`<br>`GET /api/admin/health`<br>`GET /api/admin/restore-tests`<br>`GET /api/admin/sim/outbox` |
| `admin.sar:create` | own | `POST /api/admin/compliance/sars` |
| `admin.sar:read` | inherited from `platform_auditor` | `GET /api/admin/compliance/sars` |
| `admin.sar:submit` | own | `POST /api/admin/compliance/sars/{id}/submit` |
| `admin.simulation:read` | inherited from `platform_auditor` | `GET /api/admin/scenarios`<br>`GET /api/admin/sim/controls` |
| `kyc.application:read` | inherited from `platform_auditor` | `GET /api/kyc/orgs`<br>`GET /api/kyc/orgs/me`<br>`GET /api/kyc/orgs/{id}`<br>`GET /api/orgs/kyc`<br>`GET /api/orgs/me/kyc`<br>`GET /api/orgs/{id}/kyc` |
| `kyc.form:read` | inherited from `platform_auditor` | `GET /api/kyc/lists/{code}`<br>`GET /api/kyc/schema`<br>`GET /api/kyc/schema/{orgId}` |
| `kyc.task:authorize` | own | `POST /api/kyc/tasks/{taskId}/decision` |
| `kyc.task:read` | inherited from `platform_auditor` | `GET /api/kyc/tasks` |
| `lender.advance:read` | inherited from `platform_auditor` | `GET /api/lender/advances`<br>`GET /api/lender/consents` |
| `lender.assessment:read` | inherited from `platform_auditor` | `GET /api/lender/assessments`<br>`GET /api/lender/records/{merchantId}` |
| `lender.collection:read` | inherited from `platform_auditor` | `GET /api/lender/collections` |
| `lender.portfolio:read` | inherited from `platform_auditor` | `GET /api/lender/portfolio` |
| `merchant.account:read` | inherited from `platform_auditor` | `GET /api/merchant/accept-info`<br>`GET /api/merchant/balance`<br>`GET /api/merchant/balance/entries`<br>`GET /api/merchant/billing-preview`<br>`GET /api/merchant/dashboard`<br>`GET /api/merchant/devices`<br>`GET /api/merchant/settings`<br>`GET /api/merchant/speaker-log` |
| `merchant.advance:read` | inherited from `platform_auditor` | `GET /api/merchant/advance`<br>`GET /api/merchant/advance/consents`<br>`GET /api/merchant/advance/eligibility`<br>`GET /api/merchant/lenders` |
| `merchant.inventory:read` | inherited from `platform_auditor` | `GET /api/merchant/catalogue`<br>`GET /api/merchant/inventory`<br>`GET /api/merchant/inventory/alerts`<br>`GET /api/merchant/inventory/rules` |
| `merchant.invoice:read` | inherited from `platform_auditor` | `GET /api/merchant/invoices`<br>`GET /api/merchant/invoices/{id}` |
| `merchant.khata:read` | inherited from `platform_auditor` | `GET /api/merchant/khata`<br>`GET /api/merchant/khata/{id}/entries` |
| `merchant.order:read` | inherited from `platform_auditor` | `GET /api/merchant/orders`<br>`GET /api/merchant/orders/{id}` |
| `merchant.payment:read` | inherited from `platform_auditor` | `GET /api/merchant/payments`<br>`GET /api/merchant/payments/{id}` |
| `merchant.statement:read` | inherited from `platform_auditor` | `GET /api/merchant/statements` |
| `merchant.tab:read` | inherited from `platform_auditor` | `GET /api/merchant/tabs`<br>`GET /api/merchant/tabs/{id}/entries` |
| `merchant.withdrawal:read` | inherited from `platform_auditor` | `GET /api/merchant/withdrawals` |
| `ops.audit:read` | inherited from `platform_auditor` | `GET /api/ops/audit` |
| `ops.bank_account_approval:read` | inherited from `platform_auditor` | `GET /api/ops/approvals/bank-accounts` |
| `ops.complaint:read` | inherited from `platform_auditor` | `GET /api/ops/complaints` |
| `ops.dispute:read` | inherited from `platform_auditor` | `GET /api/ops/disputes` |
| `ops.incident:read` | inherited from `platform_auditor` | `GET /api/ops/incidents` |
| `ops.kyc_check:read` | inherited from `platform_auditor` | `GET /api/ops/kyc/{entityType}/{entityId}` |
| `ops.merchant_approval:read` | inherited from `platform_auditor` | `GET /api/ops/approvals/merchants`<br>`GET /api/ops/approvals/merchants/all` |
| `ops.onboarding_request:read` | inherited from `platform_auditor` | `GET /api/ops/onboarding-requests` |
| `ops.recon:read` | inherited from `platform_auditor` | `GET /api/ops/recon/runs`<br>`GET /api/ops/recon/runs/{id}/breaks` |
| `ops.report:read` | inherited from `platform_auditor` | `GET /api/ops/payments`<br>`GET /api/ops/reports/fees`<br>`GET /api/ops/reports/settlement` |
| `ops.supplier_approval:read` | inherited from `platform_auditor` | `GET /api/ops/approvals/suppliers`<br>`GET /api/ops/approvals/suppliers/all` |
| `ops.suspension:read` | inherited from `platform_auditor` | `GET /api/ops/suspensions` |
| `org.onboarding:read` | inherited from `platform_auditor` | `GET /api/orgs/onboarding` |
| `org.rights:read` | inherited from `platform_auditor` | `GET /api/orgs/me/grantable`<br>`GET /api/orgs/{id}/rights`<br>`GET /api/orgs/{id}/role-ceiling` |
| `org.tree:read` | inherited from `platform_auditor` | `GET /api/orgs/me`<br>`GET /api/orgs/me/descendants`<br>`GET /api/orgs/me/path`<br>`GET /api/orgs/me/tree`<br>`GET /api/orgs/{id}`<br>`GET /api/orgs/{id}/descendants`<br>`GET /api/orgs/{id}/path` |
| `org.user:read` | inherited from `platform_auditor` | `GET /api/orgs/me/users`<br>`GET /api/orgs/{id}/members` |
| `rbac.assignment:authorize` | own | `POST /api/orgs/{id}/role-approvals/{assignmentId}`<br>`GET /rbac/admin/approvals`<br>`POST /rbac/admin/approvals/{id}/approve`<br>`POST /rbac/admin/approvals/{id}/reject` |
| `rbac.assignment:read` | own | `GET /api/admin/rbac/assignments`<br>`GET /api/orgs/{id}/role-approvals`<br>`GET /api/orgs/{id}/role-assignments`<br>`GET /rbac/admin/assignments`<br>`GET /rbac/admin/assignments/{id}`<br>`GET /rbac/admin/holders` |
| `rbac.audit:read` | inherited from `platform_auditor` | `GET /rbac/admin/audit`<br>`GET /rbac/admin/decisions/{id}` |
| `rbac.grant:read` | inherited from `platform_auditor` | `GET /rbac/admin/grants` |
| `rbac.review:read` | inherited from `platform_auditor` | `GET /rbac/admin/reviews/{id}` |
| `rbac.role:read` | inherited from `platform_auditor` | `GET /api/admin/rbac/roles`<br>`GET /api/admin/rbac/roles/{name}`<br>`GET /api/orgs/{id}/roles`<br>`GET /rbac/admin/export/role-pack`<br>`GET /rbac/admin/resources`<br>`GET /rbac/admin/roles`<br>`GET /rbac/admin/roles/{name}` |
| `rbac.user:read` | inherited from `platform_auditor` | `GET /api/admin/rbac/users` |
| `supplier.account:read` | inherited from `platform_auditor` | `GET /api/supplier/payments`<br>`GET /api/supplier/profile` |
| `supplier.bank_account:read` | inherited from `platform_auditor` | `GET /api/supplier/bank-accounts` |
| `supplier.catalogue:read` | inherited from `platform_auditor` | `GET /api/supplier/catalogue` |
| `supplier.invoice:read` | inherited from `platform_auditor` | `GET /api/supplier/invoices`<br>`GET /api/supplier/invoices/{id}` |
| `supplier.order:read` | inherited from `platform_auditor` | `GET /api/supplier/orders`<br>`GET /api/supplier/orders/{id}` |
| `supplier.statement:read` | inherited from `platform_auditor` | `GET /api/supplier/statements` |

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
| `admin.access:read` | inherited from `platform_auditor` | `GET /api/admin/access` |
| `admin.aml_alert:read` | inherited from `platform_auditor` | `GET /api/admin/compliance/aml-alerts` |
| `admin.api_client:read` | inherited from `platform_auditor` | `GET /api/admin/api-clients` |
| `admin.config:read` | inherited from `platform_auditor` | `GET /api/admin/limits`<br>`GET /api/admin/rules`<br>`GET /api/admin/settings`<br>`GET /api/admin/tariffs`<br>`GET /api/admin/templates` |
| `admin.connector:read` | inherited from `platform_auditor` | `GET /api/admin/connectors` |
| `admin.dsar:read` | inherited from `platform_auditor` | `GET /api/admin/compliance/dsars` |
| `admin.platform_ops:read` | inherited from `platform_auditor` | `GET /api/admin/backups`<br>`GET /api/admin/deployments`<br>`GET /api/admin/health`<br>`GET /api/admin/restore-tests`<br>`GET /api/admin/sim/outbox` |
| `admin.sar:read` | inherited from `platform_auditor` | `GET /api/admin/compliance/sars` |
| `admin.simulation:read` | inherited from `platform_auditor` | `GET /api/admin/scenarios`<br>`GET /api/admin/sim/controls` |
| `kyc.application:read` | inherited from `platform_auditor` | `GET /api/kyc/orgs`<br>`GET /api/kyc/orgs/me`<br>`GET /api/kyc/orgs/{id}`<br>`GET /api/orgs/kyc`<br>`GET /api/orgs/me/kyc`<br>`GET /api/orgs/{id}/kyc` |
| `kyc.form:read` | inherited from `platform_auditor` | `GET /api/kyc/lists/{code}`<br>`GET /api/kyc/schema`<br>`GET /api/kyc/schema/{orgId}` |
| `kyc.task:read` | inherited from `platform_auditor` | `GET /api/kyc/tasks` |
| `lender.advance:read` | inherited from `platform_auditor` | `GET /api/lender/advances`<br>`GET /api/lender/consents` |
| `lender.assessment:read` | inherited from `platform_auditor` | `GET /api/lender/assessments`<br>`GET /api/lender/records/{merchantId}` |
| `lender.collection:read` | inherited from `platform_auditor` | `GET /api/lender/collections` |
| `lender.portfolio:read` | inherited from `platform_auditor` | `GET /api/lender/portfolio` |
| `merchant.account:read` | inherited from `platform_auditor` | `GET /api/merchant/accept-info`<br>`GET /api/merchant/balance`<br>`GET /api/merchant/balance/entries`<br>`GET /api/merchant/billing-preview`<br>`GET /api/merchant/dashboard`<br>`GET /api/merchant/devices`<br>`GET /api/merchant/settings`<br>`GET /api/merchant/speaker-log` |
| `merchant.advance:read` | inherited from `platform_auditor` | `GET /api/merchant/advance`<br>`GET /api/merchant/advance/consents`<br>`GET /api/merchant/advance/eligibility`<br>`GET /api/merchant/lenders` |
| `merchant.inventory:read` | inherited from `platform_auditor` | `GET /api/merchant/catalogue`<br>`GET /api/merchant/inventory`<br>`GET /api/merchant/inventory/alerts`<br>`GET /api/merchant/inventory/rules` |
| `merchant.invoice:read` | inherited from `platform_auditor` | `GET /api/merchant/invoices`<br>`GET /api/merchant/invoices/{id}` |
| `merchant.khata:read` | inherited from `platform_auditor` | `GET /api/merchant/khata`<br>`GET /api/merchant/khata/{id}/entries` |
| `merchant.order:read` | inherited from `platform_auditor` | `GET /api/merchant/orders`<br>`GET /api/merchant/orders/{id}` |
| `merchant.payment:read` | inherited from `platform_auditor` | `GET /api/merchant/payments`<br>`GET /api/merchant/payments/{id}` |
| `merchant.statement:read` | inherited from `platform_auditor` | `GET /api/merchant/statements` |
| `merchant.tab:read` | inherited from `platform_auditor` | `GET /api/merchant/tabs`<br>`GET /api/merchant/tabs/{id}/entries` |
| `merchant.withdrawal:read` | inherited from `platform_auditor` | `GET /api/merchant/withdrawals` |
| `ops.audit:read` | inherited from `platform_auditor` | `GET /api/ops/audit` |
| `ops.bank_account_approval:read` | inherited from `platform_auditor` | `GET /api/ops/approvals/bank-accounts` |
| `ops.complaint:create` | own | `POST /api/ops/complaints` |
| `ops.complaint:modify` | own | `POST /api/ops/complaints/{id}/assign`<br>`POST /api/ops/complaints/{id}/resolve` |
| `ops.complaint:read` | inherited from `platform_auditor` | `GET /api/ops/complaints` |
| `ops.dispute:create` | own | `POST /api/ops/disputes` |
| `ops.dispute:modify` | own | `POST /api/ops/disputes/{id}/resolve` |
| `ops.dispute:read` | inherited from `platform_auditor` | `GET /api/ops/disputes` |
| `ops.incident:create` | own | `POST /api/ops/incidents` |
| `ops.incident:modify` | own | `POST /api/ops/incidents/{id}/ack`<br>`POST /api/ops/incidents/{id}/resolve` |
| `ops.incident:read` | inherited from `platform_auditor` | `GET /api/ops/incidents` |
| `ops.kyc_check:create` | own | `POST /api/ops/kyc/{entityType}/{entityId}` |
| `ops.kyc_check:read` | inherited from `platform_auditor` | `GET /api/ops/kyc/{entityType}/{entityId}` |
| `ops.merchant_approval:read` | inherited from `platform_auditor` | `GET /api/ops/approvals/merchants`<br>`GET /api/ops/approvals/merchants/all` |
| `ops.monitoring:execute` | own | `POST /api/ops/monitoring/touch` |
| `ops.onboarding_request:read` | inherited from `platform_auditor` | `GET /api/ops/onboarding-requests` |
| `ops.recon:execute` | own | `POST /api/ops/recon/run` |
| `ops.recon:modify` | own | `POST /api/ops/recon/breaks/{id}/resolve` |
| `ops.recon:read` | inherited from `platform_auditor` | `GET /api/ops/recon/runs`<br>`GET /api/ops/recon/runs/{id}/breaks` |
| `ops.report:execute` | own | `POST /api/ops/statements/generate` |
| `ops.report:read` | inherited from `platform_auditor` | `GET /api/ops/payments`<br>`GET /api/ops/reports/fees`<br>`GET /api/ops/reports/settlement` |
| `ops.supplier_approval:read` | inherited from `platform_auditor` | `GET /api/ops/approvals/suppliers`<br>`GET /api/ops/approvals/suppliers/all` |
| `ops.suspension:administer` | own | `POST /api/ops/suspend`<br>`POST /api/ops/suspensions/{id}/lift` |
| `ops.suspension:read` | inherited from `platform_auditor` | `GET /api/ops/suspensions` |
| `org.onboarding:read` | inherited from `platform_auditor` | `GET /api/orgs/onboarding` |
| `org.rights:read` | inherited from `platform_auditor` | `GET /api/orgs/me/grantable`<br>`GET /api/orgs/{id}/rights`<br>`GET /api/orgs/{id}/role-ceiling` |
| `org.tree:read` | inherited from `platform_auditor` | `GET /api/orgs/me`<br>`GET /api/orgs/me/descendants`<br>`GET /api/orgs/me/path`<br>`GET /api/orgs/me/tree`<br>`GET /api/orgs/{id}`<br>`GET /api/orgs/{id}/descendants`<br>`GET /api/orgs/{id}/path` |
| `org.user:read` | inherited from `platform_auditor` | `GET /api/orgs/me/users`<br>`GET /api/orgs/{id}/members` |
| `rbac.assignment:read` | inherited from `platform_auditor` | `GET /api/admin/rbac/assignments`<br>`GET /api/orgs/{id}/role-approvals`<br>`GET /api/orgs/{id}/role-assignments`<br>`GET /rbac/admin/assignments`<br>`GET /rbac/admin/assignments/{id}`<br>`GET /rbac/admin/holders` |
| `rbac.audit:read` | inherited from `platform_auditor` | `GET /rbac/admin/audit`<br>`GET /rbac/admin/decisions/{id}` |
| `rbac.grant:read` | inherited from `platform_auditor` | `GET /rbac/admin/grants` |
| `rbac.review:read` | inherited from `platform_auditor` | `GET /rbac/admin/reviews/{id}` |
| `rbac.role:read` | inherited from `platform_auditor` | `GET /api/admin/rbac/roles`<br>`GET /api/admin/rbac/roles/{name}`<br>`GET /api/orgs/{id}/roles`<br>`GET /rbac/admin/export/role-pack`<br>`GET /rbac/admin/resources`<br>`GET /rbac/admin/roles`<br>`GET /rbac/admin/roles/{name}` |
| `rbac.user:read` | inherited from `platform_auditor` | `GET /api/admin/rbac/users` |
| `supplier.account:read` | inherited from `platform_auditor` | `GET /api/supplier/payments`<br>`GET /api/supplier/profile` |
| `supplier.bank_account:read` | inherited from `platform_auditor` | `GET /api/supplier/bank-accounts` |
| `supplier.catalogue:read` | inherited from `platform_auditor` | `GET /api/supplier/catalogue` |
| `supplier.invoice:read` | inherited from `platform_auditor` | `GET /api/supplier/invoices`<br>`GET /api/supplier/invoices/{id}` |
| `supplier.order:read` | inherited from `platform_auditor` | `GET /api/supplier/orders`<br>`GET /api/supplier/orders/{id}` |
| `supplier.statement:read` | inherited from `platform_auditor` | `GET /api/supplier/statements` |

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
| `admin.access:read` | inherited from `platform_auditor` | `GET /api/admin/access` |
| `admin.aml_alert:read` | inherited from `platform_auditor` | `GET /api/admin/compliance/aml-alerts` |
| `admin.api_client:read` | inherited from `platform_auditor` | `GET /api/admin/api-clients` |
| `admin.config:read` | inherited from `platform_auditor` | `GET /api/admin/limits`<br>`GET /api/admin/rules`<br>`GET /api/admin/settings`<br>`GET /api/admin/tariffs`<br>`GET /api/admin/templates` |
| `admin.connector:read` | inherited from `platform_auditor` | `GET /api/admin/connectors` |
| `admin.dsar:read` | inherited from `platform_auditor` | `GET /api/admin/compliance/dsars` |
| `admin.platform_ops:read` | inherited from `platform_auditor` | `GET /api/admin/backups`<br>`GET /api/admin/deployments`<br>`GET /api/admin/health`<br>`GET /api/admin/restore-tests`<br>`GET /api/admin/sim/outbox` |
| `admin.sar:read` | inherited from `platform_auditor` | `GET /api/admin/compliance/sars` |
| `admin.simulation:read` | inherited from `platform_auditor` | `GET /api/admin/scenarios`<br>`GET /api/admin/sim/controls` |
| `kyc.application:read` | inherited from `platform_auditor` | `GET /api/kyc/orgs`<br>`GET /api/kyc/orgs/me`<br>`GET /api/kyc/orgs/{id}`<br>`GET /api/orgs/kyc`<br>`GET /api/orgs/me/kyc`<br>`GET /api/orgs/{id}/kyc` |
| `kyc.form:read` | inherited from `platform_auditor` | `GET /api/kyc/lists/{code}`<br>`GET /api/kyc/schema`<br>`GET /api/kyc/schema/{orgId}` |
| `kyc.task:authorize` | own | `POST /api/kyc/tasks/{taskId}/decision` |
| `kyc.task:read` | inherited from `platform_auditor` | `GET /api/kyc/tasks` |
| `lender.advance:read` | inherited from `platform_auditor` | `GET /api/lender/advances`<br>`GET /api/lender/consents` |
| `lender.assessment:read` | inherited from `platform_auditor` | `GET /api/lender/assessments`<br>`GET /api/lender/records/{merchantId}` |
| `lender.collection:read` | inherited from `platform_auditor` | `GET /api/lender/collections` |
| `lender.portfolio:read` | inherited from `platform_auditor` | `GET /api/lender/portfolio` |
| `merchant.account:read` | inherited from `platform_auditor` | `GET /api/merchant/accept-info`<br>`GET /api/merchant/balance`<br>`GET /api/merchant/balance/entries`<br>`GET /api/merchant/billing-preview`<br>`GET /api/merchant/dashboard`<br>`GET /api/merchant/devices`<br>`GET /api/merchant/settings`<br>`GET /api/merchant/speaker-log` |
| `merchant.advance:read` | inherited from `platform_auditor` | `GET /api/merchant/advance`<br>`GET /api/merchant/advance/consents`<br>`GET /api/merchant/advance/eligibility`<br>`GET /api/merchant/lenders` |
| `merchant.inventory:read` | inherited from `platform_auditor` | `GET /api/merchant/catalogue`<br>`GET /api/merchant/inventory`<br>`GET /api/merchant/inventory/alerts`<br>`GET /api/merchant/inventory/rules` |
| `merchant.invoice:read` | inherited from `platform_auditor` | `GET /api/merchant/invoices`<br>`GET /api/merchant/invoices/{id}` |
| `merchant.khata:read` | inherited from `platform_auditor` | `GET /api/merchant/khata`<br>`GET /api/merchant/khata/{id}/entries` |
| `merchant.order:read` | inherited from `platform_auditor` | `GET /api/merchant/orders`<br>`GET /api/merchant/orders/{id}` |
| `merchant.payment:read` | inherited from `platform_auditor` | `GET /api/merchant/payments`<br>`GET /api/merchant/payments/{id}` |
| `merchant.statement:read` | inherited from `platform_auditor` | `GET /api/merchant/statements` |
| `merchant.tab:read` | inherited from `platform_auditor` | `GET /api/merchant/tabs`<br>`GET /api/merchant/tabs/{id}/entries` |
| `merchant.withdrawal:read` | inherited from `platform_auditor` | `GET /api/merchant/withdrawals` |
| `ops.audit:read` | inherited from `platform_auditor` | `GET /api/ops/audit` |
| `ops.bank_account_approval:authorize` | own | `POST /api/ops/approvals/bank-accounts/{id}` |
| `ops.bank_account_approval:read` | inherited from `platform_auditor` | `GET /api/ops/approvals/bank-accounts` |
| `ops.complaint:read` | inherited from `platform_auditor` | `GET /api/ops/complaints` |
| `ops.dispute:read` | inherited from `platform_auditor` | `GET /api/ops/disputes` |
| `ops.incident:read` | inherited from `platform_auditor` | `GET /api/ops/incidents` |
| `ops.kyc_check:read` | inherited from `platform_auditor` | `GET /api/ops/kyc/{entityType}/{entityId}` |
| `ops.merchant_approval:authorize` | own | `POST /api/ops/approvals/merchants/{id}` |
| `ops.merchant_approval:read` | inherited from `platform_auditor` | `GET /api/ops/approvals/merchants`<br>`GET /api/ops/approvals/merchants/all` |
| `ops.onboarding_request:authorize` | own | `POST /api/ops/onboarding-requests/{id}/decision` |
| `ops.onboarding_request:read` | inherited from `platform_auditor` | `GET /api/ops/onboarding-requests` |
| `ops.recon:read` | inherited from `platform_auditor` | `GET /api/ops/recon/runs`<br>`GET /api/ops/recon/runs/{id}/breaks` |
| `ops.report:read` | inherited from `platform_auditor` | `GET /api/ops/payments`<br>`GET /api/ops/reports/fees`<br>`GET /api/ops/reports/settlement` |
| `ops.supplier_approval:authorize` | own | `POST /api/ops/approvals/suppliers/{id}` |
| `ops.supplier_approval:read` | inherited from `platform_auditor` | `GET /api/ops/approvals/suppliers`<br>`GET /api/ops/approvals/suppliers/all` |
| `ops.suspension:read` | inherited from `platform_auditor` | `GET /api/ops/suspensions` |
| `org.onboarding:read` | inherited from `platform_auditor` | `GET /api/orgs/onboarding` |
| `org.rights:read` | inherited from `platform_auditor` | `GET /api/orgs/me/grantable`<br>`GET /api/orgs/{id}/rights`<br>`GET /api/orgs/{id}/role-ceiling` |
| `org.tree:read` | inherited from `platform_auditor` | `GET /api/orgs/me`<br>`GET /api/orgs/me/descendants`<br>`GET /api/orgs/me/path`<br>`GET /api/orgs/me/tree`<br>`GET /api/orgs/{id}`<br>`GET /api/orgs/{id}/descendants`<br>`GET /api/orgs/{id}/path` |
| `org.user:read` | inherited from `platform_auditor` | `GET /api/orgs/me/users`<br>`GET /api/orgs/{id}/members` |
| `rbac.assignment:read` | inherited from `platform_auditor` | `GET /api/admin/rbac/assignments`<br>`GET /api/orgs/{id}/role-approvals`<br>`GET /api/orgs/{id}/role-assignments`<br>`GET /rbac/admin/assignments`<br>`GET /rbac/admin/assignments/{id}`<br>`GET /rbac/admin/holders` |
| `rbac.audit:read` | inherited from `platform_auditor` | `GET /rbac/admin/audit`<br>`GET /rbac/admin/decisions/{id}` |
| `rbac.grant:read` | inherited from `platform_auditor` | `GET /rbac/admin/grants` |
| `rbac.review:read` | inherited from `platform_auditor` | `GET /rbac/admin/reviews/{id}` |
| `rbac.role:read` | inherited from `platform_auditor` | `GET /api/admin/rbac/roles`<br>`GET /api/admin/rbac/roles/{name}`<br>`GET /api/orgs/{id}/roles`<br>`GET /rbac/admin/export/role-pack`<br>`GET /rbac/admin/resources`<br>`GET /rbac/admin/roles`<br>`GET /rbac/admin/roles/{name}` |
| `rbac.user:read` | inherited from `platform_auditor` | `GET /api/admin/rbac/users` |
| `supplier.account:read` | inherited from `platform_auditor` | `GET /api/supplier/payments`<br>`GET /api/supplier/profile` |
| `supplier.bank_account:read` | inherited from `platform_auditor` | `GET /api/supplier/bank-accounts` |
| `supplier.catalogue:read` | inherited from `platform_auditor` | `GET /api/supplier/catalogue` |
| `supplier.invoice:read` | inherited from `platform_auditor` | `GET /api/supplier/invoices`<br>`GET /api/supplier/invoices/{id}` |
| `supplier.order:read` | inherited from `platform_auditor` | `GET /api/supplier/orders`<br>`GET /api/supplier/orders/{id}` |
| `supplier.statement:read` | inherited from `platform_auditor` | `GET /api/supplier/statements` |

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
| `admin.access:read` | inherited from `platform_auditor` | `GET /api/admin/access` |
| `admin.aml_alert:read` | inherited from `platform_auditor` | `GET /api/admin/compliance/aml-alerts` |
| `admin.api_client:read` | inherited from `platform_auditor` | `GET /api/admin/api-clients` |
| `admin.config:read` | inherited from `platform_auditor` | `GET /api/admin/limits`<br>`GET /api/admin/rules`<br>`GET /api/admin/settings`<br>`GET /api/admin/tariffs`<br>`GET /api/admin/templates` |
| `admin.connector:read` | inherited from `platform_auditor` | `GET /api/admin/connectors` |
| `admin.dsar:read` | inherited from `platform_auditor` | `GET /api/admin/compliance/dsars` |
| `admin.platform_ops:read` | inherited from `platform_auditor` | `GET /api/admin/backups`<br>`GET /api/admin/deployments`<br>`GET /api/admin/health`<br>`GET /api/admin/restore-tests`<br>`GET /api/admin/sim/outbox` |
| `admin.sar:read` | inherited from `platform_auditor` | `GET /api/admin/compliance/sars` |
| `admin.simulation:read` | inherited from `platform_auditor` | `GET /api/admin/scenarios`<br>`GET /api/admin/sim/controls` |
| `kyc.application:read` | inherited from `platform_auditor` | `GET /api/kyc/orgs`<br>`GET /api/kyc/orgs/me`<br>`GET /api/kyc/orgs/{id}`<br>`GET /api/orgs/kyc`<br>`GET /api/orgs/me/kyc`<br>`GET /api/orgs/{id}/kyc` |
| `kyc.form:read` | inherited from `platform_auditor` | `GET /api/kyc/lists/{code}`<br>`GET /api/kyc/schema`<br>`GET /api/kyc/schema/{orgId}` |
| `kyc.task:authorize` | own | `POST /api/kyc/tasks/{taskId}/decision` |
| `kyc.task:read` | inherited from `platform_auditor` | `GET /api/kyc/tasks` |
| `lender.advance:read` | inherited from `platform_auditor` | `GET /api/lender/advances`<br>`GET /api/lender/consents` |
| `lender.assessment:read` | inherited from `platform_auditor` | `GET /api/lender/assessments`<br>`GET /api/lender/records/{merchantId}` |
| `lender.collection:read` | inherited from `platform_auditor` | `GET /api/lender/collections` |
| `lender.portfolio:read` | inherited from `platform_auditor` | `GET /api/lender/portfolio` |
| `merchant.account:read` | inherited from `platform_auditor` | `GET /api/merchant/accept-info`<br>`GET /api/merchant/balance`<br>`GET /api/merchant/balance/entries`<br>`GET /api/merchant/billing-preview`<br>`GET /api/merchant/dashboard`<br>`GET /api/merchant/devices`<br>`GET /api/merchant/settings`<br>`GET /api/merchant/speaker-log` |
| `merchant.advance:read` | inherited from `platform_auditor` | `GET /api/merchant/advance`<br>`GET /api/merchant/advance/consents`<br>`GET /api/merchant/advance/eligibility`<br>`GET /api/merchant/lenders` |
| `merchant.inventory:read` | inherited from `platform_auditor` | `GET /api/merchant/catalogue`<br>`GET /api/merchant/inventory`<br>`GET /api/merchant/inventory/alerts`<br>`GET /api/merchant/inventory/rules` |
| `merchant.invoice:read` | inherited from `platform_auditor` | `GET /api/merchant/invoices`<br>`GET /api/merchant/invoices/{id}` |
| `merchant.khata:read` | inherited from `platform_auditor` | `GET /api/merchant/khata`<br>`GET /api/merchant/khata/{id}/entries` |
| `merchant.order:read` | inherited from `platform_auditor` | `GET /api/merchant/orders`<br>`GET /api/merchant/orders/{id}` |
| `merchant.payment:read` | inherited from `platform_auditor` | `GET /api/merchant/payments`<br>`GET /api/merchant/payments/{id}` |
| `merchant.statement:read` | inherited from `platform_auditor` | `GET /api/merchant/statements` |
| `merchant.tab:read` | inherited from `platform_auditor` | `GET /api/merchant/tabs`<br>`GET /api/merchant/tabs/{id}/entries` |
| `merchant.withdrawal:read` | inherited from `platform_auditor` | `GET /api/merchant/withdrawals` |
| `ops.audit:read` | inherited from `platform_auditor` | `GET /api/ops/audit` |
| `ops.bank_account_approval:read` | inherited from `platform_auditor` | `GET /api/ops/approvals/bank-accounts` |
| `ops.complaint:read` | inherited from `platform_auditor` | `GET /api/ops/complaints` |
| `ops.dispute:read` | inherited from `platform_auditor` | `GET /api/ops/disputes` |
| `ops.incident:read` | inherited from `platform_auditor` | `GET /api/ops/incidents` |
| `ops.kyc_check:read` | inherited from `platform_auditor` | `GET /api/ops/kyc/{entityType}/{entityId}` |
| `ops.merchant_approval:read` | inherited from `platform_auditor` | `GET /api/ops/approvals/merchants`<br>`GET /api/ops/approvals/merchants/all` |
| `ops.onboarding_request:read` | inherited from `platform_auditor` | `GET /api/ops/onboarding-requests` |
| `ops.recon:read` | inherited from `platform_auditor` | `GET /api/ops/recon/runs`<br>`GET /api/ops/recon/runs/{id}/breaks` |
| `ops.report:read` | inherited from `platform_auditor` | `GET /api/ops/payments`<br>`GET /api/ops/reports/fees`<br>`GET /api/ops/reports/settlement` |
| `ops.supplier_approval:read` | inherited from `platform_auditor` | `GET /api/ops/approvals/suppliers`<br>`GET /api/ops/approvals/suppliers/all` |
| `ops.suspension:read` | inherited from `platform_auditor` | `GET /api/ops/suspensions` |
| `org.onboarding:read` | inherited from `platform_auditor` | `GET /api/orgs/onboarding` |
| `org.rights:read` | inherited from `platform_auditor` | `GET /api/orgs/me/grantable`<br>`GET /api/orgs/{id}/rights`<br>`GET /api/orgs/{id}/role-ceiling` |
| `org.tree:read` | inherited from `platform_auditor` | `GET /api/orgs/me`<br>`GET /api/orgs/me/descendants`<br>`GET /api/orgs/me/path`<br>`GET /api/orgs/me/tree`<br>`GET /api/orgs/{id}`<br>`GET /api/orgs/{id}/descendants`<br>`GET /api/orgs/{id}/path` |
| `org.user:read` | inherited from `platform_auditor` | `GET /api/orgs/me/users`<br>`GET /api/orgs/{id}/members` |
| `rbac.assignment:authorize` | own | `POST /api/orgs/{id}/role-approvals/{assignmentId}`<br>`GET /rbac/admin/approvals`<br>`POST /rbac/admin/approvals/{id}/approve`<br>`POST /rbac/admin/approvals/{id}/reject` |
| `rbac.assignment:read` | own | `GET /api/admin/rbac/assignments`<br>`GET /api/orgs/{id}/role-approvals`<br>`GET /api/orgs/{id}/role-assignments`<br>`GET /rbac/admin/assignments`<br>`GET /rbac/admin/assignments/{id}`<br>`GET /rbac/admin/holders` |
| `rbac.audit:read` | inherited from `platform_auditor` | `GET /rbac/admin/audit`<br>`GET /rbac/admin/decisions/{id}` |
| `rbac.grant:read` | inherited from `platform_auditor` | `GET /rbac/admin/grants` |
| `rbac.review:read` | inherited from `platform_auditor` | `GET /rbac/admin/reviews/{id}` |
| `rbac.role:read` | inherited from `platform_auditor` | `GET /api/admin/rbac/roles`<br>`GET /api/admin/rbac/roles/{name}`<br>`GET /api/orgs/{id}/roles`<br>`GET /rbac/admin/export/role-pack`<br>`GET /rbac/admin/resources`<br>`GET /rbac/admin/roles`<br>`GET /rbac/admin/roles/{name}` |
| `rbac.user:read` | inherited from `platform_auditor` | `GET /api/admin/rbac/users` |
| `supplier.account:read` | inherited from `platform_auditor` | `GET /api/supplier/payments`<br>`GET /api/supplier/profile` |
| `supplier.bank_account:read` | inherited from `platform_auditor` | `GET /api/supplier/bank-accounts` |
| `supplier.catalogue:read` | inherited from `platform_auditor` | `GET /api/supplier/catalogue` |
| `supplier.invoice:read` | inherited from `platform_auditor` | `GET /api/supplier/invoices`<br>`GET /api/supplier/invoices/{id}` |
| `supplier.order:read` | inherited from `platform_auditor` | `GET /api/supplier/orders`<br>`GET /api/supplier/orders/{id}` |
| `supplier.statement:read` | inherited from `platform_auditor` | `GET /api/supplier/statements` |

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
| `admin.access:administer` | own | `POST /api/admin/access/{userId}/revoke` |
| `admin.access:read` | own | `GET /api/admin/access` |
| `admin.api_client:administer` | own | `POST /api/admin/api-clients/{id}/revoke` |
| `admin.api_client:create` | own | `POST /api/admin/api-clients` |
| `admin.api_client:read` | own | `GET /api/admin/api-clients` |
| `org.tree:read` | own | `GET /api/orgs/me`<br>`GET /api/orgs/me/descendants`<br>`GET /api/orgs/me/path`<br>`GET /api/orgs/me/tree`<br>`GET /api/orgs/{id}`<br>`GET /api/orgs/{id}/descendants`<br>`GET /api/orgs/{id}/path` |
| `org.user:administer` | own | `POST /api/orgs/users/{id}/resend-activation` |
| `org.user:create` | own | `POST /api/orgs/me/users` |
| `org.user:delete` | own | `POST /api/orgs/users/{id}/deactivate` |
| `org.user:read` | own | `GET /api/orgs/me/users`<br>`GET /api/orgs/{id}/members` |
| `rbac.assignment:administer` | own | `DELETE /api/admin/rbac/assignments/{id}`<br>`POST /api/orgs/{id}/role-assignments/{assignmentId}/revoke`<br>`DELETE /rbac/admin/assignments/{id}` |
| `rbac.assignment:authorize` | own | `POST /api/orgs/{id}/role-approvals/{assignmentId}`<br>`GET /rbac/admin/approvals`<br>`POST /rbac/admin/approvals/{id}/approve`<br>`POST /rbac/admin/approvals/{id}/reject` |
| `rbac.assignment:create` | own | `POST /api/admin/rbac/assignments`<br>`POST /api/orgs/{id}/role-assignments`<br>`POST /rbac/admin/assignments` |
| `rbac.assignment:read` | own | `GET /api/admin/rbac/assignments`<br>`GET /api/orgs/{id}/role-approvals`<br>`GET /api/orgs/{id}/role-assignments`<br>`GET /rbac/admin/assignments`<br>`GET /rbac/admin/assignments/{id}`<br>`GET /rbac/admin/holders` |
| `rbac.audit:read` | own | `GET /rbac/admin/audit`<br>`GET /rbac/admin/decisions/{id}` |
| `rbac.grant:administer` | own | `DELETE /rbac/admin/grants/{id}` |
| `rbac.grant:create` | own | `POST /rbac/admin/grants` |
| `rbac.grant:read` | own | `GET /rbac/admin/grants` |
| `rbac.review:administer` | own | `POST /rbac/admin/reviews/{id}/items/{assignmentId}` |
| `rbac.review:create` | own | `POST /rbac/admin/reviews` |
| `rbac.review:read` | own | `GET /rbac/admin/reviews/{id}` |
| `rbac.role:create` | own | `POST /api/orgs/{id}/roles`<br>`POST /rbac/admin/roles` |
| `rbac.role:delete` | own | `DELETE /api/orgs/{id}/roles/{name}`<br>`DELETE /rbac/admin/roles/{name}` |
| `rbac.role:modify` | own | `PUT /api/admin/rbac/roles/{name}`<br>`PUT /api/orgs/{id}/roles/{name}`<br>`PUT /rbac/admin/roles/{name}` |
| `rbac.role:read` | own | `GET /api/admin/rbac/roles`<br>`GET /api/admin/rbac/roles/{name}`<br>`GET /api/orgs/{id}/roles`<br>`GET /rbac/admin/export/role-pack`<br>`GET /rbac/admin/resources`<br>`GET /rbac/admin/roles`<br>`GET /rbac/admin/roles/{name}` |
| `rbac.user:read` | own | `GET /api/admin/rbac/users` |

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
| `admin.simulation:read` | own | `GET /api/admin/scenarios`<br>`GET /api/admin/sim/controls` |
| `admin.simulation:simulate` | own | `POST /api/admin/scenes/run`<br>`POST /api/admin/sim/advance`<br>`POST /api/admin/sim/clear-breaks`<br>`POST /api/admin/sim/controls`<br>`POST /api/admin/sim/inject-break`<br>`POST /api/admin/sim/invoice-payment`<br>`POST /api/admin/sim/payment`<br>`POST /api/admin/sim/synthetic-history` |
| `merchant.advance:simulate` | own | `POST /api/merchant/advance/{id}/simulate-fund` |
| `merchant.payment:simulate` | own | `POST /api/merchant/payments/{id}/simulate` |

Approved for use: ______________________ (Compliance)    Date: __________

---

## Role ceilings and org-defined roles

Every org uses only the roles inside its **role ceiling**, chosen by the org that onboarded it and never wider than that org's own hand-down set. Always inside every ceiling: `kyc_applicant`, `org_admin`.

Orgs may build their own roles from the roles in their ceiling. Each one's cells are a subset of its base roles' cells, checked on save and again on every decision, so these roles never grant anything outside the signed-off roles above. Base roles never span both sides of a separation-of-duty rule. They are listed here for the access review, not for separate sign-off.

No org-defined roles yet.
