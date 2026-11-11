# Plugin: Partner Webhooks & API

Lets a partner's ERP subscribe to events in its subtree, through the existing `ApiClient` model.

## Benefits of a plugin over building it into the core
- **Integration on request:** only partners that integrate get API keys and webhooks.
- **Subtree-scoped by design:** a partner only receives events for its own network.
- **Delivery retries kept out of the request path:** with their own outbox.
- **Versioned on its own:** event formats evolve without breaking the core.
