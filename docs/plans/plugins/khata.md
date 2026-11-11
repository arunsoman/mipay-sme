# Plugin: Khata (customer credit book)

An informal credit ledger an org keeps for its own customers: charges, payments, reminders.

## Benefits of a plugin over building it into the core
- **Not merchant-only:** today it is tied to merchants. As a plugin, suppliers and distributors can also track the credit they give their buyers.
- **Inherited down the tree:** a distributor can turn it on for its whole merchant network with one grant.
- **Isolated from the money core:** informal credit records can't affect the balance ledgers by accident.
- **Optional:** orgs that don't give credit don't see it, which keeps their app simpler.
- **Reuses Notifications:** payment reminders go through the shared channel instead of their own code.
