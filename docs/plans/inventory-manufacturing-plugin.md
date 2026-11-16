# Plan: Inventory & Manufacturing Operations plugin (`inventory`)

Status: **draft for review** · 2026-11-15 · supersedes [`inventory-plugin.md`](inventory-plugin.md) (its framework, POS and isolation rules are carried over and revised here)

## 0. Summary

One plugin that runs the **physical side** of the value chain on MiPay:

- **TPTS (platform root)** buys, stocks, provisions, swaps, repairs and retires **POS terminals** and other devices (SIMs, printers, QR stands) that it places with merchants.
- A **manufacturer** runs its whole operation on it: buying raw material, making product (BOMs, work orders), quality, warehouses, and selling to its network.
- Everyone **below the manufacturer** (distributors/dealers, suppliers, merchants) gets their own isolated inventory, and goods move between them through **linked trade documents** (my sales order = your purchase order), never by one org writing another org's stock.

It is a plugin: switched on per org by TPTS with a `SELF` or `SUBTREE` grant, isolated in code (separate Gradle module + ArchUnit), in data (**its own database schema `inv`, its own DB role, its own connection pool and migrations; no SQL ever crosses between `inv` and the core schema**, §4.8), and in the UI (registered widgets). Core MiPay keeps working with it off.

The plugin is large, so it is split into **feature packs** that are switched on inside a grant (§3.3). A merchant gets `stock`; a manufacturer gets everything.

---

## 1. Context: what exists today (2026-11-15)

