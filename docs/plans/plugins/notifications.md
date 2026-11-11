# Plugin: Notifications

Sends SMS, WhatsApp, email and push messages from templates, for every other plugin and for core events.

## Benefits of a plugin over building it into the core
- **One channel layer:** it replaces the copies of `sendRemind`-style code in several controllers. Every plugin sends through one interface.
- **Swappable providers:** sandbox simulation, a real SMS gateway or WhatsApp can be changed without touching business code.
- **Per-org templates and sender IDs:** a manufacturer's network can use its own wording and branding.
- **Cost control:** messaging can be enabled, rate-limited or billed per org.
- **Easy to audit:** one outbox holds every message, with the SIMULATED label applied in one place.
