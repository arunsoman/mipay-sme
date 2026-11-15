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
  // ---------------- KYC (KycController) - same URL in every portal; context picks the org
  'kyc.application.get':      { '*': { method: 'GET',  path: '/api/kyc/orgs/{orgId}' } },
  'kyc.schema.get':           { '*': { method: 'GET',  path: '/api/kyc/schema/{orgId}' } },
  'kyc.list.get':             { '*': { method: 'GET',  path: '/api/kyc/lists/{code}', query: ['parent'] } },
  'kyc.application.save':     { '*': { method: 'PUT',  path: '/api/kyc/orgs/{orgId}', query: ['expectedVersion'] } },
  'kyc.application.submit':   { '*': { method: 'POST', path: '/api/kyc/orgs/{orgId}/submit' } },
  'kyc.application.withdraw': { '*': { method: 'POST', path: '/api/kyc/orgs/{orgId}/withdraw' } },
  // review queue (KycController#inbox, #decide)
  'kyc.tasks.list':           { '*': { method: 'GET',  path: '/api/kyc/tasks' } },
  'kyc.tasks.decide':         { '*': { method: 'POST', path: '/api/kyc/tasks/{taskId}/decision' } },
});