| Fact | Where | Consequence for this plan |
|---|---|---|
| Org tree with fixed types `PLATFORM > MANUFACTURER > DISTRIBUTOR > SUPPLIER > MERCHANT` (+ `LENDER` off platform), `org_closure` | `OrgTypeRules`, `Organization`, `OrgClosure` | Subtree grants and "my network" views are one closure query |
| Org lifecycle `PENDING / ACTIVE / REJECTED / BLOCKED`, suspension service | `OrgLifecycleService`, `OrgSuspensionService`, `OrgAccess` | Plugin access mode follows org status, no cache |
| RBAC **enforcing**: every handler carries `@Perm(resource, verb)`; `EndpointManifestPublisher` registers cells with trustplane RBAC; ceilings, org-defined roles, SoD | `security/rbac/*`, `extended-rbac.md` | The plugin defines **no private role system**. It declares `inv.*` cells and roles in the grid. (The old plan's `inv_user_roles` is dropped.) |
| Ten fixed verbs `READ … SIMULATE` | `Verb.java` | All plugin cells use these; no new verbs |
| Gateway (Go) routes `/api/**` to `backend:8080` via the BFF lane; FAPI 2.0 for partners | `trustplane/config/gateway/routes.yaml`, `fapi2-design.md` | Plugin APIs ride the existing route; partner access (dealer ERP, scanners) via the FAPI partner lane |
| Widget registry: manifests, `view/create/modify/approve` operations, `WidgetDataPort`, fixtures, per-tier sizes | `frontend/projects/shared/widgets/core`, `ui_widget_registrations`, `portal-builder.md` | Every plugin screen is a set of registered widgets; nothing hand-built |
| Merchant-only inventory: `inventory_items`, `stock_movements`, `stock_alerts`, `reorder_rules`; `InventoryService` called from `PaymentService:203` (sale) and `B2BPaymentService:326` (paid invoice) | `service/InventoryService.java` | Moves wholesale into the plugin; core keeps only event publishing (full list in §8.4) |
| B2B commerce is **merchant ↔ supplier only**: `orders`, `order_items`, `invoices` (FK to `Merchant`/`Supplier`), `supplier_catalogue_items` | `domain/b2b`, `domain/core` | Manufacturer → distributor → supplier trade is not invoiceable today. Needs a core change (§8.3, decision D-INV-3) |
| `card_terminals(merchant_id, terminal_id, status ACTIVE/INACTIVE)` | `domain/payments/CardTerminal` | Linked to plugin units by `inventory_unit_ref` |
| Outbox pattern already used (`AuthOutbox`, `SimOutbox`); Spring events in 3 services | `domain/core`, `domain/org` | Core → plugin events go through a **durable outbox**, not in-memory events |
| Clean-start database (`db/fresh/V1..V3`), no backfills | `db/README.md`, `_goal.md` | No migration of merchant stock data. Old tables are dropped once the adapter lands |
| Comms service (email/SMS) in trustplane; B1 in progress | `comms-design.md` | Alerts and PO emails go through a `NotificationPort`; they light up when comms does |
| Approval tiers / chains planned (OA5/OA6) | `org-access-model.md` | Write-offs, big adjustments, PO approvals use OA6 when it lands; a local 4-eyes until then |

### 1.1 Glossary (the words collide, so they are fixed here)

| Term | Meaning in this plan |
|---|---|
| **Org** | A MiPay organization (a tenant). Owns its inventory. |
| **Vendor** | Anyone an org **buys from**. Either an org on MiPay (linked) or an **external party** (a raw-material supplier who is not on MiPay). |
| **Customer** | Anyone an org **sells to**. Linked org or external party (e.g. an export customer, a walk-in). |
| **Dealer** | Business word for a manufacturer's downstream channel partner. On MiPay a dealer is a `DISTRIBUTOR` or `SUPPLIER` org. The UI may relabel per tenant. |
| **MiPay `SUPPLIER` org type** | A downstream wholesaler that sells to merchants. **Not** the manufacturer's raw-material supplier; that is a **vendor**. |
| **Item** | Anything stocked: raw material, component, sub-assembly, finished good, packaging, consumable, spare, device. |
| **Unit** | One serialised physical thing (a terminal with serial `PAX-A920-00123`). |
| **Lot** | A batch of a quantity-tracked item sharing manufacture date / expiry / QC result. |
| **Location** | A place stock sits: warehouse → zone → bin, plus virtual places (in-transit, custody, QC hold, scrap, WIP). |
| **Movement** | One append-only journal line. The only way stock changes. |
| **Trade document** | PO, SO, GRN, delivery note, return. Owned by one org; may be **linked** to the counter-party's document. |

---

## 2. Goals and non-goals

**Goals**
1. One stock engine for TPTS devices, manufacturers and every org in a manufacturer's network.
2. Everything a medium manufacturer (≈ 50–500 staff, 1–10 sites, 5k–50k SKUs, up to ~2M movements/year) needs to run: procurement, production, quality, warehousing, distribution, costing, traceability, planning.
3. Strict per-org isolation; cross-org flows only through linked documents, custody and explicit shares.
4. Plugin discipline: grantable, switch-off-safe (read-only, nothing deleted), code- and data-isolated, extractable into its own mesh service later.
5. Reuse the platform: RBAC cells, approval chains, comms, audit, widgets, gateway, payments/invoicing.

**Non-goals (v1)**
- General ledger / statutory accounting. The plugin produces a **valuation journal** that the [Accounting Export](plugins/accounting-export.md) plugin sends to the org's accounting system.
- HR, payroll, CRM, full MES/SCADA, finite capacity scheduling, EDI X12/EDIFACT. Hooks only.
- Retail POS checkout (MiPay payments already does selling; the plugin only consumes the sale event).
- Route planning and fleet ([Delivery & Routes](plugins/delivery-routes.md) plugin consumes our dispatches).

---

## 3. Plugin framework (core side, kept small)

This replaces `inventory-plugin.md` §1. It is generic: every plugin in [`plugins/`](plugins/README.md) uses it.

### 3.1 Pieces in core (`com.mipay.sme.plugin`)

| Piece | What it does |
|---|---|
| `PlatformPlugin` (interface) | `key()`, `version()`, `packs()`, `cells()` (its `@Perm` resources), `roles()` (grid fragment), `migrationLocation()`, `widgets()` (manifest ids) |
| `PluginRegistry` | Collects the `PlatformPlugin` beans present in the build. `app.plugins.<key>.enabled=false` removes the plugin from the context entirely (`@ConditionalOnProperty`). |
| `org_plugin_grants` | `id, org_id, plugin_key, scope SELF|SUBTREE, packs text[], config jsonb, granted_by, granted_at, revoked_at, revoked_by, version` |
| `PluginAccess.mode(orgId, key, pack)` | `FULL` / `READ_ONLY` / `NONE` (rules in §3.2). One closure query, no cache (same rule as suspension). |
| `PlatformContext` | The **only** core API the plugin may call: principal (`userId, orgId, orgType`), `org(id)` summary, `isAncestorOrSelf(a,b)`, `children(org, depth)`, `pluginMode(...)`, `audit(...)`. |
| Core ports (implemented in core, called by the plugin) | `TerminalRegistryPort`, `TradeInvoicePort`, `NotificationPort`, `ApprovalPort`, `PartyDirectoryPort` (§8) |
| Plugin → core direction only | Core never calls the plugin except through `PluginEventSink` (events). The legacy merchant inventory endpoints move into the plugin itself (§8.4), so core needs no inventory port. |
| `plugin_outbox` (core schema) + `PluginEventRelay` | Durable core → plugin events (§8.1), written in the core transaction; a **core** relay reads it and hands each event to the plugin's `PluginEventSink` (Java call, never SQL), at-least-once. The plugin stores it in its own `inv.inbox` and applies it idempotently. |
| Admin API | `POST /api/admin/plugins/{key}/grants`, `POST /api/admin/plugins/grants/{id}/revoke`, `PUT /api/admin/plugins/grants/{id}/packs`, `GET /api/admin/plugins/{key}/grants?orgId=` (effective + source) |
| Org API | `GET /api/orgs/me/plugins` → `{key, mode, packs, source}` per plugin (drives menus and widget visibility) |

### 3.2 Grant resolution

- An org **has** pack *p* of plugin *k* if it has its own active `SELF` grant containing *p*, **or** it or any ancestor has an active `SUBTREE` grant containing *p*. Packs from several applicable grants union.
- `FULL` = has it **and** org is `ACTIVE` and not suspended.
- `READ_ONLY` = had it (grant revoked, or pack removed) or org is suspended/blocked. Data stays; writes return `PLUGIN_READ_ONLY` / `ORG_SUSPENDED`.
- `NONE` = never had it → 404 `PLUGIN_NOT_ENABLED`.
- Only TPTS platform users with `platform.plugin:administer` grant or revoke. Ancestors cannot grant their children (decision kept from the old plan; revisit as D-INV-1 if manufacturers should resell packs).
- Granting is a **T3** action (4-eyes) under OA5 once tiers exist.

### 3.3 Feature packs

| Pack | Contents | Typical holders |
|---|---|---|
| `stock` (base, always on) | Items, UoM, locations, journal, balances, transfers, adjustments, counts, reorder rules, alerts, lots, serials, shares | everyone |
| `devices` | Device units, provisioning to merchants, custody, swap/repair/RMA, terminal registry link | TPTS; manufacturers of devices |
| `warehouse` | Zones/bins, putaway, pick/pack/ship, waves, scanner flows, labels | manufacturer, distributors |
| `procurement` | Vendors, price lists, RFQ, PO, approvals, GRN, 3-way match, vendor returns, landed cost | manufacturer, distributors |
| `sales` | Price lists by channel, sales orders, credit check, allocation, dispatch, returns (RMA), warranty, dealer schemes hook | manufacturer, distributors, suppliers |
| `manufacturing` | BOMs, routings, work centres, work orders, issue/backflush, production receipt, scrap, by-products, subcontracting | manufacturer |
| `quality` | Inspection plans, QC holds, test results, NCR, CAPA, CoA | manufacturer |
| `planning` | MRP, demand forecast, safety stock, planned orders | manufacturer |
| `costing` | FIFO / moving average / standard cost, valuation, variances, period close, valuation journal | manufacturer, distributors |
| `network` | Manufacturer's consented view of channel stock and secondary sales, channel replenishment suggestions | manufacturer |

Packs depend on each other: `manufacturing ⇒ procurement, costing`; `planning ⇒ procurement`; `network ⇒ sales`. The grant API refuses an incoherent set.

### 3.4 Boundaries (ArchUnit, build fails otherwise)

- `..plugins.inventory..` may depend only on `com.mipay.sme.plugin..`, `com.mipay.sme.common..`, `com.mipay.sme.security.rbac.Perm/Verb/TenantSource`, Spring, JPA, and its own packages.
- No core package may depend on `..plugins..`.
- Plugin entities may not map core tables, and core entities may not map `inv.*` tables.
- Plugin classes may use only the plugin's own `DataSource` / `EntityManager` / transaction manager (qualified `@Inventory`); core classes may never inject them. ArchUnit checks both.
- **No cross-schema SQL at all**: no foreign keys, views, joins, triggers or functions between `inv` and the core schema. `org_id`, `user_id`, `terminal_id`, `payment_id` are opaque references, validated through `PlatformContext`. This is also enforced at the database (§4.8): each side's DB role has no privileges on the other's schema.

---

## 4. Architecture

### 4.1 Deployment shape

```
 Browser portals (admin / manufacturer / distributor / supplier / merchant)
   │  same-origin /api + cookie + X-CSRF (BFF lane)        Partner systems, scanners
   ▼                                                        │ FAPI 2.0 (mTLS + DPoP)
 ┌────────────────────────── trustplane gateway (Go) ─────────────────────────┐
 │ route /api/plugins/inventory/**  → mipay upstream                           │
 │ RBAC decision per cell (gRPC, 5 s cache) · rate limit · audit headers       │
 └──────────────────────────────────┬──────────────────────────────────────────┘
                                    ▼
 ┌──────────────────────── MiPay backend (one JVM) ───────────────────────────┐
 │ core: orgs, KYC, payments, B2B, ledger, credit                               │
 │   │ writes public.plugin_outbox in its own tx                                │
 │   │ PluginEventRelay ──(Java call)──▶ PluginEventSink   ◀── PlatformContext,  │
 │   │                                        │                core ports ─────┤
 │   ▼ core DataSource (role mipay_app)       ▼                                 │
 │                          plugin.inventory  (Gradle module :plugins:inventory)│
 │                            api/ ─ app ─ domain/ ─ persistence/               │
 │                            jobs/: inbox, outbox→core ports, MRP, recon, …    │
 │                            inventory DataSource (role inv_app)               │
 └──────────┬──────────────────────────────────────┬────────────────────────────┘
            ▼                                      ▼
   core schema (public)                    schema inv (RLS) — own role, own Flyway
   no grants to inv_app                    history; no grants to mipay_app
   (same Postgres server by default; INVENTORY_DB_URL can point at another database)
                                                  comms-service · rbac-service
```

**Recommendation: a modular monolith first, with the database already separated.** The plugin runs in the backend JVM as its own Gradle module, but its data lives in **its own schema with its own DB role, connection pool, transaction manager and Flyway history** (§4.8). It talks to core only through `PlatformContext`, ports and the event relay, never through the database. Each side commits its own transaction; consistency between them comes from the outbox/inbox pattern, not shared transactions. Because neither code nor SQL crosses the boundary, it can later become a trustplane mesh service (like KYC phase 0) by swapping the port implementations for mTLS clients and the relay for a push/pull API, and its database can move to another server by changing `INVENTORY_DB_URL`. Extraction trigger: journal volume, or a manufacturer needing a dedicated deployment.

### 4.2 Module layout

```
backend/plugins/inventory/src/main/java/com/mipay/sme/plugins/inventory/
  InventoryPlugin.java            PlatformPlugin bean, packs, cells, roles
  api/                            controllers per pack (all @Perm), DTO records, problem codes
  app/                            application services (one transaction per command)
  domain/
    catalog/   Item, ItemRevision, Uom, UomConversion, Category, AttributeDef, Barcode
    stock/     Location, Balance, Movement, Lot, Unit, Reservation, Count, Share
    trade/     Party, PriceList, PurchaseOrder, SalesOrder, Receipt(GRN), Dispatch, Return, TradeLink
    mfg/       Bom, BomLine, Routing, Operation, WorkCentre, WorkOrder, Subcontract
    quality/   InspectionPlan, Inspection, Hold, Ncr, Capa
    planning/  MrpRun, PlannedOrder, Forecast
    costing/   CostLayer, StandardCost, ValuationEntry, Period
    devices/   Provisioning, Custody, RepairJob
  persistence/  JPA repos, native queries for balances, RLS session setter
  integration/  outbox consumer, core port adapters, partner webhooks out
  jobs/         scheduled jobs (ShedLock, one runner per job)
backend/plugins/inventory/src/main/resources/db/plugins/inventory/V100__…sql
frontend/projects/shared/widgets/inventory/<area>/<widget>/   widget folders
```

### 4.3 The stock engine (the heart; everything else posts through it)

Every pack changes stock only by calling **one** service: `StockLedger.post(Posting)`. A posting is a list of movement lines that must balance by quantity (from-location → to-location) and is applied atomically.

```
Posting {
  orgId, idempotencyKey, postingDate, sourceDoc {type, id, lineId},
  lines: [{ itemId, qty (in base UoM), lotId?, unitIds?, fromLocationId?, toLocationId?,
            movementType, reasonCode?, unitCost? }]
}
```

Invariants (each has a test):
1. **Append-only journal** `inv.movements`; corrections are reversals (`reverses_id`). No `UPDATE`/`DELETE` grants on it for the app role.
2. **Balance in the same transaction**: `inv.balances(org, item, location, lot)` → `on_hand, reserved, available = on_hand − reserved`. `CHECK (on_hand >= 0 AND reserved >= 0 AND reserved <= on_hand)` unless the location type allows negative (only `VIRTUAL_ADJUST`).
3. **Deadlock-free locking**: lines are sorted by `(item, location, lot)` and locked with `SELECT … FOR UPDATE` in that order.
4. **Exactly once**: `UNIQUE (org_id, idempotency_key)`; a replay returns the original result (`IDEMPOTENT_REPLAY`).
5. **Serialised units**: `inv.units` state changes with compare-and-set (`UPDATE … WHERE state=:from AND version=:v`), and every state change is also a movement line.
6. **Period lock**: no posting dated in a closed period (`PERIOD_CLOSED`); late corrections post in the open period with a reference.
7. **Costing hook**: every posting with a value impact emits cost-layer and valuation entries in the same transaction (if `costing` is on).
8. **Nightly recon**: `InventoryReconJob` rebuilds balances from the journal and unit states from unit movements; a difference raises a recon break (reuses the core reconciliation-break pattern via `PlatformContext.audit` + an `inv.recon_breaks` table) and blocks period close.

### 4.4 Location model

```
Org
 └─ Site (factory, DC, shop, van)                      inv.sites
     └─ Location (type)                                inv.locations (tree via parent_id)
          types: WAREHOUSE · ZONE · BIN · RECEIVING · QC_HOLD · STAGING · DISPATCH
                 PRODUCTION (WIP per work centre) · SCRAP · RETURNS · REPAIR
                 IN_TRANSIT (per counter-party) · CUSTODY (stock owned by me, held by org X)
                 CONSIGNMENT_IN (owned by vendor X, held by me) · SUBCONTRACTOR (at job-worker)
                 VIRTUAL_ADJUST (count/write-off counter-account)
```

**Ownership vs. possession** is the key idea for the network: a `CUSTODY` location belongs to the owner's books and names a `custodian_org_id`. TPTS terminals at a merchant, a manufacturer's consignment stock at a dealer, and material at a subcontractor are all custody. The custodian sees it read-only through the custody view; only the owner posts to it, except for the custodian's **declared acts** (confirm receipt, report a fault, report a sale of consignment stock) which arrive as requests the owner's rules auto-accept or queue.

### 4.5 Tracking modes

| Mode | Used for | What is stored |
|---|---|---|
| `QUANTITY` | bulk raw material, consumables | balances only |
| `LOT` | food, chemicals, pharma, batches of finished goods | `inv.lots(lot_no, mfg_date, expiry_date, qc_status, vendor_lot, coa_doc)`; picking FEFO/FIFO |
| `SERIAL` | devices, machines, high-value goods | `inv.units(serial, item, state, location, lot?, custodian_org_id, attributes, version)` |
| `LOT+SERIAL` | serialised goods made in batches | both |

**Genealogy** `inv.genealogy(parent_unit_or_lot, child_unit_or_lot, work_order_id)` records which component lots/serials went into which finished lot/serial. Backward trace ("which vendor lot is in this unit?") and forward trace ("where did every unit containing vendor lot X go, across the network?") are graph queries; the forward trace crosses orgs only through linked dispatches (§6) and returns counter-party orgs, not their internal stock, unless shared.

### 4.6 Data isolation

- Every `inv.*` table has `org_id NOT NULL` as the leading column of its primary key or of a unique index.
- **Postgres row-level security** as defence in depth: policies `USING (org_id = current_setting('app.org_id'))`; the plugin's `TenantSession` sets `SET LOCAL app.org_id` at the start of every transaction from the JWT org only. Jobs run per org with the same setter. A request can never set another org.
- Cross-org reads go through three explicit doors only: **shares** (`inv.shares`, read-only, revocable, per scope: items / balances / sales-out), the **custody view**, and **trade links** (the counter-party sees the status of a linked document, not the other side's stock).
- Parents get **no automatic access** to children's stock. The `network` pack shows channel stock only from children that granted a share (it can be a contractual condition the manufacturer enforces off-platform; D-INV-2 asks whether the grant can make it default-on).

### 4.7 Volumes and performance

- `inv.movements` partitioned by month (`posting_date`), BRIN on date, B-tree on `(org_id, item_id, posting_date)`.
- Balances are a materialised current state (not computed from the journal at read time).
- Stock-ledger reports and ageing read from a nightly `inv.balance_snapshots(org, date, item, location, lot, qty, value)`.
- Bulk imports (serial lists, opening stock, BOMs) are async jobs with a validation report before commit.
- Target: post a 50-line posting in < 150 ms p95; balance lookup < 20 ms p95; MRP run for 20k SKUs < 5 min.

### 4.8 Separate database schema

The plugin's data is hosted entirely in its own schema, and the database enforces the separation, not just the code.

| Aspect | Rule |
|---|---|
| Schema | `inv`, owned by role `inv_owner` (migrations only). Nothing from the plugin lives in the core schema; the core schema keeps only the generic framework tables (`org_plugin_grants`, `plugin_outbox`). |
| Runtime role | `inv_app`: `USAGE` on `inv`, DML on `inv` tables, **no `INSERT/UPDATE/DELETE` on `inv.movements`** (append via a `SECURITY DEFINER` function owned by `inv_owner`), and **no privileges on the core schema**. Core's role `mipay_app` gets **no privileges on `inv`**. `search_path` for each role is its own schema only. |
| Connection pool | Separate Hikari pool `inventoryDataSource` (`app.plugins.inventory.datasource.*`, env `INVENTORY_DB_URL/USER/PASSWORD`), its own `EntityManagerFactory` (packages `..plugins.inventory..` only) and `inventoryTransactionManager`. Core's `@Transactional` never covers plugin writes and vice versa. |
| Migrations | Own Flyway instance: location `db/plugins/inventory`, history table `inv.flyway_schema_history`, versions independent of core's `db/fresh` (V1, V2, … in its own sequence). Runs only when the plugin is in the build and enabled. Core's Flyway never touches `inv`. |
| Placement | Default: same Postgres server and database as core, different schema (no new container; the dev host is short on disk). Production can point `INVENTORY_DB_URL` at a separate database or server with no code change, because nothing crosses at SQL level. |
| Cross-schema references | Opaque ids only (`org_id`, `user_id`, `payment_id`, `terminal_id`, `invoice_id`). Integrity is checked by the application via `PlatformContext`/ports, and repaired by events (e.g. `OrgStatusChanged`). Core keeps opaque `inventory_unit_ref` on `card_terminals` and `inv_item_ref` on `order_items` the same way. |
| Consistency | No distributed transactions. Core → plugin: core outbox + plugin `inv.inbox(event_id PK)`. Plugin → core (terminal registry, invoice requests, notifications): `inv.outbox` relayed by a plugin job to the core ports, which are idempotent on the plugin's command id. Both directions are at-least-once with exactly-once effect. |
| RLS | Row-level security on every `inv` table (§4.6), keyed on `app.org_id` set by the plugin's own transaction. |
| Backup / restore / reset | `pg_dump -n inv` backs up or restores the plugin alone. The clean-start reset/reseed (`_goal.md`) drops and recreates `inv` independently. Restore of one side alone is followed by an outbox/inbox replay and the nightly recon. |
| Uninstall | Removing the plugin is `DROP SCHEMA inv CASCADE` plus deleting its grants and outbox rows. Core needs no migration. |
| Reporting | Cross-domain reporting (e.g. sales payments vs. stock) never joins schemas; it goes through the Analytics plugin fed by events from both sides. |

---

## 5. Functional modules (what a medium manufacturer gets)

### 5.1 Master data (`stock`)
- **Items**: SKU (unique per org), name, type (`RAW, COMPONENT, SUBASSEMBLY, FINISHED, PACKAGING, CONSUMABLE, SPARE, DEVICE, SERVICE`), tracking mode, base UoM, purchase/sales/production UoMs with conversions (incl. item-specific: 1 box = 12 pcs), category tree, variants (size/colour matrix → child SKUs), barcodes (EAN/GTIN/internal, several per item), weight/volume, shelf life, HS code, tax class, default locations, reorder parameters, status (`DRAFT → ACTIVE → BLOCKED → RETIRED`).
- **Item revisions** for engineered items (rev A/B), effective dates, linked to BOM revisions.
- **Custom attributes** per org (typed `TEXT, NUMBER, DATE, BOOL, ENUM`) on items, lots and units (terminal model, firmware, SIM ICCID, batch potency).
- **Bulk import/export** (CSV/XLSX) with dry-run validation.
- **Catalogue linking**: an item can be marked "sellable to network" which feeds sales price lists and (later) the [Catalogue Syndication](plugins/catalogue-syndication.md) plugin. A buyer maps the seller's SKU to its own SKU (`inv.item_cross_refs`) so linked documents carry both.

### 5.2 Inventory control (`stock`)
- Receive / issue / transfer (within site, between sites via IN_TRANSIT), adjust with reason codes.
- **Reservations** against sales orders, work orders and provisioning jobs, with expiry.
- **Counts**: full stock-take (freeze location) and cycle counts (ABC-driven schedule), blind counting on mobile, recount on variance, adjustment approval by a different person; value threshold escalates to the OA6 chain.
- **Reorder**: min/max, reorder point, fixed qty; generates alerts or draft POs / transfer requests (if `procurement`).
- **Alerts**: low stock, out of stock, expiring lots (n days), stuck in transit, QC hold ageing, dead stock; delivered in-app and via comms.
- **Expiry**: FEFO picking, auto-move expired lots to `QC_HOLD`, write-off proposal.

### 5.3 Devices / POS (`devices`) — the TPTS use case
1. TPTS org has `SELF` grant with `stock + devices + procurement + warehouse`.
2. Items "PAX A920", "SIM card", "Receipt paper" (SERIAL / SERIAL / QUANTITY) with attributes model, firmware, ICCID, IMEI.
3. Buy from vendor (external party) on a PO; GRN with **bulk serial scan or upload**; duplicates refused (`DUPLICATE_SERIAL`).
4. **Kitting**: terminal + SIM paired into a kit (genealogy link), firmware/key-injection recorded as an operation on the unit.
5. **Provision** to a merchant: `{unit or item, merchantOrgId, mode SALE|LEASE|LOAN|RENTAL, billingRef?}` → ALLOCATED → dispatch confirmed → DEPLOYED in `CUSTODY(merchant)`. `TerminalRegistryPort.register(unit, merchantOrgId)` creates/updates `card_terminals` (with `inventory_unit_ref`) in the same outbox-guaranteed flow, so acquiring works immediately.
6. Swap (atomic: old unit → RETURNS, new unit → custody, terminal id moves), return, repair (REPAIR location, repair job with vendor RMA), write-off, lost/stolen (blocks the terminal through the port).
7. Merchant sees `GET /api/merchant/devices` (core endpoint, read-only, works without the plugin). A merchant can **report a fault** → creates a repair request in TPTS's queue (a declared custodian act, §4.4).
8. Merchant suspended/blocked → its custody units are flagged for recovery (not auto-moved).
9. Billing for SALE/RENTAL: v1 billing reference; real invoice to the merchant when D-INV-3 lands (§8.3). Rental charges belong to the [Recurring Billing](plugins/recurring-billing.md) plugin, keyed by unit.

Unit states: `IN_STOCK → ALLOCATED → IN_TRANSIT → DEPLOYED → RETURNED → (IN_STOCK | IN_REPAIR | RETIRED | LOST)`, `IN_REPAIR → IN_STOCK | RETIRED`.

### 5.4 Procurement (`procurement`; absorbs the [Procurement](plugins/procurement.md) stub)
- **Vendors** = `inv.parties` with role VENDOR: linked org or external (name, tax id, contacts, payment terms, currency, lead time, rating). External vendors are not MiPay orgs and need no KYC; paying them goes through the org's normal payouts.
- **Vendor price lists** per item with validity, MOQ, pack size, quantity breaks, lead time.
- **Purchase requisitions** (from users, reorder rules, MRP) → **RFQ** to several vendors → comparison → **PO**.
- **PO approval** by amount bands (configurable per org), using `ApprovalPort` (OA6 chain); approved PO sent to vendor by email (comms) or, for a linked vendor org, creates the **linked sales order** on their side (§6).
- **GRN** against PO lines (partial, over-receipt tolerance), into RECEIVING or straight to QC_HOLD if the item has an inspection plan; lot and serial capture at receipt; labels printed.
- **3-way match** PO ↔ GRN ↔ vendor invoice (entered, or arriving as a MiPay invoice from a linked vendor), price/qty tolerances, mismatch queue. Matched invoices are what the org pays.
- **Vendor returns** (debit note reference), **landed cost** (freight, duty, insurance allocated by value/weight/qty into item cost), **vendor scorecard** (on-time, quality rejects, price variance).

### 5.5 Sales & distribution (`sales`)
- **Customers** = parties with role CUSTOMER: linked downstream orgs (distributors/dealers, suppliers, merchants) and external customers.
- **Price lists by channel**: one list per channel tier (distributor price, supplier price, MRP/retail), customer-specific overrides, quantity breaks, validity, currency. Promotions and schemes plug in from [Pricing & Promotions](plugins/pricing-promotions.md) through a `PriceAdjustmentPort` (no-op without it).
- **Sales orders**: entered by the seller, or **arriving as the linked side of a buyer's PO** (§6). Credit check against a per-customer limit and open receivables (the plugin keeps its own limit; MiPay credit/advance products are consulted read-only through `PlatformContext` when relevant, D-INV-5).
- **Allocation** (reserve stock; backorder the rest), **pick → pack → dispatch** (warehouse pack) or simple dispatch, delivery note, proof of delivery (photo/signature from mobile), shipping label.
- **Invoicing**: a dispatch produces an invoice request through `TradeInvoicePort` (§8.3); payment status flows back via events.
- **Returns (RMA)**: authorise, receive into RETURNS, inspect, restock / repair / scrap, credit note reference.
- **Warranty**: serial-based warranty start at dispatch or activation, claim intake (from dealers or merchants), replacement as a swap.
- **Secondary sales visibility**: a dealer's sales to its merchants are its own data; the manufacturer sees them only via a share (`network` pack).

### 5.6 Manufacturing (`manufacturing`)
- **Work centres** (machines/lines/labour pools) with capacity per shift, cost rate per hour.
- **BOMs**: multi-level, revisioned, effective-dated; line types `COMPONENT, PHANTOM, BY_PRODUCT, CO_PRODUCT`; scrap % per line; alternates; UoM per line; BOM explosion and where-used.
- **Routings**: ordered operations at work centres, setup and run times, inspection points, subcontracted operations.
- **Work orders**: `PLANNED → RELEASED → IN_PROGRESS → COMPLETED → CLOSED` (+ `CANCELLED`); created from MRP, sales order (make-to-order) or manually; reserves components; pick list to the line.
- **Material**: issue to WIP (manual) or **backflush** on completion; returns of unused material; component substitution with approval.
- **Production reporting** per operation (qty good / rejected / scrap, labour time, machine time) from a shop-floor mobile widget; finished goods receipt into QC_HOLD or stock with lot/serial assignment; genealogy recorded.
- **By-products and co-products** with cost split.
- **Subcontracting / job work**: send material to a subcontractor (SUBCONTRACTOR location, a custody type), receive processed goods, subcontract charge on a service PO.
- **Shop-floor view**: capacity load by work centre/week (infinite capacity, overload highlighted). Finite scheduling is a non-goal.

### 5.7 Quality (`quality`)
- **Inspection plans** per item/vendor at receipt, in-process (routing operation) and final; characteristics with spec limits; sampling (fixed n, % or AQL table).
- **QC hold**: lots/units under inspection sit in `QC_HOLD` and cannot be picked; release / reject / partial release; usage decision is an approval cell (not the inspector's own release on high-risk items: SoD).
- **NCR** (non-conformance) with disposition (use-as-is, rework, return to vendor, scrap), **CAPA** light (root cause, action, owner, due date).
- **CoA** documents attached to lots (via [Document Vault](plugins/document-vault.md) when present, else plugin file store).
- **Recall**: from a vendor lot or a finished lot, forward-trace and generate a recall list per counter-party org; notify them (comms); they see a recall task on the linked documents.

### 5.8 Planning (`planning`)
- **Demand**: open sales orders, linked-PO demand from the network, forecast (moving average / seasonal naive v1; manual override per item/month), safety stock.
- **MRP run** (nightly or on demand): net requirements by period through the BOM levels, considering on-hand, open POs, open WOs, lead times, lot-sizing (lot-for-lot, fixed, min/max, period order). Output: **planned orders** (buy → requisition, make → planned WO, move → transfer) and **exception messages** (expedite, defer, cancel).
- Planner accepts planned orders in bulk; accepted ones become documents.
- **Network replenishment** (with `network` pack + shares): suggests orders to dealers based on their shared stock and sales-out (vendor-managed inventory light).

### 5.9 Costing and valuation (`costing`)
- Per-org cost method per item category: **moving weighted average** (default), **FIFO** (cost layers), or **standard cost** (manufacturers) with variances (purchase price, material usage, labour/overhead, production, scrap).
- Landed cost and production cost (materials + labour + overhead rates from work centres) roll into finished-good cost.
- **Valuation journal** `inv.valuation_entries` (account keys, not GL accounts: `INVENTORY_RAW`, `WIP`, `FG`, `COGS`, `GRNI`, `PPV`, …) mapped to the org's chart of accounts by the Accounting Export plugin.
- **Period close**: lock date, recon must be clean, revaluation for standard cost changes, closing snapshot.
- Reports: stock valuation by location/category, COGS, gross margin by item/customer, WIP, variance analysis.

> Inventory value is **never** posted to MiPay's money ledgers (`ledger` domain). Those track money held for customers; mixing stock value in would break the regulatory ledgers. The two meet only through invoices and payments.

### 5.10 Warehouse operations (`warehouse`)
- Zones and bins with capacity and item-class rules; **putaway** suggestions (fixed bin / next empty / same-item); **pick** strategies (FEFO, FIFO, nearest bin), pick lists by order or wave, **pack** with cartons/pallets (SSCC), **dispatch staging**.
- Mobile **scanner flows** (camera `BarcodeDetector` with a fallback library, or Bluetooth/keyboard-wedge scanners): receive, putaway, pick, count, move, serial lookup. Offline queue for poor connectivity: commands carry idempotency keys and replay when online (the stock engine makes replay safe).
- **Labels**: item, bin, lot, serial, carton labels as PDF and ZPL.
- Replenishment from reserve to pick-face bins.

### 5.11 Reporting and analytics (all packs)
Stock ledger per item; stock on hand by site/location/lot; ageing; ABC/XYZ classification; dead and slow-moving stock; inventory turns and days of cover; fill rate and OTIF (sales); vendor performance; production yield, scrap and OEE-lite; expiry exposure; serial history; device fleet by state and merchant (TPTS); valuation. Each is a widget with export (`inv.report:export`). Cross-org analytics belong to the [Analytics](plugins/analytics.md) plugin, fed by our events.

---

## 6. Cross-org flows (the network)

The rule: **each org writes only its own documents and stock**. Two orgs cooperating on a shipment each own one side of a **trade link**.

### 6.1 Linked documents

```
 Buyer org (e.g. Distributor)                 Seller org (e.g. Manufacturer)
 ───────────────────────────                  ─────────────────────────────
 PO created & approved ──── link request ──▶  SO created (status NEW, from link)
                       ◀─── accepted/priced ── SO confirmed (or rejected / counter-offer)
                                              Allocation, pick, pack
                                              Dispatch posted: FG → IN_TRANSIT(buyer)
 Incoming shipment visible ◀── link event ──  Invoice via TradeInvoicePort
 GRN posted: → RECEIVING/stock
   ├─ match: link CLOSED ─────────────────▶   in-transit cleared (DELIVERED)
   └─ short/damaged: DISPUTED ────────────▶   dispute queue (credit / re-ship)
```

- `inv.trade_links(id, buyer_org, seller_org, buyer_doc, seller_doc, state, version)` lives in a shared, **link-only** table: each side can see link rows where it is a party; RLS policy is `buyer_org = me OR seller_org = me`. It holds states and line references, never stock.
- Link messages are commands to the other org's application service, executed **as the system on behalf of the link**, validated against that org's own rules (it may auto-accept or queue for a human). They go through the same internal outbox so a failed side retries and never half-applies.
- Seller's IN_TRANSIT is cleared only by the buyer's GRN event (or a timeout escalation after N days → `stuck in transit` alert both sides).
- **Neither side can see the other's stock** unless shared.
- Links can be made only between orgs that both have the plugin with `sales` (seller) and `procurement` or `stock` (buyer). If the buyer has no plugin (e.g. a merchant without it), the seller's dispatch completes on proof-of-delivery, and the core B2B order flow continues as today.
- Allowed directions follow the org tree and existing trading relations: a child can buy from any ancestor, from a sibling under the same manufacturer if the manufacturer's grant config allows (`config.lateralTrade`), and from any linked vendor org it has a trading relation with. Cross-network trade (two manufacturers) needs both orgs to accept a party link first.

### 6.2 Consignment and VMI
- Manufacturer places stock at a dealer as **consignment**: in the manufacturer's books it sits in `CUSTODY(dealer)`; in the dealer's books (if it has the plugin) it shows in `CONSIGNMENT_IN(manufacturer)`, non-owned, non-valued.
- The dealer reports consumption/sales (declared custodian act); that converts to a sale + invoice on the manufacturer's side and a purchase + receipt on the dealer's side, linked.

### 6.3 Merchant sales from core payments
- `SaleConfirmed` (from `PaymentService:203`) → plugin posts an ISSUE from the merchant's default SHOP location (if the merchant has the plugin), keyed by `paymentId`. Refund → reversal keyed by `refundId`.
- `InvoiceSettled` (from `B2BPaymentService:326`) → if the merchant has the plugin and no trade link exists, posts a RECEIVE (legacy behaviour); if a link exists the GRN on the link already did it (no double receipt; covers TC-ACC-04).

---

## 7. Access control

### 7.1 Two gates on every request
1. **RBAC** (gateway + `@Perm`): does this user hold the cell in this org?
2. **Plugin gate** (`PluginAccess`): does this org have the pack, and in which mode?

Both must pass. The plugin gate runs as a Spring `HandlerInterceptor` keyed by an `@InvPack("procurement")` annotation on each controller, so a missing annotation fails the startup audit (same style as the `@Perm` audit).

### 7.2 Cells (resources × fixed verbs)

| Resource | Verbs used |
|---|---|
| `inv.item`, `inv.attribute`, `inv.location`, `inv.party`, `inv.price_list` | read, create, modify, delete, export |
| `inv.stock` (balances, movements) | read, export |
| `inv.movement` (receive/issue/transfer) | create |
| `inv.adjustment` | create, submit, authorize |
| `inv.count` | create, modify, submit, authorize |
| `inv.unit` | read, modify, execute (state transitions) |
| `inv.provisioning` | read, create, execute (dispatch), authorize |
| `inv.requisition`, `inv.purchase_order` | read, create, modify, submit, authorize, execute (send) |
| `inv.receipt` (GRN) | read, create, execute (post) |
| `inv.invoice_match` | read, modify, authorize |
| `inv.sales_order` | read, create, modify, submit, authorize (credit override), execute (allocate) |
| `inv.dispatch`, `inv.return` | read, create, execute, authorize |
| `inv.bom`, `inv.routing`, `inv.work_centre` | read, create, modify, submit, authorize (release revision) |
| `inv.work_order` | read, create, modify, execute (release/report), authorize (close with variance) |
| `inv.inspection`, `inv.ncr` | read, create, modify, authorize (usage decision) |
| `inv.mrp` | read, execute (run), authorize (accept planned orders) |
| `inv.cost` (std cost, period) | read, modify, authorize (close period / revalue) |
| `inv.share`, `inv.trade_link` | read, create, delete |
| `inv.report` | read, export |
| `platform.plugin` | read, administer (TPTS only) |

**SoD rules** (declared in the grid, enforced by RBAC): submit and authorize on the same row are never in one role for `adjustment`, `count`, `purchase_order`, `invoice_match`, `inspection`, `cost`; the user who posted a GRN cannot authorize the match for that PO (cross-row DYNAMIC, needs permission-atlas step 2).

### 7.3 Roles (grid fragment shipped with the plugin)
`inv_viewer`, `inv_storekeeper`, `inv_warehouse_lead`, `inv_buyer`, `inv_purchase_approver`, `inv_sales_clerk`, `inv_sales_manager`, `inv_planner`, `inv_production_supervisor`, `inv_operator`, `inv_quality_inspector`, `inv_quality_manager`, `inv_cost_accountant`, `inv_manager` (union, no SoD-conflicting pairs), `tpts_device_ops`, `tpts_device_approver`.

- Roles are **eligible for a ceiling only if the org has the matching pack**; the role ceiling service filters them (a plugin role in a ceiling of an org without the pack is inert, never an error).
- Orgs build their own roles from these via org-defined roles (X8–X10), so a small dealer can make one "store person" role.
- Granting a pack does **not** auto-assign roles; `org_admin` assigns them. Exception: when the plugin is first granted, the org's privileged user gets `inv_manager` so someone can start.

### 7.4 Tiers (when OA5/OA6 land)
T1 routine: receive, pick, issue, transfer · T2 step-up: price list change, item block, share create · T3 4-eyes: adjustments and write-offs over threshold, PO over band, period close, std cost revaluation, credit override · T4: plugin grant changes on a manufacturer subtree. Until OA6 exists the plugin enforces 4-eyes locally (a different user with the authorize cell).

---

## 8. Integration with the existing system

### 8.1 Core → plugin events (durable outbox)

`public.plugin_outbox(id, event_type, org_id, payload jsonb, created_at, delivered_at, attempts)` written **in the same transaction** as the core change. A **core** relay (`PluginEventRelay`, core DataSource) polls it (`SKIP LOCKED`) and calls each registered plugin's `PluginEventSink.accept(event)`. The plugin, in its own transaction on its own DataSource, inserts the event into `inv.inbox(event_id PK)` and applies it; a duplicate id is a no-op. Only after the sink returns does the relay mark the row delivered. The plugin never reads the core schema. When the plugin is disabled no sink is registered; rows are pruned after 30 days.

| Event | Published at | Plugin reaction |
|---|---|---|
| `SaleConfirmed{paymentId, merchantOrgId, itemRef, qty}` | `PaymentService` (replaces the direct `inventoryService.recordSale` call at L203) | ISSUE from SHOP |
| `RefundConfirmed{refundId, paymentId, qty}` | refund service | reversal |
| `InvoiceSettled{invoiceId, sellerOrgId, buyerOrgId, lines}` | `B2BPaymentService` (replaces L326) | RECEIVE or close trade link |
| `TradeInvoicePaid / Cancelled{invoiceRef}` | invoicing (after §8.3) | SO/link payment status, credit exposure |
| `OrgOnboarded{orgId, parentId, type}` | `OrgOnboardingService` | seed default site/location if the org inherits the plugin |
| `OrgStatusChanged{orgId, status}` / `OrgSuspended` / `OrgUnsuspended` | lifecycle / suspension services | flag custody units, pause open links, notify counter-parties |
| `TerminalStatusChanged{terminalId, status}` | payments | mirror on the unit (e.g. lost) |
| `PluginGrantChanged{orgId, key, packs}` | plugin admin | read-only switches, job scheduling |

### 8.2 Plugin → core ports

| Port | Implemented by core using | Used for |
|---|---|---|
| `TerminalRegistryPort` | `card_terminals` repository + new `inventory_unit_ref` column | provision, swap, block, retire |
| `TradeInvoicePort` | B2B invoicing (generalised, §8.3) | invoice on dispatch, credit notes, payment status |
| `B2BOrderPort` | today's `Order` / `OrderItem` / `SupplierCatalogueItem` repositories and the approved-supplier and `requireOperationalForEntity` checks now in `InventoryService.reorder` | merchant one-tap reorder (TC-OPS-03); later, link a plugin PO to a core B2B order |
| `NotificationPort` | comms client (email/SMS/in-app) | alerts, PO to external vendor, recall notices, link requests |
| `ApprovalPort` | OA6 approval chains; local 4-eyes fallback until then | PO bands, adjustments, period close |
| `PartyDirectoryPort` | org directory + `OrgTreeService` | pick a linked org as vendor/customer within allowed relations, org summaries |
| `AuditPort` (inside `PlatformContext`) | `AuditService` | every authorize/execute and every grant-affecting change |
| `FileStorePort` | Document Vault plugin if granted, else core file store | CoA, POD photos, labels |

Read-only ports (`PartyDirectoryPort`, `PlatformContext`) are called directly. Ports that **change core state** (`TerminalRegistryPort`, `TradeInvoicePort`, `NotificationPort`) are never called inside a plugin transaction: the plugin writes a command to `inv.outbox` with its stock change, and a plugin job delivers it to the port, which deduplicates on the command id. A provisioning is therefore `ALLOCATED → dispatch posted → terminal registered`, and the unit shows `REGISTRATION_PENDING` until the port confirms.

### 8.3 Invoicing and money (core change needed)
Today `invoices` are `Supplier → Merchant` only. For manufacturer → distributor → supplier trade and for TPTS → merchant device sales, core needs **org-to-org invoices**: `invoices.seller_org_id / buyer_org_id` (keeping the old FKs as views during transition), payable by `InvoicePayment` to any seller org with a settlement account. That is a core payments change with its own review (ledger postings, fees, limits) and is decision **D-INV-3**. Until it lands:
- links and dispatches work fully;
- the seller's SO records an external billing reference;
- merchant ↔ supplier trade keeps using today's B2B orders, with `InvoiceSettled` driving stock.

### 8.4 Existing code: what moves into the plugin, what changes, what stays

Inventory today is the merchant feature K05/K06 (stock list, movements, low-stock alerts, one-tap reorder). **All of it moves out of core** into `:plugins:inventory`. The core keeps no inventory classes, tables or DTOs. What it keeps is the payments, B2B and terminal code that *used to call* inventory, and that code now publishes events or implements a port.

There is **no compatibility adapter in core** (this replaces the earlier `InventoryPort` idea). The plugin serves the old merchant URLs itself, at the same paths, with the same `merchant.inventory` cells and the same JSON. That way the sme-ui screen, the role pack and the UAT assertions don't change. With the plugin off, those URLs don't exist (404).

#### A. Moves into the plugin (deleted from core, re-implemented on the stock engine)

All paths are relative to `backend/src/main/java/com/mipay/sme/`. New classes are in `com.mipay.sme.plugins.inventory`.

| Existing class / member | Today | In the plugin |
|---|---|---|
| `domain/ops/InventoryItem` | `inventory_items`: merchant item with a materialised `current_stock` and `reorder_level` | `domain.catalog.Item` (`inv.items`) + `domain.stock.Balance` (`inv.balances`) for the merchant's default SHOP location. The reorder level goes to `ReorderRule`. |
| `domain/ops/StockMovement` | `stock_movements`: append-only movement rows | `domain.stock.Movement` (`inv.movements`) posted only through `StockLedger.post` |
| `domain/ops/StockAlert` | `stock_alerts`: open alerts per item, upserted | `domain.stock.Alert` (`inv.alerts`), same raise-once and resolve-on-recovery rules |
| `domain/ops/ReorderRule` | `reorder_rules`: level, preferred supplier, order quantity | `domain.stock.ReorderRule` (`inv.reorder_rules`); the preferred supplier becomes a `party_id` that refers to the supplier org |
| `repository/InventoryItemRepository`, `StockMovementRepository`, `StockAlertRepository`, `ReorderRuleRepository` | Spring Data repositories on the core DataSource | Plugin repositories on the `@Inventory` DataSource (`persistence/`) |
| `service/InventoryService` | `addItem`, `adjust`, `recordSale`, `recordDelivery`, `evaluateAlerts`, `reorder` | Split by job: `app.ItemService.create` · `app.MovementService.adjust` · `integration.SaleConfirmedHandler` (was `recordSale`) · `integration.InvoiceSettledHandler` (was `recordDelivery`, matching by catalogue item and no longer by product name) · `app.AlertEvaluator` (runs after every posting) · `app.ReorderService.reorder` (creates the core B2B order through the new `B2BOrderPort`, §8.2) |
| `MerchantController`, the 6 inventory handlers: `GET /inventory`, `POST /inventory`, `POST /inventory/adjust`, `GET /inventory/alerts`, `GET /inventory/rules`, `POST /inventory/{itemId}/reorder` (and their private helpers `stockState`, `supplierName`) | In core, reading the 4 repositories directly | `api.legacy.MerchantInventoryController` at the **same** `/api/merchant/inventory/**` paths with the same `@Perm(merchant.inventory, …)` and `@InvPack("stock")`. The merchant's org comes from the JWT, as `mid()` does today. |
| `MerchantController` fields `inventoryService`, `stockAlertRepo`, `reorderRuleRepo`, `inventoryItemRepo` | injected | removed |
| `web/Dtos.InventoryRow`, `StockAdjustRequest`, `StockAlertRow`, `ReorderRow`, `ReorderRequest` | core DTO records | `api.legacy.LegacyDtos`, field for field the same (the JSON contract is frozen for sme-ui) |
| `domain/Enums.InventoryItemStatus`, `StockMovementType` (`SALE, DELIVERY, ADJUSTMENT, WASTE`), `StockAlertType` | core enums | plugin enums. The legacy `StockMovementType` values map onto movement types: SALE→`SALE_ISSUE`, DELIVERY→`RECEIVE`, ADJUSTMENT→`ADJUST`, WASTE→`WRITE_OFF`. |
| `config/DataSeeder`: the inventory + reorder-rule block (around L291) and `seedItem(...)`, and its `InventoryItemRepository` / `InventoryService` fields | seeds sandbox merchant stock | `integration.InventoryDemoSeeder`, run by the plugin's own reset/reseed step (sandbox only), after core seeding |

#### B. Stays in core, changed

| Class | Change |
|---|---|
| `service/PaymentService` (field `inventoryService`, call at L203) | Remove the field and the call. In the same transaction, write `SaleConfirmed{paymentId, merchantOrgId, itemRef=p.itemId, qty}` to `plugin_outbox`; refunds write `RefundConfirmed`. |
| `service/B2BPaymentService` (field `inventoryService`, call at L326) | Remove both. Write `InvoiceSettled{invoiceId, sellerOrgId, buyerOrgId, lines[catalogueItemId, productName, qty]}`. |
| `domain/payments/Payment.itemId` / `itemQuantity` | Stay. `item_id` becomes an **opaque** reference to `inv.items.id` (no FK, already none). |
| `domain/b2b/OrderItem.inventoryItemId` | Renamed to `invItemRef` (column `inv_item_ref`), opaque |
| `domain/payments/CardTerminal` + `CardTerminalRepository` | Stay in core (acquiring owns terminals). Add `inventoryUnitRef`. **New** core class `service/TerminalRegistryService` implements `TerminalRegistryPort`. Nothing creates card terminals today, so this is new code, not moved code. |
| `service/MerchantViewsService` (reads the active terminal for `PayInfo`) | Unchanged. The new core `GET /api/merchant/devices` reads `card_terminals` plus a read-only device summary that the plugin pushes through the port (no SQL into `inv`). |
| `MerchantController GET /catalogue` | Stays in core (it is the B2B supplier catalogue, not inventory). Its cell changes from `merchant.inventory:read` to `merchant.order:read`, so switching the plugin off doesn't hide ordering. Role pack regenerated. |
| `domain/Enums` | Lose the three inventory enums |
| New in core (framework, not inventory): `plugin/` package (`PlatformPlugin`, `PluginRegistry`, `PluginAccess`, `PlatformContext`, `PluginEventRelay`, `PluginEventSink`, port interfaces), `OrgPluginGrant` entity + repository, `PluginOutbox` entity + repository, `PluginAdminController` | see §3 |

#### C. Stays in core, unchanged (named so nobody moves them by mistake)

- `domain/core/Supplier`, `SupplierCatalogueItem`, `domain/b2b/Order`, `Invoice`, `InvoicePayment`, `SupplierController`, the B2B order/pay flow: they are commerce and money, not stock. The plugin's `sales`/`procurement` packs **link** to them through `B2BOrderPort` / `TradeInvoicePort` and don't replace them.
- `ProvisioningService`: it provisions merchants and suppliers into the bank simulator, not devices. The plugin's device provisioning is unrelated.
- `domain/ops/Khata*`, `Tab*`, `Settlement`, `Statement`, etc.: they are in the same `ops` package, but they are not inventory.
- `security/rbac/PublicEndpoint` and `UiWidgetController`: they only mention "permission inventory" in comments.

#### D. Database (`db/fresh/V1__fresh_schema.sql`)

| Object | Change |
|---|---|
| `inventory_items`, `stock_movements`, `stock_alerts`, `reorder_rules` and their PKs, check constraints and the 4 `idx*` indexes | **Removed** from the core fresh schema; re-created as `inv.items`, `inv.movements`, `inv.alerts`, `inv.reorder_rules` (+ `inv.balances`) by the plugin's own Flyway V1 |
| `order_items.inventory_item_id` | renamed `inv_item_ref` |
| `card_terminals` | + `inventory_unit_ref varchar(36)` |
| New core tables | `org_plugin_grants`, `plugin_outbox` |
| Clean start | No data copy (`_goal.md`); sandbox stock is reseeded by `InventoryDemoSeeder` |

#### E. Tests (`backend/src/test/java/com/mipay/sme/uat/`)

| Test | Change |
|---|---|
| `TUat11MerchantToolsTests` TC-OPS-01, 02, 03 | Move to the plugin module as `TUat11InventoryCompatTests`. Same test ids, same HTTP calls and assertions. They run with the plugin enabled and the merchant granted `stock`. TC-OPS-04/05/09 and M26 stay in core. |
| `UatSupport` fields `inventory`, `inventoryItems`, `stockMovements`, `stockAlerts`, `reorderRules` (L188, L249–252) | Removed. Plugin tests get a `PluginUatSupport` that extends `UatSupport` and adds plugin repositories. |
| `TUat03AcceptanceTests` TC-ACC-04 (no second stock adjustment on a duplicate callback) | Stays in core. Its stock assertion now goes through the plugin API, and it adds a check that the duplicate callback wrote **one** outbox event (and the plugin applied it once). |
| New | TC-PLG / TC-INV / … suites in §12, in the plugin module |

#### F. Frontend

| File | Change |
|---|---|
| `frontend/src/app/features/merchant/screens.ts` `MerchantInventory` (L457–) | Stays in sme-ui until I2 ends (the URLs and JSON are unchanged). Then replaced by widgets `inventory.stock-on-hand`, `inventory.movement-form`, `inventory.alerts`, `inventory.reorder` under `frontend/projects/shared/widgets/inventory/`, and the component is deleted. |
| `frontend/src/app/core/services.ts` L54–61 (`inventory`, `addInventoryItem`, `adjustStock`, `stockAlerts`, `reorderRules`, `reorder`), `core/dto.ts` L127–129 (`InventoryRow`, `StockAlertRow`, `ReorderRow`) | Deleted together with the screen; the widgets' data sources replace them (`shared/widgets/data-sources.ts`) |
| `frontend/src/app/app.routes.ts` L47 `merchant/inventory`, `shared/web-core/menu.ts` `minventory` | Kept; the menu entry gets a plugin-mode gate (hidden when the pack is `NONE`) |
| `shared/web-core/portal-permission-catalogue.ts`: merchant `stock` menu (L87), distributor `inventory` menu (L113), supplier `catalogue` › `stock` screen (L128) | These existing placeholder screens become plugin widget screens. The `nav.*` ids are kept, so their grants survive. |
| `shared/web-core/permissions.ts` `merchant.inventory:*` | Kept; the plugin now owns the cells. The new `inv.*` cells are added by `perms:drift` regeneration. |
| Not inventory: the admin `/inventory` route and `PermInventory`, and the "All permissions" menu entry | These are the *permission* inventory. They stay as they are. |

#### G. Grants for existing merchants
- Merchants onboarded under a manufacturer with a `SUBTREE` grant get `stock` automatically. Standalone merchants get a `SELF stock` grant at go-live if `config.defaultMerchantStock=true` (D-INV-4). The sandbox seed grants the seeded merchant, so TC-OPS stays green.

### 8.5 RBAC and gateway
- `@Perm` on every plugin handler → `EndpointManifestPublisher` registers the `inv.*` cells with trustplane RBAC automatically; the gateway enforces them on `/api/plugins/inventory/**` with no route change (it already forwards `/api/**` to `mipay`).
- The plugin's roles go into `grid.yaml` as a fragment (`grid.d/inventory.yaml`) merged at role-pack generation; the role pack is regenerated and re-signed by compliance (`role-pack-v1.md` process).
- `perms:drift` (permission atlas step 1) covers the new cells and the widget manifests.

### 8.6 Frontend
- All screens are **widgets** under `frontend/projects/shared/widgets/inventory/…`, built only from the shared design-system components (`mp-data-table`, `mp-tabs`, `portal-theme.scss`), with manifests (`requires`, `actions` mapped to `view/create/modify/approve`), fixtures (`default`, `empty`, `error`, `longText`), and per-tier sizes. Data only through `WidgetDataPort` sources (`inventory.items`, `inventory.balances`, …) declared in `data-sources.ts`.
- Widgets also hide when `GET /api/orgs/me/plugins` says the pack is `NONE`; in `READ_ONLY` their write actions disable with a reason.
- Menus per portal (built with the portal builder; layouts in `layouts/<portal>.json`):

| Portal | Menu "Operations" |
|---|---|
| admin (TPTS) | Devices (fleet, provision, repairs), Device stock, Purchasing, Plugin grants |
| manufacturer | Items & BOMs, Stock, Warehouse, Purchasing, Production, Quality, Planning, Sales & dispatch, Network stock, Costing, Reports |
| distributor / supplier | Items, Stock, Purchasing (from manufacturer), Sales & dispatch, Returns, Reports |
| merchant (sme-ui) | Stock, Reorder, My devices (always, even without the plugin) |

- First widgets (by phase): `inventory.stock-on-hand`, `inventory.item-editor`, `inventory.movement-form`, `inventory.count-sheet`, `devices.fleet-board`, `devices.provision`, `purchasing.po-editor`, `purchasing.grn-scan`, `sales.order-board`, `mfg.work-order-board`, `mfg.bom-editor`, `quality.hold-queue`, `planning.mrp-exceptions`.
- **Mobile scanner** widgets are first-class (xs tier first): receive, pick, count, serial lookup, shop-floor report.

### 8.7 Partner API and external systems
- Same REST API exposed to partner clients through the FAPI 2.0 lane (mTLS + DPoP, client registered per org), so a dealer's own ERP or a handheld-scanner app integrates without a portal session.
- Outbound **webhooks** (via [Partner Webhooks](plugins/partner-webhooks.md) when granted): `stock.low`, `po.approved`, `so.dispatched`, `link.requested`, `recall.issued`.
- CSV/XLSX import/export everywhere; accounting via the Accounting Export plugin consuming `inv.valuation_entries`.

### 8.8 Other plugins that build on this one
Delivery & Routes (consumes dispatches), Asset Lifecycle (non-stock fixed assets; devices stay here), Field Service (uses device units and spares), Pricing & Promotions (price adjustment port), Catalogue Syndication (sellable items), Analytics (events), Accounting Export (valuation journal), Commissions (sales-out per dealer).

---

## 9. API shape

Base `/api/plugins/inventory`. JSON, RFC 9457 problem details, cursor paging, `Idempotency-Key` header required on every POST that posts stock or changes state, `If-Match` (version) on every PUT.

| Area | Endpoints (abridged) |
|---|---|
| Master | `GET/POST /items`, `PUT /items/{id}`, `POST /items/{id}/block|retire`, `/items/import`, `/uoms`, `/categories`, `/attributes`, `/sites`, `/locations`, `/parties`, `/price-lists` |
| Stock | `GET /balances`, `GET /movements`, `POST /movements` (receive/issue/transfer), `POST /adjustments` → `/{id}/submit|authorize`, `GET /lots`, `GET /units`, `GET /units/{serial}/history`, `POST /units/{id}/transition`, `/reservations`, `/counts…`, `/reorder-rules`, `/alerts`, `/shares` |
| Devices | `POST /provisioning`, `/{id}/dispatch|return|swap`, `/repairs`, `GET /fleet` · core `GET /api/merchant/devices`, `POST /api/merchant/devices/{id}/fault` |
| Procurement | `/requisitions`, `/rfqs`, `/purchase-orders` (+ `/submit|authorize|send|close`), `/receipts`, `/invoice-matches`, `/vendor-returns`, `/landed-costs` |
| Sales | `/sales-orders` (+ `/confirm|allocate|cancel`), `/dispatches`, `/returns`, `/warranty-claims` |
| Links | `GET /links`, `POST /links/{id}/accept|reject|dispute` |
| Manufacturing | `/work-centres`, `/boms` (+ `/explode`, `/where-used`), `/routings`, `/work-orders` (+ `/release|issue|report|receive|close`), `/subcontracts` |
| Quality | `/inspection-plans`, `/inspections` (+ `/decide`), `/holds`, `/ncrs`, `/recalls` |
| Planning | `POST /mrp/runs`, `GET /mrp/runs/{id}/planned-orders`, `POST /planned-orders/accept`, `/forecasts` |
| Costing | `/costs/standard`, `/periods` (+ `/close`), `GET /valuation`, `GET /valuation-entries` |
| Reports | `GET /reports/{name}?…&format=json|csv|xlsx` |

Problem codes: `PLUGIN_NOT_ENABLED` (404), `PLUGIN_READ_ONLY`, `PACK_NOT_ENABLED`, `ORG_SUSPENDED`, `INSUFFICIENT_STOCK`, `INVALID_UNIT_TRANSITION`, `DUPLICATE_SERIAL`, `LOT_ON_HOLD`, `PERIOD_CLOSED`, `INDEPENDENCE_RULE`, `APPROVAL_REQUIRED` (with chain), `LINK_NOT_ALLOWED`, `CREDIT_LIMIT`, `STALE_VERSION`, `IDEMPOTENT_REPLAY`.

---

## 10. Data model (tables, `inv` schema)

All tables: `org_id`, `id uuid`, `version`, `created_at/by`, `updated_at/by`; RLS on `org_id` (except `trade_links`: buyer or seller).

- **Master**: `items, item_revisions, item_variants, uoms, uom_conversions, categories, barcodes, attribute_defs, attribute_values, item_cross_refs, sites, locations, parties, party_contacts, price_lists, price_list_lines`
- **Stock**: `balances, movements (partitioned), postings, lots, units, unit_attributes, genealogy, reservations, counts, count_lines, adjustments, reorder_rules, alerts, shares, balance_snapshots, recon_breaks`
- **Integration**: `inbox` (core events received), `outbox` (commands to core ports), `flyway_schema_history`
- **Devices**: `provisionings, custody_requests, repair_jobs`
- **Procurement**: `requisitions, rfqs, rfq_quotes, purchase_orders, po_lines, receipts, receipt_lines, vendor_invoices, invoice_matches, vendor_returns, landed_costs, landed_cost_allocations`
- **Sales**: `sales_orders, so_lines, allocations, dispatches, dispatch_lines, packages, returns, return_lines, warranties, warranty_claims`
- **Links**: `trade_links, trade_link_lines, trade_link_events`
- **Manufacturing**: `work_centres, boms, bom_lines, routings, routing_ops, work_orders, wo_components, wo_operations, wo_reports, subcontracts`
- **Quality**: `inspection_plans, plan_characteristics, inspections, inspection_results, holds, ncrs, capas, recalls, recall_targets`
- **Planning**: `forecasts, mrp_runs, planned_orders, mrp_exceptions`
- **Costing**: `cost_layers, standard_costs, valuation_entries, periods, cost_rollups`
- **Core additions (core schema, generic framework only, no inventory data)**: `org_plugin_grants`, `plugin_outbox`, `card_terminals.inventory_unit_ref` (opaque), `order_items.inv_item_ref` (opaque), (D-INV-3) `invoices.seller_org_id/buyer_org_id`

---

## 11. Security invariants (each one tested)

1. Only TPTS (`platform.plugin:administer`) grants/revokes; `SUBTREE` cascades only downward; pack sets are coherent.
2. An org never reads or writes another org's stock: RLS + service checks; cross-org only by share (read), custody view (read + declared acts), trade link (states).
3. Disabled/suspended → read-only immediately, no cache.
4. Journal append-only; balances never negative; every event/command applies exactly once; recon detects tampering.
5. Second person for adjustments over threshold, write-offs, count approvals, PO bands, period close, QC usage decisions on high-risk items.
6. Serial unique per org; one unit has exactly one location and state.
7. Every plugin handler has `@Perm` and `@InvPack` (startup audit + test).
8. No inventory value ever reaches the money ledgers.
9. Partner API callers are bound to one org by their client registration; they cannot address another org.
10. Bulk imports are validated and committed atomically, or not at all.
11. Schema separation holds at the database: `inv_app` cannot read or write the core schema, `mipay_app` cannot read or write `inv`, and `inv_app` cannot update or delete journal rows.

---

## 12. Tests

| Suite | Covers |
|---|---|
| `TUat22PluginFrameworkTests` (TC-PLG) | SELF/SUBTREE/packs resolution, inheritance by later-onboarded orgs, revoke → read-only, own grant survives parent revoke, only TPTS grants, ArchUnit, outbox/inbox exactly-once (relay crash between sink and mark-delivered), plugin disabled → app boots and core pack green; **schema isolation**: as `inv_app` a `SELECT` on a core table fails, as `mipay_app` a `SELECT` on `inv.items` fails, `UPDATE inv.movements` fails; plugin runs against a separate database (`INVENTORY_DB_URL` ≠ core URL) with the full TC-INV pack green; `DROP SCHEMA inv` leaves core green |
| `TUat23InventoryStockTests` (TC-INV) | receive/issue/transfer/lot/serial, negative refused, idempotent replay, concurrent issue on short stock (one wins), reversal, counts + 4-eyes, reorder/alerts, cross-org 404 incl. RLS with a forged org, shares, recon detects tamper, period lock |
| `TUat24DeviceProvisioningTests` (TC-POS) | bulk serial receive, duplicate refused, provision → dispatch → DEPLOYED + `card_terminals` linked, merchant `/devices` without plugin, swap atomic, fault report, concurrent provision of one serial, suspended merchant refused |
| `TUat25ProcureToStockTests` (TC-P2S) | requisition → PO approval band → GRN with QC hold → release → 3-way match, tolerances, vendor return, landed cost into item cost |
| `TUat26OrderToDeliveryTests` (TC-O2D) | linked PO↔SO between manufacturer and distributor, allocation/backorder, dispatch → in-transit → GRN closes link, short receipt → dispute, credit limit, RMA, merchant `InvoiceSettled` no double receipt (TC-ACC-04) |
| `TUat27ManufacturingTests` (TC-MFG) | BOM revisions, explosion, WO release reserves, issue/backflush, scrap, by-product, FG receipt into QC, genealogy, subcontract round trip, std cost variances |
| `TUat28PlanningQualityTests` (TC-PLN/QA) | MRP netting through 3 levels with lead times and lot sizing, exceptions, accept planned orders; inspection plan, NCR, recall forward trace across orgs |
| Widget tests | manifest conformance, fixtures, a11y, Playwright headless flows for scanner widgets |
| Regression | `TUat11` TC-OPS-01/02/03, TC-ACC-04 and the full pack unchanged |
| Load | 10M-row movements partition test; 50-line posting p95; MRP 20k SKUs |

---

## 13. Phases

Gate (from `master-plan.md` §2): plugins start after RBAC enforces (done, 2026-11-12) and after the portal builder's widget contract and BFF lane (steps 1–5), since all screens are widgets.

| Phase | Work | Exit |
|---|---|---|
| I0 | Decisions D-INV-1…9; grid fragment review with compliance | signed |
| I1 | Plugin framework: grants + packs, `PluginAccess`, `PlatformContext`, ports (no-op defaults), `plugin_outbox`, ArchUnit, admin grant API + widget | TC-PLG |
| I2 | Stock engine: items, UoM, sites/locations, journal, balances, RLS, lots, serials, reservations, counts, reorder/alerts, recon; move the existing merchant inventory code into the plugin exactly as listed in §8.4 (A–G); `SaleConfirmed`/`InvoiceSettled` on the outbox | TC-INV, `TUat11InventoryCompatTests` (TC-OPS-01..03), TC-ACC-04, full core pack; `grep -r "domain.ops.InventoryItem\|InventoryService" backend/src/main` returns nothing |
| I3 | Devices (TPTS POS): provisioning, custody, swap/repair, `card_terminals` link, merchant devices view | TC-POS — **first business value** |
| I4 | Procurement: parties, vendor price lists, requisition/PO/approval/GRN, 3-way match, landed cost | TC-P2S |
| I5 | Sales + trade links: price lists, SO, allocation, dispatch, linked PO↔SO, disputes, RMA, warranty | TC-O2D |
| I6 | Costing: average/FIFO/standard, valuation journal, period close | valuation tests |
| I7 | Manufacturing + quality: BOM/routing/WO/backflush/subcontract; inspections/holds/NCR/recall; genealogy | TC-MFG, TC-QA |
| I8 | Warehouse: bins, putaway, pick/pack waves, labels, offline scanner | widget + Playwright |
| I9 | Planning + network: MRP, forecast, consignment/VMI, network stock view | TC-PLN |
| I10 | Core org-to-org invoicing (D-INV-3) wired through `TradeInvoicePort`; device sales invoiced | payments review |
| I11 | Extraction readiness review (volume, isolation); optional move to a trustplane mesh service | decision |

Each phase ships behind its pack (grants are the switch) and to a pilot manufacturer first.

---

## 14. Decisions needed

| # | Question | Recommendation |
|---|---|---|
| D-INV-1 | Can a manufacturer grant/withhold packs to its own children, or only TPTS? | TPTS only in v1; revisit for reseller model |
| D-INV-2 | Is the network stock share default-on for children under a manufacturer `SUBTREE` grant? | No: explicit share per child, but the grant config can **require** it at child onboarding |
| D-INV-3 | Generalise invoices to org-to-org (seller/buyer org ids) in core payments? | Yes, as I10, with its own payments/ledger review |
| D-INV-4 | Do standalone merchants get `stock` by default at go-live? | Yes (`SELF stock`), keeps today's merchant feature |
| D-INV-5 | Should the plugin's customer credit limit consult MiPay credit/advance products? | Read-only consult only; plugin limit decides |
| D-INV-6 | Modular monolith now, mesh service later (§4.1)? | Yes. The database is separated from day one (own schema, role, pool, migrations, §4.8) |
| D-INV-9 | Separate schema on the core's Postgres, or a separate database from the start? | Separate schema on the same server by default; a separate database is a config change (`INVENTORY_DB_URL`) and is tested in CI |
| D-INV-7 | Default cost method per org type | Moving average; standard cost opt-in for manufacturers |
| D-INV-8 | Lateral trade between siblings under one manufacturer | Off by default; manufacturer grant config switches it on |

## 15. Changes to other documents when accepted
- `inventory-plugin.md`: marked superseded (done with this draft).
- `plugins/README.md`: framework link points here; Procurement is a pack of this plugin.
- `master-plan.md` §1 row and §3 step 4 point here.
- `grid.yaml` + role pack: `inv.*` cells and roles (I0).
- `portal-builder.md`: inventory widgets join the widget backlog after KYC.
