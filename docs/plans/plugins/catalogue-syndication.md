# Plugin: Catalogue Syndication

A manufacturer publishes its product catalogue (SKU, images, recommended price) once; the orgs below it inherit it.

## Benefits of a plugin over building it into the core
- **No retyping down the chain:** every tier reuses the same SKUs, which also keeps Inventory data consistent.
- **The tree is the distribution list:** a `SUBTREE` grant defines who receives the catalogue.
- **Versioned on its own:** catalogue updates roll out without platform releases.
- **Supplier catalogue stays simple:** the core keeps its current supplier catalogue for orgs that don't need syndication.
