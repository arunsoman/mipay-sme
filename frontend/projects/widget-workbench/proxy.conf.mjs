/**
 * Dev-server proxy for the widget workbench (docs/plans/portal-builder.md §10).
 *
 * Fixtures mode needs nothing. For a real API, the developer picks a service from
 * api-services.json (WB_SERVICE=<id>, else its "default"). /api and /auth are forwarded to that
 * service's gateway AS the service's portal host (X-Forwarded-Host): the gateway - the FAPI 2.0
 * client (BFF) - runs PAR, PKCE, DPoP-bound tokens and refresh for that host exactly as it does
 * for the real portal, and the browser only ever holds the portal's HttpOnly session cookie.
 * Cookies ignore ports, so https://<portalHost>:4300 shares the portal's session.
 *
 * The chosen service is also written to public/wb-service.json for the harness's Connection
 * panel (git-ignored, regenerated on every start).
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const config = JSON.parse(fs.readFileSync(path.join(here, 'api-services.json'), 'utf8'));
const id = process.env.WB_SERVICE || config.default;
const service = config.services.find((s) => s.id === id);
if (!service) {
  throw new Error(`WB_SERVICE=${id} is not in api-services.json (${config.services.map((s) => s.id).join(', ')})`);
}
// only `npm run workbench:live` (WB_LIVE=1) announces a service; plain `npm run workbench` is fixtures only
const announce = path.join(here, 'public/wb-service.json');
fs.mkdirSync(path.join(here, 'public'), { recursive: true });
if (process.env.WB_LIVE === '1') {
  fs.writeFileSync(announce, JSON.stringify({ ...service, services: config.services.map(({ id: i, label }) => ({ id: i, label })) }, null, 2) + '\n');
  console.log(`[workbench] live API service "${service.id}": open https://${service.portalHost}:4300 (gateway ${service.gateway})`);
} else {
  fs.rmSync(announce, { force: true });
}

const forward = {
  target: service.gateway,
  secure: service.tlsVerify !== false,
  changeOrigin: false,
  headers: { 'X-Forwarded-Host': service.portalHost, 'X-Forwarded-Proto': 'https' },
  // the gateway names its TLS certificate after the portal host
  servername: service.portalHost,
};

export default { '/api': forward, '/auth': forward };
