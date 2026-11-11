# Plugin: Pricing & Promotions

Price lists per tier (manufacturer → distributor → merchant), volume discounts and time-limited promotions.

## Benefits of a plugin over building it into the core
- **Fits the tree:** a manufacturer sets prices once, and its `SUBTREE` grant pushes them down the network.
- **Commercial rules stay out of the payment core:** the core receives the final price and nothing else.
- **Per-org pricing strategies:** without forking the platform code.
- **Promotions are isolated:** a misconfigured promotion can't affect settlement or the ledgers.
