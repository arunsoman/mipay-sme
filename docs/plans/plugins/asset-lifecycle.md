# Plugin: Asset Lifecycle

Rented or loaned assets placed at other orgs (fridges, display stands, scales, POS terminals): deposits, condition checks, recovery.

## Benefits of a plugin over building it into the core
- **One pattern for every asset type:** it generalizes POS provisioning instead of building a special feature per asset.
- **Built on Inventory custody:** placing an asset at another org reuses inventory's custody records.
- **Inherited down the tree:** a beverage distributor can track fridges across all its merchants with one grant.
- **Deposits go through the money interface:** money handling stays under the core ledger rules.
