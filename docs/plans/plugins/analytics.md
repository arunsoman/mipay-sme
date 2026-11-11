# Plugin: Analytics

Dashboards for a subtree: sales by distributor, stock age, how many POS terminals are active.

## Benefits of a plugin over building it into the core
- **Read-only and event-fed:** it can be backed by its own store later, without touching the core.
- **Roll-ups respect data sharing:** parents see child data only where the child has shared it.
- **Sold in tiers:** basic and advanced dashboards as separate grants.
- **Heavy queries kept apart:** expensive aggregation never slows payments.
