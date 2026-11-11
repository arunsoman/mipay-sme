# Plan: Inventory management as an org plugin (MiPay SME backend)

## Context
Inventory today is a merchant-only feature:
- **Tables:** `inventory_items`, `stock_movements`, `stock_alerts`, `reorder_rules`, all keyed by `merchant_id`. There are no locations, serial numbers or batches.
- **Code:** `InventoryService`, called directly from `PaymentService` L200 (a sale reduces stock) and `B2BPaymentService` L324 (a paid invoice adds stock). `MerchantController` serves `/api/merchant/inventory`.

**Goal:** one inventory engine, packaged as a **plugin** that is switched on per org:
- **Platform (admin) org**, always: buying, selling and provisioning **POS terminals** to merchants, tracked by serial number.
- **Manufacturer**, optional: once it is enabled for a manufacturer, **that manufacturer and every org below it** (distributors, suppliers, merchants, including ones onboarded later) each get their own **separate** inventory.
- Core MiPay must keep working when the plugin is switched off.

**Depends on** the org-hierarchy plan (`binary-juggling-rocket.md`): `organizations`, `org_closure`, `OrgAccess.requireOperational`, the JWT `orgId` / `orgRole` claims, and `ORG_ROOT`. That work must finish first (it is paused partway and currently doesn't compile).

## Decisions
| Topic | Decision |
|---|---|
| Who enables it | **Platform staff only** (MINDLABS_ADMIN). Keeps it a sellable feature. |
| Enablement scope | Each grant is either `SELF` (this org only) or `SUBTREE` (this org and every org below it, now and later). The platform org uses `SELF`; a manufacturer uses `SUBTREE`. |
| Who has it | An org has the plugin if it has its own `SELF` grant, **or** it or any org above it has an active `SUBTREE` grant. One closure-table query, the same pattern as suspension. |
| Data ownership | Every org's inventory is **private and isolated**, even inside an enabled subtree. Parents get no automatic access. Stock crosses org boundaries only through shipments or custody. |
| Disable or suspend | Data is never deleted. Disabled or suspended → **read-only**. Re-enabling restores full access. |
| POS sale billing (v1) | Record the hand-over (sale, lease or loan), with an optional external billing reference. **A real MiPay invoice payable to the platform is phase 7**, because today invoices can only be paid to suppliers. |

## 1. Plugin framework (in core, kept small)
- **`com.mipay.sme.plugin.PlatformPlugin`** declares: `key()`, `version()`, `permissions()`, `migrationLocation()`.
- **`PluginRegistry`** collects the plugin beans.
- **`app.plugins.<key>.enabled`** switches a plugin on for the whole deployment, via `@ConditionalOnProperty`.
- **`org_plugin_grants`**: `id`, `org_id`, `plugin_key`, `scope SELF|SUBTREE`, `granted_by`, `granted_at`, `revoked_at`, `revoked_by`, `config`.
- **`PluginAccess.mode(orgId, key)`** returns `FULL`, `READ_ONLY` or `NONE`:
  - `FULL`: an active grant applies (own `SELF`, or `SUBTREE` on the org or an ancestor) **and** `OrgAccess.requireOperational` passes.
  - `READ_ONLY`: a grant used to apply (it was revoked), or the org is suspended.
  - `NONE`: no grant has ever applied.
- **`PlatformContext`** is the only core interface the plugin may use:
  - the current principal (userId, orgId, orgRole)
  - `orgName(id)`
  - `isAncestorOrSelf(a, b)`
  - `pluginMode(orgId)`
  - `audit(...)`
- **Domain events** published by core, which the plugin handles with `@TransactionalEventListener(AFTER_COMMIT)` plus idempotency keys:
  - `SaleConfirmed(merchantId, orgId, itemRef, qty, paymentId)`, published at `PaymentService` L200
  - `InvoiceSettled(invoiceId, sellerOrgId, buyerOrgId, lines)`, published at `B2BPaymentService` L324
  - `OrgOnboarded`, `OrgSuspended` (for cache-free checks, informational)

  When the plugin is off, nothing listens and core is unaffected.
- **Boundary test:** `com.tngtech.archunit:archunit-junit5` (test scope). The build fails if:
  - `..plugins.inventory..` depends on any core package other than `plugin`, `common` or `security.Current`
  - any core package depends on `..plugins..`
- **Admin API**, platform only:
  - `POST /api/admin/plugins/{key}/grants` with `{orgId, scope}`
  - `POST /api/admin/plugins/grants/{id}/revoke`
  - `GET /api/admin/plugins/{key}/grants?orgId=` shows the effective grants and their source
- **Org API:** `GET /api/orgs/me/plugins` returns the effective mode for each plugin.

## 2. Inventory plugin data model
Package `com.mipay.sme.plugins.inventory`. Tables use the `inv_` prefix, and every one has `org_id`.

| Table | Purpose |
|---|---|
| `inv_items` | SKU (unique per org), name, unit, category, `tracking QUANTITY / SERIALIZED / LOT`, status, `@Version` |
| `inv_attribute_defs`, `inv_item_attributes` | Custom fields per org (typed: TEXT, NUMBER, DATE, BOOL), e.g. terminal model, firmware, SIM ICCID |
| `inv_locations` | Types: WAREHOUSE, SHOP, VAN, REPAIR, IN_TRANSIT, **CUSTODY**. A CUSTODY location has a `custodian_org_id`, meaning the stock is at another org but still owned by this one. |
| `inv_units` | One row per serialized unit: serial number (unique per org and item), location, `state`, `custodian_org_id`, `@Version` |
| `inv_lots` | lot number, expiry date |
| `inv_balances` | `(org, item, location, lot)` → `on_hand`, `reserved`, with `@Version` |
| `inv_movements` | **Permanent journal**: type (RECEIVE, ISSUE, TRANSFER, SALE, ADJUST, DEPLOY, RETURN, WRITE_OFF, SHIP, RECEIVE_SHIPMENT), item, quantity or unit, from/to location, lot, reason, reference, `idempotency_key` (unique), actor, `reverses_id` |
| `inv_reservations` | Holds stock for an order or provisioning job; expires |
| `inv_reorder_rules`, `inv_alerts` | Generalized versions of the merchant ones (LOW_STOCK, OUT_OF_STOCK, EXPIRING) |
| `inv_counts`, `inv_count_lines` | Stock-takes; the resulting adjustment is approved by a second person |
| `inv_shares` | Read-only visibility granted by an org to a named org (e.g. a merchant to its supplier); can be revoked |
| `inv_shipments`, `inv_shipment_lines` | Inter-org transfers: SHIPPED → IN_TRANSIT → RECEIVED / DISPUTED |
| `inv_user_roles` | Per-user plugin role overrides |

**Invariants:**
- Every change to stock is a journal row plus a balance update in the **same transaction**. A balance can never go negative (the database enforces it, and the service rechecks under `@Version`).
- Corrections are reversing entries. Journal rows are never updated or deleted.
- The `idempotency_key` makes event handlers and retries apply exactly once (the same rule as the money ledgers).
- Serialized-unit state machine, applied with a compare-and-set `UPDATE … WHERE state=:from AND version=:v`:
  `IN_STOCK → ALLOCATED → DEPLOYED → RETURNED → IN_STOCK | IN_REPAIR → IN_STOCK | RETIRED | LOST`
- A nightly `InventoryReconJob` rebuilds the balances from the journal and raises a break when they differ. It reuses the reconciliation-break pattern.

## 3. Access inside the plugin
- **Plugin roles:** MANAGER, CLERK, VIEWER.
  - The default comes from `orgRole`: ADMIN → MANAGER, STAFF → CLERK.
  - `inv_user_roles` can override per user (only a MANAGER can grant).
- **MANAGER-only actions:** item and attribute definitions, write-off, approving stock counts, shares, approving reorders, provisioning approvals.
- **Each request:**
  1. `orgId` comes from the JWT only.
  2. `PluginAccess.mode` must be FULL for writes, and at least READ_ONLY for reads.
  3. Read-only and suspended orgs get `ORG_SUSPENDED` / `PLUGIN_READ_ONLY`.
  4. A disabled org gets 404 `PLUGIN_NOT_ENABLED`.
- **Cross-org access:** another org's inventory always returns 404, except through `inv_shares` (read-only) or the custody view below.

## 4. Platform POS flow (the main v1 use case)
1. The platform enables itself with a `SELF` grant (seeded).
2. It creates the item "POS Terminal Model X" (SERIALIZED), with custom fields for model, firmware and SIM ICCID.
3. **Receive:** a bulk upload of serial numbers into its WAREHOUSE (state IN_STOCK).
4. **Provision to a merchant:** `POST /api/plugins/inventory/provisioning` with `{serial or item, merchantOrgId, mode SALE|LEASE|LOAN, billingRef?}`.
   - The merchant must be an ACTIVE, operational org.
   - The unit is reserved (ALLOCATED). After **dispatch confirmation**, it becomes DEPLOYED at a CUSTODY location for that merchant.
   - The existing `card_terminals` row is created or updated through a core port, `TerminalRegistryPort`, so acquiring keeps working. The new `card_terminals.inventory_unit_ref` column links the two.
5. **Swap, return, repair, write-off:** each one is a movement, so every terminal has a complete history.
6. **Merchant view:** `GET /api/merchant/devices` lists the units in the merchant's custody, read-only. It works **even if the merchant doesn't have the plugin**, because the platform owns these units.
7. **Merchant suspended:** deployed units are flagged in the platform's view, so the platform can recover them. They are not moved automatically.

## 5. Manufacturer subtree flow
- The platform grants Manufacturer A `SUBTREE`. A, its distributors, their suppliers and the merchants below them all get FULL access, each with an isolated inventory. A child onboarded later gets it automatically, because the closure-table check covers it.
- **Inter-org shipments** (phase 6):
  1. When an `InvoiceSettled` event comes in between two orgs that **both** have the plugin, or when a user ships manually, the seller gets a SHIP movement into its IN_TRANSIT location.
  2. The buyer confirms receipt with RECEIVE_SHIPMENT. A mismatch moves the shipment to DISPUTED.
  3. Neither org ever writes to the other's stock.
- **Revoking A's grant** makes A and its whole subtree read-only. If an org lower down has its own grant, its access is unaffected.

## 6. Moving existing merchant inventory onto the plugin
- **Migration** `db/migration/plugins/inventory/V100__inv_init.sql` creates the tables. `V101__inv_migrate_merchant.sql`:
  1. Creates one SHOP location per merchant org.
  2. Copies `inventory_items` into `inv_items` (QUANTITY), with the opening balances recorded as `RECEIVE` journal rows (`reference='migration'`).
  3. Copies `reorder_rules` and the open `stock_alerts`.
  4. Gives each existing merchant a `SELF` grant, so nothing changes for them.
- **H2 and test profiles:** the same mapping runs from an idempotent `InventoryMigrationRunner`.
- **Compatibility adapters:** `MerchantController` `/inventory` GET, POST, adjust and reorder, together with `InventoryService.recordSale` / `recordDelivery`, become adapters onto the plugin, behind the core `InventoryPort`. When the plugin is off, they fall back to a no-op port. The response DTOs (`Dtos.InventoryRow`) stay the same.
- **Merchant UAT** (`TUat11` TC-OPS-01/02/03, and TC-ACC-04 "no second stock adjustment") must stay green **unchanged**.
- The old tables are kept read-only for one release, then dropped in a later migration.

## 7. API (`/api/plugins/inventory/**`, authenticated)
| Area | Endpoints |
|---|---|
| Items | `GET/POST /items`, `PUT /items/{id}`, `POST /items/{id}/retire`, `GET/POST /attributes` |
| Locations | `GET/POST /locations` |
| Stock | `GET /balances?item&location`, `GET /movements?item&unit&from&to` (paged), `POST /receive`, `/issue`, `/transfer`, `/adjust` (MANAGER) |
| Units | `GET /units?state&location&custodian`, `GET /units/{serial}/history`, `POST /units/{id}/transition` |
| Provisioning (platform) | `POST /provisioning`, `POST /provisioning/{id}/dispatch`, `/return`, `/swap` |
| Counts | `POST /counts`, `POST /counts/{id}/lines`, `POST /counts/{id}/submit`, `POST /counts/{id}/approve` (a different MANAGER) |
| Reorder / alerts | `GET/POST /reorder-rules`, `GET /alerts`, `POST /alerts/{id}/resolve` |
| Sharing | `GET/POST /shares`, `POST /shares/{id}/revoke`, `GET /shared-with-me` |
| Shipments | `GET /shipments`, `POST /shipments`, `POST /shipments/{id}/receive`, `/dispute` |
| Merchant custody | `GET /api/merchant/devices` (core, read-only) |

Problem codes: `PLUGIN_NOT_ENABLED` (404), `PLUGIN_READ_ONLY` / `ORG_SUSPENDED`, `INSUFFICIENT_STOCK`, `INVALID_UNIT_TRANSITION`, `DUPLICATE_SERIAL`, `INDEPENDENCE_RULE` (counts), `IDEMPOTENT_REPLAY` (returns the original result).

## 8. Security invariants (each one tested)
1. Only platform staff can create or revoke grants. Grants cascade only through `SUBTREE`, and only downward.
2. An org can never read or change another org's inventory, except through an explicit share (read-only) or the custody view.
3. A disabled or suspended org is read-only immediately, with no cache.
4. The journal is append-only, balances never go negative, and every event or retry applies exactly once.
5. Count adjustments over the threshold, and write-offs, need a second person.
6. Serial numbers are unique per org; a unit has exactly one location and state at any time.

## 9. Tests
- **`uat/TUat19InventoryPluginFrameworkTests` (TC-PLG):**
  - Grant resolution: SELF vs SUBTREE, an org onboarded later inherits, revoking makes the subtree read-only, an own grant survives a parent's revoke.
  - Only the platform can grant.
  - Disabled → 404 and suspended → read-only.
  - The ArchUnit boundary test.
- **`uat/TUat20InventoryCoreTests` (TC-INV):**
  - Receive, issue and transfer for quantity and lot tracking.
  - Negative stock is refused.
  - Idempotent replay.
  - Concurrent issues on the same balance: exactly one wins when stock is short.
  - Reversal entries.
  - Counts with second-person approval.
  - Reorder rules and alerts.
  - Cross-org 404, and shares.
  - Recon detects a tampered balance.
- **`uat/TUat21PosProvisioningTests` (TC-POS):**
  - Bulk receive of serials, duplicate serial refused.
  - Provision → dispatch → DEPLOYED in merchant custody, with `card_terminals` linked.
  - Merchant `/devices` shows it (merchant has no plugin).
  - Swap, return and repair history.
  - Invalid transitions refused.
  - Two concurrent provisionings of the same serial: one wins.
  - Provisioning to a suspended or pending merchant is refused.
- **`uat/TUat22InventorySubtreeTests` (TC-INV-S):**
  - A manufacturer `SUBTREE` grant: the distributor and merchant each have isolated stock, and the parent cannot see the child's.
  - The shipment flow: SHIP → IN_TRANSIT → RECEIVE, plus a dispute.
  - `InvoiceSettled` creates a shipment only when both orgs have the plugin.
- **Regression:** `TUat11` TC-OPS-01/02/03 and TC-ACC-04 unchanged, plus the full pack.

## 10. Implementation order
1. **(Prerequisite)** Finish the org-hierarchy onboarding plan.
2. Plugin framework: registry, grants, `PluginAccess`, `PlatformContext`, domain events, ArchUnit, admin grant API.
3. Inventory core: items, locations, journal, balances, quantity tracking.
4. Move the merchant data plus the compatibility adapters; the existing UAT must stay green.
5. Serialized units, provisioning, custody, the `card_terminals` link, and the merchant devices view (the POS use case).
6. Lots and expiry, reservations, reorder rules and alerts, counts, custom fields, sharing, the recon job.
7. Inter-org shipments driven by `InvoiceSettled`.
8. **(Later, needs a decision)** POS sale invoicing: let the platform org be paid on invoices through `InvoicePayment`.

## Verification
- `./gradlew test`: the full pack plus TC-PLG, TC-INV, TC-POS and TC-INV-S, and the ArchUnit boundary test.
- With `app.plugins.inventory.enabled=false`: the app boots, the core UAT passes, and the merchant inventory endpoints return 404 `PLUGIN_NOT_ENABLED` (behind a guard flag, so the default build still keeps legacy behavior).
- Postgres: V100 and V101 apply to a database with V4 and seeded merchant inventory; the migrated balances equal the old `current_stock`.
- Headless curl smoke:
  1. Admin grants the manufacturer `SUBTREE`.
  2. A distributor that is onboarded afterwards can receive stock.
  3. The platform provisions a terminal to a merchant.
  4. The merchant `/devices` lists it.
  5. Suspend the manufacturer; the distributor's inventory becomes read-only.

## Assumptions (please confirm)
- Only platform staff grant or revoke the plugin; ancestors cannot.
- POS hand-over is recorded in v1 with an external billing reference; in-platform invoicing comes later (step 8).
- Parents never see their children's stock unless a child shares it.
- Existing merchants keep inventory through an automatic `SELF` grant.
