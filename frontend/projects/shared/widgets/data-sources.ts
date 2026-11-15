/**
 * Every logical source and command a widget may call, mapped to a request per portal
 * ('*' = all portals). Widgets name these; they never hold URLs. The registry tool resolves
 * each widget's routes from here, and the registry server checks every route against the
 * backend's @Perm endpoint manifest (decision 6).
 *
 * Add an entry here when `widgets:check` reports "unmapped".
 */
import { defineSources } from './core/sources';

export const SOURCES = defineSources({
});
