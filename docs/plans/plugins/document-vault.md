# Plugin: Document Vault

Stores org documents (licences, contracts, IDs) with expiry alerts. Onboarding can require certain documents per org type.

## Benefits of a plugin over building it into the core
- **Sensitive files isolated:** storage, encryption and retention live in one module.
- **Requirements per org type:** each type's document list is configured, not coded into onboarding.
- **Expiry alerts reuse Notifications:** no new messaging code.
- **Storage backend can change:** database, S3 or another store, without affecting the core.
