# Plugin: Field Service

Tickets for device and installation issues: raise, assign a technician, swap or repair, close. Uses Inventory unit history.

## Benefits of a plugin over building it into the core
- **Natural follow-on to POS terminals:** the platform uses it for terminal support with no change to the payment core.
- **Same tool for manufacturers:** warranty claims across a manufacturer's network use the same engine with a `SUBTREE` grant.
- **Declared dependency on Inventory:** it is only granted where Inventory is on, so device history is always available.
- **Its own SLAs and workflows:** each org sets these without changing platform code.
